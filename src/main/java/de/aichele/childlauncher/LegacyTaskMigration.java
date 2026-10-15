package de.aichele.childlauncher;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class LegacyTaskMigration {
    record Result(List<Map<String, Object>> schedules, List<Object> remainingTasks) {}

    private LegacyTaskMigration() {}

    static Result migrate(Object rawTasks, String selectedProfile, Set<String> usedScheduleIds) {
        List<?> source = rawTasks instanceof List<?> list ? list : List.of();
        Set<String> ids = new HashSet<>(usedScheduleIds);
        List<Map<String, Object>> schedules = new ArrayList<>();
        List<Object> remaining = new ArrayList<>();
        for (Object sourceTask : source) {
            List<TaskDef> parsed = TaskDef.load(List.of(sourceTask));
            if (parsed.isEmpty()) {
                remaining.add(sourceTask);
                continue;
            }
            TaskDef task = parsed.getFirst();
            ScheduleDef converted = convert(task, selectedProfile, ids);
            if (converted == null || schedules.size() >= ScheduleDef.MAX_SCHEDULES) {
                remaining.add(sourceTask);
            } else {
                schedules.add(converted.toConfig());
                ids.add(converted.id());
            }
        }
        return new Result(List.copyOf(schedules), List.copyOf(remaining));
    }

    private static ScheduleDef convert(TaskDef task, String selectedProfile, Set<String> ids) {
        if (!task.server.isEmpty() && !task.server.equals(selectedProfile)) return null;
        if (!task.action.equals("backup") && !task.action.equals("command")) return null;
        String[] fields = task.cron.trim().split("\\s+");
        if (fields.length != 5 || !fields[2].equals("*") || !fields[3].equals("*")) return null;
        int minute = number(fields[0], 0, 59);
        if (minute < 0) return null;

        ScheduleDef.Interval interval;
        int intervalValue;
        int timeValue;
        if (fields[1].equals("*") && fields[4].equals("*")) {
            interval = ScheduleDef.Interval.HOURLY;
            intervalValue = minute;
            timeValue = 0;
        } else if (fields[1].matches("\\d{1,2}") && fields[4].equals("*")) {
            int hour = number(fields[1], 0, 23);
            if (hour < 0) return null;
            interval = ScheduleDef.Interval.DAILY;
            intervalValue = hour;
            timeValue = minute;
        } else if (fields[1].matches("\\d{1,2}") && fields[4].matches("\\d")) {
            int hour = number(fields[1], 0, 23);
            int day = number(fields[4], 0, 7);
            if (hour < 0 || day < 0) return null;
            interval = ScheduleDef.Interval.WEEKLY;
            intervalValue = day == 7 ? 0 : day;
            timeValue = hour * 60 + minute;
        } else {
            return null;
        }

        String id = task.id.replaceAll("[^A-Za-z0-9_-]", "-");
        if (id.isBlank() || !Character.isLetterOrDigit(id.charAt(0))) id = "task-" + id;
        id = id.substring(0, Math.min(id.length(), 64));
        String baseId = id;
        for (int suffix = 2; ids.contains(id); suffix++) {
            String suffixText = "-" + suffix;
            id = baseId.substring(0, Math.min(baseId.length(), 64 - suffixText.length())) + suffixText;
        }
        String name = task.id.isBlank() ? id : task.id;
        if (name.length() > 80) name = name.substring(0, 80);
        List<ScheduleDef.Action> actions = List.of(new ScheduleDef.Action(
                "action-1", task.action, task.action.equals("command") ? task.command : ""));
        try {
            return new ScheduleDef(id, name, task.enabled, interval, intervalValue, timeValue,
                    task.timezone, 0, actions);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int number(String value, int min, int max) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= min && parsed <= max ? parsed : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

}
