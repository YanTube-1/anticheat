package com.anticheat.client;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Bei Erkennung: Screenshots unter {@code screenshots/anticheat/} nach 0, 5, 10 und 30 Sekunden.
 */
public final class AntiCheatScreenshots {

    private static final Object BURST_LOCK = new Object();
    private static ScheduledExecutorService burstScheduler;
    private static final List<ScheduledFuture<?>> pendingBurst = new ArrayList<ScheduledFuture<?>>(4);

    private AntiCheatScreenshots() {
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
            if (f != null && !f.isDone()) {
                f.cancel(false);
            }
        }
        pendingBurst.clear();
    }

    /**
     * Von beliebigem Thread: volle Serie planen (sofort + 5s + 10s + 30s), jeweils auf Client-Thread aufnehmen.
     */
    public static void scheduleCapture() {
        if (FMLCommonHandler.instance().getSide() != Side.CLIENT) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        mc.addScheduledTask(AntiCheatScreenshots::scheduleBurstFromClientThread);
    }

    /**
     * Aus einem laufenden Client-Task (z. B. Chat-Alarm): sofort erste Aufnahme, Rest zeitversetzt.
     */
    static void scheduleBurstFromClientThread() {
        if (FMLCommonHandler.instance().getSide() != Side.CLIENT) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        synchronized (BURST_LOCK) {
            cancelPendingBurst();
            ensureBurstScheduler();
            String session = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss-SSS", Locale.ROOT).format(new Date());

            captureToFile(session, "0s");

            pendingBurst.add(scheduleLater(session, "5s", 5L));
            pendingBurst.add(scheduleLater(session, "10s", 10L));
            pendingBurst.add(scheduleLater(session, "30s", 30L));
        }
    }

    private static ScheduledFuture<?> scheduleLater(String session, String label, long delaySec) {
        return burstScheduler.schedule(
                () -> {
                    Minecraft m = Minecraft.getMinecraft();
                    if (m != null) {
                        m.addScheduledTask(() -> captureToFile(session, label));
                    }
                },
                delaySec,
                TimeUnit.SECONDS);
    }

    private static void captureToFile(String session, String label) {
        if (FMLCommonHandler.instance().getSide() != Side.CLIENT) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameDir == null) {
            return;
        }
        File shots = new File(mc.gameDir, "screenshots");
        File sub = new File(shots, "anticheat");
        if (!sub.exists() && !sub.mkdirs()) {
            return;
        }
        String relative = "anticheat/cheat_" + session + "_" + label + ".png";
        try {
            Framebuffer fb = mc.getFramebuffer();
            ScreenShotHelper.saveScreenshot(mc.gameDir, relative, mc.displayWidth, mc.displayHeight, fb);
            CheatDetector.appendLogLine("screenshot: screenshots/" + relative.replace('\\', '/'));
        } catch (Throwable ignored) {
        }
    }
}
