package org.example.p2pchat.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
class TransferGateTest {

    @Test
    void waitReturnsAcceptedAfterAccept() throws Exception {
        TransferGate gate = new TransferGate(5);
        Thread accepter = new Thread(() -> {
            sleep(80);
            gate.accept();
        });
        accepter.start();

        assertEquals(TransferDecision.ACCEPTED, gate.awaitDecision());
    }

    @Test
    void waitReturnsDeclinedAfterDecline() throws Exception {
        TransferGate gate = new TransferGate(5);
        Thread decliner = new Thread(() -> {
            sleep(80);
            gate.decline();
        });
        decliner.start();

        assertEquals(TransferDecision.DECLINED, gate.awaitDecision());
    }

    @Test
    void waitReturnsTimedOutWhenNobodyAnswers() {
        TransferGate gate = new TransferGate(1);

        assertEquals(TransferDecision.TIMED_OUT, gate.awaitDecision());
    }

    @Test
    void disconnectUnblocksWait() throws Exception {
        TransferGate gate = new TransferGate(30);
        CountDownLatch waiting = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            waiting.countDown();
            gate.awaitDecision();
        });
        waiter.start();
        assertTrue(waiting.await(5, TimeUnit.SECONDS));
        sleep(80);

        gate.disconnect();

        waiter.join(5000);
        assertFalse(waiter.isAlive(), "waiter must be released by disconnect");
    }

    @Test
    void disconnectDecisionIsReported() {
        TransferGate gate = new TransferGate(30);

        gate.disconnect();

        assertEquals(TransferDecision.DISCONNECTED, gate.awaitDecision());
    }

    @Test
    void firstDecisionWins() {
        TransferGate gate = new TransferGate(5);

        gate.decline();
        gate.accept();

        assertEquals(TransferDecision.DECLINED, gate.awaitDecision());
    }

    @Test
    void timeoutIsReportedAsTerminal() {
        assertTrue(TransferDecision.TIMED_OUT.isTerminal());
        assertTrue(TransferDecision.DECLINED.isTerminal());
        assertTrue(TransferDecision.DISCONNECTED.isTerminal());
        assertFalse(TransferDecision.ACCEPTED.isTerminal());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
