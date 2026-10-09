package org.example.p2pchat.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PacketCodecTest {

    private Packet roundTrip(Packet packet) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PacketCodec.write(out, packet);
        return PacketCodec.read(new ByteArrayInputStream(out.toByteArray()));
    }

    @Test
    void chatSurvivesRoundTrip() throws IOException {
        Packet result = roundTrip(Packet.chat("Hello world"));

        assertEquals(MessageType.CHAT, result.type());
        assertEquals("Hello world", result.chatText());
    }

    @Test
    void emptyChatSurvivesRoundTrip() throws IOException {
        Packet result = roundTrip(Packet.chat(""));

        assertEquals(MessageType.CHAT, result.type());
        assertEquals("", result.chatText());
        // scope (1 byte) + target count (2 bytes); the text itself is empty.
        assertEquals(3, result.payload().length);
        assertEquals(ChatScope.BROADCAST, result.chatScope());
        assertEquals(List.of(), result.chatTargets());
    }

    @Test
    void unicastChatSurvivesRoundTrip() throws IOException {
        Packet result = roundTrip(Packet.chat(ChatScope.UNICAST, List.of(3), "chi rieng cho ban"));

        assertEquals("chi rieng cho ban", result.chatText());
        assertEquals(ChatScope.UNICAST, result.chatScope());
        assertEquals(List.of(3), result.chatTargets());
    }

    @Test
    void multicastChatSurvivesRoundTrip() throws IOException {
        Packet result = roundTrip(Packet.chat(ChatScope.MULTICAST, List.of(2, 4, 5), "hop nhom"));

        assertEquals("hop nhom", result.chatText());
        assertEquals(ChatScope.MULTICAST, result.chatScope());
        assertEquals(List.of(2, 4, 5), result.chatTargets());
    }

    @Test
    void relaySurvivesRoundTripWithItsAddressingBlock() throws IOException {
        Packet result = roundTrip(Packet.relay("Cường", ChatScope.MULTICAST, List.of(1, 4), "hi"));

        assertEquals(MessageType.RELAY, result.type());
        assertEquals("Cường", result.relaySender());
        assertEquals(ChatScope.MULTICAST, result.relayScope());
        assertEquals(List.of(1, 4), result.relayTargets());
        assertEquals("hi", result.relayText());
    }

    @Test
    void rosterSurvivesRoundTrip() throws IOException {
        Packet result = roundTrip(Packet.roster(2, List.of(
                new Packet.RosterEntry(1, "Chủ nhà"),
                new Packet.RosterEntry(2, "An"),
                new Packet.RosterEntry(7, "Bình"))));

        assertEquals(MessageType.ROSTER, result.type());
        assertEquals(2, result.rosterSelfId());
        assertEquals(List.of(
                new Packet.RosterEntry(1, "Chủ nhà"),
                new Packet.RosterEntry(2, "An"),
                new Packet.RosterEntry(7, "Bình")), result.rosterEntries());
    }

    @Test
    void largeChatSurvivesRoundTrip() throws IOException {
        String text = "a".repeat(200_000);

        Packet result = roundTrip(Packet.chat(text));

        assertEquals(text, result.chatText());
    }

    @Test
    void disconnectSurvivesRoundTrip() throws IOException {
        Packet result = roundTrip(Packet.disconnect());

        assertEquals(MessageType.DISCONNECT, result.type());
    }

    @Test
    void readsConsecutivePacketsFromSameStream() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PacketCodec.write(out, Packet.chat("first"));
        PacketCodec.write(out, Packet.chat("second"));
        PacketCodec.write(out, Packet.disconnect());

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());

        assertEquals("first", PacketCodec.read(in).chatText());
        assertEquals("second", PacketCodec.read(in).chatText());
        assertEquals(MessageType.DISCONNECT, PacketCodec.read(in).type());
    }

    @Test
    void readReturnsEndOfStreamCleanlyWhenNoBytesRemain() throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);

        assertThrows(EOFException.class, () -> PacketCodec.read(in));
    }

    @Test
    void readWithPartialHeaderFails() {
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[]{1, 0});

        assertThrows(EOFException.class, () -> PacketCodec.read(in));
    }

    @Test
    void rejectedUnknownMessageType() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(99);
        out.write(new byte[]{0, 0, 0, 0});

        assertThrows(IOException.class,
                () -> PacketCodec.read(new ByteArrayInputStream(out.toByteArray())));
    }

    @Test
    void headerUsesOneTypeByteAndFourLengthBytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PacketCodec.write(out, Packet.chat("Hey"));

        byte[] bytes = out.toByteArray();

        // frame header (1 + 4) + broadcast addressing (1 + 2) + "Hey"
        assertEquals(5 + 3 + 3, bytes.length);
        assertEquals(MessageType.CHAT.id(), bytes[0] & 0xFF);
        assertEquals(0, bytes[1]);
        assertEquals(0, bytes[2]);
        assertEquals(0, bytes[3]);
        assertEquals(6, bytes[4]);
        assertEquals(ChatScope.BROADCAST.id(), bytes[5] & 0xFF);
        assertEquals(0, bytes[6]);
        assertEquals(0, bytes[7]);
    }
}
