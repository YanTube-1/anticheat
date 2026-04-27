package com.anticheat.client;

import java.util.Arrays;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Client-Befehle: {@code /anti ...} (nur Client).
 */
public final class AntiCheatCommand extends CommandBase {

    private AntiCheatCommand() {
    }

    public static void register() {
        if (FMLCommonHandler.instance().getSide() != Side.CLIENT) {
            return;
        }
        ClientCommandHandler.instance.registerCommand(new AntiCheatCommand());
    }

    @Override
    public String getName() {
        return "anti";
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("anticheat", "cac");
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/anti help";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) {
            help();
            return;
        }
        String a0 = args[0].toLowerCase();
        if ("help".equals(a0) || "?".equals(a0)) {
            help();
            return;
        }
        if ("version".equals(a0) || "v".equals(a0)) {
            msg(ClientAntiCheatMod.NAME + " " + ClientAntiCheatMod.VERSION, TextFormatting.AQUA);
            return;
        }
        if ("status".equals(a0)) {
            status();
            return;
        }
        if ("scan".equals(a0) || "probe".equals(a0)) {
            msg("[AntiCheat] Echter Scan - bei Treffer: Log, Chat, Screenshots (0/5/10/30 s).",
                    TextFormatting.YELLOW);
            DoomsdayRuntimeProbe.runAll();
            return;
        }
        if ("debug".equals(a0)) {
            if (args.length < 2) {
                msg("Debug: " + (AntiCheatDebug.isEnabled() ? "an" : "aus") + " - /anti debug on|off|test",
                        TextFormatting.GRAY);
                return;
            }
            String a1 = args[1].toLowerCase();
            if ("on".equals(a1) || "1".equals(a1) || "true".equals(a1)) {
                AntiCheatDebug.setEnabled(true);
                msg("[AntiCheat] Debug an (mehr Details bei test/status).", TextFormatting.GREEN);
                return;
            }
            if ("off".equals(a1) || "0".equals(a1) || "false".equals(a1)) {
                AntiCheatDebug.setEnabled(false);
                msg("[AntiCheat] Debug aus.", TextFormatting.GRAY);
                return;
            }
            if ("test".equals(a1)) {
                runDebugTest();
                return;
            }
            msg("Unbekannt: /anti debug on|off|test", TextFormatting.RED);
            return;
        }
        msg("Unbekannt. /anti help", TextFormatting.RED);
    }

    private static void runDebugTest() {
        String err = CheatDetector.performSelfTestLogOnly();
        if (err != null) {
            msg("[AntiCheat] Selbsttest Log: " + err, TextFormatting.RED);
        } else {
            msg("[AntiCheat] Selbsttest: Zeile in anticheat-client.log (SELFTEST).", TextFormatting.GREEN);
        }
        boolean verbose = AntiCheatDebug.isEnabled();
        msg("[AntiCheat] Diagnose (löst keinen Cheat-Alarm aus):", TextFormatting.GOLD);
        for (String line : DoomsdayRuntimeProbe.collectDiagnosticLines(verbose)) {
            msg("  " + line, TextFormatting.WHITE);
        }
        msg("[AntiCheat] Tipp: /anti debug on - ClassLoader-Details beim nächsten test.", TextFormatting.GRAY);
    }

    private static void status() {
        msg("[AntiCheat] Status " + ClientAntiCheatMod.VERSION, TextFormatting.AQUA);
        msg("  Debug: " + (AntiCheatDebug.isEnabled() ? "an" : "aus"), TextFormatting.WHITE);
        for (String line : DoomsdayRuntimeProbe.collectDiagnosticLines(AntiCheatDebug.isEnabled())) {
            msg("  " + line, TextFormatting.WHITE);
        }
    }

    private static void help() {
        msg("--- AntiCheat Client ---", TextFormatting.GOLD);
        msg("/anti help - diese Hilfe", TextFormatting.WHITE);
        msg("/anti version - Mod-Version", TextFormatting.WHITE);
        msg("/anti status - Kurzdiagnose (nur aktueller Stand)", TextFormatting.WHITE);
        msg("/anti debug on|off - ausführlichere Diagnose", TextFormatting.WHITE);
        msg("/anti debug test - Log-Selbsttest + Diagnose ohne Alarm", TextFormatting.WHITE);
        msg("/anti scan - echter Laufzeit-Scan (kann cheat erkannt + 4 Screenshots auslösen)", TextFormatting.WHITE);
        msg("Aliase: /anticheat, /cac", TextFormatting.GRAY);
    }

    private static void msg(String text, TextFormatting color) {
        if (FMLCommonHandler.instance().getSide() != Side.CLIENT) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        final String t = text;
        final TextFormatting c = color;
        mc.addScheduledTask(() -> {
            TextComponentString comp = new TextComponentString(t);
            comp.getStyle().setColor(c);
            if (mc.player != null) {
                mc.player.sendMessage(comp);
            } else if (mc.ingameGUI != null && mc.ingameGUI.getChatGUI() != null) {
                mc.ingameGUI.getChatGUI().printChatMessage(comp);
            }
        });
    }
}
