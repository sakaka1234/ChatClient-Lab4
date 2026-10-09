package org.example.p2pchat.session;

import org.example.p2pchat.protocol.ChatScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Delivery rules for addressed chat: {@code UNICAST} reaches exactly one client, {@code MULTICAST}
 * exactly the chosen group, {@code BROADCAST} everyone but the sender. The server is the only
 * forwarder, so every case here also proves it forwards selectively rather than blindly — and that
 * it takes no part in the conversation itself.
 */
@Timeout(60)
class AddressedChatTest {

    private static final long WAIT_SECONDS = 15;

    /** How long a negative assertion waits before concluding that a message never arrived. */
    private static final long SILENCE_MILLIS = 400;

    private ChatSession server;
    private ChatSession an;
    private ChatSession binh;
    private ChatSession cuong;

    @AfterEach
    void tearDown() {
        closeQuietly(cuong);
        closeQuietly(binh);
        closeQuietly(an);
        closeQuietly(server);
    }

    private static void closeQuietly(ChatSession session) {
        if (session != null) {
            session.close();
        }
    }

    /** A chat as it arrived, including how it was addressed. */
    private record Received(String sender, String message, ChatScope scope, List<String> audience) {
    }

    private static final class Recorder implements SessionListener {
        final BlockingQueue<String> chats = new ArrayBlockingQueue<>(64);
        final BlockingQueue<Received> addressed = new ArrayBlockingQueue<>(64);
        final AtomicReference<String> lastStatus = new AtomicReference<>("");

        @Override
        public void onStatusChanged(String status) {
            lastStatus.set(status);
        }

        @Override
        public void onChatMessage(String sender, String message) {
            chats.add(sender + ":" + message);
        }

        @Override
        public void onChatMessage(String sender, String message, ChatScope scope, List<String> audience) {
            onChatMessage(sender, message);
            addressed.add(new Received(sender, message, scope, audience));
        }

        @Override
        public void onDisconnected(String reason) {
        }

        String awaitChat() throws InterruptedException {
            String value = chats.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertTrue(value != null, "expected a chat message");
            return value;
        }

        Received awaitAddressed() throws InterruptedException {
            Received value = addressed.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertTrue(value != null, "expected an addressed chat message");
            return value;
        }

        /** The queue is empty, but that is only meaningful once we have waited long enough. */
        void assertSilent(String what) throws InterruptedException {
            String received = chats.poll(SILENCE_MILLIS, TimeUnit.MILLISECONDS);
            assertTrue(received == null, what + ", but it arrived as: " + received);
        }
    }

    /**
     * A server and three clients, all named and present in everyone's directory. Each client is
     * awaited before the next dials in, so the join order — and with it the numbering — is fixed.
     */
    private void startQuartet(Recorder serverRec, Recorder anRec, Recorder binhRec,
                              Recorder cuongRec) throws Exception {
        server = new ChatSession(serverRec, "Server");
        server.startServer(0);
        int port = server.port();

        an = new ChatSession(anRec, "An");
        an.startClient("127.0.0.1", port, 5000);
        awaitRoster(server, "An");

        binh = new ChatSession(binhRec, "Binh");
        binh.startClient("127.0.0.1", port, 5000);
        awaitRoster(server, "An", "Binh");

        cuong = new ChatSession(cuongRec, "Cuong");
        cuong.startClient("127.0.0.1", port, 5000);
        awaitRoster(server, "An", "Binh", "Cuong");

        awaitRoster(an, "An", "Binh", "Cuong");
        awaitRoster(binh, "An", "Binh", "Cuong");
        awaitRoster(cuong, "An", "Binh", "Cuong");
    }

    private static void awaitRoster(ChatSession session, String... names) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!hasNames(session.clients(), names)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("directory never listed " + List.of(names)
                        + ", last was " + session.clients());
            }
            Thread.sleep(20);
        }
    }

    private static boolean hasNames(List<ClientInfo> directory, String[] names) {
        for (String name : names) {
            boolean found = false;
            for (ClientInfo client : directory) {
                if (client.name().equals(name)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private int idOf(ChatSession session, String name) {
        for (ClientInfo client : session.clients()) {
            if (client.name().equals(name)) {
                return client.id();
            }
        }
        throw new AssertionError("no client named " + name + " in " + session.clients());
    }

    // ----------------------------------------------------------------- unicast

    @Test
    void unicastReachesOnlyTheNamedClient() throws Exception {
        Recorder serverRec = new Recorder();
        Recorder anRec = new Recorder();
        Recorder binhRec = new Recorder();
        Recorder cuongRec = new Recorder();
        startQuartet(serverRec, anRec, binhRec, cuongRec);

        an.sendChat("chi rieng Binh", ChatScope.UNICAST, List.of(idOf(an, "Binh")));

        assertEquals("An:chi rieng Binh", binhRec.awaitChat());
        serverRec.assertSilent("the message was addressed to Binh only");
        cuongRec.assertSilent("the message was addressed to Binh only");
        anRec.assertSilent("the sender must not receive its own message back");
    }

    // --------------------------------------------------------------- multicast

    @Test
    void multicastReachesExactlyTheChosenGroup() throws Exception {
        Recorder serverRec = new Recorder();
        Recorder anRec = new Recorder();
        Recorder binhRec = new Recorder();
        Recorder cuongRec = new Recorder();
        startQuartet(serverRec, anRec, binhRec, cuongRec);

        an.sendChat("hop nhom", ChatScope.MULTICAST,
                List.of(idOf(an, "Binh"), idOf(an, "Cuong")));

        assertEquals("An:hop nhom", binhRec.awaitChat());
        assertEquals("An:hop nhom", cuongRec.awaitChat());
        serverRec.assertSilent("the message was addressed to two clients, not to the server");
        anRec.assertSilent("the sender must not receive its own message back");
    }

    // --------------------------------------------------------------- broadcast

    @Test
    void broadcastStillReachesEveryOtherClientOnce() throws Exception {
        Recorder serverRec = new Recorder();
        Recorder anRec = new Recorder();
        Recorder binhRec = new Recorder();
        Recorder cuongRec = new Recorder();
        startQuartet(serverRec, anRec, binhRec, cuongRec);

        an.sendChat("xin chao ca nha", ChatScope.BROADCAST, List.of());

        assertEquals("An:xin chao ca nha", binhRec.awaitChat());
        assertEquals("An:xin chao ca nha", cuongRec.awaitChat());
        serverRec.assertSilent("a server relays chat, it does not take part in it");
        anRec.assertSilent("the sender must not receive its own message back");
    }

    @Test
    void emptyTargetsAreTreatedAsABroadcast() throws Exception {
        Recorder serverRec = new Recorder();
        Recorder anRec = new Recorder();
        Recorder binhRec = new Recorder();
        Recorder cuongRec = new Recorder();
        startQuartet(serverRec, anRec, binhRec, cuongRec);

        // A UI whose selection was emptied sends an empty list; that must mean "everyone".
        an.sendChat("moi nguoi", ChatScope.UNICAST, List.of());

        assertEquals("An:moi nguoi", binhRec.awaitChat());
        assertEquals("An:moi nguoi", cuongRec.awaitChat());
    }

    // ------------------------------------------------- unreachable addresses

    @Test
    void aMessageAddressedToAnAbsentClientReachesNobody() throws Exception {
        Recorder serverRec = new Recorder();
        Recorder anRec = new Recorder();
        Recorder binhRec = new Recorder();
        Recorder cuongRec = new Recorder();
        startQuartet(serverRec, anRec, binhRec, cuongRec);

        an.sendChat("gui cho ai do", ChatScope.UNICAST, List.of(99));

        serverRec.assertSilent("client #99 is not in the session");
        binhRec.assertSilent("client #99 is not in the session");
        cuongRec.assertSilent("client #99 is not in the session");
    }

    @Test
    void addressingYourselfReachesNobody() throws Exception {
        Recorder serverRec = new Recorder();
        Recorder anRec = new Recorder();
        Recorder binhRec = new Recorder();
        Recorder cuongRec = new Recorder();
        startQuartet(serverRec, anRec, binhRec, cuongRec);

        an.sendChat("cho chinh toi", ChatScope.UNICAST, List.of(idOf(an, "An")));

        serverRec.assertSilent("the message was addressed to its own sender");
        binhRec.assertSilent("the message was addressed to its own sender");
        anRec.assertSilent("the sender must not receive its own message back");
    }

    // ------------------------------------------------------- received labels

    @Test
    void aReceiverLearnsHowTheMessageWasAddressed() throws Exception {
        Recorder serverRec = new Recorder();
        Recorder anRec = new Recorder();
        Recorder binhRec = new Recorder();
        Recorder cuongRec = new Recorder();
        startQuartet(serverRec, anRec, binhRec, cuongRec);

        an.sendChat("hop nhom", ChatScope.MULTICAST,
                List.of(idOf(an, "Binh"), idOf(an, "Cuong")));

        Received atBinh = binhRec.awaitAddressed();
        assertEquals(ChatScope.MULTICAST, atBinh.scope());
        assertEquals(List.of("Binh", "Cuong"), atBinh.audience());
        assertEquals("hop nhom", atBinh.message());

        Received atCuong = cuongRec.awaitAddressed();
        assertEquals(ChatScope.MULTICAST, atCuong.scope());
        assertEquals(List.of("Binh", "Cuong"), atCuong.audience(),
                "the audience reads the same on every screen the message reached");
    }

    @Test
    void aUnicastArrivesMarkedAsUnicast() throws Exception {
        Recorder serverRec = new Recorder();
        Recorder anRec = new Recorder();
        Recorder binhRec = new Recorder();
        Recorder cuongRec = new Recorder();
        startQuartet(serverRec, anRec, binhRec, cuongRec);

        an.sendChat("chi rieng", ChatScope.UNICAST, List.of(idOf(an, "Binh")));

        Received atBinh = binhRec.awaitAddressed();
        assertEquals(ChatScope.UNICAST, atBinh.scope());
        assertEquals(List.of("Binh"), atBinh.audience());
    }

    @Test
    void aBroadcastArrivesWithNoAudience() throws Exception {
        Recorder serverRec = new Recorder();
        Recorder anRec = new Recorder();
        Recorder binhRec = new Recorder();
        Recorder cuongRec = new Recorder();
        startQuartet(serverRec, anRec, binhRec, cuongRec);

        an.sendChat("ca nha oi", ChatScope.BROADCAST, List.of());

        Received atBinh = binhRec.awaitAddressed();
        assertEquals(ChatScope.BROADCAST, atBinh.scope());
        assertEquals(List.of(), atBinh.audience());
    }

    // ------------------------------------------------------------------ roles

    @Test
    void theServerHasNoIdAndCannotSendChat() throws Exception {
        Recorder serverRec = new Recorder();
        startQuartet(serverRec, new Recorder(), new Recorder(), new Recorder());

        assertEquals(ChatSession.UNASSIGNED_CLIENT_ID, server.selfClientId(),
                "the server is not a participant, so it has no id");
        assertEquals(ChatSession.Role.SERVER, server.role());
        assertEquals(ChatSession.Role.CLIENT, an.role());

        assertThrows(IllegalStateException.class, () -> server.sendChat("toi la server"),
                "a server relays chat; it has no path to originate it");
        assertThrows(IllegalStateException.class,
                () -> server.sendChat("toi la server", ChatScope.BROADCAST, List.of()));
    }

    @Test
    void theServerIsInNoOnesDirectory() throws Exception {
        startQuartet(new Recorder(), new Recorder(), new Recorder(), new Recorder());

        assertFalse(namesOf(an.clients()).contains("Server"));
        assertFalse(namesOf(binh.clients()).contains("Server"));
        assertFalse(namesOf(cuong.clients()).contains("Server"));
        assertFalse(namesOf(server.clients()).contains("Server"));

        assertEquals(List.of("An", "Binh", "Cuong"), namesOf(server.clients()),
                "the server's directory lists exactly its clients, in join order");
    }

    // ------------------------------------------------------------- the client directory

    @Test
    void everyClientLearnsItsOwnIdAndTheWholeDirectory() throws Exception {
        startQuartet(new Recorder(), new Recorder(), new Recorder(), new Recorder());

        assertEquals(3, server.clients().size());
        assertEquals(List.of("An", "Binh", "Cuong"), namesOf(server.clients()));

        int anId = idOf(server, "An");
        int binhId = idOf(server, "Binh");
        int cuongId = idOf(server, "Cuong");
        assertNotEquals(anId, binhId);
        assertNotEquals(binhId, cuongId);
        assertNotEquals(anId, cuongId);

        // Each client's own copy of the directory names it with the id the server gave it.
        assertEquals(anId, an.selfClientId());
        assertEquals(binhId, binh.selfClientId());
        assertEquals(cuongId, cuong.selfClientId());
        assertEquals(server.clients(), an.clients(), "every client sees the same directory");
    }

    @Test
    void theDirectoryShrinksWhenAClientLeaves() throws Exception {
        startQuartet(new Recorder(), new Recorder(), new Recorder(), new Recorder());

        cuong.close();

        awaitRosterSize(server, 2);
        awaitRosterSize(an, 2);
        assertFalse(namesOf(server.clients()).contains("Cuong"));
        assertFalse(namesOf(an.clients()).contains("Cuong"));
    }

    @Test
    void restartingTheServerRenumbersFromOne() throws Exception {
        server = new ChatSession(new Recorder(), "Server");
        server.startServer(0);
        an = new ChatSession(new Recorder(), "An");
        an.startClient("127.0.0.1", server.port(), 5000);
        awaitRoster(server, "An");
        assertEquals(ChatSession.FIRST_CLIENT_ID, idOf(server, "An"));

        server.disconnect();               // the session ends: ids and directory go with it
        an.close();
        an = null;

        server.startServer(0);
        binh = new ChatSession(new Recorder(), "Binh");
        binh.startClient("127.0.0.1", server.port(), 5000);
        awaitRoster(server, "Binh");

        assertEquals(ChatSession.FIRST_CLIENT_ID, idOf(server, "Binh"),
                "a restarted session numbers its clients from #1 again");
        assertEquals(List.of("Binh"), namesOf(server.clients()));
        assertEquals(ChatSession.UNASSIGNED_CLIENT_ID, server.selfClientId());
    }

    @Test
    void theLastClientLeavingDoesNotEndTheServer() throws Exception {
        Recorder serverRec = new Recorder();
        server = new ChatSession(serverRec, "Server");
        server.startServer(0);
        an = new ChatSession(new Recorder(), "An");
        an.startClient("127.0.0.1", server.port(), 5000);
        awaitRoster(server, "An");

        an.close();
        an = null;
        awaitRosterSize(server, 0);

        assertNotEquals(ChatSession.STATUS_DISCONNECTED, serverRec.lastStatus.get(),
                "an empty directory is not the end of the session for a server");

        // ... and a later client is still accepted. The session was never restarted, so it carries on
        // counting from where it was.
        binh = new ChatSession(new Recorder(), "Binh");
        binh.startClient("127.0.0.1", server.port(), 5000);
        awaitRoster(server, "Binh");
        assertEquals(ChatSession.FIRST_CLIENT_ID + 1, idOf(server, "Binh"),
                "only a restarted session renumbers from #1");
    }

    private static void awaitRosterSize(ChatSession session, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (session.clients().size() != expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("directory size never reached " + expected
                        + ", last was " + session.clients().size() + ": " + session.clients());
            }
            Thread.sleep(20);
        }
    }

    private static List<String> namesOf(List<ClientInfo> directory) {
        return directory.stream().map(ClientInfo::name).toList();
    }
}
