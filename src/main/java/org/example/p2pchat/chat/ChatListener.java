package org.example.p2pchat.chat;

import org.example.p2pchat.protocol.ChatScope;

import java.util.List;

public interface ChatListener {

    /** A chat message that arrived without addressing information; treat it as a broadcast. */
    void onChatMessage(String sender, String message);

    /**
     * A chat message together with its addressing block.
     *
     * <p>The default implementation forwards to the two-argument form, so a listener that does not
     * care about addressing keeps working and sees every message as a broadcast.
     *
     * @param targets the client ids the sender addressed; empty for a broadcast
     */
    default void onChatMessage(String sender, String message, ChatScope scope, List<Integer> targets) {
        onChatMessage(sender, message);
    }

    void onChatError(String reason);
}
