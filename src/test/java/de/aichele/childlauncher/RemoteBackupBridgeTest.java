package de.aichele.childlauncher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.SocketException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class RemoteBackupBridgeTest {
    @TempDir Path work;

    @Test void autoModeStreamsFtpBackupAndFinalizesPartialUpload() throws Exception {
        Files.createDirectories(work.resolve("world"));
        Files.writeString(work.resolve("world").resolve("level.dat"), "world-data");
        BackupBridge.Spec backup = new BackupBridge.Spec("", java.util.List.of("world"), 0, false,
                "", "", "", "", "", "");

        try (StubFtpServer server = new StubFtpServer()) {
            var warnings = new java.util.ArrayList<String>();
            RemoteBackupBridge.Settings settings = new RemoteBackupBridge.Settings("AUTO", "127.0.0.1",
                    server.port(), "", warnings::add);
            RemoteBackupBridge.Result result = RemoteBackupBridge.archive(work, backup, settings, "user", "pass", "default");

            assertFalse(result.encrypted());
            assertTrue(warnings.get(0).contains("unencrypted"));
            assertTrue(result.path().endsWith(result.id().replace("b-default-", "backup-default-") + ".zip"));
            assertEquals(1, server.files.size());
            assertTrue(server.files.keySet().stream().noneMatch(name -> name.endsWith(".part")));
            BackupBridge.Entry listed = RemoteBackupBridge.list(settings, "user", "pass", "default").get(0);
            assertEquals(result.path().substring(result.path().lastIndexOf('/') + 1), listed.name());
            Path downloaded = work.resolve("download.zip");
            try (var output = Files.newOutputStream(downloaded)) {
                RemoteBackupBridge.download(settings, "user", "pass", "default", listed.name(), output);
            }
            byte[] archive = Files.readAllBytes(downloaded);
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
                assertEquals("world/level.dat", zip.getNextEntry().getName());
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                zip.transferTo(content);
                assertEquals("world-data", content.toString(StandardCharsets.UTF_8));
            }
            Path restore = work.resolve("restore");
            Files.createDirectories(restore);
            assertEquals(1, BackupBridge.restoreZip(restore, downloaded));
            assertEquals("world-data", Files.readString(restore.resolve("world").resolve("level.dat")));
            RemoteBackupBridge.delete(settings, "user", "pass", "default", listed.name());
            assertTrue(RemoteBackupBridge.list(settings, "user", "pass", "default").isEmpty());
            assertFalse(Files.exists(work.resolve("backups")));
        }
    }

    private static final class StubFtpServer implements AutoCloseable {
        private final ServerSocket control;
        private final Thread thread;
        private final Map<String, byte[]> files = new ConcurrentHashMap<>();
        private final Set<String> directories = ConcurrentHashMap.newKeySet();
        private volatile IOException failure;

        private StubFtpServer() throws Exception {
            control = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            control.setSoTimeout(15_000);
            thread = new Thread(this::serve, "test-ftp-server");
            thread.setDaemon(true);
            thread.start();
        }

        private int port() { return control.getLocalPort(); }

        private void serve() {
            while (!control.isClosed()) {
                try (Socket socket = control.accept()) {
                    serveConnection(socket);
                } catch (SocketException e) {
                    if (!control.isClosed()) failure = e;
                    return;
                } catch (IOException e) {
                    if (!control.isClosed()) failure = e;
                    return;
                }
            }
        }

        private void serveConnection(Socket socket) throws IOException {
            try (socket;
                 BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                 PrintWriter output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), true)) {
                socket.setSoTimeout(15_000);
                String directory = "";
                String renameFrom = "";
                ServerSocket data = null;
                output.println("220 test FTP ready");
                String line;
                while ((line = input.readLine()) != null) {
                    String[] parts = line.split(" ", 2);
                    String command = parts[0].toUpperCase(java.util.Locale.ROOT);
                    String argument = parts.length > 1 ? parts[1] : "";
                    switch (command) {
                        case "AUTH" -> output.println("502 TLS not supported");
                        case "USER" -> output.println("331 password required");
                        case "PASS" -> output.println("230 logged in");
                        case "TYPE" -> output.println("200 binary");
                        case "SYST" -> output.println("215 UNIX Type: L8");
                        case "CWD" -> {
                            if ("/".equals(argument)) {
                                directory = "";
                                output.println("250 root");
                            } else if (directories.contains(argument)) {
                                directory = argument;
                                output.println("250 directory");
                            } else {
                                output.println("550 missing");
                            }
                        }
                        case "MKD" -> {
                            directories.add(argument);
                            output.println("257 created");
                        }
                        case "PASV" -> {
                            if (data != null) data.close();
                            data = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                            int port = data.getLocalPort();
                            output.println("227 Entering Passive Mode (127,0,0,1," + (port / 256) + "," + (port % 256) + ")");
                        }
                        case "STOR" -> {
                            output.println("150 opening data connection");
                            try (Socket dataSocket = data.accept()) {
                                files.put(directory + "/" + argument, dataSocket.getInputStream().readAllBytes());
                            } finally {
                                data.close();
                                data = null;
                            }
                            output.println("226 transfer complete");
                        }
                        case "LIST" -> {
                            output.println("150 opening data connection");
                            try (Socket dataSocket = data.accept();
                                 PrintWriter dataOutput = new PrintWriter(new OutputStreamWriter(dataSocket.getOutputStream(), StandardCharsets.US_ASCII), true)) {
                                String prefix = directory + "/";
                                for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                                    if (!entry.getKey().startsWith(prefix)) continue;
                                    String name = entry.getKey().substring(prefix.length());
                                    if (name.contains("/")) continue;
                                    dataOutput.println("-rw-r--r-- 1 owner group " + entry.getValue().length + " Jan 01 00:00 " + name);
                                }
                            } finally {
                                data.close();
                                data = null;
                            }
                            output.println("226 listing complete");
                        }
                        case "RETR" -> {
                            byte[] value = files.get(directory + "/" + argument);
                            if (value == null) {
                                output.println("550 missing");
                                break;
                            }
                            output.println("150 opening data connection");
                            try (Socket dataSocket = data.accept()) {
                                dataSocket.getOutputStream().write(value);
                                dataSocket.getOutputStream().flush();
                            } finally {
                                data.close();
                                data = null;
                            }
                            output.println("226 transfer complete");
                        }
                        case "DELE" -> output.println(files.remove(directory + "/" + argument) == null
                                ? "550 missing" : "250 deleted");
                        case "RNFR" -> {
                            renameFrom = directory + "/" + argument;
                            output.println(files.containsKey(renameFrom) ? "350 rename ready" : "550 missing");
                        }
                        case "RNTO" -> {
                            byte[] value = files.remove(renameFrom);
                            if (value == null) output.println("550 missing");
                            else {
                                files.put(directory + "/" + argument, value);
                                output.println("250 renamed");
                            }
                        }
                        case "QUIT" -> {
                            output.println("221 bye");
                            return;
                        }
                        default -> output.println("200 ok");
                    }
                }
                if (data != null) data.close();
            }
        }

        @Override public void close() throws Exception {
            control.close();
            thread.join(2_000);
            assertFalse(thread.isAlive(), "FTP stub server did not stop");
            if (failure != null) throw failure;
        }
    }
}
