package com.anticheat.client;

import java.util.Arrays;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;

/**
 * /anti – AntiCheat 2.0 Kommandos.
 *
 * Subcommands:
 *   /anti status  – Zeigt ob ein Cheat erkannt wurde (grün/rot)
 *   /anti info    – Rohe JVM-Zahlen (Klassen, Threads, Deltas)
 *   /anti reset   – Baseline neu nehmen (z.B. nach Neustart des Cheats)
 *   /anti help    – Hilfe
 */
public final class AntiCheatCommand extends CommandBase {

    private AntiCheatCommand() {}

    public static void register() {
        if (FMLCommonHandler.instance().getSide() != Side.CLIENT) return;
        ClientCommandHandler.instance.registerCommand(new AntiCheatCommand());
    }

    @Override public String getName()  { return "anti"; }
    @Override public List<String> getAliases() { return Arrays.asList("anticheat", "ac"); }
    @Override public String getUsage(ICommandSender sender) { return "/anti <status|info|reset|help>"; }
    @Override public int getRequiredPermissionLevel() { return 0; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args)
            throws CommandException {

        String sub = args.length > 0 ? args[0].toLowerCase() : "status";

        switch (sub) {

            // ── /anti status ─────────────────────────────────────────────────
            case "status": {
                InjectionDetector.StatusSnapshot s = InjectionDetector.get().getStatus();

                if (!s.detectionEnabled) {
                    msg("§7[AntiCheat] §8Inaktiv §7– nur im Multiplayer aktiv.");
                    return;
                }
                if (!s.baselineTaken) {
                    msg("§e[AntiCheat] Baseline noch nicht genommen (warte ~10s nach Start)...");
                    return;
                }

                if (s.cheatDetected) {
                    msg("§c§l⚠  CHEAT ERKANNT  §r§c– " + s.lastAlertReason);
                    msg("§7Klassen-Delta: §f+" + s.lastDeltaClasses
                            + " §7| Threads-Delta: §f+" + s.lastDeltaThreads);
                    msg("§7/anti info §7für vollständige JVM-Zahlen.");
                } else {
                    msg("§a§l✔  SAUBER §r§7– kein Inject erkannt.");
                    msg("§7Klassen aktuell: §f" + s.currentClasses
                            + " §7| Baseline: §f" + s.baselineClasses
                            + " §7| Delta: §f+" + (s.currentClasses - s.baselineClasses));
                }
                break;
            }

            // ── /anti info ───────────────────────────────────────────────────
            case "info": {
                InjectionDetector.StatusSnapshot s = InjectionDetector.get().getStatus();
                msg("§6§l─ AntiCheat 2.0 – JVM Info ─");
                if (!s.detectionEnabled) { msg("§8Status: Inaktiv (Singleplayer)"); return; }
                if (s.baselineTaken) {
                    msg("§7Baseline Klassen : §f" + s.baselineClasses
                            + "  §7Threads: §f" + s.baselineThreads);
                    msg("§7Jetzt   Klassen  : §f" + s.currentClasses
                            + "  §7Threads: §f" + s.currentThreads);
                    int dc = s.currentClasses - s.baselineClasses;
                    int dt = s.currentThreads  - s.baselineThreads;
                    String dcColor = dc >= 500 ? "§c" : dc >= 100 ? "§e" : "§a";
                    String dtColor = dt >=   4 ? "§c" : dt >=   2 ? "§e" : "§a";
                    msg("§7Delta Klassen    : " + dcColor + (dc >= 0 ? "+" : "") + dc);
                    msg("§7Delta Threads    : " + dtColor + (dt >= 0 ? "+" : "") + dt);
                } else {
                    msg("§eBaseline noch ausstehend (warte ~10s nach Start).");
                    msg("§7Jetzt Klassen: §f" + s.currentClasses
                            + "  §7Threads: §f" + s.currentThreads);
                }
                msg("§7Status: " + (s.cheatDetected ? "§c§lCHEAT ERKANNT" : "§a§lSAUBER"));
                if (s.cheatDetected) {
                    msg("§7Grund: §f" + s.lastAlertReason);
                }
                break;
            }

            // ── /anti reset ──────────────────────────────────────────────────
            case "reset": {
                InjectionDetector.get().resetBaseline();
                msg("§e[AntiCheat] Baseline zurückgesetzt. Neue Baseline in ~25-40s (wenn stabil)...");
                break;
            }

            // ── /anti help ───────────────────────────────────────────────────
            default: {
                msg("§6§l─ AntiCheat 2.0 ─");
                msg("§f/anti status §7– Cheat erkannt? (Kurzform)");
                msg("§f/anti info   §7– Vollständige JVM-Zahlen");
                msg("§f/anti reset  §7– Baseline neu nehmen");
                break;
            }
        }
    }

    // ── Helfer ───────────────────────────────────────────────────────────────

    private static void msg(String text) {
        ClientAntiCheatMod.sendAlert(text);
    }
}
