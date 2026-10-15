package de.aichele.childlauncher;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;

record ServerHosterSettings(
        boolean autoRestartEnabled,
        int maxRestartAttempts,
        long initialRestartDelaySeconds,
        long maximumRestartDelaySeconds,
        long restartResetAfterSeconds,
        String backupStorage,
        String backupProtocol,
        String backupHost,
        int backupPort,
        String backupDirectory
) {
    ServerHosterSettings {
        maxRestartAttempts = RestartPolicy.maxAttempts(maxRestartAttempts);
        if (initialRestartDelaySeconds < 1 || initialRestartDelaySeconds > 3600)
            throw new IllegalArgumentException("Initial restart delay must be between 1 and 3600 seconds");
        if (maximumRestartDelaySeconds < initialRestartDelaySeconds || maximumRestartDelaySeconds > 86_400)
            throw new IllegalArgumentException("Maximum restart delay must be between the initial delay and 86400 seconds");
        if (restartResetAfterSeconds < 60 || restartResetAfterSeconds > 604_800)
            throw new IllegalArgumentException("Restart counter reset must be between 60 and 604800 seconds");
        backupStorage = enumValue(backupStorage, "backup.storage", "local", "remote");
        backupProtocol = enumValue(backupProtocol, "backup.remote.protocol", "AUTO", "FTP", "FTPS");
        backupHost = backupHost == null ? "" : backupHost.trim();
        backupDirectory = backupDirectory == null ? "" : backupDirectory.trim();
        if (backupPort < 1 || backupPort > 65_535)
            throw new IllegalArgumentException("FTP port must be between 1 and 65535");
        if (backupHost.length() > 253 || hasUnsafeHostCharacter(backupHost))
            throw new IllegalArgumentException("FTP host is invalid");
        if (backupDirectory.length() > 512 || containsControlCharacter(backupDirectory))
            throw new IllegalArgumentException("FTP directory is invalid");
        for (String segment : backupDirectory.replace('\\', '/').split("/"))
            if ("..".equals(segment)) throw new IllegalArgumentException("FTP directory cannot contain '..'");
        if ("remote".equals(backupStorage) && backupHost.isBlank())
            throw new IllegalArgumentException("FTP host is required for remote backup storage");
    }

    static ServerHosterSettings from(FileConfiguration config) {
        return new ServerHosterSettings(
                config.getBoolean("autoRestart.enabled", true),
                config.getInt("autoRestart.maxAttempts", RestartPolicy.DEFAULT_MAX_ATTEMPTS),
                config.getLong("autoRestart.initialDelaySeconds", 5),
                config.getLong("autoRestart.maxDelaySeconds", 300),
                config.getLong("autoRestart.resetAfterSeconds", 600),
                config.getString("backup.storage", "local"),
                config.getString("backup.remote.protocol", "AUTO"),
                config.getString("backup.remote.host", ""),
                config.getInt("backup.remote.port", 21),
                config.getString("backup.remote.directory", "ServerHoster")
        );
    }

    Map<String, Object> view() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("autoRestartEnabled", autoRestartEnabled);
        result.put("maxRestartAttempts", maxRestartAttempts);
        result.put("initialRestartDelaySeconds", initialRestartDelaySeconds);
        result.put("maximumRestartDelaySeconds", maximumRestartDelaySeconds);
        result.put("restartResetAfterSeconds", restartResetAfterSeconds);
        result.put("backupStorage", backupStorage);
        result.put("backupProtocol", backupProtocol);
        result.put("backupHost", backupHost);
        result.put("backupPort", backupPort);
        result.put("backupDirectory", backupDirectory);
        return result;
    }

    void apply(FileConfiguration config) {
        config.set("autoRestart.enabled", autoRestartEnabled);
        config.set("autoRestart.maxAttempts", maxRestartAttempts);
        config.set("autoRestart.initialDelaySeconds", initialRestartDelaySeconds);
        config.set("autoRestart.maxDelaySeconds", maximumRestartDelaySeconds);
        config.set("autoRestart.resetAfterSeconds", restartResetAfterSeconds);
        config.set("backup.storage", backupStorage);
        config.set("backup.remote.protocol", backupProtocol);
        config.set("backup.remote.host", backupHost);
        config.set("backup.remote.port", backupPort);
        config.set("backup.remote.directory", backupDirectory);
    }

    private static String enumValue(String value, String key, String... accepted) {
        if (value == null) throw new IllegalArgumentException(key + " is required");
        String normalized = value.trim();
        for (String candidate : accepted)
            if (candidate.equalsIgnoreCase(normalized)) return candidate;
        throw new IllegalArgumentException("Invalid value for " + key);
    }

    private static boolean hasUnsafeHostCharacter(String host) {
        return host.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)
                || c == '/' || c == '\\' || c == '@' || c == '?' || c == '#');
    }

    private static boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }
}
