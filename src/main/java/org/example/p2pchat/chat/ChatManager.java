package org.example.p2pchat.chat;

import org.example.p2pchat.network.PacketSink;
import org.example.p2pchat.protocol.ChatScope;
import org.example.p2pchat.protocol.MessageType;
import org.example.p2pchat.protocol.Packet;
import org.example.p2pchat.util.AppLogger;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Turns chat text into {@code CHAT} packets and decoded packets back into listener callbacks.
 *
 * <p>It knows nothing about who is connected or how a message is routed: the addressing block is written
 * as given and read as
 * received. Deciding which sockets a message actually travels on is the session's job.
 */
public final class ChatManager {

    private final PacketSink sink;
    private final ChatListener listener;

    public ChatManager(PacketSink sink, ChatListener listener) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /** Sends to every other client. */
    public void sendMessage(String message) {
        sendMessage(message, ChatScope.BROADCAST, List.of());
    }

    /**
     * @param scope   {@link ChatScope#UNICAST} (one target), {@link ChatScope#MULTICAST} (two or
     *                more) or {@link ChatScope#BROADCAST} (no targets)
     * @param targets client ids the message is addressed to
     */
    public void sendMessage(String message, ChatScope scope, List<Integer> targets) {
        if (message == null || message.isBlank()) {
            return;
        }
        try {
            AppLogger.info("Sending " + scope + " CHAT packet");
            sink.send(Packet.chat(scope, targets, message.strip()));
        } catch (IOException e) {
            reportError("Failed to send message: " + describe(e));
        } catch (IllegalArgumentException e) {
            reportError("Invalid chat address: " + describe(e));
        }
    }

    /**
     * Handles an incoming text packet.
     *
     * @param packet a {@code CHAT} (sender supplied by the router) or {@code RELAY} packet, whose
     *               payload already carries the original sender's display name and addressing block
     * @param sender display name to attribute a plain {@code CHAT} to; ignored for {@code RELAY}
     */
    public void handle(Packet packet, String sender) {
        try {
            String name;
            String message;
            ChatScope scope;
            List<Integer> targets;
            if (packet.type() == MessageType.RELAY) {
                name = packet.relaySender();
                scope = packet.relayScope();
                targets = packet.relayTargets();
                message = packet.relayText();
            } else {
                name = sender;
                scope = packet.chatScope();
                targets = packet.chatTargets();
                message = packet.chatText();
            }
            AppLogger.info("Receiving " + scope + " CHAT packet");
            listener.onChatMessage(name, message, scope, targets);
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
