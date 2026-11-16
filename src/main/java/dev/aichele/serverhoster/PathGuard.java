package dev.aichele.serverhoster;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** Resolves a user path inside a root and rejects traversal and symlink escapes. */
final class PathGuard {
    private static final Set<String> HOST_DIRECTORIES = Set.of("plugins", "logs", "libraries", "cache", "world", "world_nether", "world_the_end", "worlds", "versions", "build-tools", "bundler");
    private final Path root;

    private PathGuard(Path root) { this.root = root; }

    static PathGuard open(Path workDir) throws IOException {
        Files.createDirectories(workDir);
        return new PathGuard(workDir.toRealPath());
    }

    Path root() { return root; }

    Path resolve(String rel) throws IOException {
        if (rel == null) rel = "";
        rel = rel.replace('\\', '/');
        while (rel.startsWith("/")) rel = rel.substring(1);
        Path p = root.resolve(rel).normalize();
        if (!p.startsWith(root)) throw new SecurityException("Pfad außerhalb des Server-Ordners");
        Path check = p;
        while (check != null && !Files.exists(check, LinkOption.NOFOLLOW_LINKS)) check = check.getParent();
        if (check == null || !check.toRealPath().startsWith(root)) throw new SecurityException("Pfad außerhalb des Server-Ordners");
        return p;
    }

    String relativize(Path p) { return root.relativize(p).toString().replace('\\', '/'); }

    /** New child folders stay relative to the server root and must not reuse a host directory. */
    static Path resolveNewDirectory(Path serverRoot, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Serverordner fehlt");
        Path root = serverRoot.toAbsolutePath().normalize();
        Path requested = Path.of(value.trim());
        if (requested.isAbsolute()) throw new IllegalArgumentException("Neue Serverordner müssen relativ zum Serververzeichnis sein");
        Path resolved = root.resolve(requested).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root))
            throw new IllegalArgumentException("Serverordner liegt außerhalb des erlaubten Bereichs");
        Path relative = root.relativize(resolved);
        if (HOST_DIRECTORIES.contains(relative.getName(0).toString().toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("Serverordner darf kein bekanntes Host-Verzeichnis sein");
        Path current = root;
        for (Path segment : root.relativize(resolved)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current))
                throw new IllegalArgumentException("Serverordner enthält einen symbolischen Link");
        }
        return resolved;
    }
}
