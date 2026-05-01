package com.anticheat.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
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
 * AntiCheat 2.0 – reine Erkennungsmod.
 *
 * Was diese Mod macht:
 *   1. Nimmt beim Start automatisch eine JVM-Baseline (nach ~10 s).
 *   2. Überwacht laufend auf Cheat-Injects (loadedClasses / Threads).
 *   3. Schreibt bei Erkennung sofort eine farbige Chat-Warnung.
 *   4. Macht 4 Screenshots (0 s / 5 s / 10 s / 30 s nach Erkennung).
 *   5. Schickt nach dem letzten Screenshot einen vollständigen Report
 *      inkl. aller 4 Bilder an den konfigurierten Discord-Webhook.
 *   6. Beantwortet /anti status, /anti info und /anti reset.
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

    // ── Forge-Lifecycle ─────────────────────────────────────────────────────

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        if (FMLCommonHandler.instance().getSide() != Side.CLIENT) return;

        InjectionDetector detector = InjectionDetector.get();
        detector.setAlertCallback(ClientAntiCheatMod::onCheatDetected);
        detector.start();

        net.minecraftforge.fml.common.FMLLog.log.info(
                "[AntiCheat {}] Detektor gestartet. Baseline wird nach dem Hauptmenü genommen.", VERSION);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (FMLCommonHandler.instance().getSide() != Side.CLIENT) return;
        MinecraftForge.EVENT_BUS.register(this);
        AntiCheatCommand.register();
    }

    /**
     * Feuert wenn "Forge Mod Loader has successfully loaded X mods" geloggt wird.
     * Ab jetzt erst ist Minecraft vollständig initialisiert → Baseline in 10s.
     */
    @Mod.EventHandler
    public void loadComplete(FMLLoadCompleteEvent event) {
        if (FMLCommonHandler.instance().getSide() != Side.CLIENT) return;
        InjectionDetector.get().notifyModsLoaded();
    }

    // ── Welt-Beitritt: Erkennungs-Pause ──────────────────────────────────────

    /**
     * Wenn der lokale Spieler einer Welt beitritt (SP + MP), lädt Minecraft
     * massiv Klassen und Threads → würde sonst False-Alarm auslösen.
     * EntityPlayerSP ist immer eindeutig der lokale Spieler.
     * Wichtig: mc.player kann beim ersten Join noch null sein – daher nicht
     * mit mc.player vergleichen, sondern instanceof EntityPlayerSP nutzen.
     */
    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (!(event.getEntity() instanceof EntityPlayerSP)) return;
        // 45s: StateMC lädt Registry, Mods, Physics-Welt, UDP-Verbindung
        InjectionDetector.get().suppressForWorldLoad(45_000L);
    }

    // ── Alert-Handler ────────────────────────────────────────────────────────

    /**
     * Wird vom InjectionDetector auf einem Background-Thread aufgerufen.
     * Alles, was MC-API braucht, wird via addScheduledTask auf den Main-Thread verschoben.
     */
    private static void onCheatDetected(String reason) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;

        // Spieler-Infos sofort auf Main-Thread holen und dann alles weitertriggern
        mc.addScheduledTask(() -> {
            // Chat-Warnung
            sendRaw("§c§l§m          §r §c§l⚠  CHEAT INJECT ERKANNT  §r§c§l§m          §r");
            sendRaw("§c" + reason);
            sendRaw("§7Bilder werden gesammelt und an Discord gesendet...");
            sendRaw("§7/anti status  §8|  §7/anti info  §8|  §7/anti reset");

            // Context für Discord-Report einsammeln
            String playerName = (mc.player != null) ? mc.player.getName() : "Unbekannt";
            String serverAddr;
            if (mc.isSingleplayer()) {
                serverAddr = "Singleplayer";
            } else {
                ServerData sd = mc.getCurrentServerData();
                serverAddr = (sd != null) ? sd.serverIP : "Unbekannt";
            }
            InjectionDetector.StatusSnapshot status = InjectionDetector.get().getStatus();

            // Screenshots starten; nach dem 30-s-Bild → Discord senden
            AntiCheatScreenshots.scheduleCapture(files ->
                    DiscordReporter.sendReport(reason, playerName, serverAddr, status, files));
        });
    }

    // ── Chat-Helfer ──────────────────────────────────────────────────────────

    /** Sendet Text direkt auf dem aktuellen Thread (muss Main-Thread sein). */
    static void sendRaw(String text) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        TextComponentString msg = new TextComponentString(text);
        if (mc.player != null) {
            mc.player.sendMessage(msg);
        } else if (mc.ingameGUI != null && mc.ingameGUI.getChatGUI() != null) {
            mc.ingameGUI.getChatGUI().printChatMessage(msg);
        }
    }

    /** Sendet Text farbig, von beliebigem Thread aus via addScheduledTask. */
    static void sendMsg(String text, TextFormatting color) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        TextComponentString msg = new TextComponentString(text);
        msg.getStyle().setColor(color);
        mc.addScheduledTask(() -> {
            if (mc.player != null) {
                mc.player.sendMessage(msg);
            } else if (mc.ingameGUI != null && mc.ingameGUI.getChatGUI() != null) {
                mc.ingameGUI.getChatGUI().printChatMessage(msg);
            }
        });
    }

    /** Sendet Text ohne Farb-Vorab-Formatierung, von beliebigem Thread. */
    static void sendAlert(String text) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        mc.addScheduledTask(() -> sendRaw(text));
    }
}
