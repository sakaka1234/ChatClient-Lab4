# Chat

A small desktop chat application built with Java 21, JavaFX, and raw TCP sockets. It is
a Network Programming course project: one server and any number of clients talk over raw TCP
connections, with no database and no accounts.

## 1. Purpose

Demonstrate the networking fundamentals of a client–server TCP application:

- Establishing a TCP connection in **SERVER** and **CLIENT** modes
- One server relaying between several connected clients, messenger-style bubbles
- **Addressed chat** at the application level: unicast (one client), multicast (a chosen group),
  broadcast (everyone) — the server replicates a message onto exactly the chosen sockets, the way a
  multicast router replicates one datagram onto the links that joined the group
- A client directory (`ROSTER`) so every client knows who is present and which id is its own
- A **relay log** on the server: who sent what, from which address, to whom, over how many sockets
- Graceful handling of disconnects, bad input, and network errors

File transfer was part of an earlier revision and has been removed; the application is chat-only.
See `docs/SPECS.md` §12 for what was dropped and why.

## 2. Architecture

```
+---------------------+              +-----------------------------+              +---------------------+
|      Client B       |              |           Server            |              |      Client C       |
|                     |              |    (relays, never chats)    |              |                     |
| MainController (FX) |              |    MainController (JavaFX)  |              | MainController (FX) |
|        |            |              |    client list / relay log  |              |        |            |
|   ChatSession       |              |   ChatSession   ClientReg.  |              |   ChatSession       |
|        |            |              |         |       {conn→name,  |              |        |            |
|   ChatManager       |              |   ChatManager      id}      |              |   ChatManager       |
|        |            |              |         |                   |              |        |            |
|   Connection        |  TCP socket  |    Connection × N           |  TCP socket  |   Connection        |
|   reader + writer   |<============>|    reader + writer each     |<============>|   reader + writer   |
|                     |              |    Acceptor(5000)           |              |                     |
+---------------------+              +-----------------------------+              +---------------------+
```

Every client has exactly one socket, and it is to the server. The server holds one socket per client
and is the only instance that *forwards*; because a client can never reach another client directly,
the topology is a star and stays loop-free.

The server also does **not chat**. It never draws a bubble, is never a unicast or multicast target,
and does not appear in the client directory — it exists to replicate. That is what makes addressing
possible: the server assigns each client a **16-bit client id** (`#1`, `#2`, `#3`, … in join order),
publishes a **roster** so every client knows who is present and which id is its own, and decides which
sockets a chat message leaves on. A client sends one `CHAT` to the server carrying an addressing block
(scope + target ids); the server copies it onto the sockets that address selects, and writes down what
it did. See [`docs/SPECS-UNICAST-MULTICAST.md`](docs/SPECS-UNICAST-MULTICAST.md) and
[`docs/PROTOCOL.md`](docs/PROTOCOL.md) §4.

## 3. Tech stack

| Layer | Choice |
|---|---|
| Language | Java 21 |
| UI | JavaFX 22.0.1 (controls only, built in code, no FXML) |
| Build | Maven (wrapper included) |
| Networking | `ServerSocket` / `Socket`, `DataInputStream` / `DataOutputStream` |
| Concurrency | one reader thread, one writer thread, `ArrayBlockingQueue` |
| Tests | JUnit 5 |

No Spring, no Netty, no external networking framework.

## 4. Project layout

```
src/main/java/org/example/p2pchat/
    Launcher.java                JVM entry point (plain class, avoids JavaFX launcher check)
    Main.java                    JavaFX Application subclass
    ui/MainController.java       the three views: connection form, server view, client view
    ui/ChatItem.java             one chat entry: text, direction, and how it was addressed
    ui/ChatItemTracker.java      keeps bubbles in order and notifies the view when they change
    ui/ChatBubbleFactory.java    renders a ChatItem as a left/right bubble
    session/ChatSession.java     connect/listen lifecycle, packet routing, client ids, roster, relay log
    session/ClientRegistry.java  registry of connected clients (connection -> display name)
    session/ClientInfo.java      one roster line: (id, name)
    session/SessionListener.java UI callback contract
    network/Acceptor.java        ServerSocket + accept
    network/Connector.java       Socket + connect
    network/Connection.java      framed read/write loops
    network/PacketSink.java      send abstraction used by chat code
    protocol/MessageType.java    message ids
    protocol/ChatScope.java      UNICAST / MULTICAST / BROADCAST
    protocol/Packet.java         packet factories and payload parsing (incl. the addressing block)
    protocol/PacketCodec.java    wire framing
    chat/ChatManager.java        CHAT send/handle
    util/NetworkUtils.java       port/address validation
    util/AppLogger.java          timestamped console logging

src/main/resources/org/example/p2pchat/ui/app.css
src/test/java/org/example/p2pchat/...  209 JUnit tests
docs/SPECS.md                    the original specification
docs/SPECS-UNICAST-MULTICAST.md  the unicast/multicast design and its delivery rules
docs/PROTOCOL.md                 the wire protocol in detail
docs/ARCHITECTURE.md             a full walkthrough of the code (in Vietnamese)
docs/HUONG-DAN-CODE.md           which file does what: server, connect, unicast/multicast/broadcast
```

## 5. How to run

The Maven wrapper downloads Maven on first use and works with any JDK 21+ on `PATH` / `JAVA_HOME`.

```powershell
# from the project root
.\mvnw.cmd clean javafx:run
```

Other useful commands:

```powershell
.\mvnw.cmd test      # run the 209 unit tests
.\mvnw.cmd package   # build the runnable fat jar: target/Chat.jar
```

If this machine's default JDK is not 21+, point at a 21 JDK first:

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-21"
.\mvnw.cmd javafx:run
```

### Running without Maven

`.\mvnw.cmd package` produces a self-contained `target/Chat.jar` (JavaFX included):

```powershell
java -jar target/Chat.jar
```

`run-chat.cmd` does the same and finds a JDK 21 for you. It takes an optional label that is only
echoed on screen, so four terminals are easy to tell apart:

```powershell
.\run-chat.cmd Server
.\run-chat.cmd An
```

### "Error: JavaFX runtime components are missing"

Do **not** launch `org.example.p2pchat.Main` directly with `java -cp`. `Main` extends
`javafx.application.Application`, and the JVM launcher refuses to start an `Application` subclass
when JavaFX is on the classpath instead of the module path. That is exactly this message.

Use one of these instead:

| Way to run | Command |
|---|---|
| Fat jar (recommended) | `java -jar target/Chat.jar` |
| Maven (module path handled for you) | `.\mvnw.cmd javafx:run` |
| Plain classpath | `java -cp <classpath> org.example.p2pchat.Launcher` |

`org.example.p2pchat.Launcher` is a plain class that calls `Application.launch(Main.class, args)`,
which sidesteps the launcher check. It is the entry point declared in `pom.xml`.

In IntelliJ, set the run configuration's main class to `org.example.p2pchat.Launcher` as well.

## 6. How to start the server

1. Launch the application: `.\mvnw.cmd javafx:run`
2. Keep the **SERVER** mode selected.
3. Set **Local port** (default `5000`).
4. Click **Start Server**.
5. Status shows `Listening on port 5000` and the window switches to the server view.

The server view never has a message box and never has a **To:** picker: a server has nothing to send.
It shows the port, the clients that are connected, and the relay log.

## 7. How to connect a client

1. Launch another instance of the same application.
2. Select **CLIENT**.
3. Set **Server IP** (default `127.0.0.1`) and **Server port** (`5000`).
4. Click **Connect**.
5. The window switches to the chat view and status shows `Connected`.

Repeat for as many clients as you like — the server accepts any number, and each new client is
announced to everyone through the roster. Your own messages appear on the right (messenger style) and
everyone else's on the left, labelled with the sender's name.

### Choosing who receives a message

Above the message box there is a **To:** picker listing every other client with a checkbox:

| Selection | Button reads | What it sends |
|---|---|---|
| nothing checked | `Everyone` | **broadcast** — every other client receives it |
| exactly one client | that client's name | **unicast** — only that client; the bubble is labelled `private` on their side |
| two or more clients | the selected names | **multicast** — exactly that group; labelled `group · An, Binh` |

The server is not in the picker — it is not a client, and it is never an address. Your own messages
are never sent back to you, whatever you select. If a client leaves while they are still ticked, it is
dropped from the selection when the roster changes, so the next message never addresses a client that
is gone. Tick nothing again to go back to `Everyone`.

The picker is rebuilt from the roster each time membership changes. The top bar keeps the name on
its left, on the role chip — `CLIENT · An` — so with four windows open you can tell at a glance
which one you are typing into. The window title carries the name too (`Chat — Client · An`).

### Chat in the session

- Pick recipients with **To:** (see above), then type and press Enter (Shift+Enter adds a newline).
  Your messages are right-aligned bubbles, the others are left-aligned and labelled with the sender's
  name.
- A directed message is labelled with how it was addressed: `→ An` on the sending side, `private` or
  `group · An, Binh` on the receiving side. Broadcasts have no such label.
- **Disconnect** in the top bar closes the session and returns to the connection screen.

### The server window and its relay log

The server's header reads `:5000 · Clients (2) · Relayed (17)`: the port it bound, how many clients are
connected, and how many messages it has copied onto at least one socket. **Stop Server** ends the
session and returns to the connection form.

Under it, the **Clients** panel lists the directory (`#1  An`, `#2  Binh`) and the **Relay log** records
every event, one line per event, oldest first:

```
+ An #1 @127.0.0.1:54321 joined
An #1 @127.0.0.1:54321  BROADCAST  -> everyone (2 delivered, 12 B)
Binh #2 @127.0.0.1:54402  UNICAST  -> An #1 (1 delivered, 7 B)
Cuong #3 @127.0.0.1:54418  MULTICAST  -> An #1, Binh #2 (2 delivered, 20 B)
An #1 @127.0.0.1:54321  UNICAST  -> #7 NOT CONNECTED (0 delivered, 5 B)
- Binh #2 @127.0.0.1:54402 left (1 client remains)
```

Each relay line names the sender (`name #id`, plus the `@ip:port` of the socket it arrived on — the
ephemeral port is what tells two clients on one machine apart), the scope in capitals, the recipients
it was copied to (`everyone` for a broadcast, `#id NOT CONNECTED` for an address nobody holds), how
many sockets it actually reached, and the size of the message.

The **text of a message is never written to the log**. The server relays; it does not read what it
forwards, and only the size is needed to show that the bytes crossed it. The same lines go to the
console through `AppLogger`, so a server started from a terminal is just as easy to watch.

## 8. How the TCP connection works

The server creates `new ServerSocket(port)` and blocks in `accept()` on a background thread. A client
creates `new Socket()` and calls `connect(new InetSocketAddress(host, port), timeout)`; the operating
system assigns an ephemeral local port automatically, so the client never picks one.

The accepted/connected `Socket` is wrapped in `Connection`, which starts exactly two threads:

- **reader** — loops on `PacketCodec.read(inputStream)` and hands each `Packet` to a listener
- **writer** — takes `Packet`s from a bounded queue and writes them with `PacketCodec.write`

A single TCP connection carries chat, the client directory, and control messages. No second connection
is opened for the reverse direction.

## 9. Protocol format

Every message is one frame on the stream:

```
+--------+------------------+-------------------+
| type   | payload length   | payload           |
| 1 byte | 4 bytes big-end | N bytes           |
+--------+------------------+-------------------+
```

Types: `CHAT=1`, `DISCONNECT=2`, `HELLO=3`, `RELAY=4`, `ROSTER=5` — protocol v5. Ids are stable, and
an id is never reused for a different kind; v5 compressed the numbering once, when the `FILE_*` types
were removed.

A `CHAT` payload is an **addressing block** (`u8 scope`, `u16 target count`, `count × u16 client id`)
followed by the UTF-8 text. `RELAY` carries the original sender's name and then the same addressing
block, so a forwarded message keeps the address it was sent with; `ROSTER` carries the receiving
client's own id plus the directory.

Length framing lets the receiver tell where one message ends and the next begins. The full layout,
validation rules, delivery rules, and byte examples are in [`docs/PROTOCOL.md`](docs/PROTOCOL.md).

## 10. Chat implementation

`ChatManager.sendMessage(text, scope, targets)` wraps the text and its address as `Packet.chat(...)`
and pushes it through the connection's send queue. Incoming `CHAT` / `RELAY` packets are routed by
`ChatSession`: a client has one socket, so it hands the packet straight to `ChatManager.handle`, while
the server resolves the address against its directory and copies the message onto the sockets it
selects (`resolve` drops the sender from every destination list, and only the server ever relays).
`ChatManager.handle` normalises both packet kinds into `(sender, text, scope, targets)` and reports
them to the UI, which resolves target ids to names from its directory. Empty or whitespace-only
messages are ignored, and an address that no longer exists is logged as `NOT CONNECTED` and dropped
rather than delivered to everyone.

## 11. Concurrency

- Socket reads and writes never run on the JavaFX Application Thread.
- `Connection` runs one reader and one writer thread; the send queue is a bounded
  `ArrayBlockingQueue` (capacity 256) so a slow client cannot exhaust memory and `send` fails loudly
  after 30 s instead of growing without bound.
- The server accepts on a background thread (`chat-accept`) and keeps accepting, one `PacketRouter` per
  client, so a new client never blocks an existing one. An empty client list is not the end of a
  server's session — it keeps listening.
- Every UI mutation is dispatched through the listener callback, which `MainController` marshals with
  `Platform.runLater`.
- Listener exceptions are caught and logged so a UI bug cannot kill the networking threads, and a
  malformed packet is dropped by the router rather than killing the reader thread.

## 12. Localhost testing

Run everything on one machine first:

```
Instance A → SERVER → port 5000
Instance B → CLIENT → 127.0.0.1:5000
Instance C → CLIENT → 127.0.0.1:5000
```

Test checklist:

- **Connection** — A listens on `5000`, B and C connect, both show `Connected`; A's window lists
  `#1  B`, `#2  C` and never has a message box.
- **Chat** — send multiple messages B→C and C→B; every message shows the sender's name.
- **Unicast** — B ticks only `C` and sends: only C shows it, A shows nothing (no bubble ever appears
  in the server window).
- **Multicast** — add a fourth client D, have B tick `C` and `D` and send: C and D show it, nobody
  else.
- **Broadcast** — B ticks nothing (`Everyone`) and sends: everyone but B shows it, B does not get an
  echo of its own message.
- **Relay log** — A's log shows one line per message with the sender's `@ip:port`, the scope in
  capitals, the recipients and the socket count; unicast, multicast and broadcast lines are visibly
  different.
- **Directory** — connect a fourth instance and confirm it appears in every `To:` picker; close it and
  confirm it disappears again, and that A logs the leave.
- **Failures** — wrong port, no server running (`Connection failed`), disconnect from either side,
  and **Stop Server** while clients are connected.
- **Responsiveness** — have one client send a long burst of messages and confirm both windows stay
  interactive.

The JUnit suite (`.\mvnw.cmd test`) covers the protocol byte layout, the codec, the connection, the
chat manager, the client directory and addressing, the relay log, and full multi-session round trips
over real localhost sockets.

## 13. Testing on two LAN computers

No code changes are needed. Only the address changes:

1. Server computer: choose SERVER and start it, then note its LAN IP, for example `192.168.1.10`.
2. Second computer: choose CLIENT and set **Server IP** = `192.168.1.10`, **Server port** = `5000`.
3. Allow the Java process through the server machine's firewall if prompted.

A client uses an ephemeral local port, so nothing else needs configuring. NAT traversal is out of
scope and intentionally not implemented.

## 14. Definition of done

- [x] Same application can be launched several times on one computer
- [x] Instance A can act as the server, and B, C can connect to it via `127.0.0.1:5000`
- [x] The server accepts any number of clients and keeps listening when the last one leaves
- [x] The server relays and never chats: no bubble, no message box, no address of its own
- [x] Chat can be unicast to one client, multicast to a chosen group, or broadcast to everyone
- [x] The server copies selectively — a message never reaches a client the sender did not address
- [x] A sender never receives its own message back
- [x] Every client learns its own id and the live directory from the roster
- [x] The server logs each relay with the sender's address, the scope, the recipients and the size
- [x] Chat is rendered as messenger-style left/right bubbles, labelled with how it was addressed
- [x] The GUI stays responsive while messages stream in
- [x] Disconnects are handled gracefully
- [x] Invalid input does not crash the app
- [x] Maven builds the project
- [x] README documents architecture and protocol
