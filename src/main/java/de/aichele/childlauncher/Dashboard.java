package de.aichele.childlauncher;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import javax.net.ssl.SSLContext;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;

/** Web dashboard using only com.sun.net.httpserver. */
final class Dashboard {
    private static final long SESSION_TTL = 12 * 3600_000L;
    private static final long MAX_TEXT = 2 * 1024 * 1024;
    private static final long MAX_UPLOAD = 1024L * 1024 * 1024;
    private final FabricLauncherPlugin pl;
    private final Gson gson = new Gson();
    private final SecureRandom rnd = new SecureRandom();
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final Map<String, int[]> fails = new ConcurrentHashMap<>();      // ip -> [count]
    private final Map<String, Long> lockedUntil = new ConcurrentHashMap<>();
    private HttpServer server;
    private ExecutorService pool;
    private String password;
    private boolean tls;
    private volatile long lastCpuTicks = -1, lastCpuWall, lastCpuPid = -1;
    private volatile double lastCpuPct;

    Dashboard(FabricLauncherPlugin pl) { this.pl = pl; }

    void start() throws Exception {
        password = loadOrCreatePassword();
        String bind = pl.getConfig().getString("dashboard.bind", "");
        if (bind == null || bind.isBlank()) bind = pl.getConfig().getString("serverIp", "");
        if (bind == null || bind.isBlank()) bind = "0.0.0.0";
        int port = pl.getConfig().getInt("dashboard.port", 2064);
        InetSocketAddress addr = new InetSocketAddress(bind, port);
        if (pl.getConfig().getBoolean("dashboard.https.enabled", false)) {
            SSLContext ctx = HttpsSupport.build(pl.getDataFolder().toPath(), httpsSpec(pl.getConfig()), m -> pl.getLogger().info(m));
            HttpsServer https = HttpsServer.create(addr, 32);
            HttpsSupport.configure(https, ctx);
            server = https;
            tls = true;
        } else {
            server = HttpServer.create(addr, 32);
            tls = false;
        }
        pool = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "ServerhosterPlugin-Web"); t.setDaemon(true); return t; });
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
        pl.getLogger().info("Dashboard listening on " + (tls ? "https" : "http") + "://" + bind + ":" + port + "/");
    }

    boolean tls() { return tls; }

    private static HttpsSupport.Spec httpsSpec(FileConfiguration c) {
        return new HttpsSupport.Spec(
                c.getString("dashboard.https.mode", "self-signed"),
                c.getString("dashboard.https.keystore", "dashboard.p12"),
                c.getString("dashboard.https.keystorePassword", ""),
                c.getString("dashboard.https.keyPassword", ""),
                c.getString("dashboard.https.cert", "dashboard.crt"),
                c.getString("dashboard.https.key", "dashboard.key"),
                c.getInt("dashboard.https.days", 825),
                c.getString("dashboard.https.dn", "CN=ServerhosterPlugin"),
                c.getStringList("dashboard.https.sans"));
    }

    private String cookie(String token, long maxAge) {
        return "FLS=" + token + "; Path=/; Max-Age=" + maxAge + "; HttpOnly; SameSite=Strict" + (tls ? "; Secure" : "");
    }

    void stop() {
        if (server != null) server.stop(1);
        if (pool != null) pool.shutdownNow();
    }

    private String loadOrCreatePassword() throws IOException {
        Path f = pl.getDataFolder().toPath().resolve("dashboard-password.txt");
        Files.createDirectories(f.getParent());
        if (Files.exists(f)) {
            String p = Files.readString(f).trim();
            if (!p.isEmpty()) return p;
        }
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
        Files.writeString(f, sb + "\n");
        try { Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw----")); } catch (Exception ignored) {}
        pl.getLogger().warning("==================================================");
        pl.getLogger().warning(" Dashboard password generated (shown once): " + sb);
        pl.getLogger().warning(" Stored in " + f + " (edit file + restart to change)");
        pl.getLogger().warning("==================================================");
        return sb.toString();
    }

    // ---------------- routing ----------------
    private void handle(HttpExchange ex) throws IOException {
        try {
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().set("X-Frame-Options", "DENY");
            ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            if (tls) ex.getResponseHeaders().set("Strict-Transport-Security", "max-age=31536000");
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/") || path.equals("/index.html")) { serveIndex(ex); return; }
            if (path.startsWith("/assets/") && method.equals("GET")) { serveWebAsset(ex, path.substring(1)); return; }
            if (path.equals("/third-party-notices") && method.equals("GET")) { serveThirdPartyNotices(ex); return; }
            if (path.equals("/api/login") && method.equals("POST")) { login(ex); return; }
            if (!path.startsWith("/api/")) { send(ex, 404, "text/plain", "Not found"); return; }
            if (!authed(ex)) { json(ex, 401, Map.of("error", "Nicht angemeldet")); return; }
            if (pl.setupRequired() && !path.equals("/api/status") && !path.equals("/api/setup")
                    && !path.startsWith("/api/setup/")) {
                json(ex, 409, Map.of("error", "Bitte zuerst die ServerHoster-Einrichtung abschließen."));
                return;
            }
            if (!Set.of("GET", "HEAD", "OPTIONS").contains(method)
                    && !"1".equals(ex.getRequestHeaders().getFirst("X-FL"))) {
                json(ex, 403, Map.of("error", "CSRF"));
                return;
            }
            Map<String, String> q = query(ex);
            String sid = pl.focusId();
            if (path.equals("/api/schedules") || path.startsWith("/api/schedules/")) {
                schedulesApi(ex, method, path);
                return;
            }
            switch (method + " " + path) {
                case "POST /api/logout" -> { sessions.remove(token(ex)); ex.getResponseHeaders().add("Set-Cookie", cookie("", 0)); json(ex, 200, Map.of("ok", true)); }
                case "GET /api/status" -> json(ex, 200, status(sid));
                case "GET /api/console" -> json(ex, 200, pl.console(sid).snapshot());
                case "GET /api/console/stream" -> sse(ex, sid);
                case "POST /api/cmd" -> {
                    String c = body(ex, obj -> obj.get("command").getAsString()).trim();
                    if (c.isEmpty()) { json(ex, 400, Map.of("error", "leer")); return; }
                    pl.console(sid).add("> " + c);
                    json(ex, 200, Map.of("ok", pl.sendCommand(sid, c)));
                }
                case "POST /api/action" -> {
                    String a = q.getOrDefault("do", "");
                    switch (a) {
                        case "start" -> pl.requestStart(sid);
                        case "stop" -> pl.requestStop(sid);
                        case "restart" -> pl.requestRestart(sid);
                        default -> { json(ex, 400, Map.of("error", "unknown action")); return; }
                    }
                    pl.info(sid, "Dashboard action: " + a + " from " + ip(ex));
                    json(ex, 200, Map.of("ok", true));
                }
                case "GET /api/props" -> text(ex, readOr(pl.workDir(sid).resolve("server.properties")));
                case "GET /api/properties/preview" -> json(ex, 200, pl.serverPropertiesPreview(sid).stream()
                        .map(p -> Map.of("key", p.key(), "present", p.present(), "current", p.current(),
                                "next", p.next(), "action", p.action())).toList());
                case "POST /api/props" -> saveProps(ex, sid);
                case "GET /api/config" -> text(ex, readOr(pl.getDataFolder().toPath().resolve("config.yml")));
                case "POST /api/config" -> saveConfig(ex);
                case "GET /api/settings" -> json(ex, 200, pl.settingsView());
                case "POST /api/settings" -> json(ex, 200, saveSettings(ex));
                case "GET /api/players" -> players(ex, sid);
                case "GET /api/fs/list" -> fsList(ex, q, sid);
                case "GET /api/fs/read" -> fsRead(ex, q, sid);
                case "GET /api/fs/download" -> fsDownload(ex, q, sid);
                case "POST /api/fs/write" -> fsWrite(ex, q, sid);
                case "POST /api/fs/upload" -> fsUpload(ex, q, sid);
                case "POST /api/fs/rename" -> fsRename(ex, sid);
                case "POST /api/fs/mkdir" -> fsMkdir(ex, q, sid);
                case "POST /api/fs/delete" -> fsDelete(ex, sid);
                case "GET /api/diag" -> json(ex, 200, pl.diagMap(sid));
                case "POST /api/diag/threaddump" -> json(ex, 200, Map.of("text", pl.jcmd(sid, "Thread.print")));
                case "POST /api/diag/heap" -> json(ex, 200, Map.of("text", pl.jcmd(sid, "GC.heap_info")));
                case "POST /api/diag/flags" -> json(ex, 200, Map.of("text", pl.jcmd(sid, "VM.flags")));
                case "GET /api/logs" -> logs(ex, q, sid);
                case "GET /api/tasks" -> json(ex, 200, pl.tasks().stream().map(this::taskJson).toList());
                case "POST /api/tasks/run" -> {
                    String id = q.getOrDefault("id", "");
                    TaskDef found = null;
                    for (TaskDef t : pl.tasks()) if (t.id.equals(id)) found = t;
                    if (found == null) { json(ex, 404, Map.of("error", "unknown task")); return; }
                    pl.runTask(found);
                    json(ex, 200, Map.of("ok", true, "id", id));
                }
                case "GET /api/setup" -> json(ex, 200, pl.setupView());
                case "POST /api/setup" -> json(ex, 200, setup(ex));
                case "POST /api/setup/cleanup" -> {
                    JsonObject request = parseJsonObject(readBodyText(ex));
                    pl.cleanupLegacyFiles(requiredString(request, "confirm"));
                    json(ex, 200, Map.of("ok", true));
                }
                case "GET /api/plan" -> text(ex, pl.planText(sid));
                case "GET /api/jar" -> json(ex, 200, pl.jarView(sid));
                case "GET /api/crashes" -> json(ex, 200, pl.crashFiles(sid));
                case "GET /api/crashes/read" -> text(ex, pl.crashText(sid, q.get("file")));
                case "GET /api/versions" -> {
                    String type = q.getOrDefault("type", "fabric");
                    json(ex, 200, Map.of("type", type, "versions", pl.versions(type)));
                }
                case "GET /api/backup" -> {
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("last", pl.lastBackup == null ? "" : pl.lastBackup);
                    b.put("storage", pl.getConfig().getString("backup.storage", "local"));
                    b.put("files", pl.backupFiles(sid).stream().map(Dashboard::backupJson).toList());
                    json(ex, 200, b);
                }
                case "GET /api/backup/download" -> backupDownload(ex, sid, q.get("name"));
                case "POST /api/backup" -> json(ex, 200, Map.of("ok", true, "last", pl.runBackup(sid)));
                case "POST /api/backup/delete" -> backupMutate(ex, sid, false);
                case "POST /api/backup/restore" -> backupMutate(ex, sid, true);
                case "POST /api/backup/test" -> json(ex, 200, Map.of("ok", true, "transport", pl.testBackup()));
                default -> json(ex, 404, Map.of("error", "Not found"));
            }
        } catch (SecurityException e) {
            json(ex, 403, Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            json(ex, 409, Map.of("error", String.valueOf(e.getMessage())));
        } catch (IllegalArgumentException e) {
            json(ex, 400, Map.of("error", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            pl.getLogger().log(Level.FINE, "Dashboard error", e);
            try { json(ex, 500, Map.of("error", String.valueOf(e.getMessage()))); } catch (Exception ignored) {}
        } finally {
            ex.close();
        }
    }

    // ---------------- auth ----------------
    private String ip(HttpExchange ex) { return ex.getRemoteAddress().getAddress().getHostAddress(); }

    private void login(HttpExchange ex) throws IOException {
        String ip = ip(ex);
        long now = System.currentTimeMillis();
        Long until = lockedUntil.get(ip);
        if (until != null && until > now) { json(ex, 429, Map.of("error", "Zu viele Fehlversuche. Bitte " + ((until - now) / 1000 + 1) + " s warten.")); return; }
        String pw = body(ex, o -> o.has("password") ? o.get("password").getAsString() : "");
        if (MessageDigest.isEqual(pw.getBytes(StandardCharsets.UTF_8), password.getBytes(StandardCharsets.UTF_8))) {
            fails.remove(ip); lockedUntil.remove(ip);
            byte[] b = new byte[32]; rnd.nextBytes(b);
            String tok = HexFormat.of().formatHex(b);
            sessions.put(tok, now + SESSION_TTL);
            sessions.entrySet().removeIf(e -> e.getValue() < now);
            ex.getResponseHeaders().add("Set-Cookie", cookie(tok, SESSION_TTL / 1000));
            pl.getLogger().info("Dashboard login from " + ip);
            json(ex, 200, Map.of("ok", true));
        } else {
            int n = fails.computeIfAbsent(ip, k -> new int[1])[0] += 1;
            try { Thread.sleep(800); } catch (InterruptedException ignored) {}
            if (n >= 5) {
                long lock = Math.min(3600_000L, 30_000L << Math.min(n - 5, 7));
                lockedUntil.put(ip, now + lock);
                pl.getLogger().warning("Dashboard: " + n + " failed logins from " + ip + ", locked for " + lock / 1000 + "s");
            }
            json(ex, 403, Map.of("error", "Falsches Passwort"));
        }
    }

    private void schedulesApi(HttpExchange ex, String method, String path) throws IOException {
        if (path.equals("/api/schedules")) {
            if (method.equals("GET")) {
                json(ex, 200, pl.schedules().stream().map(this::scheduleJson).toList());
                return;
            }
            if (method.equals("POST")) {
                ScheduleDef schedule = parseSchedule(readBodyText(ex));
                synchronized (pl.configMutationLock()) {
                    List<ScheduleDef> schedules = new ArrayList<>(pl.schedules());
                    if (schedules.size() >= ScheduleDef.MAX_SCHEDULES)
                        throw new IllegalArgumentException("Zu viele Zeitpläne");
                    if (schedules.stream().anyMatch(item -> item.id().equals(schedule.id())))
                        throw new IllegalArgumentException("Schedule-ID existiert bereits");
                    schedules.add(schedule);
                    pl.saveSchedules(schedules);
                }
                json(ex, 201, schedule.toJson());
                return;
            }
            json(ex, 405, Map.of("error", "Methode nicht erlaubt"));
            return;
        }

        String suffix = path.substring("/api/schedules/".length());
        String[] parts = suffix.split("/", -1);
        String id = parts[0];
        if (id.isBlank() || !id.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Ungültige Schedule-ID");

        if (parts.length == 2 && parts[1].equals("run") && method.equals("POST")) {
            pl.runScheduleNow(id);
            json(ex, 202, Map.of("ok", true, "id", id));
            return;
        }

        if (parts.length == 2 && parts[1].equals("toggle") && method.equals("POST")) {
            JsonObject request = parseJsonObject(readBodyText(ex));
            if (!request.has("enabled") || !request.get("enabled").isJsonPrimitive()
                    || !request.get("enabled").getAsJsonPrimitive().isBoolean())
                throw new IllegalArgumentException("enabled muss ein Boolean sein");
            boolean enabled = request.get("enabled").getAsBoolean();
            ScheduleDef updated;
            synchronized (pl.configMutationLock()) {
                List<ScheduleDef> schedules = new ArrayList<>(pl.schedules());
                int index = scheduleIndex(schedules, id);
                updated = schedules.get(index).withEnabled(enabled);
                schedules.set(index, updated);
                pl.saveSchedules(schedules);
            }
            json(ex, 200, updated.toJson());
            return;
        }

        if (parts.length != 1) {
            json(ex, 404, Map.of("error", "Not found"));
            return;
        }
        if (method.equals("PUT")) {
            ScheduleDef replacement = parseSchedule(readBodyText(ex));
            if (!replacement.id().equals(id)) throw new IllegalArgumentException("Schedule-ID stimmt nicht überein");
            synchronized (pl.configMutationLock()) {
                List<ScheduleDef> schedules = new ArrayList<>(pl.schedules());
                int index = scheduleIndex(schedules, id);
                replacement = replacement.withLastRun(schedules.get(index).lastRun());
                schedules.set(index, replacement);
                pl.saveSchedules(schedules);
            }
            json(ex, 200, replacement.toJson());
            return;
        }
        if (method.equals("DELETE")) {
            synchronized (pl.configMutationLock()) {
                List<ScheduleDef> schedules = new ArrayList<>(pl.schedules());
                schedules.remove(scheduleIndex(schedules, id));
                pl.saveSchedules(schedules);
            }
            json(ex, 200, Map.of("ok", true, "id", id));
            return;
        }
        json(ex, 405, Map.of("error", "Methode nicht erlaubt"));
    }

    private ScheduleDef parseSchedule(String json) {
        Map<?, ?> raw;
        try {
            raw = gson.fromJson(json, Map.class);
        } catch (JsonParseException | IllegalStateException e) {
            throw new IllegalArgumentException("Ungültiger Zeitplan-JSON", e);
        }
        if (raw == null) throw new IllegalArgumentException("Ungültiger Zeitplan");
        Map<String, Object> sanitized = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key instanceof String name) sanitized.put(name, value);
        });
        sanitized.put("lastRun", 0L);
        return ScheduleDef.load(List.of(sanitized)).get(0);
    }

    private JsonObject parseJsonObject(String json) {
        try {
            JsonElement value = JsonParser.parseString(json);
            if (value.isJsonObject()) return value.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IllegalArgumentException("Ungültiger JSON-Request", e);
        }
        throw new IllegalArgumentException("JSON-Objekt erwartet");
    }

    private Map<String, Object> scheduleJson(ScheduleDef schedule) {
        Map<String, Object> result = schedule.toJson();
        result.put("last", pl.taskLast(schedule.id()));
        if (schedule.enabled()) {
            try {
                java.time.ZonedDateTime next = CronExpr.next(schedule.cron(),
                        java.time.ZonedDateTime.now(java.time.ZoneId.of(schedule.timezone())));
                result.put("next", next == null ? "" : next.toInstant().toString());
            } catch (RuntimeException e) {
                result.put("next", "");
            }
        } else {
            result.put("next", "");
        }
        return result;
    }

    private int scheduleIndex(List<ScheduleDef> schedules, String id) {
        for (int i = 0; i < schedules.size(); i++)
            if (schedules.get(i).id().equals(id)) return i;
        throw new IllegalArgumentException("Zeitplan nicht gefunden: " + id);
    }

    private String token(HttpExchange ex) {
        for (String h : ex.getRequestHeaders().getOrDefault("Cookie", List.of()))
            for (String part : h.split(";")) { String p = part.trim(); if (p.startsWith("FLS=")) return p.substring(4); }
        return "";
    }

    private boolean authed(HttpExchange ex) {
        String c = token(ex);
        Long exp = c.isEmpty() ? null : sessions.get(c);
        if (exp == null) return false;
        if (exp < System.currentTimeMillis()) { sessions.remove(c); return false; }
        return true;
    }

    // ---------------- status ----------------
    private Map<String, Object> status(String id) {
        ServerProfile pr = pl.profile(id);
        ChildSlot slot = pl.slot(id);
        Map<String, Object> m = new LinkedHashMap<>();
        long pid = slot.pid();
        m.put("id", pr.id);
        m.put("setupRequired", pl.setupRequired());
        m.put("running", slot.running());
        m.put("pid", pid);
        m.put("uptime", slot.uptimeMillis() / 1000);
        m.put("status", slot.lastStatus);
        m.put("type", pr.type);
        m.put("mcVersion", pr.mcVersion);
        m.put("address", (pr.serverIp == null ? "" : pr.serverIp) + ":" + pr.port);
        m.put("xmx", pr.xmx);
        m.put("players", new TreeSet<>(slot.onlinePlayers));
        m.put("tls", pl.dashboardTls());
        m.put("msptHistory", slot.msptHistory());
        m.put("lastBackup", pl.lastBackup == null ? "" : pl.lastBackup);
        if (pid > 0) {
            m.put("cpu", cpu(pid)); m.put("rssMb", rssMb(pid)); m.put("threads", procField(pid, "Threads:"));
            pl.maybeQueryChildTick(id);
            if (System.currentTimeMillis() - slot.tickAt < 60_000) { m.put("tps", slot.tps); m.put("mspt", slot.mspt); }
        }

        m.put("spigot", spigotStats());
        m.put("hostMemory", pl.hostMemoryView());
        return m;
    }

    private Map<String, Object> setup(HttpExchange ex) throws IOException {
        JsonObject request = parseJsonObject(readBodyText(ex));
        String action = requiredString(request, "action");
        String path = requiredString(request, "path");
        String type = optionalString(request, "type", "");
        String mcVersion = optionalString(request, "mcVersion", "");
        int port = -1;
        if (request.has("port")) {
            if (!request.get("port").isJsonPrimitive() || !request.getAsJsonPrimitive("port").isNumber())
                throw new IllegalArgumentException("port muss eine Ganzzahl sein");
            try { port = request.get("port").getAsBigDecimal().intValueExact(); }
            catch (ArithmeticException | NumberFormatException e) { throw new IllegalArgumentException("port muss eine Ganzzahl sein", e); }
        }
        String confirm = optionalString(request, "confirm", "");
        if (request.has("ntfy") && !request.get("ntfy").isJsonObject())
            throw new IllegalArgumentException("ntfy muss ein Objekt sein");
        JsonObject ntfy = request.has("ntfy") ? request.getAsJsonObject("ntfy") : new JsonObject();
        boolean ntfyEnabled = false;
        if (ntfy.has("enabled")) {
            if (!ntfy.get("enabled").isJsonPrimitive() || !ntfy.getAsJsonPrimitive("enabled").isBoolean())
                throw new IllegalArgumentException("ntfy.enabled muss ein Boolean sein");
            ntfyEnabled = ntfy.get("enabled").getAsBoolean();
        }
        String ntfyServer = optionalString(ntfy, "server", "https://ntfy.sh");
        String ntfyTopic = optionalString(ntfy, "topic", "");
        return pl.configureSetup(action, path, type, mcVersion, port, confirm, ntfyEnabled, ntfyServer, ntfyTopic);
    }

    private static String requiredString(JsonObject request, String key) {
        if (!request.has(key) || !request.get(key).isJsonPrimitive() || !request.getAsJsonPrimitive(key).isString())
            throw new IllegalArgumentException(key + " muss Text sein");
        return request.get(key).getAsString();
    }

    private static String optionalString(JsonObject request, String key, String fallback) {
        if (!request.has(key)) return fallback;
        if (!request.get(key).isJsonPrimitive() || !request.getAsJsonPrimitive(key).isString())
            throw new IllegalArgumentException(key + " muss Text sein");
        return request.get(key).getAsString();
    }

    private Map<String, Object> taskJson(TaskDef t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id); m.put("enabled", t.enabled); m.put("cron", t.cron); m.put("timezone", t.timezone);
        m.put("action", t.action); m.put("command", t.command); m.put("server", t.server);
        m.put("next", pl.taskNext(t)); m.put("last", pl.taskLast(t.id));
        return m;
    }

    private static Map<String, Object> backupJson(BackupBridge.Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", e.name()); m.put("bytes", e.bytes()); m.put("mtime", e.mtime());
        return m;
    }

    private void backupDownload(HttpExchange ex, String sid, String name) throws IOException {
        BackupBridge.Entry entry = pl.backupFiles(sid).stream()
                .filter(file -> file.name().equals(name)).findFirst()
                .orElseThrow(() -> new java.io.FileNotFoundException("backup not found"));
        ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + name.replaceAll("[\"\\\\\\r\\n]", "_") + "\"");
        ex.sendResponseHeaders(200, Math.max(0, entry.bytes()));
        try (OutputStream os = ex.getResponseBody()) { pl.downloadBackup(sid, name, os); }
    }

    private void backupMutate(HttpExchange ex, String sid, boolean restore) throws Exception {
        JsonObject o = JsonParser.parseString(readBodyText(ex)).getAsJsonObject();
        String name = o.get("name").getAsString();
        String confirm = o.has("confirm") ? o.get("confirm").getAsString() : "";
        if (!name.equals(confirm)) { json(ex, 400, Map.of("error", "Bestätigung fehlt")); return; }
        if (restore) {
            int n = pl.restoreBackup(sid, name);
            pl.info(sid, "Restored " + n + " files from " + name);
            json(ex, 200, Map.of("ok", true, "files", n));
        } else {
            pl.deleteBackup(sid, name);
            pl.info(sid, "Deleted backup " + name);
            json(ex, 200, Map.of("ok", true));
        }
    }

    private Map<String, Object> spigotStats() {
        Map<String, Object> o = new LinkedHashMap<>();
        Runtime rt = Runtime.getRuntime();
        o.put("heapUsedMb", (rt.totalMemory() - rt.freeMemory()) / 1048576);
        o.put("heapCommittedMb", rt.totalMemory() / 1048576);
        o.put("heapMaxMb", rt.maxMemory() / 1048576);
        try {
            var os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sos) {
                double l = sos.getProcessCpuLoad();
                o.put("cpu", l < 0 ? -1 : Math.round(l * Runtime.getRuntime().availableProcessors() * 1000) / 10.0); // % of one core
            }
        } catch (Throwable ignored) {}
        o.put("threads", java.lang.management.ManagementFactory.getThreadMXBean().getThreadCount());
        long self = ProcessHandle.current().pid();
        o.put("pid", self);
        o.put("rssMb", rssMb(self));
        o.put("tps", pl.outerTps); o.put("mspt", pl.outerMspt);
        o.put("chunks", pl.outerChunks); o.put("entities", pl.outerEntities); o.put("worlds", pl.outerWorlds);
        o.put("cores", Runtime.getRuntime().availableProcessors());
        return o;
    }

    private long procField(long pid, String key) {
        try { for (String l : Files.readAllLines(Paths.get("/proc/" + pid + "/status"))) if (l.startsWith(key)) return Long.parseLong(l.replaceAll("\\D", "")); }
        catch (Exception ignored) {}
        return -1;
    }

    private double cpu(long pid) {
        try {
            String st = Files.readString(Paths.get("/proc/" + pid + "/stat"));
            String[] f = st.substring(st.lastIndexOf(')') + 2).split(" ");
            long ticks = Long.parseLong(f[11]) + Long.parseLong(f[12]); // utime, stime
            long now = System.nanoTime();
            synchronized (this) {
                if (pid != lastCpuPid) { lastCpuTicks = -1; lastCpuPid = pid; }
                if (lastCpuTicks >= 0 && ticks >= lastCpuTicks && now - lastCpuWall > 500_000_000L) {
                    double secs = (now - lastCpuWall) / 1e9;
                    lastCpuPct = Math.round((ticks - lastCpuTicks) / 100.0 / secs * 1000) / 10.0; // % of one core, CLK_TCK=100
                }
                if (now - lastCpuWall > 500_000_000L || lastCpuTicks < 0 || ticks < lastCpuTicks) { lastCpuTicks = ticks; lastCpuWall = now; }
            }
            return lastCpuPct;
        } catch (Exception e) { return -1; }
    }

    private long rssMb(long pid) {
        try {
            for (String l : Files.readAllLines(Paths.get("/proc/" + pid + "/status")))
                if (l.startsWith("VmRSS:")) return Long.parseLong(l.replaceAll("\\D", "")) / 1024;
        } catch (Exception ignored) {}
        return -1;
    }

    private void players(HttpExchange ex, String sid) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String n : List.of("whitelist", "ops", "banned-players")) {
            Path f = pl.workDir(sid).resolve(n + ".json");
            List<String> names = new ArrayList<>();
            try {
                if (Files.exists(f)) for (JsonElement e : JsonParser.parseString(Files.readString(f)).getAsJsonArray())
                    if (e.getAsJsonObject().has("name")) names.add(e.getAsJsonObject().get("name").getAsString());
            } catch (Exception ignored) {}
            m.put(n, names);
        }
        m.put("online", new TreeSet<>(pl.players(sid)));
        json(ex, 200, m);
    }

    // ---------------- console SSE ----------------
    private void sse(HttpExchange ex, String sid) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        BlockingQueue<String> q = new LinkedBlockingQueue<>(5000);
        Consumer<String> l = q::offer;
        List<String> hist = pl.console(sid).subscribe(l);
        try (OutputStream os = ex.getResponseBody()) {
            StringBuilder sb = new StringBuilder();
            for (String s : hist) sb.append("data: ").append(gson.toJson(s)).append("\n\n");
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8)); os.flush();
            while (!Thread.currentThread().isInterrupted()) {
                String s = q.poll(15, TimeUnit.SECONDS);
                if (s == null) { os.write(": ping\n\n".getBytes(StandardCharsets.UTF_8)); os.flush(); continue; }
                sb.setLength(0);
                sb.append("data: ").append(gson.toJson(s)).append("\n\n");
                String more; int n = 0;
                while (n++ < 200 && (more = q.poll()) != null) sb.append("data: ").append(gson.toJson(more)).append("\n\n");
                os.write(sb.toString().getBytes(StandardCharsets.UTF_8)); os.flush();
            }
        } catch (IOException | InterruptedException ignored) {
        } finally {
            pl.console(sid).unsubscribe(l);
        }
    }

    // ---------------- editors ----------------
    private void saveProps(HttpExchange ex, String sid) throws IOException {
        String txt = readBodyText(ex);
        Properties p = new Properties();
        p.load(new StringReader(txt)); // validate
        Path f = pl.workDir(sid).resolve("server.properties");
        Files.createDirectories(f.getParent());
        Files.writeString(f, txt, StandardCharsets.UTF_8);
        boolean changed = pl.syncPropertiesEdit(sid, p);
        pl.info(sid, "server.properties saved via dashboard" + (changed ? " (config.yml synced)" : ""));
        json(ex, 200, Map.of("ok", true, "hint", "Wird beim nächsten Neustart des Servers aktiv."));
    }

    private void saveConfig(HttpExchange ex) throws IOException {
        String txt = readBodyText(ex);
        YamlConfiguration y = new YamlConfiguration();
        try { y.loadFromString(txt); } catch (Exception e) { json(ex, 400, Map.of("error", "YAML-Fehler: " + e.getMessage())); return; }
        Path f = pl.getDataFolder().toPath().resolve("config.yml");
        synchronized (pl.configMutationLock()) {
            Files.writeString(f, txt, StandardCharsets.UTF_8);
            FabricLauncherPlugin.makeWritable(f, false);
            pl.reloadConfig();
            pl.reloadProfiles();
        }
        pl.info("config.yml saved via dashboard");
        json(ex, 200, Map.of("ok", true, "hint", "Gespeichert. Wirkt beim nächsten Neustart des Child-Servers (Dashboard-Port/Passwort erst nach Spigot-Neustart)."));
    }

    private Map<String, Object> saveSettings(HttpExchange ex) throws IOException {
        JsonObject request = parseJsonObject(readBodyText(ex));
        if (!request.has("autoRestartEnabled") || !request.get("autoRestartEnabled").isJsonPrimitive()
                || !request.getAsJsonPrimitive("autoRestartEnabled").isBoolean())
            throw new IllegalArgumentException("autoRestartEnabled muss ein Boolean sein");
        ServerHosterSettings settings = new ServerHosterSettings(
                request.get("autoRestartEnabled").getAsBoolean(),
                requiredInt(request, "maxRestartAttempts"),
                requiredLong(request, "initialRestartDelaySeconds"),
                requiredLong(request, "maximumRestartDelaySeconds"),
                requiredLong(request, "restartResetAfterSeconds"),
                requiredString(request, "backupStorage"),
                requiredString(request, "backupProtocol"),
                requiredString(request, "backupHost"),
                requiredInt(request, "backupPort"),
                requiredString(request, "backupDirectory"));
        pl.saveSettings(settings);
        return Map.of("ok", true, "settings", pl.settingsView());
    }

    private static int requiredInt(JsonObject request, String key) {
        long value = requiredLong(request, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new IllegalArgumentException(key + " liegt außerhalb des gültigen Zahlenbereichs");
        return (int) value;
    }

    private static long requiredLong(JsonObject request, String key) {
        if (!request.has(key) || !request.get(key).isJsonPrimitive() || !request.getAsJsonPrimitive(key).isNumber())
            throw new IllegalArgumentException(key + " muss eine Ganzzahl sein");
        try { return request.get(key).getAsBigDecimal().longValueExact(); }
        catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException(key + " muss eine Ganzzahl sein", e);
        }
    }

    // ---------------- file browser ----------------
    private PathGuard guard(String sid) throws IOException { return PathGuard.open(pl.workDir(sid)); }
    private Path root(String sid) throws IOException { return guard(sid).root(); }
    private Path safe(String sid, String rel) throws IOException { return guard(sid).resolve(rel); }
    private String rel(String sid, Path p) throws IOException { return guard(sid).relativize(p); }

    private void fsList(HttpExchange ex, Map<String, String> q, String sid) throws IOException {
        Path d = safe(sid, q.get("path"));
        if (!Files.isDirectory(d)) { json(ex, 404, Map.of("error", "Kein Ordner")); return; }
        List<Map<String, Object>> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
            for (Path c : ds) {
                BasicFileAttributes a = Files.readAttributes(c, BasicFileAttributes.class);
                out.add(Map.of("name", c.getFileName().toString(), "dir", a.isDirectory(), "size", a.size(), "mtime", a.lastModifiedTime().toMillis()));
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> !(Boolean) m.get("dir")).thenComparing(m -> ((String) m.get("name")).toLowerCase(Locale.ROOT)));
        json(ex, 200, Map.of("path", rel(sid, d), "entries", out));
    }

    private void fsRead(HttpExchange ex, Map<String, String> q, String sid) throws IOException {
        Path f = safe(sid, q.get("path"));
        if (!Files.isRegularFile(f)) { json(ex, 404, Map.of("error", "Keine Datei")); return; }
        if (f.toString().endsWith(".gz")) {
            try (InputStream in = new GZIPInputStream(Files.newInputStream(f))) {
                byte[] b = in.readNBytes((int) MAX_TEXT + 1);
                String s = new String(b, 0, Math.min(b.length, (int) MAX_TEXT), StandardCharsets.UTF_8);
                if (b.length > MAX_TEXT) s += "\n[... gekürzt ...]";
                text(ex, s);
            }
            return;
        }
        if (Files.size(f) > MAX_TEXT) {
            // tail for big logs
            long size = Files.size(f);
            try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
                raf.seek(size - MAX_TEXT); byte[] b = new byte[(int) MAX_TEXT]; raf.readFully(b);
                text(ex, "[... Datei gekürzt, letzte 2 MB ...]\n" + new String(b, StandardCharsets.UTF_8));
            }
            return;
        }
        text(ex, Files.readString(f, StandardCharsets.UTF_8));
    }

    private void fsDownload(HttpExchange ex, Map<String, String> q, String sid) throws IOException {
        Path f = safe(sid, q.get("path"));
        if (!Files.isRegularFile(f)) { json(ex, 404, Map.of("error", "Keine Datei")); return; }
        ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + f.getFileName().toString().replaceAll("[\"\\\\\\r\\n]", "_") + "\"");
        ex.sendResponseHeaders(200, Files.size(f));
        try (OutputStream os = ex.getResponseBody()) { Files.copy(f, os); }
    }

    private void fsWrite(HttpExchange ex, Map<String, String> q, String sid) throws IOException {
        Path f = safe(sid, q.get("path"));
        if (Files.isDirectory(f)) { json(ex, 400, Map.of("error", "Ist ein Ordner")); return; }
        String txt = readBodyText(ex);
        Files.writeString(f, txt, StandardCharsets.UTF_8);
        pl.info(sid, "File saved via dashboard: " + rel(sid, f));
        json(ex, 200, Map.of("ok", true));
    }

    private void fsUpload(HttpExchange ex, Map<String, String> q, String sid) throws IOException {
        Path dir = safe(sid, q.get("path"));
        String name = q.getOrDefault("name", "");
        if (name.isBlank() || name.contains("/") || name.contains("\\") || name.equals("..") || name.equals(".")) { json(ex, 400, Map.of("error", "Ungültiger Name")); return; }
        Path f = safe(sid, rel(sid, dir) + "/" + name);
        if (Files.exists(f) && !"1".equals(q.get("overwrite"))) { json(ex, 409, Map.of("error", "Datei existiert bereits")); return; }
        Path tmp = f.resolveSibling(name + ".upload-part");
        long n = 0;
        try (InputStream in = ex.getRequestBody(); OutputStream os = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[65536]; int r;
            while ((r = in.read(buf)) > 0) { n += r; if (n > MAX_UPLOAD) throw new IOException("Upload zu groß (max 1 GB)"); os.write(buf, 0, r); }
        } catch (IOException e) { Files.deleteIfExists(tmp); throw e; }
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        pl.info(sid, "File uploaded via dashboard: " + rel(sid, f) + " (" + n + " bytes)");
        json(ex, 200, Map.of("ok", true));
    }

    private void fsRename(HttpExchange ex, String sid) throws IOException {
        JsonObject o = JsonParser.parseString(readBodyText(ex)).getAsJsonObject();
        Path from = safe(sid, o.get("from").getAsString()), to = safe(sid, o.get("to").getAsString());
        if (from.equals(root(sid))) throw new SecurityException("Wurzelordner kann nicht umbenannt werden");
        if (Files.exists(to)) { json(ex, 409, Map.of("error", "Ziel existiert bereits")); return; }
        Files.move(from, to);
        pl.info(sid, "Renamed via dashboard: " + rel(sid, from) + " -> " + rel(sid, to));
        json(ex, 200, Map.of("ok", true));
    }

    private void fsMkdir(HttpExchange ex, Map<String, String> q, String sid) throws IOException {
        Path d = safe(sid, q.get("path"));
        Files.createDirectories(d);
        json(ex, 200, Map.of("ok", true));
    }

    /** Deletes a single file or EMPTY folder; requires confirm == file name (UI asks the user). */
    private void fsDelete(HttpExchange ex, String sid) throws IOException {
        JsonObject o = JsonParser.parseString(readBodyText(ex)).getAsJsonObject();
        Path p = safe(sid, o.get("path").getAsString());
        if (p.equals(root(sid))) throw new SecurityException("Wurzelordner kann nicht gelöscht werden");
        if (!o.has("confirm") || !o.get("confirm").getAsString().equals(p.getFileName().toString())) { json(ex, 400, Map.of("error", "Bestätigung fehlt")); return; }
        Files.delete(p); // throws if non-empty directory
        pl.info(sid, "Deleted via dashboard: " + rel(sid, p));
        json(ex, 200, Map.of("ok", true));
    }

    private void logs(HttpExchange ex, Map<String, String> q, String sid) throws IOException {
        String name = q.getOrDefault("file", "latest.log");
        if (name.contains("/") || name.contains("\\") || name.contains("..")) throw new SecurityException("Pfad außerhalb des Server-Ordners");
        int limit = parseInt(q.get("limit"), 2000);
        int maxBytes = parseInt(q.get("maxBytes"), 4 * 1024 * 1024);
        LogQuery.Result r = LogQuery.query(safe(sid, "logs/" + name), q.getOrDefault("level", "all"), q.getOrDefault("q", ""), limit, maxBytes);
        json(ex, 200, Map.of("file", name, "truncated", r.truncated(), "lines", r.lines()));
    }

    private static int parseInt(String s, int fallback) {
        if (s == null || s.isBlank()) return fallback;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
    }

    // ---------------- helpers ----------------
    private void serveIndex(HttpExchange ex) throws IOException {
        try (InputStream in = pl.getResource("web/index.html")) {
            byte[] b = in == null ? "index.html missing".getBytes() : in.readAllBytes();
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; font-src 'self' data:");
            sendBytes(ex, 200, "text/html; charset=utf-8", b);
        }
    }

    private void serveWebAsset(HttpExchange ex, String path) throws IOException {
        if (path.contains("\\") || Arrays.asList(path.split("/")).contains("..")) {
            send(ex, 404, "text/plain; charset=utf-8", "Not found");
            return;
        }
        String contentType = switch (path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)) {
            case "js" -> "application/javascript; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "woff2" -> "font/woff2";
            case "png" -> "image/png";
            default -> "application/octet-stream";
        };
        try (InputStream in = pl.getResource("web/" + path)) {
            if (in == null) {
                send(ex, 404, "text/plain; charset=utf-8", "Not found");
                return;
            }
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
            sendBytes(ex, 200, contentType, in.readAllBytes());
        }
    }

    private void serveThirdPartyNotices(HttpExchange ex) throws IOException {
        try (InputStream in = pl.getResource("META-INF/THIRD-PARTY-NOTICES.txt")) {
            if (in == null) throw new IOException("Bundled third-party notices are missing");
            send(ex, 200, "text/plain; charset=utf-8", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private String readOr(Path f) throws IOException { return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : ""; }

    private String readBodyText(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = in.readNBytes((int) MAX_TEXT + 1);
            if (b.length > MAX_TEXT) throw new IOException("Zu groß (max 2 MB)");
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    private interface Extract { String get(JsonObject o); }
    private String body(HttpExchange ex, Extract e) throws IOException { return e.get(JsonParser.parseString(readBodyText(ex)).getAsJsonObject()); }

    private Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private void json(HttpExchange ex, int code, Object o) throws IOException { sendBytes(ex, code, "application/json; charset=utf-8", gson.toJson(o).getBytes(StandardCharsets.UTF_8)); }
    private void text(HttpExchange ex, String s) throws IOException { sendBytes(ex, 200, "text/plain; charset=utf-8", s.getBytes(StandardCharsets.UTF_8)); }
    private void send(HttpExchange ex, int code, String type, String s) throws IOException { sendBytes(ex, code, type, s.getBytes(StandardCharsets.UTF_8)); }
    private void sendBytes(HttpExchange ex, int code, String type, byte[] b) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
