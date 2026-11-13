package dev.aichele.serverhoster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HostMinimizeTest {
    @TempDir Path tmp;

    @Test void applyKeepsWorldSelectionChildBackupsAndSecurityKeys() throws Exception {
        Path root = tmp.resolve("server");
        Path data = root.resolve("plugins/ServerhosterPlugin");
        Path child = root.resolve("child-server");
        Path backups = data.resolve("backups");
        Files.createDirectories(child.resolve("world"));
        Files.createDirectories(backups);
        Files.writeString(child.resolve("world/level.dat"), "child-world");
        Files.writeString(child.resolve("server.properties"), "server-port=2063\nview-distance=12\n");
        Files.writeString(child.resolve("whitelist.json"), "[\"ChildPlayer\"]\n");
        Files.writeString(root.resolve("whitelist.json"), "[\"HostPlayer\"]\n");
        Files.writeString(backups.resolve("world.zip"), "backup");
        Files.writeString(root.resolve("server.properties"), """
                server-port=2060
                server-ip=0.0.0.0
                online-mode=true
                enable-rcon=false
                view-distance=5
                level-name=world
                """);
        Files.writeString(root.resolve("bukkit.yml"), "settings:\n  allow-nether: true\n");
        Files.writeString(root.resolve("eula.txt"), "eula=true\n");
        Files.createDirectories(root.resolve("libraries"));
        Files.writeString(root.resolve("libraries/keep.jar"), "runtime");

        Files.createDirectories(root.resolve("serverhoster-lobby-aaaaaaaa"));
        List<Path> protectedPaths = List.of(data, child, backups);
        Path backup = data.resolve("host-minimize-backup/test");
        HostMinimize.apply(root, backup, protectedPaths);

        String properties = Files.readString(root.resolve("server.properties"));
        assertTrue(properties.contains("level-name=world"));
        assertTrue(properties.contains("view-distance=2"));
        assertTrue(properties.contains("simulation-distance=2"));
        assertTrue(properties.contains("allow-nether=false"));
        assertFalse(properties.contains("generator-settings="));
        assertTrue(properties.contains("server-port=2060"));
        assertTrue(properties.contains("online-mode=true"));
        assertTrue(properties.contains("enable-rcon=false"));
        assertTrue(properties.contains("white-list=true"));
        assertTrue(properties.contains("enforce-whitelist=true"));
        assertEquals("[]\n", Files.readString(root.resolve("whitelist.json")));
        assertEquals("[\"ChildPlayer\"]\n", Files.readString(child.resolve("whitelist.json")));
        assertTrue(properties.contains("pause-when-empty-seconds=0"));
        assertFalse(properties.contains("pause-when-empty-seconds=60"));
        assertFalse(properties.contains("enable-rcon=true"));
        assertEquals("child-world", Files.readString(child.resolve("world/level.dat")));
        assertTrue(Files.readString(child.resolve("server.properties")).contains("view-distance=12"));
        assertEquals("backup", Files.readString(backups.resolve("world.zip")));
        assertEquals("eula=true\n", Files.readString(root.resolve("eula.txt")));
        assertEquals("runtime", Files.readString(root.resolve("libraries/keep.jar")));
        assertTrue(Files.isDirectory(root.resolve("serverhoster-lobby-aaaaaaaa")));
        assertTrue(Files.readString(root.resolve("bukkit.yml")).contains("allow-nether: false")
                || Files.readString(root.resolve("bukkit.yml")).contains("allow-nether: false\n")
                || Files.readString(root.resolve("bukkit.yml")).contains("false"));

        HostMinimize.restore(root, backup, protectedPaths);
        String restored = Files.readString(root.resolve("server.properties"));
        assertTrue(restored.contains("level-name=world"));
        assertEquals("[\"HostPlayer\"]\n", Files.readString(root.resolve("whitelist.json")));
        assertTrue(restored.contains("view-distance=5"));
        assertEquals("child-world", Files.readString(child.resolve("world/level.dat")));
    }

    @Test void missingWhitelistIsRemovedAgainOnRollback() throws Exception {
        Path root = tmp.resolve("fresh");
        Path data = root.resolve("plugins/ServerhosterPlugin");
        Files.createDirectories(data);
        Files.writeString(root.resolve("server.properties"), "server-port=2060\nenable-rcon=false\nlevel-name=world\n");
        Path backup = data.resolve("host-minimize-backup/fresh");
        HostMinimize.apply(root, backup, List.of(data));
        assertEquals("[]\n", Files.readString(root.resolve("whitelist.json")));
        HostMinimize.restore(root, backup, List.of(data));
        assertFalse(Files.exists(root.resolve("whitelist.json")));
    }

    @Test void activeRequiresTheConfiguredWorldToBeLoadedAndIdlePauseStaysOff() throws Exception {
        assertEquals("staged", HostMinimize.phase("world", "world", false));
        assertEquals("staged", HostMinimize.phase("world", "other", true));
        assertEquals("active", HostMinimize.phase("world", "world", true));
        Path properties = tmp.resolve("server.properties");
        Files.writeString(properties, "level-name=serverhoster-lobby-aaaaaaaa\npause-when-empty-seconds=60\n");
        assertTrue(HostMinimize.clearIdlePause(properties));
        assertTrue(Files.readString(properties).contains("pause-when-empty-seconds=0"));
        assertFalse(HostMinimize.clearIdlePause(properties));
    }

    @Test void symlinkAndChildRootAreRefused() throws Exception {
        Path root = tmp.resolve("server");
        Path data = root.resolve("plugins/ServerhosterPlugin");
        Path child = root.resolve("child-server");
        Files.createDirectories(data);
        Files.createDirectories(child);
        Files.writeString(child.resolve("server.properties"), "server-port=2063\n");
        try {
            Files.createSymbolicLink(root.resolve("server.properties"), child.resolve("server.properties"));
        } catch (UnsupportedOperationException | FileSystemException e) {
            assumeTrue(false, "Symbolic links are unavailable: " + e.getMessage());
        }
        assertThrows(SecurityException.class, () -> HostMinimize.apply(root, data.resolve("backup"),
                List.of(data, child)));
        assertEquals("server-port=2063\n", Files.readString(child.resolve("server.properties")));

        Path same = tmp.resolve("same");
        Files.createDirectories(same.resolve("plugins/ServerhosterPlugin"));
        Files.writeString(same.resolve("server.properties"), "server-port=2060\n");
        assertThrows(SecurityException.class, () -> HostMinimize.apply(same,
                same.resolve("plugins/ServerhosterPlugin/backup"),
                List.of(same.resolve("plugins/ServerhosterPlugin"), same)));
        assertEquals("server-port=2060\n", Files.readString(same.resolve("server.properties")));
    }

    @Test void firstSetupClearsOnlyOuterWorldsAndCreatesTheVoidLobby() throws Exception {
        Path root = tmp.resolve("first-setup");
        Path data = root.resolve("plugins/ServerhosterPlugin");
        Path child = root.resolve("child-server");
        Files.createDirectories(data.resolve("backups"));
        Files.writeString(root.resolve("plugins/ServerHoster-2.0.10.jar"), "running plugin");
        Files.writeString(root.resolve("plugins/OtherPlugin.jar"), "other plugin");
        Files.createDirectories(root.resolve("world/region"));
        Files.createDirectories(root.resolve("world_nether"));
        Files.createDirectories(root.resolve("unused-world"));
        Files.createDirectories(child.resolve("world"));
        Files.writeString(root.resolve("world/level.dat"), "host-world");
        Files.writeString(root.resolve("world/region/r.0.0.mca"), "host-region");
        Files.writeString(root.resolve("world_nether/level.dat"), "host-nether");
        Files.writeString(root.resolve("unused-world/level.dat"), "stale-host-world");
        Files.writeString(child.resolve("world/level.dat"), "child-world");
        Files.writeString(root.resolve("server.properties"), """
                server-port=2060
                online-mode=true
                enable-rcon=false
                level-name=world
                """);
        Files.writeString(data.resolve("config.yml"), "keep config");
        Files.writeString(data.resolve("dashboard-password.txt"), "keep password");
        Files.writeString(data.resolve("backups/old.zip"), "keep backup");

        Path marker = data.resolve("host-first-setup-state.txt");
        assertTrue(HostFirstSetup.shouldRun(marker, true));
        HostFirstSetup.Result result = HostFirstSetup.apply(root, marker, child,
                root.resolve("plugins/ServerHoster-2.0.10.jar"));

        assertTrue(result.applied());
        assertTrue(result.removedWorlds().isEmpty());
        assertTrue(Files.exists(root.resolve("world")));
        assertTrue(Files.exists(root.resolve("world_nether")));
        assertTrue(Files.exists(root.resolve("unused-world")));
        assertEquals("child-world", Files.readString(child.resolve("world/level.dat")));
        assertEquals("keep config", Files.readString(data.resolve("config.yml")));
        assertEquals("keep password", Files.readString(data.resolve("dashboard-password.txt")));
        assertEquals("keep backup", Files.readString(data.resolve("backups/old.zip")));
        String properties = Files.readString(root.resolve("server.properties"));
        assertTrue(properties.contains("server-port=2060"));
        assertTrue(properties.contains("online-mode=true"));
        assertTrue(properties.contains("enable-rcon=false"));
        assertTrue(properties.contains("level-name=" + result.worldName()));
        assertTrue(properties.contains("\"structure_overrides\":[]"));
        assertTrue(result.restartRequired());
        assertEquals(List.of("OtherPlugin.jar"), result.disabledPluginJars());
        assertTrue(Files.exists(root.resolve("plugins/OtherPlugin.jar.disabled")));
        assertEquals("restart-required", HostFirstSetup.readState(marker).get("status"));
        assertFalse(HostFirstSetup.shouldRun(marker, false));
        assertThrows(IOException.class, () -> HostFirstSetup.completeRestart(root, marker, child, Set.of("world")));
        assertTrue(Files.exists(root.resolve("world/level.dat")));
        assertThrows(SecurityException.class, () -> HostFirstSetup.completeRestart(root, marker, child,
                Set.of(result.worldName(), "world")));
        assertTrue(Files.exists(root.resolve("unused-world/level.dat")));
        List<String> removed = HostFirstSetup.completeRestart(root, marker, child, Set.of(result.worldName()));
        assertEquals(3, removed.size());
        assertTrue(removed.containsAll(List.of("world", "world_nether", "unused-world")));
        assertFalse(Files.exists(root.resolve("world")));
        assertFalse(Files.exists(root.resolve("world_nether")));
        assertFalse(Files.exists(root.resolve("unused-world")));
        assertEquals("child-world", Files.readString(child.resolve("world/level.dat")));
        assertTrue(HostFirstSetup.completeRestart(root, marker, child, Set.of(result.worldName())).isEmpty());
        assertEquals("done", HostFirstSetup.readState(marker).get("status"));
        assertEquals("1", HostFirstSetup.readState(marker).get("disabledPluginCount"));
    }

    @Test void firstSetupRetriesPendingStateAndRejectsProtectedWorlds() throws Exception {
        Path root = tmp.resolve("retry-setup");
        Path data = root.resolve("plugins/ServerhosterPlugin");
        Path child = root.resolve("child-server");
        Files.createDirectories(data);
        Files.writeString(root.resolve("plugins/ServerHoster.jar"), "running plugin");
        Files.createDirectories(child.resolve("world"));
        Files.writeString(root.resolve("server.properties"), "level-name=child-server\nserver-port=2060\n");
        Path marker = data.resolve("host-first-setup-state.txt");
        Files.writeString(marker, "status=pending\n");
        assertTrue(HostFirstSetup.shouldRun(marker, false));
        assertThrows(SecurityException.class, () -> HostFirstSetup.apply(root, marker, child,
                root.resolve("plugins/ServerHoster.jar")));
        assertEquals("pending", HostFirstSetup.readState(marker).get("status"));
        assertEquals("level-name=child-server\nserver-port=2060\n",
                Files.readString(root.resolve("server.properties")));
    }

    @Test void firstSetupResumesPreparationWithoutLosingOriginalWorldOrChangingLobby() throws Exception {
        Path root = tmp.resolve("resume-setup");
        Path data = root.resolve("plugins/ServerhosterPlugin");
        Path child = root.resolve("child-server");
        Files.createDirectories(data);
        Files.createDirectories(root.resolve("old-world_nether"));
        Files.writeString(root.resolve("plugins/ServerHoster.jar"), "running plugin");
        Files.writeString(root.resolve("server.properties"), "level-name=old-world\n");
        Path marker = data.resolve("host-first-setup-state.txt");
        assertTrue(HostFirstSetup.shouldRun(marker, true));
        HostFirstSetup.Result first = HostFirstSetup.apply(root, marker, child, root.resolve("plugins/ServerHoster.jar"));
        assertTrue(first.restartRequired());
        assertTrue(Files.exists(root.resolve("old-world_nether")));

        String staged = Files.readString(marker).replace("status=restart-required", "status=preparing");
        Files.writeString(marker, staged);
        assertTrue(HostFirstSetup.shouldRun(marker, false));
        HostFirstSetup.Result resumed = HostFirstSetup.apply(root, marker, child, root.resolve("plugins/ServerHoster.jar"));
        assertEquals(first.worldName(), resumed.worldName());
        assertTrue(Files.exists(root.resolve("old-world_nether")));
        List<String> removed = HostFirstSetup.completeRestart(root, marker, child, Set.of(first.worldName()));
        assertEquals(List.of("old-world_nether"), removed);
        assertEquals("done", HostFirstSetup.readState(marker).get("status"));
    }

    @Test void firstSetupRefusesCleanupWhenPreparedWorldSelectionWasChanged() throws Exception {
        Path root = tmp.resolve("changed-world");
        Path data = root.resolve("plugins/ServerhosterPlugin");
        Files.createDirectories(data);
        Files.createDirectories(root.resolve("world"));
        Files.writeString(root.resolve("world/level.dat"), "world");
        Files.writeString(root.resolve("plugins/ServerHoster.jar"), "running plugin");
        Files.writeString(root.resolve("server.properties"), "level-name=world\n");
        Path marker = data.resolve("host-first-setup-state.txt");
        assertTrue(HostFirstSetup.shouldRun(marker, true));
        HostFirstSetup.Result result = HostFirstSetup.apply(root, marker, root.resolve("child-server"),
                root.resolve("plugins/ServerHoster.jar"));
        Files.writeString(root.resolve("server.properties"), "level-name=world\n");
        assertThrows(IOException.class, () -> HostFirstSetup.completeRestart(root, marker,
                root.resolve("child-server"), Set.of(result.worldName())));
        assertTrue(Files.exists(root.resolve("world/level.dat")));
        assertEquals("restart-required", HostFirstSetup.readState(marker).get("status"));
        Files.writeString(root.resolve("server.properties"), "level-name=" + result.worldName() + "\n");
        assertThrows(IOException.class, () -> HostFirstSetup.completeRestart(root, marker,
                root.resolve("child-server"), Set.of(result.worldName())));
        assertTrue(Files.exists(root.resolve("world/level.dat")));
    }

    @Test void firstSetupProtectsConfiguredBackupWorldDirectories() throws Exception {
        Path root = tmp.resolve("protected-backup");
        Path data = root.resolve("plugins/ServerhosterPlugin");
        Path backup = root.resolve("backups");
        Files.createDirectories(data);
        Files.createDirectories(backup);
        Files.writeString(backup.resolve("level.dat"), "backup");
        Files.writeString(root.resolve("plugins/ServerHoster.jar"), "running plugin");
        Files.writeString(root.resolve("server.properties"), "level-name=world\n");
        Path marker = data.resolve("host-first-setup-state.txt");
        assertTrue(HostFirstSetup.shouldRun(marker, true));
        assertThrows(SecurityException.class, () -> HostFirstSetup.apply(root, marker, root.resolve("child-server"),
                root.resolve("plugins/ServerHoster.jar"), List.of(backup)));
        assertEquals("backup", Files.readString(backup.resolve("level.dat")));
    }

    @Test void completedFirstSetupReconcilesMinimizationOnlyForLoadedLobby() {
        String oldWorld = "serverhoster-lobby-11111111";
        String newWorld = "serverhoster-lobby-22222222";
        assertEquals(newWorld, HostMinimize.firstSetupWorld(oldWorld, newWorld, "done", newWorld, true));
        assertEquals(oldWorld, HostMinimize.firstSetupWorld(oldWorld, newWorld, "restart-required", newWorld, true));
        assertEquals(oldWorld, HostMinimize.firstSetupWorld(oldWorld, newWorld, "done", newWorld, false));
        assertEquals(oldWorld, HostMinimize.firstSetupWorld(oldWorld, oldWorld, "done", newWorld, true));
    }

    @Test void upgradesRequireManualConfirmationButFreshInstallsRunAutomatically() throws Exception {
        Path freshMarker = tmp.resolve("fresh/plugins/ServerhosterPlugin/host-first-setup-state.txt");
        assertTrue(HostFirstSetup.shouldRun(freshMarker, true));
        assertEquals("auto-authorized", HostFirstSetup.readState(freshMarker).get("status"));

        Path upgradeMarker = tmp.resolve("upgrade/plugins/ServerhosterPlugin/host-first-setup-state.txt");
        assertFalse(HostFirstSetup.shouldRun(upgradeMarker, false));
        assertEquals("manual-required", HostFirstSetup.readState(upgradeMarker).get("status"));
        assertThrows(IllegalArgumentException.class,
                () -> HostFirstSetup.requestManual(upgradeMarker, "wrong confirmation"));
        assertThrows(IllegalStateException.class,
                () -> HostFirstSetup.apply(tmp.resolve("upgrade"), upgradeMarker,
                        tmp.resolve("upgrade/child-server"), tmp.resolve("upgrade/plugins/ServerHoster.jar")));
        HostFirstSetup.requestManual(upgradeMarker, HostFirstSetup.MANUAL_CONFIRM);
        assertEquals("requested", HostFirstSetup.readState(upgradeMarker).get("status"));
        assertTrue(HostFirstSetup.shouldRun(upgradeMarker, false));
    }

    @Test void heapTuneNeverRequestsXmxAndLobbyRulesIgnoreOtherWorlds() {
        assertTrue(HostHeapTune.FLAGS.stream().noneMatch(flag -> flag.name().contains("Xmx") || flag.name().contains("Xms")));
        List<String> touched = new ArrayList<>();
        String safe = "serverhoster-lobby-abcd1234";
        assertTrue(HostMinimize.lobbyNameSafe(safe));
        assertFalse(HostMinimize.lobbyNameSafe("world"));
        assertEquals(0, HostLobbyRuntime.apply(List.of(new RecordingWorld("world", touched)), safe));
        assertTrue(touched.isEmpty());
        assertEquals(1, HostLobbyRuntime.apply(List.of(new RecordingWorld("world", touched), new RecordingWorld(safe, touched)), safe));
        assertEquals(List.of(safe), touched);
        List<HostHeapTune.Result> results = HostHeapTune.apply(new HostHeapTune.Options() {
            @Override public boolean manageable(String name) { return "MinHeapFreeRatio".equals(name); }
            @Override public String get(String name) { return "40"; }
            @Override public void set(String name, String value) {}
        });
        assertEquals("40", results.stream().filter(result -> result.name().equals("MinHeapFreeRatio")).findFirst().orElseThrow().previous());
        assertTrue(results.stream().anyMatch(result -> result.name().equals("MinHeapFreeRatio") && result.applied()));
        assertTrue(results.stream().anyMatch(result -> result.name().equals("G1PeriodicGCInterval") && !result.applied()));
    }

    private record RecordingWorld(String name, List<String> touched) implements HostLobbyRuntime.World {
        @Override public void spawnFlags(boolean monsters, boolean animals) { touched.add(name); }
        @Override public void keepSpawnInMemory(boolean keep) {}
        @Override public void ruleOff(String rule) {}
    }
}
