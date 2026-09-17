package org.example.p2pchat.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
        assertEquals(0, result.payload().length);
    }

    @Test
    void largeChatSurvivesRoundTrip() throws IOException {
        String text = "a".repeat(200_000);

        Packet result = roundTrip(Packet.chat(text));

        assertEquals(text, result.chatText());
    }

    @Test
    void fileStartSurvivesRoundTrip() throws IOException {
        Packet result = roundTrip(Packet.fileStart(42, "archive.tar.gz", 9_007_199_254_740_993L));

        assertEquals(MessageType.FILE_START, result.type());
        assertEquals(42, result.fileId());
        assertEquals("archive.tar.gz", result.fileName());
        assertEquals(9_007_199_254_740_993L, result.fileSize());
    }

    @Test
    void fileChunkSurvivesRoundTrip() throws IOException {
        byte[] data = new byte[64 * 1024];
        new Random(42).nextBytes(data);

        Packet result = roundTrip(Packet.fileChunk(3, 17, data));

        assertEquals(MessageType.FILE_CHUNK, result.type());
        assertEquals(3, result.fileId());
        assertEquals(17, result.chunkIndex());
        assertArrayEquals(data, result.chunkData());
    }

    @Test
    void emptyChunkSurvivesRoundTrip() throws IOException {
        Packet result = roundTrip(Packet.fileChunk(1, 0, new byte[0]));

        assertEquals(0, result.chunkData().length);
    }

    @Test
    void fileEndSurvivesRoundTrip() throws IOException {
        Packet result = roundTrip(Packet.fileEnd(99));

        assertEquals(MessageType.FILE_END, result.type());
        assertEquals(99, result.fileId());
    }

    @Test
    void disconnectSurvivesRoundTrip() throws IOException {
        Packet result = roundTrip(Packet.disconnect());

        assertEquals(MessageType.DISCONNECT, result.type());
    }

    @Test
    void fileAcceptSurvivesRoundTrip() throws IOException {
        Packet result = roundTrip(Packet.fileAccept(12));

        assertEquals(MessageType.FILE_ACCEPT, result.type());
        assertEquals(12, result.fileId());
    }

    @Test
    void fileDeclineSurvivesRoundTrip() throws IOException {
        Packet result = roundTrip(Packet.fileDecline(12));

        assertEquals(MessageType.FILE_DECLINE, result.type());
        assertEquals(12, result.fileId());
    }

    @Test
    void acceptAndDeclineCarryTwoBytePayload() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PacketCodec.write(out, Packet.fileAccept(300));

        byte[] bytes = out.toByteArray();

        assertEquals(7, bytes.length);
        assertEquals(MessageType.FILE_ACCEPT.id(), bytes[0] & 0xFF);
        assertEquals(0, bytes[1]);
        assertEquals(0, bytes[2]);
        assertEquals(0, bytes[3]);
        assertEquals(2, bytes[4]);
        assertEquals(1, bytes[5] & 0xFF);
        assertEquals(44, bytes[6] & 0xFF);
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

        assertEquals(5 + 3, bytes.length);
        assertEquals(MessageType.CHAT.id(), bytes[0] & 0xFF);
        assertEquals(0, bytes[1]);
        assertEquals(0, bytes[2]);
        assertEquals(0, bytes[3]);
        assertEquals(3, bytes[4]);
    }
}
