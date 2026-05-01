package com.anticheat.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

/**
 * Läuft vor normalem Mod-Code und vor den meisten Forge-Umleitungen von {@code System.out}.
 * Ohne diesen Hook fehlt oft der Konsolen-Spam von Injectoren.
 */
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.Name("clientanticheat_early")
@IFMLLoadingPlugin.SortingIndex(Integer.MIN_VALUE + 500)
public class EarlyCorePlugin implements IFMLLoadingPlugin {

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        try {
            String ud = System.getProperty("user.dir");
            Path logsDir = (ud != null && !ud.isEmpty())
                    ? Paths.get(ud).resolve("logs")
                    : Paths.get(".").resolve("logs");
            Files.createDirectories(logsDir);
        } catch (Throwable ignored) {
        }
    }
}
