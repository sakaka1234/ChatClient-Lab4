package org.example.p2pchat.chat;

import org.example.p2pchat.protocol.MessageType;
import org.example.p2pchat.protocol.Packet;
import org.example.p2pchat.support.CollectingSink;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatManagerTest {

    private static final class RecordingListener implements ChatListener {
        final List<String> messages = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        @Override
        public void onChatMessage(String message) {
            messages.add(message);
        }

        @Override
        public void onChatError(String reason) {
            errors.add(reason);
        }
    }

    @Test
    void sendsTrimmedChatPacket() throws IOException {
        CollectingSink sink = new CollectingSink();
        ChatManager manager = new ChatManager(sink, new RecordingListener());

        manager.sendMessage("  Hello there  ");

        assertEquals(1, sink.size());
        assertEquals(MessageType.CHAT, sink.packet(0).type());
        assertEquals("Hello there", sink.packet(0).chatText());
    }

    @Test
    void ignoresBlankMessage() throws IOException {
        CollectingSink sink = new CollectingSink();
        ChatManager manager = new ChatManager(sink, new RecordingListener());

        manager.sendMessage("   ");
        manager.sendMessage("");
        manager.sendMessage(null);

        assertEquals(0, sink.size());
    }

    @Test
    void deliversIncomingChatToListener() {
        RecordingListener listener = new RecordingListener();
        ChatManager manager = new ChatManager(new CollectingSink(), listener);

        manager.handle(Packet.chat("hi from peer"));

        assertEquals(List.of("hi from peer"), listener.messages);
    }

    @Test
    void rejectsNonChatPacket() {
        RecordingListener listener = new RecordingListener();
        ChatManager manager = new ChatManager(new CollectingSink(), listener);

        manager.handle(Packet.fileEnd(1));

        assertEquals(1, listener.errors.size());
    }

    @Test
    void reportsSendFailureToListener() {
        CollectingSink sink = new CollectingSink();
        sink.failWith(new IOException("socket closed"));
        RecordingListener listener = new RecordingListener();
        ChatManager manager = new ChatManager(sink, listener);

        manager.sendMessage("hello");

        assertEquals(1, listener.errors.size());
        assertTrue(listener.errors.get(0).contains("socket closed"));
    }

    @Test
    void listenerExceptionsOnlyReportedToErrors() {
        ChatListener exploding = new ChatListener() {
            @Override
            public void onChatMessage(String message) {
                throw new RuntimeException("listener bug");
            }

            @Override
            public void onChatError(String reason) {
                throw new RuntimeException("listener bug");
            }
        };
        ChatManager manager = new ChatManager(new CollectingSink(), exploding);

        manager.handle(Packet.chat("boom"));
    }

    @Test
    void rejectsNullListener() {
        assertThrows(NullPointerException.class, () -> new ChatManager(new CollectingSink(), null));
    }
}
