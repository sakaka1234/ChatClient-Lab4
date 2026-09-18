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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A chat session that speaks to one or more peers over framed TCP packets.
 *
 * <p>In <b>host</b> mode a {@link PeerServer} accepts many peers and acts as the relay hub: a chat
 * message from one client is forwarded to every other client, tagged with the sender's display name.
 * In <b>client</b> mode exactly one peer (the host) is connected, and messages travel through it.
 * Both modes are symmetric once connected: chat can be sent from any instance at any time.
 */
public final class PeerSession implements AutoCloseable {

    public static final int CONNECT_TIMEOUT_MILLIS = 5000;

    private static final String STATUS_DISCONNECTED = "Disconnected";
    private static final String STATUS_NOT_CONNECTED = "Not connected";
    private static final String STATUS_CONNECTION_FAILED = "Connection failed";
    private static final String STATUS_PEER_DISCONNECTED = "Peer disconnected";

    private final SessionListener listener;
    private final FileTransferManager fileManager;
    private final ChatManager chatManager;
    private final PeerHub<Connection> hub = new PeerHub<>();
    private final String displayName;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ExecutorService sender = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "p2p-file-sender");
        thread.setDaemon(true);
        return thread;
    });

    private final Thread.UncaughtExceptionHandler noopHandler = (thread, error) ->
            AppLogger.error("Uncaught error on " + thread.getName(), error);

    private volatile PeerServer server;
    private volatile boolean hosting;

    public PeerSession(Path receiveDirectory, SessionListener listener) {
        this(receiveDirectory, listener, PeerHub.DEFAULT_NAME, FileTransferManager.ASK_ALWAYS);
    }

    /**
     * @param autoReceive policy deciding which incoming offers are accepted without asking the user;
     *                    matched files stream into {@code receiveDirectory}. Use
     *                    {@link FileTransferManager#ASK_ALWAYS} to always prompt.
     */
    public PeerSession(Path receiveDirectory, SessionListener listener,
                       java.util.function.BiPredicate<String, Long> autoReceive) {
        this(receiveDirectory, listener, PeerHub.DEFAULT_NAME, autoReceive);
    }

    public PeerSession(Path receiveDirectory, SessionListener listener, String displayName) {
        this(receiveDirectory, listener, displayName, FileTransferManager.ASK_ALWAYS);
    }

    /**
     * @param displayName the name other peers see next to this instance's chat messages.
     */
    public PeerSession(Path receiveDirectory, SessionListener listener, String displayName,
                       java.util.function.BiPredicate<String, Long> autoReceive) {
        this.listener = Objects.requireNonNull(listener, "listener");
        this.displayName = Objects.requireNonNull(displayName, "displayName").isBlank()
                ? PeerHub.DEFAULT_NAME : displayName.strip();
        this.fileManager = new FileTransferManager(receiveDirectory, new FileTransferBridge(),
                Objects.requireNonNull(autoReceive, "autoReceive"));
        this.chatManager = new ChatManager(this::sendChatPacket, new ChatBridge());
    }

    public String displayName() {
        return displayName;
    }

    public int peerCount() {
        return hub.size();
    }

    public void startHost(int port) throws IOException {
        NetworkUtils.validateListeningPort(port);
        ensureOpen();
        clearAllConnections();
        closeServerQuietly();

        hosting = true;
        server = new PeerServer(port);
        notifyStatus("Listening on port " + server.port());

        Thread acceptThread = new Thread(this::acceptLoop, "p2p-accept");
        acceptThread.setDaemon(true);
        acceptThread.setUncaughtExceptionHandler(noopHandler);
        acceptThread.start();
    }

    public void startClient(String host, int port, int timeoutMillis) throws IOException {
        NetworkUtils.validatePort(port);
        ensureOpen();
        clearAllConnections();
        closeServerQuietly();

        hosting = false;
        try {
            PacketRouter router = new PacketRouter();
            Connection newConnection = PeerClient.connect(host, port, timeoutMillis, router);
            router.attach(newConnection);
            newConnection.start();
            register(newConnection);
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
        if (message == null || message.isBlank()) {
            return;
        }
        if (!isConnected()) {
            notifyStatus(STATUS_NOT_CONNECTED);
            return;
        }
        String text = message.strip();
        if (hosting) {
            // The local UI already renders this as an outbound bubble, so only fan it out.
            broadcastRelay(displayName, text, null);
        } else {
            chatManager.sendMessage(text);
        }
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
        List<Connection> current = hub.peers();
        for (Connection connection : current) {
            if (connection.isOpen()) {
                try {
                    connection.send(Packet.disconnect());
                } catch (IOException e) {
                    AppLogger.warn("Could not send DISCONNECT: " + e.getMessage());
                }
            }
        }
        clearAllConnections();
        notifyStatus(STATUS_DISCONNECTED);
        notifyDisconnected(STATUS_DISCONNECTED);
    }

    public boolean isConnected() {
        return hub.size() > 0;
    }

    public int port() {
        PeerServer current = server;
        return current == null ? -1 : current.port();
    }

    public String remoteDescription() {
        Connection primary = hub.primary();
        if (primary == null || primary.remoteAddress() == null) {
            return "unknown";
        }
        var address = primary.remoteAddress();
        return address.getAddress().getHostAddress() + ":" + address.getPort();
    }

    @Override
    public void close() {
        closed.set(true);
        clearAllConnections();
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
                PacketRouter router = new PacketRouter();
                Connection accepted = current.accept(router);
                if (closed.get()) {
                    accepted.close();
                    return;
                }
                router.attach(accepted);
                accepted.start();
                register(accepted);
            } catch (IOException e) {
                if (current != server) {
                    return;
                }
                if (!closed.get()) {
                    AppLogger.error("Accept failed", e);
                    notifyStatus(STATUS_CONNECTION_FAILED);
                }
                return;
            }
        }
    }

    private void register(Connection connection) {
        hub.addIfAbsent(connection, PeerHub.DEFAULT_NAME);
        refreshFileSink();
        sendHelloQuietly(connection);
        notifyStatus("Connected");
    }

    private void unregister(Connection connection) {
        if (connection == null || !hub.remove(connection)) {
            return;
        }
        refreshFileSink();
        if (hub.size() == 0) {
            fileManager.onDisconnected();
            notifyStatus(STATUS_PEER_DISCONNECTED);
            notifyDisconnected(STATUS_PEER_DISCONNECTED);
        } else {
            notifyStatus("Connected");
        }
    }

    private void refreshFileSink() {
        Connection primary = hub.primary();
        fileManager.setSink(primary == null ? null : primary::send);
    }

    private void clearAllConnections() {
        List<Connection> current = hub.peers();
        for (Connection connection : current) {
            hub.remove(connection);
            connection.close();
        }
        refreshFileSink();
        fileManager.onDisconnected();
    }

    private void sendHelloQuietly(Connection connection) {
        try {
            connection.send(Packet.hello(displayName));
        } catch (IOException e) {
            AppLogger.warn("Could not send HELLO: " + e.getMessage());
        }
    }

    private void sendChatPacket(Packet packet) throws IOException {
        Connection primary = hub.primary();
        if (primary == null) {
            throw new IOException("Not connected");
        }
        primary.send(packet);
    }

    private void broadcastRelay(String sender, String text, Connection except) {
        Packet relay = Packet.relay(sender, text);
        for (Connection connection : hub.peersExcept(except)) {
            try {
                connection.send(relay);
            } catch (IOException e) {
                AppLogger.warn("Could not relay chat to a peer: " + e.getMessage());
            }
        }
    }

    private void deliverChat(String sender, String text) {
        try {
            listener.onChatMessage(sender, text);
        } catch (RuntimeException e) {
            AppLogger.error("Session listener failed in onChatMessage", e);
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

        private volatile Connection owner;

        void attach(Connection connection) {
            this.owner = connection;
        }

        @Override
        public void onPacket(Packet packet) {
            MessageType type = packet.type();
            Connection source = owner;
            switch (type) {
                case CHAT -> {
                    String sender = hub.nameOf(source);
                    chatManager.handle(packet, sender);
                    broadcastRelay(sender, packet.chatText(), source);
                }
                case RELAY -> chatManager.handle(packet, hub.nameOf(source));
                case HELLO -> {
                    hub.add(source, packet.helloName());
                    notifyStatus("Connected");
                }
                case FILE_START, FILE_CHUNK, FILE_END, FILE_ACCEPT, FILE_DECLINE -> fileManager.handle(packet);
                case DISCONNECT -> {
                    AppLogger.info("Peer sent DISCONNECT");
                    unregister(source);
                }
            }
        }

        @Override
        public void onDisconnected(Throwable reason) {
            unregister(owner);
        }
    }

    private final class ChatBridge implements ChatListener {
        @Override
        public void onChatMessage(String sender, String message) {
            deliverChat(sender, message);
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
