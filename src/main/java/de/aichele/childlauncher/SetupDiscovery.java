package de.aichele.childlauncher;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SetupDiscovery {
    record Candidate(Path path, ServerProfile profile, boolean legacyConfigured, boolean hasServerFiles,
                     String legacyVersion, List<String> contents) {
        Map<String, Object> view() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", path.toString());
            result.put("configured", profile != null);
            result.put("type", profile == null ? "" : profile.type);
            result.put("mcVersion", profile == null ? "" : profile.mcVersion);
            result.put("port", profile == null ? 0 : profile.port);
            result.put("legacyConfigured", legacyConfigured);
            result.put("hasServerFiles", hasServerFiles);
            result.put("legacyVersion", legacyVersion == null ? "" : legacyVersion);
            result.put("contents", contents);
            return result;
        }
    }

    private static final Set<String> SKIP = Set.of("plugins", "logs", "libraries", "cache", "world", "worlds", "versions", "build-tools");

    private SetupDiscovery() {}

    static List<Candidate> scan(Path serverRoot, Path legacyConfig) throws IOException {
        Path root = serverRoot.toAbsolutePath().normalize();
        String version = legacyPluginVersion(legacyConfig.getParent().getParent());
        boolean versionSupported = version == null || isAtLeast(version, "1.10.1");
        YamlConfiguration old = versionSupported && Files.isRegularFile(legacyConfig, LinkOption.NOFOLLOW_LINKS)
                ? loadLegacyConfig(legacyConfig) : null;
        boolean importSupported = old != null;
        Map<Path, ServerProfile> configured = new LinkedHashMap<>();
        if (importSupported) {
            for (ServerProfile profile : Profiles.load(old.getValues(true))) {
                Path path = profile.directory().toAbsolutePath().normalize();
                if (isAllowedConfiguredDirectory(root, path) && isServerDirectory(path))
                    configured.putIfAbsent(path, profile);
            }
        }

        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            int examined = 0;
            for (Path entry : entries) {
                if (++examined > 2_048) throw new IOException("Server root exceeds the bounded setup scan limit");
                String entryName = entry.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (entryName.contains(".serverhoster-delete-pending-")
                        || entryName.contains(".serverhoster-reset-pending-")
                        || SKIP.contains(entryName)
                        || Files.isSymbolicLink(entry)
                        || !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) continue;
                Path path = entry.toAbsolutePath().normalize();
                if (isServerDirectory(path)) configured.putIfAbsent(path, null);
            }
        }

        List<Candidate> result = new ArrayList<>();
        for (var entry : configured.entrySet()) {
            Path path = entry.getKey();
            ServerProfile profile = entry.getValue();
            result.add(new Candidate(path, profile, profile != null && importSupported, hasServerFiles(path), version,
                    topLevelEntries(path)));
        }
        return List.copyOf(result);
    }

    static YamlConfiguration loadLegacyConfig(Path path) throws IOException {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(path.toFile());
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            throw new IOException("Legacy ServerHoster config is invalid YAML: " + path, e);
        }
        return config;
    }

    static Path resolveNewDirectory(Path serverRoot, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Serverordner fehlt");
        Path root = serverRoot.toAbsolutePath().normalize();
        Path requested = Path.of(value.trim());
        if (requested.isAbsolute()) throw new IllegalArgumentException("Neue Serverordner müssen relativ zum Serververzeichnis sein");
        Path resolved = root.resolve(requested).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root))
            throw new IllegalArgumentException("Serverordner liegt außerhalb des erlaubten Bereichs");
        Path relative = root.relativize(resolved);
        if (SKIP.contains(relative.getName(0).toString().toLowerCase(java.util.Locale.ROOT)))
            throw new IllegalArgumentException("Serverordner darf kein bekanntes Host-Verzeichnis sein");
        Path current = root;
        for (Path segment : root.relativize(resolved)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current))
                throw new IllegalArgumentException("Serverordner enthält einen symbolischen Link");
        }
        return resolved;
    }

    private static String legacyPluginVersion(Path pluginsDirectory) throws IOException {
        if (!Files.isDirectory(pluginsDirectory, LinkOption.NOFOLLOW_LINKS)) return null;
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(pluginsDirectory, "FabricLauncher*.jar")) {
            for (Path entry : entries) {
                if (jars.size() >= 8) throw new IOException("Zu viele alte FabricLauncher-JARs zur sicheren Versionsbestimmung");
                if (!Files.isSymbolicLink(entry) && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) jars.add(entry);
            }
        }
        if (jars.size() != 1) return null;
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jars.getFirst().toFile())) {
            var entry = jar.getJarEntry("plugin.yml");
            if (entry == null) return null;
            try (var input = jar.getInputStream(entry)) {
                byte[] bytes = input.readNBytes(65_537);
                if (bytes.length > 65_536) throw new IOException("Oversized legacy plugin descriptor: " + jars.getFirst());
                String descriptor = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?m)^version:\\s*['\"]?([0-9]+(?:\\.[0-9]+){2}[^\\s'\"]*)").matcher(descriptor);
                return matcher.find() ? matcher.group(1) : null;
            }
        }
    }

    private static boolean isAtLeast(String version, String minimum) {
        try {
            String[] actual = version.split("[.+-]", 4);
            String[] required = minimum.split("\\.");
            for (int i = 0; i < required.length; i++) {
                int a = Integer.parseInt(actual[i]);
                int b = Integer.parseInt(required[i]);
                if (a != b) return a > b;
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean isSafeArchiveTarget(Path serverRoot, Path target) throws IOException {
        Path root = serverRoot.toAbsolutePath().normalize().toRealPath();
        if (Files.isSymbolicLink(target) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) return false;
        Path real = target.toRealPath();
        if (!real.startsWith(root) || real.equals(root)) return false;
        return !SKIP.contains(real.getFileName().toString().toLowerCase(java.util.Locale.ROOT));
    }

    private static boolean isServerDirectory(Path path) throws IOException {
        return !Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                && hasServerFiles(path);
    }

    private static boolean isAllowedConfiguredDirectory(Path root, Path path) {
        if (path.equals(root)) return false;
        if (!path.startsWith(root)) return true;
        Path relative = root.relativize(path);
        return relative.getNameCount() > 0
                && !SKIP.contains(relative.getName(0).toString().toLowerCase(java.util.Locale.ROOT));
    }

    private static boolean hasServerFiles(Path path) throws IOException {
        for (String marker : List.of("server.properties", "eula.txt", "level.dat")) {
            if (Files.isRegularFile(path.resolve(marker), LinkOption.NOFOLLOW_LINKS)) return true;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(path, "*.jar")) {
            for (Path entry : entries)
                if (!Files.isSymbolicLink(entry) && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) return true;
            return false;
        }
    }

    private static List<String> topLevelEntries(Path path) throws IOException {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
            for (Path entry : entries) {
                if (names.size() == 20) {
                    names.add("...");
                    break;
                }
                names.add(entry.getFileName().toString() + (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) ? "/" : ""));
            }
        }
        return List.copyOf(names);
    }
}
