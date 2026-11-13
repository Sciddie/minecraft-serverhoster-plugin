package dev.aichele.serverhoster;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Pins behaviour that the second refactoring cycle moved or merged. */
class RefactorCycle2Test {
    @TempDir Path tmp;

    /** The 2.4.4 condition, copied verbatim, as the reference for the extracted predicate. */
    private static boolean allowedIn244(String method, String path) {
        return !(!path.equals("/api/status") && !path.equals("/api/setup")
                && !path.startsWith("/api/setup/") && !path.equals("/api/host-first-setup")
                && !path.equals("/api/settings") && !path.equals("/api/backup/credentials") && !path.equals("/api/backup/options")
                && !path.equals("/api/backup/test") && !path.equals("/api/schedules")
                && !(method.equals("GET") && path.equals("/api/versions"))
                && !(method.equals("GET") && path.equals("/api/host-minimize")));
    }

    @Test void setupAllowListMatchesTheOriginalConditionForEveryEndpoint() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/aichele/serverhoster/Dashboard.java"));
        TreeSet<String> paths = new TreeSet<>(List.of("/api/setup/x", "/api/schedules/x", "/api/schedules/x/run",
                "/api/statusx", "/api/setupx", "/api/unknown"));
        Matcher literal = Pattern.compile("\"(?:[A-Z]+ )?(/api/[a-z0-9/_-]*)\"").matcher(source);
        while (literal.find()) paths.add(literal.group(1));
        assertTrue(paths.size() > 40, "expected the dashboard endpoint list, got " + paths);
        for (String method : List.of("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS"))
            for (String path : paths)
                assertEquals(allowedIn244(method, path), Dashboard.allowedDuringSetup(method, path), method + " " + path);
        assertFalse(Dashboard.allowedDuringSetup("POST", "/api/action"));
        assertFalse(Dashboard.allowedDuringSetup("POST", "/api/versions"));
        assertTrue(Dashboard.allowedDuringSetup("POST", "/api/setup"));
    }

    @Test void hostLevelNameReadsTheLastEntryOfARegularFileOnly() throws Exception {
        assertEquals("", ServerHosterPlugin.hostLevelName(tmp.resolve("missing.properties")));
        Path properties = tmp.resolve("server.properties");
        Files.writeString(properties, "#level-name=commented\nlevel-name=first\nmotd=x\nlevel-name= lobby \n");
        assertEquals("lobby", ServerHosterPlugin.hostLevelName(properties));
        Files.createDirectories(tmp.resolve("dir.properties"));
        assertEquals("", ServerHosterPlugin.hostLevelName(tmp.resolve("dir.properties")));
        Path link = tmp.resolve("link.properties");
        try { Files.createSymbolicLink(link, properties); }
        catch (UnsupportedOperationException | java.io.IOException | SecurityException e) { return; }
        assertEquals("", ServerHosterPlugin.hostLevelName(link));
    }

    @Test void fabricVersionPickPrefersStableAndFallsBackToTheFirstEntry() {
        JsonArray loaders = JsonParser.parseString("""
                [{"loader":{"version":"0.18.0-beta","stable":false}},{"loader":{"version":"0.17.3","stable":true}}]
                """).getAsJsonArray();
        assertEquals("0.17.3", ChildArtifacts.firstStable(loaders, true));
        JsonArray installers = JsonParser.parseString("""
                [{"version":"1.1.0","stable":false},{"version":"1.0.9","stable":false}]
                """).getAsJsonArray();
        assertEquals("1.1.0", ChildArtifacts.firstStable(installers, false));
    }

    @Test void paperPickPrefersStableAndWarnsOnFallback() {
        List<String> warnings = new ArrayList<>();
        JsonArray builds = JsonParser.parseString("""
                [{"id":250,"channel":"BETA"},{"id":249,"channel":"stable"},{"id":248,"channel":"STABLE"}]
                """).getAsJsonArray();
        assertEquals(249, ChildArtifacts.paperBuild(builds, "26.3", warnings::add).get("id").getAsInt());
        assertTrue(warnings.isEmpty());
        JsonArray experimental = JsonParser.parseString("[{\"id\":7,\"channel\":\"ALPHA\"},{\"id\":6}]").getAsJsonArray();
        assertEquals(7, ChildArtifacts.paperBuild(experimental, "27.0", warnings::add).get("id").getAsInt());
        assertEquals(List.of("No STABLE Paper build for 27.0, using build 7"), warnings);
    }

    @Test void vanillaManifestLookupKeepsTheLastMatchAndReportsMissingVersions() {
        JsonObject manifest = JsonParser.parseString("""
                {"versions":[{"id":"26.3","url":"https://a"},{"id":"26.2","url":"https://b"},{"id":"26.3","url":"https://c"}]}
                """).getAsJsonObject();
        assertEquals("https://c", ChildArtifacts.vanillaVersionUrl(manifest, "26.3"));
        assertEquals("https://b", ChildArtifacts.vanillaVersionUrl(manifest, "26.2"));
        assertNull(ChildArtifacts.vanillaVersionUrl(manifest, "1.0"));
    }

    @Test void customJarMustExistAndUnknownTypesAreRejected() throws Exception {
        ServerProfile custom = Profiles.load(java.util.Map.of("type", "custom",
                "workingDir", tmp.toString(), "customJar", "server.jar")).getFirst();
        assertThrows(java.io.FileNotFoundException.class,
                () -> ChildArtifacts.prepare(null, tmp, custom, tmp, "java", m -> {}, m -> {}));
        Files.writeString(tmp.resolve("server.jar"), "jar");
        assertEquals(tmp.resolve("server.jar").toAbsolutePath().normalize(),
                ChildArtifacts.prepare(null, tmp, custom, tmp, "java", m -> {}, m -> {}).toAbsolutePath().normalize());
        ServerProfile unknown = Profiles.load(java.util.Map.of("type", "forge")).getFirst();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ChildArtifacts.prepare(null, tmp, unknown, tmp, "java", m -> {}, m -> {}));
        assertEquals("Unknown type: forge", error.getMessage());
    }

    @Test void commandOutputIsCappedAtFortyLines() {
        List<String> sent = new ArrayList<>();
        CommandSender sender = (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class}, (proxy, method, args) -> {
                    if (method.getName().equals("sendMessage") && args != null && args.length == 1 && args[0] instanceof String m)
                        sent.add(m);
                    return null;
                });
        ServerHosterPlugin.sendHead(sender, "a\nb\nc".split("\n"));
        assertEquals(List.of("a", "b", "c"), sent);
        sent.clear();
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 45; i++) text.append("line ").append(i).append('\n');
        ServerHosterPlugin.sendHead(sender, text.toString().split("\n", -1));
        assertEquals(41, sent.size());
        assertEquals("line 40", sent.get(39));
        assertEquals("... 6 more lines in the dashboard", sent.get(40));
    }
}
