package org.example.p2pchat.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

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
        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), packet.payload());
    }

    @Test
    void fileStartPacketKeepsIdNameAndSize() {
        Packet packet = Packet.fileStart(7, "test.pdf", 10_485_760L);

        assertEquals(MessageType.FILE_START, packet.type());
        assertEquals(7, packet.fileId());
        assertEquals("test.pdf", packet.fileName());
        assertEquals(10_485_760L, packet.fileSize());
    }

    @Test
    void fileStartPacketKeepsUnicodeName() {
        Packet packet = Packet.fileStart(1, "\u1ea3nh-\u0111\u1eb9p.png", 3L);

        assertEquals("\u1ea3nh-\u0111\u1eb9p.png", packet.fileName());
    }

    @Test
    void fileChunkPacketKeepsIdIndexAndData() {
        byte[] data = {1, 2, 3, 4, 5};
        Packet packet = Packet.fileChunk(7, 3, data);

        assertEquals(MessageType.FILE_CHUNK, packet.type());
        assertEquals(7, packet.fileId());
        assertEquals(3, packet.chunkIndex());
        assertArrayEquals(data, packet.chunkData());
    }

    @Test
    void fileChunkDataIsDefensiveCopy() {
        byte[] data = {1, 2, 3};
        Packet packet = Packet.fileChunk(7, 0, data);

        data[0] = 99;

        assertEquals(1, packet.chunkData()[0]);
    }

    @Test
    void fileEndPacketKeepsId() {
        Packet packet = Packet.fileEnd(7);

        assertEquals(MessageType.FILE_END, packet.type());
        assertEquals(7, packet.fileId());
    }

    @Test
    void disconnectPacketHasEmptyPayload() {
        Packet packet = Packet.disconnect();

        assertEquals(MessageType.DISCONNECT, packet.type());
        assertEquals(0, packet.payload().length);
    }

    @Test
    void chatTextRejectsNonChatPacket() {
        Packet packet = Packet.fileEnd(1);

        assertThrows(IllegalStateException.class, packet::chatText);
    }

    @Test
    void fileAcceptPacketKeepsId() {
        Packet packet = Packet.fileAccept(42);

        assertEquals(MessageType.FILE_ACCEPT, packet.type());
        assertEquals(42, packet.fileId());
    }

    @Test
    void fileDeclinePacketKeepsId() {
        Packet packet = Packet.fileDecline(42);

        assertEquals(MessageType.FILE_DECLINE, packet.type());
        assertEquals(42, packet.fileId());
    }

    @Test
    void acceptAndDeclineRejectInvalidId() {
        assertThrows(IllegalArgumentException.class, () -> Packet.fileAccept(-1));
        assertThrows(IllegalArgumentException.class, () -> Packet.fileDecline(70_000));
    }

    @Test
    void messageTypeIdsAreStable() throws Exception {
        assertEquals(1, MessageType.CHAT.id());
        assertEquals(2, MessageType.FILE_START.id());
        assertEquals(3, MessageType.FILE_CHUNK.id());
        assertEquals(4, MessageType.FILE_END.id());
        assertEquals(5, MessageType.DISCONNECT.id());
        assertEquals(6, MessageType.FILE_ACCEPT.id());
        assertEquals(7, MessageType.FILE_DECLINE.id());
    }
}
