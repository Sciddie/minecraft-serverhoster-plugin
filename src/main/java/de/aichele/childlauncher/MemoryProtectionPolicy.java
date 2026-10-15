package de.aichele.childlauncher;

final class MemoryProtectionPolicy {
    enum Decision {
        NONE,
        STOP_CHILD,
        RELEASE_AFTER_STABLE_MEMORY,
        RELEASE_WITHOUT_MEMORY_GUARANTEE
    }

    private int criticalSamples;
    private int safeSamples;

    Decision sample(double usagePercent, double criticalPercent, double recoveryPercent,
                    int requiredCriticalSamples, int requiredRecoverySamples,
                    long recoveryBlockedSince, long now, long recoveryCooldownMillis,
                    boolean autoStopChild) {
        int requiredCritical = Math.max(1, requiredCriticalSamples);
        int requiredRecovery = Math.max(1, requiredRecoverySamples);
        long elapsedRecovery = recoveryBlockedSince <= 0 || now < recoveryBlockedSince
                ? 0 : now - recoveryBlockedSince;
        if (!Double.isFinite(usagePercent) || usagePercent < 0) {
            criticalSamples = 0;
            safeSamples = 0;
            return recoveryBlockedSince > 0 && elapsedRecovery >= recoveryCooldownMillis
                    ? Decision.RELEASE_WITHOUT_MEMORY_GUARANTEE : Decision.NONE;
        }
        if (recoveryBlockedSince > 0) {
            criticalSamples = 0;
            if (usagePercent <= recoveryPercent) safeSamples++;
            else safeSamples = 0;
            return elapsedRecovery >= recoveryCooldownMillis && safeSamples >= requiredRecovery
                    ? Decision.RELEASE_AFTER_STABLE_MEMORY : Decision.NONE;
        }
        safeSamples = 0;
        if (usagePercent >= criticalPercent) criticalSamples++;
        else criticalSamples = 0;
        if (criticalSamples < requiredCritical) return Decision.NONE;
        if (!autoStopChild) {
            criticalSamples = 0;
            return Decision.NONE;
        }
        return Decision.STOP_CHILD;
    }

    int criticalSamples() {
        return criticalSamples;
    }

    void resetCriticalSamples() {
        criticalSamples = 0;
    }
}
