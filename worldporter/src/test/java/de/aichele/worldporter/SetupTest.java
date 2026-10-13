package de.aichele.worldporter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SetupTest {
    @TempDir Path tmp;

    @Test void neuterKeepsPortAndWritesVoidLobby() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("server"));
        Path child = Files.createDirectories(root.resolve("child-server"));
        Files.writeString(root.resolve("server.properties"), "server-port=2060\nmotd=alt\n");
        Files.writeString(child.resolve("server.properties"), "server-port=2063\n");
        assertEquals(java.util.List.of("server.properties"), OuterNeuter.patch(root, child, "lobby_empty"));
        String outer = Files.readString(root.resolve("server.properties"));
        assertTrue(outer.contains("server-port=2060"));
        assertTrue(outer.contains("level-name=lobby_empty"));
        assertTrue(outer.contains("minecraft:the_void"));
        assertTrue(outer.contains("white-list=true"));
        assertEquals("server-port=2063\n", Files.readString(child.resolve("server.properties")));
        assertFalse(outer.contains("G1RSetUpdatingPauseIntervalMillis"));
    }

    @Test void moveTransfersWorldAndRefusesLoadedOrEscape() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("server"));
        Path world = Files.createDirectories(root.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "world");
        Path nether = Files.createDirectories(root.resolve("world_nether/dimensions/minecraft/the_nether"));
        Files.writeString(nether.resolve("keep.txt"), "nether");
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "nope");
        Files.createSymbolicLink(root.resolve("world_the_end"), outside);
        var plan = WorldMove.plan(root, ".", "./child-server", "spigot-to-vanilla", "world", "world_nether", "world_the_end", "world");
        WorldMove.Result blocked = WorldMove.run(plan, Set.of(world.toAbsolutePath().normalize()), false);
        assertFalse(blocked.finished());
        assertTrue(Files.exists(world.resolve("level.dat")));
        assertFalse(Files.exists(root.resolve("child-server/world")));
        WorldMove.Result done = WorldMove.run(plan, Set.of(), false);
        assertTrue(done.finished());
        assertEquals("world", Files.readString(root.resolve("child-server/world/level.dat")));
        assertEquals("nether", Files.readString(root.resolve("child-server/world/dimensions/minecraft/the_nether/keep.txt")));
        assertFalse(Files.exists(root.resolve("child-server/world_the_end/secret.txt")));
        assertTrue(Files.exists(outside.resolve("secret.txt")));
    }
}
