package dev.aichele.serverhoster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Host setup guards must hold when the server root is reached through a path that is not its real path. */
class RefactorCycle3Test {
    @TempDir Path tmp;

    /** Server root below a symlinked ancestor, i.e. path != real path (like 8.3 short names on Windows). */
    private Path aliasedRoot() throws IOException {
        Path real = Files.createDirectories(tmp.resolve("real"));
        Path link = tmp.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links unavailable: " + e.getMessage());
        }
        return Files.createDirectories(link.resolve("server"));
    }

    @Test void minimizeCreatesAndRemovesMissingWhitelistBelowAliasedRoot() throws Exception {
        Path root = aliasedRoot();
        Path data = Files.createDirectories(root.resolve("plugins/ServerhosterPlugin"));
        Files.writeString(root.resolve("server.properties"), "server-port=2060\nenable-rcon=false\nlevel-name=world\n");
        Path backup = data.resolve("host-minimize-backup/aliased");
        HostMinimize.apply(root, backup, List.of(data));
        assertEquals("[]\n", Files.readString(root.resolve("whitelist.json")));
        HostMinimize.restore(root, backup, List.of(data));
        assertFalse(Files.exists(root.resolve("whitelist.json")));
    }

    @Test void firstSetupStillProtectsChildWorldBelowAliasedRoot() throws Exception {
        Path root = aliasedRoot();
        Path data = Files.createDirectories(root.resolve("plugins/ServerhosterPlugin"));
        Path child = root.resolve("child-server");
        Files.writeString(root.resolve("plugins/ServerHoster.jar"), "running plugin");
        Files.createDirectories(child.resolve("world"));
        Files.writeString(root.resolve("server.properties"), "level-name=child-server\nserver-port=2060\n");
        Path marker = data.resolve("host-first-setup-state.txt");
        Files.writeString(marker, "status=pending\n");
        assertThrows(SecurityException.class, () -> HostFirstSetup.apply(root, marker, child,
                root.resolve("plugins/ServerHoster.jar")));
        assertEquals("pending", HostFirstSetup.readState(marker).get("status"));
    }
}
