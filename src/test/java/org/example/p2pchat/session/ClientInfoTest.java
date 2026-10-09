package org.example.p2pchat.session;

import org.example.p2pchat.protocol.Packet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientInfoTest {

    @Test
    void keepsIdAndName() {
        ClientInfo client = new ClientInfo(3, "An");

        assertEquals(3, client.id());
        assertEquals("An", client.name());
    }

    @Test
    void theFirstClientIdIsTheSmallestAddressAClientCanHave() {
        assertEquals(1, new ClientInfo(ChatSession.FIRST_CLIENT_ID, "An").id());
        assertThrows(IllegalArgumentException.class, () -> new ClientInfo(0, "An"));
        assertThrows(IllegalArgumentException.class, () -> new ClientInfo(-1, "An"));
    }

    @Test
    void rejectsAnIdWiderThanTheWireFormat() {
        assertEquals(Packet.MAX_CLIENT_ID, new ClientInfo(Packet.MAX_CLIENT_ID, "An").id());
        assertThrows(IllegalArgumentException.class, () -> new ClientInfo(Packet.MAX_CLIENT_ID + 1, "An"));
    }

    @Test
    void rejectsANullName() {
        assertThrows(NullPointerException.class, () -> new ClientInfo(2, null));
    }

    @Test
    void equalityIsByIdAndName() {
        assertEquals(new ClientInfo(2, "An"), new ClientInfo(2, "An"));
        assertEquals(new ClientInfo(2, "An").hashCode(), new ClientInfo(2, "An").hashCode());
    }
}
