> **Implementation decisions (approved):** this specification was implemented with the following
> choices locked in. See `README.md` and `PROTOCOL.md` for the shipped behaviour.
>
> | Topic | Decision |
> |---|---|
> | Base package | `org.example.p2pchat` (§6), `Main.java` moved to `org.example.p2pchat` |
> | JDK | 21 via Maven Wrapper `mvnw`; `.mvn/wrapper/maven-wrapper.properties` committed |
> | JavaFX | pinned `22.0.1`, UI built in code (no FXML) |
> | Entry point | `org.example.p2pchat.Launcher` (plain class) so classpath/fat-jar launches do not hit "JavaFX runtime components are missing"; `Main` stays the `Application` subclass |
> | Packaging | `maven-assembly-plugin` builds runnable `target/Chat.jar` |
> | Chat UI | messenger-style bubbles: client messages left, own messages right (`ChatItem`, `ChatItemTracker`, `ChatBubbleFactory`); the server window has no chat view at all |
> | Protocol version | v5: `CHAT`/`RELAY` carry an addressing block, `ROSTER=5` carries the client directory; every instance must run the same build |
> | File transfer | **removed in v5** at the user's request: the `FILE_*` packet types, the `file/` package, image preview and the transfer UI are gone. The application is chat-only — see §12 |
> | Chat addressing | application-level unicast / multicast / broadcast (`ChatScope`); full design and delivery rules in `docs/SPECS-UNICAST-MULTICAST.md` |
> | Client identity | the server assigns 16-bit client ids in join order: `#1`, `#2`, `#3`, ...; the server itself has no id, and `0` means "unassigned" |
> | Directory | the server pushes `ROSTER` (`selfId` + `(id, name)` list) to every client whenever membership or a name changes; it never lists itself |
> | Recipient selection | UI `To:` picker: nothing ticked → `BROADCAST`, one → `UNICAST`, two or more → `MULTICAST`; the UI prunes ids of clients that left |
> | Replication point | only the server forwards, and only onto the sockets the address names (`ChatSession.resolve`); clients never forward |
> | Self-echo | a sender never receives its own message back — the UI already drew the outbound bubble |
> | Unknown target | logged in the relay log as `#N NOT CONNECTED` and dropped; the message is never re-routed to everyone |
> | Malformed addressing | rejected while parsing (`IllegalStateException`) and dropped by the router, so a bad packet cannot kill a reader thread |
> | Server relay log | one line per event on the server: joins and leaves with the client's `@ip:port`, and one line per relayed message naming the sender, the scope, the recipients, the sockets reached and the payload size — never the message text |
| Tests | JUnit 5, `.\mvnw.cmd test` (209 tests) |
> | UI tests | the JavaFX toolkit is started from the tests themselves (`Platform.startup`), so the picker, the chat list, the server's client list and its relay log are driven as real controls — no TestFX, no extra dependency, no window shown |
> | `PING` / `PONG` | intentionally not implemented; liveness via TCP EOF/IOException |
> | `DISCONNECT` | explicit packet, distinct from abrupt socket close in logs, same final UI state |
> | Logging | timestamped `[INFO]`/`[WARN]`/`[ERROR]` to console via `AppLogger` |
> | Input validation | port `1..65535`, IP resolved before connecting, errors shown inline |
> | Concurrency | one reader thread + one writer thread + bounded send queue (256) |
> | Protocol doc | `docs/PROTOCOL.md` |

---

# Chat — Project Specification

> **Note on this revision.** The original brief also asked for file transfer (its §11–§14 and §17,
> plus items 5–7 and 10 of its definition of done). That feature was **removed from the
> implementation** at the user's request, so it is recorded in §12 as what was dropped and why.
>
> The brief also listed **"Central server"** under *Do NOT implement* in §1, and the first shipped
> revision honoured that: the instances were peers of one another, and the relay was an ordinary
> peer that happened to hold every socket. It has since been turned into an explicit **client–server**
> application at the user's request — see §1 and §5 — because that is what the relay always was.
> The addressing model did not change: unicast, multicast and broadcast are still decided by the
> relay, which is now plainly a server rather than a participant. The other entries in that list
> still stand: no accounts, no database, no backend tier, no *separate* directory service.
>
> Everything else describes the application as it is today: a chat-only client and server on
> protocol v5.

## 1. Project Goal

Build a simple desktop chat application for a Network Programming course.

A session is one **server** plus one or more **clients**; the server holds a socket to every client,
and a client holds exactly one socket, to the server. The server **relays and does not chat**: it
never draws a bubble, is never a unicast or multicast target, and is absent from the client
directory. One server and two clients is the smallest topology in which the three addressing scopes
are distinguishable, so that is the target to build and demo against — §3.

Core features:

- TCP connections: the server listens, each client dials in
- Bidirectional text chat between any two clients, several at a time
- **Addressed chat at the application level**, the three classic delivery scopes:
  - **unicast** — one client,
  - **multicast** — a chosen group of clients,
  - **broadcast** — every other client
- A **client directory** so each client knows who is present and which id is its own
- A **relay log** on the server: sender and its address, scope, recipients, sockets reached, size
- Connection/disconnection handling

The project should focus on demonstrating networking concepts rather than authentication or backend
infrastructure.

Do NOT implement:

- Login / registration
- Database
- ~~Central server~~ — **deliberately added** at the user's request; see the note above. What the
  list ruled out was a *separate* backend tier with accounts and storage. There is still no such
  tier: the server here is the chat relay itself, in the same application and the same process
- REST API
- User accounts
- **Named, server-managed chat rooms** — multicast here is an ad-hoc recipient set chosen at send
  time, not a room you join and leave
- Cloud storage
- STUN/TURN/ICE
- NAT traversal
- Complex peer discovery (no broadcast-on-LAN beacon, and no separate directory service — the chat
  server's roster is the only directory, it is pushed rather than queried, and it lives nowhere but
  in memory)

Use a single repository and a single application.

Every instance runs the exact same application; SERVER and CLIENT are modes of it, not two programs.

---

# 2. Tech Stack

Use:

- Java 21
- JavaFX
- Maven
- TCP Socket / ServerSocket
- Java standard library for networking (`java.net`, `java.io` data streams)

Prefer Java NIO where it makes sense, but keep the implementation simple and understandable for a university Network Programming project.

Do NOT use Spring Boot or heavy networking frameworks.

---

# 3. IMPORTANT: Development and Testing Strategy

Implement and test the application on localhost FIRST.

Do not design the initial implementation around multiple physical machines.

The first target is one server and **two** clients, all on the same computer:

    Instance A (Server)           127.0.0.1:5000
        ^              ^
        | TCP          | TCP
        |              |
    Instance B      Instance C
    ephemeral       ephemeral
    local port      local port

Three instances, not two: with only two clients a multicast of two and a broadcast produce identical
traffic, so the addressing cannot be tested. A third client is what makes "exactly this group"
falsifiable.

All instances are the same application running on the same computer.

## Server

Instance A starts in SERVER mode:

    IP: 127.0.0.1
    Port: 5000

It creates:

    ServerSocket(5000)

and keeps accepting — the server serves every client that connects, not just one, and it goes on
accepting after the last of them leaves. It has no message box and no recipient picker: it relays.

## Client

Instances B and C start in CLIENT mode:

    Server IP: 127.0.0.1
    Server Port: 5000

Each creates a Socket and connects to the server.

Example:

    new Socket("127.0.0.1", 5000)

The connecting client does NOT need to manually specify a local port. The operating system can assign an
ephemeral local port automatically.

---

# 4. Future LAN Testing

Do NOT implement special LAN functionality.

The networking code should naturally support connecting to a server on another machine by IP.

After the localhost version works, the developer/user may manually test on several physical machines by changing only the server address.

Example:

Server computer:

    192.168.1.10:5000

Other computers connect to:

    192.168.1.10:5000

The application should not require architectural changes for this.

The user will handle changing the server IP and LAN testing manually later.

Do not implement NAT traversal.

---

# 5. Application Architecture

Use a single application.

Each application instance can operate in one of two modes:

## Server Mode

1. User enters a port.
2. Application creates ServerSocket.
3. Application keeps accepting incoming connections (several, and again after the last one leaves).
4. The window switches to the server view: the client list and the relay log.

## Client Mode

1. User enters the server IP.
2. User enters the server port.
3. Application creates a Socket.
4. Application connects to the server.
5. Once connected, chat becomes available.

The two roles are **not** equal, and the difference must be explicit rather than accidental. A client
chats; a server relays chat and never takes part in it. There is no state in which the server is a
chat participant.

| | Server | Client |
|---|---|---|
| Sockets | one per client | one, to the server |
| Chat | never sends, never receives, never draws a bubble | sends and receives |
| Addresses | has no id of its own; assigns the client ids, and is never a target | learns its own id from the roster |
| Directory | builds the roster and pushes it; never lists itself | consumes it |
| Forwarding | **replicates** each message onto the sockets the address names | never forwards |
| Window | client list + relay log; no message box, no `To:` picker | chat list + `To:` picker + message box |

Every client must be able to:

- Send chat, to one client, to a chosen group, or to everyone
- Receive chat, and know whether it was addressed to it alone or to a group
- See who else is present and which client id is its own

Because a client has only one socket, it cannot address anyone by itself — it sends one `CHAT`
carrying an address and lets the server decide which sockets that message leaves on. Only the server
forwards, which is what keeps the topology loop-free. The full design and delivery rules are in
`docs/SPECS-UNICAST-MULTICAST.md`.

---

# 6. Suggested Project Structure

Use a clean structure similar to:

src/main/java/org/example/p2pchat/

    Launcher.java
    Main.java

    ui/
        MainController.java
        ChatItem.java
        ChatItemTracker.java
        ChatBubbleFactory.java

    network/
        Acceptor.java
        Connector.java
        Connection.java
        PacketSink.java

    protocol/
        MessageType.java
        ChatScope.java
        Packet.java
        PacketCodec.java

    session/
        ChatSession.java
        ClientRegistry.java
        ClientInfo.java
        SessionListener.java

    chat/
        ChatManager.java

    util/
        NetworkUtils.java

The exact structure can be adjusted if there is a better simple design.

Avoid unnecessary abstraction.

---

# 7. GUI

Create a simple, clean JavaFX interface.

Initial screen:

--------------------------------
            CHAT
--------------------------------

Display name: [ An         ]

[ SERVER ]   [ CLIENT ]

Local port:            (SERVER)
[ 5000 ]

Server IP:             (CLIENT)
[ 127.0.0.1 ]
Server Port:
[ 5000 ]

[ Start Server / Connect ]

Status:
Disconnected

--------------------------------

After a client connects:

--------------------------------
            CHAT
--------------------------------

Status: Connected to 127.0.0.1:5000 · 2 clients · you are #1

--------------------------------
Chat

An: Hello
You: Hi
You: riêng cho An            → An

--------------------------------

To: [ Everyone v ]

[ Type message...             ]

[ Send ]

--------------------------------

[ Disconnect ]

--------------------------------

After the server starts — a server relays, so there is nothing to type:

--------------------------------
            CHAT   SERVER
--------------------------------

:5000 · Clients (2) · Relayed (17)   [ Stop Server ]

Clients              Relay log

#1  An               + An #1 @127.0.0.1:54321 joined
#2  Binh             An #1 @127.0.0.1:54321  UNICAST  -> Binh #2 (1 delivered, 7 B)
                     - An #1 @127.0.0.1:54321 left (1 client remains)

--------------------------------

The **To:** control is the addressing picker. It lists every other client with a checkbox and turns the
selection into one of the three scopes:

| Selection | Button reads | Scope sent |
|---|---|---|
| nothing checked | `Everyone` | `BROADCAST` — every other client |
| exactly one client | that client's name | `UNICAST` — only that client |
| two or more clients | the selected names, e.g. `An, Binh` | `MULTICAST` — exactly that group |

The server is not in it — it is not a client and never an address. It is rebuilt from the client
directory every time membership changes, and ids of clients that left are dropped from the selection. An outbound directed message is labelled with where it went (`→ An`); the
receiver sees `private` or `group · An, Binh`. The server's relay log records the same message from
the middle: the sender and the `@ip:port` it arrived on, the scope, the recipients it was copied to,
how many sockets it reached and how big it was — never the text, because a relay does not read what
it forwards. A message you sent yourself never comes back to you.

The GUI should remain simple.

Do not spend excessive time on UI styling.

---

# 8. TCP Communication

Use TCP sockets for all communication.

The connection should support bidirectional communication.

Conceptually:

             Server
            /      \
          TCP      TCP
          /          \
      Client        Client

Every connection is a star arm to the server, not a mesh between clients.

Both directions must work.

Do not create a separate connection for sending and receiving unless there is a strong technical reason.

A single TCP connection should support:

- Chat (including a message the server forwards on someone else's behalf)
- The client directory, which is what tells a client who it can address
- Connection control messages

Two sockets are never opened between the same pair of instances.

---

# 9. Application-Level Protocol

Do not send arbitrary raw strings without framing.

Create a simple application-level packet protocol.

Each packet should contain at least:

    Packet Type
    Payload Length
    Payload

Possible packet types:

    CHAT
    HELLO
    RELAY
    ROSTER
    DISCONNECT

A chat message must be able to say **who it is for**, not just what it says. The address travels in
the packet itself: a scope (`UNICAST` / `MULTICAST` / `BROADCAST`) followed by the target client ids —
an *addressing block*. `RELAY` carries the original sender's name and then the very same block, so a
forwarded message keeps the address it was sent with.

The exact binary format is up to the implementation.

Document the protocol clearly.

The protocol must allow the receiver to determine where one message ends and the next begins.

A malformed packet — an unknown scope, a target count that runs past the end of the payload — must be
dropped without killing the connection's reader thread.

---

# 10. Chat

When the user sends:

    Hello

the application creates a CHAT packet holding the text **and the address it is going to** — see §11.

The receiver:

1. Reads the packet.
2. Decodes it.
3. Extracts the message, and how it was addressed.
4. Displays it in the JavaFX chat area, labelled with the sender and the scope.

Every client must be able to send messages at any time; a server never sends one.

Receiving must happen independently from the JavaFX UI thread.

---

# 11. Unicast, multicast and broadcast

This is the heart of the project — the three delivery scopes, implemented at the application level.
TCP has no multicast (a socket is point-to-point), so the server does the replication: it copies one
incoming message onto exactly the sockets the address names, the way a multicast router replicates one
datagram onto the links that joined the group.

| Scope | Sender → | Recipients |
|---|---|---|
| **Unicast** | one client | exactly one |
| **Multicast** | a chosen group | exactly that group |
| **Broadcast** | everyone | every other client |

Rules the implementation must hold to:

1. Each recipient gets exactly one `RELAY` packet, carrying the original scope and targets.
2. The **sender never receives its own message back** — the sender's UI already drew the outbound
   bubble. An echo would duplicate it.
3. Targets that are no longer connected are logged and dropped; the message still reaches the rest of
   the addressees and is **never** silently re-routed to everyone.
4. Addressing yourself reaches nobody — the server drops the source from every destination list.
   The server itself cannot be addressed at all: it has no id, so no target can ever name it.
5. Clients never forward. Only the server replicates, which is what keeps the topology loop-free.
6. The receiver learns the scope from the packet and resolves the ids to names against its own
   directory, so "sent to you alone" and "sent to An, Binh" render correctly on the receiving side
   even though the bytes arrived from the server.

## Client identity and the directory

Addressing needs addresses. The server assigns each client a 16-bit **client id** in join order —
`#1`, `#2`, `#3`, ...; `0` means "not assigned yet" and is never a valid target. The server has no id
at all, because it is not an address: no message can be sent to it.

A client has one socket and cannot see who else is connected, so the server maintains the
**directory** of `(id, name)` pairs and pushes it to every client with a `ROSTER` packet whenever
membership or a display name changes. `ROSTER` carries each receiver's own id at the front, so every
client learns which entry is itself, and the server never writes a line for itself into it. Ids are
per-session; restarting the server renumbers everyone, and nothing persists.

---

# 12. File Transfer — removed

The brief (§11–§14 and §17 of the original text: streaming send, `FILE_START` / `FILE_CHUNK` /
`FILE_END`, receiving into `received/`, a progress bar, and filename sanitising) described a
file-transfer feature that was **implemented and then deliberately removed** at the user's request.

What that means for the shipped application:

- There are no `FILE_*` packet types. `MessageType` is exactly `CHAT`, `DISCONNECT`, `HELLO`,
  `RELAY`, `ROSTER` (protocol v5 — the ids were renumbered compactly when the file types went away).
- There is no `file/` package, no `FileTypes`, no image preview, no `received/` directory, and no
  file UI (no *Send File* button, no Accept/Decline, no progress bar, no transfer log).
- Nothing is written to disk by the application, so there is no filename to sanitise and no path
  traversal to defend against — the whole class of bugs is gone with the feature.

A file-transfer design that would fit the current addressing model (relaying chunks through the
server, with per-hop id translation) is noted in `docs/SPECS-UNICAST-MULTICAST.md` §7 as the extension that
would be needed; it is not part of this build.

---

# 13. Concurrency

The GUI must remain responsive during:

- Connection
- Receiving messages
- A burst of messages from several clients

Do NOT perform blocking socket operations on the JavaFX Application Thread.

Use:

- ExecutorService
- Background threads
- or another simple concurrency mechanism

A receiving loop should continuously listen for incoming packets without blocking the UI.

Avoid creating an unlimited number of threads.

---

# 14. Connection Management

Handle:

- Successful connection
- Connection refused
- Invalid IP
- Invalid port
- Port already in use
- Client disconnect
- Unexpected socket closure
- Network errors

Display clear status messages.

Examples:

    Listening on port 5000

    Connected to 127.0.0.1:5000

    Connection failed

    Client disconnected

    Server disconnected

The application should not crash because a client, or the server, disconnects unexpectedly. A server
must also survive its *last* client leaving: an empty client list is not the end of its session.

---

# 15. Logging

Add simple logging for networking/debugging.

Examples:

    [INFO] Listening on port 5000
    [INFO] Client connected: 127.0.0.1
    [INFO] Sending BROADCAST CHAT packet
    [INFO] Receiving UNICAST CHAT packet
    [INFO] Client sent DISCONNECT
    [INFO] Relay: An #1 @127.0.0.1:54321  UNICAST  -> Binh #2 (1 delivered, 7 B)

Do not spam logs unnecessarily.

---

# 16. Testing Requirements

First test everything using SEVERAL INSTANCES on the SAME MACHINE — one server (`A`) and at least two
clients (`B`, `C`).

### Connection

    Instance A → SERVER → 127.0.0.1:5000

    Instance B → CLIENT → 127.0.0.1:5000

    Instance C → CLIENT → 127.0.0.1:5000

### Chat

Test:

    A → B, B → A, B → C, C → B

Test multiple messages, in both directions, with all three connected.

Test each delivery scope and check the clients that must stay **silent**. A is the server, so it
never receives anything — the check on A is that its *relay log* shows the message and its window
shows no bubble:

    B → C only           (unicast)   → C sees it, A shows no bubble
    B → A and C          (multicast) → A and C see it, nobody else
    B → Everyone         (broadcast) → A and C see it, B does not get its own echo

Also test that a sender never receives its own message back, that a client that disconnects
disappears from the others' picker and from the server's client list, and that addressing a client
that just left is logged as `NOT CONNECTED` rather than delivered to everyone.

### Server window

Confirm that the server window has no message box, that its client list follows the directory, and
that its relay log shows one line per event with the sender's `@ip:port`, the scope in capitals, the
recipients and the socket count — and never the message text. Confirm that **Stop Server** ends the
session while clients are connected.

### Connection Failure

Test:

- Wrong port
- Server not running
- Client disconnect
- Server stopped while clients are connected

### GUI

Verify that the UI remains responsive while another client sends a large burst of messages.

---

# 17. Development Order

Implement incrementally.

## Phase 1 — Project Setup

- Maven
- Java 21
- JavaFX
- Basic GUI

Verify the application starts successfully.

## Phase 2 — TCP Connection

Implement:

- Server mode, accepting **several** clients and keeping the rest alive when one leaves
- Client mode
- localhost connection

Verify:

    Instance A  (Server)     127.0.0.1:5000

    Instance B  (Client)     127.0.0.1:5000

    Instance C  (Client)     127.0.0.1:5000

all connect successfully and none of them displaces another.

## Phase 3 — Protocol

Implement:

- Packet
- Packet type
- Length framing
- Encoder
- Decoder

Test packet serialization/deserialization, including the addressing block and the byte-level layout
of a `RELAY`.

## Phase 4 — Chat

Implement:

- CHAT packet
- Sending
- Receiving
- Chat UI

Verify bidirectional chat between all three instances, with messenger-style bubbles labelled with the
sender's name.

## Phase 5 — Addressing

Implement:

- `HELLO` display-name exchange
- `ROSTER` client directory, carrying each receiver's own id
- `RELAY` with the addressing block, forwarded by the server
- the client id assignment in join order (from `#1`; the server has no id)
- the server's relay log, which is what shows the three scopes taking different paths
- unicast / multicast / broadcast in the `To:` picker

Verify with three instances that a unicast reaches exactly the named client and nobody else, a
multicast reaches exactly the chosen group and nobody else, a broadcast reaches everyone but the
sender, that the sender never gets its own message back, and that the server itself never appears as
a recipient anywhere.

## Phase 6 — Error Handling

Handle:

- Disconnect
- Connection failure
- Invalid packets
- Socket errors

## Phase 7 — Polish

- Clean UI
- Logging
- Tests
- README
- Demo instructions

---

# 18. README

Create a README explaining:

1. Project purpose
2. Architecture
3. Tech stack
4. How to run
5. How to start the server
6. How to connect a client
7. How the TCP connection works
8. Protocol format
9. Chat implementation
10. Addressing (unicast / multicast / broadcast)
11. The server window and its relay log
12. Concurrency
13. Localhost testing
14. How to later test using two LAN computers

Include a simple architecture diagram.

---

# 19. Definition of Done

The project is complete when:

1. The same application can be launched several times on one computer.
2. Instance A can act as the server, and keep serving after its last client leaves.
3. Instances B and C can connect to A using 127.0.0.1:5000.
4. Every client can send and receive chat messages, labelled with the sender's name; the server
   never sends or receives one.
5. A message can be unicast to one client, multicast to a chosen group, or broadcast to everyone.
6. The server forwards selectively, a sender never receives its own message back, and the server is
   never a recipient.
7. The `To:` picker reflects the live directory, and the server's client list and relay log show the
   same traffic from the middle.
8. The GUI remains responsive.
9. Disconnects are handled gracefully.
10. No client ever receives a message it was not addressed in.
11. Invalid input does not crash the application.
12. Maven can build the project.
13. README documents the architecture and protocol.

Do not add unnecessary features.

Prioritize:

    Correctness
    Simplicity
    Network Programming concepts
    Maintainability
    Easy demonstration