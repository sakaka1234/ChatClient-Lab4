package org.example.p2pchat.ui;

import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.example.p2pchat.protocol.ChatScope;
import org.example.p2pchat.session.ChatSession;
import org.example.p2pchat.session.ClientInfo;
import org.example.p2pchat.session.SessionListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole path, as a user walks it: pick <em>CLIENT</em>, type a name, the server's address and its
 * port into the form, press <em>Connect</em>, let other clients join the same server, tick one in the
 * <em>To:</em> picker, type into the message box and press <em>Send</em> — then check what actually
 * arrived on the other sockets.
 *
 * <p>This is the only test that covers the glue between the widgets and the wire: the checkbox
 * listener filling {@code selectedTargets}, and {@code onSend} turning that into a {@code CHAT} with
 * the right scope. The wire behaviour itself is covered by {@code AddressedChatTest}; here the point
 * is that clicking the controls produces it.
 *
 * <p>The server is a plain {@link ChatSession} with no window: a real server has no message box to
 * drive. That is the point of the last group of tests — the instance under test is a client, and the
 * server it talks to never takes part in the conversation.
 */
@Timeout(120)
class MainControllerSendTest {

    private static final long WAIT_SECONDS = 20;

    /** How long a negative assertion waits before concluding that a message never arrived. */
    private static final long SILENCE_MILLIS = 400;

    private static final class Recorder implements SessionListener {
        final BlockingQueue<String> chats = new ArrayBlockingQueue<>(64);

        @Override
        public void onStatusChanged(String status) {
        }

        @Override
        public void onChatMessage(String sender, String message) {
            chats.add(sender + ":" + message);
        }

        @Override
        public void onDisconnected(String reason) {
        }

        String awaitChat() throws InterruptedException {
            String value = chats.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertTrue(value != null, "expected a chat message");
            return value;
        }

        void assertSilent(String what) throws InterruptedException {
            String received = chats.poll(SILENCE_MILLIS, TimeUnit.MILLISECONDS);
            assertTrue(received == null, what + ", but it arrived as: " + received);
        }
    }

    private Stage stage;
    private MainController controller;

    private Recorder serverRec;
    private Recorder binhRec;
    private Recorder cuongRec;
    private ChatSession server;
    private ChatSession binh;
    private ChatSession cuong;

    @BeforeAll
    static void startTheToolkit() throws Exception {
        FxTestSupport.startToolkit();
    }

    /**
     * Starts the server headless, then fills the form in as a client and connects to it, exactly as a
     * user would. The instance under test is therefore An {@code #1}; Binh and Cuong join afterwards.
     */
    @BeforeEach
    void connectThroughTheForm() throws Exception {
        serverRec = new Recorder();
        server = new ChatSession(serverRec, "Server");
        server.startServer(0);
        int port = server.port();

        FxTestSupport.fx(() -> {
            stage = new Stage();
            controller = new MainController(stage);

            List<TextField> fields = FxTestSupport.findAll(controller.root(), TextField.class);
            assertEquals(4, fields.size(), "the connection form lays out four text fields");
            // Pin the order down: if the form is rearranged, fail here rather than silently typing
            // the display name into the port box.
            assertEquals("127.0.0.1", fields.get(2).getText(), "field #2 should be the server IP");

            fields.get(0).setText("An");                       // display name
            fields.get(3).setText(String.valueOf(port));       // server port
            FxTestSupport.button(controller.root(), "CLIENT").fire();
            FxTestSupport.button(controller.root(), "Connect").fire();
        });

        awaitClients(server, 1);

        binhRec = new Recorder();
        binh = new ChatSession(binhRec, "Binh");
        binh.startClient("127.0.0.1", port, 5000);
        awaitPickerListing("Binh");

        // A third client, so "a group of two" is distinguishable from "everyone".
        cuongRec = new Recorder();
        cuong = new ChatSession(cuongRec, "Cuong");
        cuong.startClient("127.0.0.1", port, 5000);
        awaitPickerListing("Binh", "Cuong");
    }

    @AfterEach
    void closeEverything() throws Exception {
        closeQuietly(cuong);
        closeQuietly(binh);
        closeQuietly(server);
        FxTestSupport.fx(() -> {
            controller.shutdown();
            stage.close();
        });
    }

    private static void closeQuietly(ChatSession session) {
        if (session != null) {
            session.close();
        }
    }

    private static void awaitClients(ChatSession session, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (session.clients().size() != expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("directory never held " + expected + " client(s), last was "
                        + session.clients());
            }
            Thread.sleep(20);
        }
    }

    /** Waits for the picker to offer exactly these clients — the instance under test is never one. */
    private void awaitPickerListing(String... names) throws Exception {
        FxTestSupport.awaitFx("the picker to list " + List.of(names), () -> {
            List<String> labels = FxTestSupport.choiceLabels(FxTestSupport.picker(controller.root()));
            if (labels.size() != names.length) {
                return false;
            }
            for (String name : names) {
                if (labels.stream().noneMatch(label -> label.startsWith(name + "  #"))) {
                    return false;
                }
            }
            return true;
        });
    }

    private void tick(String name) throws Exception {
        FxTestSupport.fx(() -> FxTestSupport.choiceNamed(FxTestSupport.picker(controller.root()), name)
                .setSelected(true));
    }

    private void typeAndSend(String text) throws Exception {
        FxTestSupport.fx(() -> {
            FxTestSupport.messageBox(controller.root()).setText(text);
            FxTestSupport.button(controller.root(), "Send").fire();
        });
    }

    private List<ChatItem> bubbles() throws Exception {
        return FxTestSupport.onFx(() ->
                List.copyOf(FxTestSupport.<ChatItem>listView(controller.root(), "chat-list").getItems()));
    }

    // ------------------------------------------------------------------ send

    @Test
    void tickingOneClientAndPressingSendPrivatesTheMessageToThem() throws Exception {
        tick("Binh");

        typeAndSend("chi rieng Binh");

        assertEquals("An:chi rieng Binh", binhRec.awaitChat());
        cuongRec.assertSilent("the picker had only Binh ticked");
        serverRec.assertSilent("a server relays chat, it does not take part in it");

        List<ChatItem> bubbles = bubbles();
        assertEquals(1, bubbles.size(), "the sender draws its own bubble");
        assertTrue(bubbles.get(0).outbound());
        assertEquals("chi rieng Binh", bubbles.get(0).text());
        assertEquals(ChatScope.UNICAST, bubbles.get(0).scope());
        assertEquals(List.of("Binh"), bubbles.get(0).audience(),
                "the bubble must name who it went to, not just say it was private");
    }

    @Test
    void pressingSendWithNobodyTickedBroadcasts() throws Exception {
        typeAndSend("ca nha oi");

        assertEquals("An:ca nha oi", binhRec.awaitChat());
        assertEquals("An:ca nha oi", cuongRec.awaitChat());
        serverRec.assertSilent("a server relays chat, it does not take part in it");

        List<ChatItem> bubbles = bubbles();
        assertEquals(ChatScope.BROADCAST, bubbles.get(0).scope());
        assertEquals(List.of(), bubbles.get(0).audience());
    }

    @Test
    void tickingTwoClientsMulticastsToExactlyThose() throws Exception {
        tick("Binh");
        tick("Cuong");

        typeAndSend("hop nhom");

        assertEquals("An:hop nhom", binhRec.awaitChat());
        assertEquals("An:hop nhom", cuongRec.awaitChat());

        List<ChatItem> bubbles = bubbles();
        assertEquals(ChatScope.MULTICAST, bubbles.get(0).scope());
        assertEquals(List.of("Binh", "Cuong"), bubbles.get(0).audience());
    }

    @Test
    void tickingTheWholeDirectoryIsStillAnExplicitGroup() throws Exception {
        tick("Binh");
        tick("Cuong");

        typeAndSend("tat ca");

        assertEquals("An:tat ca", binhRec.awaitChat());
        assertEquals("An:tat ca", cuongRec.awaitChat());

        List<ChatItem> bubbles = bubbles();
        assertEquals(ChatScope.MULTICAST, bubbles.get(0).scope(),
                "ticking everyone the picker offers is a group, not a broadcast — the label has to say which");
        assertEquals(List.of("Binh", "Cuong"), bubbles.get(0).audience());
    }

    @Test
    void anEmptyMessageBoxSendsNothing() throws Exception {
        tick("Binh");

        typeAndSend("   ");

        binhRec.assertSilent("a blank message must not be sent");
        assertEquals(List.of(), bubbles(), "and must not draw a bubble either");
    }

    // --------------------------------------------------------------- receive

    @Test
    void aMessageFromAnotherClientAppearsInTheChatList() throws Exception {
        binh.sendChat("xin chao An", ChatScope.BROADCAST, List.of());

        FxTestSupport.awaitFx("the message to reach the chat list", () -> !bubblesQuietly().isEmpty());

        List<ChatItem> bubbles = bubbles();
        assertEquals(1, bubbles.size());
        assertEquals("Binh", bubbles.get(0).sender());
        assertEquals("xin chao An", bubbles.get(0).text());
        assertFalse(bubbles.get(0).outbound(), "a message that arrived is not ours");
    }

    @Test
    void aPrivateMessageFromAnotherClientIsLabelledWithItsAudience() throws Exception {
        // An is the first client to dial in, so the server numbered it #1.
        binh.sendChat("chi rieng An", ChatScope.UNICAST, List.of(ChatSession.FIRST_CLIENT_ID));

        FxTestSupport.awaitFx("the private message to reach the chat list", () -> !bubblesQuietly().isEmpty());

        List<ChatItem> bubbles = bubbles();
        assertEquals(ChatScope.UNICAST, bubbles.get(0).scope());
        assertEquals(List.of("An"), bubbles.get(0).audience(),
                "the audience reads the same on the recipient's screen as on the sender's");
    }

    // ----------------------------------------------------------- the server

    @Test
    void theClientSeesOnlyTheOtherClientsAndNeverTheServer() throws Exception {
        List<String> labels = FxTestSupport.onFx(() ->
                FxTestSupport.choiceLabels(FxTestSupport.picker(controller.root())));

        assertEquals(2, labels.size(), "the two other clients: " + labels);
        assertTrue(labels.get(0).startsWith("Binh  #"), labels.get(0));
        assertTrue(labels.get(1).startsWith("Cuong  #"), labels.get(1));
        assertEquals(List.of("An", "Binh", "Cuong"),
                server.clients().stream().map(ClientInfo::name).toList(),
                "the server knows all three; only the two it is not are offered");
    }

    @Test
    void theServerNeverReceivesWhatTheClientsRelayToEachOther() throws Exception {
        tick("Cuong");
        typeAndSend("chi rieng Cuong");
        assertEquals("An:chi rieng Cuong", cuongRec.awaitChat());

        binh.sendChat("toi cung noi", ChatScope.BROADCAST, List.of());

        FxTestSupport.awaitFx("Binh's message to reach the chat list", () -> !bubblesQuietly().isEmpty());
        serverRec.assertSilent("a server relays chat, it never receives it");
    }

    // ---------------------------------------------------------------- header

    @Test
    void theClientChipCarriesThisClientsName() throws Exception {
        assertEquals("CLIENT · An", FxTestSupport.labelText(controller.root(), "client-title"),
                "the role chip is where several open windows are told apart");
    }

    @Test
    void theNameOnTheChipIsTheOneTypedIntoTheFormNotTheMachinesUserName() throws Exception {
        // The form was filled in as "An"; the chip must not fall back to the OS user name or to the
        // "Client" default, which would make every window look alike again.
        String chip = FxTestSupport.labelText(controller.root(), "client-title");

        assertTrue(chip.endsWith("An"), chip);
        assertFalse(chip.equals("CLIENT"), "the name is missing: " + chip);
    }

    @Test
    void theSummaryNextToTheChipIsUnchangedByTheName() throws Exception {
        String summary = FxTestSupport.labelText(controller.root(), "client-summary");

        assertEquals("Connected to 127.0.0.1:" + server.port() + " · 3 clients · you are #"
                + ChatSession.FIRST_CLIENT_ID, summary,
                "An, Binh and Cuong are all connected, and the name lives on the chip instead");
    }

    @Test
    void theWindowTitleNamesTheClientToo() throws Exception {
        assertEquals("Chat — Client · An", FxTestSupport.onFx(stage::getTitle),
                "the title bar is where several windows are told apart");
    }

    /** The same read as {@link #bubbles()} but safe to call from a polling condition. */
    private List<ChatItem> bubblesQuietly() {
        try {
            return bubbles();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
