# P2P Chat & File Transfer

A small desktop peer-to-peer chat and file transfer application built with Java 21, JavaFX, and raw
TCP sockets. It is a Network Programming course project: two instances of the same application talk
directly over one TCP connection, with no server, database, or accounts in between.

## 1. Purpose

Demonstrate the networking fundamentals of a direct peer-to-peer application:

- Establishing a TCP connection in **Host** and **Connect** modes
- Bidirectional text chat over a single socket, messenger-style bubbles
- Streaming file transfer in chunks with live progress
- Inline image previews for received pictures, plus Open / Save as... actions
- Graceful handling of disconnects, bad input, and network errors

## 2. Architecture

```
+----------------------------+                 +----------------------------+
|        Instance A          |                 |        Instance B          |
|         (Host)             |                 |        (Connect)           |
|                            |                 |                            |
|  MainController (JavaFX)   |                 |  MainController (JavaFX)   |
|          |                 |                 |          |                 |
|     PeerSession            |                 |     PeerSession            |
|     /        \             |                 |     /        \             |
| ChatManager  FileManager   |                 | ChatManager  FileManager   |
|     \        /             |                 |     \        /             |
|     Connection             |  one TCP socket |     Connection             |
|     reader + writer        |<===============>|     reader + writer        |
|          |                 |                 |          |                 |
|    PeerServer(5000)        |                 |    PeerClient.connect()    |
+----------------------------+                 +----------------------------+
```

The Host/Connect distinction only exists while the connection is being established. Once connected,
both peers are equal and can send chat, send files, receive chat, and receive files.

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
    ui/MainController.java       screen, widgets, FX-thread updates
    ui/ChatItem.java             one chat entry: text or file, with transfer state
    ui/ChatItemTracker.java      keeps bubbles in order, matches file callbacks by id
    ui/ChatBubbleFactory.java    renders a ChatItem as a left/right bubble
    ui/FileTypes.java            image detection, badge label, preview size limit
    ui/FileSaveSupport.java      save-as copy, name suggestion, path helpers
    session/PeerSession.java     connect/disconnect lifecycle, packet routing
    session/SessionListener.java UI callback contract
    network/PeerServer.java      ServerSocket + accept
    network/PeerClient.java      Socket + connect
    network/Connection.java      framed read/write loops
    network/PacketSink.java      send abstraction used by chat/file code
    protocol/MessageType.java    message ids
    protocol/Packet.java         packet factories and payload parsing
    protocol/PacketCodec.java    wire framing
    chat/ChatManager.java        CHAT send/handle
    file/FileSender.java         streaming chunked sender, waits for the decision gate
    file/FileReceiver.java       offer state machine, streaming receiver, filename sanitizing
    file/FileTransferManager.java send/receive orchestration, auto-receive policy, accept/decline routing, 30 s timeout
    file/TransferGate.java        blocks the sender until accept/decline/timeout/disconnect
    file/TransferDecision.java    ACCEPTED / DECLINED / TIMED_OUT / DISCONNECTED
    util/NetworkUtils.java       port/address validation, byte formatting
    util/AppLogger.java          timestamped console logging

src/main/resources/org/example/p2pchat/ui/app.css
src/test/java/org/example/p2pchat/...  195 JUnit tests
docs/SPECS.md      the original specification
docs/PROTOCOL.md   the wire protocol in detail
```

## 5. How to run

The Maven wrapper downloads Maven on first use and works with any JDK 21+ on `PATH` / `JAVA_HOME`.

```powershell
# from the project root
.\mvnw.cmd clean javafx:run
```

Other useful commands:

```powershell
.\mvnw.cmd test      # run the 101 unit tests
.\mvnw.cmd package   # build the runnable fat jar: target/P2PChat.jar
```

If this machine's default JDK is not 21+, point at a 21 JDK first:

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-21"
.\mvnw.cmd javafx:run
```

### Running without Maven

`.\mvnw.cmd package` produces a self-contained `target/P2PChat.jar` (JavaFX included):

```powershell
java -jar target/P2PChat.jar
```

### "Error: JavaFX runtime components are missing"

Do **not** launch `org.example.p2pchat.Main` directly with `java -cp`. `Main` extends
`javafx.application.Application`, and the JVM launcher refuses to start an `Application` subclass
when JavaFX is on the classpath instead of the module path. That is exactly this message.

Use one of these instead:

| Way to run | Command |
|---|---|
| Fat jar (recommended) | `java -jar target/P2PChat.jar` |
| Maven (module path handled for you) | `.\mvnw.cmd javafx:run` |
| Plain classpath | `java -cp <classpath> org.example.p2pchat.Launcher` |

`org.example.p2pchat.Launcher` is a plain class that calls `Application.launch(Main.class, args)`,
which sidesteps the launcher check. It is the entry point declared in `pom.xml`.

In IntelliJ, set the run configuration's main class to `org.example.p2pchat.Launcher` as well.

## 6. How to start Host

1. Launch the application: `.\mvnw.cmd javafx:run`
2. Keep the **HOST** mode selected.
3. Set **Local port** (default `5000`).
4. Click **Start Host**.
5. Status shows `Listening on port 5000` and the app waits for one peer.

## 7. How to connect

1. Launch a second instance of the same application.
2. Select **CONNECT**.
3. Set **Peer IP** (default `127.0.0.1`) and **Peer port** (`5000`).
4. Click **Connect**.
5. Both instances switch to the session screen and status shows `Connected`.

Both instances are then equal: send `Hello` from either side and it appears on the other as a
bubble on the left, while your own messages appear on the right (messenger style).

### Chat and files in the session

- **Chat** — type and press Enter (Shift+Enter adds a newline). Your messages are right-aligned
  bubbles, the peer's are left-aligned.
- **Send File** — pick any file. The sender sees a bubble reading `waiting for peer...` and nothing
  is transmitted until the other side decides.
- **Images arrive automatically** — `.png .jpg .jpeg .gif .bmp .webp` files up to 8 MB are accepted
  on your behalf into `received/` and appear as a thumbnail in the bubble with **no Accept click at
  all**, on **both** sides (the sender previews its own file too). Click the image to open it full size.
- **Accept / Decline** — anything else (PDFs, archives, images over 8 MB) gets a bubble with `Accept`
  and `Decline` buttons plus the file name and size. `Accept` asks where to save the file, then the
  transfer starts; `Decline` rejects it and **no data is sent at all**. If nobody answers within 30
  seconds the sender gives up automatically.
- **Open** — opens the file with the default Windows application.
- **Save a copy...** — copies a received file somewhere else; the original stays where you accepted it.
- The right-hand panel still shows the current transfer's progress bar and a timestamped transfer log.

Auto-received images land in `received/` next to the working directory (git-ignored). For everything
else, files are only written where you choose during `Accept`.

## 8. How the TCP connection works

The Host creates `new ServerSocket(port)` and blocks in `accept()` on a background thread. The
Connect side creates `new Socket()` and calls `connect(new InetSocketAddress(host, port), timeout)`;
the operating system assigns an ephemeral local port automatically, so the connecting peer never
picks one.

The accepted/connected `Socket` is wrapped in `Connection`, which starts exactly two threads:

- **reader** — loops on `PacketCodec.read(inputStream)` and hands each `Packet` to a listener
- **writer** — takes `Packet`s from a bounded queue and writes them with `PacketCodec.write`

A single TCP connection carries chat, file data, and control messages. No second connection is
opened for the reverse direction.

## 9. Protocol format

Every message is one frame on the stream:

```
+--------+------------------+-------------------+
| type   | payload length   | payload           |
| 1 byte | 4 bytes big-end | N bytes           |
+--------+------------------+-------------------+
```

Types: `CHAT=1`, `FILE_START=2`, `FILE_CHUNK=3`, `FILE_END=4`, `DISCONNECT=5`.

Length framing lets the receiver tell where one message ends and the next begins. The full layout,
validation rules, and byte examples are in [`docs/PROTOCOL.md`](docs/PROTOCOL.md).

## 10. Chat implementation

`ChatManager.sendMessage(text)` wraps the text as `Packet.chat(...)` (UTF-8 payload) and pushes it
through the connection's send queue. Incoming `CHAT` packets are routed by `PeerSession` to
`ChatManager.handle`, which extracts the text and reports it to the UI as `Peer: <message>`. Empty
or whitespace-only messages are ignored.

## 11. File-transfer implementation

File transfer is **offer-first**, so the receiver decides before any bytes move. The receive policy
(`FileTransferManager`'s `autoReceive` predicate, wired from `FileTypes::canPreviewImage`) short-
circuits that decision for images, accepting them into `received/` so they render immediately.

`FileSender`:

1. sends `FILE_START` with `{fileId, fileSize, fileName}`
2. waits on a `TransferGate` for up to 30 s for `FILE_ACCEPT` / `FILE_DECLINE`
3. on accept — reads 64 KB at a time, sending each block as `FILE_CHUNK {fileId, chunkIndex, data}`
4. sends `FILE_END {fileId}`

`FileReceiver` is a small state machine over **pending offer → active transfer**:

1. on `FILE_START` — sanitize the name, remember `{fileId, size, name}` as a pending offer, notify the
   UI, write nothing
2. on `accept(fileId, folder)` — create the folder, open a non-clobbering target, switch to active,
   and send `FILE_ACCEPT`
3. on `decline(fileId)` — drop the offer, send `FILE_DECLINE`, write nothing
4. on `FILE_CHUNK` — verified against the active transfer (id, sequence), written, progress reported
5. on `FILE_END` — flush, close, verify `received == declared`, then mark complete

A size mismatch, wrong id, out-of-order chunk, or mid-transfer cancellation aborts and deletes the
partial file. A sender that gets no answer in 30 s sends `FILE_DECLINE` and reports
"no response from peer".

## 12. Chunking

Chunks are 64 KB (`FileSender.DEFAULT_CHUNK_SIZE`). The file is never loaded into memory — one
buffer is reused and only the current chunk is alive at a time, so a multi-gigabyte file transfers
with a flat memory profile. Progress callbacks are throttled to roughly every 256 KB, with an exact
final value sent at completion.

## 13. Concurrency

- Socket reads and writes never run on the JavaFX Application Thread.
- `Connection` runs one reader and one writer thread; the send queue is a bounded
  `ArrayBlockingQueue` (capacity 256) so a slow peer cannot exhaust memory.
- File sending is handed to a single-thread `ExecutorService` inside `PeerSession`, so a large file
  never blocks the UI thread that called `sendFile`. That thread parks on the `TransferGate` while it
  waits for the peer's answer, which is exactly why it must not be the FX thread.
- Every UI mutation is dispatched through the listener callback, which `MainController` marshals with
  `Platform.runLater`.
- File callbacks carry the transfer's `fileId`, so `ChatItemTracker` can update the correct bubble
  even when several transfers overlap.
- Image thumbnails load in the background (`Image` with background loading) to keep the UI smooth.
- Listener exceptions are caught and logged so a UI bug cannot kill the networking threads.

## 14. Localhost testing

Run everything on one machine first:

```
Instance A → Host    → 127.0.0.1:5000
Instance B → Connect → 127.0.0.1:5000
```

Test checklist:

- **Connection** — A hosts on `5000`, B connects, both show `Connected`.
- **Chat** — send multiple messages A→B and B→A.
- **Images** — send a `.png`/`.jpg` up to 8 MB and confirm it appears in the chat on both sides with
  no Accept click; the receiver's copy lands in `received/`.
- **Files** — send a small text file, a PDF, and a large file, in both directions. The receiver must
  press `Accept` and pick a folder before anything is written.
- **Decline** — press `Decline` and confirm the sender shows `declined by peer` and no file appears.
- **Timeout** — send a file and press nothing on the other side; after 30 s the sender gives up.
- **Failures** — wrong port, host not running (`Connection failed`), disconnect from either side.
- **Responsiveness** — send a large file and confirm the window stays interactive.
- **Received files** — saved in whichever folder you picked when accepting.

The JUnit suite (`.\mvnw.cmd test`) covers the protocol, connection, chat, file transfer, sanitizing,
and full two-session round trips over real localhost sockets.

## 15. Testing on two LAN computers

No code changes are needed. Only the address changes:

1. Host computer: start Host and note its LAN IP, for example `192.168.1.10`.
2. Second computer: choose Connect, set **Peer IP** = `192.168.1.10`, **Peer port** = `5000`.
3. Allow the Java process through the host firewall if prompted.

The connecting side uses an ephemeral local port, so nothing else needs configuring. NAT traversal
is out of scope and intentionally not implemented.

## 16. Definition of done

- [x] Same application can be launched twice on one computer
- [x] Instance A can act as Host
- [x] Instance B can connect to A via `127.0.0.1:5000`
- [x] Both instances send and receive chat
- [x] Both instances send and receive files
- [x] Receiver accepts or declines before any data is sent (30 s decision window)
- [x] Files transfer via streaming chunks (never fully in memory)
- [x] File progress is displayed (progress bar, bytes, percentage, status)
- [x] Chat is rendered as messenger-style left/right bubbles
- [x] Images are auto-received into `received/` and previewed inline on both sides, with Open / Save a copy... actions
- [x] The GUI stays responsive during transfers
- [x] Disconnects are handled gracefully
- [x] Received files saved in the folder chosen at accept time
- [x] Invalid input does not crash the app
- [x] Maven builds the project
- [x] README documents architecture and protocol
#   C h a t C l i e n t - L a b 4  
 