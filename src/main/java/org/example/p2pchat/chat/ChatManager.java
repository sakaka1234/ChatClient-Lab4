package org.example.p2pchat.chat;

import org.example.p2pchat.network.PacketSink;
import org.example.p2pchat.protocol.MessageType;
import org.example.p2pchat.protocol.Packet;
import org.example.p2pchat.util.AppLogger;

import java.io.IOException;
import java.util.Objects;

public final class ChatManager {

    private final PacketSink sink;
    private final ChatListener listener;

    public ChatManager(PacketSink sink, ChatListener listener) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public void sendMessage(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        try {
            AppLogger.info("Sending CHAT packet");
            sink.send(Packet.chat(message.strip()));
        } catch (IOException e) {
            reportError("Failed to send message: " + describe(e));
        }
    }

    /**
     * Handles an incoming text packet.
     *
     * @param packet a {@code CHAT} (sender supplied by the router) or {@code RELAY} packet, whose
     *               payload already carries the original sender's display name
     * @param sender display name to attribute a plain {@code CHAT} to; ignored for {@code RELAY}
     */
    public void handle(Packet packet, String sender) {
        try {
            String name;
            String message;
            if (packet.type() == MessageType.RELAY) {
                name = packet.relaySender();
                message = packet.relayText();
            } else {
                name = sender;
                message = packet.chatText();
            }
            AppLogger.info("Receiving CHAT packet");
            listener.onChatMessage(name, message);
        } catch (RuntimeException e) {
            reportError("Received invalid chat packet: " + describe(e));
        }
    }

    private void reportError(String reason) {
        try {
            listener.onChatError(reason);
        } catch (RuntimeException e) {
            AppLogger.error("Chat listener failed in onChatError", e);
        }
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.toString() : message;
    }
}
