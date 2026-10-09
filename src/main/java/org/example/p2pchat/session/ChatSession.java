package org.example.p2pchat.session;

import org.example.p2pchat.chat.ChatListener;
import org.example.p2pchat.chat.ChatManager;
import org.example.p2pchat.network.Acceptor;
import org.example.p2pchat.network.Connection;
import org.example.p2pchat.network.Connector;
import org.example.p2pchat.protocol.ChatScope;
import org.example.p2pchat.protocol.MessageType;
import org.example.p2pchat.protocol.Packet;
import org.example.p2pchat.util.AppLogger;
import org.example.p2pchat.util.NetworkUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One side of a client–server chat session, speaking framed TCP packets.
 *
 * <p>In {@link Role#SERVER} mode an {@link Acceptor} takes any number of clients and relays between
 * them: it holds a socket to every client and copies each message onto the sockets its address
 * selects. In {@link Role#CLIENT} mode exactly one socket is open — to the server — and every
 * message travels through it.
 *
 * <p>The server <b>does not chat</b>. It never draws a bubble, is never a unicast or multicast
 * target, and is absent from the client directory; it exists to replicate. Addresses therefore only
 * ever name clients, numbered {@code #1, #2, #3…} in join order.
 *
 * <p><b>Addressing.</b> Every chat message names its recipients ({@link ChatScope}), and the server
 * is the only instance that replicates it — one socket for a unicast, the chosen group for a
 * multicast, everyone else for a broadcast. Because the server hands out the ids and forwards the
 * address unchanged, a receiver knows whether the message was private or addressed to a group. The
 * client directory itself is pushed to every client as a {@code ROSTER} packet. See
 * {@code docs/SPECS-UNICAST-MULTICAST.md}.
 */
public final class ChatSession implements AutoCloseable {

    /** Which side of the connection this instance is. */
    public enum Role {
        /** Accepts clients and relays between them; never sends or receives chat itself. */
        SERVER,
        /** Connects to a server; sends and receives chat through it. */
        CLIENT
    }

    public static final int CONNECT_TIMEOUT_MILLIS = 5000;

    /** Clients are numbered from here, in join order: {@code #1}, {@code #2}, … */
    public static final int FIRST_CLIENT_ID = 1;

    /** Idiom for "no id": the server, and a client until its first {@code ROSTER} arrives. */
    public static final int UNASSIGNED_CLIENT_ID = 0;

    /** Reported when the session was ended on purpose, by {@link #disconnect()} or a lost server. */
    public static final String STATUS_DISCONNECTED = "Disconnected";

    /** Reported when the socket could not be opened, or the listening socket stopped accepting. */
    public static final String STATUS_CONNECTION_FAILED = "Connection failed";

    /** Reported to a client when its only socket — the one to the server — went away. */
    public static final String STATUS_SERVER_DISCONNECTED = "Server disconnected";

    private static final String STATUS_NOT_CONNECTED = "Not connected";

    private final SessionListener listener;
    private final ChatManager chatManager;
    private final ClientRegistry<Connection> hub = new ClientRegistry<>();
    private final String displayName;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** connection → the id the server handed out. Only meaningful on the server side. */
    private final Map<Connection, Integer> clientIds = new ConcurrentHashMap<>();
    private final AtomicInteger nextClientId = new AtomicInteger(FIRST_CLIENT_ID);
    private final AtomicReference<List<ClientInfo>> clientList = new AtomicReference<>(List.of());
    private final AtomicInteger relayed = new AtomicInteger();

    private final Thread.UncaughtExceptionHandler noopHandler = (thread, error) ->
            AppLogger.error("Uncaught error on " + thread.getName(), error);

    private volatile Acceptor server;
    private volatile Role role = Role.CLIENT;
    private volatile int selfId = UNASSIGNED_CLIENT_ID;

    public ChatSession(SessionListener listener) {
        this(listener, ClientRegistry.DEFAULT_NAME);
    }

    /** @param displayName the name the server shows for this client in its directory and log. */
    public ChatSession(SessionListener listener, String displayName) {
        this.listener = Objects.requireNonNull(listener, "listener");
        this.displayName = Objects.requireNonNull(displayName, "displayName").isBlank()
                ? ClientRegistry.DEFAULT_NAME : displayName.strip();
        this.chatManager = new ChatManager(this::sendChatPacket, new ChatBridge());
    }

    public String displayName() {
        return displayName;
    }

    public Role role() {
        return role;
    }

    public boolean isServer() {
        return role == Role.SERVER;
    }

    /** Open sockets: the connected clients on a server, the server itself on a client. */
    public int connectionCount() {
        return hub.size();
    }

    /** The client directory as last published: every client of the session, this instance included on a client. */
    public List<ClientInfo> clients() {
        return clientList.get();
    }

    /** This instance's own id: {@code 0} on the server, and on a client until its first roster. */
    public int selfClientId() {
        return selfId;
    }

    /** How many chat messages the server has copied onto at least one socket. Always {@code 0} on a client. */
    public int relayedCount() {
        return relayed.get();
    }

    public void startServer(int port) throws IOException {
        NetworkUtils.validateListeningPort(port);
        ensureOpen();
        role = Role.SERVER;
        clearAllConnections();
        closeServerQuietly();

        server = new Acceptor(port);
        notifyStatus("Listening on port " + server.port());
        publishRoster();

        Thread acceptThread = new Thread(this::acceptLoop, "chat-accept");
        acceptThread.setDaemon(true);
        acceptThread.setUncaughtExceptionHandler(noopHandler);
        acceptThread.start();
    }

    public void startClient(String host, int port, int timeoutMillis) throws IOException {
        NetworkUtils.validatePort(port);
        ensureOpen();
        role = Role.CLIENT;
        clearAllConnections();
        closeServerQuietly();

        try {
            PacketRouter router = new PacketRouter();
            Connection newConnection = Connector.connect(host, port, timeoutMillis, router);
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

    /** Sends to every other client. */
    public void sendChat(String message) {
        sendChat(message, ChatScope.BROADCAST, List.of());
    }

    /**
     * Sends a chat message to the clients the address selects.
     *
     * @param scope   {@link ChatScope#UNICAST} (exactly one target), {@link ChatScope#MULTICAST} (two
     *                or more) or {@link ChatScope#BROADCAST}
     * @param targets client ids to address; an empty list means "everyone"
     * @throws IllegalStateException on a server, which relays chat but never originates it
     */
    public void sendChat(String message, ChatScope scope, List<Integer> targets) {
        if (message == null || message.isBlank()) {
            return;
        }
        if (isServer()) {
            throw new IllegalStateException("The server relays chat but does not send it");
        }
        if (!isConnected()) {
            notifyStatus(STATUS_NOT_CONNECTED);
            return;
        }
        String text = message.strip();
        ChatScope resolvedScope = scope == null ? ChatScope.BROADCAST : scope;
        List<Integer> resolvedTargets = targets == null ? List.of() : List.copyOf(targets);
        if (resolvedTargets.isEmpty()) {
            resolvedScope = ChatScope.BROADCAST;
        }
        chatManager.sendMessage(text, resolvedScope, resolvedTargets);
    }

    /** Closes the session: on a client it drops the server, on a server it stops listening. */
    public void disconnect() {
        List<Connection> current = hub.connections();
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
        Acceptor current = server;
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
    }

    private void acceptLoop() {
        while (!closed.get()) {
            Acceptor current = server;
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
        hub.addIfAbsent(connection, ClientRegistry.DEFAULT_NAME);
        idOf(connection);                       // hand out an address before anyone can name it
        publishRoster();
        if (isServer()) {
            // The server has no name to announce, and nothing to say to the client but the roster.
            return;
        }
        sendHelloQuietly(connection);
        notifyStatus("Connected");
    }

    /**
     * Drops a connection that went away.
     *
     * <p>The two roles read an empty socket set very differently: on a client the only socket is the
     * server, so losing it ends the session; on a server the last client leaving is just an empty
     * roster, and the server keeps listening.
     */
    private void unregister(Connection connection) {
        if (connection == null || !hub.contains(connection)) {
            return;
        }
        int id = idOf(connection);
        String name = hub.nameOf(connection);
        hub.remove(connection);
        clientIds.remove(connection);
        if (isServer()) {
            int remaining = hub.size();
            notifyServerLog("- " + describeClient(id, name, connection) + " left ("
                    + remaining + (remaining == 1 ? " client remains)" : " clients remain)"));
            publishRoster();
            return;
        }
        if (hub.size() == 0) {
            notifyStatus(STATUS_SERVER_DISCONNECTED);
            notifyDisconnected(STATUS_SERVER_DISCONNECTED);
        } else {
            publishRoster();
            notifyStatus("Connected");
        }
    }

    private void clearAllConnections() {
        List<Connection> current = hub.connections();
        for (Connection connection : current) {
            hub.remove(connection);
            connection.close();
        }
        clientIds.clear();
        // Ids are per-session, so a restart renumbers from #1: nothing is carried over.
        nextClientId.set(FIRST_CLIENT_ID);
        clientList.set(List.of());
        selfId = UNASSIGNED_CLIENT_ID;
        relayed.set(0);
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

    // ------------------------------------------------------------- addressing

    /** The id the server handed to this connection; assigned on first use so join order decides it. */
    private int idOf(Connection connection) {
        return clientIds.computeIfAbsent(connection, key -> nextClientId.getAndIncrement());
    }

    /** The sockets a message addressed {@code (scope, targets)} must be copied onto, and who it missed. */
    private record Delivery(List<Connection> destinations, List<Integer> missing) {
    }

    /**
     * Resolves an address against the current client directory: every client but the sender for a
     * broadcast, the named clients otherwise. The sender is never among them — it draws its own bubble.
     */
    private Delivery resolve(Connection source, ChatScope scope, List<Integer> targets) {
        List<Connection> destinations = new ArrayList<>();
        Set<Integer> connected = new HashSet<>();
        for (Connection connection : hub.connections()) {
            int id = idOf(connection);
            connected.add(id);
            if (connection.equals(source)) {
                continue;
            }
            if (scope.isBroadcast() || targets.contains(id)) {
                destinations.add(connection);
            }
        }
        List<Integer> missing = new ArrayList<>();
        if (!scope.isBroadcast()) {
            for (int target : targets) {
                if (!connected.contains(target)) {
                    missing.add(target);
                }
            }
        }
        return new Delivery(List.copyOf(destinations), List.copyOf(missing));
    }

    private void sendRelay(List<Connection> destinations, String sender, ChatScope scope,
                           List<Integer> targets, String text) {
        if (destinations.isEmpty()) {
            return;
        }
        Packet relay = Packet.relay(sender, scope, targets, text);
        for (Connection connection : destinations) {
            try {
                connection.send(relay);
            } catch (IOException e) {
                AppLogger.warn("Could not relay chat to a client: " + e.getMessage());
            }
        }
    }

    /** Server only: rebuild the directory from the hub and push every client its own copy. */
    private void publishRoster() {
        if (!isServer()) {
            return;
        }
        try {
            List<ClientInfo> snapshot = new ArrayList<>();
            for (Connection connection : hub.connections()) {
                snapshot.add(new ClientInfo(idOf(connection), hub.nameOf(connection)));
            }
            if (snapshot.size() > Packet.MAX_ROSTER_ENTRIES) {
                AppLogger.warn("Session has more clients than the roster can carry ("
                        + snapshot.size() + "); only the first " + Packet.MAX_ROSTER_ENTRIES
                        + " are addressable");
                snapshot = snapshot.subList(0, Packet.MAX_ROSTER_ENTRIES);
            }
            List<ClientInfo> published = List.copyOf(snapshot);
            clientList.set(published);
            for (Connection connection : hub.connections()) {
                try {
                    connection.send(Packet.roster(idOf(connection), entriesFor(published, connection)));
                } catch (IOException e) {
                    AppLogger.warn("Could not send ROSTER: " + e.getMessage());
                }
            }
            notifyRoster();
        } catch (RuntimeException e) {
            AppLogger.error("Could not publish the client roster", e);
        }
    }

    /**
     * The entries one client receives. A client must find itself in its own copy — that is how it
     * learns its id — so when the directory is full the last entry makes way for the recipient.
     */
    private List<Packet.RosterEntry> entriesFor(List<ClientInfo> directory, Connection recipient) {
        int recipientId = idOf(recipient);
        List<Packet.RosterEntry> entries = new ArrayList<>(directory.size());
        boolean recipientPresent = false;
        for (ClientInfo client : directory) {
            entries.add(new Packet.RosterEntry(client.id(), client.name()));
            recipientPresent |= client.id() == recipientId;
        }
        if (!recipientPresent && !entries.isEmpty()) {
            entries.set(entries.size() - 1,
                    new Packet.RosterEntry(recipientId, hub.nameOf(recipient)));
        }
        return List.copyOf(entries);
    }

    private void onRosterReceived(Connection source, Packet packet) {
        if (isServer()) {
            AppLogger.warn("Ignoring ROSTER on the server");
            return;
        }
        List<ClientInfo> clients = new ArrayList<>();
        for (Packet.RosterEntry entry : packet.rosterEntries()) {
            clients.add(new ClientInfo(entry.id(), entry.name()));
        }
        selfId = packet.rosterSelfId();
        clientList.set(List.copyOf(clients));
        AppLogger.info("Client list updated: " + clients.size() + " client(s), I am #" + selfId);
        notifyRoster();
    }

    /** Turns target ids into display names, so the UI can label a message without the directory. */
    private List<String> audienceNames(List<Integer> targets) {
        if (targets.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>(targets.size());
        for (int target : targets) {
            String name = null;
            for (ClientInfo client : clientList.get()) {
                if (client.id() == target) {
                    name = client.name();
                    break;
                }
            }
            names.add(name == null ? "#" + target : name);
        }
        return List.copyOf(names);
    }

    private void handleIncomingChat(Connection source, Packet packet) {
        if (!isServer()) {
            // A client has one socket, so an unaddressed CHAT here is simply a message for us.
            chatManager.handle(packet, hub.nameOf(source));
            return;
        }
        String sender = hub.nameOf(source);
        ChatScope scope = packet.chatScope();
        List<Integer> targets = packet.chatTargets();
        String text = packet.chatText();

        Delivery delivery = resolve(source, scope, targets);
        if (!delivery.destinations().isEmpty()) {
            sendRelay(delivery.destinations(), sender, scope, targets, text);
            relayed.incrementAndGet();
        }
        notifyServerLog(relayLine(source, scope, targets, delivery, text));
    }

    // ------------------------------------------------------------- server log

    /**
     * One line per relayed message: who sent it, from which address, how it was addressed, who
     * received it and how much was copied. Message text is deliberately not logged — the server
     * relays, it does not read.
     */
    private String relayLine(Connection source, ChatScope scope, List<Integer> targets,
                             Delivery delivery, String text) {
        StringBuilder line = new StringBuilder()
                .append(describeClient(idOf(source), hub.nameOf(source), source))
                .append("  ").append(scope.name())
                .append("  -> ");
        if (scope.isBroadcast()) {
            line.append("everyone");
        } else {
            List<String> recipients = new ArrayList<>(targets.size());
            for (int target : targets) {
                if (delivery.missing().contains(target)) {
                    recipients.add("#" + target + " NOT CONNECTED");
                } else {
                    recipients.add(hub.nameOf(idConnection(target)) + " #" + target);
                }
            }
            line.append(recipients.isEmpty() ? "nobody" : String.join(", ", recipients));
        }
        int bytes = text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
        return line.append(" (").append(delivery.destinations().size()).append(" delivered, ")
                .append(bytes).append(" B)").toString();
    }

    private Connection idConnection(int id) {
        for (Connection connection : hub.connections()) {
            if (idOf(connection) == id) {
                return connection;
            }
        }
        return null;
    }

    private static String describeClient(int id, String name, Connection connection) {
        return name + " #" + id + " @" + addressOf(connection);
    }

    private static String addressOf(Connection connection) {
        InetSocketAddress address = connection == null ? null : connection.remoteAddress();
        if (address == null) {
            return "unknown";
        }
        return address.getAddress() == null
                ? address.getHostString() + ":" + address.getPort()
                : address.getAddress().getHostAddress() + ":" + address.getPort();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Session is closed");
        }
    }

    private void closeServerQuietly() {
        Acceptor current = server;
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

    private void notifyRoster() {
        try {
            listener.onRosterChanged(selfId, clientList.get());
        } catch (RuntimeException e) {
            AppLogger.error("Session listener failed in onRosterChanged", e);
        }
    }

    private void notifyServerLog(String message) {
        AppLogger.info("Relay: " + message);
        try {
            listener.onServerLog(message);
        } catch (RuntimeException e) {
            AppLogger.error("Session listener failed in onServerLog", e);
        }
    }

    private final class PacketRouter implements Connection.Listener {

        private volatile Connection owner;

        void attach(Connection connection) {
            this.owner = connection;
        }

        @Override
        public void onPacket(Packet packet) {
            try {
                route(packet);
            } catch (RuntimeException e) {
                // A malformed packet must not take the reader thread down with it.
                AppLogger.error("Dropping invalid " + packet.type() + " packet from a client", e);
            }
        }

        private void route(Packet packet) {
            MessageType type = packet.type();
            Connection source = owner;
            switch (type) {
                case CHAT -> handleIncomingChat(source, packet);
                case RELAY -> chatManager.handle(packet, hub.nameOf(source));
                case ROSTER -> onRosterReceived(source, packet);
                case HELLO -> {
                    hub.add(source, packet.helloName());
                    if (isServer()) {
                        notifyServerLog("+ " + describeClient(idOf(source), packet.helloName(), source)
                                + " joined");
                    }
                    publishRoster();               // the new name must reach every address list
                    if (!isServer()) {
                        notifyStatus("Connected");
                    }
                }
                case DISCONNECT -> {
                    AppLogger.info("Client sent DISCONNECT");
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
            deliverChat(sender, message, ChatScope.BROADCAST, List.of());
        }

        @Override
        public void onChatMessage(String sender, String message, ChatScope scope, List<Integer> targets) {
            deliverChat(sender, message, scope, targets);
        }

        @Override
        public void onChatError(String reason) {
            notifyStatus(reason);
        }
    }

    private void deliverChat(String sender, String message, ChatScope scope, List<Integer> targets) {
        List<String> audience = audienceNames(targets);
        try {
            listener.onChatMessage(sender, message, scope, audience);
        } catch (RuntimeException e) {
            AppLogger.error("Session listener failed in onChatMessage", e);
        }
    }
}
