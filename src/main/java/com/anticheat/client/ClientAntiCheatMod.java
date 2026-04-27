package com.anticheat.client;

import java.nio.file.Path;
import java.nio.file.Paths;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.relauncher.Side;

@Mod(
        modid = ClientAntiCheatMod.MODID,
        name = ClientAntiCheatMod.NAME,
        version = ClientAntiCheatMod.VERSION,
        clientSideOnly = true,
        acceptableRemoteVersions = "*")
public final class ClientAntiCheatMod {

    public static final String MODID = "clientanticheat";
    public static final String NAME = "Client AntiCheat Sniffer";
    public static final String VERSION = "1.2.2";

    /** Alle ~3 s im laufenden Client (auch nach späterem Ingame-Inject). */
    private int clientProbeTickCounter;

    static {
        String ud = System.getProperty("user.dir");
        if (ud != null && !ud.isEmpty()) {
            CheatDetector.setOutputLog(Paths.get(ud).resolve("logs").resolve("anticheat-client.log"));
        }
        ConsoleSniffer.install();
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        if (FMLCommonHandler.instance().getSide() != Side.CLIENT) {
            return;
        }
        Path gameDir = event.getModConfigurationDirectory().toPath().getParent();
        CheatDetector.setOutputLog(gameDir.resolve("logs").resolve("anticheat-client.log"));
        CheatDetector.setClientAlertHook(ClientChatAlert::schedule);
        CheatLogFilter.install();

        Path logs = gameDir.resolve("logs");
        Thread t1 = new Thread(new LatestLogTailer(logs.resolve("latest.log")), "ClientAntiCheat-latest.log");
        t1.setDaemon(true);
        t1.start();
        Thread t2 = new Thread(new LatestLogTailer(logs.resolve("debug.log")), "ClientAntiCheat-debug.log");
        t2.setDaemon(true);
        t2.start();

        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ClientAntiCheat-ConsoleReattach");
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(ConsoleSniffer::reattachIfNeeded, 3L, 10L, TimeUnit.SECONDS);

        java.util.concurrent.ScheduledExecutorService probeEx = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ClientAntiCheat-DoomsdayProbe");
            t.setDaemon(true);
            return t;
        });
        probeEx.scheduleAtFixedRate(DoomsdayRuntimeProbe::runAll, 4L, 5L, TimeUnit.SECONDS);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (FMLCommonHandler.instance().getSide() != Side.CLIENT) {
            return;
        }
        MinecraftForge.EVENT_BUS.register(this);
        AntiCheatCommand.register();
        ConsoleSniffer.reattachIfNeeded();
        DoomsdayRuntimeProbe.runAll();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++clientProbeTickCounter < 60) {
            return;
        }
        clientProbeTickCounter = 0;
        DoomsdayRuntimeProbe.runAll();
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        if (FMLCommonHandler.instance().getSide() != Side.CLIENT) {
            return;
        }
        DoomsdayRuntimeProbe.runAll();
    }
}
