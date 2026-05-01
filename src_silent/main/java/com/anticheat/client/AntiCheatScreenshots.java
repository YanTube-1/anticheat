package com.anticheat.client;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Screenshot-Burst bei Cheat-Erkennung: 0 s, 5 s, 10 s, 30 s.
 *
 * Silent-Version: Dateien werden im System-Temp-Verzeichnis gespeichert
 * und nach dem Discord-Upload automatisch gelöscht.
 */
public final class AntiCheatScreenshots {

    private static final Object BURST_LOCK = new Object();
    private static ScheduledExecutorService burstScheduler;
    private static final List<ScheduledFuture<?>> pendingBurst = new ArrayList<>(4);
    private static volatile boolean screenshotsEnabled = true;

    private static final List<File> currentBurstFiles = new CopyOnWriteArrayList<>();
    private static volatile Consumer<List<File>> burstCompleteCallback = null;

    private AntiCheatScreenshots() {}

    public static boolean isScreenshotsEnabled() { return screenshotsEnabled; }
    public static void    setScreenshotsEnabled(boolean on) { screenshotsEnabled = on; }

    /**
     * Plant einen 4-Bilder-Burst (0 / 5 / 10 / 30 s).
     * Dateien landen im System-Temp; nach Callback werden sie gelöscht.
     */
    public static void scheduleCapture(Consumer<List<File>> onComplete) {
        if (!screenshotsEnabled) return;
        if (FMLCommonHandler.instance().getSide() != Side.CLIENT) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        // Callback + Auto-Delete wrappen
        burstCompleteCallback = files -> {
            if (onComplete != null) {
                try { onComplete.accept(files); } catch (Throwable ignored) {}
            }
            // Dateien nach Upload löschen
            for (File f : files) {
                try { if (f != null && f.isFile()) f.delete(); } catch (Throwable ignored) {}
            }
        };
        mc.addScheduledTask(AntiCheatScreenshots::scheduleBurstFromClientThread);
    }

    public static void scheduleCapture() { scheduleCapture(null); }

    // ── Interna ─────────────────────────────────────────────────────────────

    static void scheduleBurstFromClientThread() {
        if (!screenshotsEnabled) return;
        if (FMLCommonHandler.instance().getSide() != Side.CLIENT) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;

        synchronized (BURST_LOCK) {
            cancelPendingBurst();
            currentBurstFiles.clear();
            ensureBurstScheduler();

            String session = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss-SSS", Locale.ROOT)
                    .format(new Date());

            captureToFile(session, "0s", false);
            pendingBurst.add(scheduleLater(session, "5s",  5L,  false));
            pendingBurst.add(scheduleLater(session, "10s", 10L, false));
            pendingBurst.add(scheduleLater(session, "30s", 30L, true));
        }
    }

    private static ScheduledFuture<?> scheduleLater(String session, String label,
                                                     long delaySec, boolean isLast) {
        return burstScheduler.schedule(() -> {
            Minecraft m = Minecraft.getMinecraft();
            if (m != null) m.addScheduledTask(() -> captureToFile(session, label, isLast));
        }, delaySec, TimeUnit.SECONDS);
    }

    private static void captureToFile(String session, String label, boolean isLast) {
        if (FMLCommonHandler.instance().getSide() != Side.CLIENT) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameDir == null) return;

        // Temp-Verzeichnis des Systems statt screenshots/anticheat/
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "anticheat_tmp");
        if (!tempDir.exists() && !tempDir.mkdirs()) return;

        String filename = "cheat_" + session + "_" + label + ".png";
        File target = new File(tempDir, filename);

        try {
            Framebuffer fb = mc.getFramebuffer();
            // ScreenShotHelper braucht einen relativen Pfad ab mc.gameDir.
            // Da Temp außerhalb gameDir liegt, speichern wir zuerst temporär
            // in einem relativen Pfad und verschieben dann.
            File relDir = new File(mc.gameDir, "anticheat_tmp_staging");
            relDir.mkdirs();
            String relative = "anticheat_tmp_staging/" + filename;
            ScreenShotHelper.saveScreenshot(mc.gameDir, relative,
                    mc.displayWidth, mc.displayHeight, fb);

            File staged = new File(mc.gameDir, relative);
            if (staged.isFile()) {
                // In echtes Temp-Verzeichnis verschieben
                if (target.exists()) target.delete();
                staged.renameTo(target);
                currentBurstFiles.add(target);
                // Staging-Ordner aufräumen
                staged.delete();
            }
        } catch (Throwable ignored) {}

        if (isLast) {
            Consumer<List<File>> cb = burstCompleteCallback;
            if (cb != null) {
                List<File> snapshot = Collections.unmodifiableList(
                        new ArrayList<>(currentBurstFiles));
                try { cb.accept(snapshot); } catch (Throwable t) {
                    // Silent – kein Log
                }
            }
        }
    }

    private static void ensureBurstScheduler() {
        if (burstScheduler == null) {
            burstScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ClientAntiCheat-ScreenshotBurst");
                t.setDaemon(true);
                return t;
            });
        }
    }

    private static void cancelPendingBurst() {
        for (ScheduledFuture<?> f : pendingBurst) {
            if (f != null && !f.isDone()) f.cancel(false);
        }
        pendingBurst.clear();
    }
}
