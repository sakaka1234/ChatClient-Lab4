package org.example.p2pchat.protocol;

import java.io.IOException;

/**
 * Who a chat message is addressed to.
 *
 * <p>The three constants mirror the three delivery scopes of a network: {@link #UNICAST} names one
 * client, {@link #MULTICAST} names a chosen group, and {@link #BROADCAST} names everyone. The scope
 * travels on the wire inside every {@code CHAT} and {@code RELAY} packet, so the server knows which
 * sockets to replicate the message onto and the receiver knows how the message was addressed.
 */
public enum ChatScope {

    /** Exactly one recipient. */
    UNICAST(0),

    /** A chosen group of recipients (two or more). */
    MULTICAST(1),

    /** Every client of the session except the sender. */
    BROADCAST(2);

    private final int id;

    ChatScope(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public boolean isBroadcast() {
        return this == BROADCAST;
    }

    public static ChatScope fromId(int id) throws IOException {
        for (ChatScope scope : values()) {
            if (scope.id == id) {
                return scope;
            }
        }
        throw new IOException("Unknown chat scope: " + id);
    }
}
