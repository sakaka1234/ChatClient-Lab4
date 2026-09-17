package org.example.p2pchat.file;

public final class TransferGate {

    private final long timeoutMillis;
    private final Object lock = new Object();

    private boolean answered;
    private TransferDecision decision;

    public TransferGate(long timeoutSeconds) {
        this.timeoutMillis = timeoutSeconds * 1000L;
    }

    public TransferDecision awaitDecision() {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        synchronized (lock) {
            while (!answered) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    answered = true;
                    decision = TransferDecision.TIMED_OUT;
                    break;
                }
                try {
                    lock.wait(Math.max(1L, remaining / 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    answered = true;
                    decision = TransferDecision.DISCONNECTED;
                    break;
                }
            }
            return decision;
        }
    }

    public void accept() {
        resolve(TransferDecision.ACCEPTED);
    }

    public void decline() {
        resolve(TransferDecision.DECLINED);
    }

    public void disconnect() {
        resolve(TransferDecision.DISCONNECTED);
    }

    public boolean isAnswered() {
        synchronized (lock) {
            return answered;
        }
    }

    public TransferDecision decision() {
        synchronized (lock) {
            return decision;
        }
    }

    private void resolve(TransferDecision value) {
        synchronized (lock) {
            if (answered) {
                return;
            }
            answered = true;
            decision = value;
            lock.notifyAll();
        }
    }
}
