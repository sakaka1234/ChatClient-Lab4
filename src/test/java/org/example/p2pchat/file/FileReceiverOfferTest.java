package org.example.p2pchat.file;

import org.example.p2pchat.protocol.Packet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileReceiverOfferTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static final class RecordingListener implements FileTransferListener {
        final List<String> offered = new ArrayList<>();
        final List<String> started = new ArrayList<>();
        final List<String> completed = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        final List<String> declined = new ArrayList<>();
        long lastBytes;
        long lastSize;

        @Override
        public void onTransferOffered(int fileId, String direction, String fileName, long fileSize) {
            offered.add(fileId + ":" + direction + ":" + fileName + ":" + fileSize);
        }

        @Override
        public void onTransferStarted(int fileId, String direction, String fileName, long fileSize) {
            started.add(fileId + ":" + fileName);
        }

        @Override
        public void onProgress(int fileId, String direction, String fileName, long bytes, long total) {
            lastBytes = bytes;
            lastSize = total;
        }

        @Override
        public void onTransferCompleted(int fileId, String direction, String fileName, Path savedPath) {
            completed.add(fileId + ":" + fileName + ":" + savedPath);
        }

        @Override
        public void onTransferFailed(int fileId, String direction, String fileName, String reason) {
            failed.add(fileId + ":" + fileName + ":" + reason);
        }

        @Override
        public void onTransferDeclined(int fileId, String direction, String fileName, String reason) {
            declined.add(fileId + ":" + fileName + ":" + reason);
        }
    }

    @Test
    void fileStartOffersWithoutCreatingAnyFile(@TempDir Path dir) throws IOException {
        Path receiveDir = dir.resolve("receive");
        RecordingListener listener = new RecordingListener();
        FileReceiver receiver = new FileReceiver(receiveDir, listener);

        receiver.onFileStart(Packet.fileStart(5, "photo.png", 1234));

        assertEquals(List.of("5:Receiving:photo.png:1234"), listener.offered);
        assertTrue(listener.started.isEmpty(), "no file should start before acceptance");
        assertFalse(Files.exists(receiveDir), "no directory or file may be created before acceptance");
    }

    @Test
    void acceptThenReceiveWritesFileToChosenDirectory(@TempDir Path dir) throws IOException {
        Path chosen = dir.resolve("my downloads");
        RecordingListener listener = new RecordingListener();
        FileReceiver receiver = new FileReceiver(dir.resolve("unused"), listener);
        byte[] content = bytes("accepted content");

        receiver.onFileStart(Packet.fileStart(1, "note.txt", content.length));
        assertTrue(receiver.accept(1, chosen));
        assertEquals(List.of("1:note.txt"), listener.started);

        receiver.onFileChunk(Packet.fileChunk(1, 0, content));
        receiver.onFileEnd(Packet.fileEnd(1));

        assertArrayEquals(content, Files.readAllBytes(chosen.resolve("note.txt")));
        assertEquals(1, listener.completed.size());
        assertEquals("1:note.txt:" + chosen.resolve("note.txt"), listener.completed.get(0));
    }

    @Test
    void acceptCreatesTargetDirectory(@TempDir Path dir) throws IOException {
        Path chosen = dir.resolve("deep/nested/target");
        FileReceiver receiver = new FileReceiver(dir.resolve("unused"), new RecordingListener());
        byte[] content = bytes("x");

        receiver.onFileStart(Packet.fileStart(1, "a.txt", content.length));
        receiver.accept(1, chosen);
        receiver.onFileChunk(Packet.fileChunk(1, 0, content));
        receiver.onFileEnd(Packet.fileEnd(1));

        assertTrue(Files.exists(chosen.resolve("a.txt")));
    }

    @Test
    void acceptRejectsMismatchedFileId(@TempDir Path dir) throws IOException {
        FileReceiver receiver = new FileReceiver(dir, new RecordingListener());

        receiver.onFileStart(Packet.fileStart(1, "a.txt", 10));

        assertFalse(receiver.accept(2, dir.resolve("out")));
    }

    @Test
    void acceptWithoutOfferIsRejected(@TempDir Path dir) {
        FileReceiver receiver = new FileReceiver(dir, new RecordingListener());

        assertFalse(receiver.accept(9, dir.resolve("out")));
    }

    @Test
    void declineRemovesOfferAndCreatesNoFile(@TempDir Path dir) throws IOException {
        Path receiveDir = dir.resolve("receive");
        RecordingListener listener = new RecordingListener();
        FileReceiver receiver = new FileReceiver(receiveDir, listener);

        receiver.onFileStart(Packet.fileStart(3, "big.zip", 999_999));
        assertTrue(receiver.decline(3, "declined by user"));

        assertEquals(List.of("3:big.zip:declined by user"), listener.declined);
        assertFalse(Files.exists(receiveDir));
    }

    @Test
    void declineRejectsMismatchedId(@TempDir Path dir) throws IOException {
        FileReceiver receiver = new FileReceiver(dir, new RecordingListener());
        receiver.onFileStart(Packet.fileStart(3, "a.zip", 10));

        assertFalse(receiver.decline(4, "nope"));
    }

    @Test
    void chunksBeforeAcceptAreRejected(@TempDir Path dir) throws IOException {
        RecordingListener listener = new RecordingListener();
        FileReceiver receiver = new FileReceiver(dir.resolve("receive"), listener);
        receiver.onFileStart(Packet.fileStart(1, "a.bin", 4));

        receiver.onFileChunk(Packet.fileChunk(1, 0, new byte[4]));

        assertEquals(1, listener.failed.size());
        assertFalse(Files.exists(dir.resolve("receive")));
    }

    @Test
    void secondOfferIsRejectedWhileFirstPending(@TempDir Path dir) throws IOException {
        RecordingListener listener = new RecordingListener();
        FileReceiver receiver = new FileReceiver(dir, listener);

        receiver.onFileStart(Packet.fileStart(1, "one.txt", 10));
        receiver.onFileStart(Packet.fileStart(2, "two.txt", 10));

        assertEquals(1, listener.offered.size());
        assertEquals(1, listener.failed.size());
    }

    @Test
    void cancelDuringReceivingDeletesPartialFile(@TempDir Path dir) throws IOException {
        Path chosen = dir.resolve("out");
        RecordingListener listener = new RecordingListener();
        FileReceiver receiver = new FileReceiver(dir.resolve("unused"), listener);
        byte[] part = bytes("partial");

        receiver.onFileStart(Packet.fileStart(1, "half.bin", part.length * 2));
        receiver.accept(1, chosen);
        receiver.onFileChunk(Packet.fileChunk(1, 0, part));

        assertTrue(receiver.cancel(1, "peer cancelled"));

        assertFalse(Files.exists(chosen.resolve("half.bin")), "partial file must be deleted");
        assertEquals(1, listener.declined.size());
    }

    @Test
    void acceptRejectsPathTraversalInFileName(@TempDir Path dir) throws IOException {
        Path chosen = dir.resolve("out");
        FileReceiver receiver = new FileReceiver(dir.resolve("unused"), new RecordingListener());
        byte[] content = bytes("attack");

        receiver.onFileStart(Packet.fileStart(1, "..\\..\\evil.txt", content.length));
        receiver.accept(1, chosen);
        receiver.onFileChunk(Packet.fileChunk(1, 0, content));
        receiver.onFileEnd(Packet.fileEnd(1));

        assertTrue(Files.exists(chosen.resolve("evil.txt")));
        assertFalse(Files.exists(dir.getParent().resolve("evil.txt")));
    }

    @Test
    void zeroByteFileCompletesWhenAccepted(@TempDir Path dir) throws IOException {
        Path chosen = dir.resolve("out");
        RecordingListener listener = new RecordingListener();
        FileReceiver receiver = new FileReceiver(dir.resolve("unused"), listener);

        receiver.onFileStart(Packet.fileStart(1, "empty.txt", 0));
        receiver.accept(1, chosen);
        receiver.onFileEnd(Packet.fileEnd(1));

        assertEquals(1, listener.completed.size());
        assertTrue(Files.exists(chosen.resolve("empty.txt")));
        assertEquals(0, Files.size(chosen.resolve("empty.txt")));
    }

    @Test
    void hasPendingOfferReportsState(@TempDir Path dir) throws IOException {
        FileReceiver receiver = new FileReceiver(dir, new RecordingListener());
        assertFalse(receiver.hasPendingOffer());

        receiver.onFileStart(Packet.fileStart(1, "a.txt", 5));
        assertTrue(receiver.hasPendingOffer());

        receiver.accept(1, dir.resolve("out"));
        assertFalse(receiver.hasPendingOffer());
    }

    @Test
    void receivesMultiChunkFileInOrder(@TempDir Path dir) throws IOException {
        Path chosen = dir.resolve("out");
        FileReceiver receiver = new FileReceiver(dir.resolve("unused"), new RecordingListener());
        byte[] part0 = bytes("aaaa");
        byte[] part1 = bytes("bbbb");
        byte[] part2 = bytes("cccc");
        long total = part0.length + part1.length + part2.length;

        receiver.onFileStart(Packet.fileStart(1, "join.bin", total));
        receiver.accept(1, chosen);
        receiver.onFileChunk(Packet.fileChunk(1, 0, part0));
        receiver.onFileChunk(Packet.fileChunk(1, 1, part1));
        receiver.onFileChunk(Packet.fileChunk(1, 2, part2));
        receiver.onFileEnd(Packet.fileEnd(1));

        assertArrayEquals(bytes("aaaabbbbcccc"), Files.readAllBytes(chosen.resolve("join.bin")));
    }

    @Test
    void progressReportsGrowingByteCount(@TempDir Path dir) throws IOException {
        Path chosen = dir.resolve("out");
        RecordingListener listener = new RecordingListener();
        FileReceiver receiver = new FileReceiver(dir.resolve("unused"), listener);
        receiver.onFileStart(Packet.fileStart(1, "p.bin", 10));
        receiver.accept(1, chosen);
        receiver.onFileChunk(Packet.fileChunk(1, 0, new byte[4]));
        receiver.onFileChunk(Packet.fileChunk(1, 1, new byte[4]));
        receiver.onFileChunk(Packet.fileChunk(1, 2, new byte[2]));
        receiver.onFileEnd(Packet.fileEnd(1));

        assertEquals(10, listener.lastBytes);
        assertEquals(10, listener.lastSize);
    }

    @Test
    void rejectsChunkWithWrongFileId(@TempDir Path dir) throws IOException {
        RecordingListener listener = new RecordingListener();
        FileReceiver receiver = new FileReceiver(dir.resolve("unused"), listener);
        receiver.onFileStart(Packet.fileStart(1, "x.bin", 4));
        receiver.accept(1, dir.resolve("out"));

        receiver.onFileChunk(Packet.fileChunk(2, 0, new byte[4]));

        assertEquals(1, listener.failed.size());
        assertTrue(listener.failed.get(0).toLowerCase().contains("unknown file id"));
    }

    @Test
    void rejectsOutOfOrderChunk(@TempDir Path dir) throws IOException {
        RecordingListener listener = new RecordingListener();
        FileReceiver receiver = new FileReceiver(dir.resolve("unused"), listener);
        receiver.onFileStart(Packet.fileStart(1, "x.bin", 8));
        receiver.accept(1, dir.resolve("out"));
        receiver.onFileChunk(Packet.fileChunk(1, 0, new byte[4]));

        receiver.onFileChunk(Packet.fileChunk(1, 5, new byte[4]));

        assertEquals(1, listener.failed.size());
        assertTrue(listener.failed.get(0).toLowerCase().contains("chunk"));
    }

    @Test
    void reportsFailureWhenFileEndArrivesWithWrongSize(@TempDir Path dir) throws IOException {
        Path chosen = dir.resolve("out");
        RecordingListener listener = new RecordingListener();
        FileReceiver receiver = new FileReceiver(dir.resolve("unused"), listener);
        receiver.onFileStart(Packet.fileStart(1, "short.bin", 10));
        receiver.accept(1, chosen);
        receiver.onFileChunk(Packet.fileChunk(1, 0, new byte[4]));

        receiver.onFileEnd(Packet.fileEnd(1));

        assertEquals(1, listener.failed.size());
        assertFalse(Files.exists(chosen.resolve("short.bin")), "incomplete file must be deleted");
    }

    @Test
    void avoidsOverwritingExistingFile(@TempDir Path dir) throws IOException {
        Path chosen = Files.createDirectory(dir.resolve("out"));
        Files.write(chosen.resolve("dup.txt"), bytes("original"));
        FileReceiver receiver = new FileReceiver(dir.resolve("unused"), new RecordingListener());
        byte[] content = bytes("new");

        receiver.onFileStart(Packet.fileStart(1, "dup.txt", content.length));
        receiver.accept(1, chosen);
        receiver.onFileChunk(Packet.fileChunk(1, 0, content));
        receiver.onFileEnd(Packet.fileEnd(1));

        assertArrayEquals(bytes("original"), Files.readAllBytes(chosen.resolve("dup.txt")));
        assertArrayEquals(content, Files.readAllBytes(chosen.resolve("dup (1).txt")));
    }

    @Test
    void fallsBackToDefaultNameWhenSanitizedToEmpty(@TempDir Path dir) throws IOException {
        Path chosen = dir.resolve("out");
        RecordingListener listener = new RecordingListener();
        FileReceiver receiver = new FileReceiver(dir.resolve("unused"), listener);
        byte[] content = bytes("data");

        receiver.onFileStart(Packet.fileStart(1, "..", content.length));
        receiver.accept(1, chosen);
        receiver.onFileChunk(Packet.fileChunk(1, 0, content));
        receiver.onFileEnd(Packet.fileEnd(1));

        assertEquals(1, listener.completed.size());
        try (var stream = Files.list(chosen)) {
            assertEquals(1, stream.count());
        }
    }

    @Test
    void rejectsNegativeDeclaredSize(@TempDir Path dir) {
        RecordingListener listener = new RecordingListener();
        FileReceiver receiver = new FileReceiver(dir, listener);
        byte[] name = "evil.bin".getBytes(StandardCharsets.UTF_8);
        java.nio.ByteBuffer payload = java.nio.ByteBuffer.allocate(2 + 8 + 2 + name.length);
        payload.putShort((short) 1);
        payload.putLong(-1L);
        payload.putShort((short) name.length);
        payload.put(name);

        receiver.onFileStart(Packet.of(org.example.p2pchat.protocol.MessageType.FILE_START, payload.array()));

        assertEquals(1, listener.failed.size());
        assertFalse(Files.exists(dir.resolve("evil.bin")));
    }

    @Test
    void rejectsTruncatedFileStartPayload(@TempDir Path dir) {
        RecordingListener listener = new RecordingListener();
        FileReceiver receiver = new FileReceiver(dir, listener);

        receiver.onFileStart(Packet.of(org.example.p2pchat.protocol.MessageType.FILE_START, new byte[]{0, 1}));

        assertEquals(1, listener.failed.size());
    }

    @Test
    void rejectsFileEndWithoutStart(@TempDir Path dir) {
        RecordingListener listener = new RecordingListener();
        FileReceiver receiver = new FileReceiver(dir, listener);

        receiver.onFileEnd(Packet.fileEnd(1));

        assertEquals(1, listener.failed.size());
    }

    @Test
    void fileIsWrittenEvenWhenListenerThrows(@TempDir Path dir) throws IOException {
        Path chosen = dir.resolve("out");
        FileTransferListener exploding = new FileTransferListener() {
            @Override
            public void onTransferOffered(int fileId, String direction, String fileName, long fileSize) {
                throw new RuntimeException("listener bug");
            }

            @Override
            public void onTransferStarted(int fileId, String direction, String fileName, long fileSize) {
                throw new RuntimeException("listener bug");
            }

            @Override
            public void onProgress(int fileId, String direction, String fileName, long b, long t) {
                throw new RuntimeException("listener bug");
            }

            @Override
            public void onTransferCompleted(int fileId, String direction, String fileName, Path savedPath) {
                throw new RuntimeException("listener bug");
            }

            @Override
            public void onTransferFailed(int fileId, String direction, String fileName, String reason) {
                throw new RuntimeException("listener bug");
            }

            @Override
            public void onTransferDeclined(int fileId, String direction, String fileName, String reason) {
                throw new RuntimeException("listener bug");
            }
        };
        FileReceiver receiver = new FileReceiver(dir.resolve("unused"), exploding);
        byte[] content = bytes("resilient");

        receiver.onFileStart(Packet.fileStart(1, "keep.txt", content.length));
        receiver.accept(1, chosen);
        receiver.onFileChunk(Packet.fileChunk(1, 0, content));
        receiver.onFileEnd(Packet.fileEnd(1));

        assertArrayEquals(content, Files.readAllBytes(chosen.resolve("keep.txt")));
    }

    @Test
    void sanitizeKeepsPlainName() {
        assertEquals("photo.jpg", FileReceiver.sanitizeFileName("photo.jpg"));
    }

    @Test
    void sanitizeStripsDirectories() {
        assertEquals("file.txt", FileReceiver.sanitizeFileName("/etc/passwd/file.txt"));
        assertEquals("file.txt", FileReceiver.sanitizeFileName("C:\\Windows\\System32\\file.txt"));
    }

    @Test
    void sanitizeRejectsControlCharacters() {
        assertEquals("ab.txt", FileReceiver.sanitizeFileName("a\u0000b.txt"));
    }

    @Test
    void sanitizeTrimsTrailingDotsAndSpaces() {
        assertEquals("name", FileReceiver.sanitizeFileName("name... "));
    }

    @Test
    void sanitizeLimitsLength() {
        String longName = "x".repeat(500) + ".txt";
        assertTrue(FileReceiver.sanitizeFileName(longName).length() <= 255);
    }

    @Test
    void sanitizeReturnsEmptyForDotOnly() {
        assertEquals("", FileReceiver.sanitizeFileName(".."));
        assertEquals("", FileReceiver.sanitizeFileName("."));
    }

    @Test
    void sanitizeRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> FileReceiver.sanitizeFileName(null));
    }
}
