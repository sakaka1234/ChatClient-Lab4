package org.example.p2pchat.session;

import org.example.p2pchat.chat.ChatListener;
import org.example.p2pchat.chat.ChatManager;
import org.example.p2pchat.file.FileTransferListener;
import org.example.p2pchat.file.FileTransferManager;
import org.example.p2pchat.network.Connection;
import org.example.p2pchat.network.PeerClient;
import org.example.p2pchat.network.PeerServer;
import org.example.p2pchat.protocol.MessageType;
import org.example.p2pchat.protocol.Packet;
import org.example.p2pchat.util.AppLogger;
import org.example.p2pchat.util.NetworkUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PeerSession implements AutoCloseable {

    public static final int CONNECT_TIMEOUT_MILLIS = 5000;

    private static final String STATUS_DISCONNECTED = "Disconnected";
    private static final String STATUS_NOT_CONNECTED = "Not connected";
    private static final String STATUS_CONNECTION_FAILED = "Connection failed";
    private static final String STATUS_PEER_DISCONNECTED = "Peer disconnected";

    private final SessionListener listener;
    private final FileTransferManager fileManager;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ExecutorService sender = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "p2p-file-sender");
        thread.setDaemon(true);
        return thread;
    });

    private final Thread.UncaughtExceptionHandler noopHandler = (thread, error) ->
            AppLogger.error("Uncaught error on " + thread.getName(), error);

    private volatile PeerServer server;
    private volatile Connection connection;
    private volatile ChatManager chatManager;

    public PeerSession(Path receiveDirectory, SessionListener listener) {
        this(receiveDirectory, listener, FileTransferManager.ASK_ALWAYS);
    }

    /**
     * @param autoReceive policy deciding which incoming offers are accepted without asking the user;
     *                    matched files stream into {@code receiveDirectory}. Use
     *                    {@link FileTransferManager#ASK_ALWAYS} to always prompt.
     */
    public PeerSession(Path receiveDirectory, SessionListener listener,
                       java.util.function.BiPredicate<String, Long> autoReceive) {
        this.listener = Objects.requireNonNull(listener, "listener");
        this.fileManager = new FileTransferManager(receiveDirectory, new FileTransferBridge(),
                Objects.requireNonNull(autoReceive, "autoReceive"));
    }

    public void startHost(int port) throws IOException {
        NetworkUtils.validateListeningPort(port);
        ensureOpen();
        clearConnection();
        closeServerQuietly();

        server = new PeerServer(port);
        applyConnection(null);
        notifyStatus("Listening on port " + server.port());

        Thread acceptThread = new Thread(this::acceptLoop, "p2p-accept");
        acceptThread.setDaemon(true);
        acceptThread.setUncaughtExceptionHandler(noopHandler);
        acceptThread.start();
    }

    public void startClient(String host, int port, int timeoutMillis) throws IOException {
        NetworkUtils.validatePort(port);
        ensureOpen();
        clearConnection();
        closeServerQuietly();

        try {
            Connection newConnection = PeerClient.connect(host, port, timeoutMillis, new PacketRouter());
            newConnection.start();
            applyConnection(newConnection);
            notifyStatus("Connected to " + host.trim() + ":" + port);
        } catch (RuntimeException e) {
            notifyStatus(STATUS_CONNECTION_FAILED);
            throw e;
        } catch (IOException e) {
            notifyStatus(STATUS_CONNECTION_FAILED);
            throw e;
        }
    }

    public void sendChat(String message) {
        ChatManager manager = chatManager;
        if (manager == null) {
            notifyStatus(STATUS_NOT_CONNECTED);
            return;
        }
        manager.sendMessage(message);
    }

    public int allocateFileId() {
        return fileManager.nextFileId();
    }

    public void sendFile(Path file) {
        sendFile(file, allocateFileId());
    }

    public void sendFile(Path file, int fileId) {
        if (!isConnected()) {
            notifyFileEvent(fileId, "Sending", fileNameOf(file), "Not connected", null, true);
            return;
        }
        sender.execute(() -> fileManager.sendFile(file, fileId));
    }

    public boolean acceptFileOffer(int fileId, Path destinationDirectory) {
        return fileManager.acceptOffer(fileId, destinationDirectory);
    }

    public boolean declineFileOffer(int fileId, String reason) {
        return fileManager.declineOffer(fileId, reason);
    }

    public Path defaultReceiveDirectory() {
        return fileManager.defaultReceiveDirectory();
    }

    public void disconnect() {
        Connection current = connection;
        if (current != null && current.isOpen()) {
            try {
                current.send(Packet.disconnect());
            } catch (IOException e) {
                AppLogger.warn("Could not send DISCONNECT: " + e.getMessage());
            }
        }
        clearConnection();
        notifyStatus(STATUS_DISCONNECTED);
        notifyDisconnected(STATUS_DISCONNECTED);
    }

    public boolean isConnected() {
        Connection current = connection;
        return current != null && current.isOpen();
    }

    public int port() {
        PeerServer current = server;
        return current == null ? -1 : current.port();
    }

    public String remoteDescription() {
        Connection current = connection;
        if (current == null || current.remoteAddress() == null) {
            return "unknown";
        }
        var address = current.remoteAddress();
        return address.getAddress().getHostAddress() + ":" + address.getPort();
    }

    @Override
    public void close() {
        closed.set(true);
        clearConnection();
        closeServerQuietly();
        sender.shutdownNow();
        try {
            sender.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void acceptLoop() {
        while (!closed.get()) {
            PeerServer current = server;
            if (current == null) {
                return;
            }
            try {
                Connection accepted = current.accept(new PacketRouter());
                if (closed.get()) {
                    accepted.close();
                    return;
                }
                accepted.start();
                applyConnection(accepted);
                notifyStatus("Connected");
            } catch (IOException e) {
                if (current != server) {
                    return;
                }
                if (!closed.get()) {
                    AppLogger.error("Accept failed", e);
                    notifyStatus("Connection failed");
                }
                return;
            }
        }
    }

    private void applyConnection(Connection newConnection) {
        connection = newConnection;
        if (newConnection == null) {
            chatManager = null;
            fileManager.setSink(null);
        } else {
            chatManager = new ChatManager(newConnection::send, new ChatBridge());
            fileManager.setSink(newConnection::send);
        }
    }

    private void clearConnection() {
        Connection current = connection;
        connection = null;
        chatManager = null;
        fileManager.setSink(null);
        fileManager.onDisconnected();
        if (current != null) {
            current.close();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Session is closed");
        }
    }

    private void closeServerQuietly() {
        PeerServer current = server;
        server = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException e) {
                AppLogger.warn("Failed to close server socket: " + e.getMessage());
            }
        }
    }

    private void notifyStatus(String status) {
        AppLogger.info("Status: " + status);
        try {
            listener.onStatusChanged(status);
        } catch (RuntimeException e) {
            AppLogger.error("Session listener failed in onStatusChanged", e);
        }
    }

    private void notifyDisconnected(String reason) {
        try {
            listener.onDisconnected(reason);
        } catch (RuntimeException e) {
            AppLogger.error("Session listener failed in onDisconnected", e);
        }
    }

    private void notifyFileEvent(int fileId, String direction, String fileName, String detail,
                                Path savedPath, boolean failed) {
        try {
            listener.onFileEvent(fileId, direction, fileName, detail, savedPath, failed);
        } catch (RuntimeException e) {
            AppLogger.error("Session listener failed in onFileEvent", e);
        }
    }

    private void notifyFileProgress(int fileId, String direction, String fileName, long bytes, long total) {
        try {
            listener.onFileProgress(fileId, direction, fileName, bytes, total);
        } catch (RuntimeException e) {
            AppLogger.error("Session listener failed in onFileProgress", e);
        }
    }

    private static String fileNameOf(Path file) {
        return file == null || file.getFileName() == null ? "unknown" : file.getFileName().toString();
    }

    private final class PacketRouter implements Connection.Listener {
        @Override
        public void onPacket(Packet packet) {
            MessageType type = packet.type();
            switch (type) {
                case CHAT -> {
                    ChatManager manager = chatManager;
                    if (manager != null) {
                        manager.handle(packet);
                    }
                }
                case FILE_START, FILE_CHUNK, FILE_END, FILE_ACCEPT, FILE_DECLINE -> fileManager.handle(packet);
                case DISCONNECT -> {
                    AppLogger.info("Peer sent DISCONNECT");
                    clearConnection();
                    notifyStatus(STATUS_PEER_DISCONNECTED);
                    notifyDisconnected(STATUS_PEER_DISCONNECTED);
                }
            }
        }

        @Override
        public void onDisconnected(Throwable reason) {
            clearConnection();
            notifyStatus(STATUS_PEER_DISCONNECTED);
            notifyDisconnected(STATUS_PEER_DISCONNECTED);
        }
    }

    private final class ChatBridge implements ChatListener {
        @Override
        public void onChatMessage(String message) {
            try {
                listener.onChatMessage("Peer", message);
            } catch (RuntimeException e) {
                AppLogger.error("Session listener failed in onChatMessage", e);
            }
        }

        @Override
        public void onChatError(String reason) {
            notifyStatus(reason);
        }
    }

    private final class FileTransferBridge implements FileTransferListener {
        @Override
        public void onTransferOffered(int fileId, String direction, String fileName, long fileSize) {
            try {
                listener.onFileOffered(fileId, direction, fileName, fileSize);
            } catch (RuntimeException e) {
                AppLogger.error("Session listener failed in onFileOffered", e);
            }
        }

        @Override
        public void onTransferStarted(int fileId, String direction, String fileName, long fileSize) {
            notifyFileProgress(fileId, direction, fileName, 0, fileSize);
        }

        @Override
        public void onProgress(int fileId, String direction, String fileName, long bytesTransferred, long totalBytes) {
            notifyFileProgress(fileId, direction, fileName, bytesTransferred, totalBytes);
        }

        @Override
        public void onTransferCompleted(int fileId, String direction, String fileName, Path savedPath) {
            notifyFileEvent(fileId, direction, fileName, "Completed", savedPath, false);
        }

        @Override
        public void onTransferFailed(int fileId, String direction, String fileName, String reason) {
            notifyFileEvent(fileId, direction, fileName, reason, null, true);
        }

        @Override
        public void onTransferDeclined(int fileId, String direction, String fileName, String reason) {
            try {
                listener.onFileDeclined(fileId, direction, fileName, reason);
            } catch (RuntimeException e) {
                AppLogger.error("Session listener failed in onFileDeclined", e);
            }
        }
    }
}
