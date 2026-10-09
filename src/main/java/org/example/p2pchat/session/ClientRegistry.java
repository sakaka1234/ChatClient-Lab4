package org.example.p2pchat.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Ordered registry of the connections currently attached to a session, keyed by their transport
 * handle (a {@code Connection} in production). A server holds one per client; a client holds the
 * single connection to its server.
 *
 * <p>All access is synchronized so an accept thread can register a connection while reader threads
 * remove one. Read methods return snapshots so callers can iterate without holding the lock.
 */
public final class ClientRegistry<T> {

    public static final String DEFAULT_NAME = "Client";

    private final Map<T, String> names = new LinkedHashMap<>();

    public synchronized void add(T connection, String name) {
        Objects.requireNonNull(connection, "connection");
        names.put(connection, normalizeName(name));
    }

    /**
     * Registers {@code connection} only if it is not known yet. Used when a connection is wired up
     * after its {@code HELLO} may already have been read, so a late registration does not overwrite
     * the learned display name.
     */
    public synchronized void addIfAbsent(T connection, String name) {
        Objects.requireNonNull(connection, "connection");
        names.putIfAbsent(connection, normalizeName(name));
    }

    public synchronized boolean remove(T connection) {
        return names.remove(connection) != null;
    }

    public synchronized boolean contains(T connection) {
        return names.containsKey(connection);
    }

    public synchronized int size() {
        return names.size();
    }

    public synchronized List<T> connections() {
        return new ArrayList<>(names.keySet());
    }

    public synchronized List<T> connectionsExcept(T excluded) {
        List<T> result = new ArrayList<>();
        for (T connection : names.keySet()) {
            if (!connection.equals(excluded)) {
                result.add(connection);
            }
        }
        return result;
    }

    public synchronized T primary() {
        return names.isEmpty() ? null : names.keySet().iterator().next();
    }

    public synchronized String nameOf(T connection) {
        return names.getOrDefault(connection, DEFAULT_NAME);
    }

    private static String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            return DEFAULT_NAME;
        }
        return name.strip();
    }
}
