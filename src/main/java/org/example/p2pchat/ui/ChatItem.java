package org.example.p2pchat.ui;

import java.nio.file.Path;

public final class ChatItem {

    public enum Kind {
        TEXT, FILE
    }

    public enum Status {
        WAITING_FOR_DECISION, IN_PROGRESS, COMPLETED, FAILED, DECLINED
    }

    private final Kind kind;
    private final boolean outbound;
    private final long timestamp;
    private final String sender;
    private final String text;
    private final int fileId;
    private final String fileName;
    private final long totalBytes;
    private final Path sourcePath;

    private Path localPath;
    private long transferredBytes;
    private Status status;
    private String detail;

    private ChatItem(Kind kind, boolean outbound, long timestamp, String sender, String text,
                     int fileId, String fileName, long totalBytes, Path sourcePath, Status status) {
        this.kind = kind;
        this.outbound = outbound;
        this.timestamp = timestamp;
        this.sender = sender;
        this.text = text;
        this.fileId = fileId;
        this.fileName = fileName;
        this.totalBytes = totalBytes;
        this.sourcePath = sourcePath;
        this.localPath = sourcePath;
        this.status = status;
    }

    public static ChatItem outboundText(String text, long timestamp) {
        return textItem(null, text, true, timestamp);
    }

    public static ChatItem inboundText(String text, long timestamp) {
        return textItem(null, text, false, timestamp);
    }

    public static ChatItem inboundText(String sender, String text, long timestamp) {
        return textItem(sender, text, false, timestamp);
    }

    private static ChatItem textItem(String sender, String text, boolean outbound, long timestamp) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Chat text must not be blank");
        }
        return new ChatItem(Kind.TEXT, outbound, timestamp, sender, text.strip(),
                0, null, 0, null, Status.COMPLETED);
    }

    public static ChatItem outboundFile(int fileId, String fileName, long totalBytes, Path sourcePath, long timestamp) {
        validateFileArgs(fileId, fileName, totalBytes);
        return new ChatItem(Kind.FILE, true, timestamp, null, null,
                fileId, fileName, totalBytes, sourcePath, Status.WAITING_FOR_DECISION);
    }

    public static ChatItem inboundFile(int fileId, String fileName, long totalBytes, long timestamp) {
        validateFileArgs(fileId, fileName, totalBytes);
        return new ChatItem(Kind.FILE, false, timestamp, null, null,
                fileId, fileName, totalBytes, null, Status.WAITING_FOR_DECISION);
    }

    private static void validateFileArgs(int fileId, String fileName, long totalBytes) {
        if (fileId < 0) {
            throw new IllegalArgumentException("fileId must not be negative: " + fileId);
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        if (totalBytes < 0) {
            throw new IllegalArgumentException("totalBytes must not be negative: " + totalBytes);
        }
    }

    public Kind kind() {
        return kind;
    }

    public boolean outbound() {
        return outbound;
    }

    public long timestamp() {
        return timestamp;
    }

    public String sender() {
        return sender;
    }

    public String text() {
        return text;
    }

    public int fileId() {
        return fileId;
    }

    public String fileName() {
        return fileName;
    }

    public long totalBytes() {
        return totalBytes;
    }

    public Path sourcePath() {
        return sourcePath;
    }

    public Path localPath() {
        return localPath;
    }

    public long transferredBytes() {
        return transferredBytes;
    }

    public Status status() {
        return status;
    }

    public String detail() {
        return detail;
    }

    public boolean isTerminal() {
        return status == Status.COMPLETED || status == Status.FAILED || status == Status.DECLINED;
    }

    public boolean isAwaitingDecision() {
        return status == Status.WAITING_FOR_DECISION;
    }

    public boolean isDecided() {
        return status != Status.WAITING_FOR_DECISION;
    }

    public boolean isImage() {
        return kind == Kind.FILE && FileTypes.isImage(fileName);
    }

    public boolean canOpen() {
        return kind == Kind.FILE && status == Status.COMPLETED && localPath != null;
    }

    public boolean canSaveAs() {
        return !outbound && canOpen();
    }

    public double progress() {
        if (kind == Kind.TEXT) {
            return 1.0;
        }
        if (status == Status.COMPLETED) {
            return 1.0;
        }
        if (status == Status.FAILED || status == Status.DECLINED
                || status == Status.WAITING_FOR_DECISION) {
            return 0.0;
        }
        if (totalBytes <= 0) {
            return 1.0;
        }
        double ratio = (double) transferredBytes / totalBytes;
        return Math.max(0.0, Math.min(1.0, ratio));
    }

    public void accept() {
        if (status == Status.WAITING_FOR_DECISION) {
            this.status = Status.IN_PROGRESS;
        }
    }

    public void updateProgress(long bytesTransferred) {
        if (isTerminal()) {
            return;
        }
        this.transferredBytes = Math.max(0, bytesTransferred);
        if (status == Status.WAITING_FOR_DECISION) {
            this.status = Status.IN_PROGRESS;
        }
    }

    public void complete(Path savedPath) {
        if (isTerminal()) {
            return;
        }
        if (savedPath != null) {
            this.localPath = savedPath;
        }
        if (totalBytes > 0) {
            this.transferredBytes = totalBytes;
        }
        this.status = Status.COMPLETED;
    }

    public void fail(String reason) {
        if (isTerminal()) {
            return;
        }
        this.detail = reason;
        this.status = Status.FAILED;
    }

    public void decline(String reason) {
        if (isTerminal()) {
            return;
        }
        this.detail = reason;
        this.status = Status.DECLINED;
    }

    public boolean matchesFile(int candidateFileId) {
        return kind == Kind.FILE && fileId == candidateFileId;
    }

    @Override
    public String toString() {
        if (kind == Kind.TEXT) {
            String who = outbound ? "You" : (sender == null || sender.isBlank() ? "Peer" : sender);
            return who + ": " + text;
        }
        return "File[" + fileName + ", " + status + ", " + transferredBytes + "/" + totalBytes + "]";
    }
}
