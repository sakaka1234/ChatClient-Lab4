package org.example.p2pchat.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class PeerSessionTest {

    private static final long WAIT_SECONDS = 10;

    private PeerSession host;
    private PeerSession client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (host != null) {
            host.close();
        }
    }

    private static final class Recorder implements SessionListener {
        final BlockingQueue<String> statuses = new ArrayBlockingQueue<>(64);
        final BlockingQueue<String> chats = new ArrayBlockingQueue<>(64);
        final BlockingQueue<String> files = new ArrayBlockingQueue<>(64);
        final BlockingQueue<Integer> fileIds = new ArrayBlockingQueue<>(128);
        final BlockingQueue<String> offers = new ArrayBlockingQueue<>(64);
        final BlockingQueue<String> declined = new ArrayBlockingQueue<>(64);
        final CountDownLatch disconnected = new CountDownLatch(1);
        final AtomicReference<String> lastStatus = new AtomicReference<>("");
        final AtomicReference<String> firstFileThread = new AtomicReference<>();

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
        public void onFileOffered(int fileId, String direction, String fileName, long fileSize) {
            offers.add(fileId + ":" + direction + ":" + fileName + ":" + fileSize);
        }

        @Override
        public void onFileDeclined(int fileId, String direction, String fileName, String reason) {
            declined.add(fileId + ":" + direction + ":" + fileName + ":" + reason);
        }

        String awaitOffer() throws InterruptedException {
            String value = offers.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertTrue(value != null, "expected a file offer");
            return value;
        }

        String awaitDecline() throws InterruptedException {
            String value = declined.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertTrue(value != null, "expected a decline");
            return value;
        }

        @Override
        public void onFileProgress(int fileId, String direction, String fileName, long bytes, long total) {
            firstFileThread.compareAndSet(null, Thread.currentThread().getName());
            fileIds.add(fileId);
        }

        @Override
        public void onFileEvent(int fileId, String direction, String fileName, String detail,
                                Path savedPath, boolean failed) {
            firstFileThread.compareAndSet(null, Thread.currentThread().getName());
            fileIds.add(fileId);
            files.add(fileId + ":" + direction + ":" + fileName + ":" + (failed ? "FAIL" : "OK") + ":" + detail);
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
                if (next != null) {
                    current = next;
                } else {
                    current = lastStatus.get();
                }
            }
            return current;
        }

        String awaitChat() throws InterruptedException {
            String value = chats.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertTrue(value != null, "expected a chat message");
            return value;
        }

        String awaitFile() throws InterruptedException {
            String value = files.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertTrue(value != null, "expected a file event");
            return value;
        }
    }

    private void startPair(Path hostDir, Path clientDir, Recorder hostRecorder, Recorder clientRecorder)
            throws Exception {
        host = new PeerSession(hostDir, hostRecorder);
        client = new PeerSession(clientDir, clientRecorder);

        host.startHost(0);
        hostRecorder.awaitStatusContaining("Listening");

        int port = host.port();
        client.startClient("127.0.0.1", port, 5000);

        hostRecorder.awaitStatusContaining("Connected");
        clientRecorder.awaitStatusContaining("Connected");
    }

    private static java.util.function.BiPredicate<String, Long> pngs() {
        return (fileName, size) -> fileName != null && fileName.toLowerCase().endsWith(".png");
    }

    @Test
    void imageOfferIsAutoReceivedWithoutAnyUserAction(@TempDir Path dir) throws Exception {
        Recorder hostRecorder = new Recorder();
        Recorder clientRecorder = new Recorder();
        Path clientDir = dir.resolve("client");

        host = new PeerSession(dir.resolve("host"), hostRecorder);
        client = new PeerSession(clientDir, clientRecorder, pngs());

        host.startHost(0);
        hostRecorder.awaitStatusContaining("Listening");
        client.startClient("127.0.0.1", host.port(), 5000);
        hostRecorder.awaitStatusContaining("Connected");
        clientRecorder.awaitStatusContaining("Connected");

        byte[] content = new byte[40_000];
        new Random(7).nextBytes(content);
        Path source = Files.write(dir.resolve("selfie.png"), content);

        host.sendFile(source);

        String offer = clientRecorder.awaitOffer();
        assertTrue(offer.endsWith(":Receiving:selfie.png:40000"), "unexpected offer " + offer);

        String event = clientRecorder.awaitFile();
        assertTrue(event.endsWith(":Receiving:selfie.png:OK:Completed"),
                "the image must complete without acceptFileOffer being called: " + event);
        assertArrayEquals(content, Files.readAllBytes(clientDir.resolve("selfie.png")));
    }

    @Test
    void nonImageOfferStillNeedsAnExplicitAccept(@TempDir Path dir) throws Exception {
        Recorder hostRecorder = new Recorder();
        Recorder clientRecorder = new Recorder();
        Path clientDir = dir.resolve("client");

        host = new PeerSession(dir.resolve("host"), hostRecorder);
        client = new PeerSession(clientDir, clientRecorder, pngs());

        host.startHost(0);
        hostRecorder.awaitStatusContaining("Listening");
        client.startClient("127.0.0.1", host.port(), 5000);
        hostRecorder.awaitStatusContaining("Connected");
        clientRecorder.awaitStatusContaining("Connected");

        Path source = Files.write(dir.resolve("notes.txt"), "notes".getBytes(StandardCharsets.UTF_8));
        host.sendFile(source);

        String offer = clientRecorder.awaitOffer();
        int offerId = Integer.parseInt(offer.substring(0, offer.indexOf(':')));
        assertTrue(clientRecorder.files.isEmpty(), "a non-image must wait for the user");
        assertFalse(Files.exists(clientDir.resolve("notes.txt")));

        assertTrue(client.acceptFileOffer(offerId, clientDir));
        String event = clientRecorder.awaitFile();
        assertTrue(event.endsWith(":Receiving:notes.txt:OK:Completed"), "unexpected event " + event);
    }

    @Test
    void defaultSessionKeepsAskingForImagesToo(@TempDir Path dir) throws Exception {
        Recorder hostRecorder = new Recorder();
        Recorder clientRecorder = new Recorder();
        Path clientDir = dir.resolve("client");
        startPair(dir.resolve("host"), clientDir, hostRecorder, clientRecorder);

        Path source = Files.write(dir.resolve("pic.png"), new byte[1_000]);
        host.sendFile(source);

        String offer = clientRecorder.awaitOffer();
        int offerId = Integer.parseInt(offer.substring(0, offer.indexOf(':')));
        assertTrue(clientRecorder.files.isEmpty(), "without a policy an image must still ask");
        assertFalse(Files.exists(clientDir.resolve("pic.png")));

        assertTrue(client.acceptFileOffer(offerId, clientDir));
        assertTrue(clientRecorder.awaitFile().endsWith(":Receiving:pic.png:OK:Completed"));
    }

    @Test
    void hostListensAndClientConnects(@TempDir Path dir) throws Exception {
        startPair(dir.resolve("host"), dir.resolve("client"), new Recorder(), new Recorder());

        assertTrue(host.isConnected());
        assertTrue(client.isConnected());
        assertEquals("127.0.0.1:" + host.port(), client.remoteDescription());
    }

    @Test
    void chatFlowsBothWays(@TempDir Path dir) throws Exception {
        Recorder hostRecorder = new Recorder();
        Recorder clientRecorder = new Recorder();
        startPair(dir.resolve("host"), dir.resolve("client"), hostRecorder, clientRecorder);

        client.sendChat("hello host");
        assertEquals("Peer:hello host", hostRecorder.awaitChat());

        host.sendChat("hello client");
        assertEquals("Peer:hello client", clientRecorder.awaitChat());
    }

    @Test
    void fileTransfersClientToHostAfterAccept(@TempDir Path dir) throws Exception {
        Recorder hostRecorder = new Recorder();
        Recorder clientRecorder = new Recorder();
        Path hostDir = dir.resolve("host");
        startPair(hostDir, dir.resolve("client"), hostRecorder, clientRecorder);

        byte[] content = new byte[300_000];
        new Random(2).nextBytes(content);
        Path source = Files.write(dir.resolve("send.bin"), content);

        client.sendFile(source);

        String offer = hostRecorder.awaitOffer();
        int offerId = Integer.parseInt(offer.substring(0, offer.indexOf(':')));
        assertEquals("Receiving:send.bin:300000", offer.substring(offer.indexOf(':') + 1));
        assertTrue(host.acceptFileOffer(offerId, hostDir));

        String event = hostRecorder.awaitFile();
        assertTrue(event.endsWith(":Receiving:send.bin:OK:Completed"), "unexpected event " + event);
        assertArrayEquals(content, Files.readAllBytes(hostDir.resolve("send.bin")));
        assertFalse(hostRecorder.firstFileThread.get().contains("JavaFX"),
                "file callbacks must not run on the JavaFX thread");
    }

    @Test
    void fileTransfersHostToClientAfterAccept(@TempDir Path dir) throws Exception {
        Recorder hostRecorder = new Recorder();
        Recorder clientRecorder = new Recorder();
        Path clientDir = dir.resolve("client");
        startPair(dir.resolve("host"), clientDir, hostRecorder, clientRecorder);

        byte[] content = "host to client".getBytes(StandardCharsets.UTF_8);
        Path source = Files.write(dir.resolve("down.txt"), content);

        host.sendFile(source);

        String offer = clientRecorder.awaitOffer();
        int offerId = Integer.parseInt(offer.substring(0, offer.indexOf(':')));
        assertTrue(client.acceptFileOffer(offerId, clientDir));

        String event = clientRecorder.awaitFile();
        assertTrue(event.endsWith(":Receiving:down.txt:OK:Completed"), "unexpected event " + event);
        assertArrayEquals(content, Files.readAllBytes(clientDir.resolve("down.txt")));
    }

    @Test
    void declinedOfferSendsNothingAndCreatesNoFile(@TempDir Path dir) throws Exception {
        Recorder hostRecorder = new Recorder();
        Recorder clientRecorder = new Recorder();
        Path clientDir = dir.resolve("client");
        startPair(dir.resolve("host"), clientDir, hostRecorder, clientRecorder);

        Path source = Files.write(dir.resolve("no.txt"), new byte[1_000_000]);
        host.sendFile(source);

        String offer = clientRecorder.awaitOffer();
        int offerId = Integer.parseInt(offer.substring(0, offer.indexOf(':')));
        assertTrue(client.declineFileOffer(offerId, "declined by user"));

        String senderDecline = hostRecorder.awaitDecline();
        assertTrue(senderDecline.endsWith(":Sending:no.txt:declined by peer"), "unexpected " + senderDecline);
        assertFalse(Files.exists(clientDir.resolve("no.txt")));
    }

    @Test
    void acceptThenDeclineForUnknownTargetIsIgnored(@TempDir Path dir) throws Exception {
        Recorder hostRecorder = new Recorder();
        Recorder clientRecorder = new Recorder();
        startPair(dir.resolve("host"), dir.resolve("client"), hostRecorder, clientRecorder);

        assertFalse(client.declineFileOffer(4242, "no such offer"));
        assertFalse(client.acceptFileOffer(4242, dir.resolve("out")));
    }

    @Test
    void receiveDirectoryComesFromAcceptNotFromDefault(@TempDir Path dir) throws Exception {
        Recorder hostRecorder = new Recorder();
        Recorder clientRecorder = new Recorder();
        Path clientDir = dir.resolve("client");
        startPair(dir.resolve("host"), clientDir, hostRecorder, clientRecorder);

        Path chosen = dir.resolve("chosen/downloads");
        Path source = Files.write(dir.resolve("pic.txt"), "picked".getBytes(StandardCharsets.UTF_8));
        host.sendFile(source);

        String offer = clientRecorder.awaitOffer();
        int offerId = Integer.parseInt(offer.substring(0, offer.indexOf(':')));
        client.acceptFileOffer(offerId, chosen);

        String event = clientRecorder.awaitFile();
        assertTrue(event.endsWith(":Receiving:pic.txt:OK:Completed"), "unexpected event " + event);
        assertTrue(Files.exists(chosen.resolve("pic.txt")), "file must land in the chosen folder");
        assertFalse(Files.exists(clientDir.resolve("pic.txt")), "must not auto-save into the default folder");
    }

    @Test
    void disconnectNotifiesBothSides(@TempDir Path dir) throws Exception {
        Recorder hostRecorder = new Recorder();
        Recorder clientRecorder = new Recorder();
        startPair(dir.resolve("host"), dir.resolve("client"), hostRecorder, clientRecorder);

        client.disconnect();

        assertTrue(clientRecorder.disconnected.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertTrue(hostRecorder.disconnected.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertFalse(host.isConnected());
        assertFalse(client.isConnected());
    }

    @Test
    void suddenSocketCloseIsHandledGracefully(@TempDir Path dir) throws Exception {
        Recorder hostRecorder = new Recorder();
        Recorder clientRecorder = new Recorder();
        startPair(dir.resolve("host"), dir.resolve("client"), hostRecorder, clientRecorder);

        client.close();

        assertTrue(hostRecorder.disconnected.await(WAIT_SECONDS, TimeUnit.SECONDS),
                "host was not told about the abrupt close");
    }

    @Test
    void sendChatWhenNotConnectedReportsStatus(@TempDir Path dir) {
        Recorder recorder = new Recorder();
        client = new PeerSession(dir.resolve("client"), recorder);

        client.sendChat("nobody is listening");

        assertEquals("Not connected", recorder.lastStatus.get());
    }

    @Test
    void sendFileWhenNotConnectedReportsFailure(@TempDir Path dir) throws IOException {
        Recorder recorder = new Recorder();
        client = new PeerSession(dir.resolve("client"), recorder);
        Path file = Files.write(dir.resolve("x.txt"), "x".getBytes(StandardCharsets.UTF_8));

        client.sendFile(file);

        assertTrue(recorder.files.stream().anyMatch(event -> event.endsWith(":Sending:x.txt:FAIL:Not connected")),
                "unexpected events " + recorder.files);
    }

    @Test
    void receiverCallbacksAllCarryTheSameFileId(@TempDir Path dir) throws Exception {
        Recorder hostRecorder = new Recorder();
        Recorder clientRecorder = new Recorder();
        Path hostDir = dir.resolve("host");
        startPair(hostDir, dir.resolve("client"), hostRecorder, clientRecorder);

        Path source = Files.write(dir.resolve("id.bin"), new byte[50_000]);
        client.sendFile(source);

        String offer = hostRecorder.awaitOffer();
        int offerId = Integer.parseInt(offer.substring(0, offer.indexOf(':')));
        host.acceptFileOffer(offerId, hostDir);

        String receiverEvent = hostRecorder.awaitFile();
        int receiverId = Integer.parseInt(receiverEvent.substring(0, receiverEvent.indexOf(':')));

        assertEquals("Receiving:id.bin:OK:Completed",
                receiverEvent.substring(receiverEvent.indexOf(':') + 1));
        assertFalse(hostRecorder.fileIds.isEmpty(), "receiver must have received callbacks");
        assertTrue(hostRecorder.fileIds.stream().allMatch(id -> id == receiverId),
                "all receiver callbacks must share one file id, got " + hostRecorder.fileIds);
    }

    @Test
    void sendingLargeFileDoesNotBlockTheCaller(@TempDir Path dir) throws Exception {
        Recorder hostRecorder = new Recorder();
        Recorder clientRecorder = new Recorder();
        Path hostDir = dir.resolve("host");
        startPair(hostDir, dir.resolve("client"), hostRecorder, clientRecorder);

        StringBuilder filler = new StringBuilder();
        for (int i = 0; i < 4_000_000; i++) {
            filler.append('x');
        }
        Path source = Files.writeString(dir.resolve("big.txt"), filler);

        long start = System.nanoTime();
        client.sendFile(source);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis < 500,
                "sendFile blocked the caller for " + elapsedMillis + " ms");

        String offer = hostRecorder.awaitOffer();
        int offerId = Integer.parseInt(offer.substring(0, offer.indexOf(':')));
        host.acceptFileOffer(offerId, hostDir);

        String event = hostRecorder.awaitFile();
        assertTrue(event.endsWith(":Receiving:big.txt:OK:Completed"), "unexpected event " + event);
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(hostDir.resolve("big.txt")));
    }

    @Test
    void clientConnectToUnusedPortReportsFailure(@TempDir Path dir) throws IOException {
        Recorder recorder = new Recorder();
        client = new PeerSession(dir.resolve("client"), recorder);
        int freePort;
        try (var probe = new java.net.ServerSocket(0)) {
            freePort = probe.getLocalPort();
        }

        assertThrows(ConnectException.class, () -> client.startClient("127.0.0.1", freePort, 1000));
        assertEquals("Connection failed", recorder.lastStatus.get());
    }

    @Test
    void invalidHostReportsFailure(@TempDir Path dir) {
        Recorder recorder = new Recorder();
        client = new PeerSession(dir.resolve("client"), recorder);

        assertThrows(IOException.class, () -> client.startClient("999.999.999.999", 5000, 1000));
        assertEquals("Connection failed", recorder.lastStatus.get());
    }

    @Test
    void invalidPortRejected(@TempDir Path dir) throws IOException {
        Recorder recorder = new Recorder();
        client = new PeerSession(dir.resolve("client"), recorder);

        assertThrows(IllegalArgumentException.class, () -> client.startHost(-5));
        assertThrows(IllegalArgumentException.class, () -> client.startClient("127.0.0.1", 0, 1000));
    }

    @Test
    void hostReportsPortInUse(@TempDir Path dir) throws Exception {
        Recorder recorder = new Recorder();
        host = new PeerSession(dir.resolve("host"), recorder);
        host.startHost(0);
        int usedPort = host.port();

        PeerSession second = new PeerSession(dir.resolve("second"), new Recorder());
        try {
            assertThrows(IOException.class, () -> second.startHost(usedPort));
        } finally {
            second.close();
        }
    }

    @Test
    void closeReleasesResourcesAndIsIdempotent(@TempDir Path dir) throws Exception {
        Recorder hostRecorder = new Recorder();
        Recorder clientRecorder = new Recorder();
        startPair(dir.resolve("host"), dir.resolve("client"), hostRecorder, clientRecorder);

        host.close();
        host.close();

        assertFalse(host.isConnected());
        assertThrows(IllegalStateException.class, () -> host.startHost(0));
    }

    @Test
    void reconnectAfterDisconnectIsPossible(@TempDir Path dir) throws Exception {
        Recorder hostRecorder = new Recorder();
        Recorder clientRecorder = new Recorder();
        startPair(dir.resolve("host"), dir.resolve("client"), hostRecorder, clientRecorder);
        int port = host.port();

        client.disconnect();
        assertTrue(clientRecorder.disconnected.await(WAIT_SECONDS, TimeUnit.SECONDS));

        client.startClient("127.0.0.1", port, 5000);
        clientRecorder.awaitStatusContaining("Connected");

        client.sendChat("second session");
        assertEquals("Peer:second session", hostRecorder.awaitChat());
    }
}
