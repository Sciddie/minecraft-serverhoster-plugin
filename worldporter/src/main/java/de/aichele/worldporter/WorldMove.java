package de.aichele.worldporter;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Atomic world-folder rename. Never copies and never deletes. */
final class WorldMove {
    record Move(Path from, Path to) {}

    record Result(int ok, int skipped, int failed, boolean finished, List<String> lines) {}

    private WorldMove() {}

    static List<Move> plan(Path root, String sourceDir, String targetDir, String direction,
                           String overworld, String nether, String end, String levelName) {
        Path src = root.resolve(sourceDir).normalize();
        Path dst = root.resolve(targetDir).normalize();
        if (dst.equals(root)) throw new IllegalArgumentException("targetDir");
        String way = direction == null ? "spigot-to-vanilla" : direction.toLowerCase(java.util.Locale.ROOT);
        List<Move> m = new ArrayList<>();
        switch (way) {
            case "spigot-to-vanilla" -> {
                m.add(new Move(src.resolve(overworld), dst.resolve(levelName)));
                m.add(dim(src.resolve(nether), dst.resolve(levelName), "the_nether", "DIM-1"));
                m.add(dim(src.resolve(end), dst.resolve(levelName), "the_end", "DIM1"));
            }
            case "vanilla-to-spigot" -> {
                m.add(dim(src.resolve(levelName), dst.resolve(nether), "the_nether", "DIM-1"));
                m.add(dim(src.resolve(levelName), dst.resolve(end), "the_end", "DIM1"));
                m.add(new Move(src.resolve(levelName), dst.resolve(overworld)));
            }
            case "move-only" -> {
                for (String w : List.of(overworld, nether, end)) m.add(new Move(src.resolve(w), dst.resolve(w)));
            }
            default -> throw new IllegalArgumentException("direction");
        }
        return m;
    }

    static Result run(List<Move> moves, Set<Path> loaded, boolean dry) {
        List<String> lines = new ArrayList<>();
        Set<Path> blocked = new java.util.HashSet<>();
        int ok = 0, skipped = 0, failed = 0;
        for (Move mv : moves) {
            Path from = mv.from().normalize();
            Path to = mv.to().normalize();
            lines.add(from + " -> " + to);
            if (blocked.stream().anyMatch(to::startsWith)) { lines.add("  SKIP: parent not ready"); skipped++; continue; }
            boolean inUse = loaded.stream().anyMatch(l -> from.startsWith(l) || l.startsWith(from));
            if (inUse) { lines.add("  REFUSED: source is loaded"); blocked.add(to); failed++; continue; }
            if (!Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) { lines.add("  SKIP: source missing"); skipped++; continue; }
            if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) { lines.add("  SKIP: target exists"); skipped++; continue; }
            if (to.startsWith(from)) { lines.add("  REFUSED: target inside source"); failed++; continue; }
            if (dry) { lines.add("  DRY"); ok++; continue; }
            try {
                Files.createDirectories(to.getParent());
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
                lines.add("  DONE");
                ok++;
            } catch (AtomicMoveNotSupportedException e) {
                lines.add("  FAILED: not the same filesystem");
                failed++;
            } catch (IOException e) {
                lines.add("  FAILED: " + e.getMessage());
                failed++;
            }
        }
        return new Result(ok, skipped, failed, failed == 0, List.copyOf(lines));
    }

    private static Move dim(Path fromWorld, Path toWorld, String id, String legacy) {
        Path modern = Path.of("dimensions", "minecraft", id);
        if (!Files.isDirectory(fromWorld.resolve(modern), LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(fromWorld.resolve(legacy), LinkOption.NOFOLLOW_LINKS)) {
            return new Move(fromWorld.resolve(legacy), toWorld.resolve(legacy));
        }
        return new Move(fromWorld.resolve(modern), toWorld.resolve(modern));
    }
}
