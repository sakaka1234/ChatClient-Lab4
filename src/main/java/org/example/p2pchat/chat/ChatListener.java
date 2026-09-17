package org.example.p2pchat.chat;

public interface ChatListener {

    void onChatMessage(String message);

    void onChatError(String reason);
}
