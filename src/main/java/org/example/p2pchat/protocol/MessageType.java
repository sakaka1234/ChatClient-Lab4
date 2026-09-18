package org.example.p2pchat.protocol;

import java.io.IOException;

public enum MessageType {

    CHAT(1),
    FILE_START(2),
    FILE_CHUNK(3),
    FILE_END(4),
    DISCONNECT(5),
    FILE_ACCEPT(6),
    FILE_DECLINE(7),
    HELLO(8),
    RELAY(9);

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
