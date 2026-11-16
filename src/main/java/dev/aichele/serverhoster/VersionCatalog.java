package dev.aichele.serverhoster;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parses upstream version lists. Network calls happen only when an admin asks. */
final class VersionCatalog {
    private VersionCatalog() {}

    static List<String> fabric(JsonArray games, int limit) {
        List<String> ids = new ArrayList<>();
        for (JsonElement e : games) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("stable") && o.get("stable").getAsBoolean()) ids.add(str(o, "version"));
            if (ids.size() >= limit) break;
        }
        return ids;
    }

    static List<String> paper(JsonObject project, int limit) {
        List<String> ids = new ArrayList<>();
        JsonObject versions = project.getAsJsonObject("versions");
        if (versions == null) return ids;
        for (String group : versions.keySet()) {
            for (JsonElement e : versions.getAsJsonArray(group)) {
                ids.add(e.getAsString());
                if (ids.size() >= limit) return ids;
            }
        }
        return ids;
    }

    static List<String> fetch(Upstream up, String type, int limit) throws Exception {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return switch (t) {
            case "fabric" -> fabric(up.getJson("https://meta.fabricmc.net/v2/versions/game").getAsJsonArray(), limit);
            case "paper" -> paper(up.getJson("https://fill.papermc.io/v3/projects/paper").getAsJsonObject(), limit);
            default -> throw new IllegalArgumentException("type");
        };
    }

    static int compare(String a, String b) {
        String[] as = a.split("[.\\-]");
        String[] bs = b.split("[.\\-]");
        int n = Math.max(as.length, bs.length);
        for (int i = 0; i < n; i++) {
            String x = i < as.length ? as[i] : "0";
            String y = i < bs.length ? bs[i] : "0";
            int c;
            if (num(x) && num(y)) c = Integer.compare(Integer.parseInt(y), Integer.parseInt(x));
            else if (num(y)) c = 1;
            else if (num(x)) c = -1;
            else c = y.compareTo(x);
            if (c != 0) return c;
        }
        return 0;
    }

    private static boolean num(String s) { return s.chars().allMatch(Character::isDigit) && !s.isEmpty(); }
    private static String str(JsonObject o, String k) { JsonElement e = o.get(k); return e == null || e.isJsonNull() ? "" : e.getAsString(); }
}
