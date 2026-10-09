package org.example.p2pchat.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One server and one client: connecting, relaying, and the two ways a session ends. */
@Timeout(30)
class ChatSessionTest {

    private static final long WAIT_SECONDS = 10;

    private ChatSession server;
    private ChatSession client;

    @AfterEach
    void tearDown() {
        closeQuietly(client);
        closeQuietly(server);
    }

    private static void closeQuietly(ChatSession session) {
        if (session != null) {
            session.close();
        }
    }

    private static final class Recorder implements SessionListener {
        final BlockingQueue<String> statuses = new ArrayBlockingQueue<>(64);
        final BlockingQueue<String> chats = new ArrayBlockingQueue<>(64);
        final CountDownLatch disconnected = new CountDownLatch(1);
        final AtomicReference<String> lastStatus = new AtomicReference<>("");

        @Override
        public void onStatusChanged(String status) {
            lastStatus.set(status);
            statuses.add(status);
        }

        @Override
        public void onChatMessage(String sender, String message) {
            chats.add(sender + ":" + message);
        }

        @Override
        public void onDisconnected(String reason) {
            disconnected.countDown();
        }

        String awaitStatusContaining(String needle) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            String current = lastStatus.get();
            while (!current.contains(needle)) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("status never contained '" + needle + "', last was: " + current);
                }
                String next = statuses.poll(200, TimeUnit.MILLISECONDS);
                current = next == null ? lastStatus.get() : next;
            }
            return current;
        }

        String awaitChat() throws InterruptedException {
            String value = chats.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertTrue(value != null, "expected a chat message");
            return value;
        }
    }

    private void startPair(Recorder serverRecorder, Recorder clientRecorder) throws Exception {
        server = new ChatSession(serverRecorder);
        client = new ChatSession(clientRecorder);

        server.startServer(0);
        serverRecorder.awaitStatusContaining("Listening");

        client.startClient("127.0.0.1", server.port(), 5000);
        clientRecorder.awaitStatusContaining("Connected");
        awaitClients(server, 1);
        // "Connected" is reported the moment the socket is up; the id arrives with the directory the
        // server pushes a moment later.
        awaitSelfId(client, ChatSession.FIRST_CLIENT_ID);
    }

    private static void awaitSelfId(ChatSession session, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (session.selfClientId() != expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("client never learned #" + expected + ", last was #"
                        + session.selfClientId());
            }
            Thread.sleep(20);
        }
    }

    private static void awaitClients(ChatSession session, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (session.clients().size() != expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("directory never held " + expected + " client(s), last was "
                        + session.clients());
            }
            Thread.sleep(20);
        }
    }

    @Test
    void serverListensAndClientConnects() throws Exception {
        startPair(new Recorder(), new Recorder());

        assertTrue(server.isConnected());
        assertTrue(client.isConnected());
        assertEquals(ChatSession.Role.SERVER, server.role());
        assertEquals(ChatSession.Role.CLIENT, client.role());
        assertEquals("127.0.0.1:" + server.port(), client.remoteDescription());
    }

    @Test
    void theServerKnowsTheClientByNameAndGivesItTheFirstId() throws Exception {
        Recorder serverRecorder = new Recorder();
        startPair(serverRecorder, new Recorder());

        assertEquals(1, server.clients().size());
        ClientInfo only = server.clients().get(0);
        assertEquals(ChatSession.FIRST_CLIENT_ID, only.id());
        assertEquals(client.displayName(), only.name());
        assertEquals(ChatSession.FIRST_CLIENT_ID, client.selfClientId(),
                "the client learns its own id from the directory the server pushes");
    }

    @Test
    void theServerIsNotAConversationPartner() throws Exception {
        Recorder serverRecorder = new Recorder();
        Recorder clientRecorder = new Recorder();
        startPair(serverRecorder, clientRecorder);

        // A broadcast from the only client: there is nobody else to copy it to.
        client.sendChat("hello");

        assertTrue(serverRecorder.chats.isEmpty(),
                "the server relays chat, it does not receive it");
        assertTrue(clientRecorder.chats.isEmpty(),
                "the sender must not receive its own message back");
        assertEquals(ChatSession.FIRST_CLIENT_ID, server.clients().get(0).id(),
                "the client is #1: no id is spent on the server");
        assertThrows(IllegalStateException.class, () -> server.sendChat("hello"));
    }

    @Test
    void aRelayedMessageCarriesTheSendersDisplayName() throws Exception {
        Recorder serverRecorder = new Recorder();
        Recorder anRecorder = new Recorder();
        Recorder binhRecorder = new Recorder();
        server = new ChatSession(serverRecorder, "Server");
        server.startServer(0);
        ChatSession an = new ChatSession(anRecorder, "Chủ nhà");
        ChatSession binh = new ChatSession(binhRecorder, "Binh");
        try {
            an.startClient("127.0.0.1", server.port(), 5000);
            awaitClients(server, 1);
            binh.startClient("127.0.0.1", server.port(), 5000);
            awaitClients(server, 2);

            an.sendChat("chào");

            assertEquals("Chủ nhà:chào", binhRecorder.awaitChat(),
                    "the name a client chose is the name the other client sees");
            assertTrue(serverRecorder.chats.isEmpty());
        } finally {
            an.close();
            binh.close();
        }
    }

    @Test
    void aClientLeavingDoesNotEndTheServerSession() throws Exception {
        Recorder serverRecorder = new Recorder();
        Recorder clientRecorder = new Recorder();
        startPair(serverRecorder, clientRecorder);

        client.disconnect();

        assertTrue(clientRecorder.disconnected.await(WAIT_SECONDS, TimeUnit.SECONDS),
                "the client's own session is over, so it is told");
        assertFalse(serverRecorder.disconnected.await(WAIT_SECONDS, TimeUnit.SECONDS),
                "an empty directory is not the end of a server's session");
        awaitClients(server, 0);
        assertEquals("Listening on port " + server.port(), serverRecorder.lastStatus.get(),
                "the server is still listening");
    }

    @Test
    void losingTheServerEndsTheClientSession() throws Exception {
        Recorder serverRecorder = new Recorder();
        Recorder clientRecorder = new Recorder();
        startPair(serverRecorder, clientRecorder);

        server.close();

        assertTrue(clientRecorder.disconnected.await(WAIT_SECONDS, TimeUnit.SECONDS),
                "the client's only socket is the one to the server");
        assertEquals(ChatSession.STATUS_SERVER_DISCONNECTED, clientRecorder.lastStatus.get());
    }

    @Test
    void sendChatWhenNotConnectedReportsStatus() {
        Recorder recorder = new Recorder();
        client = new ChatSession(recorder);

        client.sendChat("nobody is listening");

        assertEquals("Not connected", recorder.lastStatus.get());
    }

    @Test
    void clientConnectToUnusedPortReportsFailure() throws IOException {
        Recorder recorder = new Recorder();
        client = new ChatSession(recorder);
        int freePort;
        try (var probe = new java.net.ServerSocket(0)) {
            freePort = probe.getLocalPort();
        }

        assertThrows(ConnectException.class, () -> client.startClient("127.0.0.1", freePort, 1000));
        assertEquals(ChatSession.STATUS_CONNECTION_FAILED, recorder.lastStatus.get());
    }

    @Test
    void invalidHostReportsFailure() {
        Recorder recorder = new Recorder();
        client = new ChatSession(recorder);

        assertThrows(IOException.class, () -> client.startClient("999.999.999.999", 5000, 1000));
        assertEquals(ChatSession.STATUS_CONNECTION_FAILED, recorder.lastStatus.get());
    }

    @Test
    void invalidPortRejected() {
        Recorder recorder = new Recorder();
        client = new ChatSession(recorder);

        assertThrows(IllegalArgumentException.class, () -> client.startServer(-5));
        assertThrows(IllegalArgumentException.class, () -> client.startClient("127.0.0.1", 0, 1000));
    }

    @Test
    void serverReportsPortInUse() throws Exception {
        Recorder recorder = new Recorder();
        server = new ChatSession(recorder);
        server.startServer(0);
        int usedPort = server.port();

        ChatSession second = new ChatSession(new Recorder());
        try {
            assertThrows(IOException.class, () -> second.startServer(usedPort));
        } finally {
            second.close();
        }
    }

    @Test
    void closeReleasesResourcesAndIsIdempotent() throws Exception {
        Recorder serverRecorder = new Recorder();
        Recorder clientRecorder = new Recorder();
        startPair(serverRecorder, clientRecorder);

        server.close();
        server.close();

        assertFalse(server.isConnected());
        assertThrows(IllegalStateException.class, () -> server.startServer(0));
    }

    @Test
    void aClientCanReconnectAfterDisconnecting() throws Exception {
        Recorder serverRecorder = new Recorder();
        Recorder clientRecorder = new Recorder();
        startPair(serverRecorder, clientRecorder);
        int port = server.port();

        client.disconnect();
        assertTrue(clientRecorder.disconnected.await(WAIT_SECONDS, TimeUnit.SECONDS));
        awaitClients(server, 0);

        client.startClient("127.0.0.1", port, 5000);
        clientRecorder.awaitStatusContaining("Connected");
        awaitClients(server, 1);
        awaitSelfId(client, ChatSession.FIRST_CLIENT_ID + 1);

        // The server numbers connections, not people, so the new socket is simply its next client —
        // only a restarted session renumbers from #1.
        assertEquals(ChatSession.FIRST_CLIENT_ID + 1, client.selfClientId(),
                "the reconnected socket is the server's second client");
    }
}
