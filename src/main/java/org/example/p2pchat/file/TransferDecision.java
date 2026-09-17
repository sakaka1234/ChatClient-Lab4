package org.example.p2pchat.file;

public enum TransferDecision {

    ACCEPTED,
    DECLINED,
    TIMED_OUT,
    DISCONNECTED;

    public boolean isTerminal() {
        return this != ACCEPTED;
    }
}
