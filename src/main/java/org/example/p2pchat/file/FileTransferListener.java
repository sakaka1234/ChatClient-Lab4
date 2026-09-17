package org.example.p2pchat.file;

import java.nio.file.Path;

public interface FileTransferListener {

    void onTransferOffered(int fileId, String direction, String fileName, long fileSize);

    void onTransferStarted(int fileId, String direction, String fileName, long fileSize);

    void onProgress(int fileId, String direction, String fileName, long bytesTransferred, long totalBytes);

    void onTransferCompleted(int fileId, String direction, String fileName, Path savedPath);

    void onTransferFailed(int fileId, String direction, String fileName, String reason);

    void onTransferDeclined(int fileId, String direction, String fileName, String reason);
}
