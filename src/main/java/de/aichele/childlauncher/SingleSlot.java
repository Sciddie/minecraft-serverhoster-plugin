package de.aichele.childlauncher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One child at a time, unless allowParallel is explicitly on. */
final class SingleSlot {
    private SingleSlot() {}

    static boolean allowParallel(Object raw) {
        if (raw instanceof Boolean b) return b;
        if (raw == null) return false;
        return Boolean.parseBoolean(String.valueOf(raw).trim());
    }

    static boolean shouldAutoStart(boolean allowParallel, boolean autoStart, boolean focus) {
        return autoStart && (allowParallel || focus);
    }

    static List<String> idsToStop(boolean allowParallel, String starting, Map<String, Boolean> running) {
        if (allowParallel) return List.of();
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : running.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue()) && !e.getKey().equals(starting)) out.add(e.getKey());
        }
        return List.copyOf(out);
    }

    static boolean restartYields(boolean allowParallel, String id, Map<String, Boolean> running) {
        if (allowParallel) return false;
        for (Map.Entry<String, Boolean> e : running.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue()) && !e.getKey().equals(id)) return true;
        }
        return false;
    }
}
