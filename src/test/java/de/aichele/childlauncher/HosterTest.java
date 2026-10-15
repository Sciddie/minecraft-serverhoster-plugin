package de.aichele.childlauncher;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HosterTest {
    @TempDir Path tmp;

    @Test void ftpAutoDetectionNeverDowngradesAfterTlsIsOffered() {
        assertTrue(RemoteBackupBridge.unsupportedAuthTls(500));
        assertTrue(RemoteBackupBridge.unsupportedAuthTls(501));
        assertTrue(RemoteBackupBridge.unsupportedAuthTls(502));
        assertTrue(RemoteBackupBridge.unsupportedAuthTls(504));
        assertFalse(RemoteBackupBridge.unsupportedAuthTls(234));
        assertFalse(RemoteBackupBridge.unsupportedAuthTls(530));
        assertFalse(RemoteBackupBridge.unsupportedAuthTls(534));
    }

    @Test void ftpCredentialsAreKeptInLocalSecretsFile() throws Exception {
        BackupSecrets.Credentials empty = BackupSecrets.load(tmp);
        assertEquals("", empty.username());
        assertEquals("", empty.password());
        assertEquals("", empty.ntfyToken());
        Path secrets = tmp.resolve("secrets.yml");
        Files.writeString(secrets, "backup:\n  ftp:\n    username: admin\n    password: secret-value\nnotifications:\n  ntfy:\n    token: notification-token\n");
        BackupSecrets.Credentials credentials = BackupSecrets.load(tmp);
        assertEquals("admin", credentials.username());
        assertEquals("secret-value", credentials.password());
        assertEquals("notification-token", credentials.ntfyToken());
        assertFalse(Files.readString(Path.of("src/main/resources/config.yml")).contains("secret-value"));
    }

    @Test void ntfyRequiresHttpsAndConstructsTopicEndpoint() throws Exception {
        assertEquals("https://ntfy.sh/minecraft-alerts",
                NtfyNotifier.target("https://ntfy.sh/", "minecraft-alerts").toString());
        assertEquals("https://notify.example/custom/topic",
                NtfyNotifier.target("https://notify.example/custom/", "topic").toString());
        assertThrows(java.io.IOException.class, () -> NtfyNotifier.target("http://ntfy.sh", "topic"));
        assertThrows(java.io.IOException.class, () -> NtfyNotifier.target("https://user@ntfy.sh", "topic"));
        assertThrows(java.io.IOException.class, () -> NtfyNotifier.target("https://ntfy.sh", "topic/name"));
    }

    @Test void defaultDailyBackupUsesServerLocalTime() throws Exception {
        TaskDef configured = TaskDef.load(List.of(Map.of(
                "id", "daily-backup", "enabled", true, "cron", "0 5 * * *",
                "timezone", "local", "action", "backup"))).get(0);
        assertEquals("0 5 * * *", configured.cron);
        assertEquals(ZoneId.systemDefault().getId(), configured.timezone);
        assertEquals("backup", configured.action);
    }

    @Test void singleSlotIsTheDefault() throws Exception {
        String yml = Files.readString(Path.of("src/main/resources/config.yml"));
        assertFalse(yml.contains("allowParallel:"));
        assertFalse(yml.contains("servers:"));
        assertFalse(yml.contains("mods:"));
    }

    @Test void profilesKeepLegacyRootAndAddServers() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "fabric");
        cfg.put("mcVersion", "26.3");
        cfg.put("port", 2063);
        cfg.put("workingDir", "./child-server");
        cfg.put("memory.xmx", "8G");
        cfg.put("memory.xms", "8G");
        cfg.put("autoStart", true);
        cfg.put("jvmFlags", List.of("-XX:+UseG1GC"));
        cfg.put("serverProperties.motd", "hi");
        cfg.put("servers.paper-test.type", "paper");
        cfg.put("servers.paper-test.mcVersion", "26.3");
        cfg.put("servers.paper-test.port", 2065);
        cfg.put("servers.paper-test.autoStart", false);
        cfg.put("focusServer", "paper-test");
        List<ServerProfile> all = Profiles.load(cfg);
        assertEquals(2, all.size());
        ServerProfile fabric = all.get(0);
        assertEquals("default", fabric.id);
        assertEquals("fabric", fabric.type);
        ServerProfile paper = all.get(1);
        assertEquals("paper", paper.type);
        assertEquals(2065, paper.port);
        assertEquals("8G", paper.xmx);
        assertEquals("-XX:+UseG1GC", paper.jvmFlags.get(0));
        assertTrue(paper.directory().endsWith("child-paper-test"));
        assertEquals("hi", paper.serverProperties.get("motd"));
        assertEquals("paper-test", Profiles.focus(cfg, all));
        assertFalse(Profiles.portClash(all, 2063));
        cfg.put("servers.paper-test.port", 2063);
        assertTrue(Profiles.portClash(Profiles.load(cfg), 2063));
    }

    @Test void defaultsKeepVanillaGameplayAndNeverPauseWhenEmpty() throws Exception {
        ServerProfile profile = Profiles.load(Map.of()).get(0);
        assertEquals("5G", profile.xmx);
        assertEquals(Map.of(
                "server-port", "2063",
                "server-ip", "0.0.0.0",
                "pause-when-empty-seconds", "0"), FabricLauncherPlugin.forcedProperties(profile));

        String config = Files.readString(Path.of("src/main/resources/config.yml"));
        assertTrue(config.contains("xmx: 5G"));
        assertTrue(config.contains("policyVersion: 4"));
        assertTrue(config.contains("serverProperties: {}"));
        assertFalse(config.contains("view-distance:"));
        assertFalse(config.contains("simulation-distance:"));
        assertFalse(config.contains("difficulty:"));
        assertFalse(config.contains("max-players:"));

        Path properties = tmp.resolve("server.properties");
        Files.writeString(properties, "view-distance=12\ndifficulty=hard\npause-when-empty-seconds=60\n");
        FabricLauncherPlugin.updateProperties(properties, FabricLauncherPlugin.forcedProperties(profile), Map.of());
        String saved = Files.readString(properties);
        assertTrue(saved.contains("view-distance=12"));
        assertTrue(saved.contains("difficulty=hard"));
        assertTrue(saved.contains("pause-when-empty-seconds=0"));
    }

    @Test void propertiesPreviewShowsStartupDiffWithoutWriting() throws Exception {
        Path properties = tmp.resolve("server.properties");
        String original = "# keep this comment\nserver-port=25565\nserver-ip=127.0.0.1\nview-distance=12\n";
        Files.writeString(properties, original);
        List<PropertiesFile.Preview> preview = PropertiesFile.preview(properties,
                Map.of("server-port", "2063", "server-ip", "0.0.0.0",
                        "pause-when-empty-seconds", "0", "view-distance", "12"),
                Map.of("level-name", "world", "white-list", "false"));

        PropertiesFile.Preview port = preview.stream().filter(p -> p.key().equals("server-port")).findFirst().orElseThrow();
        assertTrue(port.present());
        assertEquals("25565", port.current());
        assertEquals("2063", port.next());
        assertEquals("Wird geändert", port.action());
        PropertiesFile.Preview pause = preview.stream().filter(p -> p.key().equals("pause-when-empty-seconds")).findFirst().orElseThrow();
        assertFalse(pause.present());
        assertEquals("0", pause.next());
        assertEquals("Wird hinzugefügt", pause.action());
        PropertiesFile.Preview viewDistance = preview.stream().filter(p -> p.key().equals("view-distance")).findFirst().orElseThrow();
        assertEquals("Keine Änderung; wird erzwungen", viewDistance.action());
        assertEquals(original, Files.readString(properties));
    }

    @Test void dashboardKeepsHosterDataAndBundledVoxelDashAttribution() throws Exception {
        String html;
        try (var in = HosterTest.class.getResourceAsStream("/web/index.html")) {
            assertNotNull(in);
            html = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        String notices;
        try (var in = HosterTest.class.getResourceAsStream("/META-INF/THIRD-PARTY-NOTICES.txt")) {
            assertNotNull(in);
            notices = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var assetMatcher = java.util.regex.Pattern.compile("src=\"(/assets/[^\"]+\\.js)\"").matcher(html);
        assertTrue(assetMatcher.find());
        String bundle;
        try (var in = HosterTest.class.getResourceAsStream("/web/" + assetMatcher.group(1).substring(1))) {
            assertNotNull(in);
            bundle = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertTrue(html.contains("<div id=\"root\"></div>"));
        assertTrue(html.contains("type=\"module\""));
        assertTrue(html.contains("/assets/"));
        assertTrue(bundle.contains("updateCharts(g,s,hm)"));
        assertTrue(bundle.contains("Spigot-Host und Child-Prozess"));
        assertTrue(bundle.contains("/third-party-notices"));
        assertTrue(notices.contains("Copyright (c) 2021 Mathias Wagner"));
        assertTrue(notices.contains("Permission is hereby granted, free of charge"));
    }

    @Test void cronRespectsZoneAndSunday() {
        ZonedDateTime berlin = ZonedDateTime.of(2026, 10, 13, 4, 0, 0, 0, ZoneId.of("Europe/Berlin"));
        ZonedDateTime utc = berlin.withZoneSameInstant(ZoneId.of("UTC"));
        assertEquals(2, utc.getHour());
        assertTrue(CronExpr.matches("0 4 * * *", berlin));
        assertFalse(CronExpr.matches("0 4 * * *", utc));
        assertTrue(CronExpr.matches("*/15 * * * *", berlin.withMinute(15)));
        assertFalse(CronExpr.matches("*/15 * * * *", berlin.withMinute(16)));
        ZonedDateTime sunday = ZonedDateTime.of(2026, 10, 18, 5, 0, 0, 0, ZoneId.of("UTC"));
        assertEquals(java.time.DayOfWeek.SUNDAY, sunday.getDayOfWeek());
        assertTrue(CronExpr.matches("0 5 * * 0", sunday));
        assertTrue(CronExpr.matches("0 5 * * 7", sunday));
        assertFalse(CronExpr.matches("0 5 * * 1", sunday));
        assertThrows(IllegalArgumentException.class, () -> CronExpr.matches("0 5 * *", sunday));
        ZonedDateTime before = berlin.minusMinutes(1);
        assertEquals(berlin.withSecond(0).withNano(0), CronExpr.next("0 4 * * *", before));
        assertEquals(berlin.plusDays(1).withSecond(0).withNano(0), CronExpr.next("0 4 * * *", berlin));
    }

    @Test void tasksParse() {
        List<TaskDef> tasks = TaskDef.load(List.of(Map.of("id", "save", "enabled", true, "cron", "*/15 * * * *", "timezone", "Europe/Berlin", "action", "command", "command", "save-all", "server", "default")));
        assertEquals(1, tasks.size());
        assertEquals("save", tasks.get(0).id);
        assertEquals("Europe/Berlin", tasks.get(0).timezone);
        assertTrue(tasks.get(0).enabled);
        assertTrue(TaskDef.load(null).isEmpty());
    }

    @Test void schedulesValidateVoxelDashIntervalsAndKeepOrderedActions() {
        ScheduleDef schedule = new ScheduleDef(
                "weekly_backup",
                "Sunday backup",
                true,
                ScheduleDef.Interval.WEEKLY,
                0,
                5 * 60 + 30,
                "Europe/Berlin",
                0,
                List.of(
                        new ScheduleDef.Action("warn", "command", "say Backup in 5 Minuten"),
                        new ScheduleDef.Action("backup", "backup", "")
                )
        );
        assertEquals("30 5 * * 0", schedule.cron());
        assertEquals("Europe/Berlin", ScheduleDef.load(List.of(schedule.toConfig())).getFirst().timezone());
        assertEquals(List.of("warn", "backup"), ScheduleDef.load(List.of(schedule.toConfig())).getFirst()
                .actions().stream().map(ScheduleDef.Action::id).toList());
        assertEquals(123_456L, schedule.withLastRun(123_456L).lastRun());
        assertEquals(123_456L, ScheduleDef.load(List.of(schedule.withLastRun(123_456L).toConfig()))
                .getFirst().lastRun());
        assertThrows(IllegalArgumentException.class, () -> new ScheduleDef(
                "hourly", "Invalid hour", true, ScheduleDef.Interval.HOURLY, 60, 0, "UTC", 0,
                List.of(new ScheduleDef.Action("backup", "backup", ""))
        ));
        assertThrows(IllegalArgumentException.class, () -> new ScheduleDef(
                "negative-run", "Invalid last run", true, ScheduleDef.Interval.DAILY, 5, 30, "UTC", -1,
                List.of(new ScheduleDef.Action("backup", "backup", ""))
        ));
        assertThrows(IllegalArgumentException.class, () -> new ScheduleDef.Action("bad", "command", "say ok\nstop"));
    }

    @Test void legacySchedulesMigrateOnlySupportedTasksAndPreserveTheRest() {
        Map<String, Object> dailyBackup = Map.of(
                "id", "daily-backup", "enabled", true, "cron", "0 5 * * *",
                "timezone", "UTC", "action", "backup", "server", "");
        Map<String, Object> monthlyTask = Map.of(
                "id", "monthly", "enabled", true, "cron", "0 5 1 * *",
                "timezone", "UTC", "action", "command", "command", "say hello", "server", "");
        Map<String, Object> otherChildTask = Map.of(
                "id", "other-child", "enabled", true, "cron", "0 6 * * *",
                "timezone", "UTC", "action", "backup", "server", "other");
        LegacyTaskMigration.Result migration = LegacyTaskMigration.migrate(
                List.of(dailyBackup, monthlyTask, otherChildTask), "default", java.util.Set.of());
        List<ScheduleDef> schedules = ScheduleDef.load(migration.schedules());
        assertEquals(1, schedules.size());
        assertEquals("daily-backup", schedules.getFirst().id());
        assertEquals(ScheduleDef.Interval.DAILY, schedules.getFirst().interval());
        assertEquals(5, schedules.getFirst().intervalValue());
        assertEquals(0, schedules.getFirst().timeValue());
        assertEquals(List.of(monthlyTask, otherChildTask), migration.remainingTasks());
    }

    @Test void versionParsers() {
        var vanilla = VersionCatalog.vanilla(JsonParser.parseString("{\"versions\":[{\"id\":\"26.3\",\"type\":\"release\"},{\"id\":\"26.4-pre\",\"type\":\"snapshot\"},{\"id\":\"26.2\",\"type\":\"release\"}]}").getAsJsonObject(), 10);
        assertEquals(List.of("26.3", "26.2"), vanilla);
        var fabric = VersionCatalog.fabric(JsonParser.parseString("[{\"version\":\"26.4-snapshot\",\"stable\":false},{\"version\":\"26.3\",\"stable\":true}]").getAsJsonArray(), 10);
        assertEquals(List.of("26.3"), fabric);
        var paper = VersionCatalog.paper(JsonParser.parseString("{\"versions\":{\"26.3\":[\"26.3\",\"26.3-rc-3\"],\"1.21\":[\"1.21.8\"]}}").getAsJsonObject(), 10);
        assertEquals(List.of("26.3", "26.3-rc-3", "1.21.8"), paper);
        var spigot = VersionCatalog.spigot("<a href=\"latest.json\">latest.json</a><a href=\"1.21.4.json\">1.21.4.json</a><a href=\"26.3.json\">26.3.json</a><a href=\"1.8.json\">", 10);
        assertEquals(List.of("26.3", "1.21.4", "1.8"), spigot);
        assertTrue(VersionCatalog.compare("26.3", "26.3-rc-1") < 0);
    }

    @Test void spigotCommandIsArgv() {
        var cmd = SpigotBuilder.command("/usr/bin/java", Path.of("/opt/BuildTools.jar"), "26.3", Path.of("/srv/child"));
        assertEquals("26.3", cmd.get(cmd.indexOf("--rev") + 1));
        assertEquals("spigot-26.3.jar", cmd.get(cmd.size() - 1));
        assertFalse(String.join(" ", cmd).contains("&&"));
        assertThrows(IllegalArgumentException.class, () -> SpigotBuilder.assertRev("26.3;rm"));
    }

    @Test void backupZipAndExternalJdbc() throws Exception {
        Path world = Files.createDirectories(tmp.resolve("child/world"));
        Files.writeString(world.resolve("level.dat"), "world");
        Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(tmp.resolve("outside/secret.txt"), "nope");
        boolean linked = tryCreateSymbolicLink(tmp.resolve("child/escape"), tmp.resolve("outside"));
        BackupBridge.Spec spec = new BackupBridge.Spec(tmp.resolve("backups").toString(), List.of("world", "escape"), 2, true,
                "jdbc:h2:mem:fl" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "", "", "org.h2.Driver", "fabriclauncher_backups");
        BackupBridge.Result r = BackupBridge.archive(tmp.resolve("child"), spec, "default");
        assertTrue(Files.size(Path.of(r.path())) > 0);
        assertEquals(64, r.sha256().length());
        assertEquals("ok", r.db());
        if (linked) {
            try (var zip = new java.util.zip.ZipFile(r.path())) {
                assertFalse(zip.stream().anyMatch(entry -> entry.getName().startsWith("escape/")));
            }
        }
        assertEquals(1, BackupBridge.count(spec));
        try (var z = new java.util.zip.ZipFile(r.path())) {
            assertNotNull(z.getEntry("world/level.dat"));
            assertNull(z.getEntry("escape/secret.txt"));
        }
        BackupBridge.archive(tmp.resolve("child"), spec, "default");
        BackupBridge.archive(tmp.resolve("child"), spec, "default");
        try (var ds = Files.newDirectoryStream(tmp.resolve("backups"), "backup-default-*.zip")) {
            int n = 0; for (var ignored : ds) n++;
            assertEquals(2, n);
        }
        assertEquals(3, BackupBridge.count(spec));
        assertThrows(IllegalArgumentException.class, () -> BackupBridge.table(new BackupBridge.Spec("", List.of(), 1, false, "", "", "", "", "", "bad-name;drop")));
        List<BackupBridge.Entry> listed = BackupBridge.list(spec, "default");
        assertEquals(2, listed.size());
        String name = listed.get(0).name();
        Path fresh = Files.createDirectories(tmp.resolve("restored"));
        Files.writeString(fresh.resolve("old.txt"), "x");
        assertEquals(1, BackupBridge.restore(fresh, spec, "default", name));
        assertEquals("world", Files.readString(fresh.resolve("world/level.dat")));
        Path slip = tmp.resolve("backups").resolve("backup-default-20000101-000000-zz.zip");
        try (var z = new java.util.zip.ZipOutputStream(Files.newOutputStream(slip))) {
            z.putNextEntry(new java.util.zip.ZipEntry("../secret.txt"));
            z.write("nope".getBytes());
            z.closeEntry();
        }
        assertThrows(SecurityException.class, () -> BackupBridge.restore(fresh, spec, "default", slip.getFileName().toString()));
        assertFalse(Files.exists(tmp.resolve("secret.txt")));
        BackupBridge.delete(spec, "default", slip.getFileName().toString());
        BackupBridge.delete(spec, "default", name);
        assertEquals(1, BackupBridge.list(spec, "default").size());
        assertThrows(IllegalArgumentException.class, () -> BackupBridge.file(spec, "default", "../" + name));
    }

    @Test void launchPlanDropsDeadFlagAndNamesJars() {
        Path dir = tmp.resolve("plan");
        ServerProfile fabric = profile("fabric", "26.3", "server.jar", List.of("-XX:+UseG1GC", "-XX:G1RSetUpdatingPauseIntervalMillis=0", "-Xlog:gc"), List.of("--bonus"), dir.toString());
        List<String> argv = LaunchPlan.argv("java", fabric);
        String joined = String.join("\n", argv);
        assertFalse(joined.contains("G1RSetUpdatingPauseIntervalMillis"));
        assertEquals(List.of("java", "-XX:+IgnoreUnrecognizedVMOptions", "-Xms8G", "-Xmx8G", "-XX:+UseG1GC", "-Xlog:gc", "-jar", dir.resolve("fabric-server-launch-26.3.jar").toString(), "nogui", "--bonus"), argv);
        assertFalse(Files.exists(dir.resolve("fabric-server-launch-26.3.jar")));
        assertEquals("paper-26.3.jar", LaunchPlan.jarName("paper", "26.3", null));
        assertEquals("minecraft_server-1.21.8.jar", LaunchPlan.jarName("vanilla", "1.21.8", null));
        assertEquals("spigot-26.3.jar", LaunchPlan.jarName("spigot", "26.3", null));
        assertEquals("custom-pack.jar", LaunchPlan.jarName("custom", "26.3", "/opt/jars/custom-pack.jar"));
        assertEquals("server.jar", LaunchPlan.jarName("other", "26.3", null));
        assertTrue(LaunchPlan.jarPath(profile("paper", "26.3", null, List.of(), List.of(), dir.toString())).endsWith("paper-26.3.jar"));
    }

    @Test void jarProbeStatsRegularFileAndSkipsSymlink() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("jars"));
        Path jar = dir.resolve("fabric-server-launch-26.3.jar");
        Files.writeString(jar, "not-a-real-server");
        ServerProfile present = profile("fabric", "26.3", "server.jar", List.of(), List.of(), dir.toString());
        JarProbe.Info info = JarProbe.inspect(present);
        assertEquals("fabric-server-launch-26.3.jar", info.name());
        assertTrue(info.exists());
        assertTrue(info.regular());
        assertFalse(info.symlink());
        assertEquals(Files.size(jar), info.bytes());
        assertTrue(info.mtime() > 0);
        assertFalse(JarProbe.text(info).contains("not-a-real-server"));
        ServerProfile missing = profile("paper", "26.3", null, List.of(), List.of(), dir.toString());
        JarProbe.Info absent = JarProbe.inspect(missing);
        assertFalse(absent.exists());
        assertEquals("paper-26.3.jar", absent.name());
        assertTrue(JarProbe.text(absent).startsWith("paper-26.3.jar fehlt"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret-bytes");
        if (tryCreateSymbolicLink(dir.resolve("spigot-26.3.jar"), outside.resolve("secret.txt"))) {
            JarProbe.Info link = JarProbe.inspect(profile("spigot", "26.3", null, List.of(), List.of(), dir.toString()));
            assertTrue(link.symlink());
            assertEquals(-1, link.bytes());
            assertFalse(JarProbe.text(link).contains("secret"));
        }
    }

    @Test void crashIndexReadsAndRejectsEscape() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("child"));
        Path crashes = Files.createDirectories(root.resolve("crash-reports"));
        Files.writeString(crashes.resolve("crash-2026-10-13_12.00.00.txt"), "hello crash\nline2\n");
        Files.writeString(crashes.resolve("notes.md"), "nope");
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        boolean linked = tryCreateSymbolicLink(crashes.resolve("link.txt"), outside.resolve("secret.txt"));
        assertEquals(List.of("crash-2026-10-13_12.00.00.txt"), CrashIndex.list(root));
        String body = CrashIndex.read(root, "crash-2026-10-13_12.00.00.txt");
        assertTrue(body.startsWith("hello crash\nline2"));
        assertThrows(SecurityException.class, () -> CrashIndex.read(root, "../secret.txt"));
        if (linked) assertThrows(SecurityException.class, () -> CrashIndex.read(root, "link.txt"));
        assertFalse(CrashIndex.read(root, "crash-2026-10-13_12.00.00.txt").contains("secret"));
    }

    @Test void setupDiscoveryFindsExistingChildAndReadsLegacyConfiguration() throws Exception {
        Path root = Files.createTempDirectory("serverhoster-setup");
        Path child = Files.createDirectory(root.resolve("child-server"));
        Files.writeString(child.resolve("server.properties"), "server-port=2063\n");
        Path pendingDelete = Files.createDirectory(root.resolve("child-server.serverhoster-delete-pending-test"));
        Files.writeString(pendingDelete.resolve("server.properties"), "server-port=2064\n");
        Path pendingReset = Files.createDirectory(root.resolve("child-server.serverhoster-reset-pending-test"));
        Files.writeString(pendingReset.resolve("server.properties"), "server-port=2065\n");
        Path plugins = Files.createDirectory(root.resolve("plugins"));
        Path legacyDirectory = Files.createDirectory(plugins.resolve("FabricLauncher"));
        Path legacyConfig = legacyDirectory.resolve("config.yml");
        Files.writeString(legacyConfig, "type: paper\nmcVersion: '26.3'\nport: 2065\nworkingDir: '"
                + child.toString().replace("\\", "\\\\") + "'\n");
        Path legacyJar = plugins.resolve("FabricLauncher-1.10.1.jar");
        writeLegacyPluginJar(legacyJar, "1.10.1");
        List<SetupDiscovery.Candidate> candidates = SetupDiscovery.scan(root, legacyConfig);
        assertEquals(1, candidates.size());
        assertTrue(candidates.getFirst().legacyConfigured());
        assertEquals("1.10.1", candidates.getFirst().legacyVersion());
        assertEquals("paper", candidates.getFirst().profile().type);
        assertEquals(child.toAbsolutePath().normalize(), candidates.getFirst().path());
        writeLegacyPluginJar(legacyJar, "1.9.0");
        SetupDiscovery.Candidate unsupported = SetupDiscovery.scan(root, legacyConfig).getFirst();
        assertFalse(unsupported.legacyConfigured());
        assertNull(unsupported.profile());
        assertEquals(root.resolve("fresh-child").toAbsolutePath().normalize(),
                SetupDiscovery.resolveNewDirectory(root, "fresh-child"));
        assertThrows(IllegalArgumentException.class, () -> SetupDiscovery.resolveNewDirectory(root, "../outside"));
    }

    @Test void setupRecoveryRollsBackPendingDirectoriesBeforeSetupCompletes() throws Exception {
        Path root = Files.createTempDirectory("serverhoster-recovery-rollback");
        Path pendingDelete = Files.createDirectory(root.resolve("child.serverhoster-delete-pending-" + java.util.UUID.randomUUID()));
        Files.writeString(pendingDelete.resolve("server.properties"), "delete candidate");
        Path pendingReset = Files.createDirectory(root.resolve("old-child.serverhoster-reset-pending-" + java.util.UUID.randomUUID()));
        Files.writeString(pendingReset.resolve("server.properties"), "reset candidate");

        List<SetupRecovery.Result> recovered = SetupRecovery.recover(root, true,
                path -> fail("Uncommitted setup must never delete child data"));

        assertEquals(2, recovered.size());
        assertTrue(recovered.stream().allMatch(result -> result.action() == SetupRecovery.Action.RESTORED));
        assertEquals("delete candidate", Files.readString(root.resolve("child/server.properties")));
        assertEquals("reset candidate", Files.readString(root.resolve("old-child/server.properties")));
        assertFalse(Files.exists(pendingDelete));
        assertFalse(Files.exists(pendingReset));
    }

    @Test void setupRecoveryFinishesOnlyConfirmedDeleteAndArchivesReset() throws Exception {
        Path root = Files.createTempDirectory("serverhoster-recovery-commit");
        Path pendingDelete = Files.createDirectory(root.resolve("deleted-child.serverhoster-delete-pending-" + java.util.UUID.randomUUID()));
        Files.writeString(pendingDelete.resolve("server.properties"), "confirmed deletion");
        Path pendingReset = Files.createDirectory(root.resolve("archived-child.serverhoster-reset-pending-" + java.util.UUID.randomUUID()));
        Files.writeString(pendingReset.resolve("server.properties"), "preserve this child");

        List<SetupRecovery.Result> recovered = SetupRecovery.recover(root, false, path -> {
            Files.delete(path.resolve("server.properties"));
            Files.delete(path);
        });

        assertTrue(recovered.stream().anyMatch(result -> result.action() == SetupRecovery.Action.DELETED));
        SetupRecovery.Result archive = recovered.stream()
                .filter(result -> result.action() == SetupRecovery.Action.ARCHIVED).findFirst().orElseThrow();
        assertTrue(archive.path().getFileName().toString().startsWith("archived-child.serverhoster-archive-"));
        assertEquals("preserve this child", Files.readString(archive.path().resolve("server.properties")));
        assertFalse(Files.exists(pendingDelete));
        assertFalse(Files.exists(pendingReset));
    }

    @Test void setupRecoveryPreservesPendingDataWhenDestinationCollides() throws Exception {
        Path root = Files.createTempDirectory("serverhoster-recovery-collision");
        Path original = Files.createDirectory(root.resolve("child"));
        Files.writeString(original.resolve("server.properties"), "new data");
        Path pending = Files.createDirectory(root.resolve("child.serverhoster-reset-pending-" + java.util.UUID.randomUUID()));
        Files.writeString(pending.resolve("server.properties"), "old data");

        List<SetupRecovery.Result> recovered = SetupRecovery.recover(root, true,
                path -> fail("Colliding child data must never be deleted"));

        assertEquals(1, recovered.size());
        assertEquals(SetupRecovery.Action.PRESERVED, recovered.getFirst().action());
        assertEquals("new data", Files.readString(original.resolve("server.properties")));
        assertEquals("old data", Files.readString(pending.resolve("server.properties")));
    }

    @Test void legacyCleanupRetainsBackupsAndMemoryReports() throws Exception {
        Path oldData = tmp.resolve("plugins").resolve("FabricLauncher");
        Path backups = oldData.resolve("backups");
        Path reports = oldData.resolve("memory-reports");
        Files.createDirectories(backups);
        Files.createDirectories(reports);
        Files.writeString(oldData.resolve("config.yml"), "old config");
        Files.writeString(backups.resolve("backup.zip"), "backup");
        Files.writeString(reports.resolve("incident.log"), "report");

        FabricLauncherPlugin.cleanupLegacyDirectory(oldData, java.util.Set.of(backups, reports));

        assertFalse(Files.exists(oldData.resolve("config.yml")));
        assertEquals("backup", Files.readString(backups.resolve("backup.zip")));
        assertEquals("report", Files.readString(reports.resolve("incident.log")));
        assertTrue(Files.isDirectory(oldData));
    }

    private static void writeLegacyPluginJar(Path jar, String version) throws Exception {
        try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new java.util.jar.JarEntry("plugin.yml"));
            output.write(("name: FabricLauncher\nversion: " + version + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private static ServerProfile profile(String type, String mc, String custom, List<String> flags, List<String> args, String dir) {
        return new ServerProfile("t", type, mc, 2063, "0.0.0.0", custom, "latest", "latest", dir, "8G", "8G", flags, args, false, Map.of());
    }

    private static boolean tryCreateSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return false;
        }
    }
}
