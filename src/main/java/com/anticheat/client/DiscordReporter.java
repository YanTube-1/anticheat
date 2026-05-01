package com.anticheat.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sendet bei Cheat-Erkennung einen Report mit Screenshots an einen Discord-Webhook.
 *
 * Format:
 *   - 1 Embed mit Erkennnungs-Details (rot) + erstem Screenshot
 *   - 3 weitere Embeds (je ein Screenshot: 5s, 10s, 30s)
 *   - Alle 4 PNG-Dateien als Anhang via multipart/form-data
 */
public final class DiscordReporter {

    /** Discord-Webhook-URL. */
    private static volatile String webhookUrl =
            "https://discord.com/api/webhooks/1499525241632719018/" +
            "bUd2QOYBiP-Q1SdhDzO4DOa-arfIUh-N538_J0_9WqNPCoL1d1hgbZh2VTpL-4w0I7FL";

    private static final String BOUNDARY = "----AntiCheat2Boundary";
    private static final String CRLF     = "\r\n";

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AntiCheat-DiscordReport");
        t.setDaemon(true);
        return t;
    });

    private DiscordReporter() {}

    public static void setWebhookUrl(String url) { webhookUrl = url; }

    // ── Öffentliche API ─────────────────────────────────────────────────────

    /**
     * Sendet den Report asynchron.
     *
     * @param alertReason   Erkennungsgrund (z.B. "+1640 Klassen/15s, +12 Threads")
     * @param playerName    MC-Spielername zum Zeitpunkt des Alarms
     * @param serverAddress Server-IP/Hostname (oder "Singleplayer")
     * @param status        Snapshot aus InjectionDetector
     * @param screenshots   Liste der gespeicherten Screenshot-Dateien (0s, 5s, 10s, 30s)
     */
    public static void sendReport(String alertReason,
                                  String playerName,
                                  String serverAddress,
                                  InjectionDetector.StatusSnapshot status,
                                  List<File> screenshots) {
        if (webhookUrl == null || webhookUrl.isEmpty()) return;

        POOL.submit(() -> {
            try {
                doSend(alertReason, playerName, serverAddress, status, screenshots);
            } catch (Exception e) {
                net.minecraftforge.fml.common.FMLLog.log.warn(
                        "[AntiCheat] Discord-Report fehlgeschlagen: {}", e.getMessage());
            }
        });
    }

    // ── HTTP-Logik ───────────────────────────────────────────────────────────

    private static void doSend(String alertReason,
                                String playerName,
                                String serverAddress,
                                InjectionDetector.StatusSnapshot status,
                                List<File> screenshots) throws IOException {

        // ── JSON-Payload aufbauen ─────────────────────────────────────────
        String json = buildJson(alertReason, playerName, serverAddress, status, screenshots);

        // ── HTTP-Verbindung ───────────────────────────────────────────────
        URL url = new URL(webhookUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("Content-Type",
                "multipart/form-data; boundary=" + BOUNDARY);
        conn.setRequestProperty("User-Agent", "AntiCheat/2.0 (+Minecraft-Mod)");

        try (OutputStream out = conn.getOutputStream()) {
            // Teil 1: payload_json
            writeTextPart(out, "payload_json", json);

            // Teil 2..N: Screenshot-Dateien
            for (int i = 0; i < screenshots.size(); i++) {
                File f = screenshots.get(i);
                if (f != null && f.isFile()) {
                    writeFilePart(out, "files[" + i + "]", f);
                }
            }

            // Abschluss-Boundary
            out.write(("--" + BOUNDARY + "--" + CRLF).getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        int code = conn.getResponseCode();
        net.minecraftforge.fml.common.FMLLog.log.info(
                "[AntiCheat] Discord-Report gesendet: HTTP {}", code);
        conn.disconnect();
    }

    // ── JSON-Embed-Builder ───────────────────────────────────────────────────

    private static String buildJson(String alertReason,
                                    String playerName,
                                    String serverAddress,
                                    InjectionDetector.StatusSnapshot status,
                                    List<File> screenshots) {
        String ts = isoNow();
        int deltaClasses = status != null ? status.lastDeltaClasses : 0;
        int deltaThreads = status != null ? status.lastDeltaThreads : 0;
        int baseClasses  = status != null ? status.baselineClasses  : -1;
        int curClasses   = status != null ? status.currentClasses   : -1;

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"username\":\"AntiCheat 2.0\",");
        sb.append("\"avatar_url\":\"https://i.imgur.com/wSTFkRM.png\",");
        sb.append("\"embeds\":[");

        // ── Haupt-Embed ───────────────────────────────────────────────────
        sb.append("{");
        sb.append("\"title\":\"\\u26A0 CHEAT INJECT ERKANNT\",");
        sb.append("\"color\":15158332,");   // Rot #E74C3C
        sb.append("\"description\":\"").append(escJson(alertReason)).append("\",");
        sb.append("\"fields\":[");

        appendField(sb, "Spieler",         playerName    != null ? playerName    : "?", true);
        sb.append(",");
        appendField(sb, "Server",          serverAddress != null ? serverAddress : "?", true);
        sb.append(",");
        appendField(sb, "Klassen-Delta",   "+" + deltaClasses + " (15s-Fenster)", true);
        sb.append(",");
        appendField(sb, "Thread-Delta",    "+" + deltaThreads + " vs Baseline",   true);
        sb.append(",");
        appendField(sb, "Baseline Klassen", String.valueOf(baseClasses), true);
        sb.append(",");
        appendField(sb, "Aktuell Klassen",  String.valueOf(curClasses),  true);

        sb.append("],");
        sb.append("\"timestamp\":\"").append(ts).append("\"");

        // Erstes Screenshot als Embed-Bild
        if (!screenshots.isEmpty() && screenshots.get(0) != null && screenshots.get(0).isFile()) {
            sb.append(",\"image\":{\"url\":\"attachment://").append(screenshots.get(0).getName()).append("\"}");
        }
        sb.append("}");

        // ── Weitere Embeds für 5s / 10s / 30s ────────────────────────────
        String[] labels = { "5 Sekunden", "10 Sekunden", "30 Sekunden" };
        for (int i = 1; i < screenshots.size() && i <= 3; i++) {
            File f = screenshots.get(i);
            if (f == null || !f.isFile()) continue;
            sb.append(",{");
            sb.append("\"color\":15158332,");
            sb.append("\"author\":{\"name\":\"Screenshot nach ").append(labels[i - 1]).append("\"},");
            sb.append("\"image\":{\"url\":\"attachment://").append(f.getName()).append("\"}");
            sb.append("}");
        }

        sb.append("]}");
        return sb.toString();
    }

    private static void appendField(StringBuilder sb, String name, String value, boolean inline) {
        sb.append("{\"name\":\"").append(escJson(name)).append("\",");
        sb.append("\"value\":\"").append(escJson(value)).append("\",");
        sb.append("\"inline\":").append(inline).append("}");
    }

    private static String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String isoNow() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date());
    }

    // ── Multipart-Helfer ─────────────────────────────────────────────────────

    private static void writeTextPart(OutputStream out, String name, String value)
            throws IOException {
        byte[] header = (
                "--" + BOUNDARY + CRLF +
                "Content-Disposition: form-data; name=\"" + name + "\"" + CRLF +
                "Content-Type: application/json; charset=UTF-8" + CRLF +
                CRLF
        ).getBytes(StandardCharsets.UTF_8);
        out.write(header);
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write(CRLF.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFilePart(OutputStream out, String fieldName, File file)
            throws IOException {
        String header =
                "--" + BOUNDARY + CRLF +
                "Content-Disposition: form-data; name=\"" + fieldName + "\";" +
                " filename=\"" + file.getName() + "\"" + CRLF +
                "Content-Type: image/png" + CRLF +
                CRLF;
        out.write(header.getBytes(StandardCharsets.UTF_8));

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = fis.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
        }
        out.write(CRLF.getBytes(StandardCharsets.UTF_8));
    }
}
