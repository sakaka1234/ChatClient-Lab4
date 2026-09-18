package org.example.p2pchat.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(60)
class MultiPeerChatTest {

    private static final long WAIT_SECONDS = 15;

    private PeerSession host;
    private PeerSession an;
    private PeerSession binh;

    @AfterEach
    void tearDown() {
        closeQuietly(binh);
        closeQuietly(an);
        closeQuietly(host);
    }

    private static void closeQuietly(PeerSession session) {
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
        public void onFileOffered(int fileId, String sender, String fileName, long fileSize) {
        }

        @Override
        public void onFileProgress(int fileId, String sender, String fileName, long bytes, long total) {
        }

        @Override
        public void onFileDeclined(int fileId, String sender, String fileName, String reason) {
        }

        @Override
        public void onFileEvent(int fileId, String sender, String fileName, String detail,
                                Path savedPath, boolean failed) {
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

    private void awaitPeerCount(PeerSession session, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (session.peerCount() != expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("peer count never reached " + expected
                        + ", last was " + session.peerCount());
            }
            Thread.sleep(20);
        }
    }

    private void startTrio(Path dir, Recorder hostRec, Recorder anRec, Recorder binhRec) throws Exception {
        host = new PeerSession(dir.resolve("host"), hostRec, "Host");
        an = new PeerSession(dir.resolve("an"), anRec, "An");
        binh = new PeerSession(dir.resolve("binh"), binhRec, "Binh");

        host.startHost(0);
        int port = host.port();
        an.startClient("127.0.0.1", port, 5000);
        binh.startClient("127.0.0.1", port, 5000);

        awaitPeerCount(host, 2);
    }

    @Test
    void hostSeesBothConnectedClients(@TempDir Path dir) throws Exception {
        Recorder hostRec = new Recorder();
        startTrio(dir, hostRec, new Recorder(), new Recorder());

        assertEquals(2, host.peerCount());
        assertTrue(an.isConnected());
        assertTrue(binh.isConnected());
    }

    @Test
    void clientMessageReachesTheHostAndTheOtherClientWithTheSendersName(@TempDir Path dir)
            throws Exception {
        Recorder hostRec = new Recorder();
        Recorder anRec = new Recorder();
        Recorder binhRec = new Recorder();
        startTrio(dir, hostRec, anRec, binhRec);

        an.sendChat("xin chao ca nha");

        assertEquals("An:xin chao ca nha", hostRec.awaitChat());
        assertEquals("An:xin chao ca nha", binhRec.awaitChat());
        assertTrue(anRec.chats.isEmpty(), "the sender must not receive its own message back");
    }

    @Test
    void hostMessageReachesEveryClientWithTheHostName(@TempDir Path dir) throws Exception {
        Recorder hostRec = new Recorder();
        Recorder anRec = new Recorder();
        Recorder binhRec = new Recorder();
        startTrio(dir, hostRec, anRec, binhRec);

        host.sendChat("chao hai ban");

        assertEquals("Host:chao hai ban", anRec.awaitChat());
        assertEquals("Host:chao hai ban", binhRec.awaitChat());
    }

    @Test
    void hostDoesNotEchoItsOwnMessageBackToItsOwnListener(@TempDir Path dir) throws Exception {
        Recorder hostRec = new Recorder();
        Recorder anRec = new Recorder();
        Recorder binhRec = new Recorder();
        startTrio(dir, hostRec, anRec, binhRec);

        host.sendChat("tin cua host");

        assertEquals("Host:tin cua host", anRec.awaitChat());
        assertEquals("Host:tin cua host", binhRec.awaitChat());
        assertTrue(hostRec.chats.isEmpty(),
                "the host UI renders its own outbound bubble, so the session must not echo it back");
    }

    @Test
    void secondClientsMessageReachesTheFirstClient(@TempDir Path dir) throws Exception {
        Recorder hostRec = new Recorder();
        Recorder anRec = new Recorder();
        Recorder binhRec = new Recorder();
        startTrio(dir, hostRec, anRec, binhRec);

        binh.sendChat("toi la Binh");

        assertEquals("Binh:toi la Binh", hostRec.awaitChat());
        assertEquals("Binh:toi la Binh", anRec.awaitChat());
    }
}
