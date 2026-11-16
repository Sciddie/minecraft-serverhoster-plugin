package dev.aichele.serverhoster;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Formats independent memory measurements. Host swap is not container memory, heap max is not RSS,
 * and a GC cycle duration is not a statement about MaxGCPauseMillis.
 */
final class MemoryTelemetry {
    static final String INTERPRETATION = "heapUsedMiB, heapCommittedMiB, heapMaxMiB and gc are the outer Spigot JVM only. "
            + "They are not the Fabric child. Child heap and child GC are not measured. "
            + "heapMaxMiB is the outer heap ceiling, not current usage and not the child Xmx. "
            + "On /api/diag, rssMb is the child process RSS and hostRssMiB is the outer process RSS. "
            + "hostAvailable and host swap come from host meminfo. cgroup current, limit, swap and events are container accounting. "
            + "A full host swap does not prove a container out-of-memory kill. "
            + "gc lastMs is the last observed outer-JVM collector cycle, not evidence that a pause target was met or missed.";

    record Heap(long usedBytes, long committedBytes, long maxBytes) {
        static Heap current() {
            Runtime runtime = Runtime.getRuntime();
            long committed = runtime.totalMemory();
            return new Heap(committed - runtime.freeMemory(), committed, runtime.maxMemory());
        }

        long usedMiB() { return mib(usedBytes); }
        long committedMiB() { return mib(committedBytes); }
        long maxMiB() { return mib(maxBytes); }
    }

    private MemoryTelemetry() {}

    /** Heap, RSS and GC read once per memory sample. cgroup data stays in the protection snapshot. */
    record Reading(Heap heap, long hostRssMiB, long childRssMiB, long childSwapMiB, List<GcDiagnostics.Collector> collectors) {}

    static Reading read(long childPid) {
        long childRss = -1;
        long childSwap = -1;
        if (childPid > 0) {
            Map<String, Object> child = ProcessProbe.snapshot(childPid);
            childRss = number(child.get("rssMb"));
            childSwap = number(child.get("swapMb"));
        }
        long hostRss = number(ProcessProbe.snapshot(ProcessHandle.current().pid()).get("rssMb"));
        return new Reading(Heap.current(), hostRss, childRss, childSwap, GcDiagnostics.snapshot());
    }

    static String line(Instant time, Heap heap, long hostRssMiB, HostMemoryProbe.Snapshot host,
                       long childRssMiB, long childSwapMiB, List<GcDiagnostics.Collector> collectors) {
        return "timestamp=" + time
                + " heapJvm=outer"
                + " gcJvm=outer"
                + " heapUsedMiB=" + heap.usedMiB()
                + " heapCommittedMiB=" + heap.committedMiB()
                + " heapMaxMiB=" + heap.maxMiB()
                + " hostRssMiB=" + hostRssMiB
                + " hostAvailableMiB=" + host.availableMb()
                + " hostSwapFreeMiB=" + host.swapFreeMb()
                + " hostSwapTotalMiB=" + host.swapTotalMb()
                + " cgroupVersion=" + host.cgroupVersion()
                + " cgroupCurrentMiB=" + host.cgroupCurrentMb()
                + " cgroupLimitMiB=" + host.cgroupLimitMb()
                + " cgroupSwapMiB=" + host.cgroupSwapMb()
                + " cgroupMaxEvents=" + host.memoryMaxEvents()
                + " cgroupOomEvents=" + host.oomEvents()
                + " cgroupOomKills=" + host.oomKills()
                + " childRssMiB=" + childRssMiB
                + " childSwapMiB=" + childSwapMiB
                + " gc=" + formatGc(collectors);
    }

    static List<Map<String, Object>> gcView(List<GcDiagnostics.Collector> collectors) {
        List<Map<String, Object>> gc = new ArrayList<>();
        if (collectors == null) return gc;
        for (GcDiagnostics.Collector collector : collectors) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", collector.name());
            row.put("collectionCount", collector.collectionCount());
            row.put("collectionTimeMs", collector.collectionTimeMs());
            row.put("lastDurationMs", collector.lastDurationMs());
            gc.add(row);
        }
        return gc;
    }

    private static String formatGc(List<GcDiagnostics.Collector> collectors) {
        if (collectors == null || collectors.isEmpty()) return "none";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < collectors.size(); i++) {
            if (i > 0) text.append(';');
            GcDiagnostics.Collector collector = collectors.get(i);
            text.append(collector.name().replace(' ', '_'))
                    .append(":count=").append(collector.collectionCount())
                    .append(",timeMs=").append(collector.collectionTimeMs())
                    .append(",lastMs=").append(collector.lastDurationMs());
        }
        return text.toString();
    }

    private static long mib(long bytes) {
        return bytes < 0 ? -1 : bytes / (1024 * 1024);
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : -1;
    }
}
