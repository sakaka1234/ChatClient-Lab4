package org.example.p2pchat.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.example.p2pchat.protocol.ChatScope;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class ChatBubbleFactory {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** Builds one chat bubble row: sender label, addressing label, text, timestamp. */
    public Node create(ChatItem item) {
        VBox bubble = new VBox(6);
        bubble.getStyleClass().add("bubble");
        bubble.getStyleClass().add(item.outbound() ? "bubble-out" : "bubble-in");
        bubble.setMaxWidth(Region.USE_PREF_SIZE);

        if (!item.outbound() && item.sender() != null && !item.sender().isBlank()) {
            Label sender = new Label(item.sender());
            sender.getStyleClass().add("bubble-sender");
            bubble.getChildren().add(sender);
        }
        String audience = audienceText(item);
        if (audience != null) {
            Label addressing = new Label(audience);
            addressing.getStyleClass().add("bubble-audience");
            addressing.setWrapText(true);
            addressing.setMaxWidth(420);
            bubble.getChildren().add(addressing);
        }
        Label text = new Label(item.text());
        text.getStyleClass().add("bubble-text");
        text.setWrapText(true);
        text.setMaxWidth(420);
        bubble.getChildren().add(text);

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

    private Node metaRow(ChatItem item) {
        Label time = new Label(TIME.format(Instant.ofEpochMilli(item.timestamp())
                .atZone(ZoneId.systemDefault())));
        time.getStyleClass().add("bubble-time");

        HBox row = new HBox(8, time);
        row.getStyleClass().add("bubble-meta");
        row.setAlignment(item.outbound() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        return row;
    }

    /**
     * How to label a message's addressing: "→ An, Binh" on the side that sent it, "private" or
     * "group · An, Binh" on the side that received it. {@code null} means a plain broadcast, which
     * reads exactly as it did before addressing existed.
     */
    static String audienceText(ChatItem item) {
        if (!item.directed()) {
            return null;
        }
        String names = String.join(", ", item.audience());
        if (item.outbound()) {
            return names.isEmpty() ? "→ a client that left" : "→ " + names;
        }
        return item.scope() == ChatScope.UNICAST ? "private" : "group · " + names;
    }
}
