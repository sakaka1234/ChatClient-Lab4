package org.example.p2pchat.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ChatItemTracker {

    /**
     * File ids are chosen independently by each peer and both start at 1, so an id alone is not
     * unique: the file this peer sends as id 1 and the file it receives as id 1 are different
     * transfers. Keying by direction as well keeps them apart.
     */
    private record TransferKey(boolean outbound, int fileId) {
    }

    private final List<ChatItem> ordered = new ArrayList<>();
    private final Map<TransferKey, ChatItem> byTransfer = new LinkedHashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();

    public void add(ChatItem item) {
        ordered.add(item);
        fireChanged();
    }

    public void trackOutbound(int fileId, ChatItem item) {
        byTransfer.put(new TransferKey(true, fileId), item);
        add(item);
    }

    public void onFileOffered(int fileId, String direction, String fileName, long fileSize) {
        TransferKey key = inboundKey(fileId);
        if (byTransfer.containsKey(key)) {
            return;
        }
        ChatItem item = ChatItem.inboundFile(fileId, fileName, fileSize, System.currentTimeMillis());
        byTransfer.put(key, item);
        ordered.add(item);
        fireChanged();
    }

    public void onFileProgress(int fileId, String direction, String fileName, long bytes, long total) {
        TransferKey key = keyFor(direction, fileId);
        ChatItem item = byTransfer.get(key);
        if (item == null) {
            if (!direction.equals("Receiving")) {
                return;
            }
            item = ChatItem.inboundFile(fileId, fileName, total, System.currentTimeMillis());
            byTransfer.put(key, item);
            ordered.add(item);
        }
        item.updateProgress(bytes);
        fireChanged();
    }

    public void onFileEvent(int fileId, String direction, String fileName, String detail,
                            Path savedPath, boolean failed) {
        TransferKey key = keyFor(direction, fileId);
        ChatItem item = byTransfer.get(key);
        if (item == null) {
            if (failed && direction.equals("Sending")) {
                return;
            }
            item = ChatItem.inboundFile(fileId, fileName, 0, System.currentTimeMillis());
            byTransfer.put(key, item);
            ordered.add(item);
        }
        if (failed) {
            item.fail(detail);
        } else {
            item.complete(savedPath);
        }
        fireChanged();
    }

    public void onFileDeclined(int fileId, String direction, String fileName, String reason) {
        ChatItem item = byTransfer.get(keyFor(direction, fileId));
        if (item == null) {
            return;
        }
        item.decline(reason);
        fireChanged();
    }

    public void acceptLocally(ChatItem item) {
        item.accept();
        fireChanged();
    }

    public void clear() {
        ordered.clear();
        byTransfer.clear();
        fireChanged();
    }

    public List<ChatItem> items() {
        return List.copyOf(ordered);
    }

    public int indexOf(ChatItem item) {
        return ordered.indexOf(item);
    }

    public void addChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    private static TransferKey keyFor(String direction, int fileId) {
        return new TransferKey(direction.equals("Sending"), fileId);
    }

    private static TransferKey inboundKey(int fileId) {
        return new TransferKey(false, fileId);
    }

    private void fireChanged() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // a broken view listener must not stop the tracker
            }
        }
    }
}
