package com.anticheat.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Fängt {@link System#out} / {@link System#err}. Wichtig: {@link PrintStream} nutzt häufig
 * {@link OutputStream#write(byte[], int, int)} – nur {@code write(int)} abzufangen reicht nicht.
 */
public final class ConsoleSniffer {

    private static PrintStream sniffingOut;
    private static PrintStream sniffingErr;
    private static final Charset CS = StandardCharsets.UTF_8;

    private ConsoleSniffer() {
    }

    public static synchronized void install() {
        if (sniffingOut == null) {
            sniffingOut = wrapStream(System.out);
            sniffingErr = wrapStream(System.err);
            System.setOut(sniffingOut);
            System.setErr(sniffingErr);
        }
    }

    public static synchronized void reattachIfNeeded() {
        if (System.out != sniffingOut) {
            sniffingOut = wrapStream(System.out);
            System.setOut(sniffingOut);
        }
        if (System.err != sniffingErr) {
            sniffingErr = wrapStream(System.err);
            System.setErr(sniffingErr);
        }
    }

    private static PrintStream wrapStream(final PrintStream delegate) {
        final ByteArrayOutputStream lineBuf = new ByteArrayOutputStream(512);
        OutputStream os = new SniffOutputStream(delegate, lineBuf);
        try {
            return new PrintStream(os, true, CS.name());
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private static final class SniffOutputStream extends OutputStream {
        private final PrintStream delegate;
        private final ByteArrayOutputStream lineBuf;

        SniffOutputStream(PrintStream delegate, ByteArrayOutputStream lineBuf) {
            this.delegate = delegate;
            this.lineBuf = lineBuf;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            delegate.write(b);
            processByte(b & 0xFF);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            int end = off + len;
            for (int i = off; i < end; i++) {
                processByte(b[i] & 0xFF);
            }
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        private void processByte(int b) {
            if (b == '\n') {
                if (lineBuf.size() > 0) {
                    String s = new String(lineBuf.toByteArray(), CS);
                    lineBuf.reset();
                    CheatDetector.inspectRawLine(s, System.currentTimeMillis());
                }
            } else if (b != '\r') {
                lineBuf.write(b);
            }
        }
    }
}
