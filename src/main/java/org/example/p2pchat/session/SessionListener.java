package org.example.p2pchat.session;

import org.example.p2pchat.protocol.ChatScope;

import java.util.List;

public interface SessionListener {

    void onStatusChanged(String status);

    /**
     * A chat message, without saying how it was addressed. This is the form listeners implemented
     * before addressing existed, and it is still called for every message — see the default below.
     */
    void onChatMessage(String sender, String message);

    /**
     * A chat message together with how it was addressed.
     *
     * <p>Defaults to the two-argument form, so a listener written before addressing existed keeps
     * compiling and simply sees every message as a broadcast.
     *
     * @param scope    {@link ChatScope#UNICAST}, {@link ChatScope#MULTICAST} or
     *                 {@link ChatScope#BROADCAST}
     * @param audience display names of the clients the sender addressed, this instance included
     *                 ("group · An, Binh" reads on An's screen too); empty for a broadcast
     */
    default void onChatMessage(String sender, String message, ChatScope scope, List<String> audience) {
        onChatMessage(sender, message);
    }

    /**
     * The client directory changed — somebody joined, left, or renamed themselves.
     *
     * @param selfId this instance's own id ({@code 0} on the server, and on a client until its first
     *               roster arrives)
     * @param clients every client of the session, in join order; on a client this instance is one of them
     */
    default void onRosterChanged(int selfId, List<ClientInfo> clients) {
    }

    /**
     * One line of the server's relay log: a client joined or left, or a message was copied to the
     * addresses it named. Only a server produces these; the default ignores them.
     *
     * <p>Sent on the session's own threads, so an implementation must not touch the scene graph
     * directly.
     */
    default void onServerLog(String message) {
    }

    void onDisconnected(String reason);
}
