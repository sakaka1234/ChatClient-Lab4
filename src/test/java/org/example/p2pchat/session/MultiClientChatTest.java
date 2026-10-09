package org.example.p2pchat.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One server, two clients: the server carries the conversation and stays out of it. */
@Timeout(60)
class MultiClientChatTest {

    private static final long WAIT_SECONDS = 15;

    private ChatSession server;
    private ChatSession an;
    private ChatSession binh;

    @AfterEach
    void tearDown() {
        closeQuietly(binh);
        closeQuietly(an);
        closeQuietly(server);
    }

    private static void closeQuietly(ChatSession session) {
        if (session != null) {
            session.close();
        }
    }

    private static final class Recorder implements SessionListener {
        final BlockingQueue<String> chats = new ArrayBlockingQueue<>(64);
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
        public void onDisconnected(String reason) {
        }

        String awaitChat() throws InterruptedException {
            String value = chats.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertTrue(value != null, "expected a chat message");
            return value;
        }

        void assertSilent(String what) throws InterruptedException {
            String received = chats.poll(400, TimeUnit.MILLISECONDS);
            assertTrue(received == null, what + ", but it arrived as: " + received);
        }
    }

    private void awaitClients(ChatSession session, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (session.clients().size() != expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("directory never held " + expected + " client(s), last was "
                        + session.clients());
            }
            Thread.sleep(20);
        }
    }

    private void startTrio(Recorder serverRec, Recorder anRec, Recorder binhRec) throws Exception {
        server = new ChatSession(serverRec, "Server");
        server.startServer(0);
        int port = server.port();

        an = new ChatSession(anRec, "An");
        an.startClient("127.0.0.1", port, 5000);
        awaitClients(server, 1);

        binh = new ChatSession(binhRec, "Binh");
        binh.startClient("127.0.0.1", port, 5000);
        awaitClients(server, 2);
    }

    @Test
    void theServerSeesBothConnectedClients() throws Exception {
        startTrio(new Recorder(), new Recorder(), new Recorder());

        assertEquals(2, server.connectionCount());
        assertEquals(2, server.clients().size());
        assertTrue(an.isConnected());
        assertTrue(binh.isConnected());
    }

    @Test
    void aClientMessageReachesTheOtherClientWithTheSendersName() throws Exception {
        Recorder serverRec = new Recorder();
        Recorder anRec = new Recorder();
        Recorder binhRec = new Recorder();
        startTrio(serverRec, anRec, binhRec);

        an.sendChat("xin chao ca nha");

        assertEquals("An:xin chao ca nha", binhRec.awaitChat());
        serverRec.assertSilent("the server relays the message, it does not receive it");
        anRec.assertSilent("the sender must not receive its own message back");
    }

    @Test
    void theSecondClientsMessageReachesTheFirst() throws Exception {
        Recorder serverRec = new Recorder();
        Recorder anRec = new Recorder();
        Recorder binhRec = new Recorder();
        startTrio(serverRec, anRec, binhRec);

        binh.sendChat("toi la Binh");

        assertEquals("Binh:toi la Binh", anRec.awaitChat());
        serverRec.assertSilent("the server is not a participant");
    }

    @Test
    void theServerHasNothingToSend() throws Exception {
        Recorder serverRec = new Recorder();
        startTrio(serverRec, new Recorder(), new Recorder());

        assertThrows(IllegalStateException.class, () -> server.sendChat("chao hai ban"));
        assertThrows(IllegalStateException.class,
                () -> server.sendChat("chao hai ban", org.example.p2pchat.protocol.ChatScope.BROADCAST,
                        java.util.List.of()));
        assertEquals(0, server.relayedCount(), "nothing was relayed, because nothing was sent");
    }

    @Test
    void aClientLeavingLeavesTheOtherOneTalking() throws Exception {
        Recorder serverRec = new Recorder();
        Recorder anRec = new Recorder();
        Recorder binhRec = new Recorder();
        startTrio(serverRec, anRec, binhRec);

        binh.close();
        binh = null;
        awaitClients(server, 1);

        an.sendChat("con ai khong?");

        anRec.assertSilent("An is the sender, so it draws its own bubble");
        serverRec.assertSilent("the server relays, it does not receive");
        assertEquals(1, server.clients().size(), "the directory followed Binh out");
        assertEquals("An", server.clients().get(0).name());
    }
}
