package org.example.p2pchat.network;

import org.example.p2pchat.protocol.Packet;
import org.example.p2pchat.protocol.PacketCodec;
import org.example.p2pchat.util.AppLogger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Connection implements AutoCloseable {

    private static final int OUTGOING_QUEUE_CAPACITY = 256;
    private static final int SEND_TIMEOUT_SECONDS = 30;

    public interface Listener {
        void onPacket(Packet packet);

        void onDisconnected(Throwable reason);
    }

    private final Socket socket;
    private final Listener listener;
    private final BlockingQueue<Packet> outgoing = new ArrayBlockingQueue<>(OUTGOING_QUEUE_CAPACITY);
    private final AtomicBoolean open = new AtomicBoolean(false);
    private final AtomicBoolean closingIntentionally = new AtomicBoolean(false);

    private volatile Thread reader;
    private volatile Thread writer;

    Connection(Socket socket, Listener listener) {
        this.socket = socket;
        this.listener = listener;
    }

    public void start() {
        if (!open.compareAndSet(false, true)) {
            throw new IllegalStateException("Connection already started");
        }
        reader = new Thread(this::readLoop, "p2p-reader");
        writer = new Thread(this::writeLoop, "p2p-writer");
        reader.setDaemon(true);
        writer.setDaemon(true);
        reader.start();
        writer.start();
    }

    public void send(Packet packet) throws IOException {
        if (!open.get()) {
            throw new IOException("Connection is closed");
        }
        try {
            if (!outgoing.offer(packet, SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("Send queue is full; peer is too slow");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while queueing packet", e);
        }
    }

    public boolean isOpen() {
        return open.get() && !socket.isClosed();
    }

    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) socket.getRemoteSocketAddress();
    }

    private void readLoop() {
        try {
            InputStream raw = socket.getInputStream();
            InputStream in = raw instanceof BufferedInputStream ? raw : new BufferedInputStream(raw, 64 * 1024);
            while (open.get()) {
                Packet packet = PacketCodec.read(in);
                listener.onPacket(packet);
            }
        } catch (IOException e) {
            if (open.get() && !closingIntentionally.get()) {
                AppLogger.info("Peer connection closed unexpectedly: " + describe(e));
            }
            closeSocket();
            notifyDisconnected(closingIntentionally.get() ? null : e);
        } finally {
            closeSocket();
        }
    }

    private void writeLoop() {
        try {
            OutputStream raw = socket.getOutputStream();
            OutputStream out = raw instanceof BufferedOutputStream ? raw : new BufferedOutputStream(raw, 64 * 1024);
            while (open.get()) {
                Packet packet = outgoing.take();
                PacketCodec.write(out, packet);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (open.get() && !closingIntentionally.get()) {
                AppLogger.error("Failed to send packet", e);
            }
            closeSocket();
            notifyDisconnected(closingIntentionally.get() ? null : e);
        }
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) {
            closeSocket();
            return;
        }
        closingIntentionally.set(true);
        closeSocket();
        Thread currentWriter = writer;
        if (currentWriter != null) {
            currentWriter.interrupt();
        }
    }

    private void closeSocket() {
        open.set(false);
        try {
            if (!socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            AppLogger.warn("Failed to close socket: " + describe(e));
        }
        Thread currentWriter = writer;
        if (currentWriter != null) {
            currentWriter.interrupt();
        }
    }

    private void notifyDisconnected(Throwable reason) {
        if (closingIntentionally.get()) {
            return;
        }
        try {
            listener.onDisconnected(reason);
        } catch (RuntimeException e) {
            AppLogger.error("Listener failed while handling disconnect", e);
        }
    }

    private static String describe(IOException e) {
        if (e instanceof EOFException) {
            return "peer closed the stream";
        }
        if (e instanceof SocketException) {
            return e.getMessage();
        }
        return e.toString();
    }
}
