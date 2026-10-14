package de.aichele.childlauncher;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class RemoteBackupBridge {
    record Settings(String protocol, String host, int port, String directory, Consumer<String> warning) {}
    record Result(String id, String path, long bytes, String sha256, String db, boolean encrypted) {}

    private record Session(FTPClient client, boolean encrypted) implements AutoCloseable {
        @Override public void close() throws IOException {
            if (!client.isConnected()) return;
            IOException failure = null;
            try {
                client.logout();
                if (!FTPReply.isPositiveCompletion(client.getReplyCode())) failure = replyError(client, "logout");
            } catch (IOException e) {
                failure = e;
            }
            try { client.disconnect(); }
            catch (IOException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
            if (failure != null) throw failure;
        }
    }

    private static void warnPlainFtp(Settings settings) {
        String protocol = settings.protocol() == null ? "AUTO" : settings.protocol().trim().toUpperCase(Locale.ROOT);
        String mode = "AUTO".equals(protocol) ? "AUTO selected plaintext FTP" : "FTP is configured";
        settings.warning().accept(mode + "; credentials and backup data are unencrypted. Prefer FTPS.");
    }

    private RemoteBackupBridge() {}

    static boolean test(Settings settings, String username, String password) throws IOException {
        try (Session session = connect(settings, username, password)) {
            enterBaseDirectory(session.client(), settings.directory());
            return session.encrypted();
        }
    }

    static List<BackupBridge.Entry> list(Settings settings, String username, String password, String serverId)
            throws IOException {
        String safe = BackupBridge.safeId(serverId);
        try (Session session = connect(settings, username, password)) {
            enterDirectory(session.client(), settings.directory(), safe);
            List<BackupBridge.Entry> entries = new ArrayList<>();
            for (FTPFile file : session.client().listFiles()) {
                if (file == null || !file.isFile() || !BackupBridge.named(safe, file.getName())) continue;
                long mtime = file.getTimestamp() == null ? 0 : file.getTimestamp().getTimeInMillis();
                entries.add(new BackupBridge.Entry(file.getName(), file.getSize(), mtime));
            }
            entries.sort(Comparator.comparing(BackupBridge.Entry::name).reversed());
            return entries;
        }
    }

    static Result archive(Path work, BackupBridge.Spec backup, Settings settings, String username,
                          String password, String serverId) throws Exception {
        PathGuard guard = PathGuard.open(work);
        String safe = BackupBridge.safeId(serverId);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-" + Long.toString(System.nanoTime(), 36);
        String id = "b-" + safe + "-" + stamp;
        String name = "backup-" + safe + "-" + stamp + ".zip";
        String partial = name + ".part";
        try (Session session = connect(settings, username, password)) {
            FTPClient client = session.client();
            enterDirectory(client, settings.directory(), safe);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            CountingOutputStream counted;
            int files = 0;
            boolean pending = false;
            try {
                OutputStream transfer = client.storeFileStream(partial);
                if (transfer == null) throw replyError(client, "start remote backup upload");
                pending = true;
                counted = new CountingOutputStream(transfer);
                try (ZipOutputStream zip = new ZipOutputStream(new DigestOutputStream(counted, digest))) {
                    List<String> paths = backup.paths() == null || backup.paths().isEmpty()
                            ? List.of("world") : backup.paths();
                    for (String relative : paths) {
                        Path start;
                        try { start = guard.resolve(relative); }
                        catch (SecurityException e) { continue; }
                        if (!Files.exists(start) || Files.isSymbolicLink(start)) continue;
                        if (Files.isDirectory(start)) {
                            try (var walk = Files.walk(start)) {
                                for (Path file : (Iterable<Path>) walk::iterator) {
                                    if (Files.isDirectory(file) || Files.isSymbolicLink(file)) continue;
                                    if (!file.toRealPath().startsWith(guard.root())) continue;
                                    files += BackupBridge.write(zip, guard.root(), file);
                                }
                            }
                        } else if (start.toRealPath().startsWith(guard.root())) {
                            files += BackupBridge.write(zip, guard.root(), start);
                        }
                    }
                }
                boolean completed = client.completePendingCommand();
                pending = false;
                if (!completed) throw replyError(client, "complete remote backup upload");
                if (files == 0) throw new IOException("nothing to archive");
                if (!client.rename(partial, name)) throw replyError(client, "finalize remote backup");
            } catch (Exception e) {
                if (pending) {
                    try { client.abort(); } catch (IOException abortError) { e.addSuppressed(abortError); }
                }
                try { client.deleteFile(partial); } catch (IOException cleanupError) { e.addSuppressed(cleanupError); }
                throw e;
            }
            long bytes = counted.count();
            String sha = HexFormat.of().formatHex(digest.digest());
            retain(client, safe, backup.keep());
            String db = "";
            String remotePath = remotePath(settings.directory(), safe, name);
            if (backup.dbEnabled()) {
                try { BackupBridge.insert(backup, id, serverId, remotePath, bytes, sha); db = "ok"; }
                catch (Exception e) { db = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
            }
            return new Result(id, remotePath, bytes, sha, db, session.encrypted());
        }
    }

    static void download(Settings settings, String username, String password, String serverId, String name,
                         OutputStream output) throws IOException {
        String safe = BackupBridge.safeId(serverId);
        if (!BackupBridge.named(safe, name)) throw new IllegalArgumentException("backup name");
        try (Session session = connect(settings, username, password)) {
            enterDirectory(session.client(), settings.directory(), safe);
            if (!session.client().retrieveFile(name, output)) throw replyError(session.client(), "download remote backup");
        }
    }

    static void delete(Settings settings, String username, String password, String serverId, String name)
            throws IOException {
        String safe = BackupBridge.safeId(serverId);
        if (!BackupBridge.named(safe, name)) throw new IllegalArgumentException("backup name");
        try (Session session = connect(settings, username, password)) {
            enterDirectory(session.client(), settings.directory(), safe);
            if (!session.client().deleteFile(name)) throw replyError(session.client(), "delete remote backup");
        }
    }

    static boolean unsupportedAuthTls(int replyCode) {
        return replyCode == 500 || replyCode == 501 || replyCode == 502 || replyCode == 504;
    }

    private static Session connect(Settings settings, String username, String password) throws IOException {
        Objects.requireNonNull(settings, "backup.remote");
        Objects.requireNonNull(settings.warning(), "backup.remote warning logger");
        if (settings.host() == null || settings.host().isBlank()) throw new IOException("backup.remote.host is required");
        validateCredentials(username, password);
        String protocol = settings.protocol() == null ? "AUTO" : settings.protocol().trim().toUpperCase(Locale.ROOT);
        if (!List.of("AUTO", "FTP", "FTPS").contains(protocol)) throw new IOException("backup.remote.protocol must be AUTO, FTP, or FTPS");
        if (settings.port() < 1 || settings.port() > 65535) throw new IOException("backup.remote.port is invalid");
        if ("FTPS".equals(protocol)) return openFtps(settings, username, password);
        if ("FTP".equals(protocol)) return openFtp(settings, username, password);
        if (settings.port() == 990) return openFtps(settings, username, password);

        FTPClient probe = new FTPClient();
        configure(probe);
        try {
            connectControl(probe, settings);
            int reply = probe.sendCommand("AUTH", "TLS");
            if (reply == 234) {
                disconnect(probe);
                return openFtps(settings, username, password);
            }
            if (!unsupportedAuthTls(reply)) {
                throw replyError(probe, "negotiate FTPS");
            }
            warnPlainFtp(settings);
            login(probe, username, password);
            return new Session(probe, false);
        } catch (IOException | RuntimeException e) {
            disconnectAfterFailure(probe, e);
            throw e;
        }
    }

    private static Session openFtps(Settings settings, String username, String password) throws IOException {
        FTPSClient client = new FTPSClient("TLS", settings.port() == 990);
        client.setEndpointCheckingEnabled(true);
        configure(client);
        try {
            connectControl(client, settings);
            login(client, username, password);
            client.execPBSZ(0);
            if (!FTPReply.isPositiveCompletion(client.getReplyCode())) throw replyError(client, "set FTPS protection buffer");
            client.execPROT("P");
            if (!FTPReply.isPositiveCompletion(client.getReplyCode())) throw replyError(client, "enable protected FTPS data channel");
            return new Session(client, true);
        } catch (IOException | RuntimeException e) {
            disconnectAfterFailure(client, e);
            throw e;
        }
    }

    private static Session openFtp(Settings settings, String username, String password) throws IOException {
        FTPClient client = new FTPClient();
        configure(client);
        try {
            connectControl(client, settings);
            warnPlainFtp(settings);
            login(client, username, password);
            return new Session(client, false);
        } catch (IOException | RuntimeException e) {
            disconnectAfterFailure(client, e);
            throw e;
        }
    }

    private static void connectControl(FTPClient client, Settings settings) throws IOException {
        client.connect(settings.host(), settings.port());
        if (!FTPReply.isPositiveCompletion(client.getReplyCode())) throw replyError(client, "connect");
    }

    private static void login(FTPClient client, String username, String password) throws IOException {
        if (!client.login(username, password == null ? "" : password)) throw replyError(client, "login");
        if (!client.setFileType(FTP.BINARY_FILE_TYPE)) throw replyError(client, "set binary transfer mode");
        client.enterLocalPassiveMode();
    }

    private static void configure(FTPClient client) {
        client.setConnectTimeout(15_000);
        client.setDefaultTimeout(15_000);
        client.setDataTimeout(600_000);
        client.setBufferSize(65_536);
    }

    private static void enterDirectory(FTPClient client, String directory, String server) throws IOException {
        enterBaseDirectory(client, directory);
        if (!client.changeWorkingDirectory(server)) {
            client.makeDirectory(server);
            if (!client.changeWorkingDirectory(server)) throw replyError(client, "create or enter server backup directory");
        }
    }

    private static void enterBaseDirectory(FTPClient client, String directory) throws IOException {
        String path = directory == null ? "" : directory.trim().replace('\\', '/');
        if (hasControlCharacter(path)) throw new IOException("backup.remote.directory is invalid");
        if (path.startsWith("/") && !client.changeWorkingDirectory("/")) throw replyError(client, "change to FTP root");
        for (String segment : path.split("/")) {
            if (segment.isBlank() || ".".equals(segment)) continue;
            if ("..".equals(segment)) throw new IOException("backup.remote.directory cannot contain '..'");
            if (!client.changeWorkingDirectory(segment)) {
                client.makeDirectory(segment);
                if (!client.changeWorkingDirectory(segment)) throw replyError(client, "create or enter remote directory");
            }
        }
    }

    private static void retain(FTPClient client, String server, int keep) throws IOException {
        if (keep <= 0) return;
        List<String> names = new ArrayList<>();
        for (FTPFile file : client.listFiles()) {
            if (file != null && file.isFile() && BackupBridge.named(server, file.getName())) names.add(file.getName());
        }
        names.sort(Comparator.reverseOrder());
        for (int i = keep; i < names.size(); i++) {
            if (!client.deleteFile(names.get(i))) throw replyError(client, "retain remote backups");
        }
    }

    private static String remotePath(String directory, String server, String name) {
        String base = directory == null ? "" : directory.trim().replace('\\', '/');
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/" + server + "/" + name;
    }

    private static void validateCredentials(String username, String password) throws IOException {
        if (username == null || username.isBlank()) throw new IOException("backup FTP username is required in secrets.yml");
        if (hasControlCharacter(username) || hasControlCharacter(password)) {
            throw new IOException("FTP credentials cannot contain control characters");
        }
    }

    private static boolean hasControlCharacter(String value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) return true;
        }
        return false;
    }

    private static IOException replyError(FTPClient client, String action) {
        String reply = client.getReplyString();
        return new IOException(action + " failed (" + client.getReplyCode() + "): "
                + (reply == null ? "no server reply" : reply.trim()));
    }

    private static void disconnect(FTPClient client) throws IOException {
        if (client.isConnected()) client.disconnect();
    }

    private static void disconnectAfterFailure(FTPClient client, Exception failure) {
        try { disconnect(client); }
        catch (IOException closeError) { failure.addSuppressed(closeError); }
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        private long count;
        private CountingOutputStream(OutputStream out) { super(out); }
        @Override public void write(int value) throws IOException { out.write(value); count++; }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length);
            count += length;
        }
        long count() { return count; }
    }
}
