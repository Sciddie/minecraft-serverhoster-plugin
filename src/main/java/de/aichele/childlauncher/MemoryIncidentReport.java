package de.aichele.childlauncher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

final class MemoryIncidentReport {
    record Sample(Instant capturedAt, HostMemoryProbe.Snapshot snapshot) {}

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private MemoryIncidentReport() {}

    static Path write(Path directory, Instant createdAt, Map<String, String> facts, List<Sample> samples,
                      Map<String, String> diagnostics) throws IOException {
        Files.createDirectories(directory);
        Path report = directory.resolve("memory-emergency-" + FILE_TIME.format(createdAt) + ".log");
        StringBuilder text = new StringBuilder(16_384);
        text.append("ServerHoster memory emergency report\n")
                .append("Created: ").append(createdAt).append("\n\n")
                .append("[Incident facts]\n");
        facts.forEach((key, value) -> text.append(key).append(": ").append(value).append('\n'));
        text.append("\n[Recent memory samples]\n")
                .append("timestamp, cgroupVersion, currentMiB, limitMiB, usePercent, swapMiB, hostAvailableMiB, hostSwapFreeMiB, maxEvents, oomEvents, oomKills\n");
        for (Sample sample : samples) {
            HostMemoryProbe.Snapshot m = sample.snapshot();
            text.append(sample.capturedAt()).append(", ").append(m.cgroupVersion()).append(", ")
                    .append(m.cgroupCurrentMb()).append(", ").append(m.cgroupLimitMb()).append(", ")
                    .append(String.format(java.util.Locale.ROOT, "%.2f", m.cgroupUsePercent())).append(", ")
                    .append(m.cgroupSwapMb()).append(", ").append(m.availableMb()).append(", ")
                    .append(m.swapFreeMb()).append(", ").append(m.memoryMaxEvents()).append(", ")
                    .append(m.oomEvents()).append(", ").append(m.oomKills()).append('\n');
        }
        for (Map.Entry<String, String> entry : diagnostics.entrySet())
            text.append("\n[").append(entry.getKey()).append("]\n").append(entry.getValue()).append('\n');
        Files.writeString(report, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return report;
    }
}
