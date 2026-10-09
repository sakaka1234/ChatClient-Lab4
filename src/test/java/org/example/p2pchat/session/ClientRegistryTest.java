package org.example.p2pchat.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientRegistryTest {

    @Test
    void tracksClientsInJoinOrder() {
        ClientRegistry<String> hub = new ClientRegistry<>();

        hub.add("a", "An");
        hub.add("b", "Binh");

        assertEquals(List.of("a", "b"), hub.connections());
        assertEquals(2, hub.size());
        assertEquals("An", hub.nameOf("a"));
        assertEquals("Binh", hub.nameOf("b"));
    }

    @Test
    void addingTheSameClientTwiceKeepsOneEntryAndUpdatesTheName() {
        ClientRegistry<String> hub = new ClientRegistry<>();

        hub.add("a", "An");
        hub.add("a", "An updated");

        assertEquals(List.of("a"), hub.connections());
        assertEquals("An updated", hub.nameOf("a"));
    }

    @Test
    void connectionsExceptSkipTheSource() {
        ClientRegistry<String> hub = new ClientRegistry<>();
        hub.add("a", "An");
        hub.add("b", "Binh");
        hub.add("c", "Cuong");

        assertEquals(List.of("a", "c"), hub.connectionsExcept("b"));
    }

    @Test
    void primaryIsTheFirstJoinedClientAndMovesOnRemoval() {
        ClientRegistry<String> hub = new ClientRegistry<>();
        hub.add("a", "An");
        hub.add("b", "Binh");

        assertEquals("a", hub.primary());

        hub.remove("a");

        assertEquals("b", hub.primary());
        assertEquals(1, hub.size());
    }

    @Test
    void primaryIsNullWhenEmpty() {
        assertNull(new ClientRegistry<String>().primary());
    }

    @Test
    void removesOnlyTheGivenConnection() {
        ClientRegistry<String> hub = new ClientRegistry<>();
        hub.add("a", "An");
        hub.add("b", "Binh");

        hub.remove("a");

        assertFalse(hub.contains("a"));
        assertTrue(hub.contains("b"));
        assertEquals(List.of("b"), hub.connections());
    }

    @Test
    void blankNameFallsBackToDefault() {
        ClientRegistry<String> hub = new ClientRegistry<>();

        hub.add("a", "   ");

        assertEquals(ClientRegistry.DEFAULT_NAME, hub.nameOf("a"));
    }

    @Test
    void nameOfUnknownConnectionIsDefault() {
        assertEquals(ClientRegistry.DEFAULT_NAME, new ClientRegistry<String>().nameOf("ghost"));
    }

    @Test
    void rejectsNullConnection() {
        assertThrows(NullPointerException.class, () -> new ClientRegistry<String>().add(null, "An"));
    }

    @Test
    void addIfAbsentKeepsAnAlreadyLearnedName() {
        ClientRegistry<String> hub = new ClientRegistry<>();
        hub.add("a", "An");

        hub.addIfAbsent("a", ClientRegistry.DEFAULT_NAME);

        assertEquals("An", hub.nameOf("a"));
        assertEquals(1, hub.size());
    }

    @Test
    void addIfAbsentRegistersANewClientWithTheDefaultName() {
        ClientRegistry<String> hub = new ClientRegistry<>();

        hub.addIfAbsent("a", ClientRegistry.DEFAULT_NAME);

        assertEquals(List.of("a"), hub.connections());
        assertEquals(ClientRegistry.DEFAULT_NAME, hub.nameOf("a"));
    }
}
