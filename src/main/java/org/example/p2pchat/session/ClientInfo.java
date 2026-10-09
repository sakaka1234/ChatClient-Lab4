package org.example.p2pchat.session;

import java.util.Objects;

/**
 * One line of the session's client directory: the address used by unicast and multicast chat, plus the
 * display name shown next to messages.
 *
 * <p>Ids are assigned by the server in join order and are unique inside one session only; the first
 * client to join is {@link ChatSession#FIRST_CLIENT_ID}. A client learns the whole directory — and its
 * own id — from the {@code ROSTER} packet. The server itself is never listed: it relays but does not
 * chat, so it is not an address.
 *
 * @param id   client id, {@code 1..65535}
 * @param name display name as the client announced it
 */
public record ClientInfo(int id, String name) {

    public ClientInfo {
        if (id < ChatSession.FIRST_CLIENT_ID || id > 0xFFFF) {
            throw new IllegalArgumentException("client id out of range: " + id);
        }
        Objects.requireNonNull(name, "name");
    }
}
