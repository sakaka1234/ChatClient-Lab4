package org.example.p2pchat.network;

import org.example.p2pchat.util.AppLogger;
import org.example.p2pchat.util.NetworkUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public final class PeerClient {

    private PeerClient() {
    }

    public static Connection connect(String host, int port, int timeoutMillis, Connection.Listener listener)
            throws IOException {
        NetworkUtils.validatePort(port);
        NetworkUtils.resolveAddress(host);
        AppLogger.info("Connecting to " + host + ":" + port);
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host.trim(), port), timeoutMillis);
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        }
        AppLogger.info("Connected to " + socket.getInetAddress().getHostAddress() + ":" + socket.getPort());
        return new Connection(socket, listener);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
