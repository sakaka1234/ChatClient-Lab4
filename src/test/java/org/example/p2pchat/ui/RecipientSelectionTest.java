package org.example.p2pchat.ui;

import org.example.p2pchat.protocol.ChatScope;
import org.example.p2pchat.session.ClientInfo;
import org.example.p2pchat.session.ChatSession;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The rule that turns the recipient picker's selection into an address, and the ids back into the
 * names shown on the bubble. Both are pure functions of the selection and the directory, so they are
 * tested here rather than through the JavaFX view.
 */
class RecipientSelectionTest {

    private static final List<ClientInfo> DIRECTORY = List.of(
            new ClientInfo(1, "An"),
            new ClientInfo(2, "Binh"),
            new ClientInfo(3, "Cuong"));

    // ------------------------------------------------------------------ scope

    @Test
    void nothingSelectedMeansBroadcast() {
        assertEquals(ChatScope.BROADCAST, MainController.scopeFor(List.of()));
    }

    @Test
    void oneClientSelectedIsAUnicast() {
        assertEquals(ChatScope.UNICAST, MainController.scopeFor(List.of(2)));
    }

    @Test
    void twoClientsSelectedAreAMulticast() {
        assertEquals(ChatScope.MULTICAST, MainController.scopeFor(List.of(1, 3)));
    }

    @Test
    void theWholeDirectorySelectedIsStillAMulticast() {
        // Ticking everyone is still an explicit group, not a broadcast: the two are sent alike today
        // but mean different things, and the receiver's label has to say which.
        assertEquals(ChatScope.MULTICAST, MainController.scopeFor(List.of(1, 2, 3)));
    }

    // ------------------------------------------------------------------ names

    @Test
    void resolvesTargetIdsToTheirNames() {
        assertEquals(List.of("Binh", "Cuong"), MainController.namesOf(DIRECTORY, List.of(2, 3)));
    }

    @Test
    void keepsTheOrderTheIdsWereGivenIn() {
        assertEquals(List.of("Cuong", "An", "Binh"), MainController.namesOf(DIRECTORY, List.of(3, 1, 2)));
    }

    @Test
    void fallsBackToTheRawIdWhenTheDirectoryNoLongerListsThatClient() {
        // The picker was drawn before the client left; the message still has to render.
        assertEquals(List.of("Binh", "#99"), MainController.namesOf(DIRECTORY, List.of(2, 99)));
    }

    @Test
    void anEmptySelectionHasNoNames() {
        assertEquals(List.of(), MainController.namesOf(DIRECTORY, List.of()));
    }

    @Test
    void anEmptyDirectoryStillRendersEveryId() {
        assertEquals(List.of("#4"), MainController.namesOf(List.of(), List.of(4)));
    }

    // -------------------------------------------------------- the picker

    @Test
    void thePickerNeverOffersYourOwnId() {
        assertEquals(List.of(new ClientInfo(2, "Binh"), new ClientInfo(3, "Cuong")),
                MainController.selectableClients(DIRECTORY, 1));
    }

    @Test
    void thePickerOffersEveryoneWhileYouAreStillUnidentified() {
        // A client has no id until its first directory arrives, and nothing is hidden meanwhile.
        assertEquals(DIRECTORY, MainController.selectableClients(DIRECTORY, ChatSession.UNASSIGNED_CLIENT_ID));
    }

    @Test
    void anEmptyRosterOffersNobody() {
        assertEquals(List.of(), MainController.selectableClients(List.of(), 1));
    }

    @Test
    void aSelectionSurvivesADirectoryThatStillContainsThoseClients() {
        Set<Integer> selected = new LinkedHashSet<>(List.of(2, 3));

        MainController.retainSelectable(DIRECTORY, selected, 1);

        assertEquals(Set.of(2, 3), selected);
    }

    @Test
    void aClientThatLeftIsDroppedFromTheSelection() {
        Set<Integer> selected = new LinkedHashSet<>(List.of(2, 3));

        MainController.retainSelectable(List.of(new ClientInfo(1, "An"), new ClientInfo(2, "Binh")),
                selected, 1);

        assertEquals(Set.of(2), selected);
    }

    @Test
    void aSelectionOfClientsThatAllLeftIsEmptied() {
        Set<Integer> selected = new LinkedHashSet<>(List.of(2, 3));

        MainController.retainSelectable(List.of(new ClientInfo(1, "An")), selected, 1);

        // Emptying the picker matters: the next send becomes a broadcast rather than a dead address.
        assertEquals(Set.of(), selected);
    }

    @Test
    void yourOwnIdIsPushedOutOfTheSelectionAsWell() {
        Set<Integer> selected = new LinkedHashSet<>(List.of(1, 2));

        MainController.retainSelectable(DIRECTORY, selected, 1);

        assertEquals(Set.of(2), selected);
    }
}
