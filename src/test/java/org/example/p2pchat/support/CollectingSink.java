package org.example.p2pchat.support;

import org.example.p2pchat.network.PacketSink;
import org.example.p2pchat.protocol.Packet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CollectingSink implements PacketSink {

    private final List<Packet> packets = Collections.synchronizedList(new ArrayList<>());
    private volatile IOException failure;

    @Override
    public void send(Packet packet) throws IOException {
        IOException current = failure;
        if (current != null) {
            throw current;
        }
        packets.add(packet);
    }

    public List<Packet> packets() {
        synchronized (packets) {
            return new ArrayList<>(packets);
        }
    }

    public Packet packet(int index) {
        return packets().get(index);
    }

    public int size() {
        return packets.size();
    }

    public void failWith(IOException cause) {
        this.failure = cause;
    }
}
