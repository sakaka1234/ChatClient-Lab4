package org.example.p2pchat.protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A framed protocol message together with the byte layout of every payload.
 *
 * <p>Chat payloads carry an <b>addressing block</b> — scope plus the target client ids — so one
 * message can be unicast to a single client, multicast to a chosen group, or broadcast to everyone.
 * See {@code docs/PROTOCOL.md} §3 for the byte layouts and {@code docs/SPECS-UNICAST-MULTICAST.md}
 * for the delivery rules.
 */
public final class Packet {

    /** Client ids and roster ids are 16-bit, so this is the largest id the protocol can address. */
    public static final int MAX_CLIENT_ID = 0xFFFF;

    /** Largest number of clients a single chat message may address, sender excluded. */
    public static final int MAX_CHAT_TARGETS = 64;

    /** Largest number of clients a {@code ROSTER} may list. */
    public static final int MAX_ROSTER_ENTRIES = 64;

    /** {@code scope (1 byte) + target count (u16)}. */
    private static final int ADDRESSING_HEADER_BYTES = 1 + 2;

    private static final int MAX_NAME_BYTES = 0xFFFF;

    private final MessageType type;
    private final byte[] payload;

    private Packet(MessageType type, byte[] payload) {
        this.type = type;
        this.payload = payload;
    }

    public static Packet of(MessageType type, byte[] payload) {
        return new Packet(type, payload.clone());
    }

    // ------------------------------------------------------------------ chat

    /** A broadcast chat: every other client is a recipient. */
    public static Packet chat(String text) {
        return chat(ChatScope.BROADCAST, List.of(), text);
    }

    /**
     * @param scope   {@link ChatScope#UNICAST} (one id), {@link ChatScope#MULTICAST} (two or more)
     *                or {@link ChatScope#BROADCAST} (no ids)
     * @param targets target client ids; must match {@code scope} and never contain {@code 0}
     */
    public static Packet chat(ChatScope scope, List<Integer> targets, String text) {
        Address address = Address.of(scope, targets);
        byte[] textBytes = utf8(text);
        ByteBuffer buffer = ByteBuffer.allocate(address.byteSize() + textBytes.length);
        address.writeTo(buffer);
        buffer.put(textBytes);
        return new Packet(MessageType.CHAT, buffer.array());
    }

    /** A broadcast relay: the server copies the message to every client except the sender. */
    public static Packet relay(String sender, String text) {
        return relay(sender, ChatScope.BROADCAST, List.of(), text);
    }

    /**
     * A relay with the original addressing block forwarded intact, so the receiver can render how the
     * message was addressed ("private" / "group: An, Binh").
     */
    public static Packet relay(String sender, ChatScope scope, List<Integer> targets, String text) {
        byte[] senderBytes = utf8(sender);
        if (senderBytes.length > MAX_NAME_BYTES) {
            throw new IllegalArgumentException("relay sender too long: " + senderBytes.length + " bytes");
        }
        Address address = Address.of(scope, targets);
        byte[] textBytes = utf8(text);
        ByteBuffer buffer = ByteBuffer.allocate(2 + senderBytes.length + address.byteSize() + textBytes.length);
        buffer.putShort((short) senderBytes.length);
        buffer.put(senderBytes);
        address.writeTo(buffer);
        buffer.put(textBytes);
        return new Packet(MessageType.RELAY, buffer.array());
    }

    // ---------------------------------------------------------------- roster

    /** One line of the client directory pushed by the server. */
    public record RosterEntry(int id, String name) {
        public RosterEntry {
            requireValidClientId(id);
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * @param selfId  the receiving client's own id
     * @param entries every client of the session, the receiver included; at least one, at most
     *                {@link #MAX_ROSTER_ENTRIES}
     */
    public static Packet roster(int selfId, List<RosterEntry> entries) {
        requireValidClientId(selfId);
        Objects.requireNonNull(entries, "entries");
        if (entries.isEmpty() || entries.size() > MAX_ROSTER_ENTRIES) {
            throw new IllegalArgumentException("roster size out of range 1.." + MAX_ROSTER_ENTRIES
                    + ": " + entries.size());
        }
        int size = 2 + 2;
        for (RosterEntry entry : entries) {
            size += 2 + 2 + utf8(entry.name()).length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.putShort((short) selfId);
        buffer.putShort((short) entries.size());
        for (RosterEntry entry : entries) {
            byte[] name = utf8(entry.name());
            if (name.length > MAX_NAME_BYTES) {
                throw new IllegalArgumentException("roster name too long: " + name.length + " bytes");
            }
            buffer.putShort((short) entry.id());
            buffer.putShort((short) name.length);
            buffer.put(name);
        }
        return new Packet(MessageType.ROSTER, buffer.array());
    }

    // ------------------------------------------------------- control packets

    public static Packet hello(String name) {
        return new Packet(MessageType.HELLO, utf8(name));
    }

    public static Packet disconnect() {
        return new Packet(MessageType.DISCONNECT, new byte[0]);
    }

    // ------------------------------------------------------------ accessors

    public MessageType type() {
        return type;
    }

    public byte[] payload() {
        return payload.clone();
    }

    public String chatText() {
        requireType(MessageType.CHAT);
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        Address.read(buffer);          // advances past scope + target ids
        return remainder(buffer);
    }

    public ChatScope chatScope() {
        requireType(MessageType.CHAT);
        return Address.read(ByteBuffer.wrap(payload)).scope();
    }

    public List<Integer> chatTargets() {
        requireType(MessageType.CHAT);
        return Address.read(ByteBuffer.wrap(payload)).targets();
    }

    public String helloName() {
        requireType(MessageType.HELLO);
        return new String(payload, StandardCharsets.UTF_8);
    }

    public String relaySender() {
        requireType(MessageType.RELAY);
        return new String(senderSlice(), StandardCharsets.UTF_8);
    }

    public ChatScope relayScope() {
        requireType(MessageType.RELAY);
        return Address.read(relayBody()).scope();
    }

    public List<Integer> relayTargets() {
        requireType(MessageType.RELAY);
        return Address.read(relayBody()).targets();
    }

    public String relayText() {
        requireType(MessageType.RELAY);
        ByteBuffer buffer = relayBody();
        Address.read(buffer);          // advances past scope + target ids
        return remainder(buffer);
    }

    public int rosterSelfId() {
        requireType(MessageType.ROSTER);
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        requireRemaining(buffer, 2, "roster self id");
        return Short.toUnsignedInt(buffer.getShort());
    }

    public List<RosterEntry> rosterEntries() {
        requireType(MessageType.ROSTER);
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        requireRemaining(buffer, 4, "roster header");
        buffer.getShort();
        int count = Short.toUnsignedInt(buffer.getShort());
        if (count == 0 || count > MAX_ROSTER_ENTRIES) {
            throw new IllegalStateException("Roster size out of range 1.." + MAX_ROSTER_ENTRIES + ": " + count);
        }
        List<RosterEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            requireRemaining(buffer, 4, "roster entry header");
            int id = Short.toUnsignedInt(buffer.getShort());
            int nameLength = Short.toUnsignedInt(buffer.getShort());
            requireRemaining(buffer, nameLength, "roster entry name");
            byte[] name = new byte[nameLength];
            buffer.get(name);
            entries.add(new RosterEntry(id, new String(name, StandardCharsets.UTF_8)));
        }
        return List.copyOf(entries);
    }

    // ----------------------------------------------------------- addressing

    /** The decoded addressing block of a {@code CHAT} or {@code RELAY} payload. */
    private record Address(ChatScope scope, List<Integer> targets) {

        static Address of(ChatScope scope, List<Integer> targets) {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(targets, "targets");
            if (targets.size() > MAX_CHAT_TARGETS) {
                throw new IllegalArgumentException("Too many chat targets: " + targets.size());
            }
            for (Integer target : targets) {
                if (target == null) {
                    throw new IllegalArgumentException("Chat target must not be null");
                }
                requireValidClientId(target);
            }
            switch (scope) {
                case BROADCAST -> {
                    if (!targets.isEmpty()) {
                        throw new IllegalArgumentException("BROADCAST must not carry targets");
                    }
                }
                case UNICAST -> {
                    if (targets.size() != 1) {
                        throw new IllegalArgumentException("UNICAST needs exactly one target, got " + targets.size());
                    }
                }
                case MULTICAST -> {
                    if (targets.size() < 2) {
                        throw new IllegalArgumentException("MULTICAST needs at least two targets, got " + targets.size());
                    }
                }
            }
            return new Address(scope, List.copyOf(targets));
        }

        int byteSize() {
            return ADDRESSING_HEADER_BYTES + targets.size() * 2;
        }

        void writeTo(ByteBuffer buffer) {
            buffer.put((byte) scope.id());
            buffer.putShort((short) targets.size());
            for (int target : targets) {
                buffer.putShort((short) target);
            }
        }

        static Address read(ByteBuffer buffer) {
            requireRemaining(buffer, ADDRESSING_HEADER_BYTES, "chat addressing header");
            ChatScope scope;
            try {
                scope = ChatScope.fromId(Byte.toUnsignedInt(buffer.get()));
            } catch (IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            int count = Short.toUnsignedInt(buffer.getShort());
            if (count > MAX_CHAT_TARGETS) {
                throw new IllegalStateException("Too many chat targets: " + count);
            }
            requireRemaining(buffer, count * 2, "chat target list");
            List<Integer> targets = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                targets.add(Short.toUnsignedInt(buffer.getShort()));
            }
            switch (scope) {
                case BROADCAST -> {
                    if (count != 0) {
                        throw new IllegalStateException("BROADCAST chat carries " + count + " targets");
                    }
                }
                case UNICAST -> {
                    if (count != 1) {
                        throw new IllegalStateException("UNICAST chat carries " + count + " targets");
                    }
                }
                case MULTICAST -> {
                    if (count < 2) {
                        throw new IllegalStateException("MULTICAST chat carries " + count + " targets");
                    }
                }
            }
            return new Address(scope, List.copyOf(targets));
        }
    }

    /** The bytes after the relay's sender field, positioned at the addressing block. */
    private ByteBuffer relayBody() {
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        // Read the length first: senderLengthIn advances the buffer itself, so folding both calls
        // into one expression would add the length to a position captured before it moved.
        int senderLength = senderLengthIn(buffer);
        buffer.position(buffer.position() + senderLength);
        return buffer;
    }

    private byte[] senderSlice() {
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        int senderLength = senderLengthIn(buffer);
        byte[] sender = new byte[senderLength];
        buffer.get(sender);
        return sender;
    }

    /** Reads the relay's {@code u16} sender length and leaves the buffer on the first name byte. */
    private static int senderLengthIn(ByteBuffer buffer) {
        requireRemaining(buffer, 2, "relay sender length");
        int senderLength = Short.toUnsignedInt(buffer.getShort());
        requireRemaining(buffer, senderLength, "relay sender name");
        return senderLength;
    }

    private static String remainder(ByteBuffer buffer) {
        byte[] text = new byte[buffer.remaining()];
        buffer.get(text);
        return new String(text, StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------- helpers

    private void requireType(MessageType expected) {
        if (type != expected) {
            throw new IllegalStateException("Expected " + expected + " packet but got " + type);
        }
    }

    private static void requireRemaining(ByteBuffer buffer, int needed, String what) {
        if (needed < 0 || buffer.remaining() < needed) {
            throw new IllegalStateException("Truncated packet: missing " + what);
        }
    }

    private static byte[] utf8(String text) {
        return Objects.requireNonNull(text, "text").getBytes(StandardCharsets.UTF_8);
    }

    private static void requireValidClientId(int clientId) {
        if (clientId < 1 || clientId > MAX_CLIENT_ID) {
            throw new IllegalArgumentException("client id out of range 1.." + MAX_CLIENT_ID + ": " + clientId);
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
