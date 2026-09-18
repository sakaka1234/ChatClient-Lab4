package org.example.p2pchat.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerHubTest {

    @Test
    void tracksPeersInJoinOrder() {
        PeerHub<String> hub = new PeerHub<>();

        hub.add("a", "An");
        hub.add("b", "Binh");

        assertEquals(List.of("a", "b"), hub.peers());
        assertEquals(2, hub.size());
        assertEquals("An", hub.nameOf("a"));
        assertEquals("Binh", hub.nameOf("b"));
    }

    @Test
    void addingTheSamePeerTwiceKeepsOneEntryAndUpdatesTheName() {
        PeerHub<String> hub = new PeerHub<>();

        hub.add("a", "An");
        hub.add("a", "An updated");

        assertEquals(List.of("a"), hub.peers());
        assertEquals("An updated", hub.nameOf("a"));
    }

    @Test
    void peersExceptSkipsTheSource() {
        PeerHub<String> hub = new PeerHub<>();
        hub.add("a", "An");
        hub.add("b", "Binh");
        hub.add("c", "Cuong");

        assertEquals(List.of("a", "c"), hub.peersExcept("b"));
    }

    @Test
    void primaryIsTheFirstJoinedPeerAndMovesOnRemoval() {
        PeerHub<String> hub = new PeerHub<>();
        hub.add("a", "An");
        hub.add("b", "Binh");

        assertEquals("a", hub.primary());

        hub.remove("a");

        assertEquals("b", hub.primary());
        assertEquals(1, hub.size());
    }

    @Test
    void primaryIsNullWhenEmpty() {
        assertNull(new PeerHub<String>().primary());
    }

    @Test
    void removesOnlyTheGivenPeer() {
        PeerHub<String> hub = new PeerHub<>();
        hub.add("a", "An");
        hub.add("b", "Binh");

        hub.remove("a");

        assertFalse(hub.contains("a"));
        assertTrue(hub.contains("b"));
        assertEquals(List.of("b"), hub.peers());
    }

    @Test
    void blankNameFallsBackToDefault() {
        PeerHub<String> hub = new PeerHub<>();

        hub.add("a", "   ");

        assertEquals(PeerHub.DEFAULT_NAME, hub.nameOf("a"));
    }

    @Test
    void nameOfUnknownPeerIsDefault() {
        assertEquals(PeerHub.DEFAULT_NAME, new PeerHub<String>().nameOf("ghost"));
    }

    @Test
    void rejectsNullPeer() {
        assertThrows(NullPointerException.class, () -> new PeerHub<String>().add(null, "An"));
    }

    @Test
    void addIfAbsentKeepsAnAlreadyLearnedName() {
        PeerHub<String> hub = new PeerHub<>();
        hub.add("a", "An");

        hub.addIfAbsent("a", PeerHub.DEFAULT_NAME);

        assertEquals("An", hub.nameOf("a"));
        assertEquals(1, hub.size());
    }

    @Test
    void addIfAbsentRegistersANewPeerWithTheDefaultName() {
        PeerHub<String> hub = new PeerHub<>();

        hub.addIfAbsent("a", PeerHub.DEFAULT_NAME);

        assertEquals(List.of("a"), hub.peers());
        assertEquals(PeerHub.DEFAULT_NAME, hub.nameOf("a"));
    }
}
