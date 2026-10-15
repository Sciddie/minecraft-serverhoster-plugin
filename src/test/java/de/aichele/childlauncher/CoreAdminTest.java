package de.aichele.childlauncher;

import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CoreAdminTest {
    @TempDir Path tmp;

    @Test void backoffCapsAndDoesNotOverflow() {
        assertEquals(5, RestartBackoff.delaySeconds(5, 300, 0));
        assertEquals(10, RestartBackoff.delaySeconds(5, 300, 1));
        assertEquals(300, RestartBackoff.delaySeconds(5, 300, 10));
        assertEquals(300, RestartBackoff.delaySeconds(Long.MAX_VALUE, 300, 4));
        assertEquals(0, RestartBackoff.delaySeconds(-1, 10, 3));
    }

    @Test void tickQueryHidesOnlyTheReply() {
        TickQueryFilter f = new TickQueryFilter();
        long now = 10_000;
        assertFalse(f.consume("[12:00:00] [Server thread/INFO]: Alice: tick rate is bad", now, now));
        assertTrue(f.consume("[12:00:00] [Server thread/INFO]: Target tick rate: 20,0 per second", 9_000, now));
        assertEquals(20, f.targetRate, 0.001);
        assertTrue(f.consume("[12:00:00] [Server thread/INFO]: Average time per tick: 12,5 ms", 9_000, now));
        assertEquals(12.5, f.mspt, 0.001);
        assertEquals(20, f.tps, 0.001);
        assertTrue(f.consume("[12:00:00] [Server thread/INFO]: The game is running normally.", 9_000, now));
        assertTrue(f.consume("[12:00:00] [Server thread/INFO]: Percentiles: P50: 10.0 ms", 9_000, now));
        assertFalse(f.consume("[12:00:00] [Server thread/INFO]: Average time per tick: 12.5 ms", 1_000, now));
    }

    @Test void childReadinessRequiresTheVanillaDoneLine() {
        assertFalse(ChildSlot.isReadyLine("[Server thread/INFO]: Preparing spawn area: 95%"));
        assertFalse(ChildSlot.isReadyLine("[Server thread/INFO]: Stopping the server"));
        assertTrue(ChildSlot.isReadyLine("[Server thread/INFO]: Done (3.015s)! For help, type \"help\""));
    }

    @Test void heapSizeParserHandlesJvmSuffixesAndInvalidValues() {
        assertEquals(8L * 1024 * 1024 * 1024, FabricLauncherPlugin.parseMemoryBytes("8G"));
        assertEquals(1536L * 1024 * 1024, FabricLauncherPlugin.parseMemoryBytes("1536M"));
        assertEquals(1024L, FabricLauncherPlugin.parseMemoryBytes("1K"));
        assertEquals(-1, FabricLauncherPlugin.parseMemoryBytes("large"));
        assertEquals(-1, FabricLauncherPlugin.parseMemoryBytes("999999999999T"));
    }

    @Test void hostMemoryProbeReadsCgroupV2AndHostSwapWithoutCommands() throws Exception {
        Path proc = Files.createDirectories(tmp.resolve("proc/self"));
        Path cgroups = Files.createDirectories(tmp.resolve("cgroup/test"));
        Files.writeString(tmp.resolve("proc/meminfo"), "MemAvailable: 4194304 kB\nSwapTotal: 8388608 kB\nSwapFree: 2097152 kB\n");
        Files.writeString(proc.resolve("cgroup"), "0::/test\n");
        Files.writeString(cgroups.resolve("memory.current"), "9437184000\n");
        Files.writeString(cgroups.resolve("memory.max"), "12582912000\n");
        Files.writeString(cgroups.resolve("memory.swap.current"), "1073741824\n");
        Files.writeString(cgroups.resolve("memory.events"), "low 0\nhigh 1\nmax 2\noom 0\noom_kill 0\n");

        HostMemoryProbe.Snapshot snapshot = HostMemoryProbe.snapshot(tmp.resolve("proc"), tmp.resolve("cgroup"));

        assertEquals(4096, snapshot.availableMb());
        assertEquals(8192, snapshot.swapTotalMb());
        assertEquals(2048, snapshot.swapFreeMb());
        assertEquals("v2", snapshot.cgroupVersion());
        assertEquals(9000, snapshot.cgroupCurrentMb());
        assertEquals(12000, snapshot.cgroupLimitMb());
        assertEquals(1024, snapshot.cgroupSwapMb());
        assertEquals(2, snapshot.memoryMaxEvents());
        assertEquals(0, snapshot.oomEvents());
        assertEquals(0, snapshot.oomKills());
        assertEquals(75, snapshot.cgroupUsePercent(), 0.001);
    }

    @Test void hostMemoryProbeSeparatesCgroupV1LimitHitsFromOomEvents() throws Exception {
        Path proc = Files.createDirectories(tmp.resolve("proc/self"));
        Path memory = Files.createDirectories(tmp.resolve("cgroup/memory/test"));
        Files.writeString(proc.resolve("cgroup"), "5:memory:/test\n");
        Files.writeString(memory.resolve("memory.limit_in_bytes"), "10737418240\n");
        Files.writeString(memory.resolve("memory.usage_in_bytes"), "8589934592\n");
        Files.writeString(memory.resolve("memory.memsw.usage_in_bytes"), "9663676416\n");
        Files.writeString(memory.resolve("memory.failcnt"), "7\n");

        HostMemoryProbe.Snapshot snapshot = HostMemoryProbe.snapshot(tmp.resolve("proc"), tmp.resolve("cgroup"));

        assertEquals("v1", snapshot.cgroupVersion());
        assertEquals(8192, snapshot.cgroupCurrentMb());
        assertEquals(10240, snapshot.cgroupLimitMb());
        assertEquals(1024, snapshot.cgroupSwapMb());
        assertEquals(7, snapshot.memoryMaxEvents());
        assertEquals(-1, snapshot.oomEvents());
        assertEquals(-1, snapshot.oomKills());
    }

    @Test void memoryIncidentReportWritesTimelineFactsAndReadOnlyDiagnostics() throws Exception {
        Instant time = Instant.parse("2026-10-14T02:00:00Z");
        HostMemoryProbe.Snapshot snapshot = new HostMemoryProbe.Snapshot(512, 8192, 1024, "v2",
                9728, 10240, 512, 4, 1, 0);
        Path report = MemoryIncidentReport.write(tmp.resolve("memory-reports"), time,
                Map.of("Plugin version", "1.9.0", "Emergency trigger", "95.00%"),
                List.of(new MemoryIncidentReport.Sample(time.minusSeconds(15), snapshot)),
                Map.of("Spigot jcmd GC.heap_info", "heap dump text"));
        String text = Files.readString(report);

        assertEquals("memory-emergency-20261014T020000Z.log", report.getFileName().toString());
        assertTrue(text.contains("Plugin version: 1.9.0"));
        assertTrue(text.contains("v2, 9728, 10240, 95.00"));
        assertTrue(text.contains("[Spigot jcmd GC.heap_info]\nheap dump text"));
    }

    @Test void hostMemoryProbeReportsUnknownCgroupLimits() throws Exception {
        Path proc = Files.createDirectories(tmp.resolve("proc/self"));
        Files.createDirectories(tmp.resolve("cgroup"));
        Files.writeString(tmp.resolve("proc/meminfo"), "MemAvailable: 1024 kB\n");
        Files.writeString(proc.resolve("cgroup"), "0::/\n");
        Files.writeString(tmp.resolve("cgroup/memory.max"), "max\n");
        Files.writeString(tmp.resolve("cgroup/memory.current"), "1000\n");

        HostMemoryProbe.Snapshot snapshot = HostMemoryProbe.snapshot(tmp.resolve("proc"), tmp.resolve("cgroup"));

        assertEquals(-1, snapshot.cgroupLimitMb());
        assertEquals(-1, snapshot.cgroupUsePercent(), 0.001);
        assertEquals("unavailable", snapshot.cgroupVersion());
    }

    @Test void propertiesKeepCommentsAndForeignKeys() throws Exception {
        Path f = tmp.resolve("server.properties");
        Files.writeString(f, "# head\nmotd=old\nview-distance=8\n");
        PropertiesFile.update(f, Map.of("motd", "neu", "server-port", "2063"), Map.of("level-name", "world", "motd", "nope"));
        String text = Files.readString(f);
        assertTrue(text.startsWith("# head"));
        assertTrue(text.contains("motd=neu"));
        assertTrue(text.contains("view-distance=8"));
        assertTrue(text.contains("server-port=2063"));
        assertTrue(text.contains("level-name=world"));
        assertFalse(text.contains("motd=nope"));
    }

    @Test void pathGuardRejectsEscapeAndSymlink() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("child"));
        PathGuard g = PathGuard.open(root);
        Path inside = g.resolve("mods/a.jar");
        assertTrue(inside.startsWith(g.root()));
        assertThrows(SecurityException.class, () -> g.resolve("../secret"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        try {
            Files.createSymbolicLink(root.resolve("link"), outside);
            assertThrows(SecurityException.class, () -> g.resolve("link/x"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            assertTrue(Files.isDirectory(outside));
        }
    }

    @Test void logQueryFiltersFromTheEnd() throws Exception {
        Path log = tmp.resolve("latest.log");
        Files.writeString(log, "INFO start\nWARN disk\nERROR boom\nException x\nINFO end\n");
        LogQuery.Result err = LogQuery.query(log, "error", "", 10, 1024 * 1024);
        assertEquals(List.of("ERROR boom", "Exception x"), err.lines());
        assertFalse(err.truncated());
        LogQuery.Result tail = LogQuery.query(log, "all", "end", 10, 1024 * 1024);
        assertEquals(List.of("INFO end"), tail.lines());
        byte[] big = new byte[9000];
        java.util.Arrays.fill(big, (byte) 'a');
        Files.write(tmp.resolve("big.log"), big);
        LogQuery.Result cut = LogQuery.query(tmp.resolve("big.log"), "all", "", 5, 4096);
        assertTrue(cut.truncated());
    }

    @Test void checksumMismatch() throws Exception {
        Path f = tmp.resolve("blob");
        Files.writeString(f, "abc");
        String good = Upstream.digest(f, "SHA-256");
        Upstream.checkHash(f, "SHA-256", good);
        assertThrows(java.io.IOException.class, () -> Upstream.checkHash(f, "SHA-256", "00"));
        Upstream.checkHash(f, null, null);
    }

    @Test void selfSignedHandshakeAndPem() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("tls"));
        HttpsSupport.Spec spec = new HttpsSupport.Spec("self-signed", "dashboard.p12", "testpass", "testpass", "dashboard.crt", "dashboard.key", 30, "CN=FabricLauncher", List.of("dns:localhost", "ip:127.0.0.1"));
        SSLContext serverCtx = HttpsSupport.build(dir, spec, m -> {});
        assertTrue(Files.isRegularFile(dir.resolve("dashboard.p12")));
        HttpsServer hs = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpsSupport.configure(hs, serverCtx);
        hs.createContext("/", ex -> {
            byte[] b = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        hs.start();
        try {
            HttpResponse<String> res = client(dir.resolve("dashboard.p12"), "testpass".toCharArray())
                    .send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + hs.getAddress().getPort() + "/")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, res.statusCode());
            assertEquals("ok", res.body());
        } finally { hs.stop(0); }

        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(dir.resolve("dashboard.p12"))) {
            store.load(in, "testpass".toCharArray());
        }
        Certificate certificate = store.getCertificate("dashboard");
        PrivateKey privateKey = (PrivateKey) store.getKey("dashboard", "testpass".toCharArray());
        Files.writeString(dir.resolve("cert.pem"), pem("CERTIFICATE", certificate.getEncoded()));
        Files.writeString(dir.resolve("key.pem"), pem("PRIVATE KEY", privateKey.getEncoded()));
        SSLContext pem = HttpsSupport.fromPem(dir.resolve("cert.pem"), dir.resolve("key.pem"), "pempass".toCharArray());
        assertNotNull(pem);
        byte[] pkcs1 = rsaPkcs1(privateKey.getEncoded());
        Files.writeString(dir.resolve("key1.pem"), pem("RSA PRIVATE KEY", pkcs1));
        assertNotNull(HttpsSupport.loadKey(Files.readString(dir.resolve("key1.pem"))));
        assertEquals(List.of("dns:localhost", "ip:127.0.0.1"), HttpsSupport.normalizeSans(List.of()));
        assertEquals(List.of("dns:example.com", "ip:10.0.0.2"), HttpsSupport.normalizeSans(List.of("example.com", "10.0.0.2")));
    }

    private static String pem(String label, byte[] der) {
        String encoded = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + encoded + "\n-----END " + label + "-----\n";
    }

    private static byte[] rsaPkcs1(byte[] pkcs8) {
        DerValue sequence = derValue(pkcs8, 0);
        if (sequence.tag != 0x30 || sequence.end != pkcs8.length) throw new IllegalArgumentException("Invalid PKCS#8 sequence");
        DerValue version = derValue(pkcs8, sequence.content);
        DerValue algorithm = derValue(pkcs8, version.end);
        DerValue key = derValue(pkcs8, algorithm.end);
        if (algorithm.tag != 0x30 || key.tag != 0x04 || key.end != sequence.end)
            throw new IllegalArgumentException("Invalid RSA PKCS#8 key");
        return java.util.Arrays.copyOfRange(pkcs8, key.content, key.end);
    }

    private static DerValue derValue(byte[] der, int offset) {
        int tag = der[offset] & 0xff;
        int firstLength = der[offset + 1] & 0xff;
        int content = offset + 2;
        int length;
        if (firstLength < 0x80) {
            length = firstLength;
        } else {
            int count = firstLength & 0x7f;
            if (count == 0 || count > 4 || content + count > der.length)
                throw new IllegalArgumentException("Invalid DER length");
            length = 0;
            for (int i = 0; i < count; i++) length = (length << 8) | (der[content++] & 0xff);
        }
        int end = content + length;
        if (end > der.length) throw new IllegalArgumentException("Truncated DER value");
        return new DerValue(tag, content, end);
    }

    private record DerValue(int tag, int content, int end) {}

    @Test void probeAndIndexContract() throws Exception {
        long pid = ProcessHandle.current().pid();
        Map<String, Object> snap = ProcessProbe.snapshot(pid);
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            assertEquals(-1L, ((Number) snap.get("rssMb")).longValue());
            assertEquals(-1L, ((Number) snap.get("threads")).longValue());
        } else {
            assertTrue(((Number) snap.get("rssMb")).longValue() > 0);
            assertTrue(((Number) snap.get("threads")).intValue() > 0);
        }
        String flags = ProcessProbe.jcmd(pid, "VM.flags");
        assertFalse(flags.isBlank());
        assertThrows(IllegalArgumentException.class, () -> ProcessProbe.jcmd(pid, "GC.run"));
        Path dir = Files.createDirectories(tmp.resolve("disk"));
        Files.writeString(dir.resolve("a.txt"), "12345");
        ProcessProbe.Disk disk = ProcessProbe.disk(dir, 100);
        assertEquals(1, disk.files());
        assertEquals(5, disk.bytes());
        String html = Files.readString(Path.of("src/main/resources/web/index.html"));
        assertTrue(html.contains("id=\"props\""));
        assertTrue(html.contains("id=\"config\""));
        assertTrue(html.contains("<textarea"));
        assertFalse(html.contains("['mods','Mods']"));
        assertTrue(html.contains("['setup','Einrichtung']"));
        assertTrue(html.contains("['diag','Diagnose']"));
        assertTrue(html.contains("id=\"t-setup\""));
    }

    private static HttpClient client(Path keystore, char[] pass) throws Exception {
        KeyStore ts = KeyStore.getInstance("PKCS12");
        try (java.io.InputStream in = Files.newInputStream(keystore)) { ts.load(in, pass); }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(ctx).build();
    }
}
