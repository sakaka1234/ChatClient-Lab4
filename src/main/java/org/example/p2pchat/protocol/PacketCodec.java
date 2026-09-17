package org.example.p2pchat.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class PacketCodec {

    public static final int MAX_PAYLOAD_LENGTH = 16 * 1024 * 1024;

    private PacketCodec() {
    }

    public static void write(OutputStream out, Packet packet) throws IOException {
        byte[] payload = packet.payload();
        if (payload.length > MAX_PAYLOAD_LENGTH) {
            throw new IOException("Payload too large: " + payload.length + " bytes");
        }
        DataOutputStream data = new DataOutputStream(out);
        data.writeByte(packet.type().id());
        data.writeInt(payload.length);
        data.write(payload);
        data.flush();
    }

    public static Packet read(InputStream in) throws IOException {
        DataInputStream data = in instanceof DataInputStream input ? input : new DataInputStream(in);
        int typeId = data.readUnsignedByte();
        MessageType type = MessageType.fromId(typeId);
        int length = data.readInt();
        if (length < 0 || length > MAX_PAYLOAD_LENGTH) {
            throw new IOException("Invalid payload length: " + length);
        }
        byte[] payload = new byte[length];
        try {
            data.readFully(payload);
        } catch (EOFException e) {
            throw new EOFException("Connection closed while reading " + type + " payload");
        }
        return Packet.of(type, payload);
    }
}
