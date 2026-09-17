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
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class AutoReceivePolicyTest {

    private static final class RecordingListener implements FileTransferListener {
        final List<String> offered = new ArrayList<>();
        final List<String> completed = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        final List<String> declined = new ArrayList<>();

        @Override
        public void onTransferOffered(int fileId, String direction, String fileName, long fileSize) {
            offered.add(fileId + ":" + direction + ":" + fileName + ":" + fileSize);
        }

        @Override
        public void onTransferStarted(int fileId, String direction, String fileName, long fileSize) {
        }

        @Override
        public void onProgress(int fileId, String direction, String fileName, long bytesTransferred, long totalBytes) {
        }

        @Override
        public void onTransferCompleted(int fileId, String direction, String fileName, Path savedPath) {
            completed.add(direction + ":" + fileName + ":" + savedPath);
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

    private static BiPredicate<String, Long> imagesOnly() {
        return (fileName, size) -> {
            String lower = fileName == null ? "" : fileName.toLowerCase();
            return (lower.endsWith(".png") || lower.endsWith(".jpg")) && size <= 8L * 1024 * 1024;
        };
    }

    @Test
    void imageOfferIsAcceptedAutomaticallyWithoutUser(@TempDir Path dir) throws IOException {
        Path receiveDir = Files.createDirectory(dir.resolve("received"));
        RecordingListener listener = new RecordingListener();
        CollectingSink sink = new CollectingSink();
        FileTransferManager manager = new FileTransferManager(receiveDir, listener, imagesOnly());
        manager.setSink(sink);

        byte[] content = "fake png bytes".getBytes(StandardCharsets.UTF_8);
        manager.handle(Packet.fileStart(1, "photo.png", content.length));

        assertTrue(sink.packets().stream()
                        .anyMatch(p -> p.type() == MessageType.FILE_ACCEPT && p.fileId() == 1),
                "an image offer must be accepted automatically so the sender starts streaming");
        assertFalse(manager.hasPendingOffer(), "the offer must not stay waiting for a decision");

        manager.handle(Packet.fileChunk(1, 0, content));
        manager.handle(Packet.fileEnd(1));

        assertArrayEquals(content, Files.readAllBytes(receiveDir.resolve("photo.png")));
        assertEquals(List.of("1:Receiving:photo.png:" + content.length), listener.offered);
        assertEquals(1, listener.completed.size());
        assertTrue(listener.completed.get(0).endsWith(":photo.png:" + receiveDir.resolve("photo.png")));
    }

    @Test
    void nonImageOfferStillWaitsForDecision(@TempDir Path dir) throws IOException {
        Path receiveDir = Files.createDirectory(dir.resolve("received"));
        RecordingListener listener = new RecordingListener();
        CollectingSink sink = new CollectingSink();
        FileTransferManager manager = new FileTransferManager(receiveDir, listener, imagesOnly());
        manager.setSink(sink);

        manager.handle(Packet.fileStart(2, "archive.zip", 4_000));

        assertTrue(manager.hasPendingOffer(), "a non-image offer must still ask the user");
        assertTrue(sink.packets().stream().noneMatch(p -> p.type() == MessageType.FILE_ACCEPT),
                "no accept packet may be sent for a non-image");
        assertEquals(List.of("2:Receiving:archive.zip:4000"), listener.offered);
        try (var entries = Files.list(receiveDir)) {
            assertTrue(entries.findAny().isEmpty(), "nothing may be written before the user decides");
        }
    }

    @Test
    void oversizedImageStaysWaitingForDecision(@TempDir Path dir) throws IOException {
        Path receiveDir = Files.createDirectory(dir.resolve("received"));
        RecordingListener listener = new RecordingListener();
        CollectingSink sink = new CollectingSink();
        FileTransferManager manager = new FileTransferManager(receiveDir, listener, imagesOnly());
        manager.setSink(sink);

        long tooBig = 8L * 1024 * 1024 + 1;
        manager.handle(Packet.fileStart(3, "huge.png", tooBig));

        assertTrue(manager.hasPendingOffer(), "an oversized image must still ask the user");
        assertTrue(sink.packets().stream().noneMatch(p -> p.type() == MessageType.FILE_ACCEPT));
        try (var entries = Files.list(receiveDir)) {
            assertTrue(entries.findAny().isEmpty(), "nothing may be written for an oversized image");
        }
    }

    @Test
    void defaultConstructorNeverAutoAccepts(@TempDir Path dir) throws IOException {
        Path receiveDir = Files.createDirectory(dir.resolve("received"));
        RecordingListener listener = new RecordingListener();
        CollectingSink sink = new CollectingSink();
        FileTransferManager manager = new FileTransferManager(receiveDir, listener);
        manager.setSink(sink);

        manager.handle(Packet.fileStart(4, "photo.png", 20));

        assertTrue(manager.hasPendingOffer(), "without a policy the offer must wait for the user");
        assertTrue(sink.packets().stream().noneMatch(p -> p.type() == MessageType.FILE_ACCEPT));
    }

    @Test
    void autoAcceptedImageStreamsWholeFileToDefaultDirectory(@TempDir Path dir) throws IOException {
        Path receiveDir = Files.createDirectory(dir.resolve("received"));
        FileTransferManager sender = new FileTransferManager(dir, new RecordingListener(),
                (name, size) -> false);
        FileTransferManager receiver = new FileTransferManager(receiveDir, new RecordingListener(),
                imagesOnly());

        CollectingSink captured = new CollectingSink();
        CollectingSink decisions = new CollectingSink();
        sender.setSink(packet -> {
            captured.send(packet);
            receiver.handle(packet);
        });
        receiver.setSink(packet -> {
            decisions.send(packet);
            sender.handle(packet);
        });

        byte[] content = new byte[70_000];
        new java.util.Random(11).nextBytes(content);
        Path source = Files.write(dir.resolve("shot.png"), content);

        sender.sendFile(source);

        assertArrayEquals(content, Files.readAllBytes(receiveDir.resolve("shot.png")));
        assertTrue(decisions.packets().stream().anyMatch(p -> p.type() == MessageType.FILE_ACCEPT),
                "receiver must have told the sender it accepted");
        assertTrue(captured.packets().stream().anyMatch(p -> p.type() == MessageType.FILE_CHUNK),
                "sender must stream chunks once the image was auto-accepted");
    }
}
