package com.anticheat.client;

import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Erkennt Cheat-Injects durch JVM-Klassenladungs- und Thread-Analyse.
 * Silent-Version: kein Logging, komplett unsichtbar.
 */
public final class InjectionDetector {

    // ── Erkennungs-Schwellenwerte ───────────────────────────────────────────
    private static final int   CLASS_SPIKE_THRESHOLD  = 500;
    private static final int   CLASS_SPIKE_STRONG     = 1_000;
    private static final int   THREAD_SPIKE_THRESHOLD = 4;
    private static final long  ALERT_COOLDOWN_MS      = 60_000L;
    private static final long  WINDOW_MS              = 15_000L;

    private static final long  MIN_BASELINE_WAIT_MS   = 10_000L;
    private static final long  STABLE_WINDOW_MS       = 8_000L;
    private static final int   STABLE_CLASS_DELTA     = 80;
    private static final long  CHECK_INTERVAL_MS      = 1_000L;

    private static final InjectionDetector INSTANCE = new InjectionDetector();
    public  static InjectionDetector get() { return INSTANCE; }

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
    private volatile boolean mainMenuReached  = false;
    private volatile boolean detectionEnabled = false;

    private volatile boolean suppressActive  = false;
    private volatile long    suppressStartMs = 0L;
    private static final long SUPPRESS_MIN_MS = 5_000L;
    private static final long SUPPRESS_MAX_MS = 60_000L;

    private final ArrayDeque<long[]> window = new ArrayDeque<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AntiCheat-Detector");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);

    private Consumer<String> alertCallback;

    private InjectionDetector() {}

    public void setAlertCallback(Consumer<String> cb) { this.alertCallback = cb; }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        startTime = System.currentTimeMillis();
        scheduler.scheduleAtFixedRate(this::tick, 2_000L, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public void stop() { scheduler.shutdownNow(); }

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
    }

    public void onMultiplayerJoin() {
        detectionEnabled = true;
        suppressActive   = true;
        suppressStartMs  = System.currentTimeMillis();
        cheatDetected    = false;
        lastAlertTs      = 0L;
        synchronized (window) { window.clear(); }
    }

    public void onSingleplayerOrDisconnect() {
        detectionEnabled = false;
        suppressActive   = false;
        synchronized (window) { window.clear(); }
    }

    public void suppressForWorldLoad(long ignoredDuration) { onMultiplayerJoin(); }

    public void notifyModsLoaded() {
        if (mainMenuReached) return;
        mainMenuReached = true;
        startTime = System.currentTimeMillis();
        synchronized (window) { window.clear(); }
    }

    public StatusSnapshot getStatus() {
        return new StatusSnapshot(
                detectionEnabled, cheatDetected, baselineTaken,
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

        if (!baselineTaken) {
            synchronized (window) {
                window.add(new long[]{ now, curClasses });
                while (!window.isEmpty() && window.peek()[0] < now - WINDOW_MS) window.poll();
            }
            tryTakeBaseline(now, curClasses, curThreads);
        } else {
            if (!detectionEnabled) return;
            synchronized (window) {
                window.add(new long[]{ now, curClasses });
                while (!window.isEmpty() && window.peek()[0] < now - WINDOW_MS) window.poll();
            }
            detectInject(now, curClasses, curThreads);
        }
    }

    private void tryTakeBaseline(long now, int curClasses, int curThreads) {
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

        if (stableSamples < minSamples || stableDelta >= STABLE_CLASS_DELTA) return;

        baselineClasses = curClasses;
        baselineThreads = curThreads;
        baselineTaken   = true;
        synchronized (window) {
            window.clear();
            window.add(new long[]{ now, curClasses });
        }
    }

    private void detectInject(long now, int curClasses, int curThreads) {
        if (suppressActive) {
            long elapsed = now - suppressStartMs;
            if (elapsed >= SUPPRESS_MAX_MS) {
                suppressActive = false;
                synchronized (window) { window.clear(); window.add(new long[]{now, curClasses}); }
            } else if (elapsed >= SUPPRESS_MIN_MS) {
                int[] minMax = windowMinMax(now, STABLE_WINDOW_MS);
                int delta = (minMax[1] == Integer.MIN_VALUE) ? Integer.MAX_VALUE : minMax[1] - minMax[0];
                if (delta < STABLE_CLASS_DELTA) {
                    suppressActive = false;
                    synchronized (window) { window.clear(); window.add(new long[]{now, curClasses}); }
                } else {
                    return;
                }
            } else {
                return;
            }
        }

        int[] minMax      = windowMinMax(now, WINDOW_MS);
        int   deltaWindow   = (minMax[1] == Integer.MIN_VALUE) ? 0 : minMax[1] - minMax[0];
        int   deltaBaseline = curClasses - baselineClasses;
        int   deltaThreads  = curThreads  - baselineThreads;

        boolean rule1 = deltaWindow   >= CLASS_SPIKE_THRESHOLD && deltaThreads >= THREAD_SPIKE_THRESHOLD;
        boolean rule2 = deltaWindow   >= CLASS_SPIKE_STRONG;
        boolean rule3 = deltaBaseline >= CLASS_SPIKE_STRONG    && deltaThreads >= THREAD_SPIKE_THRESHOLD;

        if ((rule1 || rule2 || rule3) && !cheatDetected
                && now - lastAlertTs > ALERT_COOLDOWN_MS) {
            cheatDetected    = true;
            lastAlertTs      = now;
            lastDeltaClasses = deltaWindow;
            lastDeltaThreads = deltaThreads;
            lastAlertReason  = buildReason(deltaWindow, deltaBaseline, deltaThreads);
            fireAlert(lastAlertReason);
        }
    }

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

    public static final class StatusSnapshot {
        public final boolean detectionEnabled;
        public final boolean cheatDetected;
        public final boolean baselineTaken;
        public final int     baselineClasses;
        public final int     baselineThreads;
        public final int     currentClasses;
        public final int     currentThreads;
        public final String  lastAlertReason;
        public final int     lastDeltaClasses;
        public final int     lastDeltaThreads;

        StatusSnapshot(boolean detectionEnabled, boolean cheatDetected, boolean baselineTaken,
                       int baselineClasses, int baselineThreads,
                       int currentClasses,  int currentThreads,
                       String lastAlertReason,
                       int lastDeltaClasses, int lastDeltaThreads) {
            this.detectionEnabled = detectionEnabled;
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
