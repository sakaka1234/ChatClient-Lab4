package org.example.p2pchat.network;

import org.example.p2pchat.protocol.MessageType;
import org.example.p2pchat.protocol.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ConnectException;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(15)
class ConnectionTest {

    private static final long WAIT_SECONDS = 5;

    private Acceptor server;
    private Connection serverSide;
    private Connection clientSide;

    @AfterEach
    void tearDown() {
        closeQuietly(clientSide);
        closeQuietly(serverSide);
        closeQuietly(server);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // best effort cleanup
        }
    }

    private static final class Recorder implements Connection.Listener {
        final BlockingQueue<Packet> packets = new ArrayBlockingQueue<>(64);
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicReference<Throwable> cause = new AtomicReference<>();

        @Override
        public void onPacket(Packet packet) {
            packets.add(packet);
        }

        @Override
        public void onDisconnected(Throwable reason) {
            cause.set(reason);
            closed.countDown();
        }

        Packet awaitPacket() throws InterruptedException {
            Packet packet = packets.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertTrue(packet != null, "expected a packet but none arrived");
            return packet;
        }
    }

    private void establishPair(Recorder serverRecorder, Recorder clientRecorder) throws Exception {
        server = new Acceptor(0);
        CountDownLatch accepted = new CountDownLatch(1);
        AtomicReference<IOException> acceptFailure = new AtomicReference<>();

        Thread acceptor = new Thread(() -> {
            try {
                serverSide = server.accept(serverRecorder);
                accepted.countDown();
            } catch (IOException e) {
                acceptFailure.set(e);
            }
        }, "test-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();

        clientSide = Connector.connect("127.0.0.1", server.port(), 5000, clientRecorder);

        assertTrue(accepted.await(WAIT_SECONDS, TimeUnit.SECONDS), "server did not accept in time");
        if (acceptFailure.get() != null) {
            throw acceptFailure.get();
        }
        serverSide.start();
        clientSide.start();
    }

    @Test
    void serverAcceptsClientAndReportsAddresses() throws Exception {
        establishPair(new Recorder(), new Recorder());

        assertTrue(clientSide.isOpen());
        assertTrue(serverSide.isOpen());
        assertEquals("127.0.0.1", clientSide.remoteAddress().getAddress().getHostAddress());
        assertEquals("127.0.0.1", serverSide.remoteAddress().getAddress().getHostAddress());
        assertTrue(serverSide.remoteAddress().getPort() > 0);
    }

    @Test
    void deliversChatFromClientToServer() throws Exception {
        Recorder serverRecorder = new Recorder();
        Recorder clientRecorder = new Recorder();
        establishPair(serverRecorder, clientRecorder);

        clientSide.send(Packet.chat("hello from client"));

        assertEquals("hello from client", serverRecorder.awaitPacket().chatText());
    }

    @Test
    void deliversChatFromServerToClient() throws Exception {
        Recorder serverRecorder = new Recorder();
        Recorder clientRecorder = new Recorder();
        establishPair(serverRecorder, clientRecorder);

        serverSide.send(Packet.chat("hello from server"));

        assertEquals("hello from server", clientRecorder.awaitPacket().chatText());
    }

    @Test
    void deliversConsecutivePacketsInOrder() throws Exception {
        Recorder serverRecorder = new Recorder();
        Recorder clientRecorder = new Recorder();
        establishPair(serverRecorder, clientRecorder);

        for (int i = 0; i < 20; i++) {
            clientSide.send(Packet.chat("message-" + i));
        }

        for (int i = 0; i < 20; i++) {
            assertEquals("message-" + i, serverRecorder.awaitPacket().chatText());
        }
    }

    @Test
    void deliversALargeChatPayloadIntact() throws Exception {
        Recorder serverRecorder = new Recorder();
        Recorder clientRecorder = new Recorder();
        establishPair(serverRecorder, clientRecorder);

        // 1 MiB of text: a frame far larger than any socket buffer, so it cannot arrive in one read.
        Random random = new Random(7);
        StringBuilder builder = new StringBuilder(1024 * 1024);
        for (int i = 0; i < 1024 * 1024; i++) {
            builder.append((char) ('a' + random.nextInt(26)));
        }
        String text = builder.toString();

        clientSide.send(Packet.chat(text));

        assertEquals(text, serverRecorder.awaitPacket().chatText());
    }

    @Test
    void deliversDisconnectPacket() throws Exception {
        Recorder serverRecorder = new Recorder();
        Recorder clientRecorder = new Recorder();
        establishPair(serverRecorder, clientRecorder);

        clientSide.send(Packet.disconnect());

        assertEquals(MessageType.DISCONNECT, serverRecorder.awaitPacket().type());
    }

    @Test
    void listenerNotifiedWhenTheClientClosesItsSocket() throws Exception {
        Recorder serverRecorder = new Recorder();
        Recorder clientRecorder = new Recorder();
        establishPair(serverRecorder, clientRecorder);

        clientSide.close();

        assertTrue(serverRecorder.closed.await(WAIT_SECONDS, TimeUnit.SECONDS),
                "server listener was not notified that the client closed its socket");
        assertFalse(serverSide.isOpen());
    }

    @Test
    void closeIsIdempotent() throws Exception {
        establishPair(new Recorder(), new Recorder());

        clientSide.close();
        clientSide.close();

        assertFalse(clientSide.isOpen());
    }

    @Test
    void sendAfterCloseThrows() throws Exception {
        establishPair(new Recorder(), new Recorder());
        clientSide.close();

        assertThrows(IOException.class, () -> clientSide.send(Packet.chat("too late")));
    }

    @Test
    void connectingToClosedPortFails() throws IOException {
        int freePort;
        try (Acceptor probe = new Acceptor(0)) {
            freePort = probe.port();
        }

        assertThrows(IOException.class,
                () -> Connector.connect("127.0.0.1", freePort, 2000, new Recorder()));
    }

    @Test
    void connectingToUnroutableAddressTimesOut() {
        assertThrows(IOException.class,
                () -> Connector.connect("192.0.2.1", 65000, 500, new Recorder()));
    }

    @Test
    void serverRejectsInvalidPort() {
        assertThrows(IllegalArgumentException.class, () -> new Acceptor(70000));
        assertThrows(IllegalArgumentException.class, () -> new Acceptor(-1));
    }

    @Test
    void connectRejectsInvalidPort() {
        assertThrows(IllegalArgumentException.class,
                () -> Connector.connect("127.0.0.1", 0, 1000, new Recorder()));
    }

    @Test
    void secondBindOnUsedPortFails() throws Exception {
        try (Acceptor first = new Acceptor(0);
             Acceptor second = new Acceptor(0)) {
            assertThrows(IOException.class, () -> new Acceptor(first.port()));
            assertTrue(second.port() > 0);
        }
    }

    @Test
    void clientConnectFailureUsesConnectExceptionType() {
        assertThrows(ConnectException.class, () -> {
            try (Acceptor probe = new Acceptor(0)) {
                int port = probe.port();
                probe.close();
                Connector.connect("127.0.0.1", port, 1000, new Recorder());
            }
        });
    }
}
