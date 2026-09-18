# Kiến trúc P2P Chat — Giải thích từ đầu

> **Đọc xong file này bạn sẽ hiểu:** thư mục `src/` tổ chức ra sao, một byte từ bàn phím đi qua
> những lớp nào để tới máy kia, giao thức nhị phân được đóng gói thế nào, và vì sao ảnh tự hiện mà
> không cần bấm Accept.

Tài liệu này viết để **học**, không phải để tra cứu. Mỗi khái niệm đi kèm code thật và số dòng, nên
bạn mở file lên là thấy ngay. Muốn xem đặc tả khô khan thì đọc `docs/PROTOCOL.md` và `docs/SPECS.md`.

---

## 1. Bức tranh lớn trong một câu

Hai instance giống hệt nhau nói chuyện qua **một socket TCP duy nhất**, chia sẻ bởi hai luồng: một
luồng chỉ đọc, một luồng chỉ ghi; mọi thứ (chat, file, điều khiển) đều được bọc thành **packet nhị
phân có khung độ dài** để bên nhận biết một gói kết thúc ở đâu.

```
   Instance A (Host)                         Instance B (Connect)
   ┌────────────────┐                        ┌────────────────┐
   │   JavaFX UI    │                        │   JavaFX UI    │
   │ MainController │                        │ MainController │
   └───────┬────────┘                        └───────┬────────┘
           │ SessionListener (callback)              │
   ┌───────▼────────┐                        ┌───────▼────────┐
   │  PeerSession   │  ← điều phối tất cả    │  PeerSession   │
   │  ChatManager   │                        │  ChatManager   │
   │ FileTransferMgr│                        │ FileTransferMgr│
   └───────┬────────┘                        └───────┬────────┘
           │ PacketSink.send(packet)                 │
   ┌───────▼────────┐        TCP socket        ┌──────▼─────────┐
   │   Connection   │◄════════════════════════►│   Connection   │
   │ reader + writer│   khung: type|len|data   │ reader + writer│
   └────────────────┘                          └────────────────┘
```

Điểm mấu chốt: **Host và Connect chỉ khác nhau lúc thiết lập kết nối**. Sau khi socket mở, hai bên
hoàn toàn bình đẳng — cùng gửi chat, cùng gửi file.

---

## 2. Bản đồ thư mục

```
src/main/java/org/example/p2pchat/
│
├── Launcher.java              ← JVM entry point (class thường, KHÔNG kế thừa Application)
├── Main.java                  ← JavaFX Application, dựng Scene
│
├── ui/                        ← tầng giao diện, chỉ chạy trên FX thread
│   ├── MainController.java    ← màn hình, nút bấm, nhận callback từ session
│   ├── ChatItem.java          ← một dòng chat: text hoặc file + trạng thái truyền
│   ├── ChatItemTracker.java   ← giữ bubble đúng thứ tự, khớp callback theo fileId
│   ├── ChatBubbleFactory.java ← biến ChatItem thành bubble trái/phải (có preview ảnh)
│   ├── FileTypes.java         ← nhận diện ảnh, nhãn badge, giới hạn preview 8 MB
│   └── FileSaveSupport.java   ← "Save as...", gợi ý tên file
│
├── session/
│   ├── PeerSession.java       ← vòng đời kết nối, định tuyến packet (bộ não)
│   └── SessionListener.java   ← hợp đồng callback session → UI
│
├── network/                   ← tầng socket thô, không biết gì về chat/file
│   ├── PeerServer.java        ← ServerSocket + accept
│   ├── PeerClient.java        ← Socket + connect
│   ├── Connection.java        ← vòng đọc/ghi có khung, hàng đợi gửi
│   └── PacketSink.java        ← interface "gửi 1 packet" (để code khác không phụ thuộc Socket)
│
├── protocol/                  ← định dạng byte trên dây
│   ├── MessageType.java       ← enum các loại gói kèm id số
│   ├── Packet.java            ← factory + parse payload
│   └── PacketCodec.java       ← ghi/đọc khung type|length|payload
│
├── chat/
│   ├── ChatManager.java       ← gửi/xử lý CHAT
│   └── ChatListener.java      ← callback chat
│
├── file/
│   ├── FileSender.java        ← đọc file theo chunk, chờ quyết định rồi mới gửi
│   ├── FileReceiver.java      ← máy trạng thái offer, ghi đĩa, làm sạch tên file
│   ├── FileTransferManager.java ← điều phối chung, policy auto-receive, định tuyến accept/decline
│   ├── TransferGate.java      ← chặn luồng gửi tới khi có accept/decline/timeout/disconnect
│   ├── TransferDecision.java  ← enum ACCEPTED / DECLINED / TIMED_OUT / DISCONNECTED
│   └── FileTransferListener.java ← callback file
│
└── util/
    ├── NetworkUtils.java      ← validate port/IP, format byte
    └── AppLogger.java         ← log có timestamp
```

**Quy tắc vàng về hướng phụ thuộc:** `ui → session → (chat, file) → network, protocol`. Không bao
giờ có mũi tên ngược. Vì thế `network` không cần biết "chat" là gì, và `protocol` không cần biết
JavaFX tồn tại.

### Vì sao `Launcher` tách khỏi `Main`?

Nếu class `main` kế thừa `javafx.application.Application` mà JavaFX nằm trên classpath (fat jar),
JVM sẽ báo *"JavaFX runtime components are missing"*. Cách lách chuẩn là để class thường đứng ra gọi:

```java
// src/main/java/org/example/p2pchat/Launcher.java:18
public static void main(String[] args) {
    Application.launch(Main.class, args);
}
```

`Main` vẫn là `Application` thật, chỉ có "cửa vào" là class thường:

```java
// src/main/java/org/example/p2pchat/Main.java:14
@Override
public void start(Stage stage) {
    MainController controller = new MainController(stage);
    Scene scene = new Scene(controller.root(), 980, 660);
    scene.getStylesheets().add(
            Main.class.getResource("/org/example/p2pchat/ui/app.css").toExternalForm());
    ...
}
```

---

## 3. Kết nối TCP — chỗ duy nhất Host khác Connect

### Host: mở cổng và chờ

```java
// src/main/java/org/example/p2pchat/network/PeerServer.java:32
public Connection accept(Connection.Listener listener) throws IOException {
    AppLogger.info("Listening on port " + port());
    var socket = serverSocket.accept();          // block tới khi có peer
    socket.setTcpNoDelay(true);                  // gửi ngay, không gom buffer
    AppLogger.info("Peer connected: " + socket.getInetAddress().getHostAddress());
    return new Connection(socket, listener);
}
```

`accept()` **block**, nên `PeerSession` gọi nó trên thread riêng `p2p-accept`, không phải FX thread:

```java
// src/main/java/org/example/p2pchat/session/PeerSession.java:63
Thread acceptThread = new Thread(this::acceptLoop, "p2p-accept");
acceptThread.setDaemon(true);
acceptThread.start();
```

### Connect: chủ động mở socket

```java
// src/main/java/org/example/p2pchat/network/PeerClient.java:20
Socket socket = new Socket();
socket.setTcpNoDelay(true);
socket.connect(new InetSocketAddress(host.trim(), port), timeoutMillis);
```

Cổng local do OS tự cấp — bên Connect không cần chọn. Sau khi socket mở, **cả hai đều bọc trong
`Connection` giống hệt nhau**.

---

## 4. Giao thức: khung nhị phân `type | length | payload`

Đây là trái tim của toàn bộ hệ thống. TCP là một **dòng byte liên tục, không có ranh giới thông
điệp**. Nếu A gửi "Xin chào" rồi gửi "Tạm biệt", B có thể nhận được `"Xin chàoTạm biệt"` trong một
lần đọc. Giao thức tầng ứng dụng phải tự vẽ ranh giới.

### Khung trên dây

Mỗi packet gồm đúng 5 byte header + payload:

```
┌──────────┬──────────────┬─────────────────────┐
│ type:u8  │ length:i32   │ payload: length byte │
│ 1 byte   │ 4 byte       │ 0..16 MB             │
└──────────┴──────────────┴─────────────────────┘
```

Cách ghi (encoder):

```java
// src/main/java/org/example/p2pchat/protocol/PacketCodec.java:17
public static void write(OutputStream out, Packet packet) throws IOException {
    byte[] payload = packet.payload();
    if (payload.length > MAX_PAYLOAD_LENGTH) {
        throw new IOException("Payload too large: " + payload.length + " bytes");
    }
    DataOutputStream data = new DataOutputStream(out);
    data.writeByte(packet.type().id());   // 1. loại gói
    data.writeInt(payload.length);        // 2. độ dài payload
    data.write(payload);                  // 3. nội dung
    data.flush();
}
```

Cách đọc (decoder) — đọc đúng 5 byte header rồi đọc đúng `length` byte payload:

```java
// src/main/java/org/example/p2pchat/protocol/PacketCodec.java:29
public static Packet read(InputStream in) throws IOException {
    DataInputStream data = ...;
    int typeId = data.readUnsignedByte();
    MessageType type = MessageType.fromId(typeId);
    int length = data.readInt();
    if (length < 0 || length > MAX_PAYLOAD_LENGTH) {
        throw new IOException("Invalid payload length: " + length);
    }
    byte[] payload = new byte[length];
    try {
        data.readFully(payload);          // đọc CHO ĐỦ, không thoát giữa chừng
    } catch (EOFException e) {
        throw new EOFException("Connection closed while reading " + type + " payload");
    }
    return Packet.of(type, payload);
}
```

**Vì sao `readFully` quan trọng:** `InputStream.read(buf)` có thể trả về ít byte hơn yêu cầu. Nếu
dùng nó thay `readFully`, bạn có thể parse sai khung khi mạng chậm. `readFully` đảm bảo đủ.

### Các loại gói

```java
// src/main/java/org/example/p2pchat/protocol/MessageType.java:5
public enum MessageType {
    CHAT(1),
    FILE_START(2),
    FILE_CHUNK(3),
    FILE_END(4),
    DISCONNECT(5),
    FILE_ACCEPT(6),     // v2: bên nhận đồng ý
    FILE_DECLINE(7);    // v2: bên nhận (hoặc bên gửi) hủy
    ...
}
```

### Hình dạng payload từng loại

`Packet` là nơi duy nhất biết cách nhồi/rút byte. Ví dụ `FILE_START` mang `fileId` (2 byte),
`fileSize` (8 byte), rồi `nameLength` (2 byte) + tên UTF-8:

```java
// src/main/java/org/example/p2pchat/protocol/Packet.java:27
public static Packet fileStart(int fileId, String fileName, long fileSize) {
    ...
    byte[] name = fileName.getBytes(StandardCharsets.UTF_8);
    ByteBuffer buffer = ByteBuffer.allocate(2 + 8 + 2 + name.length);
    buffer.putShort((short) fileId);
    buffer.putLong(fileSize);
    buffer.putShort((short) name.length);
    buffer.put(name);
    return new Packet(MessageType.FILE_START, buffer.array());
}
```

Rút ra thì làm ngược lại, phải nhảy qua đúng offset:

```java
// src/main/java/org/example/p2pchat/protocol/Packet.java:101
public String fileName() {
    requireType(MessageType.FILE_START);      // sai loại gói → ném lỗi ngay
    ByteBuffer buffer = ByteBuffer.wrap(payload);
    buffer.getShort();                         // bỏ qua fileId
    buffer.getLong();                          // bỏ qua fileSize
    int nameLength = Short.toUnsignedInt(buffer.getShort());
    byte[] name = new byte[nameLength];
    buffer.get(name);
    return new String(name, StandardCharsets.UTF_8);
}
```

| Gói | Payload |
|---|---|
| `CHAT` | text UTF-8 thuần |
| `FILE_START` | `u16 fileId` + `i64 size` + `u16 nameLen` + `name[UTF-8]` |
| `FILE_CHUNK` | `u16 fileId` + `i32 chunkIndex` + `data` |
| `FILE_END` | `u16 fileId` |
| `FILE_ACCEPT` / `FILE_DECLINE` | `u16 fileId` |
| `DISCONNECT` | rỗng |

> `fileId` là `u16` nên tối đa `0xFFFF` (65535). **Lưu ý quan trọng:** mỗi peer tự đếm `fileId`
> riêng bắt đầu từ 1, nên `fileId` chỉ unique trong một chiều. Đây chính là bẫy đã gây ra bug
> "bên nhận mất ảnh" — xem mục 9.

---

## 5. `Connection` — hai luồng, một socket

`Connection` biến `Socket` thô thành hai thread tách biệt:

```java
// src/main/java/org/example/p2pchat/network/Connection.java:46
public void start() {
    ...
    reader = new Thread(this::readLoop, "p2p-reader");
    writer = new Thread(this::writeLoop, "p2p-writer");
    reader.setDaemon(true);
    writer.setDaemon(true);
    reader.start();
    writer.start();
}
```

**Luồng đọc** — block trên `PacketCodec.read`, đẩy từng packet cho `listener.onPacket`:

```java
// src/main/java/org/example/p2pchat/network/Connection.java:80
private void readLoop() {
    try {
        InputStream in = new BufferedInputStream(raw, 64 * 1024);
        while (open.get()) {
            Packet packet = PacketCodec.read(in);
            listener.onPacket(packet);
        }
    } catch (IOException e) {
        ...
        notifyDisconnected(closingIntentionally.get() ? null : e);
    } finally {
        closeSocket();
    }
}
```

**Luồng ghi** — lấy packet từ hàng đợi có giới hạn (256) rồi ghi ra socket:

```java
// src/main/java/org/example/p2pchat/network/Connection.java:98
private void writeLoop() {
    OutputStream out = new BufferedOutputStream(raw, 64 * 1024);
    while (open.get()) {
        Packet packet = outgoing.take();      // chờ có gói
        PacketCodec.write(out, packet);
    }
}
```

**Vì sao phải tách 2 luồng:** không thể vừa block đọc vừa gửi trên cùng một thread. Nếu không tách,
lúc chờ `read()` bạn không gửi được gì cả.

**Vì sao hàng đợi có giới hạn 256:** để một peer gửi file ào ạt không làm tràn RAM bên gửi khi mạng
chậm. Nếu đầy, `send` ném lỗi "peer is too slow" sau 30 giây thay vì phình bộ nhớ vô hạn:

```java
// src/main/java/org/example/p2pchat/network/Connection.java:63
if (!outgoing.offer(packet, SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
    throw new IOException("Send queue is full; peer is too slow");
}
```

---

## 6. Định tuyến packet — `PeerSession.PacketRouter`

Mọi packet đến đều qua một switch duy nhất, chia về đúng manager:

```java
// src/main/java/org/example/p2pchat/session/PeerSession.java:291 (PacketRouter.onPacket)
public void onPacket(Packet packet) {
    MessageType type = packet.type();
    switch (type) {
        case CHAT -> {
            ChatManager manager = chatManager;
            if (manager != null) {
                manager.handle(packet);
            }
        }
        case FILE_START, FILE_CHUNK, FILE_END, FILE_ACCEPT, FILE_DECLINE -> fileManager.handle(packet);
        case DISCONNECT -> {
            AppLogger.info("Peer sent DISCONNECT");
            clearConnection();
            notifyStatus(STATUS_PEER_DISCONNECTED);
            notifyDisconnected(STATUS_PEER_DISCONNECTED);
        }
    }
}
```

`fileManager` được gắn "cách gửi" mỗi khi có kết nối. Đây là điểm nối giữa logic và socket:

```java
// src/main/java/org/example/p2pchat/session/PeerSession.java:211
private void applyConnection(Connection newConnection) {
    connection = newConnection;
    if (newConnection == null) {
        chatManager = null;
        fileManager.setSink(null);
    } else {
        chatManager = new ChatManager(newConnection::send, new ChatBridge());
        fileManager.setSink(newConnection::send);      // method reference tới Connection.send
    }
}
```

`PacketSink` là một functional interface — nhờ nó `ChatManager` và `FileTransferManager` không cần
giữ `Socket`, chỉ cần "một cái ống để bắn packet":

```java
// src/main/java/org/example/p2pchat/network/PacketSink.java
@FunctionalInterface
public interface PacketSink {
    void send(Packet packet) throws IOException;
}
```

---

## 7. Chat — luồng đầy đủ của một tin nhắn

### Gửi

Người dùng bấm Send → `MainController` → `PeerSession.sendChat` → `ChatManager.sendMessage`:

```java
// src/main/java/org/example/p2pchat/chat/ChatManager.java:20
public void sendMessage(String message) {
    if (message == null || message.isBlank()) {
        return;                                // chặn tin rỗng
    }
    try {
        AppLogger.info("Sending CHAT packet");
        sink.send(Packet.chat(message.strip()));
    } catch (IOException e) {
        reportError("Failed to send message: " + describe(e));
    }
}
```

`Packet.chat` chỉ đơn giản UTF-8 hóa text:

```java
// src/main/java/org/example/p2pchat/protocol/Packet.java:23
public static Packet chat(String text) {
    return new Packet(MessageType.CHAT, text.getBytes(StandardCharsets.UTF_8));
}
```

### Nhận

Bên kia `PacketRouter` gọi `ChatManager.handle`:

```java
// src/main/java/org/example/p2pchat/chat/ChatManager.java:32
public void handle(Packet packet) {
    try {
        String message = packet.chatText();
        AppLogger.info("Receiving CHAT packet");
        listener.onChatMessage(message);
    } catch (RuntimeException e) {
        reportError("Received invalid chat packet: " + describe(e));
    }
}
```

### Nhảy sang UI — và đây là chỗ dễ sai nhất

`listener` ở đây là `PeerSession.ChatBridge`, nó gọi tiếp `SessionListener` (chính là `MainController`).
Vì `handle` chạy trên **thread đọc**, không phải FX thread, `MainController` phải chuyển làn:

```java
// src/main/java/org/example/p2pchat/ui/MainController.java:501
@Override
public void onChatMessage(String direction, String message) {
    runOnFx(() -> appendChat(ChatItem.inboundText(message, System.currentTimeMillis())));
}
```

```java
// src/main/java/org/example/p2pchat/ui/MainController.java:565
private static void runOnFx(Runnable action) {
    if (Platform.isFxApplicationThread()) {
        action.run();
    } else {
        Platform.runLater(action);
    }
}
```

> **Quy tắc bất di bất dịch:** mọi thay đổi JavaFX phải qua `runOnFx`. Chạm UI từ thread mạng sẽ
> ném `IllegalStateException` hoặc treo app một cách ngẫu nhiên.

### Hiển thị thành bubble

`ChatItem` là model một dòng; `ChatBubbleFactory` biến nó thành node:

```java
// src/main/java/org/example/p2pchat/ui/ChatBubbleFactory.java:43
public Node create(ChatItem item) {
    VBox bubble = new VBox(6);
    bubble.getStyleClass().add("bubble");
    bubble.getStyleClass().add(item.outbound() ? "bubble-out" : "bubble-in");
    ...
    HBox row = new HBox(bubble);
    row.setAlignment(item.outbound() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
    ...
}
```

---

## 8. Truyền file — giao thức "offer-first"

Đây là phần thú vị nhất, và cũng là chỗ protocol v2 thêm vào.

### Vì sao không gửi thẳng?

Bản v1 gửi file ngay lập tức vào thư mục `received/`. Vấn đề: bên nhận không có quyền chọn nơi
lưu, và file rác vẫn được ghi dù người ta không muốn. v2 đổi thành **offer-first**: bên gửi chỉ
thông báo, chờ quyết định, rồi mới gửi byte.

### Luồng đầy đủ

```
Bên gửi (FileSender)                    Bên nhận (FileReceiver/Manager)
   │ FILE_START {id,size,name} ──────────►│  tạo PendingOffer, hiện Accept/Decline
   │ gate.awaitDecision() (chặn 30s)      │  (policy ảnh: tự accept luôn — mục 10)
   │◄──────────── FILE_ACCEPT {id} ───────│  người dùng chọn thư mục
   │ FILE_CHUNK {id,0,data} ─────────────►│  ghi chunk vào file
   │ FILE_CHUNK {id,1,data} ─────────────►│  ...
   │ FILE_END {id} ──────────────────────►│  flush, kiểm tra đủ byte, xong
   │                                      │
   │◄──────────── FILE_DECLINE {id} ──────│  huỷ (không gửi byte nào)
```

### Bên gửi: cổng chặn `TransferGate`

`FileSender` gửi `FILE_START`, rồi **dừng lại** chờ quyết định:

```java
// src/main/java/org/example/p2pchat/file/FileSender.java:39
sink.send(Packet.fileStart(fileId, fileName, fileSize));
AppLogger.info("Offering file: " + fileName + " (" + fileSize + " bytes)");

if (gate != null) {
    TransferDecision decision = gate.awaitDecision();     // block ở đây
    if (decision != TransferDecision.ACCEPTED) {
        AppLogger.info("Transfer not accepted (" + decision + "): " + fileName);
        if (decision == TransferDecision.TIMED_OUT) {
            sendQuietly(sink, Packet.fileDecline(fileId)); // báo ngược lại bên kia
        }
        return decision;
    }
    notifyStarted(listener, fileId, fileName, fileSize);
}
```

Sau khi được accept mới đọc file theo từng khối 64 KB:

```java
// src/main/java/org/example/p2pchat/file/FileSender.java:56
long sent = 0;
int chunkIndex = 0;
byte[] buffer = new byte[chunkSize];               // 64 KB
try (InputStream in = Files.newInputStream(file)) {
    int read;
    while ((read = in.read(buffer)) != -1) {
        byte[] data = Arrays.copyOf(buffer, read);
        sink.send(Packet.fileChunk(fileId, chunkIndex, data));
        sent += read;
        chunkIndex++;
        ...
    }
}
sink.send(Packet.fileEnd(fileId));                  // dấu chấm hết
```

> **Vì sao chunk 64 KB:** không nạp cả file vào RAM, và mỗi packet vẫn đủ lớn để không quá nhiều
> overhead header. Đây là trade-off kinh điển giữa throughput và bộ nhớ.

`TransferGate` là một cơ chế wait/notify đơn giản, có timeout:

```java
// src/main/java/org/example/p2pchat/file/TransferGate.java:15
public TransferDecision awaitDecision() {
    long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
    synchronized (lock) {
        while (!answered) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                answered = true;
                decision = TransferDecision.TIMED_OUT;
                break;
            }
            try {
                lock.wait(Math.max(1L, remaining / 1_000_000L));
            } catch (InterruptedException e) {
                ...
                decision = TransferDecision.DISCONNECTED;
                break;
            }
        }
        return decision;
    }
}
```

### Bên nhận: máy trạng thái offer

`FileReceiver` giữ **một offer đang chờ** và **một transfer đang chạy**. Nhận `FILE_START` thì chưa
ghi gì xuống đĩa, chỉ tạo trạng thái chờ:

```java
// src/main/java/org/example/p2pchat/file/FileReceiver.java:63
public void onFileStart(Packet packet) {
    int incomingId = fileIdOf(packet);
    try {
        if (pending != null) {
            fail(incomingId, fileNameOf(packet), "Already waiting for a decision on " + pending.safeName());
            return;                                    // chỉ 1 offer tại một thời điểm
        }
        if (active != null) {
            fail(incomingId, fileNameOf(packet), "Already receiving " + active.fileName);
            return;
        }
        ...
        String safeName = safeName(packet.fileName());
        pending = new PendingOffer(fileId, safeName, declaredSize);
        notifyOffered(fileId, safeName, declaredSize);  // → UI hiện Accept/Decline
    } catch (IOException | RuntimeException e) {
        fail(incomingId, fileNameOf(packet), messageOf(e));
    }
}
```

Khi người dùng accept, **lúc này mới tạo file**:

```java
// src/main/java/org/example/p2pchat/file/FileReceiver.java:90
public boolean accept(int fileId, Path destinationDirectory) {
    PendingOffer offer = pending;
    if (offer == null || offer.fileId() != fileId || destinationDirectory == null) {
        return false;
    }
    pending = null;
    try {
        Path directory = destinationDirectory.toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path target = uniqueTarget(directory, offer.safeName());   // name (1).ext nếu trùng
        OutputStream out = new BufferedOutputStream(
                Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                64 * 1024);
        active = new ActiveTransfer(offer.fileId(), offer.safeName(), offer.declaredSize(), target, out);
        notifyStarted(offer.fileId(), offer.safeName(), offer.declaredSize());
        return true;
    } catch (...) { ... }
}
```

Chunk được ghi và **kiểm tra đúng thứ tự**:

```java
// src/main/java/org/example/p2pchat/file/FileReceiver.java:145 (trong onFileChunk)
int fileId = packet.fileId();
if (fileId != transfer.fileId) {
    throw new IOException("Unknown file id " + fileId + " for active transfer " + transfer.fileId);
}
int index = packet.chunkIndex();
if (index != transfer.nextChunkIndex) {
    throw new IOException("Chunk out of order: expected index " + transfer.nextChunkIndex + " but got " + index);
}
byte[] data = packet.chunkData();
transfer.out.write(data);
transfer.bytesReceived += data.length;
transfer.nextChunkIndex++;
```

`FILE_END` chốt sổ và **xác minh đủ byte**, sai thì xoá file dở:

```java
// src/main/java/org/example/p2pchat/file/FileReceiver.java:174 (trong onFileEnd)
transfer.out.flush();
transfer.out.close();
if (transfer.bytesReceived != transfer.fileSize) {
    throw new IOException("Size mismatch: expected " + transfer.fileSize
            + " bytes but received " + transfer.bytesReceived);
}
active = null;
notifyProgress(transfer.fileId, transfer.fileName, transfer.bytesReceived, transfer.fileSize);
notifyCompleted(transfer.fileId, transfer.fileName, transfer.target);
```

### An toàn tên file

Bên gửi có thể nói tên là `../../Windows/System32/evil`. `FileReceiver` chỉ giữ basename và làm sạch:

```java
// src/main/java/org/example/p2pchat/file/FileReceiver.java:241
public static String sanitizeFileName(String rawName) {
    ...
    int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    if (separator >= 0) {
        name = name.substring(separator + 1);       // vứt mọi phần đường dẫn
    }
    // thay ký tự Windows cấm <>:"|?* bằng _
    // bỏ ký tự điều khiển, cắt dấu chấm/khoảng trắng cuối
    ...
}
```

---

## 9. Bên nhận nối callback về bubble — và cái bẫy `fileId`

Callback từ tầng file chạy trên thread mạng, mang `fileId`. UI phải tìm đúng bubble. `ChatItemTracker`
làm việc đó — nhưng key của nó từng bị sai:

```java
// src/main/java/org/example/p2pchat/ui/ChatItemTracker.java:16
private record TransferKey(boolean outbound, int fileId) {
}

// (Javadoc ngay trên record giải thích vì sao cần cả chiều lẫn fileId)
```

**Vì sao cần `outbound`:** cả hai peer đều đếm `fileId` từ 1. Nếu A gửi file id=1 và B cũng gửi
file id=1, thì trên máy A có **hai** transfer cùng id=1 nhưng khác chiều. Key cũ chỉ theo `fileId`
khiến offer đến bị nuốt, không tạo bubble, nên ảnh không hiện. Key theo `(outbound, fileId)` sửa
đúng gốc.

Luồng hoàn tất: `FileReceiver.notifyCompleted` → `FileTransferManager` listener → `PeerSession`
`notifyFileEvent` → `MainController.onFileEvent` → tracker → `ChatItem.complete(savedPath)` lưu
đường dẫn thật để render preview.

---

## 10. Ảnh tự hiện không cần Accept — policy auto-receive

Mục tiêu: gửi ảnh thì bên nhận thấy luôn, không phải bấm gì. Nhưng quyết định này **không được nằm
trong UI** — protocol phải tự biết. Vì thế `FileTransferManager` nhận một predicate:

```java
// src/main/java/org/example/p2pchat/file/FileTransferManager.java:22
/** A policy that never auto-receives: every offer waits for the user's decision. */
public static final BiPredicate<String, Long> ASK_ALWAYS = (fileName, fileSize) -> false;
```

Khi xử lý `FILE_START`, sau khi tạo offer nó thử policy:

```java
// src/main/java/org/example/p2pchat/file/FileTransferManager.java:100
case FILE_START -> {
    receiver.onFileStart(packet);
    autoAcceptIfPolicyMatches();
}
```

```java
// src/main/java/org/example/p2pchat/file/FileTransferManager.java:120
private void autoAcceptIfPolicyMatches() {
    FileReceiver.PendingOfferInfo offer = receiver.pendingOffer();
    if (offer == null) {
        return;
    }
    boolean accept;
    try {
        accept = autoReceive.test(offer.fileName(), offer.fileSize());
    } catch (RuntimeException e) {
        AppLogger.error("Auto-receive policy failed for " + offer.fileName(), e);
        return;
    }
    if (accept) {
        acceptOffer(offer.fileId(), receiver.baseDirectory());   // tự gửi FILE_ACCEPT
    }
}
```

`PeerSession` chỉ chuyển predicate xuống, và `MainController` truyền đúng luật "là ảnh ≤ 8 MB":

```java
// src/main/java/org/example/p2pchat/ui/MainController.java:287
PeerSession newSession = new PeerSession(receivedDirectory(), this,
        FileTypes::canPreviewImage);
```

Luật ảnh nằm ở một chỗ duy nhất:

```java
// src/main/java/org/example/p2pchat/ui/FileTypes.java:25
public static boolean canPreviewImage(String fileName, long fileSizeBytes) {
    return isImage(fileName) && fileSizeBytes >= 0 && fileSizeBytes <= MAX_PREVIEW_BYTES;
}
```

### Vì sao thiết kế này tốt

- **Tách bạch:** policy là dữ liệu (predicate), không phải `if` rải rác trong `FileReceiver`.
- **Test được headless:** không cần JavaFX để kiểm "ảnh tự accept, file .zip thì không".
- **Tương thích ngược:** constructor cũ dùng `ASK_ALWAYS`, nên mọi test cũ vẫn chạy y nguyên.
- **Giao thức không đổi:** auto-accept chỉ là một `FILE_ACCEPT` do máy sinh thay vì người bấm. Bên
  gửi không cần biết.

### Preview được vẽ ở đâu

```java
// src/main/java/org/example/p2pchat/ui/ChatBubbleFactory.java:74
private Node fileContent(ChatItem item) {
    VBox content = new VBox(8);
    if (item.isImage() && previewAvailable(item)) {
        content.getChildren().add(imagePreview(item));
    }
    ...
}
```

```java
// src/main/java/org/example/p2pchat/ui/ChatBubbleFactory.java:181
private boolean previewAvailable(ChatItem item) {
    Path path = item.localPath();
    return path != null && Files.isRegularFile(path)
            && FileTypes.canPreviewImage(item.fileName(), item.totalBytes());
}
```

Bên gửi có `localPath` ngay (trỏ file nguồn); bên nhận có sau khi `complete(savedPath)`. Đó là lý do
trước khi accept, bên nhận chưa thể preview — chưa có byte nào trên đĩa.

---

## 11. Chạy thử và tự kiểm chứng

```powershell
# build + test
$env:JAVA_HOME="$env:USERPROFILE\.jdks\ms-21.0.12.1"
.\mvnw.cmd clean package

# mở 2 instance
.\run-peer.cmd A
.\run-peer.cmd B
```

---

## 12. Ba bài tập để thực sự hiểu

1. **Thêm loại gói.** Thêm `PING(8)` vào `MessageType`, một factory `Packet.ping()` trong `Packet`,
   và xử lý nó trong `PacketRouter`. Chạy `.\mvnw.cmd test` và sửa mọi chỗ vỡ — bạn sẽ thấy compiler
   chỉ đường qua từng lớp.
2. **Đổi policy.** Sửa `MainController` thành chỉ tự nhận ảnh dưới 1 MB. Viết test trong
   `AutoReceivePolicyTest` chứng minh ảnh 2 MB vẫn hỏi người dùng.
3. **Bẻ giao thức.** Cố tình gửi một `FILE_CHUNK` với `chunkIndex` nhảy cóc, và quan sát
   `FileReceiver` xoá file dở. Đọc `FileReceiver.java:145` trước để đoán kết quả.

---

## 13. Những hiểu nhầm thường gặp

| Nghĩ rằng... | Thực tế |
|---|---|
| "Host mạnh hơn Connect" | Chỉ khác lúc mở socket. Sau đó hai bên bình đẳng hoàn toàn. |
| "TCP giữ nguyên ranh giới tin nhắn" | Không. TCP là dòng byte. Phải tự khung `type|length`. |
| "`fileId` là duy nhất toàn cục" | Không. Mỗi peer đếm riêng từ 1 → phải key theo cả chiều. |
| "Gọi UI từ thread mạng được" | Không. Luôn qua `Platform.runLater` / `runOnFx`. |
| "Auto-accept là gửi khác đi" | Không. Vẫn là `FILE_ACCEPT`; chỉ khác ai bấm. |
| "Dùng `read()` thay `readFully()` cũng được" | Không. `read` có thể trả ít hơn, parse sẽ lệch khung. |

---

## 14. Tóm tắt trong một màn hình

| Việc | Nơi xử lý | File |
|---|---|---|
| Mở cổng | `PeerServer.accept` | `network/PeerServer.java:32` |
| Kết nối ra | `PeerClient.connect` | `network/PeerClient.java:15` |
| Khung nhị phân | `PacketCodec.write/read` | `protocol/PacketCodec.java:17,29` |
| Định nghĩa byte payload | `Packet` factories/parsers | `protocol/Packet.java` |
| 2 luồng socket | `Connection.start` | `network/Connection.java:46` |
| Định tuyến gói | `PeerSession.PacketRouter` | `session/PeerSession.java:291` |
| Gửi chat | `ChatManager.sendMessage` | `chat/ChatManager.java:20` |
| Gửi file theo chunk | `FileSender.send` | `file/FileSender.java:27` |
| Nhận file + trạng thái offer | `FileReceiver` | `file/FileReceiver.java:63,90,137,163` |
| Cổng chờ quyết định | `TransferGate.awaitDecision` | `file/TransferGate.java:15` |
| Policy ảnh tự nhận | `FileTransferManager.autoAcceptIfPolicyMatches` | `file/FileTransferManager.java:120` |
| Khớp callback với bubble | `ChatItemTracker.TransferKey` | `ui/ChatItemTracker.java:16` |
| Vẽ preview ảnh | `ChatBubbleFactory.fileContent/imagePreview` | `ui/ChatBubbleFactory.java:74,160` |
| Chuyển sang FX thread | `MainController.runOnFx` | `ui/MainController.java:565` |

Muốn hiểu sâu hơn nữa: `docs/PROTOCOL.md` mô tả byte-level, `docs/SPECS.md` là đặc tả gốc, và mỗi
lớp đều có test tương ứng trong `src/test/java/org/example/p2pchat/`.
