package dev.aichele.serverhoster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LifecycleRobustnessTest {
    @TempDir Path tmp;

    @Test void identityRequiresStartInstantAndCommandNotJustPid() {
        Instant start = Instant.parse("2026-11-07T09:00:00Z");
        ChildProcessRecord.Identity recorded = identity(4242, start, "/srv/child", "/srv/child/server.jar");
        ChildProcessRecord.Observed samePidNewProcess = new ChildProcessRecord.Observed(4242, start.plusMillis(10),
                List.of("/usr/bin/java", "-jar", "/srv/child/server.jar"), "/srv/child", true);
        ChildProcessRecord.Observed sameStartWithoutProof = new ChildProcessRecord.Observed(4242, start,
                List.of(), null, true);
        ChildProcessRecord.Observed exactJar = new ChildProcessRecord.Observed(4242, start,
                List.of("/usr/bin/java", "-jar", "/srv/child/server.jar", "nogui"), "/tmp", true);
        ChildProcessRecord.Observed cwdAndJarName = new ChildProcessRecord.Observed(4242, start,
                List.of("/opt/java/bin/java", "-Xmx5G", "-jar", "server.jar", "nogui"), "/srv/child", true);
        ChildProcessRecord.Observed otherJar = new ChildProcessRecord.Observed(4242, start,
                List.of("/usr/bin/java", "-jar", "/srv/other/server.jar"), "/srv/other", true);

        assertFalse(ChildProcessRecord.ours(recorded, samePidNewProcess, 1));
        assertFalse(ChildProcessRecord.ours(recorded, sameStartWithoutProof, 1));
        assertFalse(ChildProcessRecord.ours(recorded, otherJar, 1));
        assertFalse(ChildProcessRecord.ours(recorded, exactJar, 4242));
        assertTrue(ChildProcessRecord.ours(recorded, exactJar, 1));
        assertTrue(ChildProcessRecord.ours(recorded, cwdAndJarName, 1));
    }

    @Test void reapRefusesUnmatchedProcessAndClearsOnlyADeadRecord() throws Exception {
        Path file = tmp.resolve("child-process.record");
        Instant start = Instant.parse("2026-11-07T09:00:01.123456789Z");
        ChildProcessRecord.Identity recorded = identity(77, start, "/srv/child", "/srv/child/server.jar");
        ChildProcessRecord.write(file, recorded);
        assertEquals(recorded, ChildProcessRecord.read(file).identity());

        FakeProcesses unmatched = new FakeProcesses(new ChildProcessRecord.Observed(77, start.plusSeconds(5),
                List.of("/usr/bin/java", "-jar", "/srv/child/server.jar"), "/srv/child", true));
        ChildProcessRecord.ReapResult refused = ChildProcessRecord.reap(file, Duration.ofMillis(20),
                Duration.ofMillis(20), unmatched, 1);
        assertEquals(ChildProcessRecord.Kind.REFUSED, refused.kind());
        assertTrue(unmatched.calls.isEmpty());
        assertTrue(Files.exists(file));

        FakeProcesses dead = new FakeProcesses(null);
        ChildProcessRecord.ReapResult cleared = ChildProcessRecord.reap(file, Duration.ZERO, Duration.ZERO, dead, 1);
        assertEquals(ChildProcessRecord.Kind.CLEARED, cleared.kind());
        assertFalse(Files.exists(file));
    }

    @Test void reapKillsOnlyAfterGracefulStopFails() throws Exception {
        Path file = tmp.resolve("child-process.record");
        Instant start = Instant.parse("2026-11-07T09:00:02Z");
        ChildProcessRecord.write(file, identity(88, start, "/srv/child", "/srv/child/server.jar"));
        FakeProcesses ours = new FakeProcesses(new ChildProcessRecord.Observed(88, start,
                List.of("/usr/bin/java", "-jar", "/srv/child/server.jar"), "/srv/child", true));
        ours.surviveTerm = true;
        ChildProcessRecord.ReapResult result = ChildProcessRecord.reap(file, Duration.ofMillis(30),
                Duration.ofMillis(30), ours, 1);
        assertEquals(ChildProcessRecord.Kind.REAPED, result.kind());
        assertEquals(List.of("term", "kill"), ours.calls);
        assertFalse(Files.exists(file));
    }

    @Test void liveUnrelatedProcessIsNotKilledWhenTheRecordNamesIt() throws Exception {
        Process process = testProcess("sleep", "30000");
        try {
            Instant start = process.info().startInstant().orElseThrow();
            Path file = tmp.resolve("child-process.record");
            ChildProcessRecord.write(file, identity(process.pid(), start, "/srv/child", "/srv/child/server.jar"));
            ChildProcessRecord.ReapResult result = ChildProcessRecord.reap(file, Duration.ofMillis(100),
                    Duration.ofMillis(100), ChildProcessRecord.liveInspector(), ProcessHandle.current().pid());
            assertEquals(ChildProcessRecord.Kind.REFUSED, result.kind());
            assertTrue(process.isAlive());
            assertTrue(Files.exists(file));
        } finally {
            process.destroyForcibly();
        }
    }

    @Test void symlinkRecordIsRejected() throws Exception {
        Path real = tmp.resolve("real.txt");
        Files.writeString(real, "serverhoster-child-process 1\n");
        Path link = tmp.resolve("child-process.record");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | FileSystemException e) {
            assumeTrue(false, "Symbolic links are unavailable: " + e.getMessage());
        }
        ChildProcessRecord.Loaded loaded = ChildProcessRecord.read(link);
        assertNull(loaded.identity());
        assertTrue(loaded.error().contains("symlink"));
        ChildProcessRecord.ReapResult result = ChildProcessRecord.reap(link, Duration.ZERO, Duration.ZERO,
                new FakeProcesses(null), 1);
        assertEquals(ChildProcessRecord.Kind.INVALID_RECORD, result.kind());
        assertTrue(Files.isSymbolicLink(link));
    }

    @Test void failedStartDrainsProcessThatIgnoresTerm() throws Exception {
        Process process = testProcess("ignore-stop");
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            ChildLaunchCleanup.Result result = ChildLaunchCleanup.abandon(process, 400);
            assertFalse(process.isAlive(), result.detail());
            assertTrue(result.exited());
        });
    }

    @Test void stopWaitsUntilReadyBeforeSendingStop() throws Exception {
        Process process = testProcess("ready-stop");
        AtomicBoolean ready = new AtomicBoolean();
        AtomicBoolean sentTooEarly = new AtomicBoolean();
        Thread reader = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) if (line.contains("Done (")) ready.set(true);
            } catch (Exception ignored) {
                // The process closing its output is the end of the fixture.
            }
        }, "test-ready-reader");
        reader.setDaemon(true);
        reader.start();
        BufferedWriter stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        List<String> log = new ArrayList<>();
        ChildStopSequence.Outcome outcome = ChildStopSequence.stop(process, ready::get, 5, 3, 20, 1_000, 1,
                () -> {
                    if (!ready.get()) sentTooEarly.set(true);
                    stdin.write("stop");
                    stdin.newLine();
                    stdin.flush();
                }, log::add, "fixture");
        assertEquals(ChildStopSequence.Outcome.CLEAN, outcome);
        assertFalse(sentTooEarly.get());
        assertTrue(log.stream().anyMatch(line -> line.contains("exit code 0")));
        reader.join(1_000);
    }

    @Test void stopSendsStopWhenStartupNeverBecomesReadyAndThenKills() throws Exception {
        Process process = testProcess("never-ready");
        List<String> log = new ArrayList<>();
        ChildStopSequence.Outcome outcome = assertTimeoutPreemptively(Duration.ofSeconds(8), () ->
                ChildStopSequence.stop(process, () -> false, 0, 0, 20, 1_000, 0,
                        () -> {
                            process.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
                            process.getOutputStream().flush();
                        }, log::add, "fixture"));
        assertEquals(ChildStopSequence.Outcome.KILLED, outcome);
        assertTrue(log.stream().anyMatch(line -> line.contains("within 0s")));
        assertTrue(log.stream().anyMatch(line -> line.contains("KILLING")));
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (process.isAlive() && System.nanoTime() < deadline) Thread.sleep(50);
        assertFalse(process.isAlive());
    }

    @Test void supervisorDoesNotRestartDuringLaunchStopOrQueuedRestart() {
        assertEquals(SupervisorHealth.Action.NONE, SupervisorHealth.assess(state(
                SupervisorHealth.Phase.PREPARING, true, false, false, false, false, false, true)));
        assertEquals(SupervisorHealth.Action.REATTACH_PIPE, SupervisorHealth.assess(state(
                SupervisorHealth.Phase.STOPPING, true, true, true, false, false, false, false)));
        assertEquals(SupervisorHealth.Action.NONE, SupervisorHealth.assess(state(
                SupervisorHealth.Phase.IDLE, true, false, false, false, false, true, false)));
        assertEquals(SupervisorHealth.Action.REATTACH_BOTH, SupervisorHealth.assess(state(
                SupervisorHealth.Phase.RUNNING, true, false, true, false, false, false, false)));
        assertEquals(SupervisorHealth.Action.SCHEDULE_RESTART, SupervisorHealth.assess(state(
                SupervisorHealth.Phase.IDLE, true, false, false, false, false, false, false)));
        assertEquals(SupervisorHealth.Action.NONE, SupervisorHealth.assess(state(
                SupervisorHealth.Phase.IDLE, false, false, false, false, false, false, false)));
    }

    @Test void dashboardDistinguishesLifecycleAndRestartCancellation() {
        var idle = SupervisorHealth.Phase.IDLE;
        var preparing = SupervisorHealth.Phase.PREPARING;
        var running = SupervisorHealth.Phase.RUNNING;
        var stopping = SupervisorHealth.Phase.STOPPING;
        assertEquals(SupervisorHealth.DashboardState.STOPPED, SupervisorHealth.dashboardState(idle, false, false, false, false, false, false, true));
        assertEquals(SupervisorHealth.DashboardState.STARTING, SupervisorHealth.dashboardState(preparing, false, false, true, false, true, false, true));
        assertEquals(SupervisorHealth.DashboardState.STARTING, SupervisorHealth.dashboardState(running, true, false, true, false, false, false, true));
        assertEquals(SupervisorHealth.DashboardState.RUNNING, SupervisorHealth.dashboardState(running, true, true, true, false, false, false, true));
        assertEquals(SupervisorHealth.DashboardState.STOPPING, SupervisorHealth.dashboardState(stopping, true, true, false, true, false, false, true));
        assertEquals(SupervisorHealth.DashboardState.STOPPING, SupervisorHealth.dashboardState(preparing, false, false, false, true, true, false, true));
        assertEquals(SupervisorHealth.DashboardState.RESTART_WAIT, SupervisorHealth.dashboardState(idle, false, false, true, false, false, true, true));
        assertEquals(SupervisorHealth.DashboardState.STOPPED, SupervisorHealth.dashboardState(idle, false, false, true, false, false, true, false));
    }

    @Test void invalidMemoryThresholdDisablesAutoStopWithoutRewritingHeap() throws Exception {
        RuntimeLimits.MemoryPolicy invalid = RuntimeLimits.memory(Double.NaN, 95, 80, true);
        assertFalse(invalid.autoStop());
        assertEquals(100, invalid.critical(), 0.001);
        assertTrue(invalid.notes().getFirst().contains("stored configuration was not changed"));
        RuntimeLimits.MemoryPolicy current = RuntimeLimits.memory(85, 95, 80, true);
        assertTrue(current.autoStop());
        assertEquals(85, current.warning(), 0.001);
        assertEquals(95, current.critical(), 0.001);
        assertEquals(80, current.recovery(), 0.001);
        assertTrue(current.notes().isEmpty());
        RuntimeLimits.Bounded negative = RuntimeLimits.boundedSeconds(-4, 60);
        assertEquals(0, negative.value());
        assertEquals(60, RuntimeLimits.boundedSeconds(60, 60).value());

        String config = Files.readString(Path.of("src/main/resources/config.yml"));
        assertTrue(config.contains("xmx: 5G"));
        assertTrue(config.contains("xms: 2G"));
        assertFalse(config.contains("memoryTelemetry:"));
        assertFalse(config.contains("memory-telemetry.log"));
        assertTrue(config.contains("pausing while empty is always disabled"));
        assertFalse(config.contains("view-distance:"));
        assertFalse(config.contains("simulation-distance:"));
        assertFalse(config.contains("MinHeapFreeRatio"));
        assertFalse(config.contains("G1PeriodicGCInterval"));
    }

    @Test void retryExhaustionKeepsTheStartFailureInsteadOfPreparing() {
        String cause = "Custom jar not found: lifecycle-intentionally-missing.jar";
        String first = StartFailureStatus.text(new java.io.FileNotFoundException(cause));
        assertEquals("start failed: " + cause + ", restarting in 2s (attempt 1)",
                first + ", restarting in 2s (attempt 1)");
        String overwrittenByNextAttempt = "preparing";
        String restored = StartFailureStatus.text(new java.io.FileNotFoundException(cause));
        assertNotEquals(overwrittenByNextAttempt, restored);
        assertEquals("start failed: " + cause + ", gave up", restored + ", gave up");
        assertEquals("start failed: FileNotFoundException", StartFailureStatus.text(new java.io.FileNotFoundException()));
    }

    @Test void telemetryKeepsHeapRssHostAndCgroupDistinct() throws Exception {
        HostMemoryProbe.Snapshot host = new HostMemoryProbe.Snapshot(512, 8192, 1024, "v2",
                900, 1024, 32, 2, 0, 0);
        List<GcDiagnostics.Collector> collectors = List.of(new GcDiagnostics.Collector("G1 Young", 4, 30, 12));
        String line = MemoryTelemetry.line(Instant.parse("2026-11-07T09:00:00Z"),
                new MemoryTelemetry.Heap(2L << 20, 4L << 20, 8L << 20), 700, host, 300, 5, collectors);
        assertTrue(line.contains("heapJvm=outer"));
        assertTrue(line.contains("gcJvm=outer"));
        assertTrue(line.contains("heapUsedMiB=2"));
        assertTrue(line.contains("heapCommittedMiB=4"));
        assertTrue(line.contains("heapMaxMiB=8"));
        assertTrue(line.contains("hostRssMiB=700"));
        assertTrue(line.contains("hostAvailableMiB=512"));
        assertTrue(line.contains("hostSwapFreeMiB=1024"));
        assertTrue(line.contains("cgroupCurrentMiB=900"));
        assertTrue(line.contains("cgroupLimitMiB=1024"));
        assertTrue(line.contains("cgroupSwapMiB=32"));
        assertTrue(line.contains("cgroupOomEvents=0"));
        assertTrue(line.contains("childRssMiB=300"));
        assertTrue(line.contains("lastMs=12"));
        assertTrue(MemoryTelemetry.INTERPRETATION.contains("outer Spigot JVM only"));
        assertTrue(MemoryTelemetry.INTERPRETATION.contains("Child heap and child GC are not measured"));
        assertTrue(MemoryTelemetry.INTERPRETATION.contains("does not prove a container"));
        assertFalse(GcDiagnostics.snapshot().isEmpty());
    }

    private static SupervisorHealth.State state(SupervisorHealth.Phase phase, boolean wantRunning, boolean stopRequested,
                                                boolean processAlive, boolean watchArmed, boolean pipeArmed,
                                                boolean restartQueued, boolean launchInProgress) {
        return new SupervisorHealth.State(phase, false, wantRunning, stopRequested, processAlive,
                watchArmed, pipeArmed, restartQueued, launchInProgress);
    }

    private static ChildProcessRecord.Identity identity(long pid, Instant start, String workDir, String jar) {
        return new ChildProcessRecord.Identity(pid, start, workDir, jar, jar, "/usr/bin/java");
    }

    private static Process testProcess(String... args) throws Exception {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        List<String> command = new ArrayList<>(List.of(java.toString(), "-cp",
                System.getProperty("java.class.path"), LifecycleTestProcess.class.getName()));
        command.addAll(List.of(args));
        return new ProcessBuilder(command).start();
    }

    private static final class FakeProcesses implements ChildProcessRecord.Inspector {
        private final ChildProcessRecord.Observed observed;
        private final List<String> calls = new ArrayList<>();
        private boolean alive;
        private boolean surviveTerm;

        private FakeProcesses(ChildProcessRecord.Observed observed) {
            this.observed = observed;
            this.alive = observed != null && observed.alive();
        }

        @Override public ChildProcessRecord.Observed find(long pid) {
            return alive ? observed : null;
        }
        @Override public void destroy(long pid) {
            calls.add("term");
            if (!surviveTerm) alive = false;
        }
        @Override public void destroyForcibly(long pid) {
            calls.add("kill");
            alive = false;
        }
        @Override public boolean alive(long pid) { return alive; }
    }
}
