package com.anticheat.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
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
            Path logsDir;
            if (ud != null && !ud.isEmpty()) {
                logsDir = Paths.get(ud).resolve("logs");
                CheatDetector.setOutputLog(logsDir.resolve("anticheat-client.log"));
            } else {
                logsDir = Paths.get(".").resolve("logs");
                CheatDetector.setOutputLog(logsDir.resolve("anticheat-client.log"));
            }
            Files.createDirectories(logsDir);
            Files.write(
                    logsDir.resolve("anticheat-bootstrap.log"),
                    ("[+BOOT] EarlyCorePlugin.injectData ok dir=" + ud + "\n").getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);

            ConsoleSniffer.install();
        } catch (Throwable ignored) {
        }
    }
}
