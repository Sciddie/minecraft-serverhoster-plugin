package dev.aichele.serverhoster;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One child definition. Loaded from config only; jars are fetched when the child starts. */
final class ServerProfile {
    final String id;
    final String type;
    final String mcVersion;
    final int port;
    final String serverIp;
    final String customJar;
    final String fabricLoaderVersion;
    final String fabricInstallerVersion;
    final String workingDir;
    final String xms;
    final String xmx;
    final List<String> jvmFlags;
    final List<String> serverArgs;
    final boolean autoStart;
    final Map<String, String> serverProperties;

    ServerProfile(String id, String type, String mcVersion, int port, String serverIp, String customJar,
                  String fabricLoaderVersion, String fabricInstallerVersion, String workingDir,
                  String xms, String xmx, List<String> jvmFlags, List<String> serverArgs, boolean autoStart,
                  Map<String, String> serverProperties) {
        this.id = id;
        this.type = type;
        this.mcVersion = mcVersion;
        this.port = port;
        this.serverIp = serverIp;
        this.customJar = customJar;
        this.fabricLoaderVersion = fabricLoaderVersion;
        this.fabricInstallerVersion = fabricInstallerVersion;
        this.workingDir = workingDir;
        this.xms = xms;
        this.xmx = xmx;
        this.jvmFlags = jvmFlags;
        this.serverArgs = serverArgs;
        this.autoStart = autoStart;
        this.serverProperties = serverProperties;
    }

    Path directory() { return Paths.get(workingDir).toAbsolutePath().normalize(); }
}

/** Reads the flat dotted map produced by Bukkit {@code getValues(true)}. */
final class Profiles {
    private Profiles() {}

    static List<ServerProfile> load(Map<String, Object> flat) {
        // serverId names the backup files, so an existing override must keep working.
        return List.of(one(flat, str(flat, "serverId", "default")));
    }

    private static ServerProfile one(Map<String, Object> flat, String id) {
        String type = str(flat, "type", "fabric");
        String mc = str(flat, "mcVersion", "26.3");
        int port = num(flat, "port", 2063);
        String ip = str(flat, "serverIp", "");
        String jar = str(flat, "customJar", "server.jar");
        String loader = str(flat, "fabricLoaderVersion", "latest");
        String inst = str(flat, "fabricInstallerVersion", "latest");
        String dir = str(flat, "workingDir", "./child-server");
        String xms = str(flat, "memory.xms", "2G");
        String xmx = str(flat, "memory.xmx", "5G");
        List<String> flags = list(flat.get("jvmFlags"));
        List<String> args = list(flat.get("serverArgs"));
        if (flags == null) flags = List.of();
        if (args == null) args = List.of();
        boolean auto = bool(flat, "autoStart", true);
        return new ServerProfile(id, type, mc, port, ip, jar, loader, inst, dir, xms, xmx,
                flags, args, auto, props(flat));
    }

    private static Map<String, String> props(Map<String, Object> flat) {
        String p = "serverProperties.";
        Map<String, String> m = new LinkedHashMap<>();
        for (var e : flat.entrySet()) if (e.getKey().startsWith(p)) m.put(e.getKey().substring(p.length()), String.valueOf(e.getValue()));
        return m;
    }

    private static String str(Map<String, Object> flat, String key, String fallback) {
        Object v = flat.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    private static int num(Map<String, Object> flat, String key, int fallback) {
        Object v = flat.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v == null) return fallback;
        try { return Integer.parseInt(String.valueOf(v).trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private static boolean bool(Map<String, Object> flat, String key, boolean fallback) {
        Object v = flat.get(key);
        if (v instanceof Boolean b) return b;
        if (v == null) return fallback;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Object raw) {
        if (!(raw instanceof List<?> l)) return null;
        List<String> out = new ArrayList<>();
        for (Object o : l) if (o != null) out.add(String.valueOf(o));
        return out;
    }
}
