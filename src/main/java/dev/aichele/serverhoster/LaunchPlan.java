package dev.aichele.serverhoster;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** JVM argv for a profile. Does not download, touch the filesystem, or start a process. */
final class LaunchPlan {
    private LaunchPlan() {}

    static String jarName(String type, String mc, String customJar) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return switch (t) {
            case "fabric" -> "fabric-server-launch-" + mc + ".jar";
            case "paper" -> "paper-" + mc + ".jar";
            case "custom" -> fileName(customJar);
            default -> "server.jar";
        };
    }

    static Path jarPath(ServerProfile profile) {
        if ("custom".equalsIgnoreCase(profile.type)) {
            Path custom = Paths.get(profile.customJar == null || profile.customJar.isBlank() ? "server.jar" : profile.customJar);
            return (custom.isAbsolute() ? custom : profile.directory().resolve(custom)).normalize();
        }
        return profile.directory().resolve(jarName(profile.type, profile.mcVersion, profile.customJar));
    }

    static List<String> argv(String javaBin, ServerProfile profile) {
        return argv(javaBin, profile, jarPath(profile));
    }

    static List<String> argv(String javaBin, ServerProfile profile, Path jar) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-XX:+IgnoreUnrecognizedVMOptions");
        cmd.add("-Xms" + profile.xms);
        cmd.add("-Xmx" + profile.xmx);
        cmd.add("-XX:+UseG1GC");
        cmd.add("-jar");
        cmd.add(jar.toString());
        cmd.add("nogui");
        return List.copyOf(cmd);
    }

    static String text(String javaBin, ServerProfile profile) {
        return String.join(" ", argv(javaBin, profile));
    }

    private static String fileName(String customJar) {
        if (customJar == null || customJar.isBlank()) return "server.jar";
        String n = customJar.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        String name = slash >= 0 ? n.substring(slash + 1) : n;
        return name.isBlank() ? "server.jar" : name;
    }
}
