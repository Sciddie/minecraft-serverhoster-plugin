package dev.aichele.serverhoster;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

final class HostFirstSetup {
    static final String MANUAL_CONFIRM = "HOST GRUNDSETUP";

    record Result(boolean applied, String worldName, List<String> removedWorlds,
                  List<String> disabledPluginJars, boolean restartRequired) {}

    private record PluginJar(Path source, Path disabled) {}

    private HostFirstSetup() {}

    static boolean shouldRun(Path marker, boolean freshInstall) throws IOException {
        if (Files.isSymbolicLink(marker)) throw new SecurityException("host setup marker must not be a symlink");
        if (Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            String status = HostMinimize.readState(Files.readString(marker)).get("status");
            if ("pending".equals(status) || "preparing".equals(status) || "requested".equals(status) || "auto-authorized".equals(status)) return true;
            if ("done".equals(status) || "restart-required".equals(status) || "manual-required".equals(status)) return false;
            throw new IOException("host setup marker has an unknown status");
        }
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("host setup marker is not a regular file");
        if (freshInstall) {
            writeMarker(marker, "auto-authorized", "");
            return true;
        }
        writeMarker(marker, "manual-required", "");
        return false;
    }

    static void requestManual(Path marker, String confirm) throws IOException {
        if (!MANUAL_CONFIRM.equals(confirm))
            throw new IllegalArgumentException("Die Bestätigung stimmt nicht. Erforderlich ist exakt: " + MANUAL_CONFIRM);
        Map<String, String> state = readState(marker);
        if (!"manual-required".equals(state.get("status")))
            throw new IllegalStateException("Das Host-Grundsetup ist nicht für eine manuelle Ausführung vorgemerkt.");
        writeMarker(marker, "requested", "");
    }

    static Result apply(Path root, Path marker, Path childDirectory, Path runningPluginJar) throws IOException {
        return apply(root, marker, childDirectory, runningPluginJar, List.of());
    }

    static Result apply(Path root, Path marker, Path childDirectory, Path runningPluginJar,
                        List<Path> additionalProtectedPaths) throws IOException {
        if (Files.isSymbolicLink(marker)) throw new SecurityException("host setup marker must not be a symlink");
        Map<String, String> previousState = readState(marker);
        String previousStatus = previousState.get("status");
        if ("done".equals(previousStatus) || "restart-required".equals(previousStatus)) {
            return new Result(false, "", List.of(), List.of(), false);
        }
        if (previousStatus == null || !Set.of("pending", "preparing", "requested", "auto-authorized").contains(previousStatus))
            throw new IllegalStateException("Host setup is not authorized in state " + previousStatus);
        requireRoot(root);
        Path properties = root.resolve("server.properties");
        if (Files.isSymbolicLink(properties)
                || !Files.isRegularFile(properties, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("server.properties must exist as a regular file before host setup");
        }
        Path realRoot = root.toRealPath();
        Path dataDirectory = marker.toAbsolutePath().normalize().getParent();
        if (dataDirectory == null || Files.isSymbolicLink(dataDirectory)
                || !Files.isDirectory(dataDirectory, LinkOption.NOFOLLOW_LINKS)
                || !dataDirectory.toRealPath().startsWith(realRoot)) {
            throw new SecurityException("host setup marker must stay inside the server directory");
        }
        Path realDataDirectory = dataDirectory.toRealPath();
        Path child = childDirectory.toAbsolutePath().normalize();
        List<PluginJar> pluginJars = otherPluginJars(realRoot, runningPluginJar);
        int disabledPluginCount = parseCount(previousState.get("disabledPluginCount"));
        List<String> previousWorlds = "preparing".equals(previousStatus)
                ? decodeWorldNames(previousState.get("previousWorlds")) : readLevelNames(properties);
        String worldName = previousState.get("worldName");
        if (!"preparing".equals(previousStatus)) worldName = HostMinimize.newLobbyName(realRoot);
        if (!HostMinimize.lobbyNameSafe(worldName)) throw new IOException("Invalid prepared lobby name");
        Set<Path> worlds = worldDirectories(realRoot, previousWorlds);
        for (Path world : worlds) {
            if (protectedPath(world, dataDirectory) || protectedPath(world, child)
                    || protectedPath(world, realDataDirectory) || protectedPath(world, realIfExists(child))
                    || protectedByAny(world, additionalProtectedPaths))
                throw new SecurityException("refusing to remove protected host world directory " + world.getFileName());
            if (Files.isSymbolicLink(world))
                throw new SecurityException("refusing symbolic link world directory " + world.getFileName());
        }

        String encodedWorlds = encodeWorldNames(previousWorlds);
        writeMarker(marker, "preparing", worldName, disabledPluginCount, encodedWorlds);
        for (PluginJar pluginJar : pluginJars) {
            movePluginJar(pluginJar);
            disabledPluginCount++;
            writeMarker(marker, "preparing", worldName, disabledPluginCount, encodedWorlds);
        }
        PropertiesFile.update(properties, HostMinimize.firstSetupProperties(worldName), Map.of());
        List<String> disabled = pluginJars.stream()
                .map(pluginJar -> pluginJar.source().getFileName().toString()).toList();
        writeMarker(marker, "restart-required", worldName, disabledPluginCount, encodedWorlds);
        return new Result(true, worldName, List.of(), disabled, true);
    }

    static List<String> completeRestart(Path root, Path marker, Path childDirectory, Set<String> loadedWorlds) throws IOException {
        return completeRestart(root, marker, childDirectory, loadedWorlds, List.of());
    }

    static List<String> completeRestart(Path root, Path marker, Path childDirectory, Set<String> loadedWorlds,
                                        List<Path> additionalProtectedPaths) throws IOException {
        Map<String, String> state = readState(marker);
        if (!"restart-required".equals(state.get("status"))) return List.of();
        requireRoot(root);
        Path realRoot = root.toRealPath();
        Path properties = realRoot.resolve("server.properties");
        String lobby = state.get("worldName");
        if (!HostMinimize.lobbyNameSafe(lobby) || Files.isSymbolicLink(properties)
                || !Files.isRegularFile(properties, LinkOption.NOFOLLOW_LINKS)
                || !readLevelNames(properties).equals(List.of(lobby)) || !loadedWorlds.contains(lobby))
            throw new IOException("Prepared lobby is not the active loaded host world; refusing world cleanup");
        Properties actual = new Properties();
        try (var input = Files.newInputStream(properties)) {
            actual.load(input);
        }
        for (var entry : HostMinimize.firstSetupProperties(lobby).entrySet()) {
            if (!entry.getValue().equals(actual.getProperty(entry.getKey())))
                throw new IOException("Prepared lobby property " + entry.getKey() + " changed; refusing world cleanup");
        }
        Path data = marker.toAbsolutePath().normalize().getParent();
        if (data == null || Files.isSymbolicLink(data)
                || !Files.isDirectory(data, LinkOption.NOFOLLOW_LINKS)
                || !data.toRealPath().startsWith(realRoot))
            throw new SecurityException("host setup marker must stay inside the server directory");
        Path child = childDirectory.toAbsolutePath().normalize();
        Path realChild = realIfExists(child);
        Set<Path> worlds = worldDirectories(realRoot, decodeWorldNames(state.get("previousWorlds")));
        List<Path> removable = new ArrayList<>();
        for (Path world : worlds) {
            String name = world.getFileName().toString();
            if (name.equals(lobby) || name.equals(lobby + "_nether") || name.equals(lobby + "_the_end")) continue;
            if (protectedPath(world, data) || protectedPath(world, data.toRealPath())
                    || protectedPath(world, child) || protectedPath(world, realChild)
                    || protectedByAny(world, additionalProtectedPaths))
                throw new SecurityException("refusing to remove protected host world directory " + name);
            if (Files.isSymbolicLink(world) || loadedWorlds.contains(name))
                throw new SecurityException("refusing to remove loaded or symbolic link world directory " + name);
            removable.add(world);
        }
        List<String> removed = new ArrayList<>();
        for (Path world : removable) {
            deleteTree(world);
            removed.add(world.getFileName().toString());
        }
        writeMarker(marker, "done", lobby,
                parseCount(state.get("disabledPluginCount")));
        return List.copyOf(removed);
    }

    private static String encodeWorldNames(List<String> names) {
        return names.stream().map(name -> Base64.getUrlEncoder().withoutPadding()
                .encodeToString(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static List<String> decodeWorldNames(String encoded) throws IOException {
        if (encoded == null || encoded.isBlank()) throw new IOException("Missing previous host world names");
        List<String> names = new ArrayList<>();
        try {
            for (String part : encoded.split(",")) {
                String name = new String(Base64.getUrlDecoder().decode(part), java.nio.charset.StandardCharsets.UTF_8);
                if (!safeWorldName(name)) throw new IOException("Invalid previous host world name");
                names.add(name);
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid encoded host world names", e);
        }
        return names;
    }

    static Map<String, String> readState(Path marker) throws IOException {
        if (Files.isSymbolicLink(marker)) throw new SecurityException("host setup marker must not be a symlink");
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) return Map.of();
        return HostMinimize.readState(Files.readString(marker));
    }

    private static void requireRoot(Path root) throws IOException {
        if (root == null || Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new SecurityException("server root must be a real directory");
        Path real = root.toRealPath();
        if (real.getNameCount() < 2 || "LiteStorage".equalsIgnoreCase(real.getFileName().toString())
                || real.getParent() != null && "LiteStorage".equalsIgnoreCase(String.valueOf(real.getParent().getFileName()))) {
            throw new SecurityException("refusing to prepare a hosting parent or LiteStorage directory");
        }
    }

    private static Set<Path> worldDirectories(Path root, List<String> previousWorlds) throws IOException {
        Set<Path> worlds = new LinkedHashSet<>();
        for (String name : previousWorlds) {
            if (!safeWorldName(name)) continue;
            addIfDirectory(root, name, worlds);
            addIfDirectory(root, name + "_nether", worlds);
            addIfDirectory(root, name + "_the_end", worlds);
        }
        try (var entries = Files.list(root)) {
            for (Path entry : entries.toList()) {
                if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(entry)) continue;
                Path level = entry.resolve("level.dat");
                if (Files.isRegularFile(level, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(level))
                    worlds.add(entry);
            }
        }
        return worlds;
    }

    private static void addIfDirectory(Path root, String name, Set<Path> worlds) {
        if (!safeWorldName(name)) return;
        Path candidate = root.resolve(name).normalize();
        if (candidate.getParent() != null && candidate.getParent().equals(root)
                && Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) worlds.add(candidate);
    }

    private static boolean safeWorldName(String name) {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return false;
        try {
            Path path = Path.of(name);
            return !path.isAbsolute() && path.getNameCount() == 1 && path.normalize().toString().equals(name);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // worldDirectories() yields real paths, so protected paths must be compared in real form as well.
    private static Path realIfExists(Path path) throws IOException {
        return Files.exists(path) ? path.toRealPath() : path;
    }

    private static boolean protectedPath(Path path, Path protectedPath) {
        return protectedPath != null && (path.equals(protectedPath) || path.startsWith(protectedPath)
                || protectedPath.startsWith(path));
    }

    private static boolean protectedByAny(Path path, List<Path> protectedPaths) throws IOException {
        for (Path protectedPath : protectedPaths) {
            Path absolute = protectedPath.toAbsolutePath().normalize();
            if (protectedPath(path, absolute)
                    || Files.exists(absolute) && protectedPath(path, absolute.toRealPath())) return true;
        }
        return false;
    }

    private static List<String> readLevelNames(Path properties) throws IOException {
        List<String> names = new ArrayList<>();
        for (String line : Files.readAllLines(properties)) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("level-name=")) continue;
            String name = trimmed.substring("level-name=".length()).trim();
            if (safeWorldName(name) && !names.contains(name)) names.add(name);
        }
        if (names.isEmpty()) names.add("world");
        return names;
    }

    private static List<PluginJar> otherPluginJars(Path root, Path runningPluginJar) throws IOException {
        Path plugins = root.resolve("plugins");
        if (Files.isSymbolicLink(plugins) || !Files.isDirectory(plugins, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("plugins must be a regular directory before host setup");
        if (runningPluginJar == null || Files.isSymbolicLink(runningPluginJar)
                || !Files.isRegularFile(runningPluginJar, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Could not identify the running ServerHoster plugin jar");
        }
        Path realPlugins = plugins.toRealPath();
        Path running = runningPluginJar.toRealPath();
        if (!running.getParent().equals(realPlugins))
            throw new SecurityException("the running ServerHoster jar must be directly inside plugins");
        List<PluginJar> result = new ArrayList<>();
        try (var entries = Files.list(realPlugins)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")
                        || entry.toAbsolutePath().normalize().equals(running)) continue;
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(entry)) continue;
                Path disabled = entry.resolveSibling(name + ".disabled");
                for (int attempt = 0; Files.exists(disabled, LinkOption.NOFOLLOW_LINKS); attempt++) {
                    if (attempt >= 8) throw new IOException("Could not choose a safe disabled filename for " + name);
                    disabled = entry.resolveSibling(name + ".serverhoster-" + UUID.randomUUID() + ".disabled");
                }
                result.add(new PluginJar(entry, disabled));
            }
        }
        return List.copyOf(result);
    }

    private static void movePluginJar(PluginJar pluginJar) throws IOException {
        try {
            Files.move(pluginJar.source(), pluginJar.disabled(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(pluginJar.source(), pluginJar.disabled());
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (Files.isSymbolicLink(root)) throw new SecurityException("refusing symbolic link world directory");
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void writeMarker(Path marker, String status, String worldName) throws IOException {
        writeMarker(marker, status, worldName, 0);
    }

    private static void writeMarker(Path marker, String status, String worldName, int disabledPluginCount) throws IOException {
        writeMarker(marker, status, worldName, disabledPluginCount, "");
    }

    private static void writeMarker(Path marker, String status, String worldName, int disabledPluginCount, String previousWorlds) throws IOException {
        if (Files.isSymbolicLink(marker)) throw new SecurityException("host setup marker must not be a symlink");
        Path parent = marker.toAbsolutePath().getParent();
        if (parent == null || Files.isSymbolicLink(parent))
            throw new SecurityException("host setup marker directory must not be a symlink");
        Files.createDirectories(parent);
        Path temporary = marker.resolveSibling(marker.getFileName() + ".tmp");
        if (Files.isSymbolicLink(temporary)) throw new SecurityException("host setup temporary marker must not be a symlink");
        Files.writeString(temporary, "status=" + status + "\nworldName=" + worldName
                + "\ndisabledPluginCount=" + disabledPluginCount + "\npreviousWorlds=" + previousWorlds + "\n");
        try {
            Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int parseCount(String value) {
        try {
            return Math.max(0, Integer.parseInt(value == null ? "0" : value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
