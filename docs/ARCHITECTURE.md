# Kiến trúc ứng dụng Chat — Giải thích từ đầu

> **Đọc xong file này bạn sẽ hiểu:** thư mục `src/` tổ chức ra sao, một byte từ bàn phím đi qua
> những lớp nào để tới máy kia, giao thức nhị phân được đóng gói thế nào, và địa chỉ
> unicast/multicast hoạt động ra sao.

Tài liệu này viết để **học**, không phải để tra cứu. Mỗi khái niệm đi kèm code thật (kèm tên file
và method), nên bạn mở file lên là thấy ngay. Muốn xem đặc tả khô khan thì đọc `docs/PROTOCOL.md`,
`docs/SPECS-UNICAST-MULTICAST.md` (đặc tả địa chỉ hoá) và `docs/SPECS.md`.

> **Cập nhật gần đây:** ứng dụng đã chuyển hẳn sang mô hình **client–server**, và **chỉ có chat**.
> Toàn bộ gói `FILE_*`, package `file/`, preview ảnh và panel truyền file đã bị xoá, nên
> `MessageType` chỉ còn `CHAT`, `DISCONNECT`, `HELLO`, `RELAY`, `ROSTER` và các id được đánh lại gọn
> (xem mục 4). Phần địa chỉ hoá vẫn nguyên: mỗi **client** được server cấp một **client id**
> (`#1`, `#2`, `#3`, ... theo thứ tự kết nối), server phát **roster** để mọi client biết ai đang
> online và mình là ai, và ô **"To:"** trong UI cho phép chọn người nhận: **unicast** (1 client),
> **multicast** (một nhóm), **broadcast** (tất cả).
>
> **Server không chat.** Nó chỉ trung chuyển: không có ô soạn tin, không có menu "To:", không bao
> giờ hiện bubble, không có client id, và không nằm trong roster. Vai trò của nó đúng bằng một
> multicast router — bên nhân bản packet.

---

## 1. Bức tranh lớn trong một câu

Nhiều instance giống hệt nhau nói chuyện qua **các socket TCP** theo mô hình **client–server**: một
instance chạy ở mode **SERVER**, những instance còn lại chạy ở mode **CLIENT**. Mỗi socket được chia
sẻ bởi hai luồng (một chỉ đọc, một chỉ ghi); mọi thứ (chat, danh bạ, điều khiển) đều được bọc thành
**packet nhị phân có khung độ dài** để bên nhận biết một gói kết thúc ở đâu.

```
   Client B (CLIENT)                     Server (SERVER)                    Client C (CLIENT)
   ┌────────────────┐                ┌────────────────────────┐            ┌────────────────┐
   │   JavaFX UI    │                │      JavaFX UI         │            │   JavaFX UI    │
   │ MainController │                │    MainController      │            │ MainController │
   └───────┬────────┘                │ danh sách client + log │            └───────┬────────┘
           │ SessionListener         └───────────┬────────────┘                    │ SessionListener
   ┌───────▼────────┐                ┌───────────▼────────────┐            ┌───────▼────────┐
   │  ChatSession   │                │      ChatSession       │            │  ChatSession   │
   │  ChatManager   │                │ ClientRegistry{conn→tên, id}         │  ChatManager   │
   └───────┬────────┘                │      ChatManager       │            └───────┬────────┘
           │ PacketSink              └──┬──────────────────┬──┘                    │ PacketSink
   ┌───────▼────────┐   TCP socket  ┌───▼────────┐  ┌──────▼─────┐   TCP socket  ┌──▼────────────┐
   │   Connection   │◄═════════════►│ Connection │  │ Connection │◄═════════════►│  Connection   │
   │ reader + writer│ type|len|data │  r + w     │  │   r + w    │ type|len|data │ reader+writer │
   └────────────────┘               └────────────┘  └────────────┘               └───────────────┘
```

Điểm mấu chốt: **SERVER và CLIENT chỉ khác nhau ở vai trò relay**. Server giữ nhiều `Connection`
(một `ClientRegistry`), client chỉ giữ một. Client gửi chat lên server; server *chuyển tiếp* (relay)
cho đúng những client mà địa chỉ chỉ ra, và **không bao giờ gửi ngược lại cho người gửi** — nhờ vậy
không sinh vòng lặp, và topology là một ngôi sao (star).

Vì chỉ server có nhiều socket, server cũng là nơi duy nhất biết "địa chỉ" của từng máy: nó cấp
**client id**, phát danh bạ, và quyết định mỗi tin nhắn đi ra những socket nào. Client chỉ việc nói
*muốn gửi cho ai* (`ChatScope` + danh sách id) — xem mục 7.

Server **không chat**. Nó không có ô soạn tin, không có menu "To:", không vẽ bubble nào, và bản thân
nó không có client id nên không địa chỉ nào trỏ tới nó được. Vai trò của nó đúng bằng một multicast
router: một packet vào, nhiều packet ra, chỉ trên những "link" đã chọn.

---

## 2. Bản đồ thư mục

```
src/main/java/org/example/p2pchat/
│
├── Launcher.java              ← JVM entry point (class thường, KHÔNG kế thừa Application)
├── Main.java                  ← JavaFX Application, dựng Scene
│
├── ui/                        ← tầng giao diện, chỉ chạy trên FX thread
│   ├── MainController.java    ← ba màn hình (kết nối / server / client), nút bấm, ô "To:"
│   ├── ChatItem.java          ← một dòng chat: text + chiều + địa chỉ
│   ├── ChatItemTracker.java   ← giữ bubble đúng thứ tự, báo cho view khi có thay đổi
│   └── ChatBubbleFactory.java ← biến ChatItem thành bubble trái/phải
│
├── session/
│   ├── ChatSession.java       ← vòng đời kết nối, định tuyến packet, cấp client id, relay, log
│   ├── ClientRegistry.java    ← registry nhiều client: connection → display name
│   ├── ClientInfo.java        ← một dòng danh bạ: (id, tên) của một client
│   └── SessionListener.java   ← hợp đồng callback session → UI
│
├── network/                   ← tầng socket thô, không biết gì về chat
│   ├── Acceptor.java          ← ServerSocket + accept
│   ├── Connector.java         ← Socket + connect
│   ├── Connection.java        ← vòng đọc/ghi có khung, hàng đợi gửi
│   └── PacketSink.java        ← interface "gửi 1 packet" (để code khác không phụ thuộc Socket)
│
├── protocol/                  ← định dạng byte trên dây
│   ├── MessageType.java       ← enum các loại gói kèm id số
│   ├── ChatScope.java         ← UNICAST / MULTICAST / BROADCAST (địa chỉ gửi)
│   ├── Packet.java            ← factory + parse payload (kèm addressing block)
│   └── PacketCodec.java       ← ghi/đọc khung type|length|payload
│
├── chat/
│   ├── ChatManager.java       ← gửi/xử lý CHAT
│   └── ChatListener.java      ← callback chat
│
└── util/
    ├── NetworkUtils.java      ← validate port/IP
    └── AppLogger.java         ← log có timestamp
```

**Quy tắc vàng về hướng phụ thuộc:** `ui → session → chat → network, protocol`. Không bao giờ có mũi
tên ngược. Vì thế `network` không cần biết "chat" là gì, và `protocol` không cần biết JavaFX tồn tại.

### Vì sao `Launcher` tách khỏi `Main`?

Nếu class `main` kế thừa `javafx.application.Application` mà JavaFX nằm trên classpath (fat jar),
JVM sẽ báo *"JavaFX runtime components are missing"*. Cách lách chuẩn là để class thường đứng ra gọi:

```java
// src/main/java/org/example/p2pchat/Launcher.java (main)
public static void main(String[] args) {
    Application.launch(Main.class, args);
}
```

`Main` vẫn là `Application` thật, chỉ có "cửa vào" là class thường:

```java
// src/main/java/org/example/p2pchat/Main.java (start)
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

## 3. Kết nối TCP — nơi SERVER và CLIENT khác nhau

### SERVER: mở cổng và chờ

```java
// src/main/java/org/example/p2pchat/network/Acceptor.java (accept)
public Connection accept(Connection.Listener listener) throws IOException {
    AppLogger.info("Listening on port " + port());
    var socket = serverSocket.accept();          // block tới khi có client
    socket.setTcpNoDelay(true);                  // gửi ngay, không gom buffer
    AppLogger.info("Client connected: " + socket.getInetAddress().getHostAddress());
    return new Connection(socket, listener);
}
```

`accept()` **block**, nên `ChatSession` gọi nó trên thread riêng `chat-accept`, không phải FX thread.
Vòng lặp này **không dừng sau một client** — nó accept liên tục cho tới khi server đóng:

```java
// src/main/java/org/example/p2pchat/session/ChatSession.java (acceptLoop)
while (!closed.get()) {
    Acceptor current = server;
    if (current == null) {
        return;
    }
    PacketRouter router = new PacketRouter();
    Connection accepted = current.accept(router);   // mỗi client một router riêng
    router.attach(accepted);
    accepted.start();
    register(accepted);                             // thêm vào ClientRegistry
}
```

### CLIENT: chủ động mở socket

```java
// src/main/java/org/example/p2pchat/network/Connector.java (connect)
Socket socket = new Socket();
socket.setTcpNoDelay(true);
socket.connect(new InetSocketAddress(host.trim(), port), timeoutMillis);
```

Cổng local do OS tự cấp — bên CLIENT không cần chọn. Sau khi socket mở, hai bên đều bọc trong
`Connection` giống hệt nhau; client gắn vào `ClientRegistry` đúng một entry.

### `ClientRegistry` — sổ danh bạ các client

Server cần biết "connection này là ai" để gắn tên vào tin nhắn. `ClientRegistry` là một
`LinkedHashMap` được bọc `synchronized`, key theo `Connection`, value là tên hiển thị:

```java
// src/main/java/org/example/p2pchat/session/ClientRegistry.java
public synchronized void add(T connection, String name) {
    Objects.requireNonNull(connection, "connection");
    names.put(connection, normalizeName(name));
}

public synchronized List<T> connectionsExcept(T excluded) {   // dùng để broadcast
    List<T> result = new ArrayList<>();
    for (T connection : names.keySet()) {
        if (!connection.equals(excluded)) {
            result.add(connection);
        }
    }
    return result;
}
```

Tên đến từ gói `HELLO` mà **client** gửi ngay sau khi kết nối. Server không gửi `HELLO`: nó không
có tên để giới thiệu và không ai cần address tới nó. Vì `HELLO` có thể tới **trước** khi `register()`
kịp chạy, `register()` dùng `addIfAbsent` để không ghi đè tên vừa học được, và chỉ client mới được
báo "đã kết nối":

```java
// src/main/java/org/example/p2pchat/session/ChatSession.java (register)
private void register(Connection connection) {
    hub.addIfAbsent(connection, ClientRegistry.DEFAULT_NAME);   // HELLO sẽ cập nhật tên sau
    idOf(connection);                                           // cấp địa chỉ TRƯỚC khi ai kịp đặt tên
    publishRoster();                                            // để mọi client biết có người mới
    if (isServer()) {
        // Server không có tên để giới thiệu, và không có gì để nói với client ngoài roster.
        return;
    }
    sendHelloQuietly(connection);
    notifyStatus("Connected");
}
```

> **Địa chỉ không nằm trong `ClientRegistry`.** Client id sống trong một `Map<Connection, Integer>`
> riêng của `ChatSession`, vì `ClientRegistry` là lớp generic (`ClientRegistry<T>`) dùng cho cả test
> và không nên biết gì về địa chỉ hoá. Muốn tra id của một connection: `ChatSession.idOf(connection)`.

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
// src/main/java/org/example/p2pchat/protocol/PacketCodec.java (write)
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
// src/main/java/org/example/p2pchat/protocol/PacketCodec.java (read)
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
// src/main/java/org/example/p2pchat/protocol/MessageType.java
public enum MessageType {
    CHAT(1),
    DISCONNECT(2),      // v1: đóng phiên một cách êm ái
    HELLO(3),           // v3: giới thiệu tên hiển thị khi vừa kết nối
    RELAY(4),           // v3: chat đã được server chuyển tiếp, kèm tên gốc
    ROSTER(5);          // v4: server gửi danh bạ (id + tên) cho từng client
    ...
}
```

Id **ổn định theo luật**: loại gói mới lấy id trống kế tiếp, và một id không bao giờ được dùng lại
cho loại khác. Ở v5 các id được đánh lại gọn một lần sau khi nhóm `FILE_*` bị xoá — đó là lý do
`DISCONNECT` giờ là `2` chứ không phải `5`.

### Hình dạng payload từng loại

`Packet` là nơi duy nhất biết cách nhồi/rút byte: mỗi factory (`Packet.chat`, `Packet.relay`,
`Packet.roster`, `Packet.hello`, `Packet.disconnect`) tự nhồi byte, và mỗi accessor
(`chatText()`, `relaySender()`, `roster()`, ...) tự rút ra — nhưng trước hết gọi `requireType(...)`
để chắc chắn đang đọc đúng loại gói, sai loại thì ném lỗi ngay thay vì trả về rác.

| Gói | Payload |
|---|---|
| `CHAT` | `u8 scope` + `u16 count` + `count × u16 id` + `text[UTF-8]` |
| `HELLO` | tên hiển thị UTF-8 thuần |
| `RELAY` | `u16 senderLen` + `sender[UTF-8]` + (địa chỉ y như `CHAT`) |
| `ROSTER` | `u16 selfId` + `u16 count` + `count × (u16 id + u16 nameLen + name[UTF-8])` |
| `DISCONNECT` | rỗng |

Địa chỉ (`scope` + danh sách id) xuất hiện **hai lần**: trong `CHAT` mà client gửi lên, và trong
`RELAY` mà server gửi xuống — y nguyên, không đổi. Nhờ vậy bên nhận vẫn biết tin nhắn vừa rồi là gửi
riêng cho mình hay gửi cho cả nhóm, dù nó chỉ nhận được packet từ server.

---

## 5. `Connection` — hai luồng, một socket

`Connection` biến `Socket` thô thành hai thread tách biệt:

```java
// src/main/java/org/example/p2pchat/network/Connection.java (start)
public void start() {
    ...
    reader = new Thread(this::readLoop, "chat-reader");
    writer = new Thread(this::writeLoop, "chat-writer");
    reader.setDaemon(true);
    writer.setDaemon(true);
    reader.start();
    writer.start();
}
```

**Luồng đọc** — block trên `PacketCodec.read`, đẩy từng packet cho `listener.onPacket`:

```java
// src/main/java/org/example/p2pchat/network/Connection.java (readLoop)
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
// src/main/java/org/example/p2pchat/network/Connection.java (writeLoop)
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

**Vì sao hàng đợi có giới hạn 256:** để một client bắn packet ào ạt không làm tràn RAM bên gửi khi
mạng chậm. Nếu đầy, `send` ném lỗi "the client is too slow" sau 30 giây thay vì phình bộ nhớ vô hạn:

```java
// src/main/java/org/example/p2pchat/network/Connection.java (send)
if (!outgoing.offer(packet, SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
    throw new IOException("Send queue is full; the client is too slow");
}
```

---

## 6. Định tuyến packet — `ChatSession.PacketRouter`

Mỗi `Connection` có **một `PacketRouter` riêng** (khác bản cũ dùng chung một router). Nhờ vậy router
biết packet này đến từ connection nào — chính là `owner` — và tra ra tên người gửi qua
`ClientRegistry`:

```java
// src/main/java/org/example/p2pchat/session/ChatSession.java (PacketRouter)
private volatile Connection owner;

@Override
public void onPacket(Packet packet) {
    try {
        route(packet);
    } catch (RuntimeException e) {
        // Một packet hỏng (payload cắt cụt, scope lạ) chỉ bị bỏ, không được giết thread đọc.
        AppLogger.error("Could not handle " + packet.type() + " packet", e);
    }
}

private void route(Packet packet) {
    MessageType type = packet.type();
    Connection source = owner;
    switch (type) {
        case CHAT -> handleIncomingChat(source, packet);   // xem mục 7
        case RELAY -> chatManager.handle(packet, hub.nameOf(source));  // tên nằm sẵn trong payload
        case ROSTER -> onRosterReceived(source, packet);   // chỉ client mới dùng
        case HELLO -> {
            hub.add(source, packet.helloName());
            publishRoster();                               // tên mới phải tới mọi danh bạ
            notifyStatus("Connected");
        }
        case DISCONNECT -> {
            AppLogger.info("Client sent DISCONNECT");
            unregister(source);
        }
    }
}
```

`ChatManager.handle(packet, sender)` nhận cả `CHAT` lẫn `RELAY`: với `CHAT` nó dùng `sender` truyền
vào, với `RELAY` nó lấy tên từ chính payload (`packet.relaySender()`). Nhờ vậy tầng chat không cần
biết packet đi thẳng hay qua relay.

`try/catch` bao quanh `route` là thay đổi nhỏ nhưng quan trọng: địa chỉ do người dùng chọn, và một
payload dị dạng (scope lạ, `count` vượt quá số byte còn lại) sẽ ném `IllegalStateException` từ
`Packet`. Trước v4 không có chuyện đó vì payload chat chỉ là text thuần.

`PacketSink` là một functional interface — nhờ nó `ChatManager` không cần giữ `Socket`, chỉ cần
"một cái ống để bắn packet":

```java
// src/main/java/org/example/p2pchat/network/PacketSink.java
@FunctionalInterface
public interface PacketSink {
    void send(Packet packet) throws IOException;
}
```

---

## 7. Chat — luồng đầy đủ của một tin nhắn (1 server + 2 client)

Chỉ có **một** đường đi, cho mọi instance:

```
  Client B bấm Send
    │ CHAT "xin chao" ─────────► Server
    │                              ├─ Server KHÔNG chat: không hiện bubble nào
    │                              ├─ ghi 1 dòng relay log (ai gửi, scope, ai nhận, bao nhiêu byte)
    │                              └─ RELAY{B,"xin chao"} ──► Client C
    │                                                            └─ hiện "B: xin chao"
    └─ (B tự vẽ bubble outbound của mình)
```

Server **không bao giờ gửi `CHAT` và không bao giờ hiện bubble**: nó chỉ đọc packet vào, chọn socket
đích, và bắn `RELAY` đi. Còn ở client, khi người dùng bấm Send thì **UI đã tự vẽ bubble outbound**
rồi, nên nếu session cũng phát lại cho listener local thì tin sẽ bị nhân đôi. Vì thế `sendChat` ở
client chỉ gửi `CHAT` lên server, còn ở server thì ném lỗi — server không có đường gửi chat:

```java
// src/main/java/org/example/p2pchat/session/ChatSession.java (sendChat)
if (isServer()) {
    throw new IllegalStateException("The server relays chat but does not send it");
}
chatManager.sendMessage(text, resolvedScope, resolvedTargets);   // client gửi CHAT lên server
```

### Địa chỉ gửi — unicast / multicast / broadcast

Vấn đề: một client chỉ có **một socket** (tới server), nên nó không thể tự gửi riêng cho ai — và cũng
không biết ai đang online. Cả hai việc đó là của server.

**Bước 1 — có địa chỉ.** Server cấp cho mỗi **client** một `client id` 16-bit theo thứ tự kết nối:

```java
// src/main/java/org/example/p2pchat/session/ChatSession.java
public static final int FIRST_CLIENT_ID = 1;        // client đầu tiên là #1
private final Map<Connection, Integer> clientIds = new ConcurrentHashMap<>();
private final AtomicInteger nextClientId = new AtomicInteger(FIRST_CLIENT_ID);  // #1, #2, ...

private int idOf(Connection connection) {
    return clientIds.computeIfAbsent(connection, key -> nextClientId.getAndIncrement());
}
```

Server **không có id nào cả** (`UNASSIGNED_CLIENT_ID`), nên không địa chỉ nào trỏ tới nó được. Id
được cấp theo *connection* chứ không theo con người: một client kết nối lại nhận id kế tiếp, và chỉ
khi server khởi động lại thì mới đếm lại từ `#1`.

**Bước 2 — phổ biến địa chỉ.** Client không thấy được `ClientRegistry` của server, nên server đẩy
**roster** xuống: `ROSTER { selfId, [(id, tên), ...] }`. `selfId` là id của chính máy nhận, nên đây
là packet mà mỗi client nhận được một bản khác nhau — server dựng danh bạ một lần rồi gửi N bản, mỗi
bản một `selfId`:

```java
// src/main/java/org/example/p2pchat/session/ChatSession.java (publishRoster)
for (Connection connection : hub.connections()) {
    connection.send(Packet.roster(idOf(connection), entriesFor(published, connection)));
}                                          // ai cũng thấy danh bạ, nhưng biết mình là ai
```

Danh bạ **chỉ gồm client**; server không nằm trong đó. Roster được gửi lại mỗi khi có client vào, ra,
hoặc đổi tên. Client nhận roster thì lưu `selfId` và danh bạ, rồi báo lên UI (`onRosterChanged`) để
vẽ lại menu "To:".

**Bước 3 — chọn người nhận.** UI dịch lựa chọn thành `(scope, targets)`:

| Menu "To:" | `ChatScope` | `targets` |
|---|---|---|
| không tick ai | `BROADCAST` | rỗng |
| tick 1 client | `UNICAST` | 1 id |
| tick ≥ 2 client | `MULTICAST` | các id đó |

**Bước 4 — server nhân bản (replication).** Đây đúng là việc một multicast router làm: một packet
vào, nhiều packet ra, chỉ trên những "link" đã chọn. Server chỉ relay, không tự hiện gì cho mình:

```java
// src/main/java/org/example/p2pchat/session/ChatSession.java (handleIncomingChat)
Delivery delivery = resolve(source, scope, targets);
if (!delivery.destinations().isEmpty()) {
    sendRelay(delivery.destinations(), sender, scope, targets, text);   // không có nhánh nào
    relayed.incrementAndGet();                                          // cho chính server
}
notifyServerLog(relayLine(source, scope, targets, delivery, text));
```

`resolve` là chỗ luật lệ nằm hết: nó **loại người gửi** khỏi danh sách đích, chọn *tất cả trừ người
gửi* nếu là broadcast, và với địa chỉ có tên thì chỉ lấy đúng những id đó. Nó cũng trả về danh sách
id **không còn ai giữ**, để log biết đường ghi `NOT CONNECTED`:

```java
// src/main/java/org/example/p2pchat/session/ChatSession.java (resolve)
for (Connection connection : hub.connections()) {
    int id = idOf(connection);
    connected.add(id);
    if (connection.equals(source)) {
        continue;                       // người gửi không bao giờ nhận lại tin của mình
    }
    if (scope.isBroadcast() || targets.contains(id)) {
        destinations.add(connection);
    }
}
```

Ba hệ quả cần nhớ:

- **Người gửi không bao giờ nhận lại tin của mình** — `source` bị loại khỏi mọi danh sách đích.
  Server cũng không bao giờ nằm trong danh sách đích: nó không có id và không phải người tham gia.
- **Địa chỉ không tồn tại thì bị bỏ qua, không phải lỗi.** Client rời đi giữa lúc chọn và lúc gửi →
  server ghi `#N NOT CONNECTED` vào relay log, phần còn lại vẫn nhận. Không tự động gửi cho tất cả.
- **Gửi cho chính mình thì không tới đâu cả.**

**Bước 5 — bên nhận biết mình được gửi riêng.** Vì `RELAY` mang nguyên khối địa chỉ, client đọc
được `scope` + `targets` và tra tên từ danh bạ của mình:

```java
// src/main/java/org/example/p2pchat/session/ChatSession.java (audienceNames)
names.add(name == null ? "#" + target : name);   // roster đổi rồi thì vẫn hiện được "#4"
```

Nhờ vậy bubble mới ghi được `private` hoặc `group · An, Bình` — xem `ChatBubbleFactory.audienceText`.
Danh sách này là **những người người gửi đã chọn**, kể cả chính máy đang xem, chứ không phải "những
máy khác trong phòng".

### Relay log của server

Server là **điểm nhân bản**, nên nó ghi lại mỗi lần chuyển tiếp — đây là thứ chứng minh
unicast/multicast/broadcast chạy thật. `ChatSession` dựng sẵn chuỗi rồi đẩy lên UI qua
`onServerLog`; địa chỉ lấy từ `Connection.remoteAddress()` (IP **và** cổng ephemeral, thứ phân biệt
được hai client cùng máy):

```
An #1 @127.0.0.1:54321  BROADCAST  -> everyone (2 delivered, 12 B)
Binh #2 @127.0.0.1:54402  UNICAST  -> An #1 (1 delivered, 7 B)
Cuong #3 @127.0.0.1:54418  MULTICAST  -> An #1, Binh #2 (2 delivered, 20 B)
An #1 @127.0.0.1:54321  UNICAST  -> #7 NOT CONNECTED (0 delivered, 5 B)
+ An #1 @127.0.0.1:54321 joined
- Binh #2 @127.0.0.1:54402 left (1 client remains)
```

Nội dung tin nhắn **không** bao giờ được ghi vào log: server chỉ chuyển tiếp, nó không đọc thứ nó
chuyển, và chỉ cần kích thước là đủ. Cùng những dòng đó cũng ra console qua `AppLogger`.

### Gửi (client → server)

`ChatManager.sendMessage` đóng gói `CHAT`; server sẽ là bên gắn tên khi relay:

```java
// src/main/java/org/example/p2pchat/chat/ChatManager.java
public void sendMessage(String message, ChatScope scope, List<Integer> targets) {
    if (message == null || message.isBlank()) {
        return;                                // chặn tin rỗng
    }
    try {
        AppLogger.info("Sending " + scope + " CHAT packet");
        sink.send(Packet.chat(scope, targets, message.strip()));
    } catch (IOException e) {
        reportError("Failed to send message: " + describe(e));
    } catch (IllegalArgumentException e) {
        reportError("Invalid chat address: " + describe(e));   // scope và targets không khớp
    }
}
```

`Packet.chat` nhồi khối địa chỉ rồi mới tới text; `Packet.relay` nhồi thêm độ dài + tên người gửi
rồi tới **y nguyên** khối địa chỉ đó:

```java
// src/main/java/org/example/p2pchat/protocol/Packet.java
public static Packet chat(ChatScope scope, List<Integer> targets, String text) {
    Address address = Address.of(scope, targets);      // kiểm tra scope ↔ targets khớp nhau
    byte[] textBytes = utf8(text);
    ByteBuffer buffer = ByteBuffer.allocate(address.byteSize() + textBytes.length);
    address.writeTo(buffer);                           // u8 scope + u16 count + count × u16 id
    buffer.put(textBytes);
    return new Packet(MessageType.CHAT, buffer.array());
}

public static Packet relay(String sender, ChatScope scope, List<Integer> targets, String text) {
    byte[] senderBytes = utf8(sender);
    Address address = Address.of(scope, targets);
    byte[] textBytes = utf8(text);
    ByteBuffer buffer = ByteBuffer.allocate(2 + senderBytes.length + address.byteSize() + textBytes.length);
    buffer.putShort((short) senderBytes.length);       // độ dài tính bằng BYTE, không phải ký tự
    buffer.put(senderBytes);
    address.writeTo(buffer);
    buffer.put(textBytes);
    return new Packet(MessageType.RELAY, buffer.array());
}
```

`Address` là record private của `Packet`: nó chỉ được tạo ra qua `Address.of(...)` (kiểm tra) và chỉ
được tạo lại từ byte qua `Address.read(...)` (kiểm tra ngược lại), nên không có đường nào lọt một
`CHAT` mang `UNICAST` mà hai id.

### Nhận

Client nhận `RELAY` và gọi `ChatManager.handle(packet, ...)` (tên người gửi nằm sẵn trong payload).
`ChatManager` chuẩn hoá cả hai loại về `(tên, nội dung, scope, targets)` — trên client, một `CHAT`
tới thẳng cũng được coi là "gửi cho mình", dù đường đi thật luôn là qua `RELAY`:

```java
// src/main/java/org/example/p2pchat/chat/ChatManager.java
public void handle(Packet packet, String sender) {
    try {
        String name;
        String message;
        ChatScope scope;
        List<Integer> targets;
        if (packet.type() == MessageType.RELAY) {
            name = packet.relaySender();
            scope = packet.relayScope();
            targets = packet.relayTargets();
            message = packet.relayText();
        } else {
            name = sender;
            scope = packet.chatScope();
            targets = packet.chatTargets();
            message = packet.chatText();
        }
        AppLogger.info("Receiving " + scope + " CHAT packet");
        listener.onChatMessage(name, message, scope, targets);
    } catch (RuntimeException e) {
        reportError("Received invalid chat packet: " + describe(e));
    }
}
```

`ChatListener` giờ có hai dạng callback. Dạng 2 tham số là bản cũ, vẫn `abstract`; dạng 4 tham số là
`default` và **gọi ngược lại** dạng 2 tham số:

```java
// src/main/java/org/example/p2pchat/chat/ChatListener.java
void onChatMessage(String sender, String message);            // bản cũ, bắt buộc implement

default void onChatMessage(String sender, String message, ChatScope scope, List<Integer> targets) {
    onChatMessage(sender, message);          // listener cũ vẫn chạy: mọi tin đều là broadcast
}
```

Nhờ chiều `default` này, ~6 listener trong test cũ không phải sửa một dòng nào. `MainController` thì
override dạng 4 tham số để vẽ thêm nhãn địa chỉ (xem mục dưới). Bản thân `ChatSession.ChatBridge`
implement **cả hai**, và dạng 4 tham số mới là bản thật sự được gọi.

### Nhảy sang UI — và đây là chỗ dễ sai nhất

`listener` ở đây là `ChatSession.ChatBridge`, nó gọi tiếp `SessionListener` (chính là
`MainController`). Vì `handle` chạy trên **thread đọc**, không phải FX thread, `MainController` phải
chuyển làn:

```java
// src/main/java/org/example/p2pchat/ui/MainController.java
@Override
public void onChatMessage(String sender, String message) {
    onChatMessage(sender, message, ChatScope.BROADCAST, List.of());
}

@Override
public void onChatMessage(String sender, String message, ChatScope scope, List<String> audience) {
    runOnFx(() -> appendChat(ChatItem.inboundText(sender, message, scope, audience,
            System.currentTimeMillis())));
}
```

`ChatItem` giữ luôn tên người gửi và cách tin nhắn được gửi; `ChatBubbleFactory` vẽ chúng thành dòng
`.bubble-sender` và `.bubble-audience` nhỏ phía trên nội dung bubble đến:

```java
// src/main/java/org/example/p2pchat/ui/ChatBubbleFactory.java (create)
if (!item.outbound() && item.sender() != null && !item.sender().isBlank()) {
    Label sender = new Label(item.sender());
    sender.getStyleClass().add("bubble-sender");
    bubble.getChildren().add(sender);
}
String audience = audienceText(item);       // null nếu là broadcast → không vẽ gì thêm
if (audience != null) {
    Label addressing = new Label(audience);
    addressing.getStyleClass().add("bubble-audience");
    bubble.getChildren().add(addressing);
}
```

```java
// src/main/java/org/example/p2pchat/ui/MainController.java (runOnFx)
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
// src/main/java/org/example/p2pchat/ui/ChatBubbleFactory.java (create)
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

## 8. Chạy thử và tự kiểm chứng (3 máy)

```powershell
# build + test
$env:JAVA_HOME="$env:USERPROFILE\.jdks\ms-21.0.12.1"
.\mvnw.cmd clean package

# mở 3 instance
.\run-chat.cmd Server
.\run-chat.cmd An
.\run-chat.cmd Binh
```

Cách chạy: instance A để nguyên mode **SERVER**, nhập Display name (ví dụ `Server`), bấm **Start
Server**. Hai instance B và C chọn **CLIENT**, nhập tên riêng (`An`, `Binh`) và trỏ tới
`127.0.0.1:5000`.

Ở mỗi cửa sổ **client**, ô **To:** ngay trên khung nhập liệu chính là nơi chọn người nhận (cửa sổ
server không có ô này — nó không chat):

| Bạn tick | Nút hiện | Gửi đi |
|---|---|---|
| không tick ai | `Everyone` | broadcast — mọi client khác đều nhận |
| 1 client | tên client đó | unicast — chỉ client đó nhận, bubble ghi `private` |
| 2 client trở lên | `An, Binh` | multicast — đúng nhóm đó nhận, bubble ghi `group · An, Binh` |

Thử ba lần để thấy rõ: B tick `An` rồi gửi → chỉ An thấy. B tick `An` + `Binh` → chỉ hai người đó
thấy, C không thấy. B bỏ tick hết → cả An và Binh đều thấy, trừ chính B. Tin của chính bạn luôn nằm
bên phải và không bao giờ bị gửi vòng lại. Cửa sổ **Server không bao giờ hiện bubble nào** — nhưng
relay log của nó ghi đủ ba dòng `UNICAST` / `MULTICAST` / `BROADCAST` để bạn đối chiếu.

---

## 9. Ba bài tập để thực sự hiểu

1. **Thêm loại gói.** Thêm `PING(6)` vào `MessageType`, một factory `Packet.ping()` trong `Packet`,
   và xử lý nó trong `PacketRouter`. Chạy `.\mvnw.cmd test` và sửa mọi chỗ vỡ — bạn sẽ thấy compiler
   chỉ đường qua từng lớp.
2. **Nhóm cố định.** Thêm một nút "nhóm" trong menu "To:" để tick sẵn `An, Binh` bằng một cú bấm —
   chỉ là thay đổi UI, không cần đụng tới protocol.
3. **Địa chỉ hoá cho packet mới.** Nếu sau này thêm một loại packet cần gửi riêng cho một client
   (ví dụ một lời mời), hãy tái dùng `Address` của `Packet` thay vì tự nghĩ ra trường đích mới: khối
   địa chỉ đã có sẵn kiểm tra `scope ↔ count`, và `resolve` đã biết chọn socket.

---

## 10. Những hiểu nhầm thường gặp

| Nghĩ rằng... | Thực tế |
|---|---|
| "SERVER mạnh hơn CLIENT" | Chỉ khác vai trò relay. Client gửi chat lên; server chỉ trung chuyển và **không chat**. |
| "Chat client gửi thẳng cho client khác" | Không. Client chỉ gửi `CHAT` cho server; server phát `RELAY` cho người còn lại. |
| "Client cũng relay để tăng tốc" | Không. Chỉ server relay, nếu không sẽ sinh vòng lặp vô hạn. |
| "Server cũng thấy tin nhắn" | Không. Server không chat nên không vẽ bubble nào; log chỉ ghi người gửi, scope, người nhận và kích thước, **không ghi nội dung**. |
| "Gửi riêng thì client tự gửi thẳng" | Không. Client vẫn gửi `CHAT` cho server, kèm địa chỉ; server mới là bên chọn socket. |
| "Tick 2 người là gửi 2 tin riêng" | Không. Một `CHAT` với `MULTICAST` + 2 id; server tạo 2 packet `RELAY` từ 1 packet vào. |
| "Địa chỉ là số hiệu máy" | Không. Đó là id do server cấp cho từng client theo thứ tự kết nối (`#1`, `#2`, ...); server không có id; không liên quan IP/port. |
| "Gửi cho client đã out là lỗi" | Không. Server ghi `#N NOT CONNECTED` vào log rồi gửi cho phần còn lại, không tự broadcast thay thế. |
| "Nhãn `group · An, Binh` là những máy đã nhận" | Không. Đó là **những người người gửi đã chọn**, kể cả chính bạn — kể cả khi một người đã rời phòng. |
| "TCP giữ nguyên ranh giới tin nhắn" | Không. TCP là dòng byte. Phải tự khung `type|length`. |
| "Gọi UI từ thread mạng được" | Không. Luôn qua `Platform.runLater` / `runOnFx`. |
| "Id gói có thể dùng lại cho loại khác" | Không. Id ổn định; v5 đánh lại một lần sau khi xoá `FILE_*`, sau đó không tái sử dụng. |
| "Dùng `read()` thay `readFully()` cũng được" | Không. `read` có thể trả về ít hơn, parse sẽ lệch khung. |

---

## 11. Tóm tắt trong một màn hình

| Việc | Nơi xử lý | File |
|---|---|---|
| Mở cổng | `Acceptor.accept` | `network/Acceptor.java` |
| Kết nối ra | `Connector.connect` | `network/Connector.java` |
| Khung nhị phân | `PacketCodec.write/read` | `protocol/PacketCodec.java` |
| Định nghĩa byte payload | `Packet` factories/parsers | `protocol/Packet.java` |
| 2 luồng socket | `Connection.start` | `network/Connection.java` |
| Danh bạ nhiều client | `ClientRegistry.add/addIfAbsent/connectionsExcept` | `session/ClientRegistry.java` |
| Định tuyến gói | `ChatSession.PacketRouter` | `session/ChatSession.java` |
| Cấp địa chỉ client | `ChatSession.idOf` + `nextClientId` | `session/ChatSession.java` |
| Danh bạ gửi xuống client | `ChatSession.publishRoster` + `onRosterReceived` | `session/ChatSession.java` |
| Chọn người nhận của một tin | `ChatSession.resolve` | `session/ChatSession.java` |
| Khối địa chỉ trên dây | `Packet.Address` + `ChatScope` | `protocol/Packet.java`, `protocol/ChatScope.java` |
| Định nghĩa unicast/multicast | `ChatScope` + `Packet.chat(scope, targets, text)` | `protocol/ChatScope.java`, `protocol/Packet.java` |
| Relay chat (chỉ server) | `ChatSession.sendRelay` | `session/ChatSession.java` |
| Relay log của server | `ChatSession.relayLine` + `describeClient` | `session/ChatSession.java` |
| Gửi chat | `ChatManager.sendMessage(message, scope, targets)` | `chat/ChatManager.java` |
| Nhận chat / relay | `ChatManager.handle(packet, sender)` | `chat/ChatManager.java` |
| Menu "To:" | `MainController.rebuildRecipientMenu` | `ui/MainController.java` |
| Tên + địa chỉ trên bubble | `ChatBubbleFactory.create` + `ChatItem.sender/audience` | `ui/ChatBubbleFactory.java`, `ui/ChatItem.java` |
| Chuyển sang FX thread | `MainController.runOnFx` | `ui/MainController.java` |

Muốn hiểu sâu hơn nữa: `docs/PROTOCOL.md` mô tả byte-level, `docs/SPECS-UNICAST-MULTICAST.md` mô tả
địa chỉ hoá, `docs/SPECS.md` là đặc tả gốc, và mỗi lớp đều có test tương ứng trong
`src/test/java/org/example/p2pchat/`.
