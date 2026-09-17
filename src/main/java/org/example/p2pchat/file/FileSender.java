package org.example.p2pchat.file;

import org.example.p2pchat.network.PacketSink;
import org.example.p2pchat.protocol.Packet;
import org.example.p2pchat.util.AppLogger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class FileSender {

    public static final int DEFAULT_CHUNK_SIZE = 64 * 1024;
    public static final String DIRECTION = "Sending";

    private static final long PROGRESS_STEP_BYTES = 256 * 1024;

    private FileSender() {
    }

    public static void send(Path file, int fileId, int chunkSize, PacketSink sink, FileTransferListener listener) {
        send(file, fileId, chunkSize, sink, listener, null);
    }

    public static TransferDecision send(Path file, int fileId, int chunkSize, PacketSink sink,
                                        FileTransferListener listener, TransferGate gate) {
        String fileName = file.getFileName() == null ? file.toString() : file.getFileName().toString();
        try {
            if (chunkSize <= 0) {
                throw new IllegalArgumentException("chunkSize must be positive: " + chunkSize);
            }
            if (!Files.isRegularFile(file)) {
                throw new IOException("Not a regular file: " + file);
            }

            long fileSize = Files.size(file);
            sink.send(Packet.fileStart(fileId, fileName, fileSize));
            AppLogger.info("Offering file: " + fileName + " (" + fileSize + " bytes)");

            if (gate != null) {
                TransferDecision decision = gate.awaitDecision();
                if (decision != TransferDecision.ACCEPTED) {
                    AppLogger.info("Transfer not accepted (" + decision + "): " + fileName);
                    if (decision == TransferDecision.TIMED_OUT) {
                        sendQuietly(sink, Packet.fileDecline(fileId));
                    }
                    return decision;
                }
                notifyStarted(listener, fileId, fileName, fileSize);
            } else {
                notifyStarted(listener, fileId, fileName, fileSize);
            }

            long sent = 0;
            long lastReported = 0;
            int chunkIndex = 0;
            byte[] buffer = new byte[chunkSize];

            try (InputStream in = Files.newInputStream(file)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    byte[] data = Arrays.copyOf(buffer, read);
                    sink.send(Packet.fileChunk(fileId, chunkIndex, data));
                    sent += read;
                    chunkIndex++;
                    if (sent - lastReported >= PROGRESS_STEP_BYTES) {
                        lastReported = sent;
                        notifyProgress(listener, fileId, fileName, sent, fileSize);
                    }
                }
            }

            sink.send(Packet.fileEnd(fileId));
            notifyProgress(listener, fileId, fileName, sent, fileSize);
            notifyCompleted(listener, fileId, fileName, file);
            AppLogger.info("File transfer completed: " + fileName);
            return TransferDecision.ACCEPTED;
        } catch (IOException e) {
            AppLogger.error("File transfer failed: " + fileName, e);
            notifyFailed(listener, fileId, fileName, e.getMessage() == null ? e.toString() : e.getMessage());
            return TransferDecision.DISCONNECTED;
        }
    }

    private static void sendQuietly(PacketSink sink, Packet packet) {
        try {
            sink.send(packet);
        } catch (IOException e) {
            AppLogger.warn("Could not send " + packet.type() + ": " + e.getMessage());
        }
    }

    private static void notifyStarted(FileTransferListener listener, int fileId, String fileName, long fileSize) {
        try {
            listener.onTransferStarted(fileId, DIRECTION, fileName, fileSize);
        } catch (RuntimeException e) {
            AppLogger.error("File listener failed in onTransferStarted", e);
        }
    }

    private static void notifyProgress(FileTransferListener listener, int fileId, String fileName, long sent, long total) {
        try {
            listener.onProgress(fileId, DIRECTION, fileName, sent, total);
        } catch (RuntimeException e) {
            AppLogger.error("File listener failed in onProgress", e);
        }
    }

    private static void notifyCompleted(FileTransferListener listener, int fileId, String fileName, Path file) {
        try {
            listener.onTransferCompleted(fileId, DIRECTION, fileName, file);
        } catch (RuntimeException e) {
            AppLogger.error("File listener failed in onTransferCompleted", e);
        }
    }

    private static void notifyFailed(FileTransferListener listener, int fileId, String fileName, String reason) {
        try {
            listener.onTransferFailed(fileId, DIRECTION, fileName, reason);
        } catch (RuntimeException e) {
            AppLogger.error("File listener failed in onTransferFailed", e);
        }
    }
}
