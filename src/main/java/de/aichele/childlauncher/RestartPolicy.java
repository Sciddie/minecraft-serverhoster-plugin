package de.aichele.childlauncher;

import java.math.BigDecimal;

final class RestartPolicy {
    static final int DEFAULT_MAX_ATTEMPTS = 5;
    static final int MAX_ATTEMPTS_LIMIT = 100;

    private RestartPolicy() {}

    static int maxAttempts(Object raw) {
        if (!(raw instanceof Number number))
            throw new IllegalArgumentException("autoRestart.maxAttempts must be an integer");
        try {
            int value = new BigDecimal(number.toString()).intValueExact();
            if (value < 1 || value > MAX_ATTEMPTS_LIMIT)
                throw new IllegalArgumentException("autoRestart.maxAttempts must be between 1 and "
                        + MAX_ATTEMPTS_LIMIT);
            return value;
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("autoRestart.maxAttempts must be an integer", e);
        }
    }

    static boolean mayRetry(int scheduledAttempts, int maximumAttempts) {
        return scheduledAttempts < maximumAttempts;
    }

    // 0 meant "unlimited" in 1.x and 2.0.0 configs and is no longer a valid value.
    static boolean migrateLegacyUnlimited(Object raw) {
        if (!(raw instanceof Number number)) return false;
        try {
            return new BigDecimal(number.toString()).intValueExact() == 0;
        } catch (NumberFormatException | ArithmeticException e) {
            return false;
        }
    }
}
