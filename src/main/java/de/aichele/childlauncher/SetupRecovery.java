package de.aichele.childlauncher;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class SetupRecovery {
    enum Action {
        RESTORED,
        DELETED,
        ARCHIVED,
        PRESERVED
    }

    record Result(Action action, Path path) {}

    @FunctionalInterface
    interface Deleter {
        void delete(Path path) throws IOException;
    }

    private SetupRecovery() {}

    static List<Result> recover(Path serverRoot, boolean setupRequired, Deleter deleter) throws IOException {
        Path root = serverRoot.toAbsolutePath().normalize();
        List<Result> results = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            int examined = 0;
            for (Path pending : entries) {
                if (++examined > 2_048) throw new IOException("Server root exceeds the bounded recovery scan limit");
                String name = pending.getFileName().toString();
                int deleteMarker = name.lastIndexOf(".serverhoster-delete-pending-");
                int resetMarker = name.lastIndexOf(".serverhoster-reset-pending-");
                boolean deleting = deleteMarker > 0 && deleteMarker > resetMarker;
                int marker = deleting ? deleteMarker : resetMarker;
                if (marker <= 0) continue;
                try {
                    UUID.fromString(name.substring(marker + (deleting
                            ? ".serverhoster-delete-pending-".length()
                            : ".serverhoster-reset-pending-".length())));
                } catch (IllegalArgumentException e) {
                    results.add(new Result(Action.PRESERVED, pending));
                    continue;
                }
                if (Files.isSymbolicLink(pending) || !Files.isDirectory(pending, LinkOption.NOFOLLOW_LINKS)) {
                    results.add(new Result(Action.PRESERVED, pending));
                    continue;
                }
                Path original = root.resolve(name.substring(0, marker)).normalize();
                if (!root.equals(original.getParent()) || original.equals(root)) {
                    results.add(new Result(Action.PRESERVED, pending));
                    continue;
                }
                if (setupRequired) {
                    if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
                        results.add(new Result(Action.PRESERVED, pending));
                    } else {
                        Files.move(pending, original);
                        results.add(new Result(Action.RESTORED, original));
                    }
                } else if (deleting) {
                    deleter.delete(pending);
                    results.add(new Result(Action.DELETED, pending));
                } else {
                    Path archive = original.resolveSibling(original.getFileName() + ".serverhoster-archive-"
                            + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())
                            .format(java.time.Instant.now())
                            + "-" + UUID.randomUUID().toString().substring(0, 8));
                    Files.move(pending, archive);
                    results.add(new Result(Action.ARCHIVED, archive));
                }
            }
        }
        return List.copyOf(results);
    }
}
