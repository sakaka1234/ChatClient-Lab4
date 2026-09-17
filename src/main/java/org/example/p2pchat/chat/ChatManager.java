package org.example.p2pchat.chat;

import org.example.p2pchat.network.PacketSink;
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

    public void handle(Packet packet) {
        try {
            String message = packet.chatText();
            AppLogger.info("Receiving CHAT packet");
            listener.onChatMessage(message);
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
