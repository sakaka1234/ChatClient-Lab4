package org.example.p2pchat.ui;

import org.example.p2pchat.protocol.ChatScope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatItemTest {

    @Test
    void outboundTextKnowsItIsOutbound() {
        ChatItem item = ChatItem.outboundText("hello", 1_000L);

        assertTrue(item.outbound());
        assertEquals("hello", item.text());
        assertEquals(1_000L, item.timestamp());
    }

    @Test
    void inboundTextKnowsItIsNot() {
        ChatItem item = ChatItem.inboundText("hi", 1_000L);

        assertFalse(item.outbound());
    }

    @Test
    void inboundTextRemembersTheSenderName() {
        ChatItem item = ChatItem.inboundText("An", "hi", 1_000L);

        assertFalse(item.outbound());
        assertEquals("An", item.sender());
        assertEquals("hi", item.text());
    }

    @Test
    void outboundTextHasNoSenderName() {
        assertNull(ChatItem.outboundText("hello", 0L).sender());
    }

    @Test
    void blankTextIsRejectedOnBothSides() {
        assertThrows(IllegalArgumentException.class, () -> ChatItem.outboundText("   ", 0L));
        assertThrows(IllegalArgumentException.class, () -> ChatItem.inboundText(null, 0L));
        assertThrows(IllegalArgumentException.class, () -> ChatItem.inboundText("An", "  ", 0L));
    }

    @Test
    void textIsTrimmedBeforeItIsStored() {
        assertEquals("hello", ChatItem.outboundText("  hello \n", 0L).text());
    }

    @Test
    void toStringReadsLikeTheTranscript() {
        assertEquals("You: hi", ChatItem.outboundText("hi", 0L).toString());
        assertEquals("An: hi", ChatItem.inboundText("An", "hi", 0L).toString());
        assertEquals("Client: hi", ChatItem.inboundText("hi", 0L).toString());
    }

    // ------------------------------------------------------------- addressing

    @Test
    void textWithoutAddressingIsABroadcast() {
        ChatItem item = ChatItem.outboundText("hi", 0L);

        assertEquals(ChatScope.BROADCAST, item.scope());
        assertEquals(List.of(), item.audience());
        assertFalse(item.directed());
    }

    @Test
    void outboundTextRemembersScopeAndAudience() {
        ChatItem item = ChatItem.outboundText("chi rieng", ChatScope.UNICAST, List.of("An"), 0L);

        assertTrue(item.outbound());
        assertEquals(ChatScope.UNICAST, item.scope());
        assertEquals(List.of("An"), item.audience());
        assertTrue(item.directed());
    }

    @Test
    void inboundTextRemembersScopeAndAudience() {
        ChatItem item = ChatItem.inboundText("An", "hop nhom", ChatScope.MULTICAST,
                List.of("Binh", "Cuong"), 0L);

        assertFalse(item.outbound());
        assertEquals("An", item.sender());
        assertEquals(ChatScope.MULTICAST, item.scope());
        assertEquals(List.of("Binh", "Cuong"), item.audience());
        assertTrue(item.directed());
    }

    @Test
    void audienceIsCopiedAndCannotBeMutatedFromOutside() {
        List<String> audience = new ArrayList<>(List.of("An"));
        ChatItem item = ChatItem.outboundText("hi", ChatScope.UNICAST, audience, 0L);

        audience.add("Binh");

        assertEquals(List.of("An"), item.audience());
        assertThrows(UnsupportedOperationException.class, () -> item.audience().add("Cuong"));
    }

    @Test
    void aNullScopeFallsBackToBroadcast() {
        ChatItem item = ChatItem.inboundText("An", "hi", null, null, 0L);

        assertEquals(ChatScope.BROADCAST, item.scope());
        assertEquals(List.of(), item.audience());
        assertFalse(item.directed());
    }

    @Test
    void aDirectedMessageWithNoNamesStillCountsAsDirected() {
        ChatItem item = ChatItem.outboundText("hi", ChatScope.UNICAST, null, 0L);

        assertTrue(item.directed(), "a client that left must not silently turn the bubble into a broadcast");
        assertEquals(List.of(), item.audience());
    }
}
