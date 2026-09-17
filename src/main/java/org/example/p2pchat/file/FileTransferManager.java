package org.example.p2pchat.file;

import org.example.p2pchat.network.PacketSink;
import org.example.p2pchat.protocol.MessageType;
import org.example.p2pchat.protocol.Packet;
import org.example.p2pchat.util.AppLogger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;

public class FileTransferManager {

    public static final int MAX_FILE_ID = 0xFFFF;
    public static final long DECISION_TIMEOUT_SECONDS = 30;

    /** A policy that never auto-receives: every offer waits for the user's decision. */
    public static final BiPredicate<String, Long> ASK_ALWAYS = (fileName, fileSize) -> false;

    private final FileReceiver receiver;
    private final FileTransferListener listener;
    private final AtomicInteger nextFileId = new AtomicInteger(1);
    private final Map<Integer, TransferGate> pendingSends = new ConcurrentHashMap<>();
    private final long decisionTimeoutSeconds;
    private final BiPredicate<String, Long> autoReceive;

    private volatile PacketSink sink;

    public FileTransferManager(Path defaultReceiveDirectory, FileTransferListener listener) {
        this(defaultReceiveDirectory, listener, DECISION_TIMEOUT_SECONDS);
    }

    public FileTransferManager(Path defaultReceiveDirectory, FileTransferListener listener,
                               long decisionTimeoutSeconds) {
        this(defaultReceiveDirectory, listener, decisionTimeoutSeconds, ASK_ALWAYS);
    }

    /**
     * @param autoReceive decides, from the sanitized file name and declared size, whether an offer is
     *                    accepted on the user's behalf and streamed into the default receive
     *                    directory. {@code false} keeps the offer waiting for a decision.
     */
    public FileTransferManager(Path defaultReceiveDirectory, FileTransferListener listener,
                               BiPredicate<String, Long> autoReceive) {
        this(defaultReceiveDirectory, listener, DECISION_TIMEOUT_SECONDS, autoReceive);
    }

    public FileTransferManager(Path defaultReceiveDirectory, FileTransferListener listener,
                               long decisionTimeoutSeconds, BiPredicate<String, Long> autoReceive) {
        Objects.requireNonNull(defaultReceiveDirectory, "defaultReceiveDirectory");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.decisionTimeoutSeconds = decisionTimeoutSeconds;
        this.autoReceive = Objects.requireNonNull(autoReceive, "autoReceive");
        this.receiver = new FileReceiver(defaultReceiveDirectory, listener);
    }

    public void setSink(PacketSink sink) {
        this.sink = sink;
    }

    public Path defaultReceiveDirectory() {
        return receiver.baseDirectory();
    }

    public int nextFileId() {
        return nextFileId.getAndUpdate(current -> current >= MAX_FILE_ID ? 1 : current + 1);
    }

    public void sendFile(Path file) {
        sendFile(file, nextFileId());
    }

    public void sendFile(Path file, int fileId) {
        String fileName = file.getFileName() == null ? file.toString() : file.getFileName().toString();
        PacketSink currentSink = sink;
        if (currentSink == null) {
            listener.onTransferFailed(fileId, FileSender.DIRECTION, fileName, "Not connected");
            return;
        }

        TransferGate gate = new TransferGate(decisionTimeoutSeconds);
        pendingSends.put(fileId, gate);

        TransferDecision decision = FileSender.send(file, fileId, FileSender.DEFAULT_CHUNK_SIZE,
                currentSink, listener, gate);

        pendingSends.remove(fileId);
        if (decision.isTerminal()) {
            listener.onTransferDeclined(fileId, FileSender.DIRECTION, fileName, describeDecision(decision));
        }
    }

    public void handle(Packet packet) {
        MessageType type = packet.type();
        switch (type) {
            case FILE_START -> {
                receiver.onFileStart(packet);
                autoAcceptIfPolicyMatches();
            }
            case FILE_CHUNK -> receiver.onFileChunk(packet);
            case FILE_END -> receiver.onFileEnd(packet);
            case FILE_ACCEPT -> onAccept(packet);
            case FILE_DECLINE -> onDecline(packet);
            default -> AppLogger.warn("FileTransferManager ignoring unexpected packet: " + type);
        }
    }

    public boolean acceptOffer(int fileId, Path destinationDirectory) {
        boolean accepted = receiver.accept(fileId, destinationDirectory);
        if (accepted) {
            sendDecision(Packet.fileAccept(fileId));
        }
        return accepted;
    }

    private void autoAcceptIfPolicyMatches() {
        FileReceiver.PendingOfferInfo offer = receiver.pendingOffer();
        if (offer == null) {
            return;
        }
        boolean accept;
        try {
            accept = autoReceive.test(offer.fileName(), offer.fileSize());
        } catch (RuntimeException e) {
            AppLogger.error("Auto-receive policy failed for " + offer.fileName(), e);
            return;
        }
        if (accept) {
            acceptOffer(offer.fileId(), receiver.baseDirectory());
        }
    }

    public boolean declineOffer(int fileId, String reason) {
        boolean declined = receiver.decline(fileId, reason);
        if (declined) {
            sendDecision(Packet.fileDecline(fileId));
        }
        return declined;
    }

    public boolean hasPendingOffer() {
        return receiver.hasPendingOffer();
    }

    public void resetReceiving() {
        receiver.reset();
    }

    public void onDisconnected() {
        receiver.reset();
        for (Map.Entry<Integer, TransferGate> entry : pendingSends.entrySet()) {
            entry.getValue().disconnect();
        }
        pendingSends.clear();
    }

    private void onAccept(Packet packet) {
        int fileId = safeFileId(packet);
        TransferGate gate = pendingSends.get(fileId);
        if (gate == null) {
            AppLogger.warn("Ignoring FILE_ACCEPT for unknown transfer " + fileId);
            return;
        }
        gate.accept();
    }

    private void onDecline(Packet packet) {
        int fileId = safeFileId(packet);
        TransferGate gate = pendingSends.get(fileId);
        if (gate != null) {
            gate.decline();
            return;
        }
        if (receiver.isReceiving() || receiver.hasPendingOffer()) {
            receiver.cancel(fileId, "peer cancelled the transfer");
        } else {
            AppLogger.warn("Ignoring FILE_DECLINE for unknown transfer " + fileId);
        }
    }

    private void sendDecision(Packet packet) {
        PacketSink currentSink = sink;
        if (currentSink == null) {
            return;
        }
        try {
            currentSink.send(packet);
        } catch (IOException e) {
            AppLogger.error("Failed to send " + packet.type(), e);
        }
    }

    private static int safeFileId(Packet packet) {
        try {
            return packet.fileId();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static String describeDecision(TransferDecision decision) {
        return switch (decision) {
            case DECLINED -> "declined by peer";
            case TIMED_OUT -> "no response from peer";
            case DISCONNECTED -> "peer disconnected";
            case ACCEPTED -> "accepted";
        };
    }
}
