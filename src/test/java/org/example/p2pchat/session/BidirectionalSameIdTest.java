package org.example.p2pchat.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(60)
class BidirectionalSameIdTest {

    private static final long WAIT = 15;

    private static final class Host implements SessionListener {
        final BlockingQueue<int[]> offers = new ArrayBlockingQueue<>(8);
        final BlockingQueue<String> completed = new ArrayBlockingQueue<>(8);
        final CountDownLatch connected = new CountDownLatch(1);

        @Override
        public void onStatusChanged(String status) {
            if (status.startsWith("Connected")) {
                connected.countDown();
            }
        }

        void awaitConnected() throws InterruptedException {
            assertTrue(connected.await(WAIT, TimeUnit.SECONDS), "peer never reported Connected");
        }

        @Override
        public void onChatMessage(String sender, String message) {
        }

        @Override
        public void onFileOffered(int fileId, String direction, String fileName, long fileSize) {
            offers.add(new int[]{fileId});
        }

        @Override
        public void onFileProgress(int fileId, String direction, String fileName, long bytes, long total) {
        }

        @Override
        public void onFileEvent(int fileId, String direction, String fileName, String detail,
                               Path savedPath, boolean failed) {
            if (!failed) {
                completed.add(direction + ":" + fileId + ":" + fileName + ":" + savedPath);
            }
        }

        String awaitReceiveCompleted() throws InterruptedException {
            long deadline = System.nanoTime() + WAIT * 1_000_000_000L;
            while (System.nanoTime() < deadline) {
                String value = completed.poll(WAIT, TimeUnit.SECONDS);
                if (value == null) {
                    break;
                }
                if (value.startsWith("Receiving:")) {
                    return value;
                }
            }
            throw new AssertionError("no receive-side completion observed");
        }

        @Override
        public void onFileDeclined(int fileId, String direction, String fileName, String reason) {
        }

        @Override
        public void onDisconnected(String reason) {
        }

        int awaitOfferId() throws InterruptedException {
            int[] value = offers.poll(WAIT, TimeUnit.SECONDS);
            assertNotNull(value, "expected an offer");
            return value[0];
        }

    }

    @Test
    void bothPeersSendFilesAtTheSameTimeAndBothReceive(@TempDir Path dir) throws Exception {
        Path hostBase = Files.createDirectory(dir.resolve("host-base"));
        Path clientBase = Files.createDirectory(dir.resolve("client-base"));
        Path hostChosen = Files.createDirectory(dir.resolve("host-chosen"));
        Path clientChosen = Files.createDirectory(dir.resolve("client-chosen"));

        byte[] hostPayload = "host side payload".getBytes(StandardCharsets.UTF_8);
        byte[] clientPayload = "client side payload".getBytes(StandardCharsets.UTF_8);
        Path hostFile = Files.write(dir.resolve("from-host.txt"), hostPayload);
        Path clientFile = Files.write(dir.resolve("from-client.txt"), clientPayload);

        Host hostListener = new Host();
        Host clientListener = new Host();
        PeerSession host = new PeerSession(hostBase, hostListener);
        PeerSession client = new PeerSession(clientBase, clientListener);

        try {
            host.startHost(0);
            client.startClient("127.0.0.1", host.port(), 5000);
            hostListener.awaitConnected();
            clientListener.awaitConnected();

            // both start at file id 1
            host.sendFile(hostFile);
            client.sendFile(clientFile);

            int hostOfferId = hostListener.awaitOfferId();
            int clientOfferId = clientListener.awaitOfferId();
            assertEquals(1, hostOfferId, "each peer numbers its own files from 1");
            assertEquals(1, clientOfferId, "each peer numbers its own files from 1");

            assertTrue(host.acceptFileOffer(hostOfferId, hostChosen));
            assertTrue(client.acceptFileOffer(clientOfferId, clientChosen));

            String hostDone = hostListener.awaitReceiveCompleted();
            String clientDone = clientListener.awaitReceiveCompleted();

            assertArrayEquals(clientPayload, Files.readAllBytes(hostChosen.resolve("from-client.txt")));
            assertArrayEquals(hostPayload, Files.readAllBytes(clientChosen.resolve("from-host.txt")));
            assertTrue(hostDone.contains("from-client.txt"), hostDone);
            assertTrue(clientDone.contains("from-host.txt"), clientDone);
        } finally {
            client.close();
            host.close();
        }
    }

    @Test
    void chatStillFlowsWhileBothDirectionsTransfer(@TempDir Path dir) throws Exception {
        Path hostBase = Files.createDirectory(dir.resolve("host-base"));
        Path clientBase = Files.createDirectory(dir.resolve("client-base"));
        Path hostChosen = Files.createDirectory(dir.resolve("host-chosen"));
        Path clientChosen = Files.createDirectory(dir.resolve("client-chosen"));

        Path hostFile = Files.write(dir.resolve("h.bin"), new byte[120_000]);
        Path clientFile = Files.write(dir.resolve("c.bin"), new byte[80_000]);

        Host hostListener = new Host();
        Host clientListener = new Host();
        PeerSession host = new PeerSession(hostBase, hostListener);
        PeerSession client = new PeerSession(clientBase, clientListener);

        CountDownLatch hostChat = new CountDownLatch(1);
        CountDownLatch clientChat = new CountDownLatch(1);
        SessionListener hostChatListener = chatCounter(hostChat);
        SessionListener clientChatListener = chatCounter(clientChat);

        PeerSession host2 = new PeerSession(hostBase, hostChatListener);
        PeerSession client2 = new PeerSession(clientBase, clientChatListener);

        try {
            host2.startHost(0);
            client2.startClient("127.0.0.1", host2.port(), 5000);

            client2.sendChat("hello during transfer");
            assertTrue(hostChat.await(WAIT, TimeUnit.SECONDS), "chat must still arrive");

            host2.sendChat("reply during transfer");
            assertTrue(clientChat.await(WAIT, TimeUnit.SECONDS), "chat must still arrive");

            assertTrue(host2.isConnected());
            assertTrue(client2.isConnected());
        } finally {
            client2.close();
            host2.close();
            client.close();
            host.close();
        }
    }

    private static SessionListener chatCounter(CountDownLatch latch) {
        return new SessionListener() {
            @Override
            public void onStatusChanged(String status) {
            }

            @Override
            public void onChatMessage(String sender, String message) {
                latch.countDown();
            }

            @Override
            public void onFileOffered(int fileId, String direction, String fileName, long fileSize) {
            }

            @Override
            public void onFileProgress(int fileId, String direction, String fileName, long bytes, long total) {
            }

            @Override
            public void onFileEvent(int fileId, String direction, String fileName, String detail,
                                   Path savedPath, boolean failed) {
            }

            @Override
            public void onFileDeclined(int fileId, String direction, String fileName, String reason) {
            }

            @Override
            public void onDisconnected(String reason) {
            }
        };
    }
}
