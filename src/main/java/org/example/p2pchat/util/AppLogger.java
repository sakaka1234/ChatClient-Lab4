package org.example.p2pchat.util;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public final class AppLogger {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final boolean QUIET = Boolean.getBoolean("p2pchat.quiet");

    private AppLogger() {
    }

    public static void info(String message) {
        log("INFO", message);
    }

    public static void warn(String message) {
        log("WARN", message);
    }

    public static void error(String message) {
        log("ERROR", message);
    }

    public static void error(String message, Throwable cause) {
        log("ERROR", message + " -> " + cause);
        if (!QUIET && Boolean.getBoolean("p2pchat.debug")) {
            cause.printStackTrace(System.err);
        }
    }

    private static void log(String level, String message) {
        if (QUIET) {
            return;
        }
        System.out.println("[" + level + "] " + LocalTime.now().format(TIME) + " " + message);
    }
}
