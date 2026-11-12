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
            assertTrue(script.contains("$('t-'+k).classList.toggle('hide',k!==t)"), "tab() must toggle panels");
            assertTrue(script.contains("$('nb-'+k).classList.toggle('on',k===t)"), "tab() must toggle nav buttons");
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
        assertTrue(workflow.contains("Vorhandenen Ordner einbinden") || workflow.contains("submit(\"attach\")"));
        assertTrue(workflow.contains("Konfiguration speichern") || workflow.contains("submit(\"new\")"));
        assertTrue(workflow.contains("Host minimieren"));
        assertTrue(workflow.contains("Host-Grundeinrichtung"));
        assertTrue(wizard.contains("export interface SetupState"));
        assertTrue(wizard.contains("onComplete"));
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
        assertEquals("old", config.getString("focusServer"));

        boolean unchanged = ServerHosterPlugin.migrateLegacyRestartPolicy(config, "2.4.2");
        assertFalse(unchanged, "same version must not rewrite pluginVersion or restart defaults");
        assertEquals(5, config.getInt("autoRestart.maxAttempts"));
        assertTrue(ServerHosterPlugin.migrateLegacyRestartPolicy(config, "2.4.4"));
        assertEquals("2.4.4", config.getString("metadata.pluginVersion"));
        assertEquals(5, config.getInt("autoRestart.maxAttempts"));

        // Mirror the startup drop performed by migrateRestartPolicy() for retired keys.
        boolean droppedMultiServer = config.contains("servers") || config.contains("focusServer")
                || config.contains("tasks");
        assertTrue(droppedMultiServer);
        config.set("servers", null);
        config.set("focusServer", null);
        config.set("tasks", null);
        config.save(configFile.toFile());

        YamlConfiguration reloaded = YamlConfiguration.loadConfiguration(configFile.toFile());
        assertFalse(reloaded.contains("tasks"));
        assertFalse(reloaded.contains("servers"));
        assertFalse(reloaded.contains("focusServer"));
        assertEquals("2.4.4", reloaded.getString("metadata.pluginVersion"));
        assertEquals(2, reloaded.getInt("metadata.schemaVersion"));
        assertEquals("pending", reloaded.getString("metadata.migrationState"));
        assertEquals(1, reloaded.getInt("backup.scheduleDefaultVersion"));
        assertEquals("paper", reloaded.getString("type"));
        assertEquals("26.1", reloaded.getString("mcVersion"));
        assertEquals(25566, reloaded.getInt("port"));
        assertEquals("./prod-child", reloaded.getString("workingDir"));
        assertEquals("3G", reloaded.getString("memory.xms"));
        assertEquals("6G", reloaded.getString("memory.xmx"));
        assertEquals("[Game] ", reloaded.getString("logPrefix"));
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

    @Test void pluginYmlUsageListsExactlyTheHandledSubcommands() throws Exception {
        String pluginYml = Files.readString(Path.of("src/main/resources/plugin.yml"));
        Matcher usage = Pattern.compile("usage: /child <([a-z|]+)>").matcher(pluginYml);
        assertTrue(usage.find());
        java.util.Set<String> advertised = new java.util.TreeSet<>(List.of(usage.group(1).split("\\|")));

        String source = Files.readString(Path.of("src/main/java/dev/aichele/serverhoster/ServerHosterPlugin.java"));
        String onCommand = source.substring(source.indexOf("public boolean onCommand("),
                source.indexOf("public List<String> onTabComplete("));
        java.util.Set<String> handled = new java.util.TreeSet<>();
        Matcher caseLine = Pattern.compile("case ([^>]+)->").matcher(onCommand);
        while (caseLine.find()) {
            Matcher label = Pattern.compile("\"([a-z]+)\"").matcher(caseLine.group(1));
            while (label.find()) handled.add(label.group(1));
        }
        Matcher completion = Pattern.compile("if \\(a\\.length == 1\\) return List\\.of\\(([^)]*)\\)").matcher(source);
        assertTrue(completion.find());
        java.util.Set<String> completed = new java.util.TreeSet<>();
        Matcher name = Pattern.compile("\"([a-z]+)\"").matcher(completion.group(1));
        while (name.find()) completed.add(name.group(1));

        assertFalse(advertised.contains("clean"), "/child clean was removed in 2.1.0");
        assertTrue(handled.containsAll(advertised), "usage advertises unhandled subcommands: " + advertised);
        assertEquals(completed, advertised);
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
