package de.aichele.childlauncher;

import com.google.gson.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

public final class FabricLauncherPlugin extends JavaPlugin {
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
    private Upstream upstream;
    private final ExecutorService exec = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "ServerhosterPlugin"); t.setDaemon(true); return t; });
    private final Map<String, ChildSlot> slots = new ConcurrentHashMap<>();
    private int consoleCap = 4000;
    private volatile List<ServerProfile> profiles = List.of();
    private volatile String focus = "default";
    private final Object childGate = new Object();
    private final Object configMutationLock = new Object();
    private final Map<String, String> taskFired = new ConcurrentHashMap<>();
    private final Map<String, String> taskLast = new ConcurrentHashMap<>();
    private final Set<String> activeSchedules = ConcurrentHashMap.newKeySet();
    private record NtfySetup(boolean enabled, String server, String topic) {}
    volatile String lastBackup = "";
    private final Map<String, ProcessProbe.Disk> diskCaches = new ConcurrentHashMap<>();
    private final Map<String, Long> diskCacheAt = new ConcurrentHashMap<>();
    private final Map<String, List<String>> versionCache = new ConcurrentHashMap<>();
    private final Map<String, Long> versionCacheAt = new ConcurrentHashMap<>();
    private final Set<String> memoryBudgetWarnings = ConcurrentHashMap.newKeySet();
    private final Deque<MemoryIncidentReport.Sample> memoryHistory = new ArrayDeque<>();
    private final MemoryProtectionPolicy memoryProtectionPolicy = new MemoryProtectionPolicy();
    private volatile boolean shuttingDown = false;
    private volatile HostMemoryProbe.Snapshot hostMemory;
    private volatile long recoveryBlockedSince;
    private volatile boolean hostRecoveryStarted;
    private volatile boolean autoStartInitialized;
    private volatile boolean setupRequired;
    private volatile String lastMemoryIncidentReport = "";
    private long lastMemoryWarningAt;
    private Dashboard dashboard;
    private Thread shutdownHook;
    private final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "ServerhosterPlugin-Save"); t.setDaemon(true); return t; });
    private final Object stopLock = new Object();
    private static final java.util.regex.Pattern JOIN = java.util.regex.Pattern.compile("\\]: (\\w{1,16}) joined the game");
    private static final java.util.regex.Pattern LEFT = java.util.regex.Pattern.compile("\\]: (\\w{1,16}) left the game");
    private static final java.util.regex.Pattern LIST = java.util.regex.Pattern.compile("There are \\d+ of a max of \\d+ players online:(.*)$");

    // ---- outer (Spigot) stats, sampled on the main thread once per second ----
    volatile double outerTps = 20, outerMspt = 0; volatile int outerChunks, outerEntities, outerWorlds;
    private long lastSampleNs; private final long[] tickNs = new long[20]; private int tickIdx; private long tickStart;
    // ---- child tick stats (from 'tick query') ----
    @Override public void onEnable() {
        saveDefaultConfig();
        migrateRestartPolicy();
        setupRequired = !getConfig().getBoolean("setup.completed", false);
        applyCurrentMemorySettings();
        consoleCap = Math.max(200, Math.min(getConfig().getInt("consoleBufferLines", 4000), 20_000));
        upstream = new Upstream(http, "Sciddie/minecraft-serverhoster-plugin/" + getDescription().getVersion(), m -> getLogger().info(m));
        reloadProfiles();
        startSampler();
        makeWritable(getDataFolder().toPath(), true);
        makeWritable(getDataFolder().toPath().resolve("config.yml"), false);
        recoverPendingSetupDirectories();
        loadMemoryRecoveryState();
        loadLatestMemoryIncidentReport();
        sampleHostMemory();
        if (getConfig().getBoolean("dashboard.enabled", true)) {
            try { dashboard = new Dashboard(this); dashboard.start(); }
            catch (Exception e) { getLogger().log(Level.SEVERE, "Dashboard failed to start", e); }
        }

        // JVM shutdown hook: covers SIGTERM / Zap restart where onDisable might not run
        shutdownHook = new Thread(() -> {
            shuttingDown = true;
            for (ChildSlot s : slots.values()) { s.wantRunning = false; s.stopRequested = true; }
            for (ChildSlot s : List.copyOf(slots.values())) if (s.running()) {
                System.out.println("[ServerhosterPlugin] JVM shutdown hook: stopping " + s.id);
                stopProcess(s, getConfig().getLong("shutdownHookTimeoutSeconds", 25), true);
            }
        }, "ServerhosterPlugin-ShutdownHook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        long every = getConfig().getLong("saveAllIntervalMinutes", 5);
        if (every > 0) sched.scheduleAtFixedRate(() -> { for (ChildSlot s : slots.values()) if (s.running()) send(s, "save-all"); }, every, every, TimeUnit.MINUTES);
        sched.scheduleAtFixedRate(this::fireDueTasks, 20, 20, TimeUnit.SECONDS);
        sched.scheduleAtFixedRate(this::fireDueSchedules, 20, 20, TimeUnit.SECONDS);
        long memoryInterval = Math.max(15, getConfig().getLong("memoryProtection.sampleIntervalSeconds", 30));
        sched.scheduleAtFixedRate(this::sampleHostMemory, memoryInterval, memoryInterval, TimeUnit.SECONDS);
        if (setupRequired) {
            getLogger().warning("Setup is required. The child server will not start until the dashboard setup is confirmed.");
        } else if (recoveryBlockedSince == 0) {
            for (ServerProfile p : profiles) if (p.autoStart) startAsync(null, p.id);
        } else {
            getLogger().warning("Previous memory emergency detected; automatic child start is paused until memory is stable.");
        }
        autoStartInitialized = true;
        NtfyNotifier.Settings ntfy = ntfySettings();
        if (ntfy.enabled() && (ntfy.topic() == null || ntfy.topic().isBlank())) {
            getLogger().warning("ntfy notifications are enabled but notifications.ntfy.topic is empty; notifications are disabled until a topic is configured.");
        }
    }

    private void migrateRestartPolicy() {
        boolean migratedUnlimited = RestartPolicy.migrateLegacyUnlimited(
                getConfig().get("autoRestart.maxAttempts"));
        boolean changed = migrateLegacyRestartPolicy(getConfig(), getDescription().getVersion());
        if (migratedUnlimited)
            getLogger().warning("Migrated the legacy unlimited restart setting to 5 automatic attempts. Change this under Dashboard > Einstellungen if needed.");
        if (changed) saveConfigWritable();
    }

    static boolean migrateLegacyRestartPolicy(FileConfiguration config, String currentVersion) {
        String previousVersion = config.getString("metadata.pluginVersion", "");
        boolean migratedUnlimited = RestartPolicy.migrateLegacyUnlimited(
                config.get("autoRestart.maxAttempts"));
        if (migratedUnlimited)
            config.set("autoRestart.maxAttempts", RestartPolicy.DEFAULT_MAX_ATTEMPTS);
        boolean changed = !currentVersion.equals(previousVersion);
        if (changed) config.set("metadata.pluginVersion", currentVersion);
        return changed || migratedUnlimited;
    }

    private NtfyNotifier.Settings ntfySettings() {
        FileConfiguration c = getConfig();
        return new NtfyNotifier.Settings(c.getBoolean("notifications.ntfy.enabled", false),
                c.getString("notifications.ntfy.server", "https://ntfy.sh"),
                c.getString("notifications.ntfy.topic", ""));
    }

    private void notifyNtfy(String title, String message, String priority) {
        NtfyNotifier.Settings settings = ntfySettings();
        if (!settings.enabled() || settings.topic() == null || settings.topic().isBlank()) return;
        try {
            exec.submit(() -> {
                try {
                    BackupSecrets.Credentials credentials = backupCredentials();
                    NtfyNotifier.send(settings, credentials.ntfyToken(), title, message, priority);
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Could not send ntfy notification: " + e.getMessage(), e);
                }
            });
        } catch (RejectedExecutionException e) {
            getLogger().log(Level.WARNING, "Could not queue ntfy notification because the plugin is shutting down.", e);
        }
    }

    @Override public void onDisable() {
        shuttingDown = true;
        for (ChildSlot s : slots.values()) { s.wantRunning = false; s.stopRequested = true; }
        for (ChildSlot s : List.copyOf(slots.values())) stopBlocking(s);
        sched.shutdownNow();
        try { if (shutdownHook != null) Runtime.getRuntime().removeShutdownHook(shutdownHook); } catch (IllegalStateException ignored) { /* JVM already shutting down; hook runs/ran */ }
        if (dashboard != null) dashboard.stop();
        exec.shutdownNow();
    }

    /** chmod rw for group/other (best effort; FTP user usually differs from the server process user). */
    static void makeWritable(Path p, boolean dir) {
        try { Files.setPosixFilePermissions(p, java.nio.file.attribute.PosixFilePermissions.fromString(dir ? "rwxrwxrwx" : "rw-rw-rw-")); } catch (Exception ignored) {}
    }

    void saveConfigWritable() {
        synchronized (configMutationLock) {
            saveConfig();
            makeWritable(getDataFolder().toPath().resolve("config.yml"), false);
        }
    }

    Map<String, Object> settingsView() {
        return ServerHosterSettings.from(getConfig()).view();
    }

    void saveSettings(ServerHosterSettings settings) {
        synchronized (configMutationLock) {
            settings.apply(getConfig());
            saveConfigWritable();
        }
    }

    Object configMutationLock() { return configMutationLock; }

    // ---------------- public control API (used by commands + dashboard) ----------------
    void reloadProfiles() {
        Map<String, Object> flat = getConfig().getValues(true);
        List<ServerProfile> configured = Profiles.load(flat);
        ServerProfile selected = configured.getFirst();
        profiles = List.of(selected);
        focus = selected.id;
        slot(selected.id).profile = selected;
    }

    String focusId() { return focus; }
    List<ServerProfile> profiles() { return profiles; }
    ServerProfile profile(String id) {
        String key = id == null || id.isBlank() ? focus : id;
        for (ServerProfile p : profiles) if (p.id.equals(key)) return p;
        throw new IllegalArgumentException("unknown server " + key);
    }

    ChildSlot slot(String id) {
        String key = id == null || id.isBlank() ? focus : id;
        profile(key);
        return slots.computeIfAbsent(key, k -> new ChildSlot(k, consoleCap));
    }

    private ChildSlot focusSlot() { return slot(focus); }
    boolean running() { return focusSlot().running(); }
    boolean running(String id) { return slot(id).running(); }
    long pid() { return focusSlot().pid(); }
    long pid(String id) { return slot(id).pid(); }
    long uptimeMillis() { return focusSlot().uptimeMillis(); }
    long uptimeMillis(String id) { return slot(id).uptimeMillis(); }
    String lastStatus() { return focusSlot().lastStatus; }
    String lastStatus(String id) { return slot(id).lastStatus; }
    ConsoleBuffer console() { return focusSlot().console; }
    ConsoleBuffer console(String id) { return slot(id).console; }
    Set<String> players() { return focusSlot().onlinePlayers; }
    Set<String> players(String id) { return slot(id).onlinePlayers; }
    boolean sendCommand(String c) { return sendCommand(focus, c); }
    boolean sendCommand(String id, String c) { if (c.startsWith("/")) c = c.substring(1); return send(slot(id), c); }
    void requestStart() { requestStart(focus); }
    void requestStop() { requestStop(focus); }
    void requestRestart() { requestRestart(focus); }
    void requestStart(String id) {
        startAsync(null, id);
    }

    private void requestStart(CommandSender sender, String id) {
        startAsync(sender, id);
    }
    void requestStop(String id) {
        ChildSlot s = slot(id);
        s.wantRunning = false;
        s.stopRequested = true;
        exec.submit(() -> stopBlocking(s));
    }
    void requestRestart(String id) { exec.submit(() -> { ChildSlot s = slot(id); s.wantRunning = false; stopBlocking(s); s.attempts = 0; reloadConfig(); reloadProfiles(); startAsync(null, id); }); }
    void focus(String id) {
        if (!focus.equals(id)) throw new IllegalArgumentException("ServerHoster verwaltet genau einen Child-Server");
    }
    void info(String m) { info(focus, m); }
    void info(String id, String m) { getLogger().info(m); slot(id).console.add("[ServerhosterPlugin] " + m); }

    /** Writes managed server.properties keys back into config so the next start keeps the edit. */
    boolean syncPropertiesEdit(String id, java.util.Properties p) {
        ServerProfile pr = profile(id);
        String prefix = configPrefix(pr);
        boolean changed = false;
        for (String k : p.stringPropertyNames()) {
            String cur = pr.serverProperties.get(k);
            if (cur != null && !p.getProperty(k).equals(cur)) {
                getConfig().set(prefix + "serverProperties." + k, parseValue(p.getProperty(k)));
                changed = true;
            }
        }
        String port = p.getProperty("server-port");
        if (port != null && !port.trim().equals(String.valueOf(pr.port))) {
            try { getConfig().set(prefix + "port", Integer.parseInt(port.trim())); changed = true; } catch (NumberFormatException ignored) {}
        }
        String ip = p.getProperty("server-ip");
        if (ip != null && !ip.equals(pr.serverIp == null ? "" : pr.serverIp)) {
            getConfig().set(prefix + "serverIp", ip);
            changed = true;
        }
        if (changed) { saveConfigWritable(); reloadProfiles(); }
        return changed;
    }

    private String configPrefix(ServerProfile pr) {
        return profiles.isEmpty() || profiles.get(0).id.equals(pr.id) ? "" : "servers." + pr.id + ".";
    }

    boolean setupRequired() { return setupRequired; }

    Map<String, Object> setupView() throws IOException {
        Path root = getWorldRoot();
        Path plugins = getDataFolder().toPath().getParent();
        Path oldData = plugins.resolve("FabricLauncher");
        Path oldConfig = oldData.resolve("config.yml");
        List<SetupDiscovery.Candidate> found = SetupDiscovery.scan(root, oldConfig);
        List<Map<String, Object>> candidates = found.stream()
                .map(SetupDiscovery.Candidate::view).toList();
        List<String> oldJars = legacyJarNames(plugins);
        FileConfiguration ntfySource = found.stream().anyMatch(SetupDiscovery.Candidate::legacyConfigured)
                ? SetupDiscovery.loadLegacyConfig(oldConfig) : getConfig();
        Map<String, Object> ntfy = Map.of(
                "enabled", ntfySource.getBoolean("notifications.ntfy.enabled", false),
                "server", ntfySource.getString("notifications.ntfy.server", "https://ntfy.sh"),
                "topic", ntfySource.getString("notifications.ntfy.topic", ""));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("required", setupRequired);
        result.put("completed", !setupRequired);
        result.put("legacyDataFound", Files.isRegularFile(oldConfig));
        result.put("candidates", candidates);
        result.put("cleanupAvailable", Files.isDirectory(oldData, LinkOption.NOFOLLOW_LINKS) || !oldJars.isEmpty());
        result.put("legacyJars", oldJars);
        result.put("ntfy", ntfy);
        return result;
    }

    synchronized void cleanupLegacyFiles(String confirm) throws IOException {
        if (setupRequired) throw new IllegalStateException("Die Einrichtung muss vor der Bereinigung abgeschlossen sein");
        if (!"DELETE FABRICLAUNCHER".equals(confirm))
            throw new IllegalArgumentException("Die Bereinigungsbestätigung stimmt nicht überein");
        Path plugins = getDataFolder().toPath().getParent();
        List<String> oldJars = legacyJarNames(plugins);
        Path oldData = plugins.resolve("FabricLauncher");
        if (Files.exists(oldData, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(oldData) || !Files.isDirectory(oldData, LinkOption.NOFOLLOW_LINKS))
                throw new SecurityException("Der alte Plugin-Datenordner ist kein sicherer regulärer Ordner");
            Set<Path> preserved = legacyDataToPreserve(oldData);
            cleanupLegacyDirectory(oldData, preserved);
            for (Path path : preserved)
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS))
                    getLogger().info("Retaining legacy data during cleanup: " + path);
        }
        for (String name : oldJars) Files.delete(plugins.resolve(name));
    }

    private Set<Path> legacyDataToPreserve(Path oldData) throws IOException {
        Path normalizedOldData = oldData.toAbsolutePath().normalize();
        Set<Path> preserved = new HashSet<>();
        addLegacyDataToPreserve(preserved, normalizedOldData.resolve("backups"));
        addLegacyDataToPreserve(preserved, normalizedOldData.resolve("memory-reports"));
        addLegacyDataToPreserve(preserved, normalizedOldData.resolve("memory-recovery.txt"));

        String configuredBackup = getConfig().getString("backup.directory", "");
        if (configuredBackup == null || configuredBackup.isBlank()) {
            addLegacyDataToPreserve(preserved, normalizedOldData.resolve("backups"));
        } else {
            Path backupDirectory = Paths.get(configuredBackup);
            if (!backupDirectory.isAbsolute()) backupDirectory = Paths.get("").toAbsolutePath().resolve(backupDirectory);
            addLegacyDataToPreserve(preserved, backupDirectory);
        }
        return Set.copyOf(preserved);
    }

    private static void addLegacyDataToPreserve(Set<Path> preserved, Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized))
            throw new SecurityException("Refusing to clean legacy data containing a symbolic-link backup or report path: " + normalized);
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) preserved.add(normalized);
    }

    static void cleanupLegacyDirectory(Path oldData, Set<Path> preserved) throws IOException {
        Path root = oldData.toAbsolutePath().normalize();
        Set<Path> normalizedPreserved = preserved.stream().map(p -> p.toAbsolutePath().normalize())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override public java.nio.file.FileVisitResult preVisitDirectory(
                    Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                Path normalized = dir.toAbsolutePath().normalize();
                if (!normalized.equals(root) && normalizedPreserved.stream().anyMatch(path ->
                        path.startsWith(normalized) || normalized.startsWith(path)))
                    return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override public java.nio.file.FileVisitResult visitFile(
                    Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Path normalized = file.toAbsolutePath().normalize();
                if (normalizedPreserved.stream().noneMatch(path ->
                        path.equals(normalized) || path.startsWith(normalized) || normalized.startsWith(path)))
                    Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Path normalized = dir.toAbsolutePath().normalize();
                if (normalized.equals(root) && normalizedPreserved.stream().anyMatch(path ->
                        path.startsWith(root) && Files.exists(path, LinkOption.NOFOLLOW_LINKS)))
                    return java.nio.file.FileVisitResult.CONTINUE;
                if (normalizedPreserved.stream().noneMatch(path ->
                        path.startsWith(normalized) || normalized.startsWith(path)))
                    Files.delete(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private List<String> legacyJarNames(Path plugins) throws IOException {
        List<String> jars = new ArrayList<>();
        if (!Files.isDirectory(plugins, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(plugins, "FabricLauncher*.jar")) {
            for (Path entry : entries) {
                if (Files.isSymbolicLink(entry) || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
                    throw new SecurityException("Unsichere alte Plugin-Datei: " + entry);
                if (jars.size() >= 16) throw new IOException("Zu viele alte Plugin-JARs zur Bereinigung");
                jars.add(entry.getFileName().toString());
            }
        }
        return List.copyOf(jars);
    }

    synchronized Map<String, Object> configureSetup(String action, String path, String type, String mcVersion,
                                       int port, String confirm, boolean ntfyEnabled, String ntfyServer,
                                       String ntfyTopic) throws IOException {
        if (!setupRequired) throw new IllegalStateException("Die Einrichtung ist bereits abgeschlossen");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Ungültiger Spielport");
        if (!Set.of("vanilla", "fabric", "paper", "spigot", "custom").contains(type))
            throw new IllegalArgumentException("Ungültiger Servertyp");
        if (mcVersion == null || !mcVersion.matches("[A-Za-z0-9._+-]{1,64}"))
            throw new IllegalArgumentException("Ungültige Minecraft-Version");
        NtfySetup ntfySetup = validateNtfySetup(ntfyEnabled, ntfyServer, ntfyTopic);

        Path root = getWorldRoot();
        Path childDirectory;
        Path rollbackSource = null;
        Path rollbackTarget = null;
        Path pendingDelete = null;
        Path pendingArchive = null;
        Path finalArchive = null;
        if (action.equals("import")) {
            Path oldConfig = getDataFolder().toPath().getParent().resolve("FabricLauncher").resolve("config.yml");
            SetupDiscovery.Candidate candidate = SetupDiscovery.scan(root, oldConfig).stream()
                    .filter(item -> item.path().toString().equals(Path.of(path).toAbsolutePath().normalize().toString()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Der erkannte Serverordner ist nicht mehr verfügbar"));
            if (!candidate.legacyConfigured() || candidate.profile() == null)
                throw new IllegalArgumentException("Für diesen Ordner wurde keine importierbare Alt-Konfiguration gefunden");
            if (candidate.legacyVersion() == null && !"IMPORT 1.10.1+".equals(confirm))
                throw new IllegalArgumentException("Die alte Plugin-Version ist unbekannt. Bestätige, dass die Konfiguration aus ServerHoster 1.10.1 oder neuer stammt.");
            importLegacyConfiguration(oldConfig, candidate.profile());
            childDirectory = candidate.path();
        } else if (action.equals("new")) {
            childDirectory = SetupDiscovery.resolveNewDirectory(root, path);
            if (Files.exists(childDirectory) && Files.isSymbolicLink(childDirectory))
                throw new IllegalArgumentException("Serverordner darf kein symbolischer Link sein");
            applySetupProfile(childDirectory, type, mcVersion, port);
        } else if (action.equals("attach")) {
            childDirectory = findSetupCandidate(root, path).path();
            applySetupProfile(childDirectory, type, mcVersion, port);
        } else if (action.equals("reset")) {
            SetupDiscovery.Candidate candidate = findSetupCandidate(root, path);
            String expected = "ARCHIVIEREN " + candidate.path().getFileName();
            if (!expected.equals(confirm)) throw new IllegalArgumentException("Die Archivierungsbestätigung stimmt nicht überein");
            if (!SetupDiscovery.isSafeArchiveTarget(root, candidate.path()))
                throw new IllegalArgumentException("Dieser Ordner kann aus Sicherheitsgründen nicht archiviert werden");
            finalArchive = candidate.path().resolveSibling(candidate.path().getFileName() + ".serverhoster-archive-"
                    + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneId.systemDefault())
                    .format(java.time.Instant.now()) + "-" + java.util.UUID.randomUUID().toString().substring(0, 8));
            pendingArchive = candidate.path().resolveSibling(candidate.path().getFileName()
                    + ".serverhoster-reset-pending-" + java.util.UUID.randomUUID());
            Files.move(candidate.path(), pendingArchive);
            rollbackSource = pendingArchive;
            rollbackTarget = candidate.path();
            childDirectory = SetupDiscovery.resolveNewDirectory(root, root.relativize(candidate.path()).toString());
            applySetupProfile(childDirectory, type, mcVersion, port);
        } else if (action.equals("delete")) {
            SetupDiscovery.Candidate candidate = findSetupCandidate(root, path);
            String expected = "DELETE " + candidate.path().getFileName();
            if (!expected.equals(confirm)) throw new IllegalArgumentException("Die Löschbestätigung stimmt nicht überein");
            if (!SetupDiscovery.isSafeArchiveTarget(root, candidate.path()))
                throw new IllegalArgumentException("Dieser Ordner kann aus Sicherheitsgründen nicht gelöscht werden");
            String suffix = ".serverhoster-delete-pending-" + java.util.UUID.randomUUID();
            pendingDelete = candidate.path().resolveSibling(candidate.path().getFileName() + suffix);
            Files.move(candidate.path(), pendingDelete);
            rollbackSource = pendingDelete;
            rollbackTarget = candidate.path();
            childDirectory = SetupDiscovery.resolveNewDirectory(root, root.relativize(candidate.path()).toString());
            applySetupProfile(childDirectory, type, mcVersion, port);
        } else {
            throw new IllegalArgumentException("Unbekannte Einrichtungsaktion");
        }
        getConfig().set("notifications.ntfy.enabled", ntfySetup.enabled());
        getConfig().set("notifications.ntfy.server", ntfySetup.server());
        getConfig().set("notifications.ntfy.topic", ntfySetup.topic());

        getConfig().set("workingDir", root.relativize(childDirectory).toString().replace('\\', '/'));
        getConfig().set("setup.completed", true);
        getConfig().set("servers", null);
        getConfig().set("metadata.schemaVersion", 2);
        getConfig().set("metadata.pluginVersion", getDescription().getVersion());
        getConfig().set("metadata.migrationState", "complete");
        try {
            saveSetupConfigAtomically();
        } catch (IOException | RuntimeException e) {
            if (rollbackSource != null && Files.exists(rollbackSource, LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(rollbackTarget, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.move(rollbackSource, rollbackTarget);
                } catch (IOException rollbackError) {
                    e.addSuppressed(rollbackError);
                    getLogger().log(Level.SEVERE, "Setup config save failed and the child directory could not be restored from " + rollbackSource, rollbackError);
                }
            }
            reloadConfig();
            reloadProfiles();
            throw e;
        }
        reloadConfig();
        reloadProfiles();
        setupRequired = false;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("path", childDirectory.toString());
        result.put("action", action);
        if (pendingDelete != null) {
            try {
                cleanupLegacyDirectory(pendingDelete, Set.of());
            } catch (IOException e) {
                String warning = "Setup is saved, but confirmed child deletion could not finish; remaining data is at " + pendingDelete;
                getLogger().log(Level.SEVERE, warning, e);
                result.put("warning", warning);
            }
        }
        if (pendingArchive != null) {
            try {
                Files.move(pendingArchive, finalArchive);
            } catch (IOException e) {
                String warning = "Setup is saved, but the archived child remains at " + pendingArchive;
                getLogger().log(Level.SEVERE, warning, e);
                result.put("warning", warning);
            }
        }
        return result;
    }

    private void saveSetupConfigAtomically() throws IOException {
        Path configFile = getDataFolder().toPath().toAbsolutePath().normalize().resolve("config.yml");
        Path temporary = configFile.resolveSibling("config.yml.setup-tmp");
        if (Files.isSymbolicLink(configFile) || Files.isSymbolicLink(temporary))
            throw new SecurityException("Setup config files must not be symbolic links");
        try {
            getConfig().save(temporary.toFile());
            try {
                Files.move(temporary, configFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, configFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            makeWritable(configFile, false);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private NtfySetup validateNtfySetup(boolean enabled, String server, String topic) {
        java.net.URI uri;
        try {
            uri = java.net.URI.create(server == null ? "" : server.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Ungültige ntfy-Server-URL", e);
        }
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null)
            throw new IllegalArgumentException("ntfy-Server muss eine HTTP(S)-URL ohne Zugangsdaten, Query oder Fragment sein");
        String normalizedTopic = topic == null ? "" : topic.trim();
        if (normalizedTopic.length() > 128 || (!normalizedTopic.isEmpty()
                && !normalizedTopic.matches("[A-Za-z0-9_-]+")))
            throw new IllegalArgumentException("ntfy-Thema darf nur Buchstaben, Ziffern, _ und - enthalten");
        if (enabled && normalizedTopic.isEmpty())
            throw new IllegalArgumentException("Für ntfy muss ein Thema angegeben werden");
        return new NtfySetup(enabled, uri.toString().replaceAll("/+$", ""), normalizedTopic);
    }

    private SetupDiscovery.Candidate findSetupCandidate(Path root, String path) throws IOException {
        Path oldConfig = getDataFolder().toPath().getParent().resolve("FabricLauncher").resolve("config.yml");
        Path selected = Path.of(path).toAbsolutePath().normalize();
        return SetupDiscovery.scan(root, oldConfig).stream()
                .filter(item -> item.path().equals(selected))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Der erkannte Serverordner ist nicht mehr verfügbar"));
    }

    private void applySetupProfile(Path directory, String type, String mcVersion, int port) {
        getConfig().set("type", type);
        getConfig().set("mcVersion", mcVersion);
        getConfig().set("port", port);
        getConfig().set("workingDir", directory.toString());
        getConfig().set("autoStart", true);
        getConfig().set("serverProperties", Map.of());
    }

    private void importLegacyConfiguration(Path oldConfig, ServerProfile profile) throws IOException {
        Path oldData = oldConfig.getParent().toAbsolutePath().normalize();
        if (Files.isSymbolicLink(oldData))
            throw new SecurityException("The legacy plugin data directory must not be a symbolic link");
        org.bukkit.configuration.file.FileConfiguration old = SetupDiscovery.loadLegacyConfig(oldConfig);
        getConfig().set("type", profile.type);
        getConfig().set("mcVersion", profile.mcVersion);
        getConfig().set("port", profile.port);
        getConfig().set("serverIp", profile.serverIp);
        getConfig().set("customJar", profile.customJar);
        getConfig().set("fabricLoaderVersion", profile.fabricLoaderVersion);
        getConfig().set("fabricInstallerVersion", profile.fabricInstallerVersion);
        getConfig().set("workingDir", profile.directory().toString());
        getConfig().set("memory.xms", profile.xms);
        getConfig().set("memory.xmx", profile.xmx);
        getConfig().set("jvmFlags", profile.jvmFlags);
        getConfig().set("serverArgs", profile.serverArgs);
        getConfig().set("autoStart", profile.autoStart);
        getConfig().set("serverProperties", profile.serverProperties);
        for (String key : List.of("autoRestart", "stopTimeoutSeconds", "startupStopTimeoutSeconds",
                "memoryProtection", "shutdownHookTimeoutSeconds", "saveAllIntervalMinutes", "logPrefix",
                "dashboard", "backup", "notifications", "consoleBufferLines")) {
            if (old.contains(key)) getConfig().set(key, old.get(key));
        }
        Path legacyBackups = oldConfig.getParent().resolve("backups").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(legacyBackups))
            throw new SecurityException("The legacy backup directory must not be a symbolic link");
        String configuredBackup = old.getString("backup.directory", "");
        if ((configuredBackup == null || configuredBackup.isBlank())
                && Files.isDirectory(legacyBackups, LinkOption.NOFOLLOW_LINKS))
            getConfig().set("backup.directory", legacyBackups.toString());
        migrateLegacySecrets(oldData);
        migrateLegacyHttpsMaterial(oldData, old);
        migrateMemoryRecoveryState(oldData);
        List<ScheduleDef> existingSchedules = ScheduleDef.load(old.get("schedules"));
        Set<String> usedIds = new HashSet<>();
        List<Map<String, Object>> migratedSchedules = new ArrayList<>();
        for (ScheduleDef schedule : existingSchedules) {
            usedIds.add(schedule.id());
            migratedSchedules.add(schedule.toConfig());
        }
        LegacyTaskMigration.Result tasks = LegacyTaskMigration.migrate(old.get("tasks"), profile.id, usedIds);
        migratedSchedules.addAll(tasks.schedules());
        getConfig().set("schedules", migratedSchedules);
        getConfig().set("tasks", tasks.remainingTasks());
        if (old.isConfigurationSection("memoryProtection")) {
            getConfig().set("memoryProtection.autoStopChild",
                    old.getBoolean("memoryProtection.autoStopChild", old.getBoolean("memoryProtection.autoShutdown", true)));
            getConfig().set("memoryProtection.autoShutdown", null);
            getConfig().set("memoryProtection.policyVersion", 4);
        }
    }

    private void migrateLegacySecrets(Path oldData) throws IOException {
        Path source = oldData.resolve("secrets.yml");
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return;
        Path target = getDataFolder().toPath().toAbsolutePath().normalize().resolve("secrets.yml");
        copyLegacyFile(oldData, source, getDataFolder().toPath().toAbsolutePath().normalize(), target, 1_048_576, true);
        BackupSecrets.load(getDataFolder().toPath());
    }

    private void migrateLegacyHttpsMaterial(Path oldData, FileConfiguration old) throws IOException {
        if (!old.getBoolean("dashboard.https.enabled", false)) return;
        String mode = old.getString("dashboard.https.mode", "self-signed");
        boolean required = "keystore".equalsIgnoreCase(mode) || "pem".equalsIgnoreCase(mode);
        if ("self-signed".equalsIgnoreCase(mode) || "keystore".equalsIgnoreCase(mode)) {
            migrateHttpsFile(oldData, old.getString("dashboard.https.keystore", "dashboard.p12"), required, 16 * 1024 * 1024);
            migrateHttpsFile(oldData, "dashboard-keystore.pass", false, 4_096);
        } else if ("pem".equalsIgnoreCase(mode)) {
            migrateHttpsFile(oldData, old.getString("dashboard.https.cert", "dashboard.crt"), true, 2 * 1024 * 1024);
            migrateHttpsFile(oldData, old.getString("dashboard.https.key", "dashboard.key"), true, 2 * 1024 * 1024);
            migrateHttpsFile(oldData, "dashboard-keystore.pass", false, 4_096);
        } else {
            throw new IOException("Unsupported legacy dashboard HTTPS mode: " + mode);
        }
    }

    private void migrateHttpsFile(Path oldData, String configured, boolean required, long maxBytes) throws IOException {
        Path path = Path.of(configured == null || configured.isBlank()
                ? "dashboard.p12" : configured);
        if (path.isAbsolute()) {
            if (required && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Required legacy dashboard HTTPS file is missing: " + path);
            return;
        }
        Path source = oldData.resolve(path).normalize();
        Path target = getDataFolder().toPath().toAbsolutePath().normalize().resolve(path).normalize();
        if (!source.startsWith(oldData) || !target.startsWith(getDataFolder().toPath().toAbsolutePath().normalize()))
            throw new SecurityException("Legacy dashboard HTTPS paths must remain inside the plugin data directory");
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            if (required) throw new IOException("Required legacy dashboard HTTPS file is missing: " + source);
            return;
        }
        copyLegacyFile(oldData, source, getDataFolder().toPath().toAbsolutePath().normalize(), target, maxBytes, false);
    }

    private void migrateMemoryRecoveryState(Path oldData) throws IOException {
        Path source = oldData.resolve("memory-recovery.txt");
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
            throw new SecurityException("Legacy memory recovery state must be a regular file");
        if (Files.size(source) > 64) throw new IOException("Legacy memory recovery state exceeds its size limit");
        String value = Files.readString(source).trim();
        long timestamp;
        try {
            timestamp = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IOException("Legacy memory recovery state is invalid; refusing to discard its safety lock", e);
        }
        if (timestamp <= 0) throw new IOException("Legacy memory recovery timestamp must be positive");
        Path target = memoryRecoveryFile();
        copyLegacyFile(oldData, source, getDataFolder().toPath().toAbsolutePath().normalize(), target, 64, false);
        recoveryBlockedSince = timestamp;
    }

    private static void copyLegacyFile(Path sourceRoot, Path source, Path targetRoot, Path target,
                                        long maxBytes, boolean secret) throws IOException {
        Path root = sourceRoot.toAbsolutePath().normalize();
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path destinationRoot = targetRoot.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedSource.startsWith(root) || !normalizedTarget.startsWith(destinationRoot))
            throw new SecurityException("Legacy file migration path escaped its data directory");
        rejectSymbolicLinkSegments(root, normalizedSource);
        rejectSymbolicLinkSegments(destinationRoot, normalizedTarget);
        if (!Files.isRegularFile(normalizedSource, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Legacy migration source is not a regular file: " + normalizedSource);
        if (Files.size(normalizedSource) > maxBytes)
            throw new IOException("Legacy migration file exceeds the size limit: " + normalizedSource);
        Files.createDirectories(normalizedTarget.getParent());
        if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(normalizedTarget) || !Files.isRegularFile(normalizedTarget, LinkOption.NOFOLLOW_LINKS)
                    || Files.mismatch(normalizedSource, normalizedTarget) != -1)
                throw new IOException("Migration destination already contains different data: " + normalizedTarget);
        } else {
            Files.copy(normalizedSource, normalizedTarget);
        }
        if (secret && Files.getFileStore(normalizedTarget).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(normalizedTarget, Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
    }

    private static void rejectSymbolicLinkSegments(Path root, Path path) {
        Path current = root;
        if (Files.isSymbolicLink(current)) throw new SecurityException("Migration data root must not be a symbolic link: " + current);
        for (Path segment : root.relativize(path)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current))
                throw new SecurityException("Migration path contains a symbolic link: " + current);
        }
    }

    private Path getWorldRoot() {
        return getDataFolder().toPath().toAbsolutePath().normalize().getParent().getParent();
    }

    private void recoverPendingSetupDirectories() {
        try {
            List<SetupRecovery.Result> recovered = SetupRecovery.recover(getWorldRoot(), setupRequired,
                    path -> cleanupLegacyDirectory(path, Set.of()));
            for (SetupRecovery.Result result : recovered) {
                switch (result.action()) {
                    case RESTORED -> getLogger().warning("Restored child data after an interrupted setup transaction: " + result.path());
                    case DELETED -> getLogger().info("Finished a previously confirmed child-directory deletion: " + result.path());
                    case ARCHIVED -> getLogger().warning("Finished archiving a child after an interrupted setup transaction: " + result.path());
                    case PRESERVED -> getLogger().warning("Pending setup data was preserved for manual recovery: " + result.path());
                }
            }
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not recover pending setup directories safely.", e);
        }
    }

    String planText(String id) { return LaunchPlan.text(javaBin(), profile(id)); }
    Map<String, Object> jarView(String id) throws IOException { return JarProbe.view(JarProbe.inspect(profile(id))); }
    String jarText(String id) throws IOException { return JarProbe.text(JarProbe.inspect(profile(id))); }
    List<String> crashFiles(String id) throws IOException { return CrashIndex.list(profile(id).directory()); }
    String crashText(String id, String file) throws IOException { return CrashIndex.read(profile(id).directory(), file); }

    Path workDir() { return profile(focus).directory(); }
    Path workDir(String id) { return profile(id).directory(); }
    List<Path> workDirs() { List<Path> out = new ArrayList<>(); for (ServerProfile p : profiles) out.add(p.directory()); return out; }

    Upstream upstream() { return upstream; }
    boolean dashboardTls() { return dashboard != null && dashboard.tls(); }

    List<Double> msptHistory() { return focusSlot().msptHistory(); }
    List<Double> msptHistory(String id) { return slot(id).msptHistory(); }

    ProcessProbe.Disk diskCached(String id) {
        long now = System.currentTimeMillis();
        Long at = diskCacheAt.get(id);
        ProcessProbe.Disk cached = diskCaches.get(id);
        if (cached != null && at != null && now - at < 60_000) return cached;
        ProcessProbe.Disk fresh = ProcessProbe.disk(workDir(id), 100_000);
        diskCaches.put(id, fresh); diskCacheAt.put(id, now);
        return fresh;
    }

    Map<String, Object> diagMap() { return diagMap(focus); }
    Map<String, Object> diagMap(String id) {
        ChildSlot s = slot(id);
        Map<String, Object> m = new LinkedHashMap<>();
        long pid = s.pid();
        m.put("server", s.id);
        m.put("running", s.running());
        m.put("pid", pid);
        m.put("tls", dashboardTls());
        m.put("status", s.lastStatus);
        m.put("mspt", s.mspt);
        m.put("tps", s.tps);
        if (pid > 0) m.putAll(ProcessProbe.snapshot(pid));
        ProcessProbe.Disk d = diskCached(s.id);
        m.put("diskBytes", d.bytes());
        m.put("diskFiles", d.files());
        m.put("diskPartial", d.partial());
        m.put("hostMemory", hostMemoryView());
        return m;
    }

    Map<String, Object> hostMemoryView() {
        HostMemoryProbe.Snapshot snapshot = hostMemory;
        Map<String, Object> view = snapshot == null ? new LinkedHashMap<>() : snapshot.view();
        view.put("usePercent", snapshot == null ? -1 : snapshot.cgroupUsePercent());
        view.put("recoveryPending", recoveryBlockedSince != 0 || hostRecoveryStarted);
        view.put("lastIncidentReport", lastMemoryIncidentReport.isBlank() ? "" : "memory-reports/" + lastMemoryIncidentReport);
        return view;
    }

    private void applyCurrentMemorySettings() {
        int policy = getConfig().getInt("memoryProtection.policyVersion", 0);
        if (policy >= 3) {
            if (policy < 4) {
                boolean wasEnabled = getConfig().getBoolean("memoryProtection.autoShutdown", true);
                getConfig().set("memoryProtection.autoStopChild", wasEnabled);
                getConfig().set("memoryProtection.autoShutdown", null);
                getConfig().set("memoryProtection.policyVersion", 4);
                saveConfigWritable();
                getLogger().warning("Memory policy updated: critical pressure now stops only the child server; ServerHoster will never automatically stop the outer Spigot host.");
            }
            return;
        }
        getConfig().set("memory.xms", "2G");
        getConfig().set("memory.xmx", "5G");
        getConfig().set("jvmFlags", List.of("-XX:+UseG1GC"));
        getConfig().set("memoryProtection.sampleIntervalSeconds", 15);
        getConfig().set("memoryProtection.warningPercent", 85);
        getConfig().set("memoryProtection.criticalPercent", 95);
        getConfig().set("memoryProtection.criticalSamples", 3);
        getConfig().set("memoryProtection.autoStopChild", true);
        getConfig().set("memoryProtection.warningDelaySeconds", 15);
        getConfig().set("memoryProtection.recoveryPercent", 80);
        getConfig().set("memoryProtection.recoverySamples", 3);
        getConfig().set("memoryProtection.recoveryCooldownSeconds", 180);
        getConfig().set("memoryProtection.policyVersion", 4);
        saveConfigWritable();
        getLogger().warning("Applied the current memory policy to config.yml: child Xms=2G, Xmx=5G, conservative G1 flags, and cgroup protection. Critical pressure can gracefully stop the child only; it never stops the outer Spigot host.");
    }

    private void loadMemoryRecoveryState() {
        Path state = memoryRecoveryFile();
        if (!Files.exists(state)) return;
        try {
            recoveryBlockedSince = Long.parseLong(Files.readString(state).trim());
            if (recoveryBlockedSince <= 0) throw new NumberFormatException("timestamp must be positive");
        } catch (IOException | NumberFormatException e) {
            getLogger().log(Level.SEVERE, "Cannot read memory recovery state; leaving automatic child start disabled: " + state, e);
            recoveryBlockedSince = System.currentTimeMillis();
        }
    }

    private void loadLatestMemoryIncidentReport() {
        Path reports = getDataFolder().toPath().resolve("memory-reports");
        if (!Files.isDirectory(reports)) return;
        try (var files = Files.list(reports)) {
            lastMemoryIncidentReport = files
                    .filter(p -> p.getFileName().toString().matches("memory-emergency-\\d{8}T\\d{6}Z\\.log"))
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .max(String::compareTo)
                    .orElse("");
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not locate the latest memory incident report.", e);
        }
    }

    private Path memoryRecoveryFile() {
        return getDataFolder().toPath().resolve("memory-recovery.txt");
    }

    private void sampleHostMemory() {
        HostMemoryProbe.Snapshot snapshot = HostMemoryProbe.snapshot();
        hostMemory = snapshot;
        synchronized (memoryHistory) {
            memoryHistory.addLast(new MemoryIncidentReport.Sample(Instant.now(), snapshot));
            while (memoryHistory.size() > 240) memoryHistory.removeFirst();
        }
        double use = snapshot.cgroupUsePercent();
        long now = System.currentTimeMillis();
        double warning = getConfig().getDouble("memoryProtection.warningPercent", 85);
        double critical = getConfig().getDouble("memoryProtection.criticalPercent", 95);
        double recovery = getConfig().getDouble("memoryProtection.recoveryPercent", 80);
        if (use >= 0) warnHeapBudget(snapshot);
        if (use >= warning && now - lastMemoryWarningAt >= 300_000) {
            lastMemoryWarningAt = now;
            String warningMessage = String.format(Locale.ROOT,
                    "Container memory pressure: %.1f%% (%d/%d MiB), swap %d MiB; cgroup=%s",
                    use, snapshot.cgroupCurrentMb(), snapshot.cgroupLimitMb(), snapshot.cgroupSwapMb(), snapshot.cgroupVersion());
            getLogger().warning(warningMessage);
            notifyNtfy("ServerHoster: Speicherdruck", warningMessage, "4");
        }

        long cooldown = TimeUnit.SECONDS.toMillis(Math.max(0,
                getConfig().getLong("memoryProtection.recoveryCooldownSeconds", 180)));
        MemoryProtectionPolicy.Decision decision = memoryProtectionPolicy.sample(use, critical, recovery,
                getConfig().getInt("memoryProtection.criticalSamples", 3),
                getConfig().getInt("memoryProtection.recoverySamples", 3), recoveryBlockedSince, now, cooldown,
                getConfig().getBoolean("memoryProtection.autoStopChild",
                        getConfig().getBoolean("memoryProtection.autoShutdown", true)));
        if (decision == MemoryProtectionPolicy.Decision.RELEASE_WITHOUT_MEMORY_GUARANTEE) {
            getLogger().warning("cgroup memory limit is unavailable after recovery cooldown; resuming configured auto-start without a pressure guarantee.");
            clearMemoryRecoveryState();
            resumeConfiguredAutoStart();
            return;
        }
        if (decision == MemoryProtectionPolicy.Decision.RELEASE_AFTER_STABLE_MEMORY) {
            clearMemoryRecoveryState();
            resumeConfiguredAutoStart();
            return;
        }
        if (decision == MemoryProtectionPolicy.Decision.STOP_CHILD)
            requestChildEmergencyStop(snapshot);
    }

    private void warnHeapBudget(HostMemoryProbe.Snapshot snapshot) {
        long outerXmx = Runtime.getRuntime().maxMemory();
        long cgroupLimit = snapshot.cgroupLimitMb() * 1024L * 1024L;
        for (ServerProfile profile : profiles) {
            ChildSlot slot = slots.get(profile.id);
            boolean willRun = (slot != null && slot.process != null && slot.process.isAlive())
                    || profile.autoStart;
            if (!willRun) continue;
            long childXmx = parseMemoryBytes(profile.xmx);
            long childXms = parseMemoryBytes(profile.xms);
            if (childXmx < 0) {
                if (memoryBudgetWarnings.add("invalid-xmx:" + profile.id + ":" + profile.xmx))
                    {
                        String warning = "Cannot compare configured child Xmx for profile " + profile.id
                                + " with cgroup memory limit; check memory.xmx.";
                        getLogger().warning(warning);
                        notifyNtfy("ServerHoster: Speicherbudget", warning, "3");
                    }
                continue;
            }
            String key = profile.id + ":" + profile.xmx + ":" + snapshot.cgroupLimitMb();
            if (childXms > 2L * 1024 * 1024 * 1024 && memoryBudgetWarnings.add("large-xms:" + key)) {
                String warning = "Profile " + profile.id + " configures child Xms=" + profile.xms
                        + "; the current root default is 2G. Profile-specific overrides are not automatically migrated, so lower memory.xms if this large minimum heap is not intentional.";
                getLogger().warning(warning);
                notifyNtfy("ServerHoster: Speicherbudget", warning, "3");
            }
            if (childXmx > Long.MAX_VALUE - outerXmx) continue;
            if (childXmx + outerXmx > cgroupLimit && memoryBudgetWarnings.add("over-budget:" + key)) {
                String warning = String.format(Locale.ROOT,
                        "Configured heap ceilings exceed the finite container memory limit: outer Spigot Xmx %.2f GiB + child %s Xmx = %.2f GiB, cgroup limit %d MiB. Xmx is a ceiling, not current usage; leave additional room for native memory and reduce heap ceilings if pressure persists.",
                        outerXmx / (double) (1024L * 1024 * 1024), profile.xmx,
                        (outerXmx + childXmx) / (double) (1024L * 1024 * 1024), snapshot.cgroupLimitMb());
                getLogger().warning(warning);
                notifyNtfy("ServerHoster: Speicherbudget", warning, "4");
            }
        }
    }

    static long parseMemoryBytes(String value) {
        if (value == null) return -1;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.endsWith("B")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.isEmpty()) return -1;
        char suffix = normalized.charAt(normalized.length() - 1);
        int power = switch (suffix) {
            case 'K' -> 1;
            case 'M' -> 2;
            case 'G' -> 3;
            case 'T' -> 4;
            default -> 0;
        };
        String amount = power == 0 ? normalized : normalized.substring(0, normalized.length() - 1);
        try {
            long bytes = Long.parseLong(amount);
            for (int i = 0; i < power; i++) bytes = Math.multiplyExact(bytes, 1024);
            return bytes > 0 ? bytes : -1;
        } catch (NumberFormatException | ArithmeticException e) {
            return -1;
        }
    }

    private void clearMemoryRecoveryState() {
        try {
            Files.deleteIfExists(memoryRecoveryFile());
            recoveryBlockedSince = 0;
            getLogger().info("Container memory recovered; configured child auto-start is enabled again.");
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Cannot clear memory recovery state; child auto-start remains paused.", e);
        }
    }

    private void resumeConfiguredAutoStart() {
        if (!autoStartInitialized || recoveryBlockedSince != 0) return;
        notifyNtfy("ServerHoster: Wiederherstellung", "Die Speicherschutz-Sperre wurde aufgehoben; konfigurierte Child-Autostarts werden fortgesetzt.", "3");
        for (ServerProfile p : profiles)
            if (p.autoStart) startAsync(null, p.id);
    }

    private void requestChildEmergencyStop(HostMemoryProbe.Snapshot snapshot) {
        if (hostRecoveryStarted) return;
        long now = System.currentTimeMillis();
        try {
            Files.writeString(memoryRecoveryFile(), Long.toString(now) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Cannot persist memory recovery state; refusing automatic child stop to avoid an uncontrolled restart loop.", e);
            memoryProtectionPolicy.resetCriticalSamples();
            return;
        }
        recoveryBlockedSince = now;
        hostRecoveryStarted = true;
        getLogger().severe(String.format(Locale.ROOT,
                "Sustained critical container memory pressure (%.1f%%, %d/%d MiB). Stopping only the child cleanly; the outer Spigot host remains running. Child auto-start will wait for the configured cooldown and stable memory.",
                snapshot.cgroupUsePercent(), snapshot.cgroupCurrentMb(), snapshot.cgroupLimitMb()));
        notifyNtfy("ServerHoster: Child-Schutz", String.format(Locale.ROOT,
                "Anhaltend kritischer Speicherverbrauch (%.1f%%, %d/%d MiB). Nur der Child wird sauber gestoppt; Spigot bleibt aktiv.",
                snapshot.cgroupUsePercent(), snapshot.cgroupCurrentMb(), snapshot.cgroupLimitMb()), "5");
        int warningDelay = Math.max(5, getConfig().getInt("memoryProtection.warningDelaySeconds", 15));
        long warningStartedAt = System.nanoTime();
        Map<String, String> warningResults = new LinkedHashMap<>();
        String warning = "say [ServerHoster] Kritischer Speicherdruck. Der Child-Server speichert und stoppt in ca. "
                + warningDelay + " Sekunden. Der Hoster bleibt aktiv; automatischer Child-Neustart erfolgt erst nach stabilen Messwerten.";
        for (ChildSlot slot : List.copyOf(slots.values())) {
            if (!slot.running()) continue;
            boolean sent = send(slot, warning);
            warningResults.put("Player warning " + slot.id, sent ? "sent to child console" : "could not be sent to child console");
            getLogger().warning("Emergency restart warning for " + slot.id + (sent ? " was sent." : " could not be sent."));
            boolean saved = send(slot, "save-all");
            warningResults.put("Pre-shutdown save-all " + slot.id, saved ? "sent to child console" : "could not be sent to child console");
            if (!saved) getLogger().warning("Could not request a pre-shutdown save-all from " + slot.id + ".");
        }
        try {
            Path report = writeMemoryIncidentReport(snapshot, warningResults);
            lastMemoryIncidentReport = report.getFileName().toString();
            getLogger().severe("Detailed memory incident report saved to " + report.toAbsolutePath());
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not write the detailed memory incident report; proceeding with the child stop.", e);
        }
        long remainingWarningNanos = TimeUnit.SECONDS.toNanos(warningDelay) - (System.nanoTime() - warningStartedAt);
        if (remainingWarningNanos > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(remainingWarningNanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                getLogger().log(Level.WARNING, "Interrupted during the player warning grace period; proceeding with emergency shutdown.", e);
            }
        }
        for (ChildSlot slot : List.copyOf(slots.values())) {
            slot.wantRunning = false;
            slot.stopRequested = true;
            stopBlocking(slot);
        }
    }

    private Path writeMemoryIncidentReport(HostMemoryProbe.Snapshot trigger, Map<String, String> warningResults) throws IOException {
        Instant createdAt = Instant.now();
        Map<String, String> facts = new LinkedHashMap<>();
        Runtime runtime = Runtime.getRuntime();
        facts.put("Plugin version", getDescription().getVersion());
        facts.put("Minecraft host version", getServer().getVersion());
        facts.put("Java version", System.getProperty("java.version"));
        facts.put("Operating system", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        facts.put("Available processors", Integer.toString(runtime.availableProcessors()));
        facts.put("Spigot JVM uptime seconds", Long.toString(java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000));
        facts.put("Spigot heap used/committed/max MiB", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                + "/" + runtime.totalMemory() / (1024 * 1024) + "/" + runtime.maxMemory() / (1024 * 1024));
        facts.put("Emergency trigger", String.format(Locale.ROOT, "%.2f%% cgroup use; %d/%d MiB; critical samples=%d/%d",
                trigger.cgroupUsePercent(), trigger.cgroupCurrentMb(), trigger.cgroupLimitMb(), memoryProtectionPolicy.criticalSamples(),
                getConfig().getInt("memoryProtection.criticalSamples", 3)));
        facts.put("Protection thresholds", "warning=" + getConfig().getDouble("memoryProtection.warningPercent", 85)
                + "%, critical=" + getConfig().getDouble("memoryProtection.criticalPercent", 95)
                + "%, recovery=" + getConfig().getDouble("memoryProtection.recoveryPercent", 80)
                + "%, sample interval=" + getConfig().getLong("memoryProtection.sampleIntervalSeconds", 15) + "s");
        facts.put("Current host memory sample", trigger.view().toString());
        facts.putAll(warningResults);

        Map<String, String> diagnostics = new LinkedHashMap<>();
        addProcessDiagnostics(diagnostics, "Spigot", ProcessHandle.current().pid(), null);
        for (ChildSlot slot : List.copyOf(slots.values())) {
            if (!slot.running()) continue;
            ServerProfile profile = slot.profile;
            facts.put("Child " + slot.id,
                    "pid=" + slot.pid() + ", type=" + (profile == null ? "unknown" : profile.type)
                            + ", xms/xmx=" + (profile == null ? "unknown" : profile.xms + "/" + profile.xmx)
                            + ", uptimeMs=" + slot.uptimeMillis() + ", players=" + slot.onlinePlayers.size()
                            + ", status=" + slot.lastStatus + ", tps=" + slot.tps + ", mspt=" + slot.mspt);
            addProcessDiagnostics(diagnostics, "Child " + slot.id, slot.pid(), profile);
        }
        List<MemoryIncidentReport.Sample> samples;
        synchronized (memoryHistory) { samples = List.copyOf(memoryHistory); }
        return MemoryIncidentReport.write(getDataFolder().toPath().resolve("memory-reports"), createdAt, facts, samples, diagnostics);
    }

    private void addProcessDiagnostics(Map<String, String> diagnostics, String name, long pid, ServerProfile profile) {
        if (pid <= 0) {
            diagnostics.put(name + " process snapshot", "unavailable: process has no live PID");
            return;
        }
        Map<String, Object> process = ProcessProbe.snapshot(pid);
        StringBuilder snapshot = new StringBuilder();
        for (String key : List.of("rssMb", "hwmMb", "swapMb", "vmMb", "threads", "state", "fds", "readBytes", "writeBytes"))
            snapshot.append(key).append('=').append(process.get(key)).append('\n');
        diagnostics.put(name + " process snapshot", snapshot.toString());
        for (String command : ProcessProbe.JCMD) {
            String key = name + " jcmd " + command;
            try {
                diagnostics.put(key, ProcessProbe.jcmd(pid, command, 3));
            } catch (IOException | IllegalArgumentException | IllegalStateException e) {
                diagnostics.put(key, "collection failed: " + e.getMessage());
            }
        }
        if (profile != null && profile.jvmFlags != null && !profile.jvmFlags.isEmpty())
            diagnostics.put(name + " configured JVM flag count", Integer.toString(profile.jvmFlags.size()));
    }

    String jcmd(String command) throws IOException { return jcmd(focus, command); }
    String jcmd(String id, String command) throws IOException { return ProcessProbe.jcmd(slot(id).pid(), command); }

    List<String> versions(String type) throws Exception {
        long now = System.currentTimeMillis();
        Long at = versionCacheAt.get(type);
        if (at != null && now - at < 600_000 && versionCache.containsKey(type)) return versionCache.get(type);
        List<String> list = VersionCatalog.fetch(upstream, type, 40);
        versionCache.put(type, list); versionCacheAt.put(type, now);
        return list;
    }

    List<TaskDef> tasks() {
        List<?> raw = getConfig().getList("tasks");
        if (raw == null) return List.of();
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object o : raw) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> c = new LinkedHashMap<>();
                m.forEach((k, v) -> c.put(String.valueOf(k), v));
                maps.add(c);
            } else if (o instanceof ConfigurationSection s) maps.add(s.getValues(false));
        }
        return TaskDef.load(maps);
    }

    List<ScheduleDef> schedules() {
        synchronized (configMutationLock) {
            if (getConfig().contains("schedules") && !(getConfig().get("schedules") instanceof List<?>))
                throw new IllegalArgumentException("schedules muss eine YAML-Liste sein");
            return ScheduleDef.load(getConfig().getList("schedules"));
        }
    }

    void saveSchedules(List<ScheduleDef> schedules) {
        if (schedules.size() > ScheduleDef.MAX_SCHEDULES)
            throw new IllegalArgumentException("Zu viele Zeitpläne");
        if (schedules.stream().map(ScheduleDef::id).distinct().count() != schedules.size())
            throw new IllegalArgumentException("Schedule-IDs müssen eindeutig sein");
        synchronized (configMutationLock) {
            getConfig().set("schedules", schedules.stream().map(ScheduleDef::toConfig).toList());
            saveConfigWritable();
        }
    }

    void runTask(TaskDef task) {
        try {
            executeTask(task);
            noteTask(task.id, "ok");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            noteTask(task.id, "interrupted");
            getLogger().log(Level.WARNING, "Task " + task.id + " interrupted", e);
        } catch (Exception e) {
            noteTask(task.id, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            getLogger().log(Level.WARNING, "Task " + task.id + " failed", e);
            if (!"backup".equals(task.action))
                notifyNtfy("ServerHoster: Aufgabenfehler", "Geplanter Task " + task.id + " ist fehlgeschlagen. Details stehen im Spigot-Log.", "4");
        }
    }

    private void executeTask(TaskDef task) throws Exception {
        String id = task.server.isBlank() ? focus : task.server;
        if (!focus.equals(id))
            throw new IllegalArgumentException("Task " + task.id + " verweist auf ein entferntes Serverprofil: " + id);
        switch (task.action) {
            case "start" -> startAsync(null, id);
            case "stop" -> { ChildSlot s = slot(id); s.wantRunning = false; stopBlocking(s); }
            case "restart" -> requestRestart(id);
            case "backup" -> runScheduledBackup(task, id);
            case "command", "save-all" -> {
                String cmd = task.action.equals("save-all") || task.command.isBlank() ? "save-all" : task.command;
                boolean ok = send(slot(id), cmd.startsWith("/") ? cmd.substring(1) : cmd);
                slot(id).console.add("[ServerhosterPlugin] task " + task.id + (ok ? " sent " : " skipped ") + cmd);
            }
            default -> throw new IllegalArgumentException("action " + task.action);
        }
    }

    String taskLast(String id) { return taskLast.getOrDefault(id, ""); }

    String taskNext(TaskDef task) {
        if (task.cron == null || task.cron.isBlank()) return "";
        try {
            java.time.ZonedDateTime z = CronExpr.next(task.cron, java.time.ZonedDateTime.now(java.time.ZoneId.of(task.timezone)));
            return z == null ? "" : z.toString();
        } catch (RuntimeException e) { return ""; }
    }

    private void noteTask(String id, String state) {
        taskLast.put(id, java.time.OffsetDateTime.now() + " " + state);
    }

    String runBackup(String id) throws Exception {
        ChildSlot s = slot(id);
        synchronized (s.backupLock) {
            try {
                if (s.running()) {
                    send(s, "save-all");
                    Thread.sleep(Math.max(0, getConfig().getInt("backup.saveWaitSeconds", 3)) * 1000L);
                }
                BackupBridge.Spec spec = backupSpec();
                if (remoteBackupSelected()) {
                    BackupSecrets.Credentials credentials = backupCredentials();
                    RemoteBackupBridge.Result r = RemoteBackupBridge.archive(profile(id).directory(), spec, remoteBackupSettings(),
                            credentials.username(), credentials.password(), id);
                    lastBackup = r.path() + " sha256=" + r.sha256()
                            + " transport=" + (r.encrypted() ? "FTPS" : "FTP (unverschlüsselt)")
                            + (r.db().isBlank() ? "" : " db=" + r.db());
                    if (!r.db().isBlank() && !"ok".equals(r.db()))
                        notifyNtfy("ServerHoster: Backup-Metadaten", "Das Backup wurde erstellt, aber die externen JDBC-Metadaten konnten nicht gespeichert werden.", "4");
                } else {
                    BackupBridge.Result r = BackupBridge.archive(profile(id).directory(), spec, id);
                    lastBackup = r.path() + " sha256=" + r.sha256() + (r.db().isBlank() ? "" : " db=" + r.db());
                    if (!r.db().isBlank() && !"ok".equals(r.db()))
                        notifyNtfy("ServerHoster: Backup-Metadaten", "Das Backup wurde erstellt, aber die externen JDBC-Metadaten konnten nicht gespeichert werden.", "4");
                }
                s.console.add("[ServerhosterPlugin] backup " + lastBackup);
                getLogger().info("Backup " + lastBackup);
                return lastBackup;
            } catch (Exception e) {
                notifyNtfy("ServerHoster: Backup fehlgeschlagen", "Ein Backup für Profil " + id + " ist fehlgeschlagen. Details stehen im Spigot-Log.", "4");
                throw e;
            }
        }
    }

    String testBackup() throws Exception {
        BackupBridge.Spec spec = backupSpec();
        String transport = "local";
        if (remoteBackupSelected()) {
            BackupSecrets.Credentials credentials = backupCredentials();
            boolean encrypted = RemoteBackupBridge.test(remoteBackupSettings(), credentials.username(), credentials.password());
            transport = encrypted ? "FTPS" : "FTP (unverschlüsselt)";
        } else {
            Path directory = Paths.get(spec.directory()).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            Path probe = Files.createTempFile(directory, ".backup-test-", ".tmp");
            try { Files.delete(probe); }
            finally { Files.deleteIfExists(probe); }
        }
        if (spec.dbEnabled()) {
            BackupBridge.test(spec);
            transport += " + JDBC";
        }
        return transport;
    }

    List<BackupBridge.Entry> backupFiles(String id) throws IOException {
        if (remoteBackupSelected()) {
            BackupSecrets.Credentials credentials = backupCredentials();
            return RemoteBackupBridge.list(remoteBackupSettings(), credentials.username(), credentials.password(), id);
        }
        return BackupBridge.list(backupSpec(), id);
    }

    Path backupFile(String id, String name) throws IOException {
        if (remoteBackupSelected()) throw new IOException("Remote backup files are streamed from the configured destination.");
        return BackupBridge.file(backupSpec(), id, name);
    }

    void downloadBackup(String id, String name, OutputStream output) throws IOException {
        if (remoteBackupSelected()) {
            BackupSecrets.Credentials credentials = backupCredentials();
            RemoteBackupBridge.download(remoteBackupSettings(), credentials.username(), credentials.password(), id, name, output);
        } else {
            Files.copy(BackupBridge.file(backupSpec(), id, name), output);
        }
    }

    void deleteBackup(String id, String name) throws IOException {
        if (remoteBackupSelected()) {
            BackupSecrets.Credentials credentials = backupCredentials();
            RemoteBackupBridge.delete(remoteBackupSettings(), credentials.username(), credentials.password(), id, name);
        } else BackupBridge.delete(backupSpec(), id, name);
    }

    int restoreBackup(String id, String name) throws IOException {
        if (slot(id).running()) throw new IllegalStateException("stop the server first");
        int n;
        if (remoteBackupSelected()) {
            Path temp = Files.createTempFile(getDataFolder().toPath(), "backup-restore-", ".zip");
            try {
                BackupSecrets.Credentials credentials = backupCredentials();
                try (OutputStream output = Files.newOutputStream(temp)) {
                    RemoteBackupBridge.download(remoteBackupSettings(), credentials.username(), credentials.password(), id, name, output);
                }
                n = BackupBridge.restoreZip(profile(id).directory(), temp);
            } finally {
                Files.deleteIfExists(temp);
            }
        } else {
            n = BackupBridge.restore(profile(id).directory(), backupSpec(), id, name);
        }
        slot(id).console.add("[ServerhosterPlugin] restored " + n + " files from " + name);
        return n;
    }

    private boolean remoteBackupSelected() throws IOException {
        String storage = getConfig().getString("backup.storage", "local");
        if (storage == null) storage = "local";
        return switch (storage.trim().toLowerCase(Locale.ROOT)) {
            case "local" -> false;
            case "remote" -> true;
            default -> throw new IOException("backup.storage must be local or remote");
        };
    }

    private RemoteBackupBridge.Settings remoteBackupSettings() {
        FileConfiguration c = getConfig();
        return new RemoteBackupBridge.Settings(c.getString("backup.remote.protocol", "AUTO"),
                c.getString("backup.remote.host", ""), c.getInt("backup.remote.port", 21),
                c.getString("backup.remote.directory", ""), message -> getLogger().warning(message));
    }

    private BackupSecrets.Credentials backupCredentials() throws IOException {
        return BackupSecrets.load(getDataFolder().toPath());
    }

    private BackupBridge.Spec backupSpec() {
        FileConfiguration c = getConfig();
        String dir = c.getString("backup.directory", "");
        Path path = dir == null || dir.isBlank() ? getDataFolder().toPath().resolve("backups") : Paths.get(dir);
        if (!path.isAbsolute()) path = Paths.get("").toAbsolutePath().resolve(path);
        return new BackupBridge.Spec(path.toString(), c.getStringList("backup.paths"), c.getInt("backup.keep", 5),
                c.getBoolean("backup.database.enabled"), c.getString("backup.database.jdbcUrl", ""),
                c.getString("backup.database.user", ""), c.getString("backup.database.password", ""),
                c.getString("backup.database.driverJar", ""), c.getString("backup.database.driverClass", ""),
                c.getString("backup.database.table", "serverhoster_backups"));
    }

    private void fireDueTasks() {
        if (shuttingDown) return;
        for (TaskDef task : tasks()) {
            if (!task.enabled || task.cron.isBlank()) continue;
            java.time.ZoneId zone;
            try { zone = java.time.ZoneId.of(task.timezone); }
            catch (Exception e) { getLogger().warning("Task " + task.id + " timezone " + task.timezone); continue; }
            java.time.ZonedDateTime local = java.time.ZonedDateTime.now(zone);
            boolean due;
            try { due = CronExpr.matches(task.cron, local); }
            catch (RuntimeException e) { getLogger().warning("Task " + task.id + " cron: " + e.getMessage()); continue; }
            if (!due) continue;
            String mark = task.id + " " + local.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
            if (taskFired.putIfAbsent(mark, "1") != null) continue;
            if (taskFired.size() > 500) taskFired.keySet().removeIf(k -> !k.endsWith(mark.substring(mark.length() - 12)));
            exec.submit(() -> runTask(task));
        }
    }

    private void fireDueSchedules() {
        if (shuttingDown) return;
        List<ScheduleDef> configured;
        try {
            configured = schedules();
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Configured schedules could not be loaded", e);
            return;
        }
        for (ScheduleDef schedule : configured) {
            if (!schedule.enabled()) continue;
            java.time.ZonedDateTime now;
            try {
                now = java.time.ZonedDateTime.now(java.time.ZoneId.of(schedule.timezone()));
                if (!CronExpr.matches(schedule.cron(), now)) continue;
            } catch (RuntimeException e) {
                getLogger().warning("Schedule " + schedule.id() + " could not be evaluated: " + e.getMessage());
                continue;
            }
            if (!activeSchedules.add(schedule.id())) continue;
            try {
                ScheduleDef started = markScheduleRun(schedule.id(), now.toInstant().toEpochMilli());
                if (started == null) activeSchedules.remove(schedule.id());
                else exec.submit(() -> runSchedule(started));
            } catch (RuntimeException e) {
                activeSchedules.remove(schedule.id());
                getLogger().log(Level.SEVERE, "Schedule " + schedule.id() + " could not be started", e);
            }
        }
    }

    private ScheduleDef markScheduleRun(String id, long timestamp) {
        synchronized (configMutationLock) {
            List<ScheduleDef> current = new ArrayList<>(schedules());
            for (int i = 0; i < current.size(); i++) {
                ScheduleDef schedule = current.get(i);
                if (!schedule.id().equals(id) || !schedule.enabled()) continue;
                if (sameScheduleMinute(schedule, timestamp)) return null;
                ScheduleDef started = schedule.withLastRun(timestamp);
                current.set(i, started);
                saveSchedules(current);
                return started;
            }
        }
        return null;
    }

    private boolean sameScheduleMinute(ScheduleDef schedule, long timestamp) {
        if (schedule.lastRun() <= 0) return false;
        java.time.ZoneId zone = java.time.ZoneId.of(schedule.timezone());
        java.time.LocalDateTime previous = java.time.Instant.ofEpochMilli(schedule.lastRun())
                .atZone(zone).toLocalDateTime().withSecond(0).withNano(0);
        java.time.LocalDateTime current = java.time.Instant.ofEpochMilli(timestamp)
                .atZone(zone).toLocalDateTime().withSecond(0).withNano(0);
        return previous.equals(current);
    }

    void runScheduleNow(String id) {
        if (!activeSchedules.add(id)) throw new IllegalStateException("Zeitplan läuft bereits: " + id);
        try {
            ScheduleDef run = markManualScheduleRun(id, System.currentTimeMillis());
            exec.submit(() -> runSchedule(run));
        } catch (RuntimeException e) {
            activeSchedules.remove(id);
            throw e;
        }
    }

    private ScheduleDef markManualScheduleRun(String id, long timestamp) {
        synchronized (configMutationLock) {
            List<ScheduleDef> current = new ArrayList<>(schedules());
            for (int i = 0; i < current.size(); i++) {
                ScheduleDef schedule = current.get(i);
                if (!schedule.id().equals(id)) continue;
                if (sameScheduleMinute(schedule, timestamp)) return schedule;
                ScheduleDef started = schedule.withLastRun(timestamp);
                current.set(i, started);
                saveSchedules(current);
                return started;
            }
        }
        throw new IllegalArgumentException("Zeitplan nicht gefunden: " + id);
    }

    private void runSchedule(ScheduleDef schedule) {
        noteTask(schedule.id(), "running");
        try {
            ScheduleExecution.run(schedule, action -> {
                if (action.type().equals("backup")) {
                    TaskDef task = new TaskDef(schedule.id() + "-" + action.id(), true, "", schedule.timezone(),
                            "backup", "", "");
                    runScheduledBackup(task, focus);
                } else {
                    ChildSlot target = slot(focus);
                    if (!send(target, action.command().startsWith("/") ? action.command().substring(1) : action.command()))
                        throw new IllegalStateException("Befehl konnte nicht an den Child gesendet werden");
                    target.console.add("[ServerHoster] schedule " + schedule.id() + " sent " + action.command());
                }
            });
            noteTask(schedule.id(), "ok");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            noteTask(schedule.id(), "interrupted");
            getLogger().log(Level.WARNING, "Schedule " + schedule.id() + " interrupted", e);
        } catch (Exception e) {
            noteTask(schedule.id(), e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            getLogger().log(Level.WARNING, "Schedule " + schedule.id() + " failed", e);
            notifyNtfy("ServerHoster: Aufgabenfehler", "Zeitplan " + schedule.id() + " ist fehlgeschlagen. Details stehen im Spigot-Log.", "4");
        } finally {
            activeSchedules.remove(schedule.id());
        }
    }

    private void runScheduledBackup(TaskDef task, String id) throws Exception {
        ChildSlot target = slot(id);
        long nextWaitNotice = 0;
        while (true) {
            while (target.running() && !target.onlinePlayers.isEmpty()) {
                if (shuttingDown) throw new InterruptedException("plugin is shutting down");
                if (System.nanoTime() >= nextWaitNotice) {
                    String message = "Scheduled backup " + task.id + " is waiting for the child to become empty.";
                    target.console.add("[ServerhosterPlugin] " + message);
                    getLogger().info(message);
                    noteTask(task.id, "waiting for empty child");
                    nextWaitNotice = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
                }
                Thread.sleep(TimeUnit.SECONDS.toMillis(30));
            }

            synchronized (target.backupLock) {
                if (target.running() && !target.onlinePlayers.isEmpty()) continue;
                boolean wasRunning = target.running();
                boolean restartAfter = wasRunning && target.wantRunning;
                try {
                    if (wasRunning) {
                        target.wantRunning = false;
                        target.stopRequested = true;
                        if (!stopProcess(target, getConfig().getLong("stopTimeoutSeconds", 60), false)) {
                            notifyNtfy("ServerHoster: Backup abgebrochen", "Der Child konnte vor dem geplanten Backup nicht sauber gestoppt werden; das Backup wurde abgebrochen.", "4");
                            throw new IOException("Child did not stop cleanly; scheduled backup was cancelled.");
                        }
                    }
                    runBackup(id);
                } finally {
                    if (restartAfter && !shuttingDown) startAsync(null, id);
                }
            }
            return;
        }
    }

    // ---------------- lifecycle ----------------
    private void startAsync(CommandSender s, String id) {
        if (setupRequired) {
            msg(s, "Complete the ServerHoster setup in the dashboard before starting the child server.");
            return;
        }
        ChildSlot slot = slot(id);
        slot.attempts = 0;
        slot.stopRequested = false;
        slot.wantRunning = true;
        exec.submit(() -> {
            try {
                openChild(s, id);
            } catch (Exception e) {
                ChildSlot failedSlot = slot(id);
                failedSlot.lastStatus = "start failed: " + e.getMessage();
                getLogger().log(Level.SEVERE, "Failed to start child " + id, e);
                msg(s, "Start failed: " + e.getMessage());
                scheduleRestart(failedSlot);
            }
        });
    }

    /** Starts the single configured child. */
    private void openChild(CommandSender s, String id) throws Exception {
        if (!slot(id).wantRunning) return;
        synchronized (childGate) {
            if (!slot(id).wantRunning) return;
            ChildSlot slot = slot(id);
            if (!slot.wantRunning) return;
            if (slot.running()) { msg(s, "Already running."); return; }
            if (launch(profile(id), slot)) msg(s, "Child server started.");
            else msg(s, "Child start cancelled.");
        }
    }

    private boolean launch(ServerProfile profile, ChildSlot slot) throws Exception {
        synchronized (slot.lock) {
            if (slot.running()) throw new IllegalStateException("already running");
            slot.profile = profile;
            Path wd = profile.directory();
            Files.createDirectories(wd);
            copyPlayerFiles(wd);
            slot.lastStatus = "preparing";
            Path jar = prepareFiles(profile, wd);
            Files.writeString(wd.resolve("eula.txt"), "eula=true\n");
            writeProperties(profile, wd);
            String javaBin = javaBin();
            List<String> cmd = new ArrayList<>(LaunchPlan.argv(javaBin, profile, jar));
            getLogger().info("Launching " + profile.id + ": " + String.join(" ", cmd));
            if (!slot.wantRunning || slot.stopRequested) {
                slot.lastStatus = "start cancelled";
                return false;
            }
            slot.serverReady = false;
            Process p = new ProcessBuilder(cmd).directory(wd.toFile()).redirectErrorStream(true).start();
            slot.process = p; slot.startedAt = System.currentTimeMillis(); slot.onlinePlayers.clear();
            slot.console.add("[ServerhosterPlugin] Child started, pid " + p.pid());
            slot.stdin = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
            slot.lastStatus = "running";
            String basePrefix = getConfig().getString("logPrefix", "[Child] ");
            final String prefix = !"default".equals(profile.id) && profiles.size() > 1 ? "[" + profile.id + "] " : basePrefix;
            exec.submit(() -> pipe(slot, p, prefix));
            exec.submit(() -> watch(slot, p));
            if (!slot.wantRunning || slot.stopRequested) exec.submit(() -> stopBlocking(slot));
            return true;
        }
    }

    private void pipe(ChildSlot slot, Process p, String prefix) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (parseTick(slot, line)) continue;
                getLogger().info(prefix + line);
                slot.console.add(line);
                trackPlayers(slot, line);
                if (ChildSlot.isReadyLine(line)) {
                    slot.serverReady = true;
                    if (slot.stopRequested) exec.submit(() -> stopBlocking(slot));
                }
            }
        } catch (IOException ignored) {}
    }

    private void startSampler() {
        lastSampleNs = System.nanoTime();
        // 1-tick task: measures tick-to-tick interval (MSPT approx. = busy part is not observable via API; we report interval)
        getServer().getScheduler().runTaskTimer(this, () -> {
            long now = System.nanoTime();
            if (tickStart != 0) { tickNs[tickIdx++ % tickNs.length] = now - tickStart; }
            tickStart = now;
            if (tickIdx % 20 == 0 && tickIdx > 0) {
                long sum = 0; for (long t : tickNs) sum += t;
                double avgMs = sum / (double) tickNs.length / 1e6;
                outerMspt = Math.round(avgMs * 100) / 100.0;
                outerTps = Math.round(Math.min(20, 1000 / Math.max(avgMs, 0.001)) * 100) / 100.0;
                int ch = 0, en = 0, ws = 0;
                for (org.bukkit.World w : getServer().getWorlds()) { ws++; ch += w.getLoadedChunks().length; en += w.getEntities().size(); }
                outerChunks = ch; outerEntities = en; outerWorlds = ws;
            }
        }, 1L, 1L);
    }

    /** Sends 'tick query' to the child at most every 10 s (called from dashboard status polling). */
    void maybeQueryChildTick(String id) {
        ChildSlot slot = slot(id);
        long now = System.currentTimeMillis();
        if (!slot.running() || now - slot.queryAt < 10_000 || now - slot.startedAt < 30_000) return;
        slot.queryAt = now;
        send(slot, "tick query");
    }

    /** Returns true if the line belongs to a tick-query answer (hidden from console/log). */
    private boolean parseTick(ChildSlot slot, String line) {
        long before = slot.ticks.tickAt;
        boolean hide = slot.ticks.consume(line, slot.queryAt, System.currentTimeMillis());
        if (hide && slot.ticks.tickAt != before) {
            slot.mspt = slot.ticks.mspt;
            slot.tps = slot.ticks.tps;
            slot.tickAt = slot.ticks.tickAt;
            slot.pushMspt(slot.mspt);
        }
        return hide;
    }

    private void trackPlayers(ChildSlot slot, String line) {
        var m = JOIN.matcher(line); if (m.find()) { slot.onlinePlayers.add(m.group(1)); return; }
        m = LEFT.matcher(line); if (m.find()) { slot.onlinePlayers.remove(m.group(1)); return; }
        m = LIST.matcher(line);
        if (m.find()) {
            slot.onlinePlayers.clear();
            for (String n : m.group(1).split(",")) { n = n.trim(); if (!n.isEmpty()) slot.onlinePlayers.add(n); }
        }
    }

    private void watch(ChildSlot slot, Process p) {
        try {
            int code = p.waitFor();
            long up = System.currentTimeMillis() - slot.startedAt;
            synchronized (slot.lock) { if (slot.process == p) { slot.process = null; slot.stdin = null; } }
            slot.lastStatus = "exited (code " + code + ")";
            slot.onlinePlayers.clear();
            slot.console.add("[ServerhosterPlugin] Child exited with code " + code);
            getLogger().warning("Child " + slot.id + " exited with code " + code + " after " + up / 1000 + "s");
            if (up >= getConfig().getLong("autoRestart.resetAfterSeconds", 600) * 1000L) slot.attempts = 0;
            if (slot.wantRunning && !shuttingDown) scheduleRestart(slot);
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private void scheduleRestart(ChildSlot slot) {
        if (!slot.wantRunning || shuttingDown || !getConfig().getBoolean("autoRestart.enabled", true)) return;
        int max;
        try {
            max = RestartPolicy.maxAttempts(getConfig().get("autoRestart.maxAttempts",
                    RestartPolicy.DEFAULT_MAX_ATTEMPTS));
        } catch (IllegalArgumentException e) {
            getLogger().log(Level.SEVERE, "Automatic restart limit is invalid; automatic restarts are paused.", e);
            slot.wantRunning = false;
            slot.lastStatus += ", auto-restart paused due to invalid settings";
            return;
        }
        if (!RestartPolicy.mayRetry(slot.attempts, max)) {
            getLogger().severe("Max restart attempts reached for " + slot.id + "; giving up.");
            notifyNtfy("ServerHoster: Neustart pausiert", "Der Child " + slot.id + " ist nach "
                    + max + " automatischen Neustartversuchen weiterhin ausgefallen. Automatische Neustarts sind pausiert; bitte Ursache prüfen und manuell starten.", "5");
            slot.wantRunning = false;
            slot.lastStatus += ", gave up";
            return;
        }
        long base = getConfig().getLong("autoRestart.initialDelaySeconds", 5);
        long cap = getConfig().getLong("autoRestart.maxDelaySeconds", 300);
        long delay = RestartBackoff.delaySeconds(base, cap, slot.attempts);
        slot.attempts++;
        slot.lastStatus += ", restarting in " + delay + "s (attempt " + slot.attempts + ")";
        getLogger().warning("Restarting " + slot.id + " in " + delay + "s (attempt " + slot.attempts + ")");
        notifyNtfy("ServerHoster: Automatischer Neustart", "Child " + slot.id + " ist beendet. Neustartversuch " + slot.attempts + " in " + delay + " Sekunden.", "4");
        exec.submit(() -> {
            try { Thread.sleep(delay * 1000L); } catch (InterruptedException e) { return; }
            if (!slot.wantRunning || shuttingDown || slot.running()) return;
            try {
                restartSingle(slot);
            } catch (Exception e) { getLogger().log(Level.SEVERE, "Restart failed", e); scheduleRestart(slot); }
        });
    }

    private void restartSingle(ChildSlot slot) throws Exception {
        synchronized (childGate) {
            if (!slot.wantRunning || shuttingDown || slot.running()) return;
            launch(profile(slot.id), slot);
        }
    }

    private boolean send(ChildSlot slot, String command) {
        BufferedWriter w = slot.stdin;
        if (w == null || !slot.running()) return false;
        try { synchronized (w) { w.write(command); w.newLine(); w.flush(); } return true; }
        catch (IOException e) { return false; }
    }

    /** Sends "stop", waits stopTimeoutSeconds, then kills. Blocks. */
    private void stopBlocking(ChildSlot slot) {
        slot.wantRunning = false;
        slot.stopRequested = true;
        if (!slot.running()) return;
        stopProcess(slot, getConfig().getLong("stopTimeoutSeconds", 60), false);
    }

    /** Thread-safe (onDisable + shutdown hook may race). useStdout: logger may be gone during JVM shutdown. */
    private boolean stopProcess(ChildSlot slot, long timeout, boolean useStdout) {
        synchronized (slot.stopLock) {
            Process p = slot.process;
            if (p == null) return true;
            if (!p.isAlive()) return p.exitValue() == 0;
            java.util.function.Consumer<String> log = m -> { if (useStdout) System.out.println("[ServerhosterPlugin] " + m); else { getLogger().info(m); slot.console.add("[ServerhosterPlugin] " + m); } };
            log.accept("Stopping " + slot.id + " (pid " + p.pid() + ", waiting up to " + timeout + "s for clean save)...");
            try {
                long startupTimeout = useStdout ? 0 : Math.max(0, getConfig().getLong("startupStopTimeoutSeconds", 180));
                long startupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(startupTimeout);
                while (p.isAlive() && !slot.serverReady && System.nanoTime() < startupDeadline) {
                    p.waitFor(250, TimeUnit.MILLISECONDS);
                }
                if (!slot.serverReady && p.isAlive())
                    log.accept("Child did not report startup complete within " + startupTimeout + "s; sending stop and retrying while it shuts down.");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(0, timeout));
                long nextSendAt = 0;
                boolean firstSend = true;
                while (p.isAlive() && (firstSend || System.nanoTime() < deadline)) {
                    long now = System.nanoTime();
                    if (firstSend || now >= nextSendAt) {
                        firstSend = false;
                        BufferedWriter w = slot.stdin;
                        try {
                            if (w == null) throw new IOException("child console is not ready");
                            synchronized (w) { w.write("stop"); w.newLine(); w.flush(); }
                        } catch (IOException e) { log.accept("Could not send 'stop': " + e.getMessage()); }
                        nextSendAt = now + TimeUnit.SECONDS.toNanos(5);
                    }
                    long remaining = deadline - now;
                    if (remaining <= 0) break;
                    p.waitFor(Math.max(1, Math.min(TimeUnit.NANOSECONDS.toMillis(remaining), 250)), TimeUnit.MILLISECONDS);
                }
                if (!p.isAlive()) {
                    int exitCode = p.exitValue();
                    if (exitCode == 0) log.accept("Child stopped cleanly (exit code 0).");
                    else log.accept("Child exited with code " + exitCode + " during stop; shutdown was not clean.");
                    return exitCode == 0;
                } else {
                    log.accept("Child did not stop within " + timeout + "s - KILLING (unsaved changes since last save-all may be lost).");
                    p.descendants().forEach(ProcessHandle::destroy);
                    p.destroy();
                    if (!p.waitFor(10, TimeUnit.SECONDS)) { p.descendants().forEach(ProcessHandle::destroyForcibly); p.destroyForcibly(); p.waitFor(10, TimeUnit.SECONDS); }
                    log.accept("Child killed.");
                    return false;
                }
            } catch (InterruptedException e) {
                p.destroyForcibly(); log.accept("Child killed (interrupted)."); Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    // ---------------- commands ----------------
    @Override public boolean onCommand(CommandSender s, Command command, String label, String[] a) {
        if (!s.hasPermission("serverhoster.admin")) { s.sendMessage("No permission."); return true; }
        if (a.length == 0) return false;
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "start" -> { s.sendMessage("Starting " + focus + " (downloads may take a while)..."); requestStart(s, focus); }
            case "stop" -> { focusSlot().wantRunning = false; s.sendMessage("Stopping " + focus + "..."); ChildSlot cur = focusSlot(); exec.submit(() -> { stopBlocking(cur); msg(s, "Child stopped."); }); }
            case "restart" -> { s.sendMessage("Restarting " + focus + "..."); requestRestart(focus); }
            case "status" -> {
                ChildSlot cur = focusSlot();
                ServerProfile pr = profile(focus);
                s.sendMessage(cur.id + ": " + (cur.running() ? "RUNNING pid " + cur.pid() + ", up " + cur.uptimeMillis() / 1000 + "s" : "NOT RUNNING") + " | " + cur.lastStatus);
                s.sendMessage("Type " + pr.type + " " + pr.mcVersion + " on port " + pr.port + ", dir " + pr.directory());
            }
            case "tasks" -> { for (TaskDef t : tasks()) s.sendMessage((t.enabled ? "on  " : "off ") + t.id + " " + t.cron + " " + t.timezone + " " + t.action + " next " + taskNext(t)); }
            case "task" -> {
                if (a.length < 3 || !a[1].equalsIgnoreCase("run")) { s.sendMessage("/child task run <id>"); return true; }
                TaskDef found = null; for (TaskDef t : tasks()) if (t.id.equals(a[2])) found = t;
                if (found == null) s.sendMessage("Unknown task"); else { TaskDef run = found; exec.submit(() -> runTask(run)); s.sendMessage("Task " + a[2]); }
            }
            case "backup" -> {
                if (a.length >= 2 && a[1].equalsIgnoreCase("list")) {
                    try { for (var e : backupFiles(focus)) s.sendMessage(e.name() + " " + e.bytes()); }
                    catch (IOException e) { s.sendMessage("Failed: " + e.getMessage()); }
                } else if (a.length >= 3 && a[1].equalsIgnoreCase("delete")) {
                    try { deleteBackup(focus, a[2]); s.sendMessage("Deleted " + a[2]); }
                    catch (Exception e) { s.sendMessage("Failed: " + e.getMessage()); }
                } else if (a.length >= 3 && a[1].equalsIgnoreCase("restore")) {
                    String file = a[2];
                    exec.submit(() -> { try { msg(s, "Restored " + restoreBackup(focus, file) + " files"); } catch (Exception e) { msg(s, "Restore failed: " + e.getMessage()); } });
                } else { s.sendMessage("Backup " + focus + "..."); exec.submit(() -> { try { msg(s, runBackup(focus)); } catch (Exception e) { msg(s, "Backup failed: " + e.getMessage()); } }); }
            }
            case "versions" -> {
                if (a.length < 2) { s.sendMessage("/child versions <fabric|paper|vanilla|spigot>"); return true; }
                exec.submit(() -> { try { for (String v : versions(a[1])) msg(s, v); } catch (Exception e) { msg(s, "Versions failed: " + e.getMessage()); } });
            }
            case "plan" -> s.sendMessage(planText(focus));
            case "jar" -> {
                try { s.sendMessage(jarText(focus)); }
                catch (IOException e) { s.sendMessage("Failed: " + e.getMessage()); }
            }
            case "crashes" -> {
                try {
                    if (a.length < 2) {
                        List<String> files = crashFiles(focus);
                        if (files.isEmpty()) s.sendMessage("no crash-reports");
                        for (String f : files) s.sendMessage(f);
                    } else {
                        String text = crashText(focus, a[1]);
                        String[] lines = text.split("\n", -1);
                        int n = Math.min(lines.length, 40);
                        for (int i = 0; i < n; i++) s.sendMessage(lines[i]);
                        if (lines.length > n) s.sendMessage("... " + (lines.length - n) + " more lines in the dashboard");
                    }
                } catch (IOException e) { s.sendMessage("Failed: " + e.getMessage()); }
                catch (SecurityException e) { s.sendMessage("Failed: " + e.getMessage()); }
            }
            case "cmd" -> {
                if (a.length < 2) { s.sendMessage("/child cmd <command>"); return true; }
                String c = String.join(" ", Arrays.copyOfRange(a, 1, a.length));
                s.sendMessage(sendCommand(c) ? "Sent: " + c : "Child not running.");
            }
            case "config" -> {
                if (a.length < 4 || !a[1].equalsIgnoreCase("set")) { s.sendMessage("/child config set <key.path> <value>  (true/false/number/text; lists: a,b,c with [list])"); return true; }
                String val = String.join(" ", Arrays.copyOfRange(a, 3, a.length));
                getConfig().set(a[2], parseValue(val));
                saveConfigWritable();
                s.sendMessage("config.yml: " + a[2] + " = " + getConfig().get(a[2]) + "  (applies on /child restart)");
            }
            case "prop" -> {
                if (a.length < 3 || !a[1].equalsIgnoreCase("set")) { s.sendMessage("/child prop set <key> <value>"); return true; }
                String val = a.length > 3 ? String.join(" ", Arrays.copyOfRange(a, 3, a.length)) : "";
                try {
                    setChildProperty(a[2], val);
                    s.sendMessage("child server.properties: " + a[2] + "=" + val + "  (applies on /child restart)");
                } catch (IOException e) { s.sendMessage("Failed: " + e.getMessage()); }
            }
            case "diag" -> {
                Map<String, Object> d = diagMap();
                s.sendMessage("running=" + d.get("running") + " pid=" + d.get("pid") + " tls=" + d.get("tls") + " status=" + d.get("status"));
                s.sendMessage("rssMb=" + d.get("rssMb") + " threads=" + d.get("threads") + " fds=" + d.get("fds") + " mspt=" + d.get("mspt") + " tps=" + d.get("tps"));
                s.sendMessage("diskBytes=" + d.get("diskBytes") + " diskFiles=" + d.get("diskFiles") + " partial=" + d.get("diskPartial"));
            }
            case "threaddump", "heap", "flags" -> {
                String cmd = switch (a[0].toLowerCase(Locale.ROOT)) { case "heap" -> "GC.heap_info"; case "flags" -> "VM.flags"; default -> "Thread.print"; };
                try {
                    String text = jcmd(cmd);
                    String[] lines = text.split("\n");
                    int n = Math.min(lines.length, 40);
                    for (int i = 0; i < n; i++) s.sendMessage(lines[i]);
                    if (lines.length > n) s.sendMessage("... " + (lines.length - n) + " more lines in the dashboard");
                } catch (IOException e) { s.sendMessage("Failed: " + e.getMessage()); }
            }
            default -> { return false; }
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 2 && (a[0].equalsIgnoreCase("config") || a[0].equalsIgnoreCase("prop"))) return List.of("set");
        if (a.length == 1) return List.of("start", "stop", "restart", "status", "cmd", "config", "prop", "diag", "threaddump", "heap", "flags", "tasks", "task", "backup", "versions", "plan", "crashes", "jar").stream().filter(x -> x.startsWith(a[0].toLowerCase(Locale.ROOT))).toList();
        if (a.length == 2 && a[0].equalsIgnoreCase("crashes")) {
            try { return crashFiles(focus).stream().filter(x -> x.startsWith(a[1])).toList(); }
            catch (IOException e) { return List.of(); }
        }
        if (a.length == 2 && a[0].equalsIgnoreCase("versions")) return List.of("fabric", "paper", "vanilla", "spigot").stream().filter(x -> x.startsWith(a[1].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }

    private void msg(CommandSender s, String m) {
        if (s == null || shuttingDown) return;
        getServer().getScheduler().runTask(this, () -> s.sendMessage(m));
    }

    // ---------------- files ----------------
    private Path prepareFiles(ServerProfile c, Path wd) throws Exception {
        String type = c.type.toLowerCase(Locale.ROOT);
        String mc = c.mcVersion;
        switch (type) {
            case "fabric": {
                Path jar = wd.resolve("fabric-server-launch-" + mc + ".jar");
                if (!Files.exists(jar)) {
                    String loader = c.fabricLoaderVersion;
                    String inst = c.fabricInstallerVersion;
                    if ("latest".equalsIgnoreCase(loader)) loader = firstStable(upstream.getJson("https://meta.fabricmc.net/v2/versions/loader/" + mc).getAsJsonArray(), true);
                    if ("latest".equalsIgnoreCase(inst)) inst = firstStable(upstream.getJson("https://meta.fabricmc.net/v2/versions/installer").getAsJsonArray(), false);
                    upstream.download("https://meta.fabricmc.net/v2/versions/loader/" + mc + "/" + loader + "/" + inst + "/server/jar", jar);
                }
                return jar;
            }
            case "spigot": {
                String javaBin = Paths.get(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
                return SpigotBuilder.build(upstream, getDataFolder().toPath(), mc, wd, javaBin, m -> { getLogger().info(m); });
            }
            case "paper": {
                Path jar = wd.resolve("paper-" + mc + ".jar");
                if (!Files.exists(jar)) {
                    JsonArray builds = upstream.getJson("https://fill.papermc.io/v3/projects/paper/versions/" + mc + "/builds").getAsJsonArray();
                    if (builds.isEmpty()) throw new IOException("No Paper builds for " + mc);
                    JsonObject pick = null;
                    for (JsonElement e : builds) { JsonObject b = e.getAsJsonObject(); if ("STABLE".equalsIgnoreCase(str(b, "channel"))) { pick = b; break; } }
                    if (pick == null) { pick = builds.get(0).getAsJsonObject(); getLogger().warning("No STABLE Paper build for " + mc + ", using build " + pick.get("id")); }
                    JsonObject dl = pick.getAsJsonObject("downloads").getAsJsonObject("server:default");
                    String sha = dl.has("checksums") && dl.getAsJsonObject("checksums").has("sha256") ? dl.getAsJsonObject("checksums").get("sha256").getAsString() : null;
                    upstream.download(dl.get("url").getAsString(), jar, "SHA-256", sha);
                }
                return jar;
            }
            case "vanilla": {
                Path jar = wd.resolve("minecraft_server-" + mc + ".jar");
                if (!Files.exists(jar)) {
                    JsonObject man = upstream.getJson("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json").getAsJsonObject();
                    String url = null;
                    for (JsonElement e : man.getAsJsonArray("versions")) if (mc.equals(str(e.getAsJsonObject(), "id"))) url = str(e.getAsJsonObject(), "url");
                    if (url == null) throw new IOException("Version " + mc + " not in Mojang manifest");
                    JsonObject v = upstream.getJson(url).getAsJsonObject();
                    JsonObject server = v.getAsJsonObject("downloads").getAsJsonObject("server");
                    upstream.download(server.get("url").getAsString(), jar, "SHA-1", server.has("sha1") ? server.get("sha1").getAsString() : null);
                }
                return jar;
            }
            case "custom": {
                Path jar = Paths.get(c.customJar);
                if (!Files.exists(jar)) throw new FileNotFoundException("Custom jar not found: " + jar);
                return jar;
            }
            default: throw new IllegalArgumentException("Unknown type: " + type);
        }
    }

    private static String firstStable(JsonArray arr, boolean loaderWrapped) {
        for (JsonElement e : arr) {
            JsonObject o = e.getAsJsonObject();
            if (loaderWrapped) o = o.getAsJsonObject("loader");
            if (o.get("stable").getAsBoolean()) return o.get("version").getAsString();
        }
        JsonObject o = arr.get(0).getAsJsonObject();
        return (loaderWrapped ? o.getAsJsonObject("loader") : o).get("version").getAsString();
    }

    private void copyPlayerFiles(Path wd) {
        Path root = Paths.get("").toAbsolutePath();
        for (String n : List.of("ops.json", "whitelist.json", "banned-players.json", "banned-ips.json", "usercache.json")) {
            Path src = root.resolve(n), dst = wd.resolve(n);
            try {
                if (Files.exists(src) && !Files.exists(dst)) { Files.copy(src, dst); getLogger().info("Copied " + n + " into child dir"); }
            } catch (IOException e) { getLogger().warning("Could not copy " + n + ": " + e); }
        }
    }

    static Object parseValue(String v) {
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) return Boolean.parseBoolean(v);
        if (v.matches("-?\\d{1,9}")) return Integer.parseInt(v);
        if (v.matches("-?\\d+\\.\\d+")) return Double.parseDouble(v);
        if (v.startsWith("[list]")) { List<String> l = new ArrayList<>(); for (String x : v.substring(6).split(",")) if (!x.isBlank()) l.add(x.trim()); return l; }
        return v;
    }

    /** Sets one key in the focused child's server.properties and, if the key is managed, in config too. */
    void setChildProperty(String key, String value) throws IOException { setChildProperty(focus, key, value); }

    void setChildProperty(String id, String key, String value) throws IOException {
        Path wd = profile(id).directory();
        Files.createDirectories(wd);
        updateProperties(wd.resolve("server.properties"), Map.of(key, value), Map.of());
        java.util.Properties p = new java.util.Properties();
        p.setProperty(key, value);
        syncPropertiesEdit(id, p);
    }

    /** Line-based edit: keeps comments, order and all other keys. force = always set, defaults = only if absent. */
    static void updateProperties(Path f, Map<String, String> force, Map<String, String> defaults) throws IOException {
        PropertiesFile.update(f, force, defaults);
    }

    private void writeProperties(ServerProfile c, Path wd) throws IOException {
        Map<String, String> force = new LinkedHashMap<>();
        if (c.serverProperties != null) force.putAll(c.serverProperties);
        force.putAll(forcedProperties(c));
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("level-name", "world");
        defaults.put("white-list", "false");
        defaults.put("enforce-whitelist", "false");
        defaults.put("enable-rcon", "false");
        defaults.put("enable-query", "false");
        updateProperties(wd.resolve("server.properties"), force, defaults);
    }

    static Map<String, String> forcedProperties(ServerProfile profile) {
        Map<String, String> force = new LinkedHashMap<>();
        force.put("server-port", String.valueOf(profile.port));
        force.put("server-ip", profile.serverIp == null ? "" : profile.serverIp);
        force.put("pause-when-empty-seconds", "0");
        return force;
    }

    List<PropertiesFile.Preview> serverPropertiesPreview(String id) throws IOException {
        ServerProfile profile = profile(id);
        Path directory = profile.directory();
        Path properties = null;
        if (Files.exists(directory)) {
            if (!Files.isDirectory(directory)) throw new IOException("Child working path is not a directory");
            properties = PathGuard.open(directory).resolve("server.properties");
        }
        Map<String, String> force = new LinkedHashMap<>();
        if (profile.serverProperties != null) force.putAll(profile.serverProperties);
        force.putAll(forcedProperties(profile));
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("level-name", "world");
        defaults.put("white-list", "false");
        defaults.put("enforce-whitelist", "false");
        defaults.put("enable-rcon", "false");
        defaults.put("enable-query", "false");
        return PropertiesFile.preview(properties, force, defaults);
    }

    static String javaBin() {
        return Paths.get(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
    }

    private static String str(JsonObject o, String k) { JsonElement e = o.get(k); return e == null || e.isJsonNull() ? null : e.getAsString(); }
    private static boolean isWindows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"); }
}
