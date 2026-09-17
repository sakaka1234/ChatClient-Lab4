package org.example.p2pchat.session;

import java.nio.file.Path;

public interface SessionListener {

    void onStatusChanged(String status);

    void onChatMessage(String direction, String message);

    void onFileOffered(int fileId, String direction, String fileName, long fileSize);

    void onFileProgress(int fileId, String direction, String fileName, long bytesTransferred, long totalBytes);

    void onFileDeclined(int fileId, String direction, String fileName, String reason);

    void onFileEvent(int fileId, String direction, String fileName, String detail, Path savedPath, boolean failed);

    void onDisconnected(String reason);
}
