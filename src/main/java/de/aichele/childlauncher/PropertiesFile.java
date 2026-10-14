package de.aichele.childlauncher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Line-based server.properties edit: keeps comments, order and unknown keys. */
final class PropertiesFile {
    record Preview(String key, boolean present, String current, String next, String action) {}

    private PropertiesFile() {}

    static void update(Path f, Map<String, String> force, Map<String, String> defaults) throws IOException {
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
        for (var e : force.entrySet()) if (!seen.contains(e.getKey())) { lines.add(e.getKey() + "=" + e.getValue()); seen.add(e.getKey()); }
        for (var e : defaults.entrySet()) if (!seen.contains(e.getKey())) lines.add(e.getKey() + "=" + e.getValue());
        Files.write(f, lines, StandardCharsets.UTF_8);
    }

    static List<Preview> preview(Path f, Map<String, String> force, Map<String, String> defaults) throws IOException {
        Map<String, String> current = new java.util.LinkedHashMap<>();
        if (f != null && Files.exists(f)) {
            if (!Files.isRegularFile(f)) throw new IOException("server.properties is not a regular file");
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) continue;
                int eq = trimmed.indexOf('=');
                String key = (eq < 0 ? trimmed : trimmed.substring(0, eq)).trim();
                current.put(key, eq < 0 ? "" : trimmed.substring(eq + 1).trim());
            }
        }

        Map<String, String> effective = new java.util.LinkedHashMap<>(defaults);
        effective.putAll(force);
        List<Preview> result = new ArrayList<>();
        for (var entry : effective.entrySet()) {
            String key = entry.getKey();
            boolean present = current.containsKey(key);
            boolean isForced = force.containsKey(key);
            if (!isForced && present) continue;
            String value = entry.getValue();
            String action = !present ? (isForced ? "Wird hinzugefügt" : "Wird als Standard ergänzt")
                    : current.get(key).equals(value) ? "Keine Änderung; wird erzwungen" : "Wird geändert";
            result.add(new Preview(key, present, present ? current.get(key) : "", value, action));
        }
        return List.copyOf(result);
    }
}
