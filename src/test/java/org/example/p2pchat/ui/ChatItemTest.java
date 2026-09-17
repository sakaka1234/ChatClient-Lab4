package org.example.p2pchat.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatItemTest {

    @Test
    void outboundTextStartsCompleted() {
        ChatItem item = ChatItem.outboundText("hello", 1_000L);

        assertEquals(ChatItem.Kind.TEXT, item.kind());
        assertTrue(item.outbound());
        assertEquals("hello", item.text());
        assertEquals(ChatItem.Status.COMPLETED, item.status());
        assertTrue(item.isTerminal());
    }

    @Test
    void inboundTextIsAlwaysCompleted() {
        ChatItem item = ChatItem.inboundText("hi", 1_000L);

        assertFalse(item.outbound());
        assertEquals(ChatItem.Status.COMPLETED, item.status());
        assertTrue(item.isTerminal());
    }

    @Test
    void textRejectsBlankContent() {
        assertThrows(IllegalArgumentException.class, () -> ChatItem.outboundText("   ", 0L));
        assertThrows(IllegalArgumentException.class, () -> ChatItem.inboundText(null, 0L));
    }

    @Test
    void outboundFileStartsInProgress(@TempDir Path dir) {
        Path file = dir.resolve("report.pdf");
        ChatItem item = ChatItem.outboundFile(7, "report.pdf", 2048, file, 500L);

        assertEquals(ChatItem.Kind.FILE, item.kind());
        assertTrue(item.outbound());
        assertEquals(7, item.fileId());
        assertEquals("report.pdf", item.fileName());
        assertEquals(2048, item.totalBytes());
        assertEquals(0, item.transferredBytes());
        assertEquals(0.0, item.progress(), 0.0001);
        assertEquals(ChatItem.Status.WAITING_FOR_DECISION, item.status());
        assertTrue(item.isAwaitingDecision());
        assertFalse(item.isTerminal());
        assertEquals(file, item.localPath());
    }

    @Test
    void inboundFileStartsAwaitingDecisionWithoutLocalPath() {
        ChatItem item = ChatItem.inboundFile(7, "report.pdf", 2048, 500L);

        assertFalse(item.outbound());
        assertEquals(ChatItem.Status.WAITING_FOR_DECISION, item.status());
        assertTrue(item.isAwaitingDecision());
        assertNull(item.localPath());
    }

    @Test
    void acceptMovesItemIntoProgress() {
        ChatItem item = ChatItem.inboundFile(1, "a.bin", 100, 0L);

        item.accept();

        assertEquals(ChatItem.Status.IN_PROGRESS, item.status());
        assertFalse(item.isAwaitingDecision());
    }

    @Test
    void declineIsTerminalAndRecordsReason() {
        ChatItem item = ChatItem.inboundFile(1, "a.bin", 100, 0L);

        item.decline("declined by user");

        assertEquals(ChatItem.Status.DECLINED, item.status());
        assertTrue(item.isTerminal());
        assertEquals("declined by user", item.detail());
        assertFalse(item.canOpen());
    }

    @Test
    void firstProgressImplicitlyAcceptsTheOffer() {
        ChatItem item = ChatItem.inboundFile(1, "a.bin", 100, 0L);

        item.updateProgress(10);

        assertEquals(ChatItem.Status.IN_PROGRESS, item.status());
    }

    @Test
    void fileProgressUpdatesBytesAndRatio() {
        ChatItem item = ChatItem.inboundFile(1, "a.bin", 1000, 0L);

        item.updateProgress(250);

        assertEquals(250, item.transferredBytes());
        assertEquals(0.25, item.progress(), 0.0001);
        assertEquals(ChatItem.Status.IN_PROGRESS, item.status());
    }

    @Test
    void progressIsClampedToRange() {
        ChatItem item = ChatItem.inboundFile(1, "a.bin", 1000, 0L);

        item.updateProgress(5000);
        assertEquals(1.0, item.progress(), 0.0001);

        item.updateProgress(-100);
        assertEquals(0.0, item.progress(), 0.0001);
    }

    @Test
    void zeroByteFileHasProgressZeroWhileAwaitingDecision() {
        ChatItem item = ChatItem.inboundFile(1, "empty.txt", 0, 0L);

        assertEquals(0.0, item.progress(), 0.0001);
    }

    @Test
    void acceptedZeroByteFileHasProgressOne() {
        ChatItem item = ChatItem.inboundFile(1, "empty.txt", 0, 0L);
        item.accept();

        assertEquals(1.0, item.progress(), 0.0001);
    }

    @Test
    void completingOutboundFileKeepsLocalPath() {
        ChatItem item = ChatItem.outboundFile(1, "a.txt", 10, Path.of("a.txt"), 0L);

        item.complete(null);

        assertEquals(ChatItem.Status.COMPLETED, item.status());
        assertTrue(item.isTerminal());
        assertEquals(Path.of("a.txt"), item.localPath());
    }

    @Test
    void completingInboundFileStoresSavedPath() {
        ChatItem item = ChatItem.inboundFile(1, "a.txt", 10, 0L);

        item.complete(Path.of("received/a.txt"));

        assertEquals(ChatItem.Status.COMPLETED, item.status());
        assertEquals(Path.of("received/a.txt"), item.localPath());
        assertEquals(1.0, item.progress(), 0.0001);
    }

    @Test
    void failingRecordsReason() {
        ChatItem item = ChatItem.inboundFile(1, "a.txt", 10, 0L);

        item.fail("size mismatch");

        assertEquals(ChatItem.Status.FAILED, item.status());
        assertTrue(item.isTerminal());
        assertEquals("size mismatch", item.detail());
    }

    @Test
    void completedItemIgnoresLaterProgress() {
        ChatItem item = ChatItem.inboundFile(1, "a.txt", 10, 0L);
        item.complete(Path.of("x"));

        item.updateProgress(3);
        item.fail("too late");

        assertEquals(ChatItem.Status.COMPLETED, item.status());
        assertEquals(Path.of("x"), item.localPath());
    }

    @Test
    void terminalItemIsNotDownloadableUntilCompleted() {
        ChatItem inProgress = ChatItem.inboundFile(1, "a.txt", 10, 0L);
        assertFalse(inProgress.canOpen());

        inProgress.complete(Path.of("received/a.txt"));
        assertTrue(inProgress.canOpen());

        ChatItem failed = ChatItem.inboundFile(2, "b.txt", 10, 0L);
        failed.fail("boom");
        assertFalse(failed.canOpen());
    }

    @Test
    void outboundFileCanBeOpenedFromItsSourcePath() {
        ChatItem item = ChatItem.outboundFile(1, "a.txt", 10, Path.of("source/a.txt"), 0L);
        item.complete(null);

        assertTrue(item.canOpen());
        assertEquals(Path.of("source/a.txt"), item.localPath());
    }

    @Test
    void saveAsOnlyAppliesToReceivedFiles() {
        ChatItem sent = ChatItem.outboundFile(1, "a.txt", 10, Path.of("source/a.txt"), 0L);
        sent.complete(null);
        assertFalse(sent.canSaveAs());

        ChatItem received = ChatItem.inboundFile(2, "b.txt", 10, 0L);
        received.complete(Path.of("received/b.txt"));
        assertTrue(received.canSaveAs());
    }

    @Test
    void isImageReflectsFileName() {
        assertTrue(ChatItem.inboundFile(1, "pic.png", 10, 0L).isImage());
        assertFalse(ChatItem.inboundFile(1, "doc.pdf", 10, 0L).isImage());
        assertFalse(ChatItem.outboundText("hello", 0L).isImage());
    }

    @Test
    void progressOfTextIsAlwaysComplete() {
        assertEquals(1.0, ChatItem.outboundText("hi", 0L).progress(), 0.0001);
    }
}
