package org.example.p2pchat.util;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

public final class NetworkUtils {

    public static final int MIN_PORT = 1;
    public static final int MAX_PORT = 65535;

    private NetworkUtils() {
    }

    public static void validatePort(int port) {
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException("Port must be between " + MIN_PORT + " and " + MAX_PORT + ": " + port);
        }
    }

    public static void validateListeningPort(int port) {
        if (port < 0 || port > MAX_PORT) {
            throw new IllegalArgumentException("Listening port must be between 0 and " + MAX_PORT + ": " + port);
        }
    }

    public static InetAddress resolveAddress(String host) throws IOException {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Host must not be empty");
        }
        try {
            return InetAddress.getByName(host.trim());
        } catch (UnknownHostException e) {
            throw new IOException("Invalid or unknown address: " + host, e);
        }
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format("%.2f %s", value, units[unit]);
    }
}
