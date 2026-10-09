package org.example.p2pchat.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lets the UI tests drive real JavaFX controls without a UI-testing framework.
 *
 * <p>The toolkit is a plain part of the JavaFX dependency already on the test classpath, so starting
 * it costs no extra library. Windows are never shown: {@code MainController} only needs a
 * {@code Stage} to exist, and {@code Node.lookup} searches an unshown scene graph quite happily.
 *
 * <p>Every read and write of a control happens on the FX thread, because JavaFX properties are not
 * thread-safe — the same rule the application itself follows with {@code Platform.runLater}.
 */
final class FxTestSupport {

    private static final long TIMEOUT_SECONDS = 30;

    private FxTestSupport() {
    }

    /** Starts the toolkit once for the whole test JVM; later calls notice it is already running. */
    static void startToolkit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyRunning) {
            started.countDown();
        }
        assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the JavaFX toolkit never started");
        // Never let the toolkit exit because the last (unshown) window closed, which would leave the
        // remaining test classes talking to a dead FX thread.
        fx(() -> Platform.setImplicitExit(false));
    }

    /** Runs {@code action} on the FX thread and waits for it, rethrowing whatever it threw. */
    static void fx(Runnable action) throws Exception {
        if (Platform.isFxApplicationThread()) {
            action.run();
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the FX thread never answered");
        Throwable thrown = failure.get();
        if (thrown != null) {
            throw new AssertionError("an action on the FX thread failed: " + thrown, thrown);
        }
    }

    /** Runs {@code action} on the FX thread and brings its result back. */
    static <T> T onFx(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        fx(() -> {
            try {
                result.set(action.call());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return result.get();
    }

    /**
     * Polls {@code condition} on the FX thread until it holds. The application reaches the UI through
     * {@code Platform.runLater} from a network thread, so anything that follows a real connection has
     * to be waited for rather than read immediately.
     */
    static void awaitFx(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            AtomicBoolean holds = new AtomicBoolean();
            fx(() -> holds.set(condition.getAsBoolean()));
            if (holds.get()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    // ------------------------------------------------------------- lookups

    static MenuButton picker(Parent root) {
        return (MenuButton) required(root.lookup(".recipient-button"), ".recipient-button");
    }

    @SuppressWarnings("unchecked")
    static <T> javafx.scene.control.ListView<T> listView(Parent root, String styleClass) {
        return (javafx.scene.control.ListView<T>) required(root.lookup("." + styleClass), "." + styleClass);
    }

    /** The text of the one label carrying this style class. */
    static String labelText(Parent root, String styleClass) throws Exception {
        javafx.scene.control.Label label =
                (javafx.scene.control.Label) required(root.lookup("." + styleClass), "." + styleClass);
        return onFx(label::getText);
    }

    /** Every node of the given type, in scene-graph order. */
    static <T> List<T> findAll(Parent root, Class<T> type) {
        List<T> found = new ArrayList<>();
        collect(root, type, found);
        return found;
    }

    private static <T> void collect(Node node, Class<T> type, List<T> found) {
        if (type.isInstance(node)) {
            found.add(type.cast(node));
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, type, found);
            }
        }
    }

    static Button button(Parent root, String text) {
        for (Button button : findAll(root, Button.class)) {
            if (text.equals(button.getText())) {
                return button;
            }
        }
        List<String> labels = new ArrayList<>();
        for (Button button : findAll(root, Button.class)) {
            labels.add(button.getText());
        }
        throw new AssertionError("no button labelled \"" + text + "\"; found " + labels);
    }

    /** The one text area of the session view: the message box. */
    static javafx.scene.control.TextArea messageBox(Parent root) {
        List<javafx.scene.control.TextArea> areas = findAll(root, javafx.scene.control.TextArea.class);
        assertTrue(areas.size() == 1, "expected exactly one TextArea, found " + areas.size());
        return areas.get(0);
    }

    private static Node required(Node node, String selector) {
        assertTrue(node != null, "no node matching " + selector + " in the view");
        return node;
    }

    // ------------------------------------------------------------ the picker

    /** The label of every checkbox the picker offers, in order. */
    static List<String> choiceLabels(MenuButton picker) {
        List<String> labels = new ArrayList<>();
        for (MenuItem item : picker.getItems()) {
            CheckBox box = asCheckBox(item);
            if (box != null) {
                labels.add(box.getText());
            }
        }
        return labels;
    }

    /** The checkbox for a client, matched on the name in front of the {@code #id}. */
    static CheckBox choiceNamed(MenuButton picker, String name) {
        for (MenuItem item : picker.getItems()) {
            CheckBox box = asCheckBox(item);
            if (box != null && box.getText().startsWith(name + "  #")) {
                return box;
            }
        }
        throw new AssertionError("no choice for \"" + name + "\" in " + choiceLabels(picker));
    }

    /** The placeholder the picker shows when there is nobody to address. */
    static MenuItem soleItem(MenuButton picker) {
        assertTrue(picker.getItems().size() == 1,
                "expected a single placeholder item, found " + picker.getItems().size());
        return picker.getItems().get(0);
    }

    private static CheckBox asCheckBox(MenuItem item) {
        if (item instanceof CustomMenuItem custom && custom.getContent() instanceof CheckBox box) {
            return box;
        }
        return null;
    }
}
