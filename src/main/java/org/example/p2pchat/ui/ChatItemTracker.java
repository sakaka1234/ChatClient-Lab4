package org.example.p2pchat.ui;

import java.util.ArrayList;
import java.util.List;

/** The ordered transcript behind the chat list, with change notification for the view. */
public final class ChatItemTracker {

    private final List<ChatItem> ordered = new ArrayList<>();
    private final List<Runnable> listeners = new ArrayList<>();

    public void add(ChatItem item) {
        ordered.add(item);
        fireChanged();
    }

    public void clear() {
        ordered.clear();
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
