package com.anticheat.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

/**
 * Mod {@code dd}, Klassen {@code net.java.*} (mehrere ClassLoader), Stacktrace-Scan.
 */
public final class DoomsdayRuntimeProbe {

    private static final String TARGET_MOD_ID = "dd";

    private static final String[] CLASS_NAMES = {
            "net.java.h",
            "net.java.i",
            "net.java.y",
            "net.java.l",
            "net.java.m",
            "net.java.f",
            "net.java.g",
            "net.java.k",
            "net.java.r",
            "net.java.s",
            "net.java.t",
    };

    private DoomsdayRuntimeProbe() {
    }

    public static void runAll() {
        scanModList();
        scanClassesAllLoaders();
        scanThreadStacks();
    }

    /**
     * Nur für Befehle / Selbsttest: keine {@link CheatDetector#triggerRuntimeCheckAlert()}-Aufrufe.
     *
     * @param verbose zusätzliche Loader-Namen
     */
    public static List<String> collectDiagnosticLines(boolean verbose) {
        List<String> lines = new ArrayList<String>();
        lines.add("Forge-Mod id=dd jetzt geladen: " + (isModDdPresent() ? "ja" : "nein"));
        List<String> loaded = listLoadedTargetClasses();
        if (loaded.isEmpty()) {
            lines.add("Bekannte net.java.*-Klassen (interne Signatur-Liste): aktuell keine geladen");
        } else {
            lines.add("Bekannte net.java.*-Klassen: " + String.join(", ", loaded));
        }
        lines.add("Stacktrace-Zeilen mit net.java.*: " + countNetJavaStackHits());
        lines.add("(Nur Momentaufnahme; Cheat kann fehlen oder spaeter erscheinen.)");
        if (verbose) {
            lines.add("ClassLoader: " + describeLoaders());
        }
        return lines;
    }

    private static boolean isModDdPresent() {
        try {
            for (ModContainer c : Loader.instance().getModList()) {
                if (TARGET_MOD_ID.equalsIgnoreCase(c.getModId())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static List<String> listLoadedTargetClasses() {
        List<String> found = new ArrayList<String>();
        for (ClassLoader cl : classLoaders()) {
            if (cl == null) {
                continue;
            }
            String tag = shortLoaderId(cl);
            for (String name : CLASS_NAMES) {
                try {
                    Class.forName(name, false, cl);
                    found.add(name + "@" + tag);
                } catch (ClassNotFoundException ok) {
                } catch (Throwable t) {
                    found.add(name + "@" + tag + "(Fehler:" + t.getClass().getSimpleName() + ")");
                }
            }
        }
        return found;
    }

    private static int countNetJavaStackHits() {
        int n = 0;
        try {
            for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                for (StackTraceElement ste : e.getValue()) {
                    String cn = ste.getClassName();
                    if (cn != null && cn.startsWith("net.java.")) {
                        n++;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    private static String shortLoaderId(ClassLoader cl) {
        if (cl == null) {
            return "?";
        }
        String n = cl.getClass().getSimpleName();
        if (n == null || n.isEmpty()) {
            n = "CL";
        }
        return n + "@" + Integer.toHexString(System.identityHashCode(cl));
    }

    private static String describeLoaders() {
        StringBuilder sb = new StringBuilder();
        List<ClassLoader> loaders = classLoaders();
        for (int i = 0; i < loaders.size(); i++) {
            ClassLoader cl = loaders.get(i);
            if (i > 0) {
                sb.append("; ");
            }
            if (cl == null) {
                sb.append("null");
            } else {
                sb.append(cl.getClass().getName()).append('#').append(Integer.toHexString(System.identityHashCode(cl)));
            }
        }
        return sb.length() == 0 ? "(keine)" : sb.toString();
    }

    private static void scanModList() {
        try {
            for (ModContainer c : Loader.instance().getModList()) {
                if (TARGET_MOD_ID.equalsIgnoreCase(c.getModId())) {
                    CheatDetector.triggerRuntimeCheckAlert();
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void scanClassesAllLoaders() {
        for (ClassLoader cl : classLoaders()) {
            if (cl == null) {
                continue;
            }
            for (String name : CLASS_NAMES) {
                try {
                    Class.forName(name, false, cl);
                    CheatDetector.triggerRuntimeCheckAlert();
                    return;
                } catch (ClassNotFoundException ok) {
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static List<ClassLoader> classLoaders() {
        List<ClassLoader> out = new ArrayList<ClassLoader>(4);
        out.add(DoomsdayRuntimeProbe.class.getClassLoader());
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx != null) {
            out.add(ctx);
        }
        ClassLoader launch = launchClassLoader();
        if (launch != null) {
            out.add(launch);
        }
        return out;
    }

    private static ClassLoader launchClassLoader() {
        try {
            Class<?> launch = Class.forName(
                    "net.minecraft.launchwrapper.Launch",
                    false,
                    DoomsdayRuntimeProbe.class.getClassLoader());
            Object cl = launch.getField("classLoader").get(null);
            return (ClassLoader) cl;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Geladene Cheat-Klassen tauchen oft kurz in beliebigen Threads auf. */
    private static void scanThreadStacks() {
        try {
            for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                for (StackTraceElement ste : e.getValue()) {
                    String cn = ste.getClassName();
                    if (cn != null && cn.startsWith("net.java.")) {
                        CheatDetector.triggerRuntimeCheckAlert();
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
