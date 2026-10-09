package org.example.p2pchat.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatItemTrackerTest {

    @Test
    void keepsEveryMessageInArrivalOrder() {
        ChatItemTracker tracker = new ChatItemTracker();
        ChatItem first = ChatItem.outboundText("hello", 1L);
        ChatItem second = ChatItem.inboundText("An", "hi back", 2L);
        ChatItem third = ChatItem.inboundText("An", "you there?", 3L);

        tracker.add(first);
        tracker.add(second);
        tracker.add(third);

        assertEquals(List.of(first, second, third), tracker.items());
        assertEquals(1, tracker.indexOf(second));
    }

    @Test
    void textIsNormalisedAndTheSenderIsKept() {
        ChatItemTracker tracker = new ChatItemTracker();

        tracker.add(ChatItem.inboundText("An", "  chào bạn  ", 7L));

        ChatItem item = tracker.items().get(0);
        assertSame("An", item.sender());
        assertEquals("chào bạn", item.text());
        assertEquals(7L, item.timestamp());
    }

    @Test
    void changeListenerFiresOncePerAdd() {
        ChatItemTracker tracker = new ChatItemTracker();
        int[] count = {0};
        tracker.addChangeListener(() -> count[0]++);

        tracker.add(ChatItem.outboundText("one", 1L));
        tracker.add(ChatItem.outboundText("two", 2L));
        tracker.add(ChatItem.outboundText("three", 3L));

        assertEquals(3, count[0]);
    }

    @Test
    void clearEmptiesTheTranscriptAndNotifies() {
        ChatItemTracker tracker = new ChatItemTracker();
        tracker.add(ChatItem.outboundText("hello", 1L));
        int[] count = {0};
        tracker.addChangeListener(() -> count[0]++);

        tracker.clear();

        assertTrue(tracker.items().isEmpty());
        assertEquals(1, count[0], "clearing the list must redraw it");
    }

    @Test
    void aBrokenListenerDoesNotStopTheOthers() {
        ChatItemTracker tracker = new ChatItemTracker();
        boolean[] reached = {false};
        tracker.addChangeListener(() -> {
            throw new IllegalStateException("view is broken");
        });
        tracker.addChangeListener(() -> reached[0] = true);

        tracker.add(ChatItem.outboundText("hello", 1L));

        assertTrue(reached[0], "one bad listener must not silence the rest");
    }
}
