package dev.aichele.serverhoster;

import com.google.gson.JsonObject;
import org.bukkit.configuration.file.FileConfiguration;

/** Applies one settings section without rewriting the other keys in config.yml. */
final class RuntimeSettings {
    private RuntimeSettings() {}

    static void apply(FileConfiguration config, String section, JsonObject request) {
        switch (section) {
            case "memory" -> applyMemory(config, request);
            case "dashboard" -> applyDashboard(config, request);
            case "https" -> applyHttps(config, request);
            case "restart" -> applyRestart(config, request);
            case "backup" -> applyBackup(config, request);
            default -> throw new IllegalArgumentException("Unbekannter Einstellungsabschnitt");
        }
    }

    private static void applyMemory(FileConfiguration config, JsonObject request) {
        String xms = requiredText(request, "xms").trim();
        String xmx = requiredText(request, "xmx").trim();
        if (!xms.matches("\\d+[MG]") || !xmx.matches("\\d+[MG]"))
            throw new IllegalArgumentException("RAM muss wie 2G oder 512M geschrieben sein.");
        config.set("memory.xms", xms);
        config.set("memory.xmx", xmx);
    }

    private static void applyDashboard(FileConfiguration config, JsonObject request) {
        int port = requiredInt(request, "dashboardPort");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Dashboard-Port ist ungültig");
        config.set("dashboard.port", port);
    }

    private static void applyHttps(FileConfiguration config, JsonObject request) {
        config.set("dashboard.https.enabled", requiredBoolean(request, "httpsEnabled"));
    }

    private static void applyRestart(FileConfiguration config, JsonObject request) {
        ServerHosterSettings current = ServerHosterSettings.from(config);
        ServerHosterSettings next = new ServerHosterSettings(
                requiredBoolean(request, "autoRestartEnabled"),
                requiredInt(request, "maxRestartAttempts"),
                requiredLong(request, "initialRestartDelaySeconds"),
                requiredLong(request, "maximumRestartDelaySeconds"),
                requiredLong(request, "restartResetAfterSeconds"),
                current.backupStorage(),
                current.backupProtocol(),
                current.backupHost(),
                current.backupPort(),
                current.backupDirectory());
        config.set("autoRestart.enabled", next.autoRestartEnabled());
        config.set("autoRestart.maxAttempts", next.maxRestartAttempts());
        config.set("autoRestart.initialDelaySeconds", next.initialRestartDelaySeconds());
        config.set("autoRestart.maxDelaySeconds", next.maximumRestartDelaySeconds());
        config.set("autoRestart.resetAfterSeconds", next.restartResetAfterSeconds());
    }

    private static void applyBackup(FileConfiguration config, JsonObject request) {
        ServerHosterSettings current = ServerHosterSettings.from(config);
        ServerHosterSettings next = new ServerHosterSettings(
                current.autoRestartEnabled(),
                current.maxRestartAttempts(),
                current.initialRestartDelaySeconds(),
                current.maximumRestartDelaySeconds(),
                current.restartResetAfterSeconds(),
                requiredText(request, "backupStorage"),
                requiredText(request, "backupProtocol"),
                requiredText(request, "backupHost"),
                requiredInt(request, "backupPort"),
                requiredText(request, "backupDirectory"));
        config.set("backup.storage", next.backupStorage());
        config.set("backup.remote.protocol", next.backupProtocol());
        config.set("backup.remote.host", next.backupHost());
        config.set("backup.remote.port", next.backupPort());
        config.set("backup.remote.directory", next.backupDirectory());
    }

    private static String requiredText(JsonObject request, String key) {
        if (!request.has(key) || !request.get(key).isJsonPrimitive() || !request.getAsJsonPrimitive(key).isString())
            throw new IllegalArgumentException(key + " muss eine Zeichenkette sein");
        return request.get(key).getAsString();
    }

    private static boolean requiredBoolean(JsonObject request, String key) {
        if (!request.has(key) || !request.get(key).isJsonPrimitive() || !request.getAsJsonPrimitive(key).isBoolean())
            throw new IllegalArgumentException(key + " muss ein Boolean sein");
        return request.get(key).getAsBoolean();
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
}
