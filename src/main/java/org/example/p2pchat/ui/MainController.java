package org.example.p2pchat.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.example.p2pchat.session.PeerSession;
import org.example.p2pchat.session.SessionListener;
import org.example.p2pchat.util.NetworkUtils;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public final class MainController implements SessionListener {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private enum Mode {
        HOST, CONNECT
    }

    private final Stage stage;
    private final StackPane root = new StackPane();

    private final GridPane connectionPane;
    private final VBox sessionPane;

    private final Button modeHostButton;
    private final Button modeConnectButton;
    private final VBox hostFields;
    private final VBox connectFields;
    private final TextField hostPortField;
    private final TextField peerIpField;
    private final TextField peerPortField;
    private final TextField displayNameField;
    private final Button startButton;
    private final Label connectionStatusLabel;

    private final ListView<ChatItem> chatList = new ListView<>();
    private final TextArea messageField = new TextArea();
    private final ListView<String> transferLogList = new ListView<>();
    private final ProgressBar fileProgressBar = new ProgressBar(0);
    private final Label fileInfoLabel = new Label("No transfer yet");
    private final Label filePercentLabel = new Label("0%");
    private final Label fileStatusLabel = new Label("Idle");
    private final Label peerLabel = new Label("No peer");
    private final Label sessionStatusLabel = new Label("Disconnected");

    private final ChatBubbleFactory bubbleFactory =
            new ChatBubbleFactory(this::onSaveAs, this::onOpen, this::onAcceptOffer, this::onDeclineOffer);
    private final ChatItemTracker chatItems = new ChatItemTracker();

    private PeerSession session;
    private Mode mode = Mode.HOST;
    private boolean busy;

    public MainController(Stage stage) {
        this.stage = stage;

        modeHostButton = new Button("HOST");
        modeHostButton.getStyleClass().add("mode-button");
        modeHostButton.setMaxWidth(Double.MAX_VALUE);
        modeHostButton.setOnAction(event -> setMode(Mode.HOST));

        modeConnectButton = new Button("CONNECT");
        modeConnectButton.getStyleClass().add("mode-button");
        modeConnectButton.setMaxWidth(Double.MAX_VALUE);
        modeConnectButton.setOnAction(event -> setMode(Mode.CONNECT));

        hostPortField = new TextField("5000");
        hostPortField.getStyleClass().add("field");
        peerIpField = new TextField("127.0.0.1");
        peerIpField.getStyleClass().add("field");
        peerPortField = new TextField("5000");
        peerPortField.getStyleClass().add("field");
        displayNameField = new TextField(defaultDisplayName());
        displayNameField.getStyleClass().add("field");

        hostFields = new VBox(6,
                fieldLabel("Local port"),
                hostPortField,
                hint("Hosts a relay: up to two clients connect, and chat is forwarded to everyone."));
        connectFields = new VBox(6,
                fieldLabel("Peer IP"),
                peerIpField,
                fieldLabel("Peer port"),
                peerPortField);

        startButton = new Button("Start Host");
        startButton.getStyleClass().add("primary-button");
        startButton.setMaxWidth(Double.MAX_VALUE);
        startButton.setOnAction(event -> onStart());

        connectionStatusLabel = new Label("Disconnected");
        connectionStatusLabel.getStyleClass().add("status-label");
        connectionStatusLabel.setWrapText(true);

        HBox modeRow = new HBox(8, modeHostButton, modeConnectButton);
        HBox.setHgrow(modeHostButton, Priority.ALWAYS);
        HBox.setHgrow(modeConnectButton, Priority.ALWAYS);

        VBox connectionContent = new VBox(14,
                title("P2P CHAT"),
                subtitle("Up to three instances chat through one Host; no server in between."),
                fieldLabel("Display name"),
                displayNameField,
                modeRow,
                hostFields,
                connectFields,
                startButton,
                connectionStatusLabel,
                hint("Run the Host first, then Connect two more instances to its port."));
        connectionContent.setMaxWidth(520);

        connectionPane = new GridPane();
        connectionPane.getStyleClass().add("card");
        connectionPane.getColumnConstraints().add(percent(100));
        connectionPane.setAlignment(Pos.TOP_CENTER);
        connectionPane.setPadding(new Insets(28));
        connectionPane.add(connectionContent, 0, 0);

        sessionPane = buildSessionPane();

        root.getStyleClass().add("app-backdrop");
        root.getChildren().addAll(connectionPane, sessionPane);
        root.setPadding(new Insets(24));

        chatItems.addChangeListener(() -> runOnFx(this::syncChatList));

        setMode(Mode.HOST);
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

    private VBox buildSessionPane() {
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

        chatPanel.getChildren().addAll(chatList, messageField, sendButton);
        chatPanel.getStyleClass().add("panel");
        GridPane.setHgrow(chatPanel, Priority.ALWAYS);
        GridPane.setVgrow(chatPanel, Priority.ALWAYS);

        VBox filePanel = new VBox(10, panelTitle("File transfer"));

        Button sendFileButton = new Button("Send File");
        sendFileButton.getStyleClass().add("ghost-button");
        sendFileButton.setMaxWidth(Double.MAX_VALUE);
        sendFileButton.setOnAction(event -> onSendFile());

        fileInfoLabel.getStyleClass().add("field-hint");
        fileInfoLabel.setWrapText(true);

        fileProgressBar.setMaxWidth(Double.MAX_VALUE);
        fileProgressBar.getStyleClass().add("file-progress");

        filePercentLabel.getStyleClass().add("file-percent");
        fileStatusLabel.getStyleClass().add("field-hint");
        fileStatusLabel.setWrapText(true);

        transferLogList.getStyleClass().add("transfer-log");
        transferLogList.setPlaceholder(hint("Transfer events appear here."));

        Button disconnectButton = new Button("Disconnect");
        disconnectButton.getStyleClass().add("danger-button");
        disconnectButton.setMaxWidth(Double.MAX_VALUE);
        disconnectButton.setOnAction(event -> onDisconnect());

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        VBox.setVgrow(transferLogList, Priority.ALWAYS);

        filePanel.getChildren().addAll(
                sendFileButton,
                fileInfoLabel,
                fileProgressBar,
                filePercentLabel,
                fileStatusLabel,
                spacer,
                panelTitle("Transfer log"),
                transferLogList,
                disconnectButton);
        filePanel.getStyleClass().add("panel");
        GridPane.setVgrow(filePanel, Priority.ALWAYS);

        HBox topbar = new HBox(12, title("P2P CHAT"));
        topbar.setAlignment(Pos.CENTER_LEFT);
        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);
        peerLabel.getStyleClass().add("peer-label");
        sessionStatusLabel.getStyleClass().add("pill");
        topbar.getChildren().addAll(topSpacer, peerLabel, sessionStatusLabel);
        topbar.getStyleClass().add("topbar");

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(16);
        grid.getColumnConstraints().add(percent(60));
        grid.getColumnConstraints().add(percent(40));
        grid.add(chatPanel, 0, 0);
        grid.add(filePanel, 1, 0);
        VBox.setVgrow(grid, Priority.ALWAYS);

        VBox pane = new VBox(topbar, grid);
        pane.getStyleClass().add("session-root");
        pane.setVisible(false);
        pane.setManaged(false);
        return pane;
    }

    private void setMode(Mode newMode) {
        mode = newMode;
        boolean host = newMode == Mode.HOST;

        hostFields.setVisible(host);
        hostFields.setManaged(host);
        connectFields.setVisible(!host);
        connectFields.setManaged(!host);

        modeHostButton.getStyleClass().remove("mode-button-active");
        modeConnectButton.getStyleClass().remove("mode-button-active");
        (host ? modeHostButton : modeConnectButton).getStyleClass().add("mode-button-active");
        startButton.setText(host ? "Start Host" : "Connect");
    }

    private void onStart() {
        if (busy) {
            return;
        }
        String status = mode == Mode.HOST ? "Listening" : "Connected";
        try {
            String displayName = requireDisplayName(displayNameField.getText());
            PeerSession newSession = new PeerSession(receivedDirectory(), this, displayName,
                    FileTypes::canPreviewImage);
            if (mode == Mode.HOST) {
                int port = parsePort(hostPortField.getText(), "Local port");
                newSession.startHost(port);
                busy = true;
                startButton.setDisable(true);
                status = "Listening on port " + port;
            } else {
                String host = requireHost(peerIpField.getText());
                int port = parsePort(peerPortField.getText(), "Peer port");
                newSession.startClient(host, port, PeerSession.CONNECT_TIMEOUT_MILLIS);
                busy = true;
                startButton.setDisable(true);
                status = "Connected to " + host + ":" + port;
            }
            replaceSession(newSession);
        } catch (IllegalArgumentException e) {
            showConnectionStatus("Invalid input: " + e.getMessage());
            return;
        } catch (Exception e) {
            showConnectionStatus("Connection failed: " + describe(e));
            return;
        }
        showConnectionStatus(status);
    }

    private void replaceSession(PeerSession newSession) {
        if (session != null) {
            session.close();
        }
        session = newSession;
        chatItems.clear();
        resetTransferUi();
        transferLogList.getItems().clear();
    }

    private void onSend() {
        String text = messageField.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        if (session == null || !session.isConnected()) {
            showConnectionStatus("Not connected");
            return;
        }
        session.sendChat(text);
        appendChat(ChatItem.outboundText(text, System.currentTimeMillis()));
        messageField.clear();
    }

    private void onSendFile() {
        if (session == null || !session.isConnected()) {
            showConnectionStatus("Not connected");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a file to send");
        File selected = chooser.showOpenDialog(stage);
        if (selected == null) {
            return;
        }
        sendSelectedFile(selected.toPath());
    }

    private void sendSelectedFile(Path file) {
        String fileName = file.getFileName() == null ? file.toString() : file.getFileName().toString();
        long size;
        try {
            size = java.nio.file.Files.size(file);
        } catch (java.io.IOException e) {
            showConnectionStatus("Cannot read file: " + describe(e));
            return;
        }
        int fileId = session.allocateFileId();
        chatItems.trackOutbound(fileId,
                ChatItem.outboundFile(fileId, fileName, size, file, System.currentTimeMillis()));
        session.sendFile(file, fileId);
    }

    private void onSaveAs(ChatItem item) {
        Path source = item.localPath();
        if (source == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save file as");
        chooser.setInitialFileName(item.fileName());
        File target = chooser.showSaveDialog(stage);
        if (target == null) {
            return;
        }
        try {
            FileSaveSupport.saveAs(source, target.toPath());
            appendLog("Saved " + item.fileName() + " -> " + target);
        } catch (java.io.IOException e) {
            showConnectionStatus("Save failed: " + describe(e));
        }
    }

    private void onAcceptOffer(ChatItem item) {
        if (session == null) {
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose where to save " + item.fileName());
        Path initial = session.defaultReceiveDirectory();
        if (initial != null && java.nio.file.Files.isDirectory(initial)) {
            chooser.setInitialDirectory(initial.toFile());
        }
        File target = chooser.showDialog(stage);
        if (target == null) {
            return;
        }
        if (session.acceptFileOffer(item.fileId(), target.toPath())) {
            chatItems.acceptLocally(item);
        } else {
            showConnectionStatus("Could not accept the file");
        }
    }

    private void onDeclineOffer(ChatItem item) {
        if (session == null) {
            return;
        }
        session.declineFileOffer(item.fileId(), "declined by user");
    }

    private void onOpen(ChatItem item) {
        Path path = item.localPath();
        if (path == null || !java.nio.file.Files.isRegularFile(path)) {
            showConnectionStatus("File is not available");
            return;
        }
        try {
            if (!java.awt.Desktop.isDesktopSupported()) {
                showConnectionStatus("Opening files is not supported here");
                return;
            }
            java.awt.Desktop.getDesktop().open(path.toFile());
        } catch (Exception e) {
            showConnectionStatus("Cannot open file: " + describe(e));
        }
    }

    private void onDisconnect() {
        if (session != null) {
            session.disconnect();
        }
        busy = false;
        startButton.setDisable(false);
        resetTransferUi();
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

    private void appendLog(String line) {
        transferLogList.getItems().add(LocalTime.now().format(TIME) + "  " + line);
        transferLogList.scrollTo(transferLogList.getItems().size() - 1);
    }

    private void showConnectionStatus(String status) {
        connectionStatusLabel.setText(status);
        sessionStatusLabel.setText(status);
    }

    private void resetTransferUi() {
        fileProgressBar.setProgress(0);
        filePercentLabel.setText("0%");
        fileInfoLabel.setText("No transfer yet");
        fileStatusLabel.setText("Idle");
    }

    private void showSessionPane(boolean show) {
        sessionPane.setVisible(show);
        sessionPane.setManaged(show);
        connectionPane.setVisible(!show);
        connectionPane.setManaged(!show);
    }

    @Override
    public void onStatusChanged(String status) {
        runOnFx(() -> {
            showConnectionStatus(status);
            if (status.startsWith("Listening")) {
                showSessionPane(false);
            } else if (status.startsWith("Connected")) {
                peerLabel.setText(peerSummary());
                showSessionPane(true);
                messageField.requestFocus();
            } else if (status.startsWith("Peer disconnected") || status.equals("Disconnected")) {
                peerLabel.setText("No peer");
                showSessionPane(false);
                busy = false;
                startButton.setDisable(false);
                resetTransferUi();
            } else if (status.equals("Connection failed")) {
                busy = false;
                startButton.setDisable(false);
                showSessionPane(false);
            }
        });
    }

    @Override
    public void onChatMessage(String sender, String message) {
        runOnFx(() -> appendChat(ChatItem.inboundText(sender, message, System.currentTimeMillis())));
    }

    @Override
    public void onFileOffered(int fileId, String direction, String fileName, long fileSize) {
        runOnFx(() -> {
            fileStatusLabel.setText("Incoming file: " + fileName);
            chatItems.onFileOffered(fileId, direction, fileName, fileSize);
        });
    }

    @Override
    public void onFileDeclined(int fileId, String direction, String fileName, String reason) {
        runOnFx(() -> {
            appendLog("#" + fileId + " " + direction + " " + fileName + " -> " + reason);
            chatItems.onFileDeclined(fileId, direction, fileName, reason);
        });
    }

    @Override
    public void onFileProgress(int fileId, String direction, String fileName, long bytesTransferred, long totalBytes) {
        double progress = totalBytes <= 0 ? 0 : (double) bytesTransferred / totalBytes;
        runOnFx(() -> {
            fileProgressBar.setProgress(Math.min(1, progress));
            filePercentLabel.setText(Math.round(progress * 100) + "%");
            fileInfoLabel.setText(direction + ": " + fileName + "\n"
                    + NetworkUtils.formatBytes(bytesTransferred) + " / " + NetworkUtils.formatBytes(totalBytes));
            fileStatusLabel.setText(direction.equals("Sending") ? "Transferring..." : "Receiving...");
            chatItems.onFileProgress(fileId, direction, fileName, bytesTransferred, totalBytes);
        });
    }

    @Override
    public void onFileEvent(int fileId, String direction, String fileName, String detail,
                            Path savedPath, boolean failed) {
        runOnFx(() -> {
            if (failed) {
                fileStatusLabel.setText("Transfer failed");
                appendLog("#" + fileId + " " + direction + " " + fileName + " -> FAILED: " + detail);
            } else {
                String location = savedPath == null ? "" : " -> " + savedPath;
                fileStatusLabel.setText("Transfer completed");
                appendLog("#" + fileId + " " + direction + " " + fileName + " completed" + location);
            }
            chatItems.onFileEvent(fileId, direction, fileName, detail, savedPath, failed);
        });
    }

    @Override
    public void onDisconnected(String reason) {
        runOnFx(() -> {
            peerLabel.setText("No peer");
            showSessionPane(false);
            busy = false;
            startButton.setDisable(false);
            appendLog("Disconnected: " + reason);
        });
    }

    private String remoteDescription() {
        return session == null ? "unknown" : session.remoteDescription();
    }

    private String peerSummary() {
        if (session == null) {
            return "No peer";
        }
        int count = session.peerCount();
        if (count <= 1) {
            return "Peer: " + remoteDescription();
        }
        return count + " peers: " + remoteDescription();
    }

    private static void runOnFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    private static Path receivedDirectory() {
        return Paths.get("received");
    }

    private static String defaultDisplayName() {
        String user = System.getProperty("user.name");
        return user == null || user.isBlank() ? "Peer" : user;
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
            throw new IllegalArgumentException("Peer IP is required");
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
