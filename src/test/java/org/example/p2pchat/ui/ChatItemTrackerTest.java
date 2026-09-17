package org.example.p2pchat.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatItemTrackerTest {

    @Test
    void outboundFileAppearsImmediately(@TempDir Path dir) {
        ChatItemTracker tracker = new ChatItemTracker();
        ChatItem item = ChatItem.outboundFile(4, "a.bin", 100, dir.resolve("a.bin"), 1_000L);

        tracker.trackOutbound(4, item);

        assertEquals(List.of(item), tracker.items());
        assertEquals(0.0, item.progress(), 0.0001);
    }

    @Test
    void inboundProgressCreatesBubbleOnce(@TempDir Path dir) {
        ChatItemTracker tracker = new ChatItemTracker();

        tracker.onFileProgress(4, "Receiving", "photo.png", 0, 1000);
        tracker.onFileProgress(4, "Receiving", "photo.png", 250, 1000);
        tracker.onFileProgress(4, "Receiving", "photo.png", 500, 1000);

        assertEquals(1, tracker.items().size());
        ChatItem item = tracker.items().get(0);
        assertFalse(item.outbound());
        assertEquals(500, item.transferredBytes());
        assertEquals(0.5, item.progress(), 0.0001);
    }

    @Test
    void outboundProgressDoesNotDuplicateBubble(@TempDir Path dir) {
        ChatItemTracker tracker = new ChatItemTracker();
        ChatItem item = ChatItem.outboundFile(4, "a.bin", 1000, dir.resolve("a.bin"), 1L);
        tracker.trackOutbound(4, item);

        tracker.onFileProgress(4, "Sending", "a.bin", 100, 1000);
        tracker.onFileProgress(4, "Sending", "a.bin", 900, 1000);

        assertEquals(1, tracker.items().size());
        assertSame(item, tracker.items().get(0));
        assertEquals(900, item.transferredBytes());
    }

    @Test
    void inboundProgressWithoutStartStillCreatesBubble() {
        ChatItemTracker tracker = new ChatItemTracker();

        tracker.onFileProgress(7, "Receiving", "late.bin", 0, 500);

        assertEquals(1, tracker.items().size());
        assertEquals(7, tracker.items().get(0).fileId());
    }

    @Test
    void completionMarksBubbleCompletedWithPath(@TempDir Path dir) {
        ChatItemTracker tracker = new ChatItemTracker();
        Path saved = dir.resolve("received/r.txt");

        tracker.onFileProgress(3, "Receiving", "r.txt", 0, 10);
        tracker.onFileEvent(3, "Receiving", "r.txt", "Completed", saved, false);

        ChatItem item = tracker.items().get(0);
        assertEquals(ChatItem.Status.COMPLETED, item.status());
        assertEquals(saved, item.localPath());
        assertTrue(item.canOpen());
    }

    @Test
    void failureMarksBubbleFailedAndKeepsDetail(@TempDir Path dir) {
        ChatItemTracker tracker = new ChatItemTracker();

        tracker.onFileProgress(3, "Receiving", "r.txt", 0, 10);
        tracker.onFileEvent(3, "Receiving", "r.txt", "size mismatch", null, true);

        ChatItem item = tracker.items().get(0);
        assertEquals(ChatItem.Status.FAILED, item.status());
        assertEquals("size mismatch", item.detail());
        assertFalse(item.canOpen());
    }

    @Test
    void eventWithoutPriorProgressCreatesBubble() {
        ChatItemTracker tracker = new ChatItemTracker();

        tracker.onFileEvent(9, "Receiving", "orphan.bin", "Completed", Path.of("received/orphan.bin"), false);

        assertEquals(1, tracker.items().size());
        ChatItem item = tracker.items().get(0);
        assertEquals("orphan.bin", item.fileName());
        assertEquals(ChatItem.Status.COMPLETED, item.status());
    }

    @Test
    void outboundFailureWithoutTrackedBubbleIsIgnored() {
        ChatItemTracker tracker = new ChatItemTracker();

        tracker.onFileEvent(11, "Sending", "never-tracked.bin", "Not connected", null, true);

        assertTrue(tracker.items().isEmpty());
    }

    @Test
    void concurrentTransfersKeepSeparateBubbles(@TempDir Path dir) {
        ChatItemTracker tracker = new ChatItemTracker();
        ChatItem first = ChatItem.outboundFile(1, "one.bin", 100, dir.resolve("one.bin"), 1L);
        ChatItem second = ChatItem.outboundFile(2, "two.bin", 200, dir.resolve("two.bin"), 2L);
        tracker.trackOutbound(1, first);
        tracker.trackOutbound(2, second);

        tracker.onFileProgress(2, "Sending", "two.bin", 100, 200);
        tracker.onFileEvent(2, "Sending", "two.bin", "Completed", null, false);
        tracker.onFileProgress(1, "Sending", "one.bin", 50, 100);

        assertEquals(2, tracker.items().size());
        assertEquals(ChatItem.Status.IN_PROGRESS, first.status());
        assertEquals(50, first.transferredBytes());
        assertEquals(ChatItem.Status.COMPLETED, second.status());
        assertEquals(dir.resolve("one.bin"), first.localPath());
        assertFalse(first.canOpen());
    }

    @Test
    void textMessagesAndFilesShareOneOrderedStream() {
        ChatItemTracker tracker = new ChatItemTracker();

        tracker.add(ChatItem.outboundText("hello", 1L));
        tracker.onFileProgress(1, "Receiving", "pic.png", 0, 10);
        tracker.add(ChatItem.inboundText("hi back", 2L));
        tracker.onFileEvent(1, "Receiving", "pic.png", "Completed", Path.of("received/pic.png"), false);

        List<ChatItem> items = tracker.items();
        assertEquals(3, items.size());
        assertEquals("hello", items.get(0).text());
        assertEquals("pic.png", items.get(1).fileName());
        assertEquals("hi back", items.get(2).text());
    }

    @Test
    void changeListenerFiresForAddsAndUpdates() {
        ChatItemTracker tracker = new ChatItemTracker();
        int[] count = {0};
        tracker.addChangeListener(() -> count[0]++);

        tracker.add(ChatItem.outboundText("hello", 1L));
        tracker.onFileProgress(1, "Receiving", "a.bin", 0, 10);
        tracker.onFileProgress(1, "Receiving", "a.bin", 5, 10);

        assertTrue(count[0] >= 3, "expected at least 3 change events, got " + count[0]);
    }

    @Test
    void bothPeersNumberFromOneSoInboundMustNotCollideWithOutbound(@TempDir Path dir) {
        ChatItemTracker tracker = new ChatItemTracker();

        ChatItem mine = ChatItem.outboundFile(1, "my-photo.png", 500, dir.resolve("my-photo.png"), 1L);
        tracker.trackOutbound(1, mine);

        tracker.onFileOffered(1, "Receiving", "their-photo.png", 900);
        assertEquals(2, tracker.items().size(), "the inbound offer must get its own bubble");

        ChatItem received = tracker.items().get(1);
        tracker.acceptLocally(received);
        tracker.onFileProgress(1, "Receiving", "their-photo.png", 900, 900);
        tracker.onFileEvent(1, "Receiving", "their-photo.png", "Completed",
                dir.resolve("their-photo.png"), false);

        assertEquals(ChatItem.Status.COMPLETED, received.status());
        assertEquals(dir.resolve("their-photo.png"), received.localPath());
        assertTrue(received.canOpen());
        assertEquals("their-photo.png", received.fileName());

        assertEquals(ChatItem.Status.WAITING_FOR_DECISION, mine.status(),
                "the outbound bubble must be untouched by the inbound transfer");
        assertEquals("my-photo.png", mine.fileName());
    }

    @Test
    void inboundDeclineDoesNotTouchOutboundWithSameId(@TempDir Path dir) {
        ChatItemTracker tracker = new ChatItemTracker();
        ChatItem mine = ChatItem.outboundFile(3, "mine.bin", 10, dir.resolve("mine.bin"), 1L);
        tracker.trackOutbound(3, mine);

        tracker.onFileOffered(3, "Receiving", "theirs.bin", 20);
        ChatItem received = tracker.items().get(1);
        tracker.onFileDeclined(3, "Receiving", "theirs.bin", "declined by user");

        assertEquals(ChatItem.Status.DECLINED, received.status());
        assertEquals(ChatItem.Status.WAITING_FOR_DECISION, mine.status(),
                "the outbound bubble must be untouched");
    }

    @Test
    void clearForgetsAllItems() {
        ChatItemTracker tracker = new ChatItemTracker();

        tracker.add(ChatItem.outboundText("hello", 1L));
        tracker.onFileProgress(1, "Receiving", "a.bin", 0, 10);
        tracker.clear();

        assertTrue(tracker.items().isEmpty());
    }
}
