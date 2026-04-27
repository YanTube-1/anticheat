package com.anticheat.client;

import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

/**
 * Chat-HUD nur auf dem Client; muss vom Haupt-Thread ausgeführt werden.
 */
public final class ClientChatAlert {

    private static final String MSG = "[AntiCheat] cheat erkannt";

    private ClientChatAlert() {
    }

    public static void schedule() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        mc.addScheduledTask(() -> {
            TextComponentString text = new TextComponentString(MSG);
            text.getStyle().setColor(TextFormatting.RED);
            if (mc.player != null) {
                mc.player.sendMessage(text);
            } else if (mc.ingameGUI != null && mc.ingameGUI.getChatGUI() != null) {
                mc.ingameGUI.getChatGUI().printChatMessage(text);
            }
            AntiCheatScreenshots.scheduleBurstFromClientThread();
        });
    }
}
