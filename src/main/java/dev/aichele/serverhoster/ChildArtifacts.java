package dev.aichele.serverhoster;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

/** Downloads, builds or locates the server jar for the configured child type. */
final class ChildArtifacts {
    private ChildArtifacts() {}

    static Path prepare(Upstream upstream, Path dataFolder, ServerProfile c, Path wd, String javaBin,
                        Consumer<String> info, Consumer<String> warning) throws Exception {
        String type = c.type.toLowerCase(Locale.ROOT);
        String mc = c.mcVersion;
        switch (type) {
            case "fabric": {
                Path jar = wd.resolve("fabric-server-launch-" + mc + ".jar");
                if (!Files.exists(jar)) {
                    String loader = c.fabricLoaderVersion;
                    String inst = c.fabricInstallerVersion;
                    if ("latest".equalsIgnoreCase(loader)) loader = firstStable(upstream.getJson("https://meta.fabricmc.net/v2/versions/loader/" + mc).getAsJsonArray(), true);
                    if ("latest".equalsIgnoreCase(inst)) inst = firstStable(upstream.getJson("https://meta.fabricmc.net/v2/versions/installer").getAsJsonArray(), false);
                    upstream.download("https://meta.fabricmc.net/v2/versions/loader/" + mc + "/" + loader + "/" + inst + "/server/jar", jar);
                }
                return jar;
            }
            case "spigot": {
                return SpigotBuilder.build(upstream, dataFolder, mc, wd, javaBin, info);
            }
            case "paper": {
                Path jar = wd.resolve("paper-" + mc + ".jar");
                if (!Files.exists(jar)) {
                    JsonArray builds = upstream.getJson("https://fill.papermc.io/v3/projects/paper/versions/" + mc + "/builds").getAsJsonArray();
                    if (builds.isEmpty()) throw new IOException("No Paper builds for " + mc);
                    JsonObject pick = paperBuild(builds, mc, warning);
                    JsonObject dl = pick.getAsJsonObject("downloads").getAsJsonObject("server:default");
                    String sha = dl.has("checksums") && dl.getAsJsonObject("checksums").has("sha256") ? dl.getAsJsonObject("checksums").get("sha256").getAsString() : null;
                    upstream.download(dl.get("url").getAsString(), jar, "SHA-256", sha);
                }
                return jar;
            }
            case "vanilla": {
                Path jar = wd.resolve("minecraft_server-" + mc + ".jar");
                if (!Files.exists(jar)) {
                    JsonObject man = upstream.getJson("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json").getAsJsonObject();
                    String url = vanillaVersionUrl(man, mc);
                    if (url == null) throw new IOException("Version " + mc + " not in Mojang manifest");
                    JsonObject v = upstream.getJson(url).getAsJsonObject();
                    JsonObject server = v.getAsJsonObject("downloads").getAsJsonObject("server");
                    upstream.download(server.get("url").getAsString(), jar, "SHA-1", server.has("sha1") ? server.get("sha1").getAsString() : null);
                }
                return jar;
            }
            case "custom": {
                Path jar = LaunchPlan.jarPath(c);
                if (!Files.exists(jar)) throw new FileNotFoundException("Custom jar not found: " + jar);
                return jar;
            }
            default: throw new IllegalArgumentException("Unknown type: " + type);
        }
    }

    /** First STABLE Paper build; otherwise the first listed build, with a warning. */
    static JsonObject paperBuild(JsonArray builds, String mc, Consumer<String> warning) {
        for (JsonElement e : builds) {
            JsonObject b = e.getAsJsonObject();
            if ("STABLE".equalsIgnoreCase(str(b, "channel"))) return b;
        }
        JsonObject pick = builds.get(0).getAsJsonObject();
        warning.accept("No STABLE Paper build for " + mc + ", using build " + pick.get("id"));
        return pick;
    }

    /** Version JSON URL from the Mojang manifest; the last matching entry wins, as before. */
    static String vanillaVersionUrl(JsonObject manifest, String mc) {
        String url = null;
        for (JsonElement e : manifest.getAsJsonArray("versions")) if (mc.equals(str(e.getAsJsonObject(), "id"))) url = str(e.getAsJsonObject(), "url");
        return url;
    }

    static String firstStable(JsonArray arr, boolean loaderWrapped) {
        for (JsonElement e : arr) {
            JsonObject o = e.getAsJsonObject();
            if (loaderWrapped) o = o.getAsJsonObject("loader");
            if (o.get("stable").getAsBoolean()) return o.get("version").getAsString();
        }
        JsonObject o = arr.get(0).getAsJsonObject();
        return (loaderWrapped ? o.getAsJsonObject("loader") : o).get("version").getAsString();
    }

    private static String str(JsonObject o, String k) { JsonElement e = o.get(k); return e == null || e.isJsonNull() ? null : e.getAsString(); }
}
