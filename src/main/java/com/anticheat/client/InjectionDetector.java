package com.anticheat.client;

import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Erkennt Cheat-Injects (z.B. Doomsday) durch JVM-Klassenladungs- und Thread-Analyse.
 *
 * Logs landen in logs/latest.log mit Prefix [AntiCheat2].
 * Pre-Baseline: jede Sekunde ein Log-Eintrag mit aktuellem Klassen-Count und Stability-Delta.
 * Detection: alle 10 Sekunden ein Heartbeat-Log.
 */
public final class InjectionDetector {

    private static final Logger LOG = LogManager.getLogger("AntiCheat2");

    // ── Erkennungs-Schwellenwerte ───────────────────────────────────────────
    private static final int   CLASS_SPIKE_THRESHOLD  = 500;
    private static final int   CLASS_SPIKE_STRONG     = 1_000;
    private static final int   THREAD_SPIKE_THRESHOLD = 4;
    private static final long  ALERT_COOLDOWN_MS      = 60_000L;
    private static final long  WINDOW_MS              = 15_000L;

    // ── Baseline-Stabilitätsprüfung ─────────────────────────────────────────
    /**
     * Mindest-Wartezeit nach Hauptmenü-Erkennung bevor Baseline akzeptiert wird.
     * 10s reichen – zu diesem Zeitpunkt ist alles geladen.
     */
    private static final long  MIN_BASELINE_WAIT_MS   = 10_000L;
    /** Stabilitätsfenster: Klassen dürfen in diesem Zeitraum um max. STABLE_CLASS_DELTA schwanken. */
    private static final long  STABLE_WINDOW_MS       = 8_000L;
    /** Max. Klassen-Delta im Stabilitätsfenster. */
    private static final int   STABLE_CLASS_DELTA     = 80;
    private static final long  CHECK_INTERVAL_MS      = 1_000L;

    // ── Singleton ───────────────────────────────────────────────────────────
    private static final InjectionDetector INSTANCE = new InjectionDetector();
    public  static InjectionDetector get() { return INSTANCE; }

    // ── State ───────────────────────────────────────────────────────────────
    private volatile boolean baselineTaken    = false;
    private volatile int     baselineClasses  = -1;
    private volatile int     baselineThreads  = -1;

    private volatile boolean cheatDetected    = false;
    private volatile long    lastAlertTs      = 0L;
    private volatile String  lastAlertReason  = "–";
    private volatile int     lastDeltaClasses = 0;
    private volatile int     lastDeltaThreads = 0;

    private volatile long    startTime        = 0L;
    private          long    tickCount        = 0L;

    /**
     * Wird gesetzt sobald das Hauptmenü zum ersten Mal geöffnet wurde.
     * Erst dann ist Minecraft vollständig geladen – erst ab diesem Moment
     * macht eine Baseline Sinn.
     */
    private volatile boolean mainMenuReached  = false;

    /**
     * Adaptive Suppression: Erkennung pausiert bis der Klassen-Count nach
     * Welt-Beitritt wieder stabil ist – kein fester Timer.
     * suppressStartMs: Zeitpunkt des Welt-Beitritts.
     * SUPPRESS_MIN_MS: Mindestdauer (Schutz vor zu frühem Ablauf).
     * SUPPRESS_MAX_MS: Sicherheitsnetz (nie länger als X pausieren).
     */
    private volatile boolean suppressActive  = false;
    private volatile long    suppressStartMs = 0L;
    private static final long SUPPRESS_MIN_MS = 5_000L;
    private static final long SUPPRESS_MAX_MS = 60_000L;

    /**
     * Gleitendes Fenster: { timestamp_ms, loadedClasses }
     * Vor Baseline: Stabilitätsprüfung. Nach Baseline: Spike-Erkennung.
     * Nicht thread-safe → alle Zugriffe unter synchronized(window).
     */
    private final ArrayDeque<long[]> window = new ArrayDeque<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AntiCheat-Detector");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);

    private Consumer<String> alertCallback;
    private Consumer<String> clearCallback;

    private InjectionDetector() {}

    // ── Öffentliche API ─────────────────────────────────────────────────────

    public void setAlertCallback(Consumer<String> cb) { this.alertCallback = cb; }
    public void setClearCallback(Consumer<String> cb) { this.clearCallback = cb; }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        startTime = System.currentTimeMillis();
        LOG.info("=== AntiCheat2 gestartet. Warte auf Stabilisierung (min {}s) ===",
                MIN_BASELINE_WAIT_MS / 1000);
        scheduler.scheduleAtFixedRate(this::tick, 2_000L, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    public void resetBaseline() {
        baselineTaken    = false;
        cheatDetected    = false;
        lastAlertReason  = "–";
        lastDeltaClasses = 0;
        lastDeltaThreads = 0;
        lastAlertTs      = 0L;
        tickCount        = 0L;
        synchronized (window) { window.clear(); }
        startTime = System.currentTimeMillis();
        // mainMenuReached bleibt true – wir sind ja schon im Hauptmenü/Spiel
        LOG.info("=== Baseline RESET. Warte erneut auf Stabilisierung ===");
    }

    /**
     * Wird aufgerufen wenn FMLLoadCompleteEvent feuert
     * (= "Forge Mod Loader has successfully loaded X mods").
     * Ab jetzt ist Minecraft vollständig initialisiert; Baseline in ~10s.
     */
    /**
     * Adaptive Suppression starten.
     * Kein fester Timer – Erkennung läuft wieder sobald Klassenladerate stabil ist.
     * Wird aufgerufen wenn der lokale Spieler einer Welt beitritt.
     */
    public void suppressForWorldLoad(long ignoredDuration) {
        suppressActive  = true;
        suppressStartMs = System.currentTimeMillis();
        synchronized (window) { window.clear(); }
        LOG.info("=== Adaptive Suppression gestartet (Welt-Beitritt) – Fenster geleert ===");
    }

    public void notifyModsLoaded() {
        if (mainMenuReached) return;
        mainMenuReached = true;
        startTime = System.currentTimeMillis();
        synchronized (window) { window.clear(); }
        LOG.info("=== Mods vollständig geladen (FMLLoadComplete). Baseline in ~{}s (wenn stabil). ===",
                MIN_BASELINE_WAIT_MS / 1000);
    }

    public StatusSnapshot getStatus() {
        return new StatusSnapshot(
                cheatDetected, baselineTaken,
                baselineClasses, baselineThreads,
                currentClasses(), currentThreads(),
                lastAlertReason, lastDeltaClasses, lastDeltaThreads);
    }

    public boolean isBaselineTaken() { return baselineTaken; }

    // ── Kern-Loop ────────────────────────────────────────────────────────────

    private void tick() {
        tickCount++;
        long now        = System.currentTimeMillis();
        int  curClasses = currentClasses();
        int  curThreads = currentThreads();
        long elapsedSec = (now - startTime) / 1000;

        synchronized (window) {
            window.add(new long[]{ now, curClasses });
            while (!window.isEmpty() && window.peek()[0] < now - WINDOW_MS) {
                window.poll();
            }
        }

        if (!baselineTaken) {
            if (!mainMenuReached) {
                // Noch nicht bereit – nur alle 10s loggen
                if (tickCount % 10 == 0) {
                    LOG.info("[PRE-BL t+{}s] Klassen={} Threads={} | Warte auf FMLLoadComplete...",
                            elapsedSec, curClasses, curThreads);
                }
            } else {
                long timeLeft = (MIN_BASELINE_WAIT_MS - (now - startTime)) / 1000;
                if (timeLeft > 0) {
                    LOG.info("[PRE-BL t+{}s] Klassen={} Threads={} | Mindestzeit: noch {}s",
                            elapsedSec, curClasses, curThreads, timeLeft);
                } else {
                    int[] minMax = windowMinMax(now, STABLE_WINDOW_MS);
                    int stableDelta = (minMax[1] == Integer.MIN_VALUE) ? -1 : minMax[1] - minMax[0];
                    LOG.info("[PRE-BL t+{}s] Klassen={} Threads={} | StabilDelta({}s)={} (Ziel<{})",
                            elapsedSec, curClasses, curThreads,
                            STABLE_WINDOW_MS / 1000, stableDelta, STABLE_CLASS_DELTA);
                }
            }
            tryTakeBaseline(now, curClasses, curThreads);
        } else {
            // Im Erkennungsmodus: alle 10s ein Heartbeat
            if (tickCount % 10 == 0) {
                int deltaBaseline = curClasses - baselineClasses;
                int deltaThreads  = curThreads  - baselineThreads;
                int[] minMax      = windowMinMax(now, WINDOW_MS);
                int deltaWindow   = (minMax[1] == Integer.MIN_VALUE) ? 0 : minMax[1] - minMax[0];
                LOG.info("[DETECT t+{}s] Klassen={} (+{}vsBL, +{}win15s) Threads={} (+{}vsBL)",
                        elapsedSec, curClasses, deltaBaseline, deltaWindow,
                        curThreads, deltaThreads);
            }
            detectInject(now, curClasses, curThreads);
        }
    }

    // ── Baseline: warten bis Klassen-Count stabil ist ───────────────────────

    private void tryTakeBaseline(long now, int curClasses, int curThreads) {
        // Warte bis FMLLoadComplete (= alle Mods geladen)
        if (!mainMenuReached) return;

        if (now - startTime < MIN_BASELINE_WAIT_MS) return;

        int stableMin = Integer.MAX_VALUE, stableMax = Integer.MIN_VALUE;
        int stableSamples = 0;
        synchronized (window) {
            for (long[] s : window) {
                if (s[0] >= now - STABLE_WINDOW_MS) {
                    int c = (int) s[1];
                    if (c < stableMin) stableMin = c;
                    if (c > stableMax) stableMax = c;
                    stableSamples++;
                }
            }
        }

        int minSamples  = (int) (STABLE_WINDOW_MS / CHECK_INTERVAL_MS) - 1;
        int stableDelta = (stableMax == Integer.MIN_VALUE) ? Integer.MAX_VALUE
                                                           : stableMax - stableMin;

        if (stableSamples < minSamples) {
            LOG.info("[PRE-BL] Zu wenige Samples im Stabilitätsfenster: {} (brauche {})",
                    stableSamples, minSamples);
            return;
        }

        if (stableDelta >= STABLE_CLASS_DELTA) {
            LOG.info("[PRE-BL] Noch instabil: Delta={} Klassen in {}s (Ziel<{}). Warte...",
                    stableDelta, STABLE_WINDOW_MS / 1000, STABLE_CLASS_DELTA);
            return;
        }

        // Stabil → Baseline nehmen
        baselineClasses = curClasses;
        baselineThreads = curThreads;
        baselineTaken   = true;
        synchronized (window) {
            window.clear();
            window.add(new long[]{ now, curClasses });
        }

        LOG.info("=== BASELINE GESETZT: {} Klassen, {} Threads (StabilDelta={}/<{}, {}s nach Start) ===",
                baselineClasses, baselineThreads,
                stableDelta, STABLE_CLASS_DELTA,
                (now - startTime) / 1000);
    }

    // ── Inject-Erkennung ─────────────────────────────────────────────────────

    private void detectInject(long now, int curClasses, int curThreads) {
        // Adaptive Suppression: pausiert bis Klassenladerate wieder stabil ist
        if (suppressActive) {
            long elapsed = now - suppressStartMs;

            // Sicherheitsnetz: nach MAX immer beenden (verhindert endlose Blindheit)
            if (elapsed >= SUPPRESS_MAX_MS) {
                suppressActive = false;
                synchronized (window) { window.clear(); window.add(new long[]{now, curClasses}); }
                LOG.warn("=== Suppression nach {}s MAX beendet – Erkennung aktiv ===", SUPPRESS_MAX_MS / 1000);
            } else if (elapsed >= SUPPRESS_MIN_MS) {
                // Prüfen ob Welt-Ladevorgang stabil ist (= Klassenrate hat sich beruhigt)
                int[] minMax = windowMinMax(now, STABLE_WINDOW_MS);
                int delta = (minMax[1] == Integer.MIN_VALUE) ? Integer.MAX_VALUE : minMax[1] - minMax[0];
                if (delta < STABLE_CLASS_DELTA) {
                    // Stabil → Suppression beenden, Fenster reset für sauberen Start
                    suppressActive = false;
                    synchronized (window) { window.clear(); window.add(new long[]{now, curClasses}); }
                    LOG.info("=== Suppression beendet nach {}s (Stabilisierung erkannt, Delta={}) ===",
                            elapsed / 1000, delta);
                } else {
                    if (tickCount % 5 == 0) {
                        LOG.info("[SUPPRESS t+{}s] Noch instabil: Delta={} Klassen/{}s (Ziel<{})",
                                elapsed / 1000, delta, STABLE_WINDOW_MS / 1000, STABLE_CLASS_DELTA);
                    }
                    return;
                }
            } else {
                return; // Mindestzeit noch nicht abgelaufen
            }
        }
        int[] minMax      = windowMinMax(now, WINDOW_MS);
        int   deltaWindow = (minMax[1] == Integer.MIN_VALUE) ? 0 : minMax[1] - minMax[0];
        int   deltaBaseline = curClasses - baselineClasses;
        int   deltaThreads  = curThreads  - baselineThreads;

        boolean rule1 = deltaWindow   >= CLASS_SPIKE_THRESHOLD
                     && deltaThreads  >= THREAD_SPIKE_THRESHOLD;
        boolean rule2 = deltaWindow   >= CLASS_SPIKE_STRONG;
        boolean rule3 = deltaBaseline >= CLASS_SPIKE_STRONG
                     && deltaThreads  >= THREAD_SPIKE_THRESHOLD;

        if ((rule1 || rule2 || rule3) && !cheatDetected
                && now - lastAlertTs > ALERT_COOLDOWN_MS) {
            cheatDetected    = true;
            lastAlertTs      = now;
            lastDeltaClasses = deltaWindow;
            lastDeltaThreads = deltaThreads;
            lastAlertReason  = buildReason(deltaWindow, deltaBaseline, deltaThreads);
            LOG.warn("!!! CHEAT ERKANNT: {} !!!", lastAlertReason);
            fireAlert(lastAlertReason);
        }
    }

    // ── Hilfsmethoden ───────────────────────────────────────────────────────

    /** Gibt {min, max} der Klassen-Werte im übergebenen Zeitfenster zurück. */
    private int[] windowMinMax(long now, long windowMs) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        synchronized (window) {
            for (long[] s : window) {
                if (s[0] >= now - windowMs) {
                    int c = (int) s[1];
                    if (c < min) min = c;
                    if (c > max) max = c;
                }
            }
        }
        return new int[]{ min, max };
    }

    private String buildReason(int dWindow, int dBaseline, int dThreads) {
        StringBuilder sb = new StringBuilder();
        sb.append("+").append(dWindow).append(" Klassen/15s");
        if (dBaseline > dWindow) sb.append(", +").append(dBaseline).append(" vs Baseline");
        sb.append(", +").append(dThreads).append(" Threads");
        return sb.toString();
    }

    private void fireAlert(String reason) {
        if (alertCallback != null) {
            try { alertCallback.accept(reason); } catch (Throwable ignored) {}
        }
    }

    private static int currentClasses() {
        return (int) ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
    }

    private static int currentThreads() {
        return Thread.activeCount();
    }

    // ── Datenklasse ─────────────────────────────────────────────────────────

    public static final class StatusSnapshot {
        public final boolean cheatDetected;
        public final boolean baselineTaken;
        public final int     baselineClasses;
        public final int     baselineThreads;
        public final int     currentClasses;
        public final int     currentThreads;
        public final String  lastAlertReason;
        public final int     lastDeltaClasses;
        public final int     lastDeltaThreads;

        StatusSnapshot(boolean cheatDetected, boolean baselineTaken,
                       int baselineClasses, int baselineThreads,
                       int currentClasses,  int currentThreads,
                       String lastAlertReason,
                       int lastDeltaClasses, int lastDeltaThreads) {
            this.cheatDetected    = cheatDetected;
            this.baselineTaken    = baselineTaken;
            this.baselineClasses  = baselineClasses;
            this.baselineThreads  = baselineThreads;
            this.currentClasses   = currentClasses;
            this.currentThreads   = currentThreads;
            this.lastAlertReason  = lastAlertReason;
            this.lastDeltaClasses = lastDeltaClasses;
            this.lastDeltaThreads = lastDeltaThreads;
        }
    }
}
