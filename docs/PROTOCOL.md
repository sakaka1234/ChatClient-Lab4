# Chat Application Protocol

Version 5. All integers are **big-endian** (network byte order), matching `java.io.DataOutputStream`.

Version history:

| Version | Adds |
|---|---|
| 2 | file-transfer handshake (`FILE_ACCEPT` / `FILE_DECLINE`) so the receiver decided before any data was sent |
| 3 | `HELLO` (display name exchange) and `RELAY` (the server forwarding a chat on behalf of a client) |
| 4 | the **addressing block** on `CHAT` / `RELAY` (unicast / multicast / broadcast) and `ROSTER` (client ids) |
| 5 | file transfer removed: the `FILE_*` types (ids 2, 3, 4, 6, 7) are retired and the remaining types are renumbered compactly |

Every instance must run the same version. The addressing design is specified in
`docs/SPECS-UNICAST-MULTICAST.md`.

## 1. Frame format

Every message is one frame:

```
+--------+------------------+-------------------+
| type   | payload length   | payload           |
| 1 byte | 4 bytes (int32)  | N bytes           |
+--------+------------------+-------------------+
```

- `type` — message type id (see §2).
- `payload length` — number of payload bytes, `0 .. 16 MiB` (`PacketCodec.MAX_PAYLOAD_LENGTH`).
- The receiver reads the type byte, then the length, then exactly `length` bytes.

Length framing is what lets the receiver know where one message ends and the next begins on a
single TCP stream. Reading is done with `DataInputStream.readFully` so partial reads are handled.

Rejected at read time:

| Condition | Result |
|---|---|
| EOF before the type byte | `EOFException` (clean end of stream) |
| EOF inside header or payload | `EOFException` |
| Unknown type id | `IOException("Unknown message type: N")` |
| Length `< 0` or `> 16 MiB` | `IOException("Invalid payload length: N")` |

## 2. Message types

| Id | Name | Direction | Payload |
|---|---|---|---|
| 1 | `CHAT` | client → server | addressing block + UTF-8 text (§3.1) |
| 2 | `DISCONNECT` | both | empty |
| 3 | `HELLO` | client → server | UTF-8 display name |
| 4 | `RELAY` | server → client | sender name + addressing block + UTF-8 text (§3.2) |
| 5 | `ROSTER` | server → client | own id + directory (§3.3) |

Ids are stable: a new message kind takes the next free id, and an id is never reused for a different
kind. (Version 5 compressed the numbering after the `FILE_*` types were removed, which is why
`DISCONNECT` is now `2` rather than `5` — see the version history.)

`PING` / `PONG` are intentionally **not** implemented. Client liveness is detected through TCP
EOF / `IOException` on the reader thread, which is sufficient for a LAN/localhost app.

There is exactly one way a chat reaches a listener. A **client** sends `CHAT`. It has one socket (to
the server), so the server — not the client — decides which sockets the message continues on, and
converts each `CHAT` it receives into `RELAY` for the clients that should get it. The **server never
sends `CHAT` and never keeps one**: it relays and does not chat, so it has no message of its own and
is never a recipient. A `RELAY` therefore carries the original sender's name, so a receiving client
attributes the message to the client that typed it rather than to the server.

## 3. Payload layouts

### 3.1 `CHAT`

```
+---------------+------------------+--------------------+----------------+
| scope         | target count     | target ids         | text           |
| 1 byte        | 2 bytes          | 2 bytes each       | remainder      |
+---------------+------------------+--------------------+----------------+
```

The first three fields are the **addressing block** — the same block appears inside `RELAY`, so a
forwarded message keeps the address it was sent with.

- `scope` — `0` = `UNICAST`, `1` = `MULTICAST`, `2` = `BROADCAST` (`ChatScope`). Any other value is
  rejected.
- `target count` — unsigned 16-bit, `0 .. 64` (`Packet.MAX_CHAT_TARGETS`).
- `target ids` — unsigned 16-bit client ids; `0` is never a valid target, and the server has no id
  at all, so no target can ever name it.
- `text` — UTF-8, the rest of the payload; may be empty.

The scope and the target count must agree:

| Scope | Requires | Meaning |
|---|---|---|
| `BROADCAST` | count `= 0` | every other client |
| `UNICAST` | count `= 1` | exactly that client |
| `MULTICAST` | count `>= 2` | exactly those clients |

A payload that breaks the rule (e.g. `UNICAST` with two ids, or a count that runs past the end of the
payload) is rejected on read with `IllegalStateException`, and the router logs it and drops the
packet instead of killing the reader thread.

### 3.2 `RELAY`

```
+------------------+----------------+------------------+--------------------+----------+
| sender length    | sender         | scope            | target count       | ids      | text
| 2 bytes          | UTF-8, N bytes | 1 byte           | 2 bytes            | 2 each   | remainder
+------------------+----------------+------------------+--------------------+----------+
```

- `sender length` — byte length of the name that follows, not the character count. A name with
  two-byte UTF-8 characters is therefore longer than it looks (`"Cường"` is 6 bytes).
- `sender` — the display name of the client that originally typed the message.
- the addressing block and text are exactly as in §3.1, forwarded unchanged.

The sender field is what fixes attribution: the client's listener reports `Bình: tin nhắn` even
though the bytes arrived on the server's socket.

### 3.3 `ROSTER`

```
+----------+--------------+-----------+----------+-----------+--------+-----+
| self id  | count        | id 1      | name len | name 1    |  ...   | ...
| 2 bytes  | 2 bytes      | 2 bytes   | 2 bytes  | N bytes   |        |
+----------+--------------+-----------+----------+-----------+--------+-----+
```

- `self id` — the id of the client **receiving** this packet. Each client gets its own copy, so the
  server builds the same directory N times with a different `self id` in front. That is how a client
  learns which entry is itself.
- `count` — `1 .. 64` (`Packet.MAX_ROSTER_ENTRIES`). The list holds clients only: the server is never
  one of them, so it is never counted.
- `name len` — byte length of the UTF-8 name, as in §3.2.

The server sends a fresh `ROSTER` whenever the directory changes: a client connects, a client
leaves, or a client announces its name. It is sent to every connected client.

A server that receives a `ROSTER` ignores it (only the server may define the directory).

## 4. Addressing and delivery

This is the application-level analogue of unicast / multicast / broadcast. TCP has no multicast, so
the server — the only instance with a socket to every client — does the replication, exactly as a
multicast router replicates one datagram onto the links that joined the group.

Delivery rules, all of them enforced by the server:

| Sender | Scope | Recipients |
|---|---|---|
| client | `BROADCAST` | every client except the sender |
| client | `UNICAST` / `MULTICAST` | the named clients, except the sender |

The server is never a row in this table. It originates no chat, so it is never a sender, and it has no
id, so it is never a recipient.

Consequences worth stating explicitly:

- **The sender never receives its own message back.** The UI already drew an outbound bubble, so an
  echo would duplicate it. The server, which does the copying, is not a participant either, so it is
  not in the recipient set.
- **An unknown target is dropped, not an error.** If a client left between the picker being drawn and
  the message being sent, the server writes `#N NOT CONNECTED` in its relay log and delivers to the
  rest. The message is never re-routed to everyone.
- **Addressing yourself reaches nobody.** The server excludes the source from every destination list.
- **An empty target set means `BROADCAST`.** A UI whose selection was emptied sends `BROADCAST`, not
  a malformed `UNICAST` with no ids.
- **A malformed addressing block never kills a reader thread.** `PacketRouter` catches
  `RuntimeException` from a handler, logs it, and keeps reading.

The audience a receiver sees is resolved from its directory: a client is told the client ids, and
turns them into names locally. An id that is no longer in the directory is displayed as `#id`, so a
message still renders if the directory moved on.

## 5. Disconnect

Two paths lead to a closed session:

1. **Clean** — one side sends `DISCONNECT` and closes. The receiver logs `Client sent DISCONNECT`.
2. **Abrupt** — the socket is closed without `DISCONNECT`. The reader thread hits `EOFException`
   (or another `IOException`) and releases resources.

What happens next depends on which side lost the socket:

- **A client lost its server** (its only socket): the session is over. It reports
  `Server disconnected`, its picker is emptied and the GUI returns to the connection screen.
- **A server lost a client**: the server removes it from the registry and publishes a fresh
  `ROSTER`, the remaining clients drop its id from their pickers, and the server writes a leave line
  in its relay log. The server itself keeps listening — an empty client list is not the end of its
  session, so only **Stop Server** ends it.

Either way no thread is leaked.

## 6. Example byte dumps

`CHAT("Hi")` — a broadcast:

```
01  00 00 00 05  02 00 00  48 69
^   ^^^^^^^^^^^  ^^ ^^^^^  ^^^^^
|   length = 5   |  count=0  UTF-8 "Hi"
type = CHAT      scope = BROADCAST
```

`CHAT(UNICAST, [3], "Hi")` — private to client `#3`:

```
01  00 00 00 07  00 00 01  00 03  48 69
^   ^^^^^^^^^^^  ^  ^^^^^  ^^^^^  ^^^^^
|   length = 7   |  count=1  id=3   "Hi"
type = CHAT     scope = UNICAST
```

`CHAT(MULTICAST, [1,3], "Hi")`:

```
01  00 00 00 09  01 00 02  00 01  00 03  48 69
^   ^^^^^^^^^^^  ^  ^^^^^  ^^^^^  ^^^^^  ^^^^^
|   length = 9   |  count=2  id=1   id=3   "Hi"
type = CHAT     scope = MULTICAST
```

`RELAY("An", UNICAST, [2], "hi")`:

```
04  00 00 00 0B  00 02  41 6E  00 00 01  00 02  68 69
^   ^^^^^^^^^^^  ^^^^^  ^^^^^  ^  ^^^^^  ^^^^^  ^^^^^
|   length = 11  len=2  "An"   |   count=1  id=2   "hi"
type = RELAY                  scope = UNICAST
```

`ROSTER(self=2, [(1,"Host"),(2,"An")])` — payload `2 + 2 + (2+2+4) + (2+2+2) = 18`:

(The names in the samples are arbitrary strings — the encoder does not care what they say, and this
particular dump is pinned by `PacketTest.rosterPayloadMatchesTheDocumentedDump`. A real directory
looks the same shape but never contains the server, so no entry here stands for one.)

```
05  00 00 00 12  00 02  00 02  00 01  00 04  48 6F 73 74  00 02  00 02  41 6E
^   ^^^^^^^^^^^  ^^^^^  ^^^^^  ^^^^^  ^^^^^  ^^^^^^^^^^^  ^^^^^  ^^^^^  ^^^^^
|   length = 18  self=2 count=2  id=1  len=4  "Host"      id=2  len=2  "An"
type = ROSTER
```

`HELLO("An")`:

```
03  00 00 00 02  41 6E
^   ^^^^^^^^^^^  ^^^^^
|   length = 2   UTF-8 "An"
type = HELLO
```

`DISCONNECT`:

```
02 00 00 00 00
```

## 7. Limits

| Limit | Value | Source |
|---|---|---|
| Max payload per frame | 16 MiB | `PacketCodec.MAX_PAYLOAD_LENGTH` |
| Max client id | 65535 | `Packet.MAX_CLIENT_ID` |
| Max chat targets per message | 64 | `Packet.MAX_CHAT_TARGETS` |
| Max clients in a roster | 64 | `Packet.MAX_ROSTER_ENTRIES` |
| Max display name | 65535 bytes | `Packet` (private `MAX_NAME_BYTES`) |
| Addressing block size | 3 bytes + 2 per target | `Packet` (private) |
| Send queue capacity | 256 packets | `Connection.OUTGOING_QUEUE_CAPACITY` |
