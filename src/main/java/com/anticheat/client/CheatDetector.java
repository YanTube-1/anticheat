package com.anticheat.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.core.LogEvent;

/**
 * Heuristiken: STATS_SERVICE, Readed/0000, Ziffern-Spam, DoomsDay-Strings ({@code doomsdayargs}, {@code net.java.*}), Laufzeit-Probe.
 */
public final class CheatDetector {

    /** Nach vielen Tests: aggressiver Schwellwert für schnelle Live-Meldung. */
    private static final int MIN_DIGIT_STREAK = 16;
    private static final long COOLDOWN_MS = 8_000L;

    private static final Pattern SPAM_CLOCK = Pattern.compile("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+[1-8]\\s*$");
    private static final Pattern DIGIT_ONLY = Pattern.compile("^[1-8]$");
    /** ANSI-Farbcodes (Konsole / Prism). */
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]");

    private static Path outFile;
    private static long lastAlertMs;
    private static int digitStreak;
    /** Optional (nur Client-Mod): z. B. Chat-Meldung; darf synchron sein, Hook plant selbst den MC-Thread. */
    private static Runnable clientAlertHook;

    private CheatDetector() {
    }

    public static void setOutputLog(Path file) {
        outFile = file;
    }

    public static void setClientAlertHook(Runnable hook) {
        clientAlertHook = hook;
    }

    /**
     * Gleiche Meldung wie bei Log-Erkennung (Datei + Chat-Hook), mit Cooldown – für Mod-Liste / Class.forName.
     */
    public static void triggerRuntimeCheckAlert() {
        synchronized (CheatDetector.class) {
            if (outFile == null) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastAlertMs < COOLDOWN_MS) {
                return;
            }
            alert(now);
        }
    }

    /**
     * Einmaliger Selbsttest (Befehl): schreibt markierte Zeile ins AntiCheat-Log, ohne Cheat-Alarm-Hook.
     *
     * @return {@code null} bei Erfolg, sonst Fehlertext
     */
    public static String performSelfTestLogOnly() {
        synchronized (CheatDetector.class) {
            if (outFile == null) {
                return "Log-Pfad nicht gesetzt";
            }
            try {
                Path parent = outFile.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
                String line = "[" + ts + "] [SELFTEST] Befehl /anti debug test — Mod reagiert, Log schreibbar\n";
                Files.write(
                        outFile,
                        line.getBytes(StandardCharsets.UTF_8),
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                return e.getMessage() != null ? e.getMessage() : "IOException";
            }
            return null;
        }
    }

    public static void inspect(LogEvent event) {
        if (outFile == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String line = buildLogLineForPatterns(event);
        inspectRawLine(line, now);
        String alt = formatMessage(event);
        if (!alt.isEmpty() && !alt.equals(line)) {
            inspectRawLine(alt, now);
        }
    }

    /** Logger-Name + Meldung, damit „[FML]“-Kontext mit erfasst wird. */
    private static String buildLogLineForPatterns(LogEvent event) {
        StringBuilder sb = new StringBuilder(128);
        if (event.getLoggerName() != null && !event.getLoggerName().isEmpty()) {
            sb.append(event.getLoggerName());
        }
        Message m = event.getMessage();
        if (m != null) {
            String fmt = m.getFormattedMessage();
            if (fmt != null && !fmt.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(fmt);
            }
        }
        return sb.toString();
    }

    public static synchronized void inspectRawLine(String line, long now) {
        if (outFile == null) {
            return;
        }
        String msg = line == null ? "" : line.replace("\r", "");
        msg = ANSI_ESCAPE.matcher(msg).replaceAll("").trim();

        if (!msg.isEmpty()) {
            if (msg.contains("[STATS_SERVICE]") && msg.contains("Report crash")) {
                if (msg.contains("Not real") || msg.contains("Throwable not set")) {
                    alert(now);
                    return;
                }
            }
            if (isReadedOrZeroMarker(msg)) {
                alert(now);
                return;
            }
            if (isDoomsdayStringSignature(msg)) {
                alert(now);
                return;
            }
            if (isPrismFmlInjectSignature(msg)) {
                alert(now);
                return;
            }
        }

        if (msg.isEmpty()) {
            return;
        }

        if (isDigitSpamLine(msg)) {
            digitStreak++;
            if (digitStreak >= MIN_DIGIT_STREAK && now - lastAlertMs >= COOLDOWN_MS) {
                alert(now);
            }
        } else {
            if (!preservesDigitStreak(msg)) {
                digitStreak = 0;
            }
        }
    }

    private static boolean isReadedOrZeroMarker(String msg) {
        String t = msg.trim();
        if ("Readed".equalsIgnoreCase(t)) {
            return true;
        }
        if ("0000".equals(t)) {
            return true;
        }
        return false;
    }

    /** Aus Dekompilat 8tjpjeap: JVM-Args / Logs / Stacktraces. */
    private static boolean isDoomsdayStringSignature(String msg) {
        String lower = msg.toLowerCase(Locale.ROOT);
        if (lower.contains("doomsdayargs")) {
            return true;
        }
        if (lower.contains("--doomsday")) {
            return true;
        }
        if (msg.contains("net.java.") || msg.contains("net/java/")) {
            return true;
        }
        if (lower.contains("\"id\":\"dd\"") || lower.contains("'id':'dd'")) {
            return true;
        }
        if (lower.contains("\"modid\":\"dd\"") || lower.contains("'modid':'dd'")) {
            return true;
        }
        return false;
    }

    /**
     * Nach Inject oft im Log: FML warnt vor {@code System.exit} in Prism-EntryPoint.
     * Kann auch bei normalem Beenden auftauchen — bei dir typisch direkt nach Cheat-Aktivität.
     */
    private static boolean isPrismFmlInjectSignature(String msg) {
        String lower = msg.toLowerCase(Locale.ROOT);
        if (lower.contains("prismlauncher") && lower.contains("entrypoint")) {
            return true;
        }
        if (lower.contains("mod has direct reference")
                && lower.contains("system.exit")
                && lower.contains("not allowed")) {
            return true;
        }
        return false;
    }

    /**
     * Nur „Dekoration“ zwischen Ziffern-Spam (z. B. {@code =====}), sonst würde der Streak vor FML-Zeilen auf 0 springen.
     */
    private static boolean preservesDigitStreak(String msg) {
        String t = msg.trim();
        if (t.isEmpty()) {
            return true;
        }
        if (t.matches("^[=\\s\\-]+$")) {
            return true;
        }
        String lower = msg.toLowerCase(Locale.ROOT);
        if (lower.contains("[fml]") && (msg.contains("====") || lower.contains("system.exit"))) {
            return true;
        }
        return false;
    }

    private static boolean isDigitSpamLine(String msg) {
        String t = msg.trim();
        if (SPAM_CLOCK.matcher(t).matches()) {
            return true;
        }
        if (DIGIT_ONLY.matcher(t).matches()) {
            return true;
        }
        return mcStyleLineEndsWithSingleDigit(t);
    }

    private static boolean mcStyleLineEndsWithSingleDigit(String t) {
        if (!t.startsWith("[")) {
            return false;
        }
        int colon = t.lastIndexOf(':');
        if (colon < 0 || colon >= t.length() - 1) {
            return false;
        }
        String tail = t.substring(colon + 1).trim();
        tail = ANSI_ESCAPE.matcher(tail).replaceAll("").trim();
        return DIGIT_ONLY.matcher(tail).matches();
    }

    private static String formatMessage(LogEvent event) {
        Message m = event.getMessage();
        if (m != null) {
            String fmt = m.getFormattedMessage();
            if (fmt != null && !fmt.isEmpty()) {
                return fmt;
            }
            String format = m.getFormat();
            if (format != null && !format.isEmpty()) {
                return format;
            }
        }
        if (event.getThrown() != null) {
            String tm = event.getThrown().getMessage();
            if (tm != null) {
                return tm;
            }
        }
        return "";
    }

    private static void alert(long now) {
        digitStreak = 0;
        lastAlertMs = now;
        try {
            Path parent = outFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(
                    outFile,
                    "cheat erkannt\n".getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
        Runnable hook = clientAlertHook;
        if (hook != null) {
            try {
                hook.run();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Zusatzzeilen in dieselbe Client-Logdatei (z. B. Screenshot-Pfad). */
    public static void appendLogLine(String line) {
        if (outFile == null || line == null || line.isEmpty()) {
            return;
        }
        try {
            Path parent = outFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(
                    outFile,
                    (line + "\n").getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }
}
