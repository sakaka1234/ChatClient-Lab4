package org.example.p2pchat.ui;

import org.example.p2pchat.protocol.ChatScope;

import java.util.List;

/** One line of the chat transcript: the text, who said it, and how it was addressed. */
public final class ChatItem {

    private final boolean outbound;
    private final long timestamp;
    private final String sender;
    private final String text;
    private final ChatScope scope;
    private final List<String> audience;

    private ChatItem(boolean outbound, long timestamp, String sender, String text,
                     ChatScope scope, List<String> audience) {
        this.outbound = outbound;
        this.timestamp = timestamp;
        this.sender = sender;
        this.text = text;
        this.scope = scope == null ? ChatScope.BROADCAST : scope;
        this.audience = audience == null ? List.of() : List.copyOf(audience);
    }

    public static ChatItem outboundText(String text, long timestamp) {
        return outboundText(text, ChatScope.BROADCAST, List.of(), timestamp);
    }

    /** @param audience display names of the clients this message was addressed to */
    public static ChatItem outboundText(String text, ChatScope scope, List<String> audience, long timestamp) {
        return textItem(null, text, true, timestamp, scope, audience);
    }

    public static ChatItem inboundText(String text, long timestamp) {
        return textItem(null, text, false, timestamp, ChatScope.BROADCAST, List.of());
    }

    public static ChatItem inboundText(String sender, String text, long timestamp) {
        return textItem(sender, text, false, timestamp, ChatScope.BROADCAST, List.of());
    }

    /** @param audience display names of the clients the sender addressed, this instance included */
    public static ChatItem inboundText(String sender, String text, ChatScope scope, List<String> audience,
                                       long timestamp) {
        return textItem(sender, text, false, timestamp, scope, audience);
    }

    private static ChatItem textItem(String sender, String text, boolean outbound, long timestamp,
                                     ChatScope scope, List<String> audience) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Chat text must not be blank");
        }
        return new ChatItem(outbound, timestamp, sender, text.strip(), scope, audience);
    }

    public boolean outbound() {
        return outbound;
    }

    public long timestamp() {
        return timestamp;
    }

    public String sender() {
        return sender;
    }

    public String text() {
        return text;
    }

    /** How this message was addressed. */
    public ChatScope scope() {
        return scope;
    }

    /** Display names of the clients this message was addressed to; empty for a broadcast. */
    public List<String> audience() {
        return audience;
    }

    /** True when the message was addressed to specific clients rather than to everyone. */
    public boolean directed() {
        return !scope.isBroadcast();
    }

    @Override
    public String toString() {
        String who = outbound ? "You" : (sender == null || sender.isBlank() ? "Client" : sender);
        return who + ": " + text;
    }
}
