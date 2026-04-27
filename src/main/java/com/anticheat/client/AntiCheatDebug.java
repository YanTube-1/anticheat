package com.anticheat.client;

/**
 * Clientseitiger Debug-Schalter (verbose Chat-Hinweise bei Diagnose-Befehlen).
 */
public final class AntiCheatDebug {

    private static volatile boolean enabled;

    private AntiCheatDebug() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }
}
