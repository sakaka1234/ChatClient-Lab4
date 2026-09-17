package org.example.p2pchat.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.example.p2pchat.util.NetworkUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

public final class ChatBubbleFactory {

    public static final double IMAGE_WIDTH = 260;
    public static final double IMAGE_HEIGHT = 180;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final Consumer<ChatItem> onSaveAs;
    private final Consumer<ChatItem> onOpen;
    private final Consumer<ChatItem> onAccept;
    private final Consumer<ChatItem> onDecline;

    public ChatBubbleFactory(Consumer<ChatItem> onSaveAs, Consumer<ChatItem> onOpen,
                             Consumer<ChatItem> onAccept, Consumer<ChatItem> onDecline) {
        this.onSaveAs = onSaveAs;
        this.onOpen = onOpen;
        this.onAccept = onAccept;
        this.onDecline = onDecline;
    }

    public Node create(ChatItem item) {
        VBox bubble = new VBox(6);
        bubble.getStyleClass().add("bubble");
        bubble.getStyleClass().add(item.outbound() ? "bubble-out" : "bubble-in");
        bubble.setMaxWidth(Region.USE_PREF_SIZE);

        if (item.kind() == ChatItem.Kind.TEXT) {
            Label text = new Label(item.text());
            text.getStyleClass().add("bubble-text");
            text.setWrapText(true);
            text.setMaxWidth(420);
            bubble.getChildren().add(text);
        } else {
            bubble.getChildren().add(fileContent(item));
        }

        bubble.getChildren().add(metaRow(item));

        HBox row = new HBox(bubble);
        row.getStyleClass().add("bubble-row");
        row.setAlignment(item.outbound() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        if (item.outbound()) {
            row.getChildren().add(0, spacer);
        } else {
            row.getChildren().add(spacer);
        }
        return row;
    }

    private Node fileContent(ChatItem item) {
        VBox content = new VBox(8);

        if (item.isImage() && previewAvailable(item)) {
            content.getChildren().add(imagePreview(item));
        }

        HBox card = new HBox(10);
        card.getStyleClass().add("file-card");

        Label badge = new Label(FileTypes.badge(item.fileName()));
        badge.getStyleClass().add("file-badge");
        badge.setMinWidth(46);
        badge.setAlignment(Pos.CENTER);

        VBox info = new VBox(2);
        Label name = new Label(item.fileName());
        name.getStyleClass().add("file-name");
        name.setWrapText(true);
        Label size = new Label(NetworkUtils.formatBytes(item.totalBytes()));
        size.getStyleClass().add("file-size");
        info.getChildren().addAll(name, size);

        HBox.setHgrow(info, Priority.ALWAYS);
        card.getChildren().addAll(badge, info);
        content.getChildren().add(card);

        if (item.isAwaitingDecision()) {
            content.getChildren().add(decisionRow(item));
        }

        if (item.status() == ChatItem.Status.IN_PROGRESS) {
            ProgressBar bar = new ProgressBar(item.progress());
            bar.getStyleClass().add("bubble-progress");
            bar.setMaxWidth(Double.MAX_VALUE);
            content.getChildren().add(bar);
        }

        if ((item.status() == ChatItem.Status.FAILED || item.status() == ChatItem.Status.DECLINED)
                && item.detail() != null) {
            Label failure = new Label(item.detail());
            failure.getStyleClass().add("file-failure");
            failure.setWrapText(true);
            failure.setMaxWidth(320);
            content.getChildren().add(failure);
        }

        if (item.canOpen()) {
            content.getChildren().add(actionRow(item));
        }
        return content;
    }

    private Node decisionRow(ChatItem item) {
        if (item.outbound()) {
            Label waiting = new Label("waiting for peer...");
            waiting.getStyleClass().add("file-size");
            return waiting;
        }
        Button accept = new Button("Accept");
        accept.getStyleClass().add("mini-button-primary");
        accept.setOnAction(event -> onAccept.accept(item));

        Button decline = new Button("Decline");
        decline.getStyleClass().add("mini-button");
        decline.setOnAction(event -> onDecline.accept(item));

        HBox row = new HBox(8, accept, decline);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Node actionRow(ChatItem item) {
        Button open = new Button("Open");
        open.getStyleClass().add("mini-button");
        open.setOnAction(event -> onOpen.accept(item));

        Button saveAs = new Button("Save as...");
        saveAs.getStyleClass().add("mini-button-primary");
        saveAs.setOnAction(event -> onSaveAs.accept(item));

        HBox row = new HBox(8, open, saveAs);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Node imagePreview(ChatItem item) {
        ImageView view = new ImageView();
        view.setFitWidth(IMAGE_WIDTH);
        view.setFitHeight(IMAGE_HEIGHT);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        view.getStyleClass().add("chat-image");

        Path path = item.localPath();
        if (path != null && Files.isRegularFile(path)) {
            Image image = new Image(path.toUri().toString(), IMAGE_WIDTH, IMAGE_HEIGHT, true, true, true);
            view.setImage(image);
            view.setOnMouseClicked(event -> onOpen.accept(item));
        } else {
            Label placeholder = new Label("Preview unavailable");
            placeholder.getStyleClass().add("file-failure");
            return placeholder;
        }
        return view;
    }

    private boolean previewAvailable(ChatItem item) {
        Path path = item.localPath();
        return path != null && Files.isRegularFile(path)
                && FileTypes.canPreviewImage(item.fileName(), item.totalBytes());
    }

    private Node metaRow(ChatItem item) {
        Label time = new Label(TIME.format(Instant.ofEpochMilli(item.timestamp())
                .atZone(ZoneId.systemDefault())));
        time.getStyleClass().add("bubble-time");

        HBox row = new HBox(8, time);
        row.getStyleClass().add("bubble-meta");
        row.setAlignment(item.outbound() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

        String status = statusText(item);
        if (status != null) {
            Label label = new Label(status);
            boolean bad = item.status() == ChatItem.Status.FAILED || item.status() == ChatItem.Status.DECLINED;
            label.getStyleClass().add(bad ? "bubble-status-failed" : "bubble-status");
            if (item.outbound()) {
                row.getChildren().add(0, label);
            } else {
                row.getChildren().add(label);
            }
        }
        return row;
    }

    private static String statusText(ChatItem item) {
        return switch (item.status()) {
            case WAITING_FOR_DECISION -> null;
            case IN_PROGRESS -> item.outbound() ? "sending..." : "receiving...";
            case FAILED -> "failed";
            case DECLINED -> "declined";
            case COMPLETED -> item.kind() == ChatItem.Kind.FILE ? "done" : null;
        };
    }
}
