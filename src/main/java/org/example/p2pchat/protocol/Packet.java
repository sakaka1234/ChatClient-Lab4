package org.example.p2pchat.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class Packet {

    public static final int MAX_FILE_ID = 0xFFFF;

    private final MessageType type;
    private final byte[] payload;

    private Packet(MessageType type, byte[] payload) {
        this.type = type;
        this.payload = payload;
    }

    public static Packet of(MessageType type, byte[] payload) {
        return new Packet(type, payload.clone());
    }

    public static Packet chat(String text) {
        return new Packet(MessageType.CHAT, text.getBytes(StandardCharsets.UTF_8));
    }

    public static Packet fileStart(int fileId, String fileName, long fileSize) {
        requireValidFileId(fileId);
        if (fileSize < 0) {
            throw new IllegalArgumentException("fileSize must not be negative: " + fileSize);
        }
        byte[] name = fileName.getBytes(StandardCharsets.UTF_8);
        if (name.length > 0xFFFF) {
            throw new IllegalArgumentException("fileName too long: " + name.length + " bytes");
        }
        ByteBuffer buffer = ByteBuffer.allocate(2 + 8 + 2 + name.length);
        buffer.putShort((short) fileId);
        buffer.putLong(fileSize);
        buffer.putShort((short) name.length);
        buffer.put(name);
        return new Packet(MessageType.FILE_START, buffer.array());
    }

    public static Packet fileChunk(int fileId, int chunkIndex, byte[] data) {
        requireValidFileId(fileId);
        ByteBuffer buffer = ByteBuffer.allocate(2 + 4 + data.length);
        buffer.putShort((short) fileId);
        buffer.putInt(chunkIndex);
        buffer.put(data);
        return new Packet(MessageType.FILE_CHUNK, buffer.array());
    }

    public static Packet fileEnd(int fileId) {
        return fileIdOnly(MessageType.FILE_END, fileId);
    }

    public static Packet fileAccept(int fileId) {
        return fileIdOnly(MessageType.FILE_ACCEPT, fileId);
    }

    public static Packet fileDecline(int fileId) {
        return fileIdOnly(MessageType.FILE_DECLINE, fileId);
    }

    private static Packet fileIdOnly(MessageType type, int fileId) {
        requireValidFileId(fileId);
        ByteBuffer buffer = ByteBuffer.allocate(2);
        buffer.putShort((short) fileId);
        return new Packet(type, buffer.array());
    }

    public static Packet hello(String name) {
        return new Packet(MessageType.HELLO, name.getBytes(StandardCharsets.UTF_8));
    }

    public static Packet relay(String sender, String text) {
        byte[] senderBytes = sender.getBytes(StandardCharsets.UTF_8);
        if (senderBytes.length > 0xFFFF) {
            throw new IllegalArgumentException("relay sender too long: " + senderBytes.length + " bytes");
        }
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(2 + senderBytes.length + textBytes.length);
        buffer.putShort((short) senderBytes.length);
        buffer.put(senderBytes);
        buffer.put(textBytes);
        return new Packet(MessageType.RELAY, buffer.array());
    }

    public static Packet disconnect() {
        return new Packet(MessageType.DISCONNECT, new byte[0]);
    }

    public MessageType type() {
        return type;
    }

    public byte[] payload() {
        return payload.clone();
    }

    public String chatText() {
        requireType(MessageType.CHAT);
        return new String(payload, StandardCharsets.UTF_8);
    }

    public String helloName() {
        requireType(MessageType.HELLO);
        return new String(payload, StandardCharsets.UTF_8);
    }

    public String relaySender() {
        requireType(MessageType.RELAY);
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        int senderLength = Short.toUnsignedInt(buffer.getShort());
        byte[] sender = new byte[senderLength];
        buffer.get(sender);
        return new String(sender, StandardCharsets.UTF_8);
    }

    public String relayText() {
        requireType(MessageType.RELAY);
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        int senderLength = Short.toUnsignedInt(buffer.getShort());
        buffer.position(buffer.position() + senderLength);
        byte[] text = new byte[buffer.remaining()];
        buffer.get(text);
        return new String(text, StandardCharsets.UTF_8);
    }

    public int fileId() {
        switch (type) {
            case FILE_START, FILE_END, FILE_CHUNK, FILE_ACCEPT, FILE_DECLINE -> {
                if (payload.length < 2) {
                    throw new IllegalStateException("Packet " + type + " is missing its file id");
                }
                return Short.toUnsignedInt(ByteBuffer.wrap(payload).getShort());
            }
            default -> throw new IllegalStateException("Packet " + type + " has no file id");
        }
    }

    public String fileName() {
        requireType(MessageType.FILE_START);
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.getShort();
        buffer.getLong();
        int nameLength = Short.toUnsignedInt(buffer.getShort());
        byte[] name = new byte[nameLength];
        buffer.get(name);
        return new String(name, StandardCharsets.UTF_8);
    }

    public long fileSize() {
        requireType(MessageType.FILE_START);
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.getShort();
        return buffer.getLong();
    }

    public int chunkIndex() {
        requireType(MessageType.FILE_CHUNK);
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.getShort();
        return buffer.getInt();
    }

    public byte[] chunkData() {
        requireType(MessageType.FILE_CHUNK);
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.getShort();
        buffer.getInt();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        return data;
    }

    private void requireType(MessageType expected) {
        if (type != expected) {
            throw new IllegalStateException("Expected " + expected + " packet but got " + type);
        }
    }

    private static void requireValidFileId(int fileId) {
        if (fileId < 0 || fileId > MAX_FILE_ID) {
            throw new IllegalArgumentException("fileId out of range 0.." + MAX_FILE_ID + ": " + fileId);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Packet packet)) {
            return false;
        }
        return type == packet.type && Arrays.equals(payload, packet.payload);
    }

    @Override
    public int hashCode() {
        return 31 * type.hashCode() + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "Packet[" + type + ", payloadLength=" + payload.length + "]";
    }
}
