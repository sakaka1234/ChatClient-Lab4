package org.example.p2pchat.chat;

import org.example.p2pchat.protocol.ChatScope;
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
        final List<String> addressed = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        @Override
        public void onChatMessage(String sender, String message) {
            messages.add(sender + ":" + message);
        }

        @Override
        public void onChatMessage(String sender, String message, ChatScope scope, List<Integer> targets) {
            onChatMessage(sender, message);
            addressed.add(scope + ":" + targets + ":" + message);
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
    void deliversIncomingChatToListenerWithTheGivenSender() {
        RecordingListener listener = new RecordingListener();
        ChatManager manager = new ChatManager(new CollectingSink(), listener);

        manager.handle(Packet.chat("hi from client"), "An");

        assertEquals(List.of("An:hi from client"), listener.messages);
    }

    @Test
    void deliversRelayPacketUsingTheNameInsideThePacket() {
        RecordingListener listener = new RecordingListener();
        ChatManager manager = new ChatManager(new CollectingSink(), listener);

        manager.handle(Packet.relay("Binh", "xin chao"), "ignored");

        assertEquals(List.of("Binh:xin chao"), listener.messages);
    }

    @Test
    void rejectsNonChatPacket() {
        RecordingListener listener = new RecordingListener();
        ChatManager manager = new ChatManager(new CollectingSink(), listener);

        manager.handle(Packet.hello("An"), "An");

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
            public void onChatMessage(String sender, String message) {
                throw new RuntimeException("listener bug");
            }

            @Override
            public void onChatError(String reason) {
                throw new RuntimeException("listener bug");
            }
        };
        ChatManager manager = new ChatManager(new CollectingSink(), exploding);

        manager.handle(Packet.chat("boom"), "An");
    }

    @Test
    void rejectsNullListener() {
        assertThrows(NullPointerException.class, () -> new ChatManager(new CollectingSink(), null));
    }

    // ------------------------------------------------------------- addressing

    @Test
    void sendsTheAddressingBlockItWasGiven() throws IOException {
        CollectingSink sink = new CollectingSink();
        ChatManager manager = new ChatManager(sink, new RecordingListener());

        manager.sendMessage("chi rieng", ChatScope.UNICAST, List.of(3));

        assertEquals(1, sink.size());
        assertEquals(ChatScope.UNICAST, sink.packet(0).chatScope());
        assertEquals(List.of(3), sink.packet(0).chatTargets());
        assertEquals("chi rieng", sink.packet(0).chatText());
    }

    @Test
    void aOneArgumentSendIsABroadcast() throws IOException {
        CollectingSink sink = new CollectingSink();
        ChatManager manager = new ChatManager(sink, new RecordingListener());

        manager.sendMessage("ca nha oi");

        assertEquals(ChatScope.BROADCAST, sink.packet(0).chatScope());
        assertEquals(List.of(), sink.packet(0).chatTargets());
    }

    @Test
    void anAddressThatContradictsItsScopeIsReportedAndNotSent() throws IOException {
        CollectingSink sink = new CollectingSink();
        RecordingListener listener = new RecordingListener();
        ChatManager manager = new ChatManager(sink, listener);

        manager.sendMessage("hi", ChatScope.UNICAST, List.of());          // a unicast with no target
        manager.sendMessage("hi", ChatScope.UNICAST, List.of(2, 3));      // a unicast with two
        manager.sendMessage("hi", ChatScope.MULTICAST, List.of(2));       // a multicast with one

        assertEquals(0, sink.size(), "a message with an impossible address must never be sent");
        assertEquals(3, listener.errors.size());
        assertTrue(listener.errors.get(0).contains("Invalid chat address"));
    }

    @Test
    void anIncomingChatReachesTheListenerWithItsAddressing() {
        RecordingListener listener = new RecordingListener();
        ChatManager manager = new ChatManager(new CollectingSink(), listener);

        manager.handle(Packet.chat(ChatScope.MULTICAST, List.of(1, 3), "hop nhom"), "An");

        assertEquals(List.of("MULTICAST:[1, 3]:hop nhom"), listener.addressed);
    }

    @Test
    void anIncomingRelayKeepsTheAddressingItForwarded() {
        RecordingListener listener = new RecordingListener();
        ChatManager manager = new ChatManager(new CollectingSink(), listener);

        manager.handle(Packet.relay("Binh", ChatScope.UNICAST, List.of(2), "chi rieng"), "ignored");

        assertEquals(List.of("Binh:chi rieng"), listener.messages);
        assertEquals(List.of("UNICAST:[2]:chi rieng"), listener.addressed);
    }

    @Test
    void aMalformedIncomingChatIsReportedInsteadOfThrown() {
        RecordingListener listener = new RecordingListener();
        ChatManager manager = new ChatManager(new CollectingSink(), listener);

        manager.handle(Packet.of(MessageType.CHAT, new byte[]{9, 0, 0, 'h', 'i'}), "An");

        assertEquals(List.of(), listener.messages);
        assertEquals(1, listener.errors.size());
        assertTrue(listener.errors.get(0).contains("invalid chat packet"));
    }
}
