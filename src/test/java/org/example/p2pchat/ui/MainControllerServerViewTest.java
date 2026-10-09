package org.example.p2pchat.ui;

import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.example.p2pchat.protocol.ChatScope;
import org.example.p2pchat.session.ChatSession;
import org.example.p2pchat.session.ClientInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server window, opened the way a user opens it: fill the form in and press <em>Start Server</em>.
 *
 * <p>The server relays and never chats, so its window has no message box, no recipient picker and no
 * conversation — only the client list, the relay log, and a header counting both. The directory and
 * the log lines are fed in the way the session feeds them, so what is asserted here is the wiring
 * between {@code onRosterChanged}/{@code onServerLog} and the widgets.
 *
 * <p>The last test is the one that says the server window stayed out of the conversation: it is the
 * only test in the suite that shows the server view, and it checks that the view added no second
 * text area of its own.
 */
@Timeout(60)
class MainControllerServerViewTest {

    private static final ClientInfo AN = new ClientInfo(1, "An");
    private static final ClientInfo BINH = new ClientInfo(2, "Binh");
    private static final ClientInfo CUONG = new ClientInfo(3, "Cuong");

    private Stage stage;
    private MainController controller;

    @BeforeAll
    static void startTheToolkit() throws Exception {
        FxTestSupport.startToolkit();
    }

    /** Starts a real server through the form. No client ever dials in; the directory is fed by hand. */
    @BeforeEach
    void startTheServerThroughTheForm() throws Exception {
        int port = freePort();

        FxTestSupport.fx(() -> {
            stage = new Stage();
            controller = new MainController(stage);

            List<TextField> fields = FxTestSupport.findAll(controller.root(), TextField.class);
            assertEquals(4, fields.size(), "the connection form lays out four text fields");
            assertEquals("5000", fields.get(1).getText(), "field #1 should be the local port");

            fields.get(0).setText("Server");                  // display name
            fields.get(1).setText(String.valueOf(port));      // local port
            FxTestSupport.button(controller.root(), "Start Server").fire();
        });

        assertEquals("Chat — Server", FxTestSupport.onFx(stage::getTitle),
                "the form opens the window it started");
    }

    @AfterEach
    void closeTheApplication() throws Exception {
        FxTestSupport.fx(() -> {
            controller.shutdown();
            stage.close();
        });
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private void directory(ClientInfo... clients) throws Exception {
        FxTestSupport.fx(() -> controller.onRosterChanged(ChatSession.UNASSIGNED_CLIENT_ID,
                List.of(clients)));
    }

    private List<String> clientRows() throws Exception {
        return FxTestSupport.onFx(() ->
                List.copyOf(FxTestSupport.<String>listView(controller.root(), "client-list").getItems()));
    }

    private List<String> logLines() throws Exception {
        return FxTestSupport.onFx(() ->
                List.copyOf(FxTestSupport.<String>listView(controller.root(), "server-log").getItems()));
    }

    /** The header line: the port, how many clients are connected, and how much has been relayed. */
    private String summary() throws Exception {
        return FxTestSupport.onFx(() -> ((Label) controller.root().lookup(".server-summary")).getText());
    }

    // ---------------------------------------------------------- the directory

    @Test
    void theServerViewListsItsClientsByNumberAndName() throws Exception {
        directory(AN, BINH, CUONG);

        assertEquals(List.of("#1  An", "#2  Binh", "#3  Cuong"), clientRows());
    }

    @Test
    void theDirectoryIsRepaintedWhenAClientLeaves() throws Exception {
        directory(AN, BINH, CUONG);

        directory(AN, CUONG);

        assertEquals(List.of("#1  An", "#3  Cuong"), clientRows());
    }

    @Test
    void theHeaderCountsTheClientsAndWhatHasBeenRelayed() throws Exception {
        directory(AN, BINH, CUONG);

        assertTrue(summary().startsWith(":"), summary());
        assertTrue(summary().contains("Clients (3)"), summary());
        assertTrue(summary().contains("Relayed (0)"), summary());
    }

    // --------------------------------------------------------------- the log

    @Test
    void theServerViewShowsTheRelayLogItIsGiven() throws Exception {
        FxTestSupport.fx(() -> {
            controller.onServerLog("+ An #1 @127.0.0.1:54321 joined");
            controller.onServerLog("An #1 @127.0.0.1:54321  BROADCAST  -> everyone (2 delivered, 12 B)");
        });

        assertEquals(List.of(
                "+ An #1 @127.0.0.1:54321 joined",
                "An #1 @127.0.0.1:54321  BROADCAST  -> everyone (2 delivered, 12 B)"), logLines());
    }

    @Test
    void aServerThatHasJustStartedHasRelayedNothing() throws Exception {
        assertEquals(List.of(), logLines());
    }

    // ------------------------------------------------------- out of the chat

    @Test
    void theServerViewNeverGrowsAMessageBox() throws Exception {
        directory(AN, BINH, CUONG);
        FxTestSupport.fx(() -> controller.onServerLog("An #1 @127.0.0.1:54321  UNICAST  -> Binh #2 "
                + "(1 delivered, 7 B)"));

        // FxTestSupport.messageBox asserts the whole scene graph holds exactly one TextArea, and that
        // one belongs to the client view: a server has nothing to type into.
        FxTestSupport.messageBox(controller.root());
    }

    @Test
    void aMessageArrivingDoesNotTurnTheServerWindowIntoAClientOne() throws Exception {
        directory(AN, BINH, CUONG);

        FxTestSupport.fx(() -> controller.onChatMessage("An", "chao", ChatScope.BROADCAST, List.of()));

        assertEquals("Chat — Server", FxTestSupport.onFx(stage::getTitle),
                "a message on the wire must not move the window to the other view");
        FxTestSupport.messageBox(controller.root());
    }
}
