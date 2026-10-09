package org.example.p2pchat.session;

import org.example.p2pchat.protocol.ChatScope;
import org.example.p2pchat.protocol.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a client sending nonsense can and cannot do to a session.
 *
 * <p>A packet that is <em>well framed</em> but carries a malformed addressing block is dropped where
 * it is decoded: the reader thread logs it and carries on, so the other clients never notice. That is
 * the guarantee {@code PROTOCOL.md} §3.1 and §4 make, and it is tested here by writing the bad bytes
 * directly onto a socket — no client would ever produce them.
 *
 * <p>What the connection surviving is measured by has to be the server's own behaviour, since a
 * server relays chat rather than receiving it: a valid broadcast sent after the garbage still reaches
 * the other client, which is exactly what the server is for.
 *
 * <p>A packet that is <em>badly framed</em> (a length the codec rejects) is a different matter: a
 * length-framed stream cannot be resynchronised after that, so the connection ends. The blast radius
 * is still one connection, which the last test pins down.
 */
@Timeout(60)
class MalformedChatPacketTest {

    private static final long WAIT_SECONDS = 15;

    /** Broadcast "still here" — the frame a well-behaved client would send next. */
    private static final byte[] VALID_BROADCAST =
            {2, 0, 0, 's', 't', 'i', 'l', 'l', ' ', 'h', 'e', 'r', 'e'};

    private static final class Recorder implements SessionListener {
        final BlockingQueue<String> chats = new ArrayBlockingQueue<>(64);

        @Override
        public void onStatusChanged(String status) {
        }

        @Override
        public void onChatMessage(String sender, String message) {
            chats.add(sender + ":" + message);
        }

        @Override
        public void onDisconnected(String reason) {
        }

        String awaitChat() throws InterruptedException {
            String value = chats.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertTrue(value != null, "expected a chat message");
            return value;
        }
    }

    private ChatSession server;
    private ChatSession an;
    private ChatSession binh;
    private Socket raw;
    private DataOutputStream out;
    private Recorder anRec;

    @AfterEach
    void tearDown() {
        closeQuietly(raw);
        closeQuietly(binh);
        closeQuietly(an);
        closeQuietly(server);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // tearing down
            }
        }
    }

    /**
     * A server, one real client, and a raw socket standing in for a second client so we can write
     * bytes no client would. The real client dials in first, so the numbering is An #1, raw #2.
     */
    private void startServerWithARawClient() throws Exception {
        server = new ChatSession(new Recorder(), "Server");
        server.startServer(0);

        anRec = new Recorder();
        an = new ChatSession(anRec, "An");
        an.startClient("127.0.0.1", server.port(), 5000);
        awaitDirectory(1);

        raw = new Socket("127.0.0.1", server.port());
        out = new DataOutputStream(raw.getOutputStream());
        awaitDirectory(2);
    }

    private void sendFrame(int type, byte[] payload) throws IOException {
        out.writeByte(type);
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }

    /**
     * The connection was not torn down: the next valid frame still arrives, and the client on the
     * other end of it still gets served.
     */
    private void assertTheConnectionOutlivedTheGarbage() throws Exception {
        sendFrame(MessageType.CHAT.id(), VALID_BROADCAST);

        assertEquals("Client:still here", anRec.awaitChat(),
                "the raw client never announced a name, so it is the default one");
        assertEquals(2, server.clients().size(), "a malformed payload must not drop the client");
    }

    private void awaitDirectory(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (server.clients().size() != expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("directory never reached " + expected + " client(s), last was "
                        + server.clients());
            }
            Thread.sleep(20);
        }
    }

    // ------------------------------------------------- malformed addressing

    @Test
    void anUnknownScopeIsDroppedAndTheReaderSurvives() throws Exception {
        startServerWithARawClient();

        sendFrame(MessageType.CHAT.id(), new byte[]{9, 0, 0, 'h', 'i'});   // 9 is not a ChatScope

        assertTheConnectionOutlivedTheGarbage();
    }

    @Test
    void aTargetCountThatRunsPastThePayloadIsDropped() throws Exception {
        startServerWithARawClient();

        // MULTICAST claims five targets but carries none.
        sendFrame(MessageType.CHAT.id(), new byte[]{(byte) ChatScope.MULTICAST.id(), 0, 5, 'h'});

        assertTheConnectionOutlivedTheGarbage();
    }

    @Test
    void aTargetCountAboveTheLimitIsDropped() throws Exception {
        startServerWithARawClient();

        // 127 targets is beyond MAX_CHAT_TARGETS (64), and there is not a single id behind it.
        sendFrame(MessageType.CHAT.id(), new byte[]{(byte) ChatScope.MULTICAST.id(), 0, 127, 'h'});

        assertTheConnectionOutlivedTheGarbage();
    }

    @Test
    void aMalformedRelayIsDroppedToo() throws Exception {
        startServerWithARawClient();

        // sender length 2, name "An", then an unknown scope byte.
        sendFrame(MessageType.RELAY.id(), new byte[]{0, 2, 'A', 'n', 9, 0, 0, 'h', 'i'});

        assertTheConnectionOutlivedTheGarbage();
    }

    @Test
    void aTruncatedAddressingHeaderIsDropped() throws Exception {
        startServerWithARawClient();

        sendFrame(MessageType.CHAT.id(), new byte[]{1, 0});                // scope, then half a count

        assertTheConnectionOutlivedTheGarbage();
    }

    @Test
    void severalMalformedPacketsInARowDoNotAccumulateIntoAFailure() throws Exception {
        startServerWithARawClient();

        sendFrame(MessageType.CHAT.id(), new byte[]{9, 0, 0, 'h', 'i'});
        sendFrame(MessageType.CHAT.id(), new byte[]{(byte) ChatScope.MULTICAST.id(), 0, 5, 'h'});
        sendFrame(MessageType.RELAY.id(), new byte[]{0, 2, 'A', 'n', 9, 0, 0});
        sendFrame(MessageType.CHAT.id(), new byte[]{1, 0});

        assertTheConnectionOutlivedTheGarbage();
    }

    // ---------------------------------------------------- malformed framing

    @Test
    void aBadFrameLengthEndsOnlyThatOneConnection() throws Exception {
        Recorder binhRec = new Recorder();
        server = new ChatSession(new Recorder(), "Server");
        server.startServer(0);

        anRec = new Recorder();
        an = new ChatSession(anRec, "An");
        an.startClient("127.0.0.1", server.port(), 5000);
        awaitDirectory(1);

        binh = new ChatSession(binhRec, "Binh");
        binh.startClient("127.0.0.1", server.port(), 5000);
        awaitDirectory(2);

        raw = new Socket("127.0.0.1", server.port());
        out = new DataOutputStream(raw.getOutputStream());
        awaitDirectory(3);

        // Declares 20 MiB, twice the codec's limit, so the frame is rejected before any payload.
        out.writeByte(MessageType.CHAT.id());
        out.writeInt(20 * 1024 * 1024);
        out.write(new byte[]{2, 0, 0, 'h', 'i'});
        out.flush();

        // Only the offending client is gone...
        awaitDirectory(2);
        assertEquals(List.of("An", "Binh"),
                server.clients().stream().map(ClientInfo::name).toList());

        // ...and the session it left behind still relays chat between the clients that remain.
        an.sendChat("con song khong?", ChatScope.BROADCAST, List.of());
        assertEquals("An:con song khong?", binhRec.awaitChat());
    }
}
