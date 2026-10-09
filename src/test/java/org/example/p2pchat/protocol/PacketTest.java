package org.example.p2pchat.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PacketTest {

    @Test
    void chatPacketKeepsText() {
        Packet packet = Packet.chat("Hello");

        assertEquals(MessageType.CHAT, packet.type());
        assertEquals("Hello", packet.chatText());
    }

    @Test
    void chatPacketKeepsUtf8Text() {
        String text = "Xin chao, b\u1ea1n kh\u1ecfe kh\u00f4ng? \uD83D\uDC4B";
        Packet packet = Packet.chat(text);

        assertEquals(text, packet.chatText());
        byte[] payload = packet.payload();
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        // payload = broadcast addressing block (3 bytes) followed by the raw UTF-8 text
        assertEquals(3 + textBytes.length, payload.length);
        assertArrayEquals(textBytes, Arrays.copyOfRange(payload, 3, payload.length));
    }

    @Test
    void disconnectPacketHasEmptyPayload() {
        Packet packet = Packet.disconnect();

        assertEquals(MessageType.DISCONNECT, packet.type());
        assertEquals(0, packet.payload().length);
    }

    @Test
    void chatTextRejectsNonChatPacket() {
        Packet packet = Packet.hello("An");

        assertThrows(IllegalStateException.class, packet::chatText);
    }

    @Test
    void messageTypeIdsAreStable() throws Exception {
        assertEquals(1, MessageType.CHAT.id());
        assertEquals(2, MessageType.DISCONNECT.id());
        assertEquals(3, MessageType.HELLO.id());
        assertEquals(4, MessageType.RELAY.id());
        assertEquals(5, MessageType.ROSTER.id());
    }

    @Test
    void helloPacketKeepsName() {
        Packet packet = Packet.hello("An");

        assertEquals(MessageType.HELLO, packet.type());
        assertEquals("An", packet.helloName());
    }

    @Test
    void helloPacketKeepsUnicodeName() {
        Packet packet = Packet.hello("B\u00ecnh");

        assertEquals("B\u00ecnh", packet.helloName());
    }

    @Test
    void relayPacketKeepsSenderAndText() {
        Packet packet = Packet.relay("An", "xin chao ca nha");

        assertEquals(MessageType.RELAY, packet.type());
        assertEquals("An", packet.relaySender());
        assertEquals("xin chao ca nha", packet.relayText());
    }

    @Test
    void relayPacketKeepsUnicodeTextAndSender() {
        Packet packet = Packet.relay("C\u01b0\u1eddng", "\u0111ang \u1edf \u0111\u00e2y \uD83D\uDC4B");

        assertEquals("C\u01b0\u1eddng", packet.relaySender());
        assertEquals("\u0111ang \u1edf \u0111\u00e2y \uD83D\uDC4B", packet.relayText());
    }

    @Test
    void relaySenderRejectsNonRelayPacket() {
        assertThrows(IllegalStateException.class, () -> Packet.chat("hi").relaySender());
    }

    // ------------------------------------------------------------- addressing

    @Test
    void broadcastChatCarriesNoTargets() {
        Packet packet = Packet.chat("hi");

        assertEquals(ChatScope.BROADCAST, packet.chatScope());
        assertEquals(List.of(), packet.chatTargets());
    }

    @Test
    void unicastChatCarriesExactlyOneTarget() {
        Packet packet = Packet.chat(ChatScope.UNICAST, List.of(2), "chi rieng");

        assertEquals(ChatScope.UNICAST, packet.chatScope());
        assertEquals(List.of(2), packet.chatTargets());
        assertEquals("chi rieng", packet.chatText());
    }

    @Test
    void multicastChatCarriesEveryTarget() {
        Packet packet = Packet.chat(ChatScope.MULTICAST, List.of(1, 3, 4), "hop nhom");

        assertEquals(ChatScope.MULTICAST, packet.chatScope());
        assertEquals(List.of(1, 3, 4), packet.chatTargets());
    }

    @Test
    void addressingBlockIsScopeThenCountThenTargets() {
        Packet packet = Packet.chat(ChatScope.MULTICAST, List.of(1, 300), "x");

        assertArrayEquals(new byte[]{
                (byte) ChatScope.MULTICAST.id(), 0, 2,          // scope, count = 2
                0, 1,                                           // target 1
                1, 44,                                          // target 300
                'x'}, packet.payload());
    }

    @Test
    void chatPacketRejectsScopeThatDisagreesWithItsTargets() {
        assertThrows(IllegalArgumentException.class, () -> Packet.chat(ChatScope.BROADCAST, List.of(1), "hi"));
        assertThrows(IllegalArgumentException.class, () -> Packet.chat(ChatScope.UNICAST, List.of(), "hi"));
        assertThrows(IllegalArgumentException.class,
                () -> Packet.chat(ChatScope.UNICAST, List.of(1, 2), "hi"));
        assertThrows(IllegalArgumentException.class, () -> Packet.chat(ChatScope.MULTICAST, List.of(1), "hi"));
    }

    @Test
    void chatPacketRejectsUnreachableOrTooManyTargets() {
        assertThrows(IllegalArgumentException.class, () -> Packet.chat(ChatScope.UNICAST, List.of(0), "hi"));
        assertThrows(IllegalArgumentException.class, () -> Packet.chat(ChatScope.UNICAST, List.of(70_000), "hi"));

        List<Integer> tooMany = java.util.stream.IntStream.rangeClosed(1, Packet.MAX_CHAT_TARGETS + 1)
                .boxed().toList();
        assertThrows(IllegalArgumentException.class, () -> Packet.chat(ChatScope.MULTICAST, tooMany, "hi"));
    }

    @Test
    void relayKeepsTheAddressingBlockItForwarded() {
        Packet packet = Packet.relay("An", ChatScope.UNICAST, List.of(4), "chi rieng");

        assertEquals("An", packet.relaySender());
        assertEquals(ChatScope.UNICAST, packet.relayScope());
        assertEquals(List.of(4), packet.relayTargets());
        assertEquals("chi rieng", packet.relayText());
    }

    @Test
    void relaySenderLengthIsIndependentOfTheNameAlphabet() {
        // Two-byte UTF-8 characters must be counted in bytes, not characters.
        Packet packet = Packet.relay("Cường", ChatScope.MULTICAST, List.of(2, 3), "chào");

        assertEquals("Cường", packet.relaySender());
        assertEquals(List.of(2, 3), packet.relayTargets());
        assertEquals("chào", packet.relayText());
    }

    @Test
    void chatAddressingRejectsATruncatedHeader() {
        assertThrows(IllegalStateException.class,
                () -> Packet.of(MessageType.CHAT, new byte[]{1, 0}).chatScope());
    }

    @Test
    void chatAddressingRejectsAnUnknownScope() {
        Packet packet = Packet.of(MessageType.CHAT, new byte[]{9, 0, 0, 'h', 'i'});

        assertThrows(IllegalStateException.class, packet::chatScope);
    }

    @Test
    void chatAddressingRejectsATargetCountThatOverflowsThePayload() {
        // claims 5 targets but carries none
        Packet packet = Packet.of(MessageType.CHAT, new byte[]{(byte) ChatScope.MULTICAST.id(), 0, 5, 'h'});

        assertThrows(IllegalStateException.class, packet::chatScope);
    }

    @Test
    void chatAddressingRejectsACountAboveTheLimit() {
        Packet packet = Packet.of(MessageType.CHAT, new byte[]{(byte) ChatScope.MULTICAST.id(), 0, 127, 'h'});

        assertThrows(IllegalStateException.class, packet::chatScope);
    }

    // ----------------------------------------------------------------- roster

    // ----------------------------------------------- byte dumps in PROTOCOL.md

    @Test
    void unicastChatPayloadMatchesTheDocumentedDump() {
        // PROTOCOL.md §7: CHAT(UNICAST, [3], "Hi") -> 7 payload bytes
        Packet packet = Packet.chat(ChatScope.UNICAST, List.of(3), "Hi");

        assertArrayEquals(new byte[]{0, 0, 1, 0, 3, 'H', 'i'}, packet.payload());
        assertEquals(7, packet.payload().length);
    }

    @Test
    void relayPayloadMatchesTheDocumentedDump() {
        // PROTOCOL.md §7: RELAY("An", UNICAST, [2], "hi") -> 11 payload bytes
        Packet packet = Packet.relay("An", ChatScope.UNICAST, List.of(2), "hi");

        assertArrayEquals(new byte[]{
                0, 2, 'A', 'n',                       // sender length = 2, then "An"
                0, 0, 1, 0, 2,                        // UNICAST, count = 1, target #2
                'h', 'i'}, packet.payload());
        assertEquals(11, packet.payload().length);
    }

    @Test
    void rosterPayloadMatchesTheDocumentedDump() {
        // PROTOCOL.md §7: ROSTER(self=2, [(1,"Host"),(2,"An")]) -> 18 payload bytes
        Packet packet = Packet.roster(2, List.of(
                new Packet.RosterEntry(1, "Host"),
                new Packet.RosterEntry(2, "An")));

        assertArrayEquals(new byte[]{
                0, 2,                                 // self id = 2
                0, 2,                                 // two entries
                0, 1, 0, 4, 'H', 'o', 's', 't',
                0, 2, 0, 2, 'A', 'n'}, packet.payload());
        assertEquals(18, packet.payload().length);
    }

    @Test
    void rosterPacketKeepsSelfIdAndEntries() {
        Packet packet = Packet.roster(3, List.of(
                new Packet.RosterEntry(1, "Host"),
                new Packet.RosterEntry(3, "An")));

        assertEquals(MessageType.ROSTER, packet.type());
        assertEquals(3, packet.rosterSelfId());
        assertEquals(List.of(new Packet.RosterEntry(1, "Host"), new Packet.RosterEntry(3, "An")),
                packet.rosterEntries());
    }

    @Test
    void rosterEntryNameLengthIsCountedInBytes() {
        Packet packet = Packet.roster(1, List.of(new Packet.RosterEntry(1, "Bình")));

        assertArrayEquals(new byte[]{
                0, 1,           // self id = 1
                0, 1,           // one entry
                0, 1,           // client id = 1
                0, 5,           // name length = 5 bytes (4 characters, one of them two bytes)
                'B', (byte) 0xC3, (byte) 0xAC, 'n', 'h'}, packet.payload());
    }

    @Test
    void rosterRejectsAnEmptyOrOversizedDirectory() {
        assertThrows(IllegalArgumentException.class, () -> Packet.roster(1, List.of()));

        List<Packet.RosterEntry> tooMany = java.util.stream.IntStream.rangeClosed(1, Packet.MAX_ROSTER_ENTRIES + 1)
                .mapToObj(id -> new Packet.RosterEntry(id, "p" + id)).toList();
        assertThrows(IllegalArgumentException.class, () -> Packet.roster(1, tooMany));
    }

    @Test
    void rosterEntryRejectsAnUnusableClientId() {
        assertThrows(IllegalArgumentException.class, () -> new Packet.RosterEntry(0, "An"));
        assertThrows(IllegalArgumentException.class, () -> new Packet.RosterEntry(70_000, "An"));
        assertThrows(NullPointerException.class, () -> new Packet.RosterEntry(1, null));
    }

    @Test
    void rosterRejectsATruncatedPayload() {
        Packet missingEntries = Packet.of(MessageType.ROSTER, new byte[]{0, 1});
        Packet missingName = Packet.of(MessageType.ROSTER, new byte[]{0, 1, 0, 1, 0, 1, 0, 9, 'A'});

        assertThrows(IllegalStateException.class, missingEntries::rosterEntries);
        assertThrows(IllegalStateException.class, missingName::rosterEntries);
    }

    @Test
    void rosterAccessorsRejectNonRosterPackets() {
        assertThrows(IllegalStateException.class, () -> Packet.chat("hi").rosterEntries());
        assertThrows(IllegalStateException.class, () -> Packet.chat("hi").rosterSelfId());
    }
}
