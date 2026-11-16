package dev.aichele.serverhoster;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted regressions for the post-2.4.2 cleanup: dashboard script contracts after
 * removing {@code scoped()}, stable scheduled-backup IDs, and upgrade from a real
 * 2.4.2 configuration. These tests intentionally avoid a live Spigot process.
 */
class CleanupRegressionTest {
    @TempDir Path tmp;

    @Test void dashboardScriptKeepsLoginNavigationAndCsrfWithoutScoped() throws Exception {
        String script = Files.readString(Path.of("ui/src/dashboardScript.js"));
        String panels = Files.readString(Path.of("ui/src/dashboardPanels.html"));
        String app = Files.readString(Path.of("ui/src/App.tsx"));
        String hostApi = Files.readString(Path.of("ui/src/lib/hostApi.ts"));
        String editors = Files.readString(Path.of("ui/src/lib/fileEditor.ts"));
        String boot = Files.readString(Path.of("ui/src/main.tsx"));
        String boundary = Files.readString(Path.of("ui/src/components/DashboardErrorBoundary.tsx"));

        assertTrue(editors.contains("textarea[data-editor]"));
        assertFalse(editors.contains("throw new Error"));
        assertTrue(panels.contains("<textarea id=\"config\" data-editor"));
        assertTrue(panels.contains("<textarea id=\"etext\" data-editor"));
        assertFalse(panels.contains("cfg-xms"));
        assertFalse(panels.contains("cfg-port"));
        assertFalse(panels.contains("cfg-https"));
        assertTrue(script.contains("post('/api/config/form',{yaml})"));
        String settings = Files.readString(Path.of("ui/src/components/SettingsWorkflow.tsx"));
        assertTrue(settings.contains("section: \"memory\"") || settings.contains("saveSection(\"memory\""));
        assertTrue(settings.contains("saveSection(\"dashboard\""));
        assertTrue(settings.contains("saveSection(\"https\""));
        assertTrue(settings.contains("saveSection(\"restart\""));
        assertTrue(settings.contains("saveBackup"));
        assertFalse(app.contains("throw new Error("));
        assertTrue(app.contains("Editor konnten nicht gestartet werden"));
        assertTrue(app.contains("Dashboard-Skript konnte nicht starten"));
        assertTrue(boot.contains("<DashboardErrorBoundary page>"));
        assertTrue(boundary.contains("getDerivedStateFromError"));
        assertFalse(script.contains("function scoped("));
        assertTrue(script.contains("async function api(path,opt={}){opt.headers=Object.assign({'X-FL':'1'}"));
        assertTrue(script.contains("fetch(path,opt)"));
        assertTrue(script.contains("fetch('/api/login',{method:'POST',headers:{'X-FL':'1'}"));
        assertTrue(hostApi.contains("headers.set(\"X-FL\", \"1\")"));

        List<String> tabs = tabIds(script);
        assertEquals(List.of(
                "status", "console", "players", "props", "config", "files", "logs",
                "diag", "setup", "tasks", "backup", "settings"), tabs);
        for (String tab : tabs) {
            assertTrue(panels.contains("id=\"t-" + tab + "\""), "missing panel for " + tab);
            assertTrue(script.contains("panel.classList.toggle('hide',k!==t)"), "tab() must toggle panels");
        }

        for (String routeTab : List.of("status", "console", "players", "files", "logs", "props",
                "config", "settings", "diag", "setup", "tasks", "backup")) {
            assertTrue(tabs.contains(routeTab), routeTab);
            assertTrue(app.contains(routeTab + ": \"" + routeFor(routeTab) + "\"")
                            || app.contains(routeTab + ": \"/" + routeTab + "\""),
                    "App route missing for " + routeTab);
            assertTrue(Dashboard.isDashboardPage(routeFor(routeTab)), routeTab);
        }

        assertTrue(app.contains("task-workflow-root"));
        assertTrue(app.contains("backup-workflow-root"));
        assertTrue(app.contains("player-workflow-root"));
        assertTrue(app.contains("setup-workflow-root"));
        assertFalse(script.contains("/api/tasks"));
        assertTrue(script.contains("'/api/status'") || script.contains("\"/api/status\"")
                || script.contains("api('/api/status')"));
    }

    @Test void setupWorkflowKeepsWizardIncompleteFormAndPostSetupHostTools() throws Exception {
        String workflow = Files.readString(Path.of("ui/src/components/SetupWorkflow.tsx"));
        String wizard = Files.readString(Path.of("ui/src/components/SetupWizard.tsx"));
        assertTrue(workflow.contains("SetupWizard"));
        assertTrue(workflow.contains("if (state.required) return <SetupWizard"));
        assertTrue(workflow.contains("!state?.completed"));
        assertFalse(workflow.contains("Vorhandenen Ordner einbinden"));
        assertFalse(workflow.contains("submit(\"attach\")"));
        assertFalse(workflow.contains("Konfiguration speichern"));
        assertTrue(workflow.contains("Host vorbereiten"));
        assertTrue(workflow.contains("Host minimieren"));
        assertTrue(wizard.contains("export interface SetupState"));
        assertTrue(wizard.contains("onComplete"));
        assertTrue(wizard.contains("Neuen Server erstellen"));
        assertTrue(wizard.contains("Update ${upgrade?.version || \"2.5.3\"}"));
        assertTrue(wizard.contains("Child gefunden in"));
        assertTrue(wizard.contains("wurde kein Child gefunden"));
        assertTrue(wizard.contains("className=\"login-error\""));
        assertTrue(wizard.contains("\"/api/setup/child\""));
        assertFalse(wizard.contains("Neu aufsetzen"));
        assertFalse(wizard.contains("Child übernehmen"));
    }

    @Test void processProbeRssAndThreadsMatchSnapshotContract() {
        long pid = ProcessHandle.current().pid();
        Map<String, Object> snap = ProcessProbe.snapshot(pid);
        long rss = ProcessProbe.rssMb(pid);
        long threads = ProcessProbe.threads(pid);
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            assertEquals(-1L, rss);
            assertEquals(-1L, threads);
            assertEquals(-1L, ((Number) snap.get("rssMb")).longValue());
            assertEquals(-1L, ((Number) snap.get("threads")).longValue());
        } else {
            // Separate /proc reads can move slightly; assert the shared contract, not lockstep equality.
            assertTrue(rss > 0, "rssMb");
            assertTrue(threads > 0, "threads");
            assertTrue(((Number) snap.get("rssMb")).longValue() > 0, "snapshot rssMb");
            assertTrue(((Number) snap.get("threads")).longValue() > 0, "snapshot threads");
        }
        assertEquals(-1L, ProcessProbe.rssMb(0));
        assertEquals(-1L, ProcessProbe.threads(-1));
    }

    @Test void setupCompletionSnapshotStaysConsistentForConcurrentFlip() {
        Map<String, Object> before = new LinkedHashMap<>();
        ServerHosterPlugin.putSetupCompletion(before, true);
        assertEquals(true, before.get("required"));
        assertEquals(false, before.get("completed"));

        Map<String, Object> after = new LinkedHashMap<>();
        ServerHosterPlugin.putSetupCompletion(after, false);
        assertEquals(false, after.get("required"));
        assertEquals(true, after.get("completed"));

        // Emulate a torn read of the volatile flag: writing both fields from one snapshot
        // never yields required==completed.
        for (boolean required : List.of(true, false)) {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            ServerHosterPlugin.putSetupCompletion(snapshot, required);
            assertNotEquals(snapshot.get("required"), snapshot.get("completed"));
            assertEquals(!required, snapshot.get("completed"));
        }

        try {
            String source = Files.readString(Path.of("src/main/java/dev/aichele/serverhoster/ServerHosterPlugin.java"));
            assertTrue(source.contains("putSetupCompletion(result, setupRequired)"));
            assertTrue(source.contains("static void putSetupCompletion(Map<String, Object> result, boolean required)"));
            assertFalse(Pattern.compile("result\\.put\\(\"required\", setupRequired\\);\\s*result\\.put\\(\"completed\", !setupRequired\\);")
                    .matcher(source).find());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test void setupWizardCompletionNotifiesAndReloadsStatus() throws Exception {
        String wizard = Files.readString(Path.of("ui/src/components/SetupWizard.tsx"));
        assertTrue(wizard.contains("if (!await onComplete())"));
        assertTrue(wizard.contains("Der aktuelle Einrichtungsstatus konnte nicht neu geladen werden"));
        assertTrue(wizard.contains("dashboard:setup-required"));
        assertTrue(wizard.contains("detail: false"));
        assertTrue(wizard.contains("navigate(\"status\")"));
        String workflow = Files.readString(Path.of("ui/src/components/SetupWorkflow.tsx"));
        assertTrue(workflow.contains("onComplete={refresh}"));
        assertTrue(workflow.contains("Die Einrichtung ist bereits abgeschlossen")
                || Files.readString(Path.of("src/main/java/dev/aichele/serverhoster/ServerHosterPlugin.java"))
                .contains("Die Einrichtung ist bereits abgeschlossen"));
    }

    @Test void bundledDashboardAssetsKeepSameLoginAndScheduleContracts() throws Exception {
        String html;
        try (var in = CleanupRegressionTest.class.getResourceAsStream("/web/index.html")) {
            assertNotNull(in);
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher asset = Pattern.compile("src=\"(/assets/[^\"]+\\.js)\"").matcher(html);
        assertTrue(asset.find());
        String bundle;
        try (var in = CleanupRegressionTest.class.getResourceAsStream("/web/" + asset.group(1).substring(1))) {
            assertNotNull(in);
            bundle = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertFalse(bundle.contains("function scoped("));
        assertTrue(bundle.contains("X-FL"));
        assertTrue(bundle.contains("/api/login"));
        assertTrue(bundle.contains("/api/schedules"));
        assertFalse(bundle.contains("/api/tasks"));
        assertFalse(bundle.contains("function loadPlayers("));
        assertFalse(bundle.contains("function loadBackup("));
        assertFalse(bundle.contains("function loadTasks("));
        for (String path : List.of("/dashboard", "/tasks", "/backup", "/setup", "/settings"))
            assertTrue(Dashboard.isDashboardPage(path), path);
    }

    @Test void scheduledBackupIdsStayUnchangedThroughScheduleExecution() throws Exception {
        ScheduleDef schedule = new ScheduleDef(
                "nightly_backup",
                "Nachtbackup",
                true,
                ScheduleDef.Interval.DAILY,
                3,
                15,
                "Europe/Berlin",
                1_735_689_600_000L,
                List.of(
                        new ScheduleDef.Action("warn", "command", "say Backup startet"),
                        new ScheduleDef.Action("backup", "backup", "")
                ));

        List<String> commands = new ArrayList<>();
        List<String> backupIds = new ArrayList<>();
        ScheduleExecution.run(schedule, action -> {
            if (action.type().equals("backup")) {
                // Same ID the plugin used with TaskDef and still uses after its removal.
                backupIds.add(schedule.id() + "-" + action.id());
            } else {
                commands.add(action.command());
            }
        });

        assertEquals(List.of("say Backup startet"), commands);
        assertEquals(List.of("nightly_backup-backup"), backupIds);
        assertEquals("15 3 * * *", schedule.cron());
        assertEquals(1_735_689_600_000L, schedule.lastRun());
    }

    @Test void upgradeFromLivedIn242ConfigKeepsUserValuesAndDropsRetiredKeys() throws Exception {
        Path source = Path.of("src/test/resources/fixtures/upgrade-from-2.4.2-lived-in.yml");
        Path shipped = Path.of("src/test/resources/fixtures/config-shipped-2.4.2.yml");
        assertTrue(Files.exists(source));
        assertTrue(Files.exists(shipped));

        String shippedText = Files.readString(shipped);
        assertTrue(shippedText.contains("schemaVersion: 2"));
        assertTrue(shippedText.contains("migrationState: pending"));
        assertTrue(shippedText.contains("scheduleDefaultVersion: 1"));
        assertTrue(shippedText.contains("pluginVersion: 2.4.2"));

        Path data = tmp.resolve("ServerhosterPlugin");
        Files.createDirectories(data);
        Path configFile = data.resolve("config.yml");
        Files.copy(source, configFile);
        Files.writeString(data.resolve("dashboard-password.txt"), "isolated-test-password");
        Files.writeString(data.resolve("secrets.yml"), """
                backup:
                  ftp:
                    username: isolated-ftp
                    password: isolated-secret
                notifications:
                  ntfy:
                    token: isolated-ntfy-token
                """);

        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile.toFile());
        assertEquals("2.4.2", config.getString("metadata.pluginVersion"));
        assertEquals(2, config.getInt("metadata.schemaVersion"));
        assertEquals("pending", config.getString("metadata.migrationState"));
        assertEquals(1, config.getInt("backup.scheduleDefaultVersion"));
        assertTrue(config.getBoolean("setup.completed"));
        assertEquals("paper", config.getString("type"));
        assertEquals("26.1", config.getString("mcVersion"));
        assertEquals(25566, config.getInt("port"));
        assertEquals("./prod-child", config.getString("workingDir"));
        assertEquals("3G", config.getString("memory.xms"));
        assertEquals("6G", config.getString("memory.xmx"));
        assertEquals("[Game] ", config.getString("logPrefix"));
        assertEquals(7, config.getInt("backup.keep"));
        assertEquals("/tmp/serverhoster-isolated-backups", config.getString("backup.directory"));
        assertEquals(List.of("world", "world_nether"), config.getStringList("backup.paths"));
        assertTrue(config.contains("tasks"));
        assertTrue(config.contains("servers"));
        assertTrue(config.contains("memoryTelemetry"));
        assertEquals("old", config.getString("focusServer"));

        YamlConfiguration fresh = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/config.yml").toFile());
        ServerHosterPlugin.reconcileConfig(fresh, config, "2.5.0");
        fresh.save(configFile.toFile());

        YamlConfiguration reloaded = YamlConfiguration.loadConfiguration(configFile.toFile());
        assertFalse(reloaded.contains("tasks"));
        assertFalse(reloaded.contains("servers"));
        assertFalse(reloaded.contains("focusServer"));
        assertFalse(reloaded.contains("memoryTelemetry"));
        assertFalse(reloaded.contains("metadata.schemaVersion"));
        assertFalse(reloaded.contains("metadata.migrationState"));
        assertFalse(reloaded.contains("backup.scheduleDefaultVersion"));
        assertEquals("2.5.0", reloaded.getString("metadata.pluginVersion"));
        assertEquals(5, reloaded.getInt("autoRestart.maxAttempts"));
        assertEquals("paper", reloaded.getString("type"));
        assertEquals("26.1", reloaded.getString("mcVersion"));
        assertEquals(25566, reloaded.getInt("port"));
        assertEquals("./prod-child", reloaded.getString("workingDir"));
        assertEquals("3G", reloaded.getString("memory.xms"));
        assertEquals("6G", reloaded.getString("memory.xmx"));
        assertFalse(reloaded.contains("logPrefix"));
        assertEquals(7, reloaded.getInt("backup.keep"));
        assertEquals("/tmp/serverhoster-isolated-backups", reloaded.getString("backup.directory"));
        assertEquals(List.of("world", "world_nether"), reloaded.getStringList("backup.paths"));
        assertTrue(reloaded.getBoolean("setup.completed"));
        assertTrue(reloaded.getBoolean("memoryProtection.autoStopChild"));

        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : reloaded.getKeys(true)) {
            Object value = reloaded.get(key);
            if (!(value instanceof org.bukkit.configuration.ConfigurationSection)) values.put(key, value);
        }
        List<ServerProfile> profiles = Profiles.load(values);
        assertEquals(1, profiles.size());
        assertEquals("default", profiles.getFirst().id);
        assertEquals("paper", profiles.getFirst().type);
        assertEquals("26.1", profiles.getFirst().mcVersion);
        assertEquals(25566, profiles.getFirst().port);
        assertEquals("3G", profiles.getFirst().xms);
        assertEquals("6G", profiles.getFirst().xmx);
        assertEquals("./prod-child", profiles.getFirst().workingDir);

        List<ScheduleDef> schedules = ScheduleDef.load(reloaded.getList("schedules"));
        assertEquals(2, schedules.size());
        ScheduleDef nightly = schedules.stream().filter(s -> s.id().equals("nightly_backup")).findFirst().orElseThrow();
        assertEquals("Nachtbackup", nightly.name());
        assertTrue(nightly.enabled());
        assertEquals("Europe/Berlin", nightly.timezone());
        assertEquals(1_735_689_600_000L, nightly.lastRun());
        assertEquals(List.of("warn", "backup"), nightly.actions().stream().map(ScheduleDef.Action::id).toList());
        assertEquals("nightly_backup-backup", nightly.id() + "-" + nightly.actions().get(1).id());
        assertEquals("15 3 * * *", nightly.cron());

        ScheduleDef hourly = schedules.stream().filter(s -> s.id().equals("hourly_save")).findFirst().orElseThrow();
        assertEquals("save-all", hourly.actions().getFirst().command());

        BackupSecrets.Credentials secrets = BackupSecrets.load(data);
        assertEquals("isolated-ftp", secrets.username());
        assertEquals("isolated-secret", secrets.password());
        assertEquals("isolated-ntfy-token", secrets.ntfyToken());
        assertEquals("isolated-test-password", DashboardPassword.read(data.resolve("dashboard-password.txt"), false));

        assertTrue(Dashboard.csrfRejected("POST", null));
        assertFalse(Dashboard.csrfRejected("POST", "1"));
        assertFalse(Dashboard.csrfRejected("GET", null));
    }

    @Test void pluginYmlHasNoChildCommand() throws Exception {
        String pluginYml = Files.readString(Path.of("src/main/resources/plugin.yml"));
        assertFalse(pluginYml.contains("commands:"));
        assertFalse(pluginYml.contains("/child"));
        String source = Files.readString(Path.of("src/main/java/dev/aichele/serverhoster/ServerHosterPlugin.java"));
        assertFalse(source.contains("public boolean onCommand("));
        assertFalse(source.contains("onTabComplete("));
        assertFalse(source.contains("copyPlayerFiles("));
    }

    @Test void childStartAndPreviewShareOneServerPropertiesPlan() throws Exception {
        Map<String, String> defaults = ServerHosterPlugin.childPropertyDefaults();
        assertEquals(List.of("level-name", "white-list", "enforce-whitelist", "enable-rcon", "enable-query"),
                List.copyOf(defaults.keySet()));
        assertEquals("world", defaults.get("level-name"));
        assertEquals(List.of("false"), defaults.values().stream().skip(1).distinct().toList());

        String source = Files.readString(Path.of("src/main/java/dev/aichele/serverhoster/ServerHosterPlugin.java"));
        assertTrue(source.contains("updateProperties(wd.resolve(\"server.properties\"), childPropertyOverrides(c), childPropertyDefaults())"));
        assertTrue(source.contains("PropertiesFile.preview(properties, childPropertyOverrides(profile), childPropertyDefaults())"));

        ServerProfile profile = Profiles.load(Map.of("serverProperties.motd", "hi", "port", 25570)).getFirst();
        Path existing = tmp.resolve("server.properties");
        Files.writeString(existing, "level-name=survival\nmotd=old\n");
        Map<String, String> force = new LinkedHashMap<>(profile.serverProperties);
        force.putAll(ServerHosterPlugin.forcedProperties(profile, "192.0.2.4"));
        Map<String, PropertiesFile.Preview> preview = new LinkedHashMap<>();
        for (PropertiesFile.Preview row : PropertiesFile.preview(existing, force, defaults)) preview.put(row.key(), row);
        ServerHosterPlugin.updateProperties(existing, force, defaults);
        java.util.Properties written = new java.util.Properties();
        try (var in = Files.newBufferedReader(existing)) { written.load(in); }
        for (PropertiesFile.Preview row : preview.values()) assertEquals(row.next(), written.getProperty(row.key()), row.key());
        assertEquals("survival", written.getProperty("level-name"));
        assertEquals("hi", written.getProperty("motd"));
        assertEquals("25570", written.getProperty("server-port"));
    }

    @Test void profileKeepsConfiguredServerIdForBackupNames() {
        assertEquals("default", Profiles.load(Map.of()).getFirst().id);
        ServerProfile named = Profiles.load(Map.of("serverId", "prod", "serverProperties.motd", "hi",
                "serverPropertiesExtra", "ignored")).getFirst();
        assertEquals("prod", named.id);
        assertEquals(Map.of("motd", "hi"), named.serverProperties);
        assertEquals("prod", BackupBridge.safeId(named.id));
    }

    @Test void loginAndPasswordSetupShareTheLockoutSchedule() throws Exception {
        assertEquals(0, Dashboard.lockMillis(1));
        assertEquals(0, Dashboard.lockMillis(4));
        assertEquals(30_000L, Dashboard.lockMillis(5));
        assertEquals(60_000L, Dashboard.lockMillis(6));
        assertEquals(1_920_000L, Dashboard.lockMillis(11));
        assertEquals(3_600_000L, Dashboard.lockMillis(12));
        assertEquals(3_600_000L, Dashboard.lockMillis(1_000));
        String source = Files.readString(Path.of("src/main/java/dev/aichele/serverhoster/Dashboard.java"));
        assertEquals(2, source.split("lockMillis\\(n\\)", -1).length - 1);
        assertEquals(1, source.split("30_000L <<", -1).length - 1);
    }

    @Test void shippedConfigDescribesTheDashboardBlockDirectlyAboveIt() throws Exception {
        List<String> lines = Files.readAllLines(Path.of("src/main/resources/config.yml"));
        int dashboard = lines.indexOf("dashboard:");
        assertTrue(dashboard > 0);
        assertTrue(lines.get(dashboard - 1).startsWith("# Web dashboard"));
        assertFalse(lines.get(lines.indexOf("metadata:") - 1).startsWith("# Web dashboard"));
    }

    @Test void everyDashboardCssClassIsUsedByTheUiSources() throws Exception {
        String css = Files.readString(Path.of("ui/src/index.css"));
        StringBuilder sources = new StringBuilder(Files.readString(Path.of("ui/index.html")));
        try (var files = Files.walk(Path.of("ui/src"))) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(f -> f.toString().matches(".*\\.(tsx|ts|js|html)$")).toList())
                sources.append('\n').append(Files.readString(file));
        }
        java.util.Set<String> unused = new java.util.TreeSet<>();
        Matcher selector = Pattern.compile("\\.([a-zA-Z][a-zA-Z0-9_-]*)").matcher(css.replaceAll("(?s)/\\*.*?\\*/", ""));
        while (selector.find()) {
            String name = selector.group(1);
            // CodeMirror adds these classes itself at runtime.
            if (name.startsWith("CodeMirror") || name.startsWith("cm-")) continue;
            if (!Pattern.compile("(?<![A-Za-z0-9_-])" + Pattern.quote(name) + "(?![A-Za-z0-9_-])").matcher(sources).find())
                unused.add(name);
        }
        assertEquals(java.util.Set.of(), unused, "CSS classes without any UI usage");
    }

    @Test void removedLoginBlockHelperStaysGone() throws Exception {
        String hostMinimize = Files.readString(Path.of("src/main/java/dev/aichele/serverhoster/HostMinimize.java"));
        assertFalse(hostMinimize.contains("playersBlocked"), "the login block was replaced by the empty whitelist in 2.0.10");
    }

    @Test void versionChangeAsksOnlyMissingOnboardingAnswers() throws Exception {
        YamlConfiguration previous = YamlConfiguration.loadConfiguration(
                Path.of("src/test/resources/fixtures/upgrade-from-2.4.2-lived-in.yml").toFile());
        List<String> known = ServerHosterPlugin.setupAnswersPresent(previous);
        ServerHosterPlugin.OnboardingQuestions complete = ServerHosterPlugin.onboardingQuestions(known, "paper", "local", false);
        assertFalse(complete.server());
        assertFalse(complete.backup());
        assertFalse(complete.schedule());
        assertFalse(complete.ntfy());

        YamlConfiguration sparse = new YamlConfiguration();
        sparse.set("metadata.pluginVersion", "2.4.4");
        sparse.set("type", "fabric");
        ServerHosterPlugin.OnboardingQuestions gaps = ServerHosterPlugin.onboardingQuestions(
                ServerHosterPlugin.setupAnswersPresent(sparse), "fabric", "local", false);
        assertTrue(gaps.server());
        assertTrue(gaps.backup());
        assertTrue(gaps.schedule());
        assertTrue(gaps.ntfy());

        YamlConfiguration defaultsOnly = new YamlConfiguration();
        defaultsOnly.set("type", "paper");
        defaultsOnly.set("mcVersion", "26.1");
        defaultsOnly.set("port", 25566);
        defaultsOnly.set("backup.storage", "local");
        defaultsOnly.set("backup.paths", List.of("world"));
        defaultsOnly.set("backup.keep", 5);
        defaultsOnly.set("schedules", List.of());
        defaultsOnly.set("notifications.ntfy.enabled", false);
        ServerHosterPlugin.OnboardingQuestions silentDefaults = ServerHosterPlugin.onboardingQuestions(
                ServerHosterPlugin.setupAnswersPresent(defaultsOnly), "paper", "local", false);
        assertFalse(silentDefaults.server() || silentDefaults.backup() || silentDefaults.schedule() || silentDefaults.ntfy());

        defaultsOnly.set("notifications.ntfy.enabled", true);
        defaultsOnly.set("notifications.ntfy.server", "https://ntfy.sh");
        assertTrue(ServerHosterPlugin.onboardingQuestions(
                ServerHosterPlugin.setupAnswersPresent(defaultsOnly), "paper", "local", true).ntfy());

        defaultsOnly.set("notifications.ntfy.topic", "alerts");
        defaultsOnly.set("backup.storage", "remote");
        assertTrue(ServerHosterPlugin.onboardingQuestions(
                ServerHosterPlugin.setupAnswersPresent(defaultsOnly), "paper", "remote", true).backup());
        defaultsOnly.set("backup.remote.host", "ftp.example.net");
        assertFalse(ServerHosterPlugin.onboardingQuestions(
                ServerHosterPlugin.setupAnswersPresent(defaultsOnly), "paper", "remote", true).backup());

        assertTrue(ServerHosterPlugin.onboardingQuestions(known, "vanilla", "local", false).server());

        YamlConfiguration fresh = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/config.yml").toFile());
        YamlConfiguration old = new YamlConfiguration();
        old.set("metadata.pluginVersion", "2.4.4");
        old.set("type", "fabric");
        old.set("mcVersion", "26.3");
        old.set("port", 2063);
        old.set("backup.storage", "local");
        old.set("backup.paths", List.of("world"));
        old.set("backup.keep", 5);
        old.set("schedules", List.of());
        old.set("notifications.ntfy.enabled", false);
        ServerHosterPlugin.reconcileConfig(fresh, old, "2.5.0");
        assertEquals("5G", fresh.getString("memory.xmx"));
        assertEquals("2G", fresh.getString("memory.xms"));
        assertEquals(true, fresh.getBoolean("autoStart"));
        assertFalse(fresh.contains("memoryTelemetry"));
        ServerHosterPlugin.OnboardingQuestions filled = ServerHosterPlugin.onboardingQuestions(
                ServerHosterPlugin.setupAnswersPresent(old), fresh.getString("type"),
                fresh.getString("backup.storage"), fresh.getBoolean("notifications.ntfy.enabled"));
        assertFalse(filled.server() || filled.backup() || filled.schedule() || filled.ntfy());

        Path marker = tmp.resolve("setup-upgrade.yml");
        ServerHosterPlugin.rememberUpgrade(marker, previous, "2.4.2");
        ServerHosterPlugin.rememberUpgrade(marker, old, "9.9.9");
        assertEquals("2.4.2", YamlConfiguration.loadConfiguration(marker.toFile()).getString("from"));

        Path root = tmp.resolve("server");
        Files.createDirectories(root.resolve("child-server"));
        assertFalse(ServerHosterPlugin.childInstallation(root, "./child-server"));
        assertEquals("Bitte gib den Ordner an, in dem der Child liegt", ServerHosterPlugin.childDirectoryProblem(root, " "));
        assertEquals("In diesem Ordner wurde kein Child gefunden", ServerHosterPlugin.childDirectoryProblem(root, "./child-server"));
        assertFalse(ServerHosterPlugin.childDirectoryProblem(root, "/tmp/outside").isEmpty());
        Files.writeString(root.resolve("child-server").resolve("server.properties"), "motd=hi\n");
        assertTrue(ServerHosterPlugin.childInstallation(root, "./child-server"));
        assertEquals("", ServerHosterPlugin.childDirectoryProblem(root, "./child-server"));
        Path jarChild = root.resolve("jar-child");
        Files.createDirectories(jarChild);
        Files.write(jarChild.resolve("server.jar"), new byte[] {1});
        assertTrue(ServerHosterPlugin.childInstallation(root, "jar-child"));
        assertEquals("./kept", ServerHosterPlugin.setupDirectory(true, true, "update", "./kept", "./other"));
        assertEquals("./other", ServerHosterPlugin.setupDirectory(true, false, "update", "./missing", "./other"));
        assertEquals("./child-server", ServerHosterPlugin.setupDirectory(true, false, "new", "./missing", "./child-server"));
        assertThrows(IllegalArgumentException.class,
                () -> ServerHosterPlugin.setupDirectory(true, true, "new", "./kept", "./child-server"));
        assertThrows(IllegalArgumentException.class,
                () -> ServerHosterPlugin.setupDirectory(true, false, "update", "./missing", " "));

        assertTrue(ServerHosterPlugin.reopenSetupAfterVersionChange("2.4.2", "2.5.0"));
        assertFalse(ServerHosterPlugin.reopenSetupAfterVersionChange("2.5.0", "2.5.0"));
        assertFalse(ServerHosterPlugin.reopenSetupAfterVersionChange("", "2.5.0"));
    }

    private static List<String> tabIds(String script) {
        Matcher matcher = Pattern.compile("const TABS=\\[(.*)];", Pattern.DOTALL).matcher(script);
        assertTrue(matcher.find());
        List<String> ids = new ArrayList<>();
        Matcher entry = Pattern.compile("\\['([a-z]+)','[^']*']").matcher(matcher.group(1));
        while (entry.find()) ids.add(entry.group(1));
        return ids;
    }

    private static String routeFor(String tab) {
        return switch (tab) {
            case "status" -> "/dashboard";
            case "props" -> "/properties";
            case "diag" -> "/diagnostics";
            default -> "/" + tab;
        };
    }
}
