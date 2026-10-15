package de.aichele.childlauncher;

import org.bukkit.configuration.ConfigurationSection;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

record ScheduleDef(
        String id,
        String name,
        boolean enabled,
        Interval interval,
        int intervalValue,
        int timeValue,
        String timezone,
        long lastRun,
        List<Action> actions
) {
    static final int MAX_SCHEDULES = 100;
    static final int MAX_ACTIONS = 50;
    private static final String ID_PATTERN = "[A-Za-z0-9_-]{1,64}";

    enum Interval {
        HOURLY,
        DAILY,
        WEEKLY
    }

    record Action(String id, String type, String command) {
        Action {
            if (id == null || !id.matches(ID_PATTERN)) throw new IllegalArgumentException("Ungültige Aktions-ID");
            if (!"backup".equals(type) && !"command".equals(type))
                throw new IllegalArgumentException("Nicht unterstützter Aktionstyp: " + type);
            command = command == null ? "" : command.trim();
            if ("command".equals(type) && (command.isEmpty() || command.length() > 512
                    || command.chars().anyMatch(Character::isISOControl)))
                throw new IllegalArgumentException("Befehl muss 1 bis 512 druckbare Zeichen enthalten");
            if ("backup".equals(type) && !command.isEmpty())
                throw new IllegalArgumentException("Backup-Aktionen akzeptieren keinen Befehl");
        }

        Map<String, Object> toConfig() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("type", type);
            result.put("command", command);
            return result;
        }
    }

    ScheduleDef {
        if (id == null || !id.matches(ID_PATTERN)) throw new IllegalArgumentException("Ungültige Schedule-ID");
        name = name == null ? "" : name.trim();
        if (name.isEmpty() || name.length() > 80) throw new IllegalArgumentException("Name muss zwischen 1 und 80 Zeichen lang sein");
        if (interval == null) throw new IllegalArgumentException("Ungültiges Intervall");
        if (lastRun < 0) throw new IllegalArgumentException("Ungültiger letzter Lauf");
        if (timezone == null || timezone.isBlank()) timezone = ZoneId.systemDefault().getId();
        try {
            timezone = ZoneId.of(timezone).getId();
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Ungültige Zeitzone: " + timezone, e);
        }
        validateInterval(interval, intervalValue, timeValue);
        actions = List.copyOf(actions == null ? List.of() : actions);
        if (actions.isEmpty() || actions.size() > MAX_ACTIONS)
            throw new IllegalArgumentException("Ein Zeitplan benötigt 1 bis " + MAX_ACTIONS + " Aktionen");
        if (actions.stream().map(Action::id).distinct().count() != actions.size())
            throw new IllegalArgumentException("Aktions-IDs müssen eindeutig sein");
    }

    String cron() {
        return switch (interval) {
            case HOURLY -> intervalValue + " * * * *";
            case DAILY -> timeValue + " " + intervalValue + " * * *";
            case WEEKLY -> (timeValue % 60) + " " + (timeValue / 60) + " * * " + intervalValue;
        };
    }

    ScheduleDef withEnabled(boolean value) {
        return new ScheduleDef(id, name, value, interval, intervalValue, timeValue, timezone, lastRun, actions);
    }

    ScheduleDef withLastRun(long value) {
        return new ScheduleDef(id, name, enabled, interval, intervalValue, timeValue, timezone, value, actions);
    }

    Map<String, Object> toConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("name", name);
        result.put("enabled", enabled);
        result.put("interval", interval.name());
        result.put("intervalValue", intervalValue);
        result.put("timeValue", timeValue);
        result.put("timezone", timezone);
        result.put("lastRun", lastRun);
        result.put("actions", actions.stream().map(Action::toConfig).toList());
        return result;
    }

    Map<String, Object> toJson() {
        Map<String, Object> result = toConfig();
        result.put("cron", cron());
        return result;
    }

    static List<ScheduleDef> load(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("schedules muss eine YAML-Liste sein");
        if (list.size() > MAX_SCHEDULES) throw new IllegalArgumentException("Zu viele Zeitpläne");
        List<ScheduleDef> schedules = new ArrayList<>();
        for (Object item : list) {
            Map<?, ?> map = asMap(item);
            if (map == null) throw new IllegalArgumentException("Ungültiger Zeitplan-Eintrag");
            Object rawActions = map.get("actions");
            if (!(rawActions instanceof List<?> actionList)) throw new IllegalArgumentException("Aktionen fehlen");
            if (actionList.size() > MAX_ACTIONS) throw new IllegalArgumentException("Zu viele Aktionen");
            List<Action> actions = new ArrayList<>();
            for (Object actionItem : actionList) {
                Map<?, ?> action = asMap(actionItem);
                if (action == null) throw new IllegalArgumentException("Ungültige Aktion");
                actions.add(new Action(string(action, "id", ""), string(action, "type", ""), string(action, "command", "")));
            }
            schedules.add(new ScheduleDef(
                    string(map, "id", ""),
                    string(map, "name", ""),
                    bool(map, "enabled", false),
                    enumValue(string(map, "interval", ""), Interval.class),
                    integer(map, "intervalValue"),
                    integer(map, "timeValue"),
                    string(map, "timezone", ZoneId.systemDefault().getId()),
                    optionalLong(map, "lastRun", 0),
                    actions
            ));
        }
        if (schedules.stream().map(ScheduleDef::id).distinct().count() != schedules.size())
            throw new IllegalArgumentException("Schedule-IDs müssen eindeutig sein");
        return List.copyOf(schedules);
    }

    private static void validateInterval(Interval interval, int intervalValue, int timeValue) {
        boolean valid = switch (interval) {
            case HOURLY -> intervalValue >= 0 && intervalValue <= 59 && timeValue == 0;
            case DAILY -> intervalValue >= 0 && intervalValue <= 23 && timeValue >= 0 && timeValue <= 59;
            case WEEKLY -> intervalValue >= 0 && intervalValue <= 6 && timeValue >= 0 && timeValue <= 1439;
        };
        if (!valid) throw new IllegalArgumentException("Ungültige Intervallwerte für " + interval);
    }

    private static Map<?, ?> asMap(Object value) {
        if (value instanceof Map<?, ?> map) return map;
        if (value instanceof ConfigurationSection section) return section.getValues(false);
        return null;
    }

    private static String string(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean bool(Map<?, ?> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value instanceof Boolean result) return result;
        if (value == null) return fallback;
        if ("true".equalsIgnoreCase(String.valueOf(value))) return true;
        if ("false".equalsIgnoreCase(String.valueOf(value))) return false;
        throw new IllegalArgumentException("Ungültiger Boolean-Wert: " + key);
    }

    private static int integer(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (numeric != Math.rint(numeric) || numeric < Integer.MIN_VALUE || numeric > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Ungültige Zahl: " + key);
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Ungültige Zahl: " + key, e);
            }
        }
        throw new IllegalArgumentException("Zahl fehlt: " + key);
    }

    private static long optionalLong(Map<?, ?> map, String key, long fallback) {
        Object value = map.get(key);
        if (value == null) return fallback;
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).longValueExact();
            } catch (ArithmeticException | NumberFormatException e) {
                throw new IllegalArgumentException("Ungültige Zahl: " + key, e);
            }
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Ungültige Zahl: " + key, e);
            }
        }
        throw new IllegalArgumentException("Ungültige Zahl: " + key);
    }

    private static <T extends Enum<T>> T enumValue(String value, Class<T> type) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Ungültiger Wert: " + value, e);
        }
    }
}
