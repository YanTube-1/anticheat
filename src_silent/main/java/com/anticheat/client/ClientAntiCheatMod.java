package com.anticheat.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLLoadCompleteEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * AntiCheat Silent – vollständig unsichtbar für den Spieler.
 *
 * Kein Chat, keine Befehle, keine logs/latest.log Einträge.
 * Erkennung läuft komplett im Hintergrund.
 * Bei Cheat-Detect: Screenshots in Temp → Discord-Report → Dateien gelöscht.
 */
@Mod(
        modid   = ClientAntiCheatMod.MODID,
        name    = ClientAntiCheatMod.NAME,
        version = ClientAntiCheatMod.VERSION,
        clientSideOnly = true,
        acceptableRemoteVersions = "*")
public final class ClientAntiCheatMod {

    public static final String MODID   = "clientanticheat";
    public static final String NAME    = "Client AntiCheat";
    public static final String VERSION = "2.0.1";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        if (FMLCommonHandler.instance().getSide() != Side.CLIENT) return;
        InjectionDetector detector = InjectionDetector.get();
        detector.setAlertCallback(ClientAntiCheatMod::onCheatDetected);
        detector.start();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (FMLCommonHandler.instance().getSide() != Side.CLIENT) return;
        MinecraftForge.EVENT_BUS.register(this);
        // Keine Commands registrieren – vollständig unsichtbar
    }

    @Mod.EventHandler
    public void loadComplete(FMLLoadCompleteEvent event) {
        if (FMLCommonHandler.instance().getSide() != Side.CLIENT) return;
        InjectionDetector.get().notifyModsLoaded();
    }

    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (!(event.getEntity() instanceof EntityPlayerSP)) return;
        if (Minecraft.getMinecraft().isIntegratedServerRunning()) {
            InjectionDetector.get().onSingleplayerOrDisconnect();
        } else {
            InjectionDetector.get().onMultiplayerJoin();
        }
    }

    // ── Alert-Handler (kein Chat, nur Discord) ────────────────────────────────

    private static void onCheatDetected(String reason) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;

        mc.addScheduledTask(() -> {
            String playerName = (mc.player != null) ? mc.player.getName() : "Unbekannt";
            String serverAddr;
            if (mc.isSingleplayer()) {
                serverAddr = "Singleplayer";
            } else {
                ServerData sd = mc.getCurrentServerData();
                serverAddr = (sd != null) ? sd.serverIP : "Unbekannt";
            }
            InjectionDetector.StatusSnapshot status = InjectionDetector.get().getStatus();

            // Screenshots in Temp → nach Upload automatisch löschen
            AntiCheatScreenshots.scheduleCapture(files ->
                    DiscordReporter.sendReport(reason, playerName, serverAddr, status, files));
        });
    }
}
