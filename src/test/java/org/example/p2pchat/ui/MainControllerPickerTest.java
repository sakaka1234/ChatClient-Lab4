package org.example.p2pchat.ui;

import javafx.scene.control.MenuButton;
import javafx.stage.Stage;
import org.example.p2pchat.protocol.ChatScope;
import org.example.p2pchat.session.ChatSession;
import org.example.p2pchat.session.ClientInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real controls behind the recipient picker and the chat list, driven exactly as the running
 * application drives them: the controller is told the directory changed, or a message arrived, and
 * the widgets are then read back. Nothing is stubbed — these are JavaFX {@code CheckBox}es and
 * {@code MenuItem}s — but no window is ever shown and no socket is involved.
 *
 * <p>The server is not one of these entries: it relays, so it is not in the directory and cannot be
 * addressed. The ids run An #1, Binh #2, Cuong #3. The server's own window is covered by
 * {@link MainControllerServerViewTest}.
 */
class MainControllerPickerTest {

    private static final ClientInfo AN = new ClientInfo(1, "An");
    private static final ClientInfo BINH = new ClientInfo(2, "Binh");
    private static final ClientInfo CUONG = new ClientInfo(3, "Cuong");

    private Stage stage;
    private MainController controller;

    @BeforeAll
    static void startTheToolkit() throws Exception {
        FxTestSupport.startToolkit();
    }

    @BeforeEach
    void openTheApplication() throws Exception {
        FxTestSupport.fx(() -> {
            stage = new Stage();
            controller = new MainController(stage);
        });
    }

    @AfterEach
    void closeTheApplication() throws Exception {
        FxTestSupport.fx(() -> {
            controller.shutdown();
            stage.close();
        });
    }

    // ---------------------------------------------------------------- setup

    private void directory(int selfId, ClientInfo... clients) throws Exception {
        FxTestSupport.fx(() -> controller.onRosterChanged(selfId, List.of(clients)));
    }

    private MenuButton picker() throws Exception {
        return FxTestSupport.onFx(() -> FxTestSupport.picker(controller.root()));
    }

    private List<String> choices() throws Exception {
        return FxTestSupport.onFx(() -> FxTestSupport.choiceLabels(FxTestSupport.picker(controller.root())));
    }

    private String pickerLabel() throws Exception {
        return FxTestSupport.onFx(() -> FxTestSupport.picker(controller.root()).getText());
    }

    private void check(String name, boolean selected) throws Exception {
        FxTestSupport.fx(() -> FxTestSupport.choiceNamed(FxTestSupport.picker(controller.root()), name)
                .setSelected(selected));
    }

    // ------------------------------------------------------------- the list

    @Test
    void thePickerOffersEveryOtherClientAndNeverYou() throws Exception {
        directory(1, AN, BINH, CUONG);

        assertEquals(List.of("Binh  #2", "Cuong  #3"), choices());
    }

    @Test
    void aClientIsNeverOfferedToItselfWhereverItSitsInTheDirectory() throws Exception {
        directory(3, AN, BINH, CUONG);

        assertEquals(List.of("An  #1", "Binh  #2"), choices(),
                "a message cannot be addressed to its own sender");
    }

    @Test
    void aClientThatIsAloneSaysThereIsNobodyToAddress() throws Exception {
        directory(1, AN);

        assertEquals("Everyone", pickerLabel());
        assertEquals("No other client yet",
                FxTestSupport.onFx(() -> FxTestSupport.soleItem(FxTestSupport.picker(controller.root())).getText()));
        assertTrue(FxTestSupport.onFx(() ->
                FxTestSupport.soleItem(FxTestSupport.picker(controller.root())).isDisable()),
                "the placeholder must not be clickable");
    }

    // -------------------------------------------------------- choosing clients

    @Test
    void checkingOneClientNamesTheButtonAfterThem() throws Exception {
        directory(1, AN, BINH, CUONG);

        check("Binh", true);

        assertEquals("Binh", pickerLabel(), "one client picked means a private message");
    }

    @Test
    void checkingSeveralClientsListsThemAllInTheOrderTheyWerePicked() throws Exception {
        directory(1, AN, BINH, CUONG);

        check("Binh", true);
        check("Cuong", true);

        assertEquals("Binh, Cuong", pickerLabel());
    }

    @Test
    void uncheckingEveryoneBringsTheButtonBackToEveryone() throws Exception {
        directory(1, AN, BINH, CUONG);
        check("Binh", true);
        check("Cuong", true);

        check("Binh", false);
        check("Cuong", false);

        assertEquals("Everyone", pickerLabel(), "an empty selection is a broadcast again");
    }

    // ------------------------------------------------------ the directory moves

    @Test
    void aClientThatLeavesIsDroppedFromThePickerAndFromTheSelection() throws Exception {
        directory(1, AN, BINH, CUONG);
        check("Binh", true);
        check("Cuong", true);
        assertEquals("Binh, Cuong", pickerLabel());

        directory(1, AN, BINH);                    // the sender itself left: only #1 and #2 remain

        assertEquals(List.of("Binh  #2"), choices());
        assertEquals("Binh", pickerLabel(),
                "Cuong must not stay selected, or the next message would address a client that is gone");
    }

    @Test
    void theDirectoryOfAClientIsRebuiltWhenSomebodyJoins() throws Exception {
        directory(1, AN);
        assertEquals(List.of(), choices());

        directory(1, AN, BINH, CUONG);

        assertEquals(List.of("Binh  #2", "Cuong  #3"), choices());
    }

    // ------------------------------------------------------ losing the session

    @Test
    void disconnectingEmptiesThePicker() throws Exception {
        directory(1, AN, BINH, CUONG);
        check("Binh", true);

        FxTestSupport.fx(() -> controller.onDisconnected(ChatSession.STATUS_SERVER_DISCONNECTED));

        assertEquals("Everyone", pickerLabel());
        assertEquals("No other client yet",
                FxTestSupport.onFx(() -> FxTestSupport.soleItem(FxTestSupport.picker(controller.root())).getText()));
    }

    // ----------------------------------------------------------- the bubbles

    @Test
    void anIncomingGroupMessageRendersWithItsAudience() throws Exception {
        directory(3, AN, BINH, CUONG);

        FxTestSupport.fx(() -> controller.onChatMessage("An", "hop nhom", ChatScope.MULTICAST,
                List.of("Binh", "Cuong")));

        List<ChatItem> items = FxTestSupport.onFx(() ->
                List.copyOf(FxTestSupport.<ChatItem>listView(controller.root(), "chat-list").getItems()));

        assertEquals(1, items.size());
        ChatItem item = items.get(0);
        assertEquals("An", item.sender());
        assertEquals("hop nhom", item.text());
        assertEquals(ChatScope.MULTICAST, item.scope());
        assertEquals(List.of("Binh", "Cuong"), item.audience());
        assertFalse(item.outbound(), "a message that arrived is not ours");
    }

    @Test
    void anIncomingBroadcastRendersWithoutAnAudience() throws Exception {
        directory(1, AN, BINH);

        FxTestSupport.fx(() -> controller.onChatMessage("Binh", "ca nha oi", ChatScope.BROADCAST, List.of()));

        List<ChatItem> items = FxTestSupport.onFx(() ->
                List.copyOf(FxTestSupport.<ChatItem>listView(controller.root(), "chat-list").getItems()));

        assertEquals(1, items.size());
        assertEquals(ChatScope.BROADCAST, items.get(0).scope());
        assertEquals(List.of(), items.get(0).audience());
    }

    @Test
    void theOldTwoArgumentCallbackStillRendersAsABroadcast() throws Exception {
        directory(1, AN, BINH);

        FxTestSupport.fx(() -> controller.onChatMessage("Binh", "hi"));

        List<ChatItem> items = FxTestSupport.onFx(() ->
                List.copyOf(FxTestSupport.<ChatItem>listView(controller.root(), "chat-list").getItems()));

        assertEquals(1, items.size(), "a listener written before addressing existed still works");
        assertEquals(ChatScope.BROADCAST, items.get(0).scope());
    }

    @Test
    void messagesArriveInTheOrderTheyWereSent() throws Exception {
        directory(1, AN, BINH);

        FxTestSupport.fx(() -> {
            controller.onChatMessage("Binh", "mot", ChatScope.BROADCAST, List.of());
            controller.onChatMessage("Cuong", "hai", ChatScope.BROADCAST, List.of());
            controller.onChatMessage("Binh", "ba", ChatScope.BROADCAST, List.of());
        });

        List<ChatItem> items = FxTestSupport.onFx(() ->
                List.copyOf(FxTestSupport.<ChatItem>listView(controller.root(), "chat-list").getItems()));

        assertEquals(List.of("mot", "hai", "ba"), items.stream().map(ChatItem::text).toList());
    }

    @Test
    void thePickerSurvivesAMessageArriving() throws Exception {
        directory(1, AN, BINH, CUONG);
        check("Binh", true);

        FxTestSupport.fx(() -> controller.onChatMessage("Cuong", "chao", ChatScope.BROADCAST, List.of()));

        List<ChatItem> items = FxTestSupport.onFx(() ->
                List.copyOf(FxTestSupport.<ChatItem>listView(controller.root(), "chat-list").getItems()));

        assertEquals(1, items.size());
        assertEquals("Binh", pickerLabel(), "a message must not disturb what is addressed next");
    }
}
