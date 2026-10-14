package de.aichele.childlauncher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only Linux host and cgroup memory facts. Missing cgroup data is reported as unavailable. */
final class HostMemoryProbe {
    record Snapshot(long availableMb, long swapTotalMb, long swapFreeMb, String cgroupVersion,
                    long cgroupCurrentMb, long cgroupLimitMb, long cgroupSwapMb,
                    long memoryMaxEvents, long oomEvents, long oomKills) {
        Map<String, Object> view() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("availableMb", availableMb);
            out.put("swapTotalMb", swapTotalMb);
            out.put("swapFreeMb", swapFreeMb);
            out.put("cgroupVersion", cgroupVersion);
            out.put("cgroupCurrentMb", cgroupCurrentMb);
            out.put("cgroupLimitMb", cgroupLimitMb);
            out.put("cgroupSwapMb", cgroupSwapMb);
            out.put("memoryMaxEvents", memoryMaxEvents);
            out.put("oomEvents", oomEvents);
            out.put("oomKills", oomKills);
            return out;
        }

        double cgroupUsePercent() {
            return cgroupLimitMb > 0 && cgroupCurrentMb >= 0 ? cgroupCurrentMb * 100.0 / cgroupLimitMb : -1;
        }
    }

    private HostMemoryProbe() {}

    static Snapshot snapshot() { return snapshot(Path.of("/proc"), Path.of("/sys/fs/cgroup")); }

    static Snapshot snapshot(Path proc, Path cgroupRoot) {
        Map<String, Long> host = meminfo(proc.resolve("meminfo"));
        Cgroup cg = cgroup(proc, cgroupRoot);
        return new Snapshot(host.getOrDefault("MemAvailable", -1L), host.getOrDefault("SwapTotal", -1L),
                host.getOrDefault("SwapFree", -1L), cg.version, cg.currentMb, cg.limitMb, cg.swapMb,
                cg.memoryMaxEvents, cg.oomEvents, cg.oomKills);
    }

    private static Map<String, Long> meminfo(Path path) {
        Map<String, Long> out = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(path)) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String[] parts = line.substring(colon + 1).trim().split("\\s+");
                if (parts.length > 0) {
                    try { out.put(line.substring(0, colon), Long.parseLong(parts[0]) / 1024); }
                    catch (NumberFormatException ignored) {}
                }
            }
        } catch (IOException ignored) {}
        return out;
    }

    private static Cgroup cgroup(Path proc, Path root) {
        try {
            for (String line : Files.readAllLines(proc.resolve("self/cgroup"))) {
                String[] fields = line.split(":", 3);
                if (fields.length != 3) continue;
                if (fields[1].isEmpty()) {
                    Path dir = resolve(root, fields[2]);
                    for (Path p : ancestors(dir, root)) {
                        long limit = number(p.resolve("memory.max"));
                        long current = number(p.resolve("memory.current"));
                        if (limit > 0 && current >= 0) {
                            Map<String, Long> events = keyValues(p.resolve("memory.events"));
                            long swap = number(p.resolve("memory.swap.current"));
                            return new Cgroup("v2", current / MB, limit / MB, swap < 0 ? -1 : swap / MB,
                                    events.getOrDefault("max", -1L), events.getOrDefault("oom", -1L),
                                    events.getOrDefault("oom_kill", -1L));
                        }
                    }
                } else if (List.of(fields[1].split(",")).contains("memory")) {
                    for (Path base : List.of(root.resolve("memory"), root)) {
                        Path dir = resolve(base, fields[2]);
                        for (Path p : ancestors(dir, base)) {
                            long limit = number(p.resolve("memory.limit_in_bytes"));
                            long current = number(p.resolve("memory.usage_in_bytes"));
                            if (limit > 0 && limit < (1L << 60) && current >= 0) {
                                long memsw = number(p.resolve("memory.memsw.usage_in_bytes"));
                                long failCount = number(p.resolve("memory.failcnt"));
                                return new Cgroup("v1", current / MB, limit / MB, memsw < 0 ? -1 : Math.max(0, memsw - current) / MB,
                                        failCount, -1, -1);
                            }
                        }
                    }
                }
            }
        } catch (IOException ignored) {}
        return Cgroup.unavailable();
    }

    private static Path resolve(Path root, String cgroupPath) {
        String normalizedPath = cgroupPath.replace('\\', '/');
        while (normalizedPath.startsWith("/")) normalizedPath = normalizedPath.substring(1);
        Path relative = Path.of(normalizedPath).normalize();
        if (relative.isAbsolute() || relative.startsWith("..")) return root;
        Path result = root.resolve(relative).normalize();
        return result.startsWith(root.normalize()) ? result : root;
    }

    private static List<Path> ancestors(Path from, Path boundary) {
        java.util.ArrayList<Path> out = new java.util.ArrayList<>();
        Path root = boundary.normalize();
        Path p = from.normalize();
        while (p != null && p.startsWith(root)) {
            out.add(p);
            if (p.equals(root)) break;
            p = p.getParent();
        }
        if (!out.contains(root)) out.add(root);
        return out;
    }

    private static long number(Path path) {
        try {
            String value = Files.readString(path).trim();
            if (value.isEmpty() || value.equals("max")) return -1;
            return Long.parseLong(value);
        } catch (IOException | NumberFormatException ignored) { return -1; }
    }

    private static Map<String, Long> keyValues(Path path) {
        Map<String, Long> out = new LinkedHashMap<>();
        try {
            List<String> lines = Files.readAllLines(path);
            if (lines.size() == 1 && !lines.get(0).contains(" ")) {
                try { out.put("", Long.parseLong(lines.get(0).trim())); } catch (NumberFormatException ignored) {}
            } else for (String line : lines) {
                String[] fields = line.trim().split("\\s+");
                if (fields.length == 2) try { out.put(fields[0], Long.parseLong(fields[1])); } catch (NumberFormatException ignored) {}
            }
        } catch (IOException ignored) {}
        return out;
    }

    private static final long MB = 1024L * 1024;
    private record Cgroup(String version, long currentMb, long limitMb, long swapMb,
                          long memoryMaxEvents, long oomEvents, long oomKills) {
        static Cgroup unavailable() { return new Cgroup("unavailable", -1, -1, -1, -1, -1, -1); }
    }
}
