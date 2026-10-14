package de.aichele.childlauncher;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

final class BackupSecrets {
    record Credentials(String username, String password, String ntfyToken) {}

    private BackupSecrets() {}

    static Credentials load(Path pluginDirectory) throws IOException {
        Path directory = pluginDirectory.toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path path = directory.resolve("secrets.yml");
        if (!path.getParent().equals(directory) || Files.isSymbolicLink(path)) {
            throw new IOException("Invalid secrets.yml path");
        }
        if (!Files.exists(path)) {
            Files.createFile(path);
            restrict(path);
            YamlConfiguration initial = new YamlConfiguration();
            initial.set("backup.ftp.username", "");
            initial.set("backup.ftp.password", "");
            initial.set("notifications.ntfy.token", "");
            initial.save(path.toFile());
        }
        restrict(path);
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(path.toFile());
        } catch (InvalidConfigurationException e) {
            throw new IOException("Cannot parse plugins/FabricLauncher/secrets.yml", e);
        }
        String username = config.getString("backup.ftp.username", "");
        String password = config.getString("backup.ftp.password", "");
        String token = config.getString("notifications.ntfy.token", "");
        if (username == null || password == null || token == null)
            throw new IOException("FTP and ntfy credentials in secrets.yml must be strings");
        return new Credentials(username, password, token);
    }

    private static void restrict(Path path) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
    }
}
