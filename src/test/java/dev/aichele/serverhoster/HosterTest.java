package dev.aichele.serverhoster;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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

    @Test void schedulesDefaultBlankTimezoneToServerLocalZone() {
        ScheduleDef schedule = new ScheduleDef(
                "daily_backup",
                "Daily backup",
                true,
                ScheduleDef.Interval.DAILY,
                5,
                0,
                " ",
                0,
                List.of(new ScheduleDef.Action("backup", "backup", "")));
        assertEquals("0 5 * * *", schedule.cron());
        assertEquals(ZoneId.systemDefault().getId(), schedule.timezone());
        assertEquals("backup", schedule.actions().getFirst().type());
    }

    @Test void shippedConfigDropsUnreadLegacyKeys() throws Exception {
        String yml = Files.readString(Path.of("src/main/resources/config.yml"));
        assertTrue(yml.contains("pluginVersion:"));
        assertFalse(yml.contains("schemaVersion:"));
        assertFalse(yml.contains("migrationState:"));
        assertFalse(yml.contains("scheduleDefaultVersion:"));
        assertFalse(yml.contains("\ntasks:"));
        assertFalse(yml.contains("\nservers:"));
    }

    @Test void singleSlotIsTheDefault() throws Exception {
        String yml = Files.readString(Path.of("src/main/resources/config.yml"));
        assertFalse(yml.contains("allowParallel:"));
        assertFalse(yml.contains("servers:"));
        assertFalse(yml.contains("mods:"));
    }

    @Test void profilesIgnoreRetiredServerLists() {
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
        List<ServerProfile> all = Profiles.load(cfg);
        assertEquals(1, all.size());
        ServerProfile fabric = all.get(0);
        assertEquals("default", fabric.id);
        assertEquals("fabric", fabric.type);
        assertEquals(2063, fabric.port);
        assertEquals("8G", fabric.xmx);
        assertEquals("-XX:+UseG1GC", fabric.jvmFlags.get(0));
        assertEquals("hi", fabric.serverProperties.get("motd"));
    }

    @Test void defaultsKeepVanillaGameplayAndNeverPauseWhenEmpty() throws Exception {
        ServerProfile profile = Profiles.load(Map.of()).get(0);
        assertEquals("5G", profile.xmx);
        assertEquals(Map.of(
                "server-port", "2063",
                "server-ip", "192.0.2.1",
                "pause-when-empty-seconds", "0"), ServerHosterPlugin.forcedProperties(profile, "192.0.2.1"));

        String config = Files.readString(Path.of("src/main/resources/config.yml"));
        assertTrue(config.contains("xmx: 5G"));
        assertTrue(config.contains("policyVersion: 4"));
        assertTrue(config.contains("maxAttempts: 5"));
        assertEquals(1L, config.lines().filter(line -> line.trim().startsWith("maxAttempts:")).count());
        assertTrue(config.contains("serverProperties: {}"));
        assertFalse(config.contains("view-distance:"));
        assertFalse(config.contains("simulation-distance:"));
        assertFalse(config.contains("difficulty:"));
        assertFalse(config.contains("max-players:"));

        Path properties = tmp.resolve("server.properties");
        Files.writeString(properties, "view-distance=12\ndifficulty=hard\npause-when-empty-seconds=60\n");
        ServerHosterPlugin.updateProperties(properties, ServerHosterPlugin.forcedProperties(profile, "192.0.2.1"), Map.of());
        String saved = Files.readString(properties);
        assertTrue(saved.contains("view-distance=12"));
        assertTrue(saved.contains("difficulty=hard"));
        assertTrue(saved.contains("pause-when-empty-seconds=0"));
        assertTrue(saved.contains("server-ip=192.0.2.1"));
    }

    @Test void bindAddressInheritsHostUnlessExplicitlyOverridden() {
        assertEquals("192.0.2.1", ServerHosterPlugin.effectiveBindAddress("", "192.0.2.1"));
        assertEquals("192.0.2.2", ServerHosterPlugin.effectiveBindAddress("192.0.2.2", "192.0.2.1"));
        assertEquals("0.0.0.0", ServerHosterPlugin.effectiveBindAddress("0.0.0.0", "192.0.2.1"));
        assertEquals("", ServerHosterPlugin.effectiveBindAddress("", ""));
        assertEquals("", ServerHosterPlugin.effectiveBindAddress(null, null));
        ServerProfile explicit = Profiles.load(Map.of("serverIp", "127.0.0.1")).getFirst();
        assertEquals("127.0.0.1", ServerHosterPlugin.forcedProperties(explicit, "192.0.2.1").get("server-ip"));
        ServerProfile inherited = Profiles.load(Map.of()).getFirst();
        assertEquals("192.0.2.3", ServerHosterPlugin.forcedProperties(inherited, "192.0.2.3").get("server-ip"));
        assertEquals("", ServerHosterPlugin.forcedProperties(inherited, "").get("server-ip"));
        assertEquals("192.0.2.3", ServerHosterPlugin.effectiveBindAddress("", "192.0.2.3"));
        assertEquals("127.0.0.1", ServerHosterPlugin.effectiveBindAddress("127.0.0.1", "192.0.2.3"));
        assertEquals("Alle Interfaces (Port 2063)", Dashboard.formatBindAddress("", 2063));
        assertEquals("Alle Interfaces (Port 2063)", Dashboard.formatBindAddress("0.0.0.0", 2063));
        assertEquals("192.0.2.3:2063", Dashboard.formatBindAddress("192.0.2.3", 2063));
        assertEquals("[::1]:2063", Dashboard.formatBindAddress("::1", 2063));
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

    @Test void scheduleBackupIdsStayStableForStatusMessages() {
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
                ));
        String backupId = schedule.id() + "-" + schedule.actions().stream()
                .filter(action -> action.type().equals("backup"))
                .findFirst()
                .orElseThrow()
                .id();
        assertEquals("weekly_backup-backup", backupId);
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

    @Test void scheduleExecutionPreservesOrderAndStopsAfterFailure() throws Exception {
        ScheduleDef schedule = new ScheduleDef("ordered", "Ordered", true, ScheduleDef.Interval.DAILY,
                5, 30, "UTC", 0, List.of(
                new ScheduleDef.Action("warn", "command", "say warning"),
                new ScheduleDef.Action("backup", "backup", ""),
                new ScheduleDef.Action("done", "command", "say done")));
        List<String> ran = new java.util.ArrayList<>();

        ScheduleExecution.run(schedule, action -> ran.add(action.id()));

        assertEquals(List.of("warn", "backup", "done"), ran);
        ran.clear();
        assertThrows(IOException.class, () -> ScheduleExecution.run(schedule, action -> {
            ran.add(action.id());
            if (action.id().equals("backup")) throw new IOException("backup failed");
        }));
        assertEquals(List.of("warn", "backup"), ran);
    }

    @Test void scheduleRunTrackerReportsOutcomesAndDoesNotReviveDeletedRuns() {
        ScheduleRunTracker tracker = new ScheduleRunTracker();
        assertEquals(new ScheduleRunTracker.Status("unknown", ""), tracker.status("backup"));
        ScheduleRunTracker.Run run = tracker.start("backup");
        assertEquals("running", tracker.status("backup").state());
        run.update("waiting", "");
        assertEquals("waiting", tracker.status("backup").state());
        run.update("failed", "backup failed");
        assertEquals(new ScheduleRunTracker.Status("failed", "backup failed"), tracker.status("backup"));
        tracker.remove("backup");
        ScheduleRunTracker.Run replacement = tracker.start("backup");
        run.update("success", "");
        assertEquals("running", tracker.status("backup").state());
        replacement.update("success", "");
        assertEquals("success", tracker.status("backup").state());
    }

    @Test void restartPolicyCapsRetriesAndMigratesOnlyTheOldUnlimitedDefault() {
        assertEquals(5, RestartPolicy.DEFAULT_MAX_ATTEMPTS);
        assertEquals(5, RestartPolicy.maxAttempts(5));
        assertTrue(RestartPolicy.mayRetry(4, 5));
        assertFalse(RestartPolicy.mayRetry(5, 5));
        assertTrue(RestartPolicy.migrateLegacyUnlimited(0));
        assertFalse(RestartPolicy.migrateLegacyUnlimited(3));
        assertThrows(IllegalArgumentException.class, () -> RestartPolicy.maxAttempts(0));
        assertThrows(IllegalArgumentException.class, () -> RestartPolicy.maxAttempts(101));
        assertThrows(IllegalArgumentException.class, () -> RestartPolicy.maxAttempts(5.5));
    }

    @Test void restartPolicyMigrationChangesOnlyThe2_0_0UnlimitedDefault() {
        org.bukkit.configuration.file.YamlConfiguration config = new org.bukkit.configuration.file.YamlConfiguration();
        config.set("metadata.pluginVersion", "2.0.0");
        config.set("autoRestart.maxAttempts", 0);
        assertTrue(ServerHosterPlugin.migrateLegacyRestartPolicy(config, "2.0.1"));
        assertEquals(5, config.getInt("autoRestart.maxAttempts"));
        assertEquals("2.0.1", config.getString("metadata.pluginVersion"));

        config.set("autoRestart.maxAttempts", 3);
        assertFalse(ServerHosterPlugin.migrateLegacyRestartPolicy(config, "2.0.1"));
        assertEquals(3, config.getInt("autoRestart.maxAttempts"));

        // Configs imported from 1.10.x carry no pluginVersion but may contain the unlimited value.
        org.bukkit.configuration.file.YamlConfiguration imported = new org.bukkit.configuration.file.YamlConfiguration();
        imported.set("autoRestart.maxAttempts", 0);
        assertTrue(ServerHosterPlugin.migrateLegacyRestartPolicy(imported, "2.0.1"));
        assertEquals(5, imported.getInt("autoRestart.maxAttempts"));
    }

    @Test void settingsValidateAndKeepRemoteCredentialsOutOfDashboardView() {
        ServerHosterSettings settings = new ServerHosterSettings(true, 5, 5, 300, 600,
                "remote", "FTPS", "ftp.example.net", 2121, "/backups/minecraft");
        assertEquals(5, settings.view().get("maxRestartAttempts"));
        assertFalse(settings.view().containsKey("password"));
        assertFalse(settings.view().containsKey("username"));
        assertThrows(IllegalArgumentException.class, () -> new ServerHosterSettings(
                true, 0, 5, 300, 600, "local", "AUTO", "", 21, "ServerHoster"));
        assertThrows(IllegalArgumentException.class, () -> new ServerHosterSettings(
                true, 5, 5, 300, 600, "remote", "AUTO", "bad host", 21, "ServerHoster"));
        assertThrows(IllegalArgumentException.class, () -> new ServerHosterSettings(
                true, 5, 5, 300, 600, "remote", "AUTO", "ftp.example.net", 21, "../outside"));
        org.bukkit.configuration.file.YamlConfiguration config = new org.bukkit.configuration.file.YamlConfiguration();
        settings.apply(config);
        assertEquals(settings, ServerHosterSettings.from(config));
    }

    @Test void versionParsers() {
        var fabric = VersionCatalog.fabric(JsonParser.parseString("[{\"version\":\"26.4-snapshot\",\"stable\":false},{\"version\":\"26.3\",\"stable\":true}]").getAsJsonArray(), 10);
        assertEquals(List.of("26.3"), fabric);
        var paper = VersionCatalog.paper(JsonParser.parseString("{\"versions\":{\"26.3\":[\"26.3\",\"26.3-rc-3\"],\"1.21\":[\"1.21.8\"]}}").getAsJsonObject(), 10);
        assertEquals(List.of("26.3", "26.3-rc-3", "1.21.8"), paper);
        assertTrue(VersionCatalog.compare("26.3", "26.3-rc-1") < 0);
    }

    @Test void versionCatalogHonorsTheRequestedLimit() {
        var games = new com.google.gson.JsonArray();
        for (int i = 0; i < 50; i++) {
            var entry = new com.google.gson.JsonObject();
            entry.addProperty("version", "1." + i);
            entry.addProperty("stable", true);
            games.add(entry);
        }
        assertEquals(50, VersionCatalog.fabric(games, Integer.MAX_VALUE).size());
        assertEquals(40, VersionCatalog.fabric(games, 40).size());
    }

    @Test void backupZipKeepsWorldDataAndRejectsTraversal() throws Exception {
        Path world = Files.createDirectories(tmp.resolve("child/world"));
        Files.writeString(world.resolve("level.dat"), "world");
        Files.writeString(world.resolve("session.lock"), "minecraft lock");
        Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(tmp.resolve("outside/secret.txt"), "nope");
        boolean linked = tryCreateSymbolicLink(tmp.resolve("child/escape"), tmp.resolve("outside"));
        BackupBridge.Spec spec = new BackupBridge.Spec(tmp.resolve("backups").toString(), List.of("world", "escape"), 2);
        BackupBridge.Result r = BackupBridge.archive(tmp.resolve("child"), spec, "default");
        assertTrue(Files.size(Path.of(r.path())) > 0);
        assertEquals(64, r.sha256().length());
        if (linked) {
            try (var zip = new java.util.zip.ZipFile(r.path())) {
                assertFalse(zip.stream().anyMatch(entry -> entry.getName().startsWith("escape/")));
            }
        }
        try (var z = new java.util.zip.ZipFile(r.path())) {
            assertNotNull(z.getEntry("world/level.dat"));
            assertNull(z.getEntry("world/session.lock"));
            assertNull(z.getEntry("escape/secret.txt"));
        }
        BackupBridge.archive(tmp.resolve("child"), spec, "default");
        BackupBridge.archive(tmp.resolve("child"), spec, "default");
        try (var ds = Files.newDirectoryStream(tmp.resolve("backups"), "backup-default-*.zip")) {
            int n = 0; for (var ignored : ds) n++;
            assertEquals(2, n);
        }
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

    @Test void customJarPathsResolveAgainstChildAndPreserveAbsolutePaths() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("custom-child"));
        Path jar = dir.resolve("server.jar");
        Files.writeString(jar, "test jar");
        ServerProfile relative = profile("custom", "26.3", "server.jar", List.of(), List.of(), dir.toString());
        assertEquals(jar, LaunchPlan.jarPath(relative));
        assertEquals(jar.toString(), LaunchPlan.argv("java", relative).get(5));
        assertTrue(JarProbe.inspect(relative).regular());
        ServerProfile nested = profile("custom", "26.3", "jars/server.jar", List.of(), List.of(), dir.toString());
        assertEquals(dir.resolve("jars").resolve("server.jar"), LaunchPlan.jarPath(nested));
        ServerProfile blank = profile("custom", "26.3", "", List.of(), List.of(), dir.toString());
        assertEquals(jar, LaunchPlan.jarPath(blank));
        Path absolute = tmp.resolve("external.jar").toAbsolutePath();
        ServerProfile external = profile("custom", "26.3", absolute.toString(), List.of(), List.of(), dir.toString());
        assertEquals(absolute, LaunchPlan.jarPath(external));
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

    @Test void childDirectoryStaysInsideTheServerRoot() throws Exception {
        Path root = Files.createTempDirectory("serverhoster-setup");
        assertEquals(root.resolve("child-server").toAbsolutePath().normalize(),
                PathGuard.resolveNewDirectory(root, "./child-server"));
        assertEquals(root.resolve("fresh-child").toAbsolutePath().normalize(),
                PathGuard.resolveNewDirectory(root, "fresh-child"));
        assertThrows(IllegalArgumentException.class, () -> PathGuard.resolveNewDirectory(root, "../outside"));
        assertThrows(IllegalArgumentException.class, () -> PathGuard.resolveNewDirectory(root, root.resolve("abs").toString()));
        assertThrows(IllegalArgumentException.class, () -> PathGuard.resolveNewDirectory(root, "plugins"));
        assertThrows(IllegalArgumentException.class, () -> PathGuard.resolveNewDirectory(root, ""));
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

    @Test void memoryProtectionWaitsForSustainedPressureAndStableRecovery() {
        MemoryProtectionPolicy policy = new MemoryProtectionPolicy();
        assertEquals(MemoryProtectionPolicy.Decision.NONE,
                policy.sample(95, 95, 80, 3, 2, 0, 1_000, 1_000, true));
        assertEquals(1, policy.criticalSamples());
        policy.sample(94, 95, 80, 3, 2, 0, 2_000, 1_000, true);
        assertEquals(0, policy.criticalSamples());
        assertEquals(MemoryProtectionPolicy.Decision.NONE,
                policy.sample(95, 95, 80, 3, 2, 0, 3_000, 1_000, true));
        assertEquals(MemoryProtectionPolicy.Decision.NONE,
                policy.sample(96, 95, 80, 3, 2, 0, 4_000, 1_000, true));
        assertEquals(MemoryProtectionPolicy.Decision.STOP_CHILD,
                policy.sample(97, 95, 80, 3, 2, 0, 5_000, 1_000, true));
        policy.startEmergencyStop();
        assertTrue(policy.emergencyStopStarted());

        assertEquals(MemoryProtectionPolicy.Decision.NONE,
                policy.sample(75, 95, 80, 3, 2, 1_000, 1_999, 1_000, true));
        assertEquals(MemoryProtectionPolicy.Decision.RELEASE_AFTER_STABLE_MEMORY,
                policy.sample(75, 95, 80, 3, 2, 1_000, 2_000, 1_000, true));
        policy.finishRecovery();
        assertFalse(policy.emergencyStopStarted());
        assertEquals(MemoryProtectionPolicy.Decision.STOP_CHILD,
                policy.sample(97, 95, 80, 1, 2, 0, 3_000, 1_000, true));
        policy.startEmergencyStop();
        assertTrue(policy.emergencyStopStarted());
        assertEquals(MemoryProtectionPolicy.Decision.RELEASE_WITHOUT_MEMORY_GUARANTEE,
                policy.sample(-1, 95, 80, 3, 2, 1_000, 2_000, 1_000, true));
        assertEquals(MemoryProtectionPolicy.Decision.NONE,
                policy.sample(Double.NaN, 95, 80, 3, 2, 0, 3_000, 1_000, true));
        assertEquals(MemoryProtectionPolicy.Decision.NONE,
                policy.sample(99, 95, 80, 1, 2, 0, 4_000, 1_000, false));
        assertEquals(0, policy.criticalSamples());
    }

    @Test void confirmedChildDeletionRemovesTheMovedDirectory() throws Exception {
        Path oldData = tmp.resolve("deleted-child.serverhoster-delete-pending-test");
        Files.createDirectories(oldData.resolve("world"));
        Files.writeString(oldData.resolve("server.properties"), "server-port=2063");
        Files.writeString(oldData.resolve("world").resolve("level.dat"), "world");

        ServerHosterPlugin.cleanupLegacyDirectory(oldData);

        assertFalse(Files.exists(oldData));
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
