package de.aichele.worldporter;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One-time outer-server file patch. Does not touch the child directory. */
final class OuterNeuter {
    private OuterNeuter() {}

    static List<String> patch(Path root, Path child, String levelName) throws IOException {
        List<String> changed = new ArrayList<>();
        Path props = root.resolve("server.properties");
        if (!inside(child, props)) {
            String before = Files.exists(props) ? Files.readString(props) : "";
            Map<String, String> values = lobbyProperties();
            values.put("level-name", levelName == null || levelName.isBlank() ? "lobby_empty" : levelName);
            updateProperties(props, values);
            if (!before.equals(Files.readString(props))) changed.add("server.properties");
        }
        if (patchYaml(root.resolve("bukkit.yml"), child, bukkit())) changed.add("bukkit.yml");
        if (patchYaml(root.resolve("spigot.yml"), child, spigot())) changed.add("spigot.yml");
        for (String n : List.of("world_nether", "world_the_end", "timings")) {
            Path ed = root.resolve(n);
            if (inside(child, ed) || !Files.isDirectory(ed, LinkOption.NOFOLLOW_LINKS)) continue;
            try (var st = Files.list(ed)) {
                if (st.findAny().isEmpty()) { Files.delete(ed); changed.add("removed empty " + n); }
            }
        }
        return changed;
    }

    static Map<String, String> lobbyProperties() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("level-name", "lobby_empty");
        p.put("level-type", "minecraft:flat");
        p.put("generator-settings", "{\"layers\":[{\"block\":\"minecraft:air\",\"height\":1}],\"biome\":\"minecraft:the_void\"}");
        p.put("generate-structures", "false");
        p.put("view-distance", "2");
        p.put("simulation-distance", "2");
        p.put("spawn-monsters", "false");
        p.put("spawn-animals", "false");
        p.put("spawn-npcs", "false");
        p.put("allow-nether", "false");
        p.put("max-players", "2");
        p.put("white-list", "true");
        p.put("enforce-whitelist", "true");
        p.put("spawn-protection", "0");
        p.put("sync-chunk-writes", "false");
        p.put("entity-broadcast-range-percentage", "10");
        p.put("max-world-size", "16");
        return p;
    }

    static boolean sourceWorld(Path worldFolder, Path root, String overworld, String nether, String end) {
        Path folder = worldFolder.toAbsolutePath().normalize();
        for (String name : List.of(overworld, nether, end)) {
            Path src = root.resolve(name).toAbsolutePath().normalize();
            if (folder.equals(src) || folder.startsWith(src)) return true;
        }
        return false;
    }

    private static Map<String, Object> bukkit() {
        Map<String, Object> y = new LinkedHashMap<>();
        y.put("settings.allow-end", false);
        y.put("settings.allow-nether", false);
        for (String k : List.of("monsters", "animals", "water-animals", "water-ambient", "water-underground-creature", "axolotls", "ambient"))
            y.put("spawn-limits." + k, 0);
        for (String k : List.of("animal-spawns", "monster-spawns", "water-spawns", "water-ambient-spawns", "water-underground-creature-spawns", "axolotl-spawns", "ambient-spawns"))
            y.put("ticks-per." + k, 6000);
        y.put("ticks-per.autosave", 72000);
        y.put("chunk-gc.period-in-ticks", 200);
        return y;
    }

    private static Map<String, Object> spigot() {
        Map<String, Object> y = new LinkedHashMap<>();
        String d = "world-settings.default.";
        y.put(d + "view-distance", 2);
        y.put(d + "simulation-distance", 2);
        y.put(d + "mob-spawn-range", 0);
        for (String k : List.of("animals", "monsters", "raiders", "misc")) y.put(d + "entity-activation-range." + k, 2);
        for (String k : List.of("players", "animals", "monsters", "misc", "display", "other")) y.put(d + "entity-tracking-range." + k, 8);
        y.put(d + "entity-activation-range.tick-inactive-villagers", false);
        y.put(d + "nerf-spawner-mobs", true);
        y.put("settings.save-user-cache-on-stop-only", true);
        return y;
    }

    static void updateProperties(Path f, Map<String, String> force) throws IOException {
        List<String> lines = Files.exists(f) ? new ArrayList<>(Files.readAllLines(f, StandardCharsets.UTF_8)) : new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) continue;
            int eq = t.indexOf('=');
            String k = (eq < 0 ? t : t.substring(0, eq)).trim();
            seen.add(k);
            if (force.containsKey(k)) lines.set(i, k + "=" + force.get(k));
        }
        for (var e : force.entrySet()) if (!seen.contains(e.getKey())) lines.add(e.getKey() + "=" + e.getValue());
        if (f.getParent() != null) Files.createDirectories(f.getParent());
        Files.write(f, lines, StandardCharsets.UTF_8);
    }

    private static boolean patchYaml(Path f, Path child, Map<String, Object> vals) {
        try {
            if (inside(child, f) || !Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) return false;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f.toFile());
            boolean ch = false;
            for (var e : vals.entrySet()) {
                if (!String.valueOf(e.getValue()).equals(String.valueOf(y.get(e.getKey())))) {
                    y.set(e.getKey(), e.getValue());
                    ch = true;
                }
            }
            if (ch) {
                Files.copy(f, f.resolveSibling(f.getFileName() + ".fl-bak"), StandardCopyOption.REPLACE_EXISTING);
                y.save(f.toFile());
            }
            return ch;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean inside(Path child, Path file) {
        if (child == null) return false;
        Path abs = file.toAbsolutePath().normalize();
        Path root = child.toAbsolutePath().normalize();
        return abs.startsWith(root);
    }
}
