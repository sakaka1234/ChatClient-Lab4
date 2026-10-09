# Hướng dẫn đọc code: mở Server, kết nối, unicast / multicast / broadcast

File này chỉ để trả lời một câu: **mỗi việc đó nằm ở file nào, và code trông ra sao?**
Giải thích ngắn, không đi vào chi tiết byte. Muốn hiểu sâu thì xem
[`ARCHITECTURE.md`](ARCHITECTURE.md) và [`PROTOCOL.md`](PROTOCOL.md).

> Nhớ một điều trước đây: **Server không chat**. Server chỉ đứng giữa chuyển tin.
> Vì vậy Server không có ô soạn tin, không có menu chọn người nhận, cũng không bao giờ hiện bubble.
> Ai gọi `sendChat(...)` trên Server sẽ bị ném `IllegalStateException`.

Các đoạn code dưới đây là **trích từ chính dự án** (có lược bớt vài dòng không cần thiết).

---

## 1. Mở Server

Người dùng bấm nút **Start Server**, đi theo chuỗi này:

| Bước | File | Chỗ cần xem |
|---|---|---|
| Người dùng bấm nút | `ui/MainController.java` | hàm `onStart()` — nhánh khi mode là `SERVER` |
| Mở cổng và chờ | `session/ChatSession.java` | hàm `startServer(int port)` |
| Việc mở cổng thật sự | `network/Acceptor.java` | hàm `accept(...)` |
| Vòng lặp nhận client | `session/ChatSession.java` | hàm `acceptLoop()` |
| Cho client mới vào danh bạ | `session/ChatSession.java` | hàm `register(...)` |

```java
// ui/MainController.java — onStart()
if (mode == Mode.SERVER) {
    int port = parsePort(listenPortField.getText(), "Local port");
    newSession.startServer(port);
    resetServerPane();
    showServerPane();                       // mở cửa sổ Server, không phải cửa sổ chat
    showConnectionStatus("Listening on port " + port);
}
```

```java
// session/ChatSession.java — startServer(int port)
server = new Acceptor(port);                 // mở cổng ở đây
notifyStatus("Listening on port " + server.port());
publishRoster();

Thread acceptThread = new Thread(this::acceptLoop, "chat-accept");
acceptThread.setDaemon(true);
acceptThread.start();
```

```java
// network/Acceptor.java — accept(Connection.Listener)   ← lời gọi socket thật sự duy nhất
var socket = serverSocket.accept();          // đứng chờ cho tới khi có client gọi vào
socket.setTcpNoDelay(true);
return new Connection(socket, listener);
```

```java
// session/ChatSession.java — acceptLoop()
while (!closed.get()) {
    PacketRouter router = new PacketRouter();
    Connection accepted = current.accept(router);
    router.attach(accepted);
    accepted.start();
    register(accepted);                      // ghi client mới vào sổ
}
```

```java
// session/ChatSession.java — register(Connection)
hub.addIfAbsent(connection, ClientRegistry.DEFAULT_NAME);
idOf(connection);                            // cấp số #1, #2, #3… ngay lập tức
publishRoster();
if (isServer()) {
    return;                                  // Server không có tên để chào, chỉ gửi danh bạ
}
sendHelloQuietly(connection);
```

**Điểm cần nhớ:** cổng chỉ mở **một lần** trong `startServer`. Từ đó về sau có client nào vào thì
`acceptLoop()` nhận, rồi `register()` ghi vào sổ.

Khi Server chạy, cửa sổ Server hiện **danh sách client** và **log sự kiện** — không có khung chat.
Log được đẩy lên UI qua `session/SessionListener.java` → `onServerLog(String)`.
Danh sách client lấy qua `ChatSession.clients()`.

---

## 2. Client kết nối vào Server

Người dùng chọn mode **CLIENT**, gõ IP + cổng của Server, rồi bấm **Connect**:

| Bước | File | Chỗ cần xem |
|---|---|---|
| Người dùng bấm nút | `ui/MainController.java` | hàm `onStart()` — nhánh mode `CLIENT` |
| Chủ động gọi sang Server | `session/ChatSession.java` | hàm `startClient(String host, int port, int timeoutMillis)` |
| Mở socket thật sự | `network/Connector.java` | hàm `connect(...)` |
| Vào danh bạ của Server | `session/ChatSession.java` | hàm `register(...)` (chạy ở phía Server) |
| Server gửi danh bạ về | `session/ChatSession.java` | hàm `publishRoster()` |

```java
// ui/MainController.java — onStart()
String host = requireHost(serverIpField.getText());
int port = parsePort(serverPortField.getText(), "Server port");
newSession.startClient(host, port, ChatSession.CONNECT_TIMEOUT_MILLIS);
showClientPane();                            // client thì mới có cửa sổ chat
```

```java
// session/ChatSession.java — startClient(...)
Connection newConnection = Connector.connect(host, port, timeoutMillis, router);
router.attach(newConnection);
newConnection.start();
register(newConnection);
```

```java
// network/Connector.java — connect(...)   ← client tự mở socket, Server không gọi ngược lại
Socket socket = new Socket();
socket.setTcpNoDelay(true);
socket.connect(new InetSocketAddress(host.trim(), port), timeoutMillis);
return new Connection(socket, listener);
```

Sau khi kết nối, client **nhận được số của mình** (ví dụ `#1`) từ danh bạ mà Server gửi về:

```java
// ui/MainController.java — replaceSession(...)
selfClientId = newSession.selfClientId();    // 0 nghĩa là chưa có số
clientDirectory = newSession.clients();      // danh bạ Server vừa gửi
```

Số này là số thứ tự lúc vào, không cố định — client thoát rồi vào lại sẽ được số mới.

---

## 3. Gửi tin: unicast, multicast, broadcast

Cả ba đều **gửi giống hệt nhau**, chỉ khác **danh sách người nhận**. Không có ba hàm riêng.

### 3.1 Người dùng chọn người nhận ở đâu

| Việc | File | Chỗ cần xem |
|---|---|---|
| Dựng menu chọn người nhận | `ui/MainController.java` | hàm `rebuildRecipientMenu()` |
| Ghi nhớ ai đang được tick | `ui/MainController.java` | biến `selectedTargets` |
| Tick mấy người thì ra loại gì | `ui/MainController.java` | hàm `scopeFor(List<Integer>)` |

Ba loại được định nghĩa ở `protocol/ChatScope.java`:

```java
UNICAST    // đúng 1 người nhận
MULTICAST  // một nhóm, từ 2 người trở lên
BROADCAST  // tất cả mọi người, trừ người gửi
```

```java
// ui/MainController.java — scopeFor(...)
static ChatScope scopeFor(List<Integer> targets) {
    if (targets.isEmpty()) {
        return ChatScope.BROADCAST;                 // không tick ai -> tất cả
    }
    return targets.size() == 1 ? ChatScope.UNICAST  // tick 1 người -> private
                               : ChatScope.MULTICAST; // tick 2+ người -> nhóm
}
```

### 3.2 Bấm Send thì chạy qua đâu

| Bước | File | Chỗ cần xem |
|---|---|---|
| Người dùng bấm Send | `ui/MainController.java` | hàm `onSend()` |
| Đóng gói và gửi đi | `session/ChatSession.java` | hàm `sendChat(String, ChatScope, List<Integer>)` |
| Ghi ra socket | `chat/ChatManager.java` | hàm `sendMessage(...)` |
| Dựng gói tin | `protocol/Packet.java` | hàm `chat(scope, targets, text)` |

```java
// ui/MainController.java — onSend()
List<Integer> targets = List.copyOf(selectedTargets);
ChatScope scope = scopeFor(targets);
session.sendChat(text, scope, targets);
appendChat(ChatItem.outboundText(text, scope, namesOf(clientDirectory, targets),
        System.currentTimeMillis()));            // người gửi tự vẽ bubble của mình
```

```java
// session/ChatSession.java — sendChat(...)
if (isServer()) {
    throw new IllegalStateException("The server relays chat but does not send it");
}
ChatScope resolvedScope = scope == null ? ChatScope.BROADCAST : scope;
List<Integer> resolvedTargets = targets == null ? List.of() : List.copyOf(targets);
if (resolvedTargets.isEmpty()) {
    resolvedScope = ChatScope.BROADCAST;
}
chatManager.sendMessage(text, resolvedScope, resolvedTargets);
```

```java
// chat/ChatManager.java — sendMessage(...)
sink.send(Packet.chat(scope, targets, message.strip()));   // ra socket
```

Từ đây gói tin đi lên Server. **Client không tự gửi cho client khác** — nó chỉ gửi cho Server.

### 3.3 Server nhận rồi copy đi

Đây là chỗ quan trọng nhất của bài:

| Bước | File | Chỗ cần xem |
|---|---|---|
| Server nhận gói tin | `session/ChatSession.java` | hàm `handleIncomingChat(...)` |
| Tính xem gửi cho ai | `session/ChatSession.java` | hàm `resolve(...)` |
| Copy và gửi đi | `session/ChatSession.java` | hàm `sendRelay(...)` |
| Ghi một dòng log | `session/ChatSession.java` | hàm `relayLine(...)` |

```java
// session/ChatSession.java — handleIncomingChat(...)
if (!isServer()) {
    chatManager.handle(packet, hub.nameOf(source));   // client: tin này là cho chính nó
    return;
}
Delivery delivery = resolve(source, scope, targets);  // bảng chuyển tiếp
if (!delivery.destinations().isEmpty()) {
    sendRelay(delivery.destinations(), sender, scope, targets, text);
    relayed.incrementAndGet();
}
notifyServerLog(relayLine(source, scope, targets, delivery, text));
```

```java
// session/ChatSession.java — resolve(...)
for (Connection connection : hub.connections()) {
    int id = idOf(connection);
    if (connection.equals(source)) {
        continue;                        // người gửi không nhận lại tin của chính mình
    }
    if (scope.isBroadcast() || targets.contains(id)) {
        destinations.add(connection);     // BROADCAST: mọi người — còn lại: đúng người được tick
    }
}
```

```java
// session/ChatSession.java — sendRelay(...)
Packet relay = Packet.relay(sender, scope, targets, text);
for (Connection connection : destinations) {
    connection.send(relay);               // copy y nguyên ra từng socket
}
```

Server **không** đọc nội dung tin, **không** tự nhận tin. Log của Server trông như thế này:

```
An #1 @127.0.0.1:54321    UNICAST    -> Binh #2 (1 delivered, 7 B)
An #1 @127.0.0.1:54321    MULTICAST  -> Binh #2, Cuong #3 (2 delivered, 8 B)
An #1 @127.0.0.1:54321    BROADCAST  -> everyone (2 delivered, 10 B)
An #1 @127.0.0.1:54321    UNICAST    -> #7 NOT CONNECTED (0 delivered, 5 B)
+ Binh #2 @127.0.0.1:54402 joined
- Binh #2 @127.0.0.1:54402 left (2 clients remain)
```

Có người gửi, IP, loại tin, người nhận, số socket đã gửi, kích thước — **không có nội dung tin**.

### 3.4 Client nhận tin

| Bước | File | Chỗ cần xem |
|---|---|---|
| Nhận gói tin | `chat/ChatManager.java` | hàm `handle(...)` |
| Báo lên tầng trên | `chat/ChatListener.java` | hàm `onChatMessage(sender, message, scope, targets)` |
| Hiện lên màn hình | `ui/MainController.java` | hàm `onChatMessage(...)` |
| Vẽ bubble | `ui/ChatBubbleFactory.java` | lớp vẽ bubble |

```java
// chat/ChatManager.java — handle(...)
if (packet.type() == MessageType.RELAY) {
    name    = packet.relaySender();       // tên người gửi gốc, do Server ghi vào
    scope   = packet.relayScope();
    targets = packet.relayTargets();
    message = packet.relayText();
} else {
    name    = sender;                     // CHAT thường: người gửi chính là socket này
    scope   = packet.chatScope();
    targets = packet.chatTargets();
    message = packet.chatText();
}
listener.onChatMessage(name, message, scope, targets);
```

```java
// ui/MainController.java — onChatMessage(...)
runOnFx(() -> appendChat(ChatItem.inboundText(sender, message, scope, audience,
        System.currentTimeMillis())));     // rồi ChatBubbleFactory vẽ ra trên luồng UI
```

Bubble của tin gửi đi có ghi rõ nó thuộc loại nào — `private` cho unicast, tên nhóm cho multicast,
và trống cho broadcast. Phần dữ liệu đó nằm trong `ui/ChatItem.java`.

---

## 4. Chạy thử bằng code, không cần bấm UI

Cùng đường đi đó có thể gọi thẳng, không cần JavaFX. Đây là bản rút gọn của
`src/test/java/org/example/p2pchat/session/AddressedChatTest.java` — file đó là bản đầy đủ và
chạy được thật:

```java
// 1. Một listener đơn giản: chỉ in ra màn hình.
SessionListener inRa = new SessionListener() {
    @Override public void onStatusChanged(String status) { System.out.println("[status] " + status); }
    @Override public void onChatMessage(String sender, String message) { System.out.println(sender + ": " + message); }
    @Override public void onServerLog(String line) { System.out.println("[server] " + line); }
    @Override public void onDisconnected(String reason) { System.out.println("[bye] " + reason); }
};

// 2. Mở Server, rồi cho 3 client vào.
ChatSession server = new ChatSession(inRa, "Server");
server.startServer(5000);

ChatSession an = new ChatSession(inRa, "An");
an.startClient("127.0.0.1", 5000, 5000);

ChatSession binh = new ChatSession(inRa, "Binh");
binh.startClient("127.0.0.1", 5000, 5000);

ChatSession cuong = new ChatSession(inRa, "Cuong");
cuong.startClient("127.0.0.1", 5000, 5000);

// 3. Chờ danh bạ về (số của mình + danh sách client) rồi mới gửi.
while (an.selfClientId() == ChatSession.UNASSIGNED_CLIENT_ID) { Thread.sleep(20); }
while (an.clients().size() < 3) { Thread.sleep(20); }

// 4. Ba kiểu gửi — chỉ khác danh sách người nhận.
int binhId  = idCua(an, "Binh");
int cuongId = idCua(an, "Cuong");

an.sendChat("chi rieng Binh", ChatScope.UNICAST,   List.of(binhId));
an.sendChat("hop nhom",       ChatScope.MULTICAST, List.of(binhId, cuongId));
an.sendChat("ca nha oi",      ChatScope.BROADCAST, List.of());

// 5. Server thì không gửi được — dòng này ném IllegalStateException:
// server.sendChat("toi la server", ChatScope.BROADCAST, List.of());

// Tra id của một client theo tên, lấy từ danh bạ.
static int idCua(ChatSession session, String ten) {
    for (ClientInfo client : session.clients()) {
        if (client.name().equals(ten)) {
            return client.id();
        }
    }
    throw new IllegalArgumentException("không có client tên " + ten);
}
```

Điểm cần để ý trong đoạn trên:

- Không có `if (broadcast)` nào ở phía gửi — **cả ba kiểu dùng chung một lời gọi** `sendChat`.
- Client **không** biết đường tới client khác; nó chỉ nói chuyện với Server.
- Server là chỗ duy nhất quyết định tin đi tới socket nào (`resolve`).
- Số `#1`, `#2`, `#3` cấp theo **thứ tự vào**, nên bản test thật cho từng client vào lần lượt và chờ
  danh bạ sau mỗi lần, để số không đổi giữa các lần chạy. Đoạn trên tra id theo **tên** nên không
  cần quan tâm thứ tự.

---

## 5. Bảng tóm tắt nhanh

| Câu hỏi | File |
|---|---|
| Mở Server ở đâu? | `session/ChatSession.java` → `startServer(int)` |
| Mở cổng thật sự ở đâu? | `network/Acceptor.java` |
| Client kết nối ở đâu? | `session/ChatSession.java` → `startClient(...)` |
| Mở socket thật sự ở đâu? | `network/Connector.java` |
| Danh sách client của Server ở đâu? | `session/ClientRegistry.java`, xem qua `ChatSession.clients()` |
| Ba loại unicast/multicast/broadcast định nghĩa ở đâu? | `protocol/ChatScope.java` |
| Chọn người nhận ở đâu? | `ui/MainController.java` → `scopeFor(...)` |
| Gửi tin ở đâu? | `session/ChatSession.java` → `sendChat(...)` |
| Server quyết định gửi cho ai ở đâu? | `session/ChatSession.java` → `resolve(...)` |
| Server copy tin đi ở đâu? | `session/ChatSession.java` → `sendRelay(...)` |
| Log của Server ở đâu? | `session/ChatSession.java` → `relayLine(...)` |
| Nhận tin ở đâu? | `chat/ChatManager.java` → `handle(...)` |
| Test đầy đủ của cả luồng ở đâu? | `src/test/java/org/example/p2pchat/session/AddressedChatTest.java` |

---

## 6. Cách tự chạy thử bằng giao diện

1. Mở một cửa sổ, chọn **SERVER**, bấm **Start Server**.
2. Mở thêm 2–3 cửa sổ, chọn **CLIENT**, gõ IP + cổng của Server, đặt tên rồi bấm **Connect**.
3. Ở một cửa sổ client:
   - không tick ai → gõ tin → **Send** ⇒ **broadcast**;
   - tick 1 người → **Send** ⇒ **unicast**;
   - tick 2 người → **Send** ⇒ **multicast**.
4. Nhìn sang **cửa sổ Server**: mỗi lần gửi sẽ có một dòng log mới, ghi rõ người gửi, loại tin và
   những ai đã nhận. Cửa sổ Server không bao giờ hiện bubble nào.
