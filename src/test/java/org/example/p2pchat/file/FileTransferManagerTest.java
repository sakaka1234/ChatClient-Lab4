package org.example.p2pchat.file;

import org.example.p2pchat.protocol.MessageType;
import org.example.p2pchat.protocol.Packet;
import org.example.p2pchat.support.CollectingSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class FileTransferManagerTest {

    private static class RecordingListener implements FileTransferListener {
        final List<String> started = new ArrayList<>();
        final List<String> completed = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        final List<String> declined = new ArrayList<>();
        final List<String> offered = new ArrayList<>();

        @Override
        public void onTransferOffered(int fileId, String direction, String fileName, long fileSize) {
            offered.add(fileId + ":" + direction + ":" + fileName);
        }

        @Override
        public void onTransferStarted(int fileId, String direction, String fileName, long fileSize) {
            started.add(direction + ":" + fileName);
        }

        @Override
        public void onProgress(int fileId, String direction, String fileName, long bytesTransferred, long totalBytes) {
        }

        @Override
        public void onTransferCompleted(int fileId, String direction, String fileName, Path savedPath) {
            completed.add(direction + ":" + fileName);
        }

        @Override
        public void onTransferFailed(int fileId, String direction, String fileName, String reason) {
            failed.add(direction + ":" + fileName + ":" + reason);
        }

        @Override
        public void onTransferDeclined(int fileId, String direction, String fileName, String reason) {
            declined.add(direction + ":" + fileName + ":" + reason);
        }
    }

    private static final class AutoAcceptListener extends RecordingListener {
        private final Path targetDirectory;
        private FileTransferManager manager;

        AutoAcceptListener(Path targetDirectory) {
            this.targetDirectory = targetDirectory;
        }

        void bind(FileTransferManager manager) {
            this.manager = manager;
        }

        @Override
        public void onTransferOffered(int fileId, String direction, String fileName, long fileSize) {
            super.onTransferOffered(fileId, direction, fileName, fileSize);
            manager.acceptOffer(fileId, targetDirectory);
        }
    }

    private static final class AutoDeclineListener extends RecordingListener {
        private FileTransferManager manager;

        void bind(FileTransferManager manager) {
            this.manager = manager;
        }

        @Override
        public void onTransferOffered(int fileId, String direction, String fileName, long fileSize) {
            super.onTransferOffered(fileId, direction, fileName, fileSize);
            manager.declineOffer(fileId, "auto-declined for test");
        }
    }

    private static void wire(FileTransferManager sender, FileTransferManager receiver,
                             CollectingSink captured, CollectingSink decisions) {
        sender.setSink(packet -> {
            captured.send(packet);
            receiver.handle(packet);
        });
        receiver.setSink(packet -> {
            decisions.send(packet);
            sender.handle(packet);
        });
    }

    @Test
    void sendFileEmitsStartChunkEndSequenceAfterAccept(@TempDir Path dir) throws IOException {
        byte[] content = "file payload".getBytes(StandardCharsets.UTF_8);
        Path file = Files.write(dir.resolve("payload.txt"), content);
        Path receiveDir = Files.createDirectory(dir.resolve("received"));

        CollectingSink sink = new CollectingSink();
        RecordingListener senderListener = new RecordingListener();
        AutoAcceptListener receiverListener = new AutoAcceptListener(receiveDir);
        FileTransferManager receiver = new FileTransferManager(receiveDir, receiverListener);
        receiverListener.bind(receiver);
        FileTransferManager sender = new FileTransferManager(dir, senderListener);
        wire(sender, receiver, sink, new CollectingSink());

        sender.sendFile(file);

        List<Packet> packets = sink.packets();
        assertEquals(MessageType.FILE_START, packets.get(0).type());
        assertEquals(MessageType.FILE_CHUNK, packets.get(1).type());
        assertEquals(MessageType.FILE_END, packets.get(packets.size() - 1).type());
        assertArrayEquals(content, packets.get(1).chunkData());
        assertEquals(List.of("Sending:payload.txt"), senderListener.completed);
        assertEquals(List.of("Receiving:payload.txt"), receiverListener.completed);
    }

    @Test
    void declinedTransferSendsNoChunksAndReportsDecline(@TempDir Path dir) throws IOException {
        Path file = Files.write(dir.resolve("big.bin"), new byte[50_000]);
        Path receiveDir = Files.createDirectory(dir.resolve("received"));

        CollectingSink sink = new CollectingSink();
        RecordingListener senderListener = new RecordingListener();
        AutoDeclineListener receiverListener = new AutoDeclineListener();
        FileTransferManager receiver = new FileTransferManager(receiveDir, receiverListener);
        receiverListener.bind(receiver);
        FileTransferManager sender = new FileTransferManager(dir, senderListener);
        wire(sender, receiver, sink, new CollectingSink());

        sender.sendFile(file);

        assertTrue(sink.packets().stream().noneMatch(p -> p.type() == MessageType.FILE_CHUNK),
                "declined transfer must not send any chunk");
        assertEquals(1, senderListener.declined.size());
        assertTrue(senderListener.declined.get(0).contains("declined by peer"));
        assertTrue(senderListener.completed.isEmpty());
        assertTrue(receiverListener.completed.isEmpty());
        try (var entries = Files.list(receiveDir)) {
            assertTrue(entries.findAny().isEmpty(), "nothing may be written when declined");
        }
    }

    @Test
    void eachSendUsesDistinctFileId(@TempDir Path dir) throws IOException {
        Path first = Files.write(dir.resolve("a.txt"), "aaa".getBytes(StandardCharsets.UTF_8));
        Path second = Files.write(dir.resolve("b.txt"), "bbb".getBytes(StandardCharsets.UTF_8));
        Path receiveDir = Files.createDirectory(dir.resolve("received"));

        CollectingSink sink = new CollectingSink();
        AutoAcceptListener receiverListener = new AutoAcceptListener(receiveDir);
        FileTransferManager receiver = new FileTransferManager(receiveDir, receiverListener);
        receiverListener.bind(receiver);
        FileTransferManager sender = new FileTransferManager(dir, new RecordingListener());
        wire(sender, receiver, sink, new CollectingSink());

        sender.sendFile(first);
        sender.sendFile(second);

        List<Packet> starts = sink.packets().stream().filter(p -> p.type() == MessageType.FILE_START).toList();
        assertEquals(2, starts.size());
        assertTrue(starts.get(0).fileId() != starts.get(1).fileId());
    }

    @Test
    void incomingSequenceIsWrittenToDiskAfterAccept(@TempDir Path dir) throws IOException {
        Path receiveDir = dir.resolve("received");
        RecordingListener listener = new RecordingListener();
        FileTransferManager manager = new FileTransferManager(receiveDir, listener);
        manager.setSink(new CollectingSink());
        byte[] content = "incoming file".getBytes(StandardCharsets.UTF_8);

        manager.handle(Packet.fileStart(1, "in.txt", content.length));
        assertEquals(List.of("1:Receiving:in.txt"), listener.offered);
        assertTrue(manager.acceptOffer(1, receiveDir));
        manager.handle(Packet.fileChunk(1, 0, content));
        manager.handle(Packet.fileEnd(1));

        assertArrayEquals(content, Files.readAllBytes(receiveDir.resolve("in.txt")));
        assertEquals(List.of("Receiving:in.txt"), listener.completed);
    }

    @Test
    void acceptSendsAcceptPacketToPeer(@TempDir Path dir) throws IOException {
        CollectingSink sink = new CollectingSink();
        FileTransferManager manager = new FileTransferManager(dir, new RecordingListener());
        manager.setSink(sink);

        manager.handle(Packet.fileStart(9, "x.bin", 10));
        assertTrue(manager.acceptOffer(9, dir.resolve("out")));

        assertTrue(sink.packets().stream().anyMatch(p -> p.type() == MessageType.FILE_ACCEPT && p.fileId() == 9));
    }

    @Test
    void declineSendsDeclinePacketToPeer(@TempDir Path dir) throws IOException {
        CollectingSink sink = new CollectingSink();
        FileTransferManager manager = new FileTransferManager(dir, new RecordingListener());
        manager.setSink(sink);

        manager.handle(Packet.fileStart(9, "x.bin", 10));
        assertTrue(manager.declineOffer(9, "no thanks"));

        assertTrue(sink.packets().stream().anyMatch(p -> p.type() == MessageType.FILE_DECLINE && p.fileId() == 9));
    }

    @Test
    void routingSendsChatPacketsToNoFileHandler(@TempDir Path dir) {
        RecordingListener fileListener = new RecordingListener();
        FileTransferManager manager = new FileTransferManager(dir, fileListener);
        manager.setSink(new CollectingSink());

        manager.handle(Packet.chat("ignored by file manager"));

        assertTrue(fileListener.failed.isEmpty());
        assertTrue(fileListener.offered.isEmpty());
    }

    @Test
    void fullRoundTripBetweenTwoManagersOverSink(@TempDir Path dir) throws IOException {
        byte[] content = new byte[200_000];
        new Random(5).nextBytes(content);
        Path source = Files.write(dir.resolve("round.bin"), content);
        Path senderDir = Files.createDirectory(dir.resolve("sender"));
        Path receiverDir = Files.createDirectory(dir.resolve("receiver"));

        RecordingListener senderListener = new RecordingListener();
        AutoAcceptListener receiverListener = new AutoAcceptListener(receiverDir);
        FileTransferManager sender = new FileTransferManager(senderDir, senderListener);
        FileTransferManager receiver = new FileTransferManager(receiverDir, receiverListener);
        receiverListener.bind(receiver);
        wire(sender, receiver, new CollectingSink(), new CollectingSink());

        sender.sendFile(source);

        assertArrayEquals(content, Files.readAllBytes(receiverDir.resolve("round.bin")));
        assertEquals(List.of("Sending:round.bin"), senderListener.completed);
        assertEquals(List.of("Receiving:round.bin"), receiverListener.completed);
    }

    @Test
    void silentlyIgnoredOfferTimesOutAndSendsDecline(@TempDir Path dir) throws IOException {
        Path file = Files.write(dir.resolve("waits.bin"), new byte[20_000]);
        Path receiveDir = Files.createDirectory(dir.resolve("received"));

        CollectingSink captured = new CollectingSink();
        CollectingSink decisions = new CollectingSink();
        RecordingListener senderListener = new RecordingListener();
        RecordingListener receiverListener = new RecordingListener();
        FileTransferManager sender = new FileTransferManager(dir, senderListener, 1);
        FileTransferManager receiver = new FileTransferManager(receiveDir, receiverListener, 1);
        sender.setSink(packet -> {
            captured.send(packet);
            receiver.handle(packet);
        });
        receiver.setSink(packet -> {
            decisions.send(packet);
            sender.handle(packet);
        });

        long start = System.nanoTime();
        sender.sendFile(file);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis >= 900, "must wait for the decision window, waited " + elapsedMillis + " ms");
        assertTrue(captured.packets().stream().noneMatch(p -> p.type() == MessageType.FILE_CHUNK),
                "timed-out transfer must not send chunks");
        assertTrue(captured.packets().stream().anyMatch(p -> p.type() == MessageType.FILE_DECLINE),
                "sender must tell the receiver it gave up");
        assertFalse(receiver.hasPendingOffer(), "receiver must clear the stale offer");
        assertEquals(1, senderListener.declined.size());
        assertTrue(senderListener.declined.get(0).contains("no response"));
        try (var entries = Files.list(receiveDir)) {
            assertTrue(entries.findAny().isEmpty());
        }
    }

    @Test
    void disconnectReleasesWaitingSender(@TempDir Path dir) throws IOException {
        Path file = Files.write(dir.resolve("blocked.bin"), new byte[20_000]);
        Path receiveDir = Files.createDirectory(dir.resolve("received"));

        RecordingListener senderListener = new RecordingListener();
        RecordingListener receiverListener = new RecordingListener();
        FileTransferManager sender = new FileTransferManager(dir, senderListener);
        FileTransferManager receiver = new FileTransferManager(receiveDir, receiverListener);
        sender.setSink(receiver::handle);

        Thread sending = new Thread(() -> sender.sendFile(file), "test-sender");
        sending.start();
        awaitPendingOffer(receiver);

        sender.onDisconnected();

        try {
            sending.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertFalse(sending.isAlive(), "disconnect must release the waiting sender");
        assertFalse(senderListener.declined.isEmpty(), "sender must be told the transfer is over");
        assertTrue(senderListener.declined.get(0).contains("disconnected"));
    }

    private static void awaitPendingOffer(FileTransferManager receiver) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!receiver.hasPendingOffer()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("receiver never got the offer");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    void sendWithoutSinkReportsFailure(@TempDir Path dir) throws IOException {
        Path file = Files.write(dir.resolve("x.txt"), "x".getBytes(StandardCharsets.UTF_8));
        RecordingListener listener = new RecordingListener();
        FileTransferManager manager = new FileTransferManager(dir, listener);
        manager.setSink(null);

        manager.sendFile(file);

        assertEquals(1, listener.failed.size());
    }

    @Test
    void rejectsNullDirectory() {
        assertThrows(NullPointerException.class,
                () -> new FileTransferManager(null, new RecordingListener()));
    }
}
