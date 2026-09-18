package org.example.p2pchat.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Ordered registry of the peers currently attached to a session, keyed by their transport handle
 * (a {@code Connection} in production). Host and client both use it; the host may hold many peers.
 *
 * <p>All access is synchronized so an accept thread can register a peer while reader threads remove
 * one. Read methods return snapshots so callers can iterate without holding the lock.
 */
public final class PeerHub<T> {

    public static final String DEFAULT_NAME = "Peer";

    private final Map<T, String> names = new LinkedHashMap<>();

    public synchronized void add(T peer, String name) {
        Objects.requireNonNull(peer, "peer");
        names.put(peer, normalizeName(name));
    }

    /**
     * Registers {@code peer} only if it is not known yet. Used when a connection is wired up after
     * the peer's {@code HELLO} may already have been read, so a late registration does not overwrite
     * the learned display name.
     */
    public synchronized void addIfAbsent(T peer, String name) {
        Objects.requireNonNull(peer, "peer");
        names.putIfAbsent(peer, normalizeName(name));
    }

    public synchronized boolean remove(T peer) {
        return names.remove(peer) != null;
    }

    public synchronized boolean contains(T peer) {
        return names.containsKey(peer);
    }

    public synchronized int size() {
        return names.size();
    }

    public synchronized List<T> peers() {
        return new ArrayList<>(names.keySet());
    }

    public synchronized List<T> peersExcept(T excluded) {
        List<T> result = new ArrayList<>();
        for (T peer : names.keySet()) {
            if (!peer.equals(excluded)) {
                result.add(peer);
            }
        }
        return result;
    }

    public synchronized T primary() {
        return names.isEmpty() ? null : names.keySet().iterator().next();
    }

    public synchronized String nameOf(T peer) {
        return names.getOrDefault(peer, DEFAULT_NAME);
    }

    private static String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            return DEFAULT_NAME;
        }
        return name.strip();
    }
}
