package org.example.p2pchat.network;

import org.example.p2pchat.util.AppLogger;
import org.example.p2pchat.util.NetworkUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

public final class PeerServer implements AutoCloseable {

    private final ServerSocket serverSocket;

    public PeerServer(int port) throws IOException {
        NetworkUtils.validateListeningPort(port);
        this.serverSocket = new ServerSocket(port);
    }

    public PeerServer(int port, InetAddress bindAddress) throws IOException {
        NetworkUtils.validateListeningPort(port);
        this.serverSocket = new ServerSocket(port, 1, bindAddress);
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public InetAddress localAddress() {
        return serverSocket.getInetAddress();
    }

    public Connection accept(Connection.Listener listener) throws IOException {
        AppLogger.info("Listening on port " + port());
        var socket = serverSocket.accept();
        socket.setTcpNoDelay(true);
        AppLogger.info("Peer connected: " + socket.getInetAddress().getHostAddress());
        return new Connection(socket, listener);
    }

    @Override
    public void close() throws IOException {
        if (!serverSocket.isClosed()) {
            serverSocket.close();
        }
    }
}
