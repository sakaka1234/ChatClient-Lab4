package org.example.p2pchat.file;

import org.example.p2pchat.protocol.Packet;
import org.example.p2pchat.util.AppLogger;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class FileReceiver {

    public static final String DIRECTION = "Receiving";
    public static final String DEFAULT_FILE_NAME = "received-file";
    public static final int MAX_FILE_NAME_LENGTH = 255;

    private final Path baseDirectory;
    private final FileTransferListener listener;

    private PendingOffer pending;
    private ActiveTransfer active;

    /**
     * @param baseDirectory retained as the receiving root handed to the manager; the actual write
     *                      location comes from {@link #accept(int, Path)} so nothing is written
     *                      before the user agrees.
     */
    public FileReceiver(Path baseDirectory, FileTransferListener listener) {
        this.baseDirectory = baseDirectory;
        this.listener = listener;
    }

    public Path baseDirectory() {
        return baseDirectory;
    }

    public boolean hasPendingOffer() {
        return pending != null;
    }

    /**
     * @return the outstanding offer's id, sanitized name and declared size, or {@code null} when
     *         nothing is waiting for a decision. Used by the manager to apply an auto-receive
     *         policy without the offer being lost.
     */
    public PendingOfferInfo pendingOffer() {
        PendingOffer offer = pending;
        if (offer == null) {
            return null;
        }
        return new PendingOfferInfo(offer.fileId(), offer.safeName(), offer.declaredSize());
    }

    public record PendingOfferInfo(int fileId, String fileName, long fileSize) {
    }

    public boolean isReceiving() {
        return active != null;
    }

    public void onFileStart(Packet packet) {
        int incomingId = fileIdOf(packet);
        try {
            if (pending != null) {
                fail(incomingId, fileNameOf(packet), "Already waiting for a decision on " + pending.safeName());
                return;
            }
            if (active != null) {
                fail(incomingId, fileNameOf(packet), "Already receiving " + active.fileName);
                return;
            }

            int fileId = packet.fileId();
            long declaredSize = packet.fileSize();
            if (declaredSize < 0) {
                throw new IOException("Invalid declared file size: " + declaredSize);
            }

            String safeName = safeName(packet.fileName());
            pending = new PendingOffer(fileId, safeName, declaredSize);
            AppLogger.info("File offered: " + safeName + " (" + declaredSize + " bytes)");
            notifyOffered(fileId, safeName, declaredSize);
        } catch (IOException | RuntimeException e) {
            fail(incomingId, fileNameOf(packet), messageOf(e));
        }
    }

    public boolean accept(int fileId, Path destinationDirectory) {
        PendingOffer offer = pending;
        if (offer == null || offer.fileId() != fileId || destinationDirectory == null) {
            return false;
        }
        pending = null;
        try {
            Path directory = destinationDirectory.toAbsolutePath().normalize();
            Files.createDirectories(directory);
            Path target = uniqueTarget(directory, offer.safeName());
            OutputStream out = new BufferedOutputStream(
                    Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    64 * 1024);
            active = new ActiveTransfer(offer.fileId(), offer.safeName(), offer.declaredSize(), target, out);
            AppLogger.info("Receiving file: " + offer.safeName() + " into " + directory);
            notifyStarted(offer.fileId(), offer.safeName(), offer.declaredSize());
            return true;
        } catch (IOException | RuntimeException e) {
            fail(offer.fileId(), offer.safeName(), messageOf(e));
            return false;
        }
    }

    public boolean decline(int fileId, String reason) {
        PendingOffer offer = pending;
        if (offer == null || offer.fileId() != fileId) {
            return false;
        }
        pending = null;
        AppLogger.info("File declined: " + offer.safeName() + " (" + reason + ")");
        notifyDeclined(offer.fileId(), offer.safeName(), reason);
        return true;
    }

    public boolean cancel(int fileId, String reason) {
        ActiveTransfer transfer = active;
        if (transfer == null || transfer.fileId != fileId) {
            return decline(fileId, reason);
        }
        active = null;
        closeQuietly(transfer.out);
        deleteQuietly(transfer.target);
        AppLogger.info("Receiving cancelled: " + transfer.fileName + " (" + reason + ")");
        notifyDeclined(transfer.fileId, transfer.fileName, reason);
        return true;
    }

    public void onFileChunk(Packet packet) {
        ActiveTransfer transfer = active;
        if (transfer == null) {
            String what = pending != null ? "offer not accepted yet" : "no transfer in progress";
            fail(fileIdOf(packet), unknownName(packet), "chunk received but " + what);
            return;
        }
        try {
            int fileId = packet.fileId();
            if (fileId != transfer.fileId) {
                throw new IOException("Unknown file id " + fileId + " for active transfer " + transfer.fileId);
            }
            int index = packet.chunkIndex();
            if (index != transfer.nextChunkIndex) {
                throw new IOException("Chunk out of order: expected index " + transfer.nextChunkIndex + " but got " + index);
            }
            byte[] data = packet.chunkData();
            transfer.out.write(data);
            transfer.bytesReceived += data.length;
            transfer.nextChunkIndex++;
            notifyProgress(transfer.fileId, transfer.fileName, transfer.bytesReceived, transfer.fileSize);
        } catch (IOException | RuntimeException e) {
            failActive(transfer.fileId, transfer.fileName, messageOf(e));
        }
    }

    public void onFileEnd(Packet packet) {
        ActiveTransfer transfer = active;
        if (transfer == null) {
            fail(fileIdOf(packet), unknownName(packet), "file end received with no transfer in progress");
            return;
        }
        try {
            int fileId = packet.fileId();
            if (fileId != transfer.fileId) {
                throw new IOException("Unknown file id " + fileId + " for active transfer " + transfer.fileId);
            }
            transfer.out.flush();
            transfer.out.close();

            if (transfer.bytesReceived != transfer.fileSize) {
                throw new IOException("Size mismatch: expected " + transfer.fileSize
                        + " bytes but received " + transfer.bytesReceived);
            }

            active = null;
            notifyProgress(transfer.fileId, transfer.fileName, transfer.bytesReceived, transfer.fileSize);
            notifyCompleted(transfer.fileId, transfer.fileName, transfer.target);
            AppLogger.info("File received: " + transfer.fileName);
        } catch (IOException | RuntimeException e) {
            failActive(transfer.fileId, transfer.fileName, messageOf(e));
        }
    }

    public void reset() {
        PendingOffer offer = pending;
        pending = null;
        if (offer != null) {
            notifyDeclined(offer.fileId(), offer.safeName(), "connection closed");
        }
        ActiveTransfer transfer = active;
        active = null;
        if (transfer != null) {
            closeQuietly(transfer.out);
            deleteQuietly(transfer.target);
            notifyDeclined(transfer.fileId, transfer.fileName, "connection closed");
        }
    }

    private void failActive(int fileId, String fileName, String reason) {
        ActiveTransfer transfer = active;
        active = null;
        if (transfer != null) {
            closeQuietly(transfer.out);
            deleteQuietly(transfer.target);
        }
        fail(fileId, fileName, reason);
    }

    private Path uniqueTarget(Path directory, String fileName) {
        Path candidate = directory.resolve(fileName).normalize();
        if (!candidate.startsWith(directory)) {
            candidate = directory.resolve(DEFAULT_FILE_NAME);
        }
        if (!Files.exists(candidate)) {
            return candidate;
        }
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        for (int counter = 1; counter < Integer.MAX_VALUE; counter++) {
            Path next = directory.resolve(base + " (" + counter + ")" + extension).normalize();
            if (next.startsWith(directory) && !Files.exists(next)) {
                return next;
            }
        }
        return directory.resolve(DEFAULT_FILE_NAME + "-" + System.nanoTime());
    }

    private static String safeName(String rawName) {
        String name = sanitizeFileName(rawName);
        return name.isEmpty() ? DEFAULT_FILE_NAME : name;
    }

    public static String sanitizeFileName(String rawName) {
        if (rawName == null) {
            throw new IllegalArgumentException("fileName must not be null");
        }
        String name = rawName;
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (separator >= 0) {
            name = name.substring(separator + 1);
        }

        StringBuilder cleaned = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                continue;
            }
            if ("<>:\"|?*".indexOf(c) >= 0) {
                cleaned.append('_');
            } else {
                cleaned.append(c);
            }
        }
        name = cleaned.toString().strip();

        while (name.endsWith(".") || name.endsWith(" ")) {
            name = name.substring(0, name.length() - 1);
        }
        if (name.equals("..") || name.equals(".") || name.isEmpty()) {
            return "";
        }
        if (name.length() > MAX_FILE_NAME_LENGTH) {
            int dot = name.lastIndexOf('.');
            String extension = dot > 0 && name.length() - dot <= 16 ? name.substring(dot) : "";
            int keep = MAX_FILE_NAME_LENGTH - extension.length();
            name = name.substring(0, Math.max(1, keep)) + extension;
        }
        return name;
    }

    private static String fileNameOf(Packet packet) {
        try {
            String name = safeName(packet.fileName());
            return name;
        } catch (RuntimeException e) {
            return unknownName(packet);
        }
    }

    private static String unknownName(Packet packet) {
        try {
            return "file#" + packet.fileId();
        } catch (RuntimeException e) {
            return DEFAULT_FILE_NAME;
        }
    }

    private static int fileIdOf(Packet packet) {
        try {
            return packet.fileId();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String messageOf(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.toString() : message;
    }

    private static void closeQuietly(OutputStream out) {
        try {
            out.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private void notifyOffered(int fileId, String fileName, long fileSize) {
        try {
            listener.onTransferOffered(fileId, DIRECTION, fileName, fileSize);
        } catch (RuntimeException e) {
            AppLogger.error("File listener failed in onTransferOffered", e);
        }
    }

    private void notifyStarted(int fileId, String fileName, long fileSize) {
        try {
            listener.onTransferStarted(fileId, DIRECTION, fileName, fileSize);
        } catch (RuntimeException e) {
            AppLogger.error("File listener failed in onTransferStarted", e);
        }
    }

    private void notifyProgress(int fileId, String fileName, long bytes, long total) {
        try {
            listener.onProgress(fileId, DIRECTION, fileName, bytes, total);
        } catch (RuntimeException e) {
            AppLogger.error("File listener failed in onProgress", e);
        }
    }

    private void notifyCompleted(int fileId, String fileName, Path savedPath) {
        try {
            listener.onTransferCompleted(fileId, DIRECTION, fileName, savedPath);
        } catch (RuntimeException e) {
            AppLogger.error("File listener failed in onTransferCompleted", e);
        }
    }

    private void fail(int fileId, String fileName, String reason) {
        AppLogger.warn("File transfer failed: " + fileName + " -> " + reason);
        try {
            listener.onTransferFailed(fileId, DIRECTION, fileName, reason);
        } catch (RuntimeException e) {
            AppLogger.error("File listener failed in onTransferFailed", e);
        }
    }

    private void notifyDeclined(int fileId, String fileName, String reason) {
        try {
            listener.onTransferDeclined(fileId, DIRECTION, fileName, reason);
        } catch (RuntimeException e) {
            AppLogger.error("File listener failed in onTransferDeclined", e);
        }
    }

    private record PendingOffer(int fileId, String safeName, long declaredSize) {
    }

    private static final class ActiveTransfer {
        final int fileId;
        final String fileName;
        final long fileSize;
        final Path target;
        final OutputStream out;
        long bytesReceived;
        int nextChunkIndex;

        ActiveTransfer(int fileId, String fileName, long fileSize, Path target, OutputStream out) {
            this.fileId = fileId;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.target = target;
            this.out = out;
        }
    }
}
