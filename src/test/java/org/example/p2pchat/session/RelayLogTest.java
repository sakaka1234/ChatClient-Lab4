package org.example.p2pchat.session;

import org.example.p2pchat.protocol.ChatScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the server writes down as it relays.
 *
 * <p>The server is the replication point, so its log is the evidence that unicast, multicast and
 * broadcast really take different paths: every line names the sender and its socket, the scope, the
 * clients the message was copied to, how many sockets it reached and how big it was. The message
 * text itself is deliberately absent — a relay does not read what it forwards.
 */
@Timeout(60)
class RelayLogTest {

    private static final long WAIT_SECONDS = 15;

    private static final Pattern SUMMARY = Pattern.compile("\\((\\d+) delivered, (\\d+) B\\)$");

    private ChatSession server;
    private ChatSession an;
    private ChatSession binh;
    private ChatSession cuong;

    @AfterEach
    void tearDown() {
        closeQuietly(cuong);
        closeQuietly(binh);
        closeQuietly(an);
        closeQuietly(server);
    }

    private static void closeQuietly(ChatSession session) {
        if (session != null) {
            session.close();
        }
    }

    /** A listener that keeps the server's log lines, and nothing else. */
    private static final class Recorder implements SessionListener {
        final BlockingQueue<String> log = new ArrayBlockingQueue<>(128);

        @Override
        public void onStatusChanged(String status) {
        }

        @Override
        public void onChatMessage(String sender, String message) {
        }

        @Override
        public void onDisconnected(String reason) {
        }

        @Override
        public void onServerLog(String message) {
            log.add(message);
        }

        String awaitLog() throws InterruptedException {
            String line = log.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertTrue(line != null, "expected a log line");
            return line;
        }

        List<String> drain() {
            List<String> lines = new ArrayList<>();
            log.drainTo(lines);
            return lines;
        }
    }

    /** Server plus two clients, joined one at a time so the numbering is fixed: An #1, Binh #2. */
    private void startTrio(Recorder serverRec) throws Exception {
        server = new ChatSession(serverRec, "Server");
        server.startServer(0);
        an = joinAs("An", "An");
        binh = joinAs("Binh", "An", "Binh");
    }

    /** Dials in a client and waits until the server's directory lists it, and everyone before it. */
    private ChatSession joinAs(String name, String... present) throws Exception {
        ChatSession client = new ChatSession(new Recorder(), name);
        client.startClient("127.0.0.1", server.port(), 5000);
        awaitDirectory(present);
        return client;
    }

    private void awaitDirectory(String... names) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!namesPresent(names)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("directory never listed " + List.of(names)
                        + ", last was " + server.clients());
            }
            Thread.sleep(20);
        }
    }

    private boolean namesPresent(String[] names) {
        List<String> present = server.clients().stream().map(ClientInfo::name).toList();
        for (String name : names) {
            if (!present.contains(name)) {
                return false;
            }
        }
        return true;
    }

    private int idOf(String name) {
        for (ClientInfo client : server.clients()) {
            if (client.name().equals(name)) {
                return client.id();
            }
        }
        throw new AssertionError("no client named " + name + " in " + server.clients());
    }

    // ------------------------------------------------------------------ join

    @Test
    void aJoinIsLoggedWithTheClientsAddress() throws Exception {
        Recorder serverRec = new Recorder();
        startTrio(serverRec);

        List<String> joins = serverRec.drain();
        assertEquals(2, joins.size(), "one line per client that joined: " + joins);
        assertMatches("^\\+ An #1 @127\\.0\\.0\\.1:\\d+ joined$", joins.get(0));
        assertMatches("^\\+ Binh #2 @127\\.0\\.0\\.1:\\d+ joined$", joins.get(1));
    }

    @Test
    void twoClientsOnOneMachineAreToldApartByTheirPort() throws Exception {
        Recorder serverRec = new Recorder();
        startTrio(serverRec);

        List<String> joins = serverRec.drain();
        String anAddress = addressIn(joins.get(0));
        String binhAddress = addressIn(joins.get(1));
        assertEquals("127.0.0.1", hostOf(anAddress), "both clients run on this machine");
        assertFalse(anAddress.equals(binhAddress),
                "the ephemeral port is what tells two clients on one machine apart");
    }

    @Test
    void aLeaveIsLoggedWithTheNumberOfClientsLeft() throws Exception {
        Recorder serverRec = new Recorder();
        startTrio(serverRec);
        serverRec.drain();

        binh.close();
        binh = null;

        assertMatches("^- Binh #2 @127\\.0\\.0\\.1:\\d+ left \\(1 client remains\\)$",
                awaitLeaveLine(serverRec));
    }

    @Test
    void theLastLeaveSaysTheDirectoryIsEmpty() throws Exception {
        Recorder serverRec = new Recorder();
        startTrio(serverRec);
        serverRec.drain();

        binh.close();
        binh = null;
        awaitLeaveLine(serverRec);
        an.close();
        an = null;

        assertMatches("^- An #1 @127\\.0\\.0\\.1:\\d+ left \\(0 clients remain\\)$",
                awaitLeaveLine(serverRec));
    }

    private static String awaitLeaveLine(Recorder rec) throws InterruptedException {
        String line = rec.awaitLog();
        assertTrue(line.startsWith("- "), "expected a leave line, got: " + line);
        return line;
    }

    // ----------------------------------------------------------------- relay

    @Test
    void aUnicastIsLoggedWithItsScopeRecipientAndSize() throws Exception {
        Recorder serverRec = new Recorder();
        startTrio(serverRec);
        serverRec.drain();

        // Vietnamese text, so the size reported has to be the UTF-8 payload, not the character count.
        String text = "chào bạn";
        an.sendChat(text, ChatScope.UNICAST, List.of(idOf("Binh")));

        String line = serverRec.awaitLog();
        assertMatches("^An #1 @127\\.0\\.0\\.1:\\d+  UNICAST  -> Binh #2 \\(1 delivered, \\d+ B\\)$", line);
        assertEquals(1, deliveredSockets(line));
        assertEquals(text.getBytes(StandardCharsets.UTF_8).length, deliveredBytes(line));
    }

    @Test
    void aMulticastNamesEveryRecipientItReached() throws Exception {
        Recorder serverRec = new Recorder();
        startTrio(serverRec);
        cuong = joinAs("Cuong", "An", "Binh", "Cuong");
        serverRec.drain();

        an.sendChat("hop nhom", ChatScope.MULTICAST,
                List.of(idOf("Binh"), idOf("Cuong")));

        String line = serverRec.awaitLog();
        assertMatches("^An #1 @127\\.0\\.0\\.1:\\d+  MULTICAST  -> Binh #2, Cuong #3"
                + " \\(2 delivered, \\d+ B\\)$", line);
        assertEquals(2, deliveredSockets(line));
    }

    @Test
    void aBroadcastSaysEveryoneRatherThanListingThem() throws Exception {
        Recorder serverRec = new Recorder();
        startTrio(serverRec);
        serverRec.drain();

        an.sendChat("ca nha oi", ChatScope.BROADCAST, List.of());

        String line = serverRec.awaitLog();
        assertMatches("^An #1 @127\\.0\\.0\\.1:\\d+  BROADCAST  -> everyone \\(1 delivered, \\d+ B\\)$",
                line);
        assertEquals(1, deliveredSockets(line), "everyone except the sender: Binh only");
    }

    @Test
    void anAddressNobodyHoldsIsLoggedAsNotConnected() throws Exception {
        Recorder serverRec = new Recorder();
        startTrio(serverRec);
        serverRec.drain();

        an.sendChat("gui cho ai do", ChatScope.UNICAST, List.of(99));

        String line = serverRec.awaitLog();
        assertMatches("^An #1 @127\\.0\\.0\\.1:\\d+  UNICAST  -> #99 NOT CONNECTED"
                + " \\(0 delivered, \\d+ B\\)$", line);
        assertEquals(0, deliveredSockets(line));
    }

    @Test
    void aGroupThatNamesNobodyConnectedIsListedOneByOne() throws Exception {
        Recorder serverRec = new Recorder();
        startTrio(serverRec);
        serverRec.drain();

        an.sendChat("khong ai", ChatScope.MULTICAST, List.of(97, 98));

        assertMatches("^An #1 @127\\.0\\.0\\.1:\\d+  MULTICAST  -> #97 NOT CONNECTED, #98 NOT CONNECTED"
                + " \\(0 delivered, \\d+ B\\)$", serverRec.awaitLog());
    }

    @Test
    void theMessageTextIsNeverWrittenDown() throws Exception {
        Recorder serverRec = new Recorder();
        startTrio(serverRec);
        serverRec.drain();

        an.sendChat("mat khau la hunter2", ChatScope.BROADCAST, List.of());
        serverRec.awaitLog();

        for (String line : serverRec.drain()) {
            assertFalse(line.contains("hunter2"), "the relay log must not carry message text: " + line);
        }
    }

    @Test
    void theRelayCounterCountsWhatActuallyWentOut() throws Exception {
        Recorder serverRec = new Recorder();
        startTrio(serverRec);
        serverRec.drain();
        assertEquals(0, server.relayedCount());

        an.sendChat("mot", ChatScope.BROADCAST, List.of());
        serverRec.awaitLog();
        assertEquals(1, server.relayedCount());

        an.sendChat("hai", ChatScope.UNICAST, List.of(idOf("Binh")));
        serverRec.awaitLog();
        assertEquals(2, server.relayedCount());

        an.sendChat("ba", ChatScope.UNICAST, List.of(99));
        serverRec.awaitLog();
        assertEquals(2, server.relayedCount(),
                "a message that reached no socket was not relayed anywhere");
    }

    @Test
    void aClientLogsNothingAtAll() throws Exception {
        Recorder serverRec = new Recorder();
        Recorder anRec = new Recorder();
        server = new ChatSession(serverRec, "Server");
        server.startServer(0);
        an = new ChatSession(anRec, "An");
        an.startClient("127.0.0.1", server.port(), 5000);
        awaitDirectory("An");

        an.sendChat("chi rieng toi", ChatScope.BROADCAST, List.of());
        serverRec.awaitLog();

        assertEquals(List.of(), anRec.drain(),
                "only the server relays, so only the server has a relay log");
    }

    // ---------------------------------------------------------------- helpers

    private static void assertMatches(String pattern, String line) {
        assertTrue(Pattern.matches(pattern, line),
                "expected the line to match " + pattern + " but it was: " + line);
    }

    private static int deliveredSockets(String line) {
        return Integer.parseInt(summary(line).group(1));
    }

    private static int deliveredBytes(String line) {
        return Integer.parseInt(summary(line).group(2));
    }

    private static Matcher summary(String line) {
        Matcher matcher = SUMMARY.matcher(line);
        assertTrue(matcher.find(), "no delivery summary at the end of: " + line);
        return matcher;
    }

    /** The {@code @host:port} part of a join or leave line. */
    private static String addressIn(String line) {
        Matcher matcher = Pattern.compile("@([^ ]+)").matcher(line);
        assertTrue(matcher.find(), "no address in: " + line);
        return matcher.group(1);
    }

    private static String hostOf(String address) {
        return address.substring(0, address.lastIndexOf(':'));
    }
}
