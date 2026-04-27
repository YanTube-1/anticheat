package com.anticheat.client;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Liest eine Log-Datei fortlaufend (nur neue Bytes ab Start).
 */
public final class LatestLogTailer implements Runnable {

    private static final long POLL_MS = 150L;
    private static final int READ_CHUNK = 1 << 16;

    private final Path logFile;
    private volatile boolean stopped;

    public LatestLogTailer(Path logFile) {
        this.logFile = logFile;
    }

    public void stop() {
        stopped = true;
    }

    @Override
    public void run() {
        long position = 0;
        try {
            if (Files.isRegularFile(logFile)) {
                position = Files.size(logFile);
            }
        } catch (IOException ignored) {
        }

        StringBuilder pending = new StringBuilder();

        while (!stopped) {
            try {
                if (Files.isRegularFile(logFile)) {
                    try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
                        long len = raf.length();
                        if (len < position) {
                            position = 0;
                            pending.setLength(0);
                        }
                        if (len > position) {
                            raf.seek(position);
                            int toRead = (int) Math.min(READ_CHUNK, len - position);
                            byte[] buf = new byte[toRead];
                            int n = raf.read(buf);
                            if (n > 0) {
                                position += n;
                                pending.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                                drainCompleteLines(pending);
                            }
                        }
                    }
                }
            } catch (IOException ignored) {
            }

            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static void drainCompleteLines(StringBuilder pending) {
        long now = System.currentTimeMillis();
        int i = 0;
        while (i < pending.length()) {
            if (pending.charAt(i) == '\n') {
                String line = pending.substring(0, i);
                pending.delete(0, i + 1);
                i = 0;
                CheatDetector.inspectRawLine(line, now);
                continue;
            }
            i++;
        }
    }
}
