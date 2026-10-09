package org.example.p2pchat.protocol;

import java.io.IOException;

/**
 * The packet kinds of protocol v5. Ids are stable: adding a kind takes the next free id, and an id
 * is never reused for a different kind, so a client running the same build always agrees on the byte.
 */
public enum MessageType {

    CHAT(1),
    DISCONNECT(2),
    HELLO(3),
    RELAY(4),
    ROSTER(5);

    private final int id;

    MessageType(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static MessageType fromId(int id) throws IOException {
        for (MessageType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        throw new IOException("Unknown message type: " + id);
    }
}
