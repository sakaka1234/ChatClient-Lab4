> **Implementation decisions (approved):** this specification was implemented with the following
> choices locked in. See `README.md` and `PROTOCOL.md` for the shipped behaviour.
>
> | Topic | Decision |
> |---|---|
> | Base package | `org.example.p2pchat` (§6), `Main.java` moved to `org.example.p2pchat` |
> | JDK | 21 via Maven Wrapper `mvnw`; `.mvn/wrapper/maven-wrapper.properties` committed |
> | JavaFX | pinned `22.0.1`, UI built in code (no FXML) |
> | Entry point | `org.example.p2pchat.Launcher` (plain class) so classpath/fat-jar launches do not hit "JavaFX runtime components are missing"; `Main` stays the `Application` subclass |
> | Packaging | `maven-assembly-plugin` builds runnable `target/P2PChat.jar` |
> | Chat UI | messenger-style bubbles: peer messages left, own messages right (`ChatItem`, `ChatItemTracker`, `ChatBubbleFactory`) |
> | Image preview | inline thumbnail for `png/jpg/jpeg/gif/bmp/webp` up to 8 MB, click to open full size |
> | File actions | per-bubble `Open` (default app) and `Save as...` (copy out of `received/`) |
> | File callbacks | carry `fileId` so overlapping transfers map to the right bubble |
> | File sending | runs on a background single-thread executor in `PeerSession`, never on the FX thread |
> | Protocol version | v2 adds `FILE_ACCEPT=6` / `FILE_DECLINE=7`; both peers must run the same build |
> | File receiving | offer-first handshake: `FILE_START` shows Accept/Decline for files the user must decide on |
> | Auto-receive | images (`png/jpg/jpeg/gif/bmp/webp` ≤ 8 MB) are accepted automatically into `received/` and previewed right away; the sender is sent `FILE_ACCEPT` without a prompt |
> | Save location | user-chosen at accept time for prompted files; auto-received images go to `received/` (§13 revised) |
> | Decision timeout | 30 s (`FileTransferManager.DECISION_TIMEOUT_SECONDS`); sender then sends `FILE_DECLINE` |
> | Image preview | shown on both sides; the sender previews its local source file, the receiver previews the saved copy |
> | Tests | JUnit 5, `.\mvnw.cmd test` (195 tests) |
> | `PING` / `PONG` | intentionally not implemented; liveness via TCP EOF/IOException |
> | Concurrent files | one incoming transfer at a time, second `FILE_START` rejected |
> | `FILE_CHUNK.index` | enforced in strict sequence; mismatch aborts the transfer |
> | Integrity | `FILE_END` verifies `bytesReceived == declaredSize`, else partial file is deleted |
> | Duplicate names | auto-renamed `name (1).ext`, `name (2).ext`, ... |
> | Name safety | only basename kept; path separators, control chars, `..` and reserved Windows chars handled (§17) |
> | `DISCONNECT` | explicit packet, distinct from abrupt socket close in logs, same final UI state |
> | GUI progress | JavaFX `ProgressBar` + bytes/percentage labels (§14 illustration is text-only) |
> | `received/` | created next to the working directory and git-ignored |
> | Logging | timestamped `[INFO]`/`[WARN]`/`[ERROR]` to console via `AppLogger` |
> | Input validation | port `1..65535`, IP resolved before connecting, errors shown inline |
> | Concurrency | one reader thread + one writer thread + bounded send queue (256) |
> | Protocol doc | `docs/PROTOCOL.md` |

---

# P2P Chat & File Transfer — Project Specification

## 1. Project Goal

Build a simple desktop P2P chat application for a Network Programming course.

The application is designed for exactly 2 peers.

Core features:

- Direct TCP P2P connection
- Bidirectional text chat
- Bidirectional file transfer
- File transfer progress
- Connection/disconnection handling

The project should focus on demonstrating networking concepts rather than authentication or backend infrastructure.

Do NOT implement:

- Login / registration
- Database
- Central server
- REST API
- User accounts
- Group chat
- Cloud storage
- STUN/TURN/ICE
- NAT traversal
- Complex peer discovery

Use a single repository and a single application.

Both peers run the exact same application.

---

# 2. Tech Stack

Use:

- Java 21
- JavaFX
- Maven
- TCP Socket / ServerSocket
- Java standard library for networking and file I/O

Prefer Java NIO where it makes sense, but keep the implementation simple and understandable for a university Network Programming project.

Do NOT use Spring Boot or heavy networking frameworks.

---

# 3. IMPORTANT: Development and Testing Strategy

Implement and test the application on localhost FIRST.

Do not design the initial implementation around multiple physical machines.

The first target is:

    Program A
    127.0.0.1:5000
          |
          | TCP
          |
          v
    Program B
    127.0.0.1:<ephemeral-port>

Both programs are two independent instances of the same application running on the same computer.

## Host

Instance A starts in Host mode:

    IP: 127.0.0.1
    Port: 5000

It creates:

    ServerSocket(5000)

and waits for one peer.

## Connect

Instance B starts in Connect mode:

    Peer IP: 127.0.0.1
    Peer Port: 5000

It creates a Socket and connects to the Host.

Example:

    new Socket("127.0.0.1", 5000)

The connecting peer does NOT need to manually specify a local port. The operating system can assign an ephemeral local port automatically.

---

# 4. Future LAN Testing

Do NOT implement special LAN functionality.

The networking code should naturally support connecting to another host by IP.

After the localhost version works, the developer/user may manually test two physical machines by changing only the Host address.

Example:

Host computer:

    192.168.1.10:5000

Second computer connects to:

    192.168.1.10:5000

The application should not require architectural changes for this.

The user will handle changing the Host IP and LAN testing manually later.

Do not implement NAT traversal.

---

# 5. Application Architecture

Use a single P2P application.

Each application instance can operate in one of two modes:

## Host Mode

1. User enters a port.
2. Application creates ServerSocket.
3. Application waits for one incoming connection.
4. Once connected, chat and file transfer become available.

## Connect Mode

1. User enters peer IP.
2. User enters peer port.
3. Application creates a Socket.
4. Application connects to the Host.
5. Once connected, chat and file transfer become available.

After connection establishment, both peers are equal.

Both peers must be able to:

- Send chat
- Receive chat
- Send files
- Receive files

The Host/Connect distinction only exists during connection establishment.

---

# 6. Suggested Project Structure

Use a clean structure similar to:

src/main/java/com/example/p2pchat/

    Main.java

    ui/
        MainController.java

    network/
        PeerServer.java
        PeerClient.java
        Connection.java

    protocol/
        MessageType.java
        Packet.java
        PacketCodec.java

    chat/
        ChatManager.java

    file/
        FileSender.java
        FileReceiver.java
        FileTransferManager.java

    util/
        NetworkUtils.java

The exact structure can be adjusted if there is a better simple design.

Avoid unnecessary abstraction.

---

# 7. GUI

Create a simple, clean JavaFX interface.

Initial screen:

--------------------------------
            P2P CHAT
--------------------------------

Mode:

[ HOST ]    [ CONNECT ]

Port:
[ 5000 ]

Peer IP:
[ 127.0.0.1 ]

Peer Port:
[ 5000 ]

[ Start / Connect ]

Status:
Disconnected

--------------------------------

After connection:

--------------------------------
            P2P CHAT
--------------------------------

Status: Connected

--------------------------------
Chat

Peer: Hello
You: Hi

--------------------------------

[ Type message...             ]

[ Send ]

--------------------------------
File Transfer

[ Send File ]

File:
example.pdf

Progress:
████████████░░░░ 75%

Status:
Transferring...

--------------------------------

[ Disconnect ]

The GUI should remain simple.

Do not spend excessive time on UI styling.

---

# 8. TCP Communication

Use TCP sockets for all communication.

The connection should support bidirectional communication.

Conceptually:

    Peer A
       |
       | TCP
       |
       v
    Peer B

Both directions must work.

Do not create a separate connection for sending and receiving unless there is a strong technical reason.

A single TCP connection should support:

- Chat
- File transfer
- Connection control messages

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
    FILE_START
    FILE_CHUNK
    FILE_END
    PING
    PONG
    DISCONNECT

The exact binary format is up to the implementation.

Document the protocol clearly.

The protocol must allow the receiver to determine where one message ends and the next begins.

---

# 10. Chat

When the user sends:

    Hello

the application creates a CHAT packet.

The receiver:

1. Reads the packet.
2. Decodes it.
3. Extracts the message.
4. Displays it in the JavaFX chat area.

Both peers must be able to send messages at any time.

Receiving must happen independently from the JavaFX UI thread.

---

# 11. File Transfer

Support sending arbitrary files.

Do NOT load the entire file into memory.

Use streaming.

Recommended approach:

- Open the file using BufferedInputStream or NIO
- Read the file in chunks
- Send chunks sequentially

Use a reasonable chunk size such as:

    64 KB

---

# 12. File Transfer Protocol

When starting a file transfer:

FILE_START

Include:

    file ID
    filename
    file size

Then send:

FILE_CHUNK

Include:

    file ID
    chunk index
    chunk data

Finally send:

FILE_END

Include:

    file ID

Example:

    FILE_START
        filename = test.pdf
        size = 10485760

    FILE_CHUNK
        index = 0
        data = ...

    FILE_CHUNK
        index = 1
        data = ...

    ...

    FILE_END

---

# 13. File Receiving

When FILE_START is received:

1. Sanitize the filename.
2. Create the destination file.
3. Prepare the receiving state.
4. Display the filename and total size.

When FILE_CHUNK is received:

1. Write the chunk to disk.
2. Update bytes received.
3. Update progress.

When FILE_END is received:

1. Close the file.
2. Mark the transfer as completed.
3. Update the UI.

Save received files under:

    received/

Create the directory automatically if necessary.

---

# 14. File Progress

Display:

- Filename
- Total file size
- Bytes transferred
- Percentage
- Transfer status

Example:

    Sending:
    example.zip

    42 MB / 100 MB

    42%

The JavaFX UI must not be updated directly from networking threads.

Use Platform.runLater() or another appropriate mechanism.

---

# 15. Concurrency

The GUI must remain responsive during:

- Connection
- Receiving messages
- Sending files
- Receiving files

Do NOT perform blocking socket operations on the JavaFX Application Thread.

Use:

- ExecutorService
- Background threads
- or another simple concurrency mechanism

A receiving loop should continuously listen for incoming packets without blocking the UI.

Avoid creating an unlimited number of threads.

---

# 16. Connection Management

Handle:

- Successful connection
- Connection refused
- Invalid IP
- Invalid port
- Port already in use
- Peer disconnect
- Unexpected socket closure
- Network errors

Display clear status messages.

Examples:

    Listening on port 5000

    Connected to 127.0.0.1:5000

    Connection failed

    Peer disconnected

    File transfer failed

The application should not crash because the peer disconnects unexpectedly.

---

# 17. File Safety

When receiving a file, only accept a filename.

Do not allow the remote peer to specify an arbitrary filesystem path.

Prevent path traversal.

For example:

Allowed:

    photo.jpg

Not allowed:

    ../../Windows/System32/file

Sanitize filenames before writing them.

---

# 18. Logging

Add simple logging for networking/debugging.

Examples:

    [INFO] Listening on port 5000
    [INFO] Peer connected: 127.0.0.1
    [INFO] Sending CHAT packet
    [INFO] Receiving FILE_START
    [INFO] Receiving file: test.pdf
    [INFO] File transfer completed

Do not spam logs unnecessarily.

---

# 19. Testing Requirements

First test everything using TWO INSTANCES on the SAME MACHINE.

Test:

### Connection

    Instance A → Host → 127.0.0.1:5000

    Instance B → Connect → 127.0.0.1:5000

### Chat

Test:

    A → B
    B → A

Test multiple messages.

### File Transfer

Test:

- Small text file
- Image
- PDF
- Large file

Test both directions:

    A → B

    B → A

### Connection Failure

Test:

- Wrong port
- Host not running
- Peer disconnect

### GUI

Verify that the UI remains responsive while transferring a large file.

---

# 20. Development Order

Implement incrementally.

## Phase 1 — Project Setup

- Maven
- Java 21
- JavaFX
- Basic GUI

Verify the application starts successfully.

## Phase 2 — TCP Connection

Implement:

- Host mode
- Connect mode
- localhost connection

Verify:

    Instance A
    127.0.0.1:5000

    Instance B
    127.0.0.1:5000

can connect successfully.

## Phase 3 — Protocol

Implement:

- Packet
- Packet type
- Length framing
- Encoder
- Decoder

Test packet serialization/deserialization.

## Phase 4 — Chat

Implement:

- CHAT packet
- Sending
- Receiving
- Chat UI

Verify bidirectional chat.

## Phase 5 — File Transfer

Implement:

- FILE_START
- FILE_CHUNK
- FILE_END
- Streaming
- Receiving
- Progress bar

Verify bidirectional file transfer.

## Phase 6 — Error Handling

Handle:

- Disconnect
- Connection failure
- Invalid packets
- File errors
- Socket errors

## Phase 7 — Polish

- Clean UI
- Logging
- Tests
- README
- Demo instructions

---

# 21. README

Create a README explaining:

1. Project purpose
2. Architecture
3. Tech stack
4. How to run
5. How to start Host
6. How to connect
7. How the TCP connection works
8. Protocol format
9. Chat implementation
10. File-transfer implementation
11. Chunking
12. Concurrency
13. Localhost testing
14. How to later test using two LAN computers

Include a simple architecture diagram.

---

# 22. Definition of Done

The project is complete when:

1. The same application can be launched twice on one computer.
2. Instance A can act as Host.
3. Instance B can connect to A using 127.0.0.1:5000.
4. Both instances can send and receive chat messages.
5. Both instances can send and receive files.
6. Files are transferred using streaming/chunks.
7. File progress is displayed.
8. The GUI remains responsive.
9. Disconnects are handled gracefully.
10. Received files are saved correctly.
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