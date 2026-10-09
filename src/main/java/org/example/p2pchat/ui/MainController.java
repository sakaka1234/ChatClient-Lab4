package org.example.p2pchat.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.example.p2pchat.protocol.ChatScope;
import org.example.p2pchat.session.ChatSession;
import org.example.p2pchat.session.ClientInfo;
import org.example.p2pchat.session.SessionListener;
import org.example.p2pchat.util.NetworkUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The application window: a start form, and one of two session views depending on which side of the
 * connection this instance is.
 *
 * <p>A <b>client</b> gets the chat view — the message list, the <em>To:</em> picker and the message
 * box. A <b>server</b> gets the relay view — the client list and the relay log, and nothing to type
 * into, because a server copies messages between its clients rather than taking part in them. Both
 * views are built up front and swapped by {@link #showConnectionPane()} and friends; the window is
 * never rebuilt.
 */
public final class MainController implements SessionListener {

    /** Which connection form the start screen offers. */
    private enum Mode {
        SERVER, CLIENT
    }

    /** How many relay-log lines are kept before the oldest are dropped. */
    private static final int MAX_LOG_LINES = 500;

    private final Stage stage;
    private final StackPane root = new StackPane();

    private final GridPane connectionPane;
    private final VBox serverPane;
    private final VBox clientPane;

    private final Button modeServerButton;
    private final Button modeClientButton;
    private final VBox serverFields;
    private final VBox clientFields;
    private final TextField listenPortField;
    private final TextField serverIpField;
    private final TextField serverPortField;
    private final TextField displayNameField;
    private final Button startButton;
    private final Label connectionStatusLabel;

    // ------------------------------------------------------------- the server

    private final Label serverSummaryLabel = new Label(":/");
    private final Label serverStatusLabel = new Label("Disconnected");
    private final ListView<String> serverClientList = new ListView<>();
    private final ListView<String> relayLog = new ListView<>();
    private final List<String> relayLogLines = new ArrayList<>();

    // ------------------------------------------------------------- the client

    private final ListView<ChatItem> chatList = new ListView<>();
    private final TextArea messageField = new TextArea();
    private final Label clientLabel = new Label("No server");
    private final Label clientStatusLabel = new Label("Disconnected");
    private final Label clientTitleLabel = title("CLIENT");

    /** The name this client connected under, shown beside CLIENT so two windows are told apart. */
    private String selfName = "";

    /** Address picker for the next message: nothing checked means "everyone". */
    private final MenuButton recipientButton = new MenuButton("Everyone");
    private final Set<Integer> selectedTargets = new LinkedHashSet<>();
    private List<ClientInfo> clientDirectory = List.of();
    private int selfClientId = ChatSession.UNASSIGNED_CLIENT_ID;

    private final ChatBubbleFactory bubbleFactory = new ChatBubbleFactory();
    private final ChatItemTracker chatItems = new ChatItemTracker();

    private ChatSession session;
    private Mode mode = Mode.SERVER;
    private boolean busy;

    /** True between the first line of {@link #onStart()} and the pane it settles on. */
    private boolean starting;

    public MainController(Stage stage) {
        this.stage = stage;

        modeServerButton = new Button("SERVER");
        modeServerButton.getStyleClass().add("mode-button");
        modeServerButton.setMaxWidth(Double.MAX_VALUE);
        modeServerButton.setOnAction(event -> setMode(Mode.SERVER));

        modeClientButton = new Button("CLIENT");
        modeClientButton.getStyleClass().add("mode-button");
        modeClientButton.setMaxWidth(Double.MAX_VALUE);
        modeClientButton.setOnAction(event -> setMode(Mode.CLIENT));

        listenPortField = new TextField("5000");
        listenPortField.getStyleClass().add("field");
        serverIpField = new TextField("127.0.0.1");
        serverIpField.getStyleClass().add("field");
        serverPortField = new TextField("5000");
        serverPortField.getStyleClass().add("field");
        displayNameField = new TextField(defaultDisplayName());
        displayNameField.getStyleClass().add("field");

        serverFields = new VBox(6,
                fieldLabel("Local port"),
                listenPortField,
                hint("Accepts any number of clients, keeps the client list, and copies each message "
                        + "only to the clients it is addressed to. The server relays; it never chats "
                        + "itself."));
        clientFields = new VBox(6,
                fieldLabel("Server IP"),
                serverIpField,
                fieldLabel("Server port"),
                serverPortField);

        startButton = new Button("Start Server");
        startButton.getStyleClass().add("primary-button");
        startButton.setMaxWidth(Double.MAX_VALUE);
        startButton.setOnAction(event -> onStart());

        connectionStatusLabel = new Label("Disconnected");
        connectionStatusLabel.getStyleClass().add("status-label");
        connectionStatusLabel.setWrapText(true);

        HBox modeRow = new HBox(8, modeServerButton, modeClientButton);
        HBox.setHgrow(modeServerButton, Priority.ALWAYS);
        HBox.setHgrow(modeClientButton, Priority.ALWAYS);

        VBox connectionContent = new VBox(14,
                title("CHAT"),
                subtitle("A server relays between its clients - send to one client, to a group, or to "
                        + "everyone. The server itself takes no part in the conversation."),
                fieldLabel("Display name"),
                displayNameField,
                modeRow,
                serverFields,
                clientFields,
                startButton,
                connectionStatusLabel,
                hint("Run one instance as the Server, then connect one or more clients to its port."));
        connectionContent.setMaxWidth(520);

        connectionPane = new GridPane();
        connectionPane.getStyleClass().add("card");
        connectionPane.getColumnConstraints().add(percent(100));
        connectionPane.setAlignment(Pos.TOP_CENTER);
        connectionPane.setPadding(new Insets(28));
        connectionPane.add(connectionContent, 0, 0);

        serverPane = buildServerPane();
        clientPane = buildClientPane();

        root.getStyleClass().add("app-backdrop");
        root.getChildren().addAll(connectionPane, serverPane, clientPane);
        root.setPadding(new Insets(24));

        chatItems.addChangeListener(() -> runOnFx(this::syncChatList));

        setMode(Mode.SERVER);
        showConnectionPane();
        Platform.runLater(connectionPane::requestFocus);
    }

    public Parent root() {
        return root;
    }

    public void shutdown() {
        if (session != null) {
            session.close();
        }
    }

    // ------------------------------------------------------------------ panes

    /**
     * The server view: who is connected, and what the server has relayed. There is deliberately no
     * message box and no recipient picker — a server has nothing to send.
     */
    private VBox buildServerPane() {
        serverClientList.getStyleClass().add("client-list");
        serverClientList.setPlaceholder(hint("No clients connected yet."));
        VBox clientsPanel = new VBox(10, panelTitle("Clients"), serverClientList);
        clientsPanel.getStyleClass().add("panel");
        clientsPanel.setPrefWidth(260);
        clientsPanel.setMinWidth(200);
        VBox.setVgrow(serverClientList, Priority.ALWAYS);

        relayLog.getStyleClass().add("server-log");
        relayLog.setPlaceholder(hint("Nothing relayed yet."));
        VBox logPanel = new VBox(10, panelTitle("Relay log"), relayLog);
        logPanel.getStyleClass().add("panel");
        VBox.setVgrow(relayLog, Priority.ALWAYS);

        HBox body = new HBox(16, clientsPanel, logPanel);
        HBox.setHgrow(logPanel, Priority.ALWAYS);
        VBox.setVgrow(body, Priority.ALWAYS);

        Button stopButton = new Button("Stop Server");
        stopButton.getStyleClass().add("danger-button");
        stopButton.setOnAction(event -> endSession());

        serverSummaryLabel.getStyleClass().addAll("summary-label", "server-summary");
        serverStatusLabel.getStyleClass().add("pill");
        HBox topbar = new HBox(12, title("SERVER"));
        topbar.setAlignment(Pos.CENTER_LEFT);
        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);
        topbar.getChildren().addAll(topSpacer, serverSummaryLabel, serverStatusLabel, stopButton);
        topbar.getStyleClass().add("topbar");

        VBox pane = new VBox(16, topbar, body);
        pane.getStyleClass().add("session-root");
        pane.setVisible(false);
        pane.setManaged(false);
        return pane;
    }

    /** The client view: the conversation, plus the address picker that turns it into unicast/multicast/broadcast. */
    private VBox buildClientPane() {
        VBox chatPanel = new VBox(10, panelTitle("Chat"));
        chatList.getStyleClass().add("chat-list");
        chatList.setPlaceholder(hint("No messages yet."));
        chatList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(ChatItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(null);
                setGraphic(bubbleFactory.create(item));
            }
        });
        VBox.setVgrow(chatList, Priority.ALWAYS);

        messageField.setPromptText("Type message...");
        messageField.setPrefRowCount(2);
        messageField.setWrapText(true);
        messageField.getStyleClass().add("field");
        messageField.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER && !event.isShiftDown()) {
                event.consume();
                onSend();
            }
        });

        Button sendButton = new Button("Send");
        sendButton.getStyleClass().add("primary-button");
        sendButton.setMaxWidth(Double.MAX_VALUE);
        sendButton.setOnAction(event -> onSend());

        recipientButton.getStyleClass().add("recipient-button");
        recipientButton.setMaxWidth(Double.MAX_VALUE);
        recipientButton.setTooltip(new Tooltip(
                "Nothing checked -> everyone (broadcast)\n"
                        + "One client -> private (unicast)\n"
                        + "Two or more -> group (multicast)"));
        rebuildRecipientMenu();

        HBox recipientRow = new HBox(8, fieldLabel("To:"), recipientButton);
        recipientRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(recipientButton, Priority.ALWAYS);

        chatPanel.getChildren().addAll(
                chatList,
                recipientRow,
                hint("Every message carries an address: the server copies it only to the clients named "
                        + "in it."),
                messageField,
                sendButton);
        chatPanel.getStyleClass().add("panel");
        GridPane.setHgrow(chatPanel, Priority.ALWAYS);
        GridPane.setVgrow(chatPanel, Priority.ALWAYS);

        Button disconnectButton = new Button("Disconnect");
        disconnectButton.getStyleClass().add("danger-button");
        disconnectButton.setOnAction(event -> endSession());

        clientLabel.getStyleClass().addAll("summary-label", "client-summary");
        clientStatusLabel.getStyleClass().add("pill");
        clientTitleLabel.getStyleClass().addAll("client-title", "client-role");
        HBox topbar = new HBox(12, clientTitleLabel);
        topbar.setAlignment(Pos.CENTER_LEFT);
        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);
        topbar.getChildren().addAll(topSpacer, clientLabel, clientStatusLabel, disconnectButton);
        topbar.getStyleClass().add("topbar");

        VBox pane = new VBox(16, topbar, chatPanel);
        VBox.setVgrow(chatPanel, Priority.ALWAYS);
        pane.getStyleClass().add("session-root");
        pane.setVisible(false);
        pane.setManaged(false);
        return pane;
    }

    /** Shows exactly one of the three views, and names the window after it. */
    private void showOnly(Region pane) {
        for (Region candidate : List.of(connectionPane, serverPane, clientPane)) {
            boolean active = candidate == pane;
            candidate.setVisible(active);
            candidate.setManaged(active);
        }
        if (pane == serverPane) {
            stage.setTitle("Chat — Server");
        } else if (pane == clientPane) {
            stage.setTitle(selfName.isBlank() ? "Chat — Client" : "Chat — Client · " + selfName);
        } else {
            stage.setTitle("Chat");
        }
    }

    private void showConnectionPane() {
        showOnly(connectionPane);
    }

    private void showServerPane() {
        showOnly(serverPane);
        refreshServerPane();
    }

    private void showClientPane() {
        showOnly(clientPane);
    }

    // ------------------------------------------------------------- the form

    private void setMode(Mode newMode) {
        mode = newMode;
        boolean server = newMode == Mode.SERVER;

        serverFields.setVisible(server);
        serverFields.setManaged(server);
        clientFields.setVisible(!server);
        clientFields.setManaged(!server);

        modeServerButton.getStyleClass().remove("mode-button-active");
        modeClientButton.getStyleClass().remove("mode-button-active");
        (server ? modeServerButton : modeClientButton).getStyleClass().add("mode-button-active");
        startButton.setText(server ? "Start Server" : "Connect");
    }

    private void onStart() {
        if (busy) {
            return;
        }
        starting = true;
        try {
            String displayName = requireDisplayName(displayNameField.getText());
            ChatSession newSession = new ChatSession(this, displayName);
            if (mode == Mode.SERVER) {
                int port = parsePort(listenPortField.getText(), "Local port");
                newSession.startServer(port);
                replaceSession(newSession);
                busy = true;
                startButton.setDisable(true);
                resetServerPane();
                showServerPane();
                showConnectionStatus("Listening on port " + port);
            } else {
                String host = requireHost(serverIpField.getText());
                int port = parsePort(serverPortField.getText(), "Server port");
                newSession.startClient(host, port, ChatSession.CONNECT_TIMEOUT_MILLIS);
                replaceSession(newSession);
                busy = true;
                startButton.setDisable(true);
                showClientPane();
                refreshClientHeader();
                showConnectionStatus("Connected to " + host + ":" + port);
                messageField.requestFocus();
            }
        } catch (IllegalArgumentException e) {
            showConnectionStatus("Invalid input: " + e.getMessage());
            showConnectionPane();
        } catch (Exception e) {
            showConnectionStatus("Connection failed: " + describe(e));
            showConnectionPane();
        } finally {
            starting = false;
        }
    }

    private void replaceSession(ChatSession newSession) {
        if (session != null) {
            session.close();
        }
        session = newSession;
        selfName = newSession.displayName();
        // The new session may already have published a directory (the server does so on start), so
        // take it from the session instead of starting from an empty one.
        selectedTargets.clear();
        selfClientId = newSession.selfClientId();
        clientDirectory = newSession.clients();
        rebuildRecipientMenu();
        chatItems.clear();
    }

    private void endSession() {
        if (session != null) {
            session.disconnect();
        }
        busy = false;
        startButton.setDisable(false);
    }

    private boolean runningAsServer() {
        return session != null && session.isServer();
    }

    // ------------------------------------------------------------- the client

    private void onSend() {
        String text = messageField.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        if (session == null || !session.isConnected()) {
            showConnectionStatus("Not connected");
            return;
        }
        List<Integer> targets = List.copyOf(selectedTargets);
        ChatScope scope = scopeFor(targets);
        session.sendChat(text, scope, targets);
        appendChat(ChatItem.outboundText(text, scope, namesOf(clientDirectory, targets),
                System.currentTimeMillis()));
        messageField.clear();
    }

    // -------------------------------------------------------------- addressing

    /**
     * Rebuilds the recipient picker from the current directory and drops selected ids whose client
     * left. You never appear in your own picker.
     */
    private void rebuildRecipientMenu() {
        retainSelectable(clientDirectory, selectedTargets, selfClientId);
        recipientButton.getItems().clear();
        for (ClientInfo client : selectableClients(clientDirectory, selfClientId)) {
            CheckBox box = new CheckBox(client.name() + "  #" + client.id());
            box.getStyleClass().add("recipient-choice");
            box.setSelected(selectedTargets.contains(client.id()));
            box.selectedProperty().addListener((observable, was, isSelected) -> {
                if (isSelected) {
                    selectedTargets.add(client.id());
                } else {
                    selectedTargets.remove(client.id());
                }
                updateRecipientLabel();
            });
            recipientButton.getItems().add(new CustomMenuItem(box, false));
        }
        if (recipientButton.getItems().isEmpty()) {
            MenuItem none = new MenuItem("No other client yet");
            none.setDisable(true);
            recipientButton.getItems().add(none);
        }
        updateRecipientLabel();
    }

    /**
     * Who the picker offers: every client of the session except this instance. You never appear in
     * your own recipient list, so a message cannot be addressed to its own sender. The server is not
     * in the directory at all.
     */
    static List<ClientInfo> selectableClients(List<ClientInfo> directory, int selfClientId) {
        List<ClientInfo> selectable = new ArrayList<>(directory.size());
        for (ClientInfo client : directory) {
            if (client.id() != selfClientId) {
                selectable.add(client);
            }
        }
        return selectable;
    }

    /**
     * Drops from {@code selected} every id that is no longer offered — a client that left between the
     * picker being drawn and the message being sent. Without this the next send would address a ghost,
     * and the server would have to drop the message as unreachable.
     */
    static void retainSelectable(List<ClientInfo> directory, Set<Integer> selected, int selfClientId) {
        Set<Integer> offerable = new LinkedHashSet<>();
        for (ClientInfo client : selectableClients(directory, selfClientId)) {
            offerable.add(client.id());
        }
        selected.retainAll(offerable);
    }

    private void updateRecipientLabel() {
        if (selectedTargets.isEmpty()) {
            recipientButton.setText("Everyone");
        } else {
            recipientButton.setText(String.join(", ",
                    namesOf(clientDirectory, List.copyOf(selectedTargets))));
        }
    }

    /**
     * Nothing selected means everyone; one client is a unicast, several a multicast. Package-private
     * so the rule can be tested without standing up a JavaFX toolkit.
     */
    static ChatScope scopeFor(List<Integer> targets) {
        if (targets.isEmpty()) {
            return ChatScope.BROADCAST;
        }
        return targets.size() == 1 ? ChatScope.UNICAST : ChatScope.MULTICAST;
    }

    /** Turns target ids into names, falling back to {@code #id} for a client the directory no longer lists. */
    static List<String> namesOf(List<ClientInfo> directory, List<Integer> ids) {
        List<String> names = new ArrayList<>(ids.size());
        for (int id : ids) {
            String name = null;
            for (ClientInfo client : directory) {
                if (client.id() == id) {
                    name = client.name();
                    break;
                }
            }
            names.add(name == null ? "#" + id : name);
        }
        return names;
    }

    private void resetAddressing() {
        selectedTargets.clear();
        selfClientId = ChatSession.UNASSIGNED_CLIENT_ID;
        clientDirectory = List.of();
        rebuildRecipientMenu();
    }

    private void appendChat(ChatItem item) {
        chatItems.add(item);
    }

    private void syncChatList() {
        chatList.getItems().setAll(chatItems.items());
        if (!chatList.getItems().isEmpty()) {
            chatList.scrollTo(chatList.getItems().size() - 1);
        }
    }

    // ------------------------------------------------------------- the server

    private void resetServerPane() {
        relayLogLines.clear();
        relayLog.getItems().clear();
        serverClientList.getItems().clear();
        serverSummaryLabel.setText(":/");
    }

    private void refreshServerPane() {
        int port = session == null ? -1 : session.port();
        int relayed = session == null ? 0 : session.relayedCount();
        serverSummaryLabel.setText(":" + (port < 0 ? "?" : port)
                + " · Clients (" + clientDirectory.size() + ")"
                + " · Relayed (" + relayed + ")");

        List<String> rows = new ArrayList<>(clientDirectory.size());
        for (ClientInfo client : clientDirectory) {
            rows.add("#" + client.id() + "  " + client.name());
        }
        serverClientList.getItems().setAll(rows);
    }

    private void refreshClientHeader() {
        // The name rides on the CLIENT chip rather than in the summary: it is what tells this window
        // apart from the other clients at a glance.
        clientTitleLabel.setText(selfName.isBlank() ? "CLIENT" : "CLIENT · " + selfName);
        if (session == null) {
            clientLabel.setText("No server");
            return;
        }
        String me = selfClientId == ChatSession.UNASSIGNED_CLIENT_ID
                ? "" : " · you are #" + selfClientId;
        String where = "Connected to " + remoteDescription();
        int count = clientDirectory.size();
        clientLabel.setText(count <= 1 ? where + me : where + " · " + count + " clients" + me);
    }

    // ------------------------------------------------------------- callbacks

    private void showConnectionStatus(String status) {
        connectionStatusLabel.setText(status);
        clientStatusLabel.setText(status);
        serverStatusLabel.setText(status);
    }

    @Override
    public void onStatusChanged(String status) {
        runOnFx(() -> {
            showConnectionStatus(status);
            if (starting) {
                return;                     // onStart settles on the right pane once it knows it worked
            }
            if (status.equals(ChatSession.STATUS_DISCONNECTED)) {
                if (runningAsServer()) {
                    leaveServerPane();
                } else {
                    leaveClientPane();
                }
                return;
            }
            if (status.equals(ChatSession.STATUS_SERVER_DISCONNECTED)) {
                leaveClientPane();
                return;
            }
            if (status.equals(ChatSession.STATUS_CONNECTION_FAILED) && runningAsServer()) {
                // The listening socket died under us, so no further client can join.
                session.close();
                leaveServerPane();
            }
        });
    }

    @Override
    public void onChatMessage(String sender, String message) {
        onChatMessage(sender, message, ChatScope.BROADCAST, List.of());
    }

    @Override
    public void onChatMessage(String sender, String message, ChatScope scope, List<String> audience) {
        runOnFx(() -> appendChat(ChatItem.inboundText(sender, message, scope, audience,
                System.currentTimeMillis())));
    }

    @Override
    public void onRosterChanged(int selfId, List<ClientInfo> clients) {
        runOnFx(() -> {
            selfClientId = selfId;
            clientDirectory = List.copyOf(clients);
            if (runningAsServer()) {
                refreshServerPane();
                return;
            }
            rebuildRecipientMenu();
            refreshClientHeader();
        });
    }

    @Override
    public void onServerLog(String message) {
        runOnFx(() -> {
            relayLogLines.add(message);
            if (relayLogLines.size() > MAX_LOG_LINES) {
                relayLogLines.remove(0);
            }
            relayLog.getItems().setAll(relayLogLines);
            relayLog.scrollTo(relayLog.getItems().size() - 1);
        });
    }

    @Override
    public void onDisconnected(String reason) {
        runOnFx(() -> {
            if (runningAsServer()) {
                leaveServerPane();
            } else {
                leaveClientPane();
            }
        });
    }

    private void leaveServerPane() {
        resetServerPane();
        busy = false;
        startButton.setDisable(false);
        showConnectionPane();
    }

    private void leaveClientPane() {
        selfName = "";
        clientTitleLabel.setText("CLIENT");
        clientLabel.setText("No server");
        resetAddressing();
        busy = false;
        startButton.setDisable(false);
        showConnectionPane();
    }

    private String remoteDescription() {
        return session == null ? "unknown" : session.remoteDescription();
    }

    private static void runOnFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    private static String defaultDisplayName() {
        String user = System.getProperty("user.name");
        return user == null || user.isBlank() ? "Client" : user;
    }

    private static String requireDisplayName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Display name is required");
        }
        if (name.length() > 32) {
            throw new IllegalArgumentException("Display name must be 32 characters or fewer");
        }
        return name;
    }

    private static int parsePort(String raw, String fieldName) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldName + " must be a number");
        }
        if (port < NetworkUtils.MIN_PORT || port > NetworkUtils.MAX_PORT) {
            throw new IllegalArgumentException(fieldName + " must be between "
                    + NetworkUtils.MIN_PORT + " and " + NetworkUtils.MAX_PORT);
        }
        return port;
    }

    private static String requireHost(String raw) {
        String host = raw == null ? "" : raw.trim();
        if (host.isEmpty()) {
            throw new IllegalArgumentException("Server IP is required");
        }
        return host;
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static Label title(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("app-title");
        return label;
    }

    private static Label subtitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("app-subtitle");
        label.setWrapText(true);
        return label;
    }

    private static Label panelTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("panel-title");
        return label;
    }

    private static Label fieldLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        return label;
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-hint");
        label.setWrapText(true);
        return label;
    }

    private static ColumnConstraints percent(double value) {
        ColumnConstraints constraints = new ColumnConstraints();
        constraints.setPercentWidth(value);
        return constraints;
    }
}
