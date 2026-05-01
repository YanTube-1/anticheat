package com.anticheat.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Rapid Data Collection für AntiCheat-Training.
 *
 * Befehle:
 *   /anti rapid          → Session starten (nummeriert, jede 0.5s Snapshot)
 *   /anti rapid stop     → Session stoppen
 *   /anti ts <Text>      → Zeitstempel-Marker in aktive Session schreiben
 *
 * Output:
 *   .minecraft/anticheat-rapid-001.jsonl  (nummeriert, jede Session +1)
 *   .minecraft/anticheat-rapid-counter.txt
 */
public final class RapidTrainer {

    private static final long   SNAPSHOT_INTERVAL_MS = 500L;
    private static final String COUNTER_FILE         = "anticheat-rapid-counter.txt";
    private static final String LOG_PREFIX           = "anticheat-rapid-";

    private final AtomicBoolean running      = new AtomicBoolean(false);
    private volatile String     sessionId    = "";
    private volatile String     pendingLabel = "";
    private volatile long       lastSnapshotMs = 0L;

    private PrintWriter writer;
    private File        currentFile;

    // ── Tick-Event ───────────────────────────────────────────────────────────

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!running.get()) return;

        long now = System.currentTimeMillis();
        if (now - lastSnapshotMs < SNAPSHOT_INTERVAL_MS) return;
        lastSnapshotMs = now;

        try {
            String line = buildSnapshotJson(now);
            if (writer != null) {
                writer.println(line);
                writer.flush();
            }
        } catch (Throwable ignored) {}

        pendingLabel = "";
    }

    // ── Start / Stop ─────────────────────────────────────────────────────────

    public synchronized void startSession() {
        if (running.get()) {
            chat("§eRapid läuft bereits – zuerst /anti rapid stop");
            return;
        }
        int num = readAndIncrementCounter();
        sessionId = String.format("%03d", num);
        String filename = LOG_PREFIX + sessionId + ".jsonl";

        Minecraft mc = Minecraft.getMinecraft();
        currentFile = new File(mc.gameDir, filename);
        try {
            writer = new PrintWriter(new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(currentFile, true), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            chat("§cKonnte Datei nicht öffnen: " + e.getMessage());
            return;
        }

        lastSnapshotMs = 0;
        running.set(true);
        chat("§aRapid gestartet – Session §f" + sessionId + "§a → §f" + filename);
        chat("§7/anti ts <Label>  um Marker zu setzen  |  /anti rapid stop  zum Stoppen");
    }

    public synchronized void stopSession() {
        if (!running.get()) {
            chat("§eKeine aktive Rapid-Session.");
            return;
        }
        running.set(false);
        if (writer != null) { writer.close(); writer = null; }
        chat("§aRapid gestoppt. Datei: §f" + (currentFile != null ? currentFile.getName() : "?"));
    }

    public void setLabel(String label) {
        if (!running.get()) {
            chat("§eKeine aktive Session – zuerst /anti rapid starten.");
            return;
        }
        pendingLabel = label;
        chat("§7Label gesetzt: §f" + label);
    }

    // ── Snapshot-Builder ─────────────────────────────────────────────────────

    private String buildSnapshotJson(long nowMs) {
        Minecraft mc   = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        World world      = mc.world;

        String iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(new Date(nowMs));

        int    loadedClasses      = (int) ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
        long   totalLoadedClasses = ManagementFactory.getClassLoadingMXBean().getTotalLoadedClassCount();
        long   usedMem            = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
        long   maxMem             = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        int    threadCount        = Thread.activeCount();
        String threadNames        = getThreadNamesSample();

        double px = 0, py = 0, pz = 0, yaw = 0, pitch = 0;
        double mx = 0, my = 0, mz = 0, fall = 0;
        boolean ground = false, inWater = false, inLava = false;
        int food = 20; float health = 20;
        if (p != null) {
            px = p.posX; py = p.posY; pz = p.posZ;
            yaw = p.rotationYaw; pitch = p.rotationPitch;
            mx = p.motionX; my = p.motionY; mz = p.motionZ;
            fall = p.fallDistance;
            ground  = p.onGround;
            inWater = p.isInWater();
            inLava  = p.isInLava();
            food    = p.getFoodStats().getFoodLevel();
            health  = p.getHealth();
        }

        int  dim  = (world != null) ? world.provider.getDimension() : 0;
        long time = (world != null) ? world.getWorldTime() : 0;
        boolean rain = (world != null) && world.isRaining();
        int fps = Minecraft.getDebugFPS();
        int sw  = mc.displayWidth, sh = mc.displayHeight;

        StringBuilder sb = new StringBuilder(512);
        sb.append("{");
        sb.append("\"iso\":\"").append(esc(iso)).append("\",");
        sb.append("\"session\":\"").append(esc(sessionId)).append("\",");
        sb.append("\"label\":\"").append(esc(pendingLabel)).append("\",");
        sb.append("\"loadedClasses\":").append(loadedClasses).append(",");
        sb.append("\"totalLoadedClasses\":").append(totalLoadedClasses).append(",");
        sb.append("\"jvmUsedMemMb\":").append(usedMem).append(",");
        sb.append("\"jvmMaxMemMb\":").append(maxMem).append(",");
        sb.append("\"threadCount\":").append(threadCount).append(",");
        sb.append("\"threadNames\":\"").append(esc(threadNames)).append("\",");
        sb.append("\"px\":").append(fmt(px)).append(",");
        sb.append("\"py\":").append(fmt(py)).append(",");
        sb.append("\"pz\":").append(fmt(pz)).append(",");
        sb.append("\"yaw\":").append(fmt(yaw)).append(",");
        sb.append("\"pitch\":").append(fmt(pitch)).append(",");
        sb.append("\"onGround\":").append(ground).append(",");
        sb.append("\"food\":").append(food).append(",");
        sb.append("\"health\":").append(fmt(health)).append(",");
        sb.append("\"mx\":").append(fmt(mx)).append(",");
        sb.append("\"my\":").append(fmt(my)).append(",");
        sb.append("\"mz\":").append(fmt(mz)).append(",");
        sb.append("\"fall\":").append(fmt(fall)).append(",");
        sb.append("\"inWater\":").append(inWater).append(",");
        sb.append("\"inLava\":").append(inLava).append(",");
        sb.append("\"dim\":").append(dim).append(",");
        sb.append("\"worldTime\":").append(time).append(",");
        sb.append("\"raining\":").append(rain).append(",");
        sb.append("\"fps\":").append(fps).append(",");
        sb.append("\"resW\":").append(sw).append(",");
        sb.append("\"resH\":").append(sh);
        sb.append("}");
        return sb.toString();
    }

    // ── Hilfsmethoden ────────────────────────────────────────────────────────

    private static String getThreadNamesSample() {
        Thread[] arr = new Thread[Thread.activeCount() + 8];
        int count = Thread.enumerate(arr);
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < count && shown < 20; i++) {
            if (arr[i] == null) continue;
            String name = arr[i].getName();
            if (name.startsWith("AntiCheat") || name.startsWith("main") || name.startsWith("Client")) continue;
            if (sb.length() > 0) sb.append("|");
            sb.append(name.length() > 48 ? name.substring(0, 48) : name);
            shown++;
        }
        return sb.toString();
    }

    private static int readAndIncrementCounter() {
        File f = new File(Minecraft.getMinecraft().gameDir, COUNTER_FILE);
        int n = 1;
        if (f.isFile()) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line = r.readLine();
                if (line != null) n = Integer.parseInt(line.trim()) + 1;
            } catch (Exception ignored) {}
        }
        try (PrintWriter w = new PrintWriter(new FileWriter(f))) { w.print(n); } catch (Exception ignored) {}
        return n;
    }

    private static String fmt(double d) { return String.format(Locale.ROOT, "%.4f", d); }
    private static String fmt(float f)  { return String.format(Locale.ROOT, "%.3f", f); }

    private static String esc(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    static void chat(String msg) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        mc.addScheduledTask(() -> {
            if (mc.player != null)
                mc.player.sendMessage(new net.minecraft.util.text.TextComponentString(msg));
        });
    }

    // ── Command ──────────────────────────────────────────────────────────────

    public static final class Cmd extends CommandBase {

        private static final RapidTrainer TRAINER = new RapidTrainer();

        public static void register() {
            ClientCommandHandler.instance.registerCommand(new Cmd());
            MinecraftForge.EVENT_BUS.register(TRAINER);
        }

        @Override public String getName() { return "anti"; }
        @Override public List<String> getAliases() { return Arrays.asList("anticheat", "ac"); }
        @Override public String getUsage(ICommandSender s) { return "/anti rapid [stop] | /anti ts <Label>"; }
        @Override public int getRequiredPermissionLevel() { return 0; }

        @Override
        public void execute(MinecraftServer server, ICommandSender sender, String[] args)
                throws CommandException {
            if (args.length == 0) { printHelp(); return; }
            switch (args[0].toLowerCase()) {
                case "rapid":
                    if (args.length >= 2 && args[1].equalsIgnoreCase("stop")) {
                        TRAINER.stopSession();
                    } else {
                        TRAINER.startSession();
                    }
                    break;
                case "ts":
                    if (args.length < 2) { chat("§cUsage: /anti ts <Label>"); return; }
                    TRAINER.setLabel(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                    break;
                default:
                    printHelp();
            }
        }

        private void printHelp() {
            chat("§6─ AntiCheat Trainer ─");
            chat("§f/anti rapid      §7– Neue Session starten");
            chat("§f/anti rapid stop §7– Session stoppen");
            chat("§f/anti ts <Text>  §7– Label in aktive Session schreiben");
        }
    }
}
