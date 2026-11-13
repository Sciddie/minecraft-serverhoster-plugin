package dev.aichele.serverhoster;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Opt-in runtime settings for the outer Spigot host. Never selects or deletes worlds, plugins,
 * logs, backups, or child directories. Does not restart the host or change the child JVM.
 */
final class HostMinimize {
    static final String CONFIRM = "HOST MINIMIEREN";
    static final String ROLLBACK_CONFIRM = "HOST ZURUECK";
    static final Pattern LOBBY_NAME = Pattern.compile("serverhoster-lobby-[a-f0-9]{8}");

    private static final List<String> EDITABLE = List.of("server.properties", "bukkit.yml", "spigot.yml", "whitelist.json");
    private static final String WHITELIST_ABSENT = "whitelist.json.absent";

    private HostMinimize() {}

    static String newLobbyName() {
        return "serverhoster-lobby-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    static String newLobbyName(Path root) throws IOException {
        for (int attempt = 0; attempt < 8; attempt++) {
            String name = newLobbyName();
            if (!Files.exists(root.resolve(name), LinkOption.NOFOLLOW_LINKS)) return name;
        }
        throw new IOException("Could not choose a lobby name that does not already exist");
    }

    static Map<String, String> firstSetupProperties(String worldName) {
        if (!LOBBY_NAME.matcher(worldName).matches()) throw new IllegalArgumentException("unsafe lobby name");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("level-name", worldName);
        values.put("level-type", "minecraft:flat");
        values.put("generator-settings",
                "{\"biome\":\"minecraft:the_void\",\"features\":true,\"lakes\":false,\"layers\":[{\"block\":\"minecraft:air\",\"height\":1}],\"structure_overrides\":[]}");
        values.put("generate-structures", "false");
        return values;
    }

    static Map<String, String> outerProperties() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("allow-nether", "false");
        values.put("view-distance", "2");
        values.put("simulation-distance", "2");
        values.put("spawn-monsters", "false");
        values.put("spawn-animals", "false");
        values.put("spawn-npcs", "false");
        values.put("spawn-protection", "0");
        values.put("max-players", "1");
        values.put("entity-broadcast-range-percentage", "10");
        values.put("max-world-size", "16");
        values.put("pause-when-empty-seconds", "0");
        values.put("white-list", "true");
        values.put("enforce-whitelist", "true");
        return values;
    }

    static Map<String, Object> bukkitChanges() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("settings.allow-end", false);
        values.put("settings.allow-nether", false);
        for (String kind : List.of("monsters", "animals", "water-animals", "water-ambient",
                "water-underground-creature", "axolotls", "ambient"))
            values.put("spawn-limits." + kind, 0);
        return values;
    }

    static Map<String, Object> spigotChanges() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("world-settings.default.view-distance", 2);
        values.put("world-settings.default.simulation-distance", 2);
        values.put("world-settings.default.mob-spawn-range", 0);
        return values;
    }

    static String phase(String worldName, String levelName, boolean worldLoaded) {
        if (worldNameSafe(worldName) && worldName.equals(levelName) && worldLoaded) return "active";
        return "staged";
    }

    static String firstSetupWorld(String existing, String level, String setupStatus, String setupWorld, boolean loaded) {
        if ("done".equals(setupStatus) && lobbyNameSafe(setupWorld) && setupWorld.equals(level) && loaded)
            return setupWorld;
        return existing;
    }

    static boolean clearIdlePause(Path properties) throws IOException {
        if (properties == null || !Files.isRegularFile(properties, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(properties))
            return false;
        String text = Files.readString(properties);
        String level = "";
        for (String line : text.split("\n")) if (line.startsWith("level-name=")) level = line.substring("level-name=".length()).trim();
        if (!lobbyNameSafe(level)) return false;
        String updated = text.contains("pause-when-empty-seconds=")
                ? text.replaceAll("(?m)^pause-when-empty-seconds=.*$", "pause-when-empty-seconds=0")
                : text + (text.endsWith("\n") ? "" : "\n") + "pause-when-empty-seconds=0\n";
        if (updated.equals(text)) return false;
        Files.writeString(properties, updated);
        return true;
    }

    static List<String> preservedNotes() {
        return List.of(
                "Does not delete worlds, plugins, logs, backups, or the child directory.",
                "Does not change level-name or world generation; one-time void-world setup is separate.",
                "Does not change server-port, server-ip, online-mode, or enable RCON.",
                "Does not change the child JVM Xmx or child flags.",
                "Existing world folders and the configured world stay unchanged.",
                "The plugin does not restart the outer host.");
    }

    record Change(String file, String key, String from, String to) {}

    record Preview(List<Change> changes, List<String> notes) {}

    static Preview preview(Path root, List<Path> protectedPaths) throws IOException {
        assertRoot(root, protectedPaths);
        Map<String, String> current = readProperties(root.resolve("server.properties"));
        List<Change> changes = new ArrayList<>();
        for (var entry : outerProperties().entrySet())
            changes.add(new Change("server.properties", entry.getKey(), current.get(entry.getKey()), entry.getValue()));
        for (var entry : bukkitChanges().entrySet())
            changes.add(new Change("bukkit.yml", entry.getKey(), null, String.valueOf(entry.getValue())));
        for (var entry : spigotChanges().entrySet())
            changes.add(new Change("spigot.yml", entry.getKey(), null, String.valueOf(entry.getValue())));
        changes.add(new Change("whitelist.json", "players", null, "[]"));
        return new Preview(List.copyOf(changes), preservedNotes());
    }

    record ApplyResult(Path backupDirectory, List<String> files) {}

    static ApplyResult apply(Path root, Path backupDirectory, List<Path> protectedPaths) throws IOException {
        assertRoot(root, protectedPaths);
        Path properties = root.resolve("server.properties");
        assertEditable(root, properties, protectedPaths);
        if (!Files.isRegularFile(properties, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Outer server.properties must already exist as a regular file");
        List<Path> files = new ArrayList<>();
        files.add(properties);
        if (Files.exists(root.resolve("bukkit.yml"), LinkOption.NOFOLLOW_LINKS)) files.add(root.resolve("bukkit.yml"));
        if (Files.exists(root.resolve("spigot.yml"), LinkOption.NOFOLLOW_LINKS)) files.add(root.resolve("spigot.yml"));
        Path whitelist = root.resolve("whitelist.json");
        boolean whitelistExisted = Files.exists(whitelist, LinkOption.NOFOLLOW_LINKS);
        if (whitelistExisted) files.add(whitelist);
        for (Path file : files) assertEditable(root, file, protectedPaths);
        assertEditable(root, root.resolve("bukkit.yml"), protectedPaths);
        assertEditable(root, root.resolve("spigot.yml"), protectedPaths);
        Path dataDirectory = protectedPaths.getFirst().toAbsolutePath().normalize();
        if (!backupDirectory.toAbsolutePath().normalize().startsWith(dataDirectory))
            throw new SecurityException("host minimization backup must stay inside the plugin data directory");
        Files.createDirectories(backupDirectory);
        if (!whitelistExisted) Files.writeString(backupDirectory.resolve(WHITELIST_ABSENT), "absent\n");
        for (Path file : files) {
            Path destination = backupDirectory.resolve(file.getFileName().toString());
            if (Files.isSymbolicLink(destination)) throw new SecurityException("backup target must not be a symlink");
            Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
        }
        try {
            PropertiesFile.update(properties, outerProperties(), Map.of());
            if (files.stream().anyMatch(file -> file.getFileName().toString().equals("bukkit.yml")))
                updateYaml(root.resolve("bukkit.yml"), bukkitChanges());
            if (files.stream().anyMatch(file -> file.getFileName().toString().equals("spigot.yml")))
                updateYaml(root.resolve("spigot.yml"), spigotChanges());
            writeEmptyWhitelist(root, protectedPaths);
        } catch (IOException | RuntimeException e) {
            restore(root, backupDirectory, protectedPaths);
            throw e;
        }
        return new ApplyResult(backupDirectory, files.stream().map(file -> file.getFileName().toString()).toList());
    }

    static void restore(Path root, Path backupDirectory, List<Path> protectedPaths) throws IOException {
        if (Files.isSymbolicLink(backupDirectory) || !Files.isDirectory(backupDirectory))
            throw new IOException("host minimization backup is not a directory");
        Path realBackup = backupDirectory.toRealPath();
        Path data = protectedPaths.getFirst().toAbsolutePath().normalize();
        if (Files.isSymbolicLink(data)) throw new SecurityException("protected path must not be a symlink");
        // realBackup is a real path, so compare it with the real data directory (8.3 names, symlinked ancestors).
        if (Files.isDirectory(data)) data = data.toRealPath();
        if (!realBackup.startsWith(data)) throw new SecurityException("backup is outside the plugin data directory");
        if (Files.isRegularFile(realBackup.resolve(WHITELIST_ABSENT), LinkOption.NOFOLLOW_LINKS)) {
            Path created = root.resolve("whitelist.json");
            assertEditable(root, created, protectedPaths);
            if (Files.isSymbolicLink(created)) throw new SecurityException("refusing symbolic link whitelist.json");
            Files.deleteIfExists(created);
        }
        for (String name : EDITABLE) {
            Path source = realBackup.resolve(name);
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) continue;
            Path target = root.resolve(name);
            assertEditable(root, target, protectedPaths);
            Path temporary = target.resolveSibling(name + ".host-minimize-tmp");
            if (Files.isSymbolicLink(temporary)) throw new SecurityException("temporary restore file must not be a symlink");
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void writeEmptyWhitelist(Path root, List<Path> protectedPaths) throws IOException {
        Path whitelist = root.resolve("whitelist.json");
        assertEditable(root, whitelist, protectedPaths);
        if (Files.isSymbolicLink(whitelist)) throw new SecurityException("refusing symbolic link whitelist.json");
        Files.writeString(whitelist, "[]\n");
    }

    static void assertEditable(Path root, Path file, List<Path> protectedPaths) throws IOException {
        Path rootNormal = root.toAbsolutePath().normalize();
        Path fileNormal = file.toAbsolutePath().normalize();
        if (fileNormal.getParent() == null || !fileNormal.getParent().equals(rootNormal))
            throw new SecurityException("only a file directly in the server root can be edited");
        if (!EDITABLE.contains(fileNormal.getFileName().toString()))
            throw new SecurityException("file is not part of host minimization");
        if (Files.isSymbolicLink(fileNormal)) throw new SecurityException("refusing symbolic link " + fileNormal.getFileName());
        if (Files.exists(fileNormal, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(fileNormal, LinkOption.NOFOLLOW_LINKS))
            throw new SecurityException("refusing non-regular file " + fileNormal.getFileName());
        Path realRoot = Files.isDirectory(rootNormal) ? rootNormal.toRealPath() : rootNormal;
        // A missing file sits directly in root (checked above), so its real path is realRoot/name; the
        // normalized path would keep 8.3 short names or symlinked ancestors and never match realRoot.
        Path real = Files.exists(fileNormal, LinkOption.NOFOLLOW_LINKS) ? fileNormal.toRealPath()
                : realRoot.resolve(fileNormal.getFileName().toString());
        if (!real.startsWith(realRoot)) throw new SecurityException("path escapes the server root");
        for (Path protectedPath : protectedPaths) {
            if (protectedPath == null) continue;
            Path candidate = protectedPath.toAbsolutePath().normalize();
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(candidate)) throw new SecurityException("protected path must not be a symlink");
                candidate = candidate.toRealPath();
            }
            if (real.equals(candidate) || real.startsWith(candidate) || candidate.startsWith(real))
                throw new SecurityException("refusing to edit protected path via " + fileNormal.getFileName());
        }
    }

    private static void assertRoot(Path root, List<Path> protectedPaths) throws IOException {
        if (protectedPaths.isEmpty()) throw new SecurityException("protected paths are required");
        if (!Files.isDirectory(root)) throw new IOException("server root is not a directory");
        if (Files.isSymbolicLink(root)) throw new SecurityException("server root must not be a symlink");
        for (Path protectedPath : protectedPaths) {
            if (protectedPath == null) continue;
            Path candidate = protectedPath.toAbsolutePath().normalize();
            Path realRoot = root.toRealPath();
            Path realProtected = Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(candidate)
                    ? candidate.toRealPath() : candidate;
            if (realProtected.equals(realRoot))
                throw new SecurityException("a protected directory is the server root; host minimization is refused");
        }
    }

    private static Map<String, String> readProperties(Path file) throws IOException {
        Map<String, String> current = new LinkedHashMap<>();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return current;
        for (String line : Files.readAllLines(file)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) continue;
            int eq = trimmed.indexOf('=');
            if (eq < 0) continue;
            current.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
        }
        return current;
    }

    private static void updateYaml(Path file, Map<String, Object> values) throws IOException {
        assertNotSymlink(file);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        for (var entry : values.entrySet()) yaml.set(entry.getKey(), entry.getValue());
        yaml.save(file.toFile());
    }

    private static void assertNotSymlink(Path file) {
        if (Files.isSymbolicLink(file)) throw new SecurityException("refusing symbolic link " + file.getFileName());
    }

    static String stateText(String status, String lobbyName, String backup, boolean restartRequired, String heapPrevious) {
        String text = "status=" + status + "\nlobbyName=" + lobbyName + "\nbackup=" + backup
                + "\nrestartRequired=" + restartRequired + "\n";
        if (heapPrevious != null && !heapPrevious.isBlank()) text += "heapPrevious=" + heapPrevious + "\n";
        return text;
    }

    static Map<String, String> readState(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        if (text == null) return values;
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) values.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return values;
    }

    static boolean lobbyNameSafe(String name) {
        return name != null && LOBBY_NAME.matcher(name).matches() && !"world".equals(name);
    }

    static boolean worldNameSafe(String name) {
        return name != null && !name.isBlank() && !".".equals(name) && !"..".equals(name)
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0;
    }

}
