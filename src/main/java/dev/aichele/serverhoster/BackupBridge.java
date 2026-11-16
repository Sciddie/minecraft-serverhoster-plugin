package dev.aichele.serverhoster;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Zip on demand. Backups stay local files or go through the remote FTP/FTPS bridge. */
final class BackupBridge {
    record Spec(String directory, List<String> paths, int keep) {}

    record Result(String id, String path, long bytes, String sha256) {}
    record Entry(String name, long bytes, long mtime) {}

    private static final long MAX_RESTORE_BYTES = 32L * 1024 * 1024 * 1024;
    private static final int MAX_RESTORE_FILES = 100_000;

    private BackupBridge() {}

    static List<Entry> list(Spec spec, String serverId) throws IOException {
        Path dir = directory(spec);
        if (!Files.isDirectory(dir)) return List.of();
        String safe = safeId(serverId);
        List<Entry> out = new ArrayList<>();
        try (var ds = Files.newDirectoryStream(dir, "backup-" + safe + "-*.zip")) {
            for (Path p : ds) {
                if (!named(safe, p.getFileName().toString()) || !Files.isRegularFile(p) || Files.isSymbolicLink(p)) continue;
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                out.add(new Entry(p.getFileName().toString(), a.size(), a.lastModifiedTime().toMillis()));
            }
        }
        out.sort(Comparator.comparing(Entry::name).reversed());
        return out;
    }

    static Path file(Spec spec, String serverId, String name) {
        String safe = safeId(serverId);
        if (!named(safe, name)) throw new IllegalArgumentException("backup name");
        Path dir = directory(spec);
        Path f = dir.resolve(name).normalize();
        if (f.getParent() == null || !f.getParent().equals(dir)) throw new IllegalArgumentException("backup name");
        if (!Files.isRegularFile(f) || Files.isSymbolicLink(f)) throw new IllegalArgumentException("backup name");
        return f;
    }

    static void delete(Spec spec, String serverId, String name) throws IOException {
        Files.deleteIfExists(file(spec, serverId, name));
    }

    /** Extracts a backup this bridge wrote. Rejects traversal. Caller must stop the child first. */
    static int restore(Path work, Spec spec, String serverId, String name) throws IOException {
        Path zip = file(spec, serverId, name);
        return restoreZip(work, zip);
    }

    static int restoreZip(Path work, Path zip) throws IOException {
        if (!Files.isRegularFile(zip) || Files.isSymbolicLink(zip)) throw new IOException("invalid backup archive");
        PathGuard guard = PathGuard.open(work);
        int n = 0;
        long bytes = 0;
        try (ZipFile z = new ZipFile(zip.toFile())) {
            if (z.size() > MAX_RESTORE_FILES) throw new IOException("zip too large");
            var en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String raw = e.getName().replace('\\', '/');
                if (raw.isBlank() || raw.contains("\0")) throw new SecurityException("zip entry");
                if (e.getSize() > MAX_RESTORE_BYTES) throw new IOException("zip entry too large");
                Path target = guard.resolve(raw);
                if (Files.isSymbolicLink(target)) throw new SecurityException("symlink");
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                try (InputStream in = z.getInputStream(e); var out = Files.newOutputStream(target)) {
                    byte[] buf = new byte[65536];
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        bytes += r;
                        if (bytes > MAX_RESTORE_BYTES) throw new IOException("zip too large");
                        out.write(buf, 0, r);
                    }
                }
                n++;
            }
        }
        if (n == 0) throw new IOException("empty zip");
        return n;
    }

    private static Path directory(Spec spec) {
        if (spec.directory == null || spec.directory.isBlank()) throw new IllegalArgumentException("backup directory");
        return Path.of(spec.directory).toAbsolutePath().normalize();
    }

    static String safeId(String serverId) {
        String s = serverId == null ? "" : serverId.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.isBlank() ? "server" : s;
    }

    static boolean named(String safe, String name) {
        return name != null && name.matches("backup-" + Pattern.quote(safe) + "-[0-9]{8}-[0-9]{6}-[0-9a-z]+\\.zip");
    }

    static Result archive(Path work, Spec spec, String serverId) throws Exception {
        if (spec.directory == null || spec.directory.isBlank()) throw new IOException("backup directory");
        PathGuard guard = PathGuard.open(work);
        Path dir = Path.of(spec.directory);
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + Long.toString(System.nanoTime(), 36);
        String safe = safeId(serverId);
        Path zip = dir.resolve("backup-" + safe + "-" + stamp + ".zip");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        int files = 0;
        try (ZipOutputStream out = new ZipOutputStream(new DigestOutputStream(Files.newOutputStream(zip), md))) {
            List<String> paths = spec.paths == null || spec.paths.isEmpty() ? List.of("world") : spec.paths;
            for (String rel : paths) {
                Path start;
                try { start = guard.resolve(rel); } catch (SecurityException e) { continue; }
                if (!Files.exists(start) || Files.isSymbolicLink(start)) continue;
                if (Files.isDirectory(start)) {
                    try (var walk = Files.walk(start)) {
                        for (Path p : (Iterable<Path>) walk::iterator) {
                            if (Files.isDirectory(p) || Files.isSymbolicLink(p)) continue;
                            if (!p.toRealPath().startsWith(guard.root())) continue;
                            files += write(out, guard.root(), p);
                        }
                    }
                } else if (pRealInside(guard, start)) files += write(out, guard.root(), start);
            }
        } catch (Exception e) {
            try { Files.deleteIfExists(zip); } catch (IOException cleanupError) { e.addSuppressed(cleanupError); }
            throw e;
        }
        if (files == 0) { Files.deleteIfExists(zip); throw new IOException("nothing to archive"); }
        String sha = HexFormat.of().formatHex(md.digest());
        long bytes = Files.size(zip);
        retain(dir, safe, spec.keep);
        return new Result("b-" + safe + "-" + stamp, zip.toString(), bytes, sha);
    }

    static int write(ZipOutputStream out, Path root, Path file) throws IOException {
        if (file.getFileName().toString().equals("session.lock")
                && Files.isRegularFile(file.getParent().resolve("level.dat"), LinkOption.NOFOLLOW_LINKS)) return 0;
        String name = root.relativize(file).toString().replace('\\', '/');
        out.putNextEntry(new ZipEntry(name));
        try (InputStream in = Files.newInputStream(file)) { in.transferTo(out); }
        out.closeEntry();
        return 1;
    }

    private static boolean pRealInside(PathGuard guard, Path p) throws IOException {
        return p.toRealPath().startsWith(guard.root());
    }

    private static void retain(Path dir, String server, int keep) throws IOException {
        if (keep <= 0) return;
        List<Path> zips = new ArrayList<>();
        try (var ds = Files.newDirectoryStream(dir, "backup-" + server + "-*.zip")) { for (Path p : ds) zips.add(p); }
        zips.sort(Comparator.comparing(Path::getFileName).reversed());
        for (int i = keep; i < zips.size(); i++) Files.deleteIfExists(zips.get(i));
    }
}
