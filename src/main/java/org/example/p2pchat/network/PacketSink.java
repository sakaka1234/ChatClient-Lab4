package org.example.p2pchat.network;

import org.example.p2pchat.protocol.Packet;

import java.io.IOException;

@FunctionalInterface
public interface PacketSink {

    void send(Packet packet) throws IOException;
}
