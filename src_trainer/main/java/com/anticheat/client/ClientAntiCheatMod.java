package com.anticheat.client;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * AntiCheat Trainer – erfasst alle 0.5s Snapshots für späteres ML-Training.
 *
 * Befehle:
 *   /anti rapid          – Session starten (nummerierte JSONL-Datei)
 *   /anti rapid stop     – Session stoppen
 *   /anti ts <Text>      – Zeitstempel-Marker in aktive Session schreiben
 */
@Mod(
        modid   = ClientAntiCheatMod.MODID,
        name    = ClientAntiCheatMod.NAME,
        version = ClientAntiCheatMod.VERSION,
        clientSideOnly = true,
        acceptableRemoteVersions = "*")
public final class ClientAntiCheatMod {

    public static final String MODID   = "clientanticheat";
    public static final String NAME    = "AntiCheat Trainer";
    public static final String VERSION = "1.0.0";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {}

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (FMLCommonHandler.instance().getSide() != Side.CLIENT) return;
        RapidTrainer.Cmd.register();
    }
}
