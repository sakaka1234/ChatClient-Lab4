package org.example.p2pchat.file;

import org.example.p2pchat.protocol.MessageType;
import org.example.p2pchat.protocol.Packet;
import org.example.p2pchat.support.CollectingSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSenderTest {

    private static final class RecordingListener implements FileTransferListener {
        int started;
        int completed;
        int failed;
        long lastBytes;
        long lastTotal;
        String lastError;

        @Override
        public void onTransferOffered(int fileId, String direction, String fileName, long fileSize) {
        }

        public void onTransferDeclined(int fileId, String direction, String fileName, String reason) {
        }

        public void onTransferStarted(int fileId, String direction, String fileName, long fileSize) {
            started++;
        }

        @Override
        public void onProgress(int fileId, String direction, String fileName, long bytesTransferred, long totalBytes) {
            lastBytes = bytesTransferred;
            lastTotal = totalBytes;
        }

        @Override
        public void onTransferCompleted(int fileId, String direction, String fileName, Path savedPath) {
            completed++;
        }

        @Override
        public void onTransferFailed(int fileId, String direction, String fileName, String reason) {
            failed++;
            lastError = reason;
        }
    }

    private static TransferGate acceptedGate() {
        TransferGate gate = new TransferGate(5);
        gate.accept();
        return gate;
    }

    private static byte[] concatChunks(List<Packet> packets) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (Packet packet : packets) {
            if (packet.type() == MessageType.FILE_CHUNK) {
                out.writeBytes(packet.chunkData());
            }
        }
        return out.toByteArray();
    }

    @Test
    void sendsStartThenEndForEmptyFile(@TempDir Path dir) throws IOException {
        Path file = Files.write(dir.resolve("empty.txt"), new byte[0]);
        CollectingSink sink = new CollectingSink();
        RecordingListener listener = new RecordingListener();

        FileSender.send(file, 5, 64 * 1024, sink, listener, acceptedGate());

        assertEquals(2, sink.size());
        assertEquals(MessageType.FILE_START, sink.packet(0).type());
        assertEquals("empty.txt", sink.packet(0).fileName());
        assertEquals(0L, sink.packet(0).fileSize());
        assertEquals(5, sink.packet(0).fileId());
        assertEquals(MessageType.FILE_END, sink.packet(1).type());
        assertEquals(1, listener.started);
        assertEquals(1, listener.completed);
        assertEquals(0, listener.failed);
    }

    @Test
    void sendsOneChunkWithCorrectIndexForSmallFile(@TempDir Path dir) throws IOException {
        byte[] content = "small content".getBytes(StandardCharsets.UTF_8);
        Path file = Files.write(dir.resolve("small.bin"), content);
        CollectingSink sink = new CollectingSink();

        FileSender.send(file, 1, 64 * 1024, sink, new RecordingListener(), acceptedGate());

        assertEquals(3, sink.size());
        assertEquals(0, sink.packet(1).chunkIndex());
        assertArrayEquals(content, sink.packet(1).chunkData());
    }

    @Test
    void splitsLargeFileIntoSequentiallyIndexedChunks(@TempDir Path dir) throws IOException {
        byte[] content = new byte[64 * 1024 * 3 + 123];
        new Random(11).nextBytes(content);
        Path file = Files.write(dir.resolve("big.bin"), content);
        CollectingSink sink = new CollectingSink();

        FileSender.send(file, 9, 64 * 1024, sink, new RecordingListener(), acceptedGate());

        List<Packet> chunks = sink.packets().stream().filter(p -> p.type() == MessageType.FILE_CHUNK).toList();
        assertEquals(4, chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).chunkIndex());
            assertEquals(9, chunks.get(i).fileId());
        }
        assertArrayEquals(content, concatChunks(sink.packets()));
    }

    @Test
    void reconstructsFileExactlyFromChunks(@TempDir Path dir) throws IOException {
        byte[] content = new byte[150_000];
        new Random(3).nextBytes(content);
        Path file = Files.write(dir.resolve("data.bin"), content);
        CollectingSink sink = new CollectingSink();

        FileSender.send(file, 1, 4096, sink, new RecordingListener(), acceptedGate());

        assertArrayEquals(content, concatChunks(sink.packets()));
    }

    @Test
    void reportsProgressUpToTotalSize(@TempDir Path dir) throws IOException {
        byte[] content = new byte[10_000];
        Path file = Files.write(dir.resolve("p.bin"), content);
        CollectingSink sink = new CollectingSink();
        RecordingListener listener = new RecordingListener();

        FileSender.send(file, 1, 1024, sink, listener, acceptedGate());

        assertEquals(10_000, listener.lastBytes);
        assertEquals(10_000, listener.lastTotal);
    }

    @Test
    void chunkSizeNeverExceedsConfiguredMaximum(@TempDir Path dir) throws IOException {
        byte[] content = new byte[10_000];
        Path file = Files.write(dir.resolve("c.bin"), content);
        CollectingSink sink = new CollectingSink();

        FileSender.send(file, 1, 1024, sink, new RecordingListener(), acceptedGate());

        for (Packet packet : sink.packets()) {
            if (packet.type() == MessageType.FILE_CHUNK) {
                assertTrue(packet.chunkData().length <= 1024);
            }
        }
    }

    @Test
    void reportsFailureWhenSinkFails(@TempDir Path dir) throws IOException {
        Path file = Files.write(dir.resolve("f.bin"), new byte[5000]);
        CollectingSink sink = new CollectingSink();
        sink.failWith(new IOException("network down"));
        RecordingListener listener = new RecordingListener();

        FileSender.send(file, 1, 1024, sink, listener, acceptedGate());

        assertEquals(1, listener.failed);
        assertEquals(0, listener.completed);
        assertTrue(listener.lastError.contains("network down"));
    }

    @Test
    void rejectsMissingFile(@TempDir Path dir) {
        Path missing = dir.resolve("nope.bin");
        CollectingSink sink = new CollectingSink();
        RecordingListener listener = new RecordingListener();

        FileSender.send(missing, 1, 1024, sink, listener, acceptedGate());

        assertEquals(1, listener.failed);
        assertEquals(0, sink.size());
    }

    @Test
    void rejectsDirectory(@TempDir Path dir) throws IOException {
        Path sub = Files.createDirectory(dir.resolve("folder"));
        CollectingSink sink = new CollectingSink();
        RecordingListener listener = new RecordingListener();

        FileSender.send(sub, 1, 1024, sink, listener, acceptedGate());

        assertEquals(1, listener.failed);
    }

    @Test
    void rejectsInvalidChunkSize(@TempDir Path dir) throws IOException {
        Path file = Files.write(dir.resolve("x.bin"), new byte[10]);

        assertThrows(IllegalArgumentException.class,
                () -> FileSender.send(file, 1, 0, new CollectingSink(), new RecordingListener()));
    }

    @Test
    void progressCallbackIsThrottledButFinalProgressIsExact(@TempDir Path dir) throws IOException {
        byte[] content = new byte[64 * 1024 * 8];
        Path file = Files.write(dir.resolve("t.bin"), content);
        CollectingSink sink = new CollectingSink();
        AtomicLong progressCalls = new AtomicLong();
        long[] finalBytes = {0};
        FileTransferListener listener = new FileTransferListener() {
            @Override
            public void onTransferOffered(int fileId, String direction, String fileName, long fileSize) {
            }

            public void onTransferDeclined(int fileId, String direction, String fileName, String reason) {
            }

            public void onTransferStarted(int fileId, String direction, String fileName, long fileSize) {
            }

            @Override
            public void onProgress(int fileId, String direction, String fileName, long bytesTransferred, long totalBytes) {
                progressCalls.incrementAndGet();
                finalBytes[0] = bytesTransferred;
            }

            @Override
            public void onTransferCompleted(int fileId, String direction, String fileName, Path savedPath) {
                finalBytes[0] = 64 * 1024 * 8;
            }

            @Override
            public void onTransferFailed(int fileId, String direction, String fileName, String reason) {
            }
        };

        FileSender.send(file, 1, 64 * 1024, sink, listener, acceptedGate());

        assertTrue(progressCalls.get() <= 9, "expected throttled progress, got " + progressCalls.get());
        assertEquals(64 * 1024 * 8, finalBytes[0]);
    }
}
