package dev.aichele.serverhoster;

import com.google.gson.*;
import org.bukkit.command.*;
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

public final class ServerHosterPlugin extends JavaPlugin {
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
    private Upstream upstream;
    private final ExecutorService exec = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "ServerhosterPlugin"); t.setDaemon(true); return t; });
    private final Map<String, ChildSlot> slots = new ConcurrentHashMap<>();
    private int consoleCap = 4000;
    private volatile List<ServerProfile> profiles = List.of();
    private volatile String focus = "default";
    private final Object childGate = new Object();
    private final Object configMutationLock = new Object();
    private final Set<String> activeSchedules = ConcurrentHashMap.newKeySet();
    private final ScheduleRunTracker scheduleRuns = new ScheduleRunTracker();
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
    private volatile boolean autoStartInitialized;
    private volatile boolean setupRequired;
    private volatile boolean hostFirstSetupRestartRequired;
    private volatile boolean hostFirstSetupBlocked;
    private volatile String lastMemoryIncidentReport = "";
    private long lastMemoryWarningAt;
    private Dashboard dashboard;
    private Thread shutdownHook;
    private volatile long shutdownHookTimeoutSeconds = 25;
    private volatile long lastTelemetryWriteAt;
    private volatile MemoryTelemetry.Reading lastReading;
    private final ChildProcessRecord.Inspector processes = ChildProcessRecord.liveInspector();
    private final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "ServerhosterPlugin-Save"); t.setDaemon(true); return t; });
    private final ScheduledExecutorService memorySched = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "ServerhosterPlugin-Memory"); t.setDaemon(true); return t; });
    private final ScheduledExecutorService healthSched = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "ServerhosterPlugin-Supervisor"); t.setDaemon(true); return t; });
    private final Object stopLock = new Object();
    private static final java.util.regex.Pattern JOIN = java.util.regex.Pattern.compile("\\]: (?:System chat: )?(\\w{1,16}) joined the game\\s*$");
    private static final java.util.regex.Pattern LEFT = java.util.regex.Pattern.compile("\\]: (?:System chat: )?(\\w{1,16}) left the game\\s*$");
    private static final java.util.regex.Pattern LIST = java.util.regex.Pattern.compile("\\]: (?:System chat: )?There are \\d+ of a max of \\d+ players online:(.*)$");

    // ---- outer (Spigot) stats, sampled on the main thread once per second ----
    volatile double outerTps = 20, outerMspt = 0; volatile int outerChunks, outerEntities, outerWorlds;
    private long lastSampleNs; private final long[] tickNs = new long[20]; private int tickIdx; private long tickStart;
    // ---- child tick stats (from 'tick query') ----
    @Override public void onLoad() {
        Path marker = hostFirstSetupMarker();
        boolean freshInstall = !Files.exists(getDataFolder().toPath(), LinkOption.NOFOLLOW_LINKS);
        saveDefaultConfig();
        try {
            if (HostFirstSetup.shouldRun(marker, freshInstall)) {
                Path child = configuredPath(getConfig().getString("workingDir", "./child-server"));
                HostFirstSetup.Result result = HostFirstSetup.apply(getWorldRoot(), marker, child, runningPluginJar(),
                        hostProtectedPaths());
                hostFirstSetupRestartRequired = result.restartRequired();
                if (result.applied()) {
                    getLogger().info("One-time host setup prepared void world " + result.worldName()
                            + "; existing worlds will only be removed after the host restarts into that world.");
                    if (!result.disabledPluginJars().isEmpty()) {
                        getLogger().info("Disabled other plugin jars for the minimal host: "
                                + String.join(", ", result.disabledPluginJars()));
                    }
                }
            } else if ("manual-required".equals(HostFirstSetup.readState(marker).get("status"))) {
                getLogger().info("Host first setup is available from the dashboard and requires explicit confirmation.");
            }
        } catch (Exception e) {
            hostFirstSetupBlocked = true;
            getLogger().log(Level.SEVERE, "One-time host setup failed; it will be retried on the next start.", e);
        }
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        if (!hostFirstSetupRestartRequired && !hostFirstSetupBlocked) {
            try {
                if ("restart-required".equals(HostFirstSetup.readState(hostFirstSetupMarker()).get("status"))) {
                    Set<String> loadedWorlds = new HashSet<>();
                    getServer().getWorlds().forEach(world -> loadedWorlds.add(world.getName()));
                    List<String> removed = HostFirstSetup.completeRestart(getWorldRoot(), hostFirstSetupMarker(),
                            configuredPath(getConfig().getString("workingDir", "./child-server")), loadedWorlds,
                            hostProtectedPaths());
                    getLogger().info("One-time host setup completed. Removed outer world folders: " + String.join(", ", removed));
                }
            } catch (Exception e) {
                hostFirstSetupBlocked = true;
                getLogger().log(Level.SEVERE, "One-time host setup cleanup failed; automatic child start is blocked.", e);
            }
        }
        try { HostMinimize.clearIdlePause(getWorldRoot().resolve("server.properties")); }
        catch (IOException e) { getLogger().warning("Could not clear outer idle pause: " + e.getMessage()); }
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

        // JVM shutdown hook: covers SIGTERM / panel restart where onDisable might not run.
        // The hook is removed from onDisable after the child stop, so a normal disable does not stop twice.
        shutdownHookTimeoutSeconds = RuntimeLimits.boundedSeconds(getConfig().getLong("shutdownHookTimeoutSeconds", 25), 25).value();
        shutdownHook = new Thread(() -> {
            shuttingDown = true;
            for (ChildSlot s : slots.values()) {
                s.wantRunning = false;
                s.stopRequested = true;
                invalidateRestarts(s);
            }
            for (ChildSlot s : List.copyOf(slots.values())) if (s.running() || s.phase == SupervisorHealth.Phase.PREPARING) {
                System.out.println("[ServerhosterPlugin] JVM shutdown hook: stopping " + s.id);
                stopProcess(s, shutdownHookTimeoutSeconds, true);
            }
        }, "ServerhosterPlugin-ShutdownHook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        long every = getConfig().getLong("saveAllIntervalMinutes", 5);
        if (every > 0) sched.scheduleAtFixedRate(() -> { for (ChildSlot s : slots.values()) if (s.running()) send(s, "save-all"); }, every, every, TimeUnit.MINUTES);
        sched.scheduleAtFixedRate(this::fireDueSchedules, 20, 20, TimeUnit.SECONDS);
        long memoryInterval = Math.max(15, getConfig().getLong("memoryProtection.sampleIntervalSeconds", 30));
        memorySched.scheduleAtFixedRate(() -> {
            try { sampleHostMemory(); }
            catch (Throwable t) { getLogger().log(Level.SEVERE, "Memory sample failed", t); }
        }, memoryInterval, memoryInterval, TimeUnit.SECONDS);
        healthSched.scheduleAtFixedRate(() -> {
            try { recoverChildSupervision(); }
            catch (Throwable t) { getLogger().log(Level.SEVERE, "Child supervisor health check failed", t); }
        }, 5, 5, TimeUnit.SECONDS);
        if (hostFirstSetupRestartRequired) {
            getLogger().warning("One-time host setup is prepared. Shutting down; restart the host in the hosting panel to load the void lobby and finish cleanup.");
            getServer().getScheduler().runTask(this, () -> getServer().shutdown());
        } else if (hostFirstSetupBlocked) {
            getLogger().severe("Automatic child start is blocked until the host first setup error is resolved.");
        } else if (setupRequired) {
            getLogger().warning("Setup is required. The child server will not start until the dashboard setup is confirmed.");
        } else if (recoveryBlockedSince == 0) {
            for (ServerProfile p : profiles) if (p.autoStart) startAsync(null, p.id);
        } else {
            getLogger().warning("Previous memory emergency detected; automatic child start is paused until memory is stable.");
        }
        autoStartInitialized = true;
        refreshHostMinimize();
        NtfyNotifier.Settings ntfy = ntfySettings();
        if (ntfy.enabled() && (ntfy.topic() == null || ntfy.topic().isBlank())) {
            getLogger().warning("ntfy notifications are enabled but notifications.ntfy.topic is empty; notifications are disabled until a topic is configured.");
        }
    }

    private void migrateRestartPolicy() {
        boolean migratedUnlimited = RestartPolicy.migrateLegacyUnlimited(
                getConfig().get("autoRestart.maxAttempts"));
        boolean changed = migrateLegacyRestartPolicy(getConfig(), getDescription().getVersion());
        boolean droppedMultiServer = getConfig().contains("servers") || getConfig().contains("focusServer")
                || getConfig().contains("tasks");
        if (droppedMultiServer) {
            getConfig().set("servers", null);
            getConfig().set("focusServer", null);
            getConfig().set("tasks", null);
        }
        if (migratedUnlimited)
            getLogger().warning("Migrated the legacy unlimited restart setting to 5 automatic attempts. Change this under Dashboard > Einstellungen if needed.");
        if (changed || droppedMultiServer) saveConfigWritable();
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
        memorySched.shutdownNow();
        healthSched.shutdownNow();
        sched.shutdownNow();
        try {
            if (shutdownHook != null) Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) { /* JVM already shutting down; hook runs/ran */ }
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

    String hostBindAddress() {
        String address = getServer().getIp();
        return address == null ? "" : address.trim();
    }

    static String effectiveBindAddress(String override, String hostAddress) {
        if (override != null && !override.isBlank()) return override.trim();
        return hostAddress == null ? "" : hostAddress.trim();
    }

    String childBindAddress(ServerProfile profile) {
        return effectiveBindAddress(profile.serverIp, hostBindAddress());
    }

    String focusId() { return focus; }
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
    ConsoleBuffer console(String id) { return slot(id).console; }
    Set<String> players(String id) { return slot(id).onlinePlayers; }
    boolean sendCommand(String c) { return sendCommand(focus, c); }
    boolean sendCommand(String id, String c) { if (c.startsWith("/")) c = c.substring(1); return send(slot(id), c); }
    void requestStart(String id) {
        startAsync(null, id);
    }
    void requestStop(String id) {
        ChildSlot s = slot(id);
        s.wantRunning = false;
        s.stopRequested = true;
        exec.submit(() -> stopBlocking(s));
    }
    void requestRestart(String id) { exec.submit(() -> { ChildSlot s = slot(id); s.wantRunning = false; stopBlocking(s); s.attempts = 0; reloadConfig(); reloadProfiles(); startAsync(null, id); }); }
    void info(String m) { info(focus, m); }
    void info(String id, String m) { getLogger().info(m); slot(id).console.add("[ServerhosterPlugin] " + m); }

    /** Writes managed server.properties keys back into config so the next start keeps the edit. */
    boolean syncPropertiesEdit(String id, java.util.Properties p) {
        ServerProfile pr = profile(id);
        boolean changed = false;
        for (String k : p.stringPropertyNames()) {
            String cur = pr.serverProperties.get(k);
            if (cur != null && !p.getProperty(k).equals(cur)) {
                getConfig().set("serverProperties." + k, parseValue(p.getProperty(k)));
                changed = true;
            }
        }
        String port = p.getProperty("server-port");
        if (port != null && !port.trim().equals(String.valueOf(pr.port))) {
            try { getConfig().set("port", Integer.parseInt(port.trim())); changed = true; } catch (NumberFormatException ignored) {}
        }
        String ip = p.getProperty("server-ip");
        if (ip != null && !ip.equals(childBindAddress(pr))) {
            getConfig().set("serverIp", ip);
            changed = true;
        }
        if (changed) { saveConfigWritable(); reloadProfiles(); }
        return changed;
    }

    boolean setupRequired() { return setupRequired; }

    String dashboardStartBlockReason() {
        if (setupRequired) return "Bitte zuerst die ServerHoster-Einrichtung abschließen.";
        if (hostFirstSetupRestartRequired || hostFirstSetupBlocked)
            return "Die Host-Grundeinrichtung ist noch nicht abgeschlossen. Bitte das Host-Log prüfen oder den Host neu starten.";
        if (shuttingDown) return "Der Host wird heruntergefahren.";
        return "";
    }

    Map<String, Object> setupView() throws IOException {
        List<Map<String, Object>> candidates = SetupDiscovery.scan(getWorldRoot()).stream()
                .map(SetupDiscovery.Candidate::view).toList();
        Map<String, Object> ntfy = Map.of(
                "enabled", getConfig().getBoolean("notifications.ntfy.enabled", false),
                "server", getConfig().getString("notifications.ntfy.server", "https://ntfy.sh"),
                "topic", getConfig().getString("notifications.ntfy.topic", ""));
        Map<String, Object> result = new LinkedHashMap<>();
        // Snapshot once: setupRequired is volatile and can flip while configureSetup finishes.
        putSetupCompletion(result, setupRequired);
        result.put("candidates", candidates);
        result.put("ntfy", ntfy);
        return result;
    }

    /** Writes a consistent required/completed pair from one boolean snapshot. */
    static void putSetupCompletion(Map<String, Object> result, boolean required) {
        result.put("required", required);
        result.put("completed", !required);
    }

    static void cleanupLegacyDirectory(Path oldData) throws IOException {
        Path root = oldData.toAbsolutePath().normalize();
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override public java.nio.file.FileVisitResult visitFile(
                    Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
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
        if (action.equals("new")) {
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
        getConfig().set("metadata.pluginVersion", getDescription().getVersion());
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
                cleanupLegacyDirectory(pendingDelete);
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
        Path selected = Path.of(path).toAbsolutePath().normalize();
        return SetupDiscovery.scan(root).stream()
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

    private Path getWorldRoot() {
        return getDataFolder().toPath().toAbsolutePath().normalize().getParent().getParent();
    }

    private void recoverPendingSetupDirectories() {
        try {
            List<SetupRecovery.Result> recovered = SetupRecovery.recover(getWorldRoot(), setupRequired,
                    path -> cleanupLegacyDirectory(path));
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

    Path workDir(String id) { return profile(id).directory(); }
    boolean dashboardTls() { return dashboard != null && dashboard.tls(); }

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
        MemoryTelemetry.Reading reading = lastReading;
        if (reading != null) {
            m.put("heapJvm", "outer");
            m.put("heapUsedMiB", reading.heap().usedMiB());
            m.put("heapCommittedMiB", reading.heap().committedMiB());
            m.put("heapMaxMiB", reading.heap().maxMiB());
            m.put("hostRssMiB", reading.hostRssMiB());
            m.put("gcJvm", "outer");
            m.put("gc", MemoryTelemetry.gcView(reading.collectors()));
            m.put("interpretation", MemoryTelemetry.INTERPRETATION);
        }
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
        view.put("recoveryPending", recoveryBlockedSince != 0 || memoryProtectionPolicy.emergencyStopStarted());
        view.put("lastIncidentReport", lastMemoryIncidentReport.isBlank() ? "" : "memory-reports/" + lastMemoryIncidentReport);
        return view;
    }

    private void maybeWriteTelemetry(HostMemoryProbe.Snapshot snapshot, MemoryTelemetry.Reading reading) {
        if (!getConfig().getBoolean("memoryTelemetry.enabled", true)) return;
        long configuredInterval = getConfig().getLong("memoryTelemetry.intervalSeconds", 60);
        long interval = configuredInterval < 15 ? 15 : RuntimeLimits.boundedSeconds(configuredInterval, 60).value();
        if (configuredInterval > 0 && configuredInterval < 15)
            warnOnce("telemetry-interval", "memoryTelemetry.intervalSeconds below 15 is raised to 15 in memory. The stored value was not changed.");
        RuntimeLimits.boundedSeconds(configuredInterval, 60).notes().forEach(note -> warnOnce("telemetry-interval-cap", note));
        long maxBytes = getConfig().getLong("memoryTelemetry.maxBytes", 5_000_000);
        if (maxBytes < 100_000) {
            warnOnce("telemetry-size", "memoryTelemetry.maxBytes below 100000 is raised to 100000 in memory. The stored value was not changed.");
            maxBytes = 100_000;
        }
        long now = System.currentTimeMillis();
        if (lastTelemetryWriteAt != 0 && now - lastTelemetryWriteAt < interval * 1000L) return;
        lastTelemetryWriteAt = now;
        try {
            String line = MemoryTelemetry.line(Instant.now(), reading.heap(), reading.hostRssMiB(), snapshot,
                    reading.childRssMiB(), reading.childSwapMiB(), reading.collectors());
            MemoryTelemetry.appendRotated(getDataFolder().toPath().resolve("memory-telemetry.log"), line, maxBytes);
        } catch (IOException e) {
            warnOnce("telemetry-write", "Could not write memory telemetry: " + e.getMessage());
        }
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
        if (!getConfig().getBoolean("memoryTelemetry.enabled", true)) {
            lastReading = null;
        } else {
            long childPid = -1;
            for (ChildSlot slot : slots.values()) {
                long pid = slot.pid();
                if (pid > 0) { childPid = pid; break; }
            }
            MemoryTelemetry.Reading reading = MemoryTelemetry.read(childPid);
            lastReading = reading;
            maybeWriteTelemetry(snapshot, reading);
        }
        synchronized (memoryHistory) {
            memoryHistory.addLast(new MemoryIncidentReport.Sample(Instant.now(), snapshot));
            while (memoryHistory.size() > 240) memoryHistory.removeFirst();
        }
        double use = snapshot.cgroupUsePercent();
        long now = System.currentTimeMillis();
        RuntimeLimits.MemoryPolicy thresholds = RuntimeLimits.memory(
                getConfig().getDouble("memoryProtection.warningPercent", 85),
                getConfig().getDouble("memoryProtection.criticalPercent", 95),
                getConfig().getDouble("memoryProtection.recoveryPercent", 80),
                getConfig().getBoolean("memoryProtection.autoStopChild", true));
        thresholds.notes().forEach(note -> warnOnce("memory-threshold:" + note, note));
        double warning = thresholds.warning();
        double critical = thresholds.critical();
        double recovery = thresholds.recovery();
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
                thresholds.autoStop());
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
            memoryProtectionPolicy.finishRecovery();
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
        if (memoryProtectionPolicy.emergencyStopStarted()) return;
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
        memoryProtectionPolicy.startEmergencyStop();
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
        facts.put("Measurement note", MemoryTelemetry.INTERPRETATION);
        facts.put("GC collectors", GcDiagnostics.snapshot().toString());
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
        List<String> list = VersionCatalog.fetch(upstream, type, Integer.MAX_VALUE);
        versionCache.put(type, list); versionCacheAt.put(type, now);
        return list;
    }

    List<ScheduleDef> schedules() {
        synchronized (configMutationLock) {
            if (getConfig().contains("schedules") && !(getConfig().get("schedules") instanceof List<?>))
                throw new IllegalArgumentException("schedules muss eine YAML-Liste sein");
            return ScheduleDef.load(getConfig().getList("schedules"));
        }
    }

    ScheduleRunTracker.Status scheduleStatus(String id) { return scheduleRuns.status(id); }
    void forgetScheduleStatus(String id) { scheduleRuns.remove(id); }

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

    String runBackup(String id) throws Exception {
        ChildSlot s = slot(id);
        synchronized (s.backupLock) {
            try {
                if (s.running()) {
                    send(s, "save-all");
                    Thread.sleep(Math.max(0, getConfig().getInt("backup.saveWaitSeconds", 3)) * 1000L);
                }
                BackupBridge.Spec spec = backupSpec();
                String db;
                if (remoteBackupSelected()) {
                    BackupSecrets.Credentials credentials = backupCredentials();
                    RemoteBackupBridge.Result r = RemoteBackupBridge.archive(profile(id).directory(), spec, remoteBackupSettings(),
                            credentials.username(), credentials.password(), id);
                    db = r.db();
                    lastBackup = r.path() + " sha256=" + r.sha256()
                            + " transport=" + (r.encrypted() ? "FTPS" : "FTP (unverschlüsselt)")
                            + (db.isBlank() ? "" : " db=" + db);
                } else {
                    BackupBridge.Result r = BackupBridge.archive(profile(id).directory(), spec, id);
                    db = r.db();
                    lastBackup = r.path() + " sha256=" + r.sha256() + (db.isBlank() ? "" : " db=" + db);
                }
                if (!db.isBlank() && !"ok".equals(db))
                    notifyNtfy("ServerHoster: Backup-Metadaten", "Das Backup wurde erstellt, aber die externen JDBC-Metadaten konnten nicht gespeichert werden.", "4");
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
                else {
                    ScheduleRunTracker.Run run = scheduleRuns.start(started.id());
                    try {
                        exec.submit(() -> runSchedule(started, run));
                    } catch (RuntimeException e) {
                        run.update("failed", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                        throw e;
                    }
                }
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
            ScheduleRunTracker.Run status = scheduleRuns.start(id);
            try {
                exec.submit(() -> runSchedule(run, status));
            } catch (RuntimeException e) {
                status.update("failed", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                throw e;
            }
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

    private void runSchedule(ScheduleDef schedule, ScheduleRunTracker.Run status) {
        try {
            ScheduleExecution.run(schedule, action -> {
                status.update("running", "");
                if (action.type().equals("backup")) {
                    runScheduledBackup(schedule.id() + "-" + action.id(), focus, status);
                } else {
                    ChildSlot target = slot(focus);
                    if (!send(target, action.command().startsWith("/") ? action.command().substring(1) : action.command()))
                        throw new IllegalStateException("Befehl konnte nicht an den Child gesendet werden");
                    target.console.add("[ServerHoster] schedule " + schedule.id() + " sent " + action.command());
                }
            });
            status.update("success", "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status.update("interrupted", "");
            getLogger().log(Level.WARNING, "Schedule " + schedule.id() + " interrupted", e);
        } catch (Exception e) {
            status.update("failed", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            getLogger().log(Level.WARNING, "Schedule " + schedule.id() + " failed", e);
            notifyNtfy("ServerHoster: Aufgabenfehler", "Zeitplan " + schedule.id() + " ist fehlgeschlagen. Details stehen im Spigot-Log.", "4");
        } finally {
            activeSchedules.remove(schedule.id());
        }
    }

    private void runScheduledBackup(String backupId, String id, ScheduleRunTracker.Run status) throws Exception {
        ChildSlot target = slot(id);
        long nextWaitNotice = 0;
        while (true) {
            while (target.running() && !target.onlinePlayers.isEmpty()) {
                if (shuttingDown) throw new InterruptedException("plugin is shutting down");
                status.update("waiting", "");
                if (System.nanoTime() >= nextWaitNotice) {
                    String message = "Scheduled backup " + backupId + " is waiting for the child to become empty.";
                    target.console.add("[ServerhosterPlugin] " + message);
                    getLogger().info(message);
                    nextWaitNotice = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
                }
                Thread.sleep(TimeUnit.SECONDS.toMillis(30));
            }

            synchronized (target.backupLock) {
                if (target.running() && !target.onlinePlayers.isEmpty()) continue;
                status.update("running", "");
                boolean wasRunning = target.running();
                boolean restartAfter = wasRunning && target.wantRunning;
                try {
                    if (wasRunning) {
                        invalidateRestarts(target);
                        target.wantRunning = false;
                        target.stopRequested = true;
                        target.phase = SupervisorHealth.Phase.STOPPING;
                        if (!stopProcess(target, getConfig().getLong("stopTimeoutSeconds", 60), false)) {
                            notifyNtfy("ServerHoster: Backup abgebrochen", "Der Child konnte vor dem geplanten Backup nicht sauber gestoppt werden; das Backup wurde abgebrochen.", "4");
                            throw new IOException("Child did not stop cleanly; scheduled backup was cancelled.");
                        }
                    }
                    runBackup(id);
                } finally {
                    if (!target.running()) target.phase = SupervisorHealth.Phase.IDLE;
                    if (restartAfter && !shuttingDown) startAsync(null, id);
                }
            }
            return;
        }
    }

    // ---------------- lifecycle ----------------
    private void startAsync(CommandSender s, String id) {
        if (hostFirstSetupRestartRequired || hostFirstSetupBlocked) {
            msg(s, "Host first setup is incomplete; resolve its logged error or restart the host before starting the child.");
            return;
        }
        if (setupRequired) {
            msg(s, "Complete the ServerHoster setup in the dashboard before starting the child server.");
            return;
        }
        ChildSlot slot = slot(id);
        invalidateRestarts(slot);
        slot.attempts = 0;
        slot.stopRequested = false;
        slot.wantRunning = true;
        if (!slot.launchInProgress.compareAndSet(false, true)) {
            msg(s, "Child start is already in progress.");
            return;
        }
        try {
            exec.submit(() -> {
                try {
                    openChild(s, id);
                } catch (Exception e) {
                    ChildSlot failedSlot = slot(id);
                    failedSlot.lastStatus = StartFailureStatus.text(e);
                    if (failedSlot.phase == SupervisorHealth.Phase.PREPARING) failedSlot.phase = SupervisorHealth.Phase.IDLE;
                    getLogger().log(Level.SEVERE, "Failed to start child " + id, e);
                    msg(s, "Start failed: " + e.getMessage());
                    scheduleRestart(failedSlot);
                } finally {
                    slot.launchInProgress.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            slot.launchInProgress.set(false);
            getLogger().log(Level.SEVERE, "Could not queue child start because the plugin is shutting down.", e);
            msg(s, "Start failed: plugin is shutting down.");
        }
    }

    /** Starts the single configured child. */
    private void openChild(CommandSender s, String id) throws Exception {
        if (!slot(id).wantRunning) return;
        synchronized (childGate) {
            ChildSlot slot = slot(id);
            if (!slot.wantRunning) return;
            if (slot.running()) { msg(s, "Already running."); return; }
            if (launch(profile(id), slot)) msg(s, "Child server started.");
            else msg(s, "Child start cancelled.");
        }
    }

    private boolean launch(ServerProfile profile, ChildSlot slot) throws Exception {
        Process started = null;
        boolean claimed = false;
        boolean stopAfterStart = false;
        try {
            synchronized (slot.lock) {
                if (slot.running()) throw new IllegalStateException("already running");
                slot.phase = SupervisorHealth.Phase.PREPARING;
                claimed = true;
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
                if (!slot.wantRunning || slot.stopRequested || shuttingDown) {
                    slot.lastStatus = "start cancelled";
                    return false;
                }
                reapLeftover();
                slot.serverReady = false;
                slot.pipeFailures = 0;
                Process p = new ProcessBuilder(cmd).directory(wd.toFile()).redirectErrorStream(true).start();
                started = p;
                slot.process = p;
                slot.startedAt = System.currentTimeMillis();
                slot.trackedStart = p.info().startInstant().orElse(null);
                slot.onlinePlayers.clear();
                slot.console.add("[ServerhosterPlugin] Child started, pid " + p.pid());
                slot.stdin = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
                slot.lastStatus = "running";
                slot.phase = SupervisorHealth.Phase.RUNNING;
                rememberProcess(p, profile, jar);
                armPipe(slot, p, consolePrefix());
                armWatch(slot, p, false);
                stopAfterStart = !slot.wantRunning || slot.stopRequested || shuttingDown;
            }
            if (stopAfterStart) stopProcess(slot, getConfig().getLong("stopTimeoutSeconds", 60), shuttingDown);
            return true;
        } catch (Exception e) {
            if (started != null) abandonFailedStart(slot, started);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (claimed && started == null && slot.phase == SupervisorHealth.Phase.PREPARING) slot.phase = SupervisorHealth.Phase.IDLE;
        }
    }

    private void pipe(ChildSlot slot, Process p, String prefix, int generation) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                slot.pipeFailures = 0;
                if (parseTick(slot, line)) continue;
                getLogger().info(prefix + line);
                slot.console.add(line);
                trackPlayers(slot, line);
                if (ChildSlot.isReadyLine(line)) {
                    slot.serverReady = true;
                    if (slot.stopRequested) exec.submit(() -> stopBlocking(slot));
                }
            }
        } catch (IOException e) {
            slot.pipeFailures++;
            getLogger().warning("Child console pipe failed for " + slot.id + ": " + e.getMessage());
        } finally {
            if (slot.pipeGeneration.get() == generation) slot.pipeArmed.set(false);
        }
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

    static void trackPlayers(ChildSlot slot, String line) {
        var m = JOIN.matcher(line); if (m.find()) { slot.onlinePlayers.add(m.group(1)); return; }
        m = LEFT.matcher(line); if (m.find()) { slot.onlinePlayers.remove(m.group(1)); return; }
        m = LIST.matcher(line);
        if (m.find()) {
            slot.onlinePlayers.clear();
            for (String n : m.group(1).split(",")) { n = n.trim(); if (!n.isEmpty()) slot.onlinePlayers.add(n); }
        }
    }

    private void watch(ChildSlot slot, Process p, int token, int generation) {
        int code = -1;
        boolean exited = false;
        try {
            code = p.waitFor();
            exited = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!p.isAlive()) {
                try {
                    code = p.exitValue();
                    exited = true;
                } catch (IllegalThreadStateException ignored) {}
            } else if (!shuttingDown && slot.process == p) {
                getLogger().warning("Child watcher for " + slot.id + " was interrupted; reattaching.");
                try {
                    armWatch(slot, p, true);
                } catch (RejectedExecutionException ex) {
                    getLogger().log(Level.SEVERE, "Could not reattach child watcher for " + slot.id, ex);
                }
                return;
            }
            if (!exited) return;
        } finally {
            if (slot.watchGeneration.get() == generation) slot.watchArmed.set(false);
        }
        long up = System.currentTimeMillis() - slot.startedAt;
        synchronized (slot.lock) { if (slot.process == p) { slot.process = null; slot.stdin = null; } }
        if (slot.phase == SupervisorHealth.Phase.RUNNING) slot.phase = SupervisorHealth.Phase.IDLE;
        slot.lastStatus = "exited (code " + code + ")";
        slot.onlinePlayers.clear();
        slot.console.add("[ServerhosterPlugin] Child exited with code " + code);
        getLogger().warning("Child " + slot.id + " exited with code " + code + " after " + up / 1000 + "s");
        try {
            if (ChildProcessRecord.deleteIfMatch(processRecord(), p.pid(), slot.trackedStart))
                getLogger().info("Removed child process record for pid " + p.pid());
        } catch (IOException e) {
            getLogger().warning("Could not remove child process record: " + e.getMessage());
        }
        if (up >= getConfig().getLong("autoRestart.resetAfterSeconds", 600) * 1000L) slot.attempts = 0;
        if (slot.wantRunning && !shuttingDown && slot.scheduleToken.get() == token) scheduleRestart(slot);
    }

    private void scheduleRestart(ChildSlot slot) {
        if (shuttingDown) return;
        if (!slot.wantRunning) {
            slot.restartQueued.set(false);
            return;
        }
        if (!getConfig().getBoolean("autoRestart.enabled", true)) {
            slot.restartQueued.set(true);
            slot.lastStatus += ", auto-restart disabled";
            getLogger().warning("Child " + slot.id + " exited and autoRestart is off.");
            return;
        }
        int max;
        try {
            max = RestartPolicy.maxAttempts(getConfig().get("autoRestart.maxAttempts",
                    RestartPolicy.DEFAULT_MAX_ATTEMPTS));
        } catch (IllegalArgumentException e) {
            getLogger().log(Level.SEVERE, "Automatic restart limit is invalid; automatic restarts are paused.", e);
            slot.wantRunning = false;
            slot.restartQueued.set(false);
            slot.lastStatus += ", auto-restart paused due to invalid settings";
            return;
        }
        if (!RestartPolicy.mayRetry(slot.attempts, max)) {
            getLogger().severe("Max restart attempts reached for " + slot.id + "; giving up.");
            notifyNtfy("ServerHoster: Neustart pausiert", "Der Child " + slot.id + " ist nach "
                    + max + " automatischen Neustartversuchen weiterhin ausgefallen. Automatische Neustarts sind pausiert; bitte Ursache prüfen und manuell starten.", "5");
            slot.wantRunning = false;
            slot.restartQueued.set(false);
            slot.lastStatus += ", gave up";
            return;
        }
        if (!slot.restartQueued.compareAndSet(false, true)) return;
        int token = slot.scheduleToken.incrementAndGet();
        long base = getConfig().getLong("autoRestart.initialDelaySeconds", 5);
        long cap = getConfig().getLong("autoRestart.maxDelaySeconds", 300);
        long delay = RestartBackoff.delaySeconds(base, cap, slot.attempts);
        slot.attempts++;
        slot.lastStatus += ", restarting in " + delay + "s (attempt " + slot.attempts + ")";
        getLogger().warning("Restarting " + slot.id + " in " + delay + "s (attempt " + slot.attempts + ")");
        notifyNtfy("ServerHoster: Automatischer Neustart", "Child " + slot.id + " ist beendet. Neustartversuch " + slot.attempts + " in " + delay + " Sekunden.", "4");
        try {
            exec.submit(() -> {
                boolean failed = false;
                try {
                    Thread.sleep(delay * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (slot.scheduleToken.get() == token) slot.restartQueued.set(false);
                    return;
                }
                if (slot.scheduleToken.get() != token || !slot.wantRunning || shuttingDown || slot.running()) {
                    if (slot.scheduleToken.get() == token) slot.restartQueued.set(false);
                    return;
                }
                try {
                    restartSingle(slot, token);
                } catch (Exception e) {
                    failed = true;
                    slot.lastStatus = StartFailureStatus.text(e);
                    getLogger().log(Level.SEVERE, "Restart failed", e);
                } finally {
                    if (slot.scheduleToken.get() == token) slot.restartQueued.set(false);
                }
                if (failed && slot.scheduleToken.get() == token) scheduleRestart(slot);
            });
        } catch (RejectedExecutionException e) {
            slot.restartQueued.set(false);
            getLogger().log(Level.SEVERE, "Could not queue child restart.", e);
        }
    }

    private void restartSingle(ChildSlot slot, int token) throws Exception {
        synchronized (childGate) {
            if (slot.scheduleToken.get() != token || !slot.wantRunning || shuttingDown || slot.running()) return;
            launch(profile(slot.id), slot);
        }
    }

    private boolean send(ChildSlot slot, String command) {
        BufferedWriter w = slot.stdin;
        if (w == null || !slot.running()) return false;
        try { synchronized (w) { w.write(command); w.newLine(); w.flush(); } return true; }
        catch (IOException e) { return false; }
    }

    /** Sends "stop", waits stopTimeoutSeconds, then kills. Blocks the caller, not the server thread when queued. */
    private void stopBlocking(ChildSlot slot) {
        invalidateRestarts(slot);
        slot.wantRunning = false;
        slot.stopRequested = true;
        slot.phase = SupervisorHealth.Phase.STOPPING;
        try {
            if (!slot.running()) return;
            stopProcess(slot, getConfig().getLong("stopTimeoutSeconds", 60), false);
        } finally {
            if (!slot.running()) slot.phase = SupervisorHealth.Phase.IDLE;
        }
    }

    /** Thread-safe (onDisable + shutdown hook may race). useStdout: logger may be gone during JVM shutdown. */
    private boolean stopProcess(ChildSlot slot, long timeout, boolean useStdout) {
        synchronized (slot.stopLock) {
            Process p = slot.process;
            if (p == null) return true;
            if (!p.isAlive()) {
                try { return p.exitValue() == 0; }
                catch (IllegalThreadStateException e) { return false; }
            }
            java.util.function.Consumer<String> log = m -> { if (useStdout) System.out.println("[ServerhosterPlugin] " + m); else { getLogger().info(m); slot.console.add("[ServerhosterPlugin] " + m); } };
            long startupSeconds = 0;
            if (!useStdout) {
                RuntimeLimits.Bounded startup = RuntimeLimits.boundedSeconds(getConfig().getLong("startupStopTimeoutSeconds", 180), 180);
                startup.notes().forEach(note -> warnOnce("startup-stop", note));
                startupSeconds = startup.value();
            }
            RuntimeLimits.Bounded stop = RuntimeLimits.boundedSeconds(timeout, 60);
            if (!useStdout) stop.notes().forEach(note -> warnOnce("stop-timeout", note));
            ChildStopSequence.Outcome outcome = ChildStopSequence.stop(p, () -> slot.serverReady, startupSeconds, stop.value(),
                    250, 5_000, 10, () -> {
                        BufferedWriter w = slot.stdin;
                        if (w == null) throw new IOException("child console is not ready");
                        synchronized (w) { w.write("stop"); w.newLine(); w.flush(); }
                    }, log, slot.id);
            return outcome == ChildStopSequence.Outcome.CLEAN;
        }
    }

    private void invalidateRestarts(ChildSlot slot) {
        slot.scheduleToken.incrementAndGet();
        slot.restartQueued.set(false);
    }

    private void recoverChildSupervision() {
        if (shuttingDown) return;
        for (ChildSlot slot : List.copyOf(slots.values())) {
            Process process = slot.process;
            boolean alive = process != null && process.isAlive();
            SupervisorHealth.Action action = SupervisorHealth.assess(new SupervisorHealth.State(
                    slot.phase, shuttingDown, slot.wantRunning, slot.stopRequested, alive,
                    slot.watchArmed.get(), slot.pipeArmed.get(), slot.restartQueued.get(), slot.launchInProgress.get()));
            switch (action) {
                case REATTACH_WATCH -> {
                    getLogger().warning("Reattaching child watcher for " + slot.id);
                    armWatch(slot, process, false);
                }
                case REATTACH_PIPE -> {
                    getLogger().warning("Reattaching child console pipe for " + slot.id);
                    armPipe(slot, process, consolePrefix());
                }
                case REATTACH_BOTH -> {
                    getLogger().warning("Reattaching child watcher and console pipe for " + slot.id);
                    armWatch(slot, process, false);
                    armPipe(slot, process, consolePrefix());
                }
                case SCHEDULE_RESTART -> {
                    getLogger().warning("No child watcher is attached after " + slot.id + " exited; scheduling restart.");
                    scheduleRestart(slot);
                }
                case NONE -> {}
            }
        }
    }

    private void armWatch(ChildSlot slot, Process process, boolean replace) {
        synchronized (slot.watchArmed) {
            if (process == null || !process.isAlive()) return;
            if (slot.watchArmed.get() && !replace) return;
            int generation = slot.watchGeneration.incrementAndGet();
            int token = slot.scheduleToken.get();
            slot.watchArmed.set(true);
            try {
                exec.submit(() -> watch(slot, process, token, generation));
            } catch (RejectedExecutionException e) {
                if (slot.watchGeneration.get() == generation) slot.watchArmed.set(false);
                throw e;
            }
        }
    }

    private void armPipe(ChildSlot slot, Process process, String prefix) {
        synchronized (slot.pipeArmed) {
            if (process == null || !process.isAlive()) return;
            if (slot.pipeFailures >= 3) {
                if (slot.pipeFailures == 3) {
                    slot.pipeFailures = 4;
                    getLogger().severe("Child console pipe failed repeatedly for " + slot.id
                            + "; not reattaching. The child can block if its output buffer fills.");
                }
                return;
            }
            if (slot.pipeArmed.get()) return;
            int generation = slot.pipeGeneration.incrementAndGet();
            slot.pipeArmed.set(true);
            try {
                exec.submit(() -> pipe(slot, process, prefix, generation));
            } catch (RejectedExecutionException e) {
                if (slot.pipeGeneration.get() == generation) slot.pipeArmed.set(false);
                throw e;
            }
        }
    }

    private String consolePrefix() {
        return getConfig().getString("logPrefix", "[Child] ");
    }

    private Path processRecord() {
        return getDataFolder().toPath().resolve("child-process.record");
    }

    private void rememberProcess(Process process, ServerProfile profile, Path jar) {
        try {
            ChildProcessRecord.write(processRecord(), new ChildProcessRecord.Identity(
                    process.pid(), process.info().startInstant().orElse(null),
                    profile.directory().toAbsolutePath().normalize().toString(),
                    jar.toString(), jar.toAbsolutePath().normalize().toString(), javaBin()));
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not persist child process identity; leftover recovery will refuse to kill an unmatched process.", e);
        }
    }

    private void reapLeftover() throws IOException, InterruptedException {
        ChildProcessRecord.ReapResult result = ChildProcessRecord.reap(processRecord(), Duration.ofSeconds(10),
                Duration.ofSeconds(5), processes, ProcessHandle.current().pid());
        switch (result.kind()) {
            case NO_RECORD -> {}
            case CLEARED -> getLogger().info("Removed stale child process record: " + result.detail());
            case INVALID_RECORD -> getLogger().warning("Child process record is not usable; not killing any process. " + result.detail());
            case REFUSED -> getLogger().warning("Leftover PID is not the recorded child; not killing it. " + result.detail());
            case REAPED -> getLogger().warning("Stopped a leftover child process before start. " + result.detail());
            case STILL_ALIVE -> throw new IOException("Leftover child is still running after a stop attempt; refusing to start a second process. " + result.detail());
        }
    }

    private void abandonFailedStart(ChildSlot slot, Process process) {
        ChildLaunchCleanup.Result cleanup = ChildLaunchCleanup.abandon(process, 2_000);
        String detail = cleanup.detail() == null || cleanup.detail().isBlank() ? "no extra detail" : cleanup.detail();
        getLogger().warning("Cleaned up child after a failed start (pid " + process.pid() + ", exited=" + cleanup.exited() + "): " + detail);
        synchronized (slot.lock) {
            if (slot.process == process) { slot.process = null; slot.stdin = null; }
        }
        slot.watchArmed.set(false);
        slot.pipeArmed.set(false);
        slot.phase = SupervisorHealth.Phase.IDLE;
        try {
            ChildProcessRecord.deleteIfMatch(processRecord(), process.pid(), slot.trackedStart);
        } catch (IOException e) {
            getLogger().warning("Could not remove child process record after the failed start: " + e.getMessage());
        }
    }

    private void warnOnce(String key, String message) {
        if (memoryBudgetWarnings.add(key)) getLogger().warning(message);
    }

    private Path hostMinimizeStateFile() {
        return getDataFolder().toPath().resolve("host-minimize-state.txt");
    }

    private Path hostFirstSetupMarker() {
        return getDataFolder().toPath().resolve("host-first-setup-state.txt");
    }

    private Path runningPluginJar() throws Exception {
        java.security.CodeSource source = ServerHosterPlugin.class.getProtectionDomain().getCodeSource();
        if (source == null) throw new IOException("Could not identify the running ServerHoster plugin jar");
        return Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
    }

    private List<Path> hostProtectedPaths() {
        List<Path> paths = new ArrayList<>();
        paths.add(getDataFolder().toPath().toAbsolutePath().normalize());
        Path root = getWorldRoot();
        String configured = getConfig().getString("workingDir", "./child-server");
        Path child = Path.of(configured == null ? "./child-server" : configured);
        if (!child.isAbsolute()) child = root.resolve(child);
        paths.add(child.normalize());
        for (ServerProfile profile : profiles) paths.add(profile.directory().toAbsolutePath().normalize());
        String backup = getConfig().getString("backup.directory", "");
        if (backup != null && !backup.isBlank()) {
            Path backupPath = Path.of(backup);
            if (!backupPath.isAbsolute()) backupPath = root.resolve(backupPath);
            paths.add(backupPath.normalize());
        }
        return paths;
    }

    private Map<String, String> readHostMinimizeState() throws IOException {
        Path file = hostMinimizeStateFile();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Map.of();
        return HostMinimize.readState(Files.readString(file));
    }

    private void writeHostMinimizeState(String status, String lobbyName, String backup, boolean restartRequired,
                                        String heapPrevious) throws IOException {
        Path file = hostMinimizeStateFile();
        if (Files.isSymbolicLink(file)) throw new SecurityException("host minimization state must not be a symlink");
        Files.writeString(file, HostMinimize.stateText(status, lobbyName, backup, restartRequired, heapPrevious));
    }

    private static String heapPrevious(List<HostHeapTune.Result> results) {
        StringBuilder encoded = new StringBuilder();
        for (HostHeapTune.Result result : results) {
            if (!result.applied() || result.previous().isBlank() || result.previous().indexOf(';') >= 0) continue;
            if (!encoded.isEmpty()) encoded.append(';');
            encoded.append(result.name()).append(':').append(result.previous());
        }
        return encoded.toString();
    }

    private void restoreHostHeap(String encoded) {
        if (encoded == null || encoded.isBlank()) return;
        HostHeapTune.Options options = HostHeapTune.hotspot();
        for (String part : encoded.split(";")) {
            int colon = part.indexOf(':');
            if (colon <= 0) continue;
            String name = part.substring(0, colon);
            String value = part.substring(colon + 1);
            try {
                if (options.manageable(name)) options.set(name, value);
            } catch (Exception e) {
                getLogger().warning("Could not restore outer heap option " + name + ": " + e.getMessage());
            }
        }
    }

    private Path configuredPath(String configured) {
        Path path = Paths.get(configured == null || configured.isBlank() ? "./child-server" : configured);
        if (!path.isAbsolute()) path = getWorldRoot().resolve(path);
        return path.toAbsolutePath().normalize();
    }

    Map<String, Object> hostMinimizeView() throws IOException {
        Map<String, String> state = readHostMinimizeState();
        String status = state.getOrDefault("status", "inactive");
        String worldName = configuredHostWorld();
        HostMinimize.Preview preview = HostMinimize.preview(getWorldRoot(), hostProtectedPaths());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("status", status);
        view.put("restartRequired", Boolean.parseBoolean(state.getOrDefault("restartRequired", "false")));
        view.put("worldName", worldName);
        Map<String, String> firstSetup = HostFirstSetup.readState(hostFirstSetupMarker());
        view.put("firstSetupStatus", firstSetup.getOrDefault("status", "not-run"));
        view.put("firstSetupWorld", firstSetup.getOrDefault("worldName", ""));
        view.put("firstSetupDisabledPluginCount", firstSetup.getOrDefault("disabledPluginCount", "0"));
        view.put("firstSetupConfirmation", HostFirstSetup.MANUAL_CONFIRM);
        view.put("confirmation", HostMinimize.CONFIRM);
        view.put("rollbackConfirmation", HostMinimize.ROLLBACK_CONFIRM);
        view.put("changes", preview.changes().stream().map(change -> Map.of(
                "file", change.file(), "key", change.key(),
                "from", change.from() == null ? "" : change.from(), "to", change.to())).toList());
        view.put("notes", preview.notes());
        view.put("childRunning", focusSlot().running());
        return view;
    }

    Map<String, Object> hostFirstSetupRequest(String confirm) throws IOException {
        HostFirstSetup.requestManual(hostFirstSetupMarker(), confirm);
        getLogger().warning("Manual host first setup confirmed. The host will shut down; follow the subsequent panel restart instructions to complete setup safely.");
        getServer().getScheduler().runTask(this, () -> getServer().shutdown());
        return Map.of("firstSetupStatus", "requested", "shutdownScheduled", true);
    }

    private String configuredHostWorld() throws IOException {
        Path properties = getWorldRoot().resolve("server.properties");
        if (!Files.isRegularFile(properties, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(properties))
            return "";
        String level = "";
        for (String line : Files.readAllLines(properties)) {
            if (line.startsWith("level-name=")) level = line.substring("level-name=".length()).trim();
        }
        return HostMinimize.worldNameSafe(level) ? level : "";
    }

    Map<String, Object> hostMinimizeApply(String confirm) throws IOException {
        if (!HostMinimize.CONFIRM.equals(confirm))
            throw new IllegalArgumentException("Die Bestätigung stimmt nicht. Erforderlich ist exakt: " + HostMinimize.CONFIRM);
        ChildSlot slot = focusSlot();
        if (slot.running()) {
            stopBlocking(slot);
            if (slot.running()) {
                slot.wantRunning = true;
                slot.stopRequested = false;
                slot.phase = SupervisorHealth.Phase.RUNNING;
                Process process = slot.process;
                if (process != null) armWatch(slot, process, true);
                throw new IOException("Der Child läuft noch. Host-Dateien wurden nicht geändert.");
            }
        }
        List<Path> protectedPaths = hostProtectedPaths();
        String worldName = configuredHostWorld();
        if (worldName.isBlank()) throw new IOException("The outer host world name is missing or unsafe.");
        String backupId = HostMinimize.newLobbyName().substring("serverhoster-lobby-".length());
        Path backup = getDataFolder().toPath().resolve("host-minimize-backup").resolve(backupId);
        HostMinimize.apply(getWorldRoot(), backup, protectedPaths);
        List<HostHeapTune.Result> tuned = HostHeapTune.apply(HostHeapTune.hotspot());
        List<Map<String, Object>> heap = tuned.stream().map(result -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", result.name());
            row.put("value", result.value());
            row.put("previous", result.previous());
            row.put("applied", result.applied());
            row.put("detail", result.detail());
            return row;
        }).toList();
        String relativeBackup = getDataFolder().toPath().relativize(backup).toString().replace('\\', '/');
        try {
            writeHostMinimizeState("staged", worldName, relativeBackup, true, heapPrevious(tuned));
        } catch (IOException e) {
            try {
                HostMinimize.restore(getWorldRoot(), backup, protectedPaths);
            } catch (IOException restoreError) {
                e.addSuppressed(restoreError);
            }
            throw e;
        }
        Map<String, Object> view = hostMinimizeView();
        view.put("heap", heap);
        view.put("restartRequired", true);
        view.put("message", "Host-Minimierung ist vorbereitet. Welten und Weltgenerator blieben unverändert. Der äußere Spigot muss im Panel neu gestartet werden. -Xmx wurde nicht geändert.");
        return view;
    }

    Map<String, Object> hostMinimizeRollback(String confirm) throws IOException {
        if (!HostMinimize.ROLLBACK_CONFIRM.equals(confirm))
            throw new IllegalArgumentException("Die Bestätigung stimmt nicht. Erforderlich ist exakt: " + HostMinimize.ROLLBACK_CONFIRM);
        Map<String, String> state = readHostMinimizeState();
        String relative = state.getOrDefault("backup", "");
        if (relative.isBlank()) throw new IllegalStateException("Es gibt keine Host-Minimierungs-Sicherung");
        Path backup = getDataFolder().toPath().resolve(relative).normalize();
        if (!backup.startsWith(getDataFolder().toPath().toAbsolutePath().normalize()))
            throw new SecurityException("Die Sicherung liegt außerhalb des Plugin-Ordners");
        HostMinimize.restore(getWorldRoot(), backup, hostProtectedPaths());
        restoreHostHeap(state.getOrDefault("heapPrevious", ""));
        Files.deleteIfExists(hostMinimizeStateFile());
        Map<String, Object> view = hostMinimizeView();
        view.put("message", "Gesicherte Host-Dateien wurden zurückgeschrieben. Ein äußerer Neustart ist nötig, wenn der Host schon mit der neuen Welt gestartet wurde. Welten wurden nicht gelöscht.");
        return view;
    }

    private void refreshHostMinimize() {
        try {
            Map<String, String> state = readHostMinimizeState();
            if (!"staged".equals(state.get("status")) && !"active".equals(state.get("status"))) return;
            String worldName = state.getOrDefault("lobbyName", "");
            if (!HostMinimize.worldNameSafe(worldName)) return;
            for (HostHeapTune.Result result : HostHeapTune.apply(HostHeapTune.hotspot()))
                getLogger().info("Host heap option " + result.name() + ": " + result.detail());
            String level = "";
            Path properties = getWorldRoot().resolve("server.properties");
            if (Files.isRegularFile(properties, LinkOption.NOFOLLOW_LINKS)) {
                for (String line : Files.readAllLines(properties)) {
                    if (line.startsWith("level-name=")) level = line.substring("level-name=".length()).trim();
                }
            }
            Map<String, String> firstSetup = HostFirstSetup.readState(hostFirstSetupMarker());
            String preparedLobby = firstSetup.getOrDefault("worldName", "");
            boolean preparedLoaded = getServer().getWorlds().stream().anyMatch(world -> preparedLobby.equals(world.getName()));
            String reconciled = HostMinimize.firstSetupWorld(worldName, level, firstSetup.get("status"),
                    preparedLobby, preparedLoaded);
            if (!worldName.equals(reconciled)) {
                worldName = reconciled;
                getLogger().info("Host minimization now targets the completed first-setup lobby " + worldName + ".");
            }
            String selectedWorld = worldName;
            boolean loaded = getServer().getWorlds().stream().anyMatch(world -> selectedWorld.equals(world.getName()));
            String phase = HostMinimize.phase(worldName, level, loaded);
            boolean active = "active".equals(phase);
            writeHostMinimizeState(phase, worldName, state.getOrDefault("backup", ""), !active,
                    state.getOrDefault("heapPrevious", ""));
            if (active) {
                String target = worldName;
                getServer().getScheduler().runTask(this, () -> applyLobbyRules(target));
            } else {
                getLogger().warning(worldName.equals(level)
                        ? "Host minimization stays staged. The outer world " + worldName + " is not loaded."
                        : "Host minimization is staged. Restart the outer Spigot from the panel to apply its settings.");
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Could not refresh host minimization", e);
        }
    }

    private void applyLobbyRules(String lobby) {
        int changed = HostLobbyRuntime.apply(getServer().getWorlds().stream().map(world -> (HostLobbyRuntime.World) new HostLobbyRuntime.World() {
            @Override public String name() { return world.getName(); }
            @Override public void spawnFlags(boolean monsters, boolean animals) { world.setSpawnFlags(monsters, animals); }
            @Override public void keepSpawnInMemory(boolean keep) { world.setKeepSpawnInMemory(keep); }
            @Override public void ruleOff(String name) { setOuterRuleOff(world, name); }
        }).toList(), lobby);
        if (changed > 0) getLogger().info("Applied outer lobby runtime limits to " + changed + " world(s) named " + lobby);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void setOuterRuleOff(org.bukkit.World world, String name) {
        org.bukkit.GameRule rule = org.bukkit.GameRule.getByName(name);
        if (rule == null) return;
        if (Boolean.class.equals(rule.getType())) world.setGameRule(rule, false);
        else if (Integer.class.equals(rule.getType())) world.setGameRule(rule, 0);
    }

    // ---------------- commands ----------------
    @Override public boolean onCommand(CommandSender s, Command command, String label, String[] a) {
        if (!s.hasPermission("serverhoster.admin")) { s.sendMessage("No permission."); return true; }
        if (a.length == 0) return false;
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "start" -> { s.sendMessage("Starting " + focus + " (downloads may take a while)..."); startAsync(s, focus); }
            case "stop" -> { focusSlot().wantRunning = false; s.sendMessage("Stopping " + focus + "..."); ChildSlot cur = focusSlot(); exec.submit(() -> { stopBlocking(cur); msg(s, "Child stopped."); }); }
            case "restart" -> { s.sendMessage("Restarting " + focus + "..."); requestRestart(focus); }
            case "status" -> {
                ChildSlot cur = focusSlot();
                ServerProfile pr = profile(focus);
                s.sendMessage(cur.id + ": " + (cur.running() ? "RUNNING pid " + cur.pid() + ", up " + cur.uptimeMillis() / 1000 + "s" : "NOT RUNNING") + " | " + cur.lastStatus);
                s.sendMessage("Type " + pr.type + " " + pr.mcVersion + " on port " + pr.port + ", dir " + pr.directory());
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
                exec.submit(() -> { try { for (String v : versions(a[1]).stream().limit(40).toList()) msg(s, v); } catch (Exception e) { msg(s, "Versions failed: " + e.getMessage()); } });
            }
            case "host" -> {
                if (a.length < 2) { s.sendMessage("/child host <preview|apply|rollback>"); return true; }
                switch (a[1].toLowerCase(Locale.ROOT)) {
                    case "preview" -> exec.submit(() -> { try { msg(s, hostMinimizeView().toString()); } catch (Exception e) { msg(s, "Host-Vorschau fehlgeschlagen: " + e.getMessage()); } });
                    case "apply" -> {
                        String confirm = a.length >= 3 ? String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)) : "";
                        exec.submit(() -> { try { msg(s, String.valueOf(hostMinimizeApply(confirm).get("message"))); } catch (Exception e) { msg(s, "Host-Minimierung fehlgeschlagen: " + e.getMessage()); } });
                    }
                    case "rollback" -> {
                        String confirm = a.length >= 3 ? String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)) : "";
                        exec.submit(() -> { try { msg(s, String.valueOf(hostMinimizeRollback(confirm).get("message"))); } catch (Exception e) { msg(s, "Host-Rücknahme fehlgeschlagen: " + e.getMessage()); } });
                    }
                    default -> s.sendMessage("/child host <preview|apply|rollback>");
                }
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
        if (a.length == 1) return List.of("start", "stop", "restart", "status", "cmd", "config", "prop", "diag", "threaddump", "heap", "flags", "backup", "versions", "plan", "crashes", "jar", "host").stream().filter(x -> x.startsWith(a[0].toLowerCase(Locale.ROOT))).toList();
        if (a.length == 2 && a[0].equalsIgnoreCase("host")) return List.of("preview", "apply", "rollback").stream().filter(x -> x.startsWith(a[1].toLowerCase(Locale.ROOT))).toList();
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
                return SpigotBuilder.build(upstream, getDataFolder().toPath(), mc, wd, javaBin(), m -> { getLogger().info(m); });
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
                Path jar = LaunchPlan.jarPath(c);
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
        updateProperties(wd.resolve("server.properties"), childPropertyOverrides(c), childPropertyDefaults());
    }

    /** Configured serverProperties plus the keys ServerHoster always writes; shared by start and preview. */
    private Map<String, String> childPropertyOverrides(ServerProfile profile) {
        Map<String, String> force = new LinkedHashMap<>();
        if (profile.serverProperties != null) force.putAll(profile.serverProperties);
        force.putAll(forcedProperties(profile, hostBindAddress()));
        return force;
    }

    /** Values written only when the child's server.properties does not set them yet. */
    static Map<String, String> childPropertyDefaults() {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("level-name", "world");
        defaults.put("white-list", "false");
        defaults.put("enforce-whitelist", "false");
        defaults.put("enable-rcon", "false");
        defaults.put("enable-query", "false");
        return defaults;
    }

    static Map<String, String> forcedProperties(ServerProfile profile, String hostAddress) {
        Map<String, String> force = new LinkedHashMap<>();
        force.put("server-port", String.valueOf(profile.port));
        force.put("server-ip", effectiveBindAddress(profile.serverIp, hostAddress));
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
        return PropertiesFile.preview(properties, childPropertyOverrides(profile), childPropertyDefaults());
    }

    static String javaBin() {
        return Paths.get(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
    }

    private static String str(JsonObject o, String k) { JsonElement e = o.get(k); return e == null || e.isJsonNull() ? null : e.getAsString(); }
    private static boolean isWindows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"); }
}
