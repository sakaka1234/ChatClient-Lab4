# P2P Chat Application Protocol (P2PCP)

Version 2. All integers are **big-endian** (network byte order), matching `java.io.DataOutputStream`.

Version 2 adds the file-transfer handshake (`FILE_ACCEPT` / `FILE_DECLINE`) so the receiver can
accept or refuse a file before any data is sent. Both peers must run the same version.

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
| 1 | `CHAT` | both | UTF-8 message text |
| 2 | `FILE_START` | both | see §3.1 |
| 3 | `FILE_CHUNK` | both | see §3.2 |
| 4 | `FILE_END` | both | see §3.3 |
| 5 | `DISCONNECT` | both | empty |
| 6 | `FILE_ACCEPT` | receiver | file id (2 bytes) |
| 7 | `FILE_DECLINE` | both | file id (2 bytes) |

`PING` / `PONG` are intentionally **not** implemented. Peer liveness is detected through TCP
EOF / `IOException` on the reader thread, which is sufficient for a two-peer LAN/localhost app.

The wire protocol is unchanged by the messenger-style UI: chat text still travels as `CHAT`, and
files still use `FILE_START` / `FILE_CHUNK` / `FILE_END`. The `file id` field is what lets the UI
attach progress and completion to the right bubble when transfers overlap.

## 3. Payload layouts

### 3.1 `FILE_START`

```
+-----------+------------------+-----------------+------------------+
| file id   | file size        | name length     | file name        |
| 2 bytes   | 8 bytes (int64)  | 2 bytes         | name length bytes|
+-----------+------------------+-----------------+------------------+
```

- `file id` — unsigned 16-bit id, `0..65535`. Identifies this transfer.
- `file size` — total file size in bytes, must be `>= 0`.
- `file name` — UTF-8, up to 65535 bytes.
- The receiver validates `name length >= 0` and that the declared payload actually contains that
  many name bytes; a truncated payload fails the transfer.

### 3.2 `FILE_CHUNK`

```
+-----------+------------------+-----------------+
| file id   | chunk index      | chunk data      |
| 2 bytes   | 4 bytes (int32)  | remainder       |
+-----------+------------------+-----------------+
```

- `chunk index` — 0-based, must arrive in exact sequence (`0, 1, 2, ...`).
- `chunk data` — raw bytes, chunk size is `65536` bytes (64 KB) except the final chunk.
- Chunks are streamed: a chunk is read from disk, sent, and released. The whole file is never held
  in memory.

### 3.3 `FILE_END`

```
+-----------+
| file id   |
| 2 bytes   |
+-----------+
```

### 3.4 `FILE_ACCEPT` / `FILE_DECLINE`

```
+-----------+
| file id   |
| 2 bytes   |
+-----------+
```

`FILE_ACCEPT` is sent by the receiver after the transfer is agreed to — either by the user, or
automatically when the receive policy matches (see below). `FILE_DECLINE` means "this file id is
cancelled" and can come from either side:

- receiver → refuses the offer, or aborts an in-progress one
- sender → gives up because no decision arrived within 30 seconds, or the connection dropped

## 4. File transfer sequence

The transfer is **offer-first**: the sender announces the file and waits for a decision before
sending any data, so the receiver chooses where (or whether) to store it.

The shipped client auto-accepts **images** (`png/jpg/jpeg/gif/bmp/webp` up to 8 MB) so a picture
appears in the chat without any click; those go into `received/`. Every other file (and any image
over 8 MB) still shows Accept/Decline and waits for the user, exactly as below. The protocol itself
is unchanged — an auto-accepted image is just a `FILE_ACCEPT` that no human produced.

```
sender                                  receiver
  |  FILE_START {id, size, name}          |
  |-------------------------------------->|  show "Accept / Decline" with name + size
  |  (wait up to 30 s for a decision)     |  nothing written to disk yet
  |                                        |
  |  <-------------- FILE_ACCEPT ----------|  user accepts + picks a folder
  |                                        |  -- or the image policy auto-accepts into received/
  |  FILE_CHUNK {id, index=0, data}       |
  |-------------------------------------->|  write chunk into the chosen folder
  |  ...                                  |
  |  FILE_END {id}                        |
  |-------------------------------------->|  close, verify size, mark done
  |                                        |
  |  <-------------- FILE_DECLINE ---------|  user declines (no data was sent)
  |  or: no answer for 30 s               |
  |-------------- FILE_DECLINE ---------->|  sender gives up, receiver clears the offer
```

Decision outcomes (`TransferDecision`):

| Decision | Trigger | Result |
|---|---|---|
| `ACCEPTED` | receiver sent `FILE_ACCEPT` | chunks start flowing |
| `DECLINED` | receiver sent `FILE_DECLINE` | no chunks sent, sender reports "declined by peer" |
| `TIMED_OUT` | no answer within 30 s | no chunks sent, sender sends `FILE_DECLINE`, reports "no response from peer" |
| `DISCONNECTED` | socket closed while waiting | no chunks sent, sender reports "peer disconnected" |

Receiver validation:

| Check | On failure |
|---|---|
| `FILE_START` while an offer is already pending | new transfer rejected, pending one untouched |
| `FILE_START` while a transfer is active | rejected |
| `FILE_START` with negative size | rejected |
| `FILE_CHUNK` before the offer was accepted | rejected, nothing written |
| `FILE_CHUNK` with no active transfer | failure reported |
| Chunk `file id` does not match the active transfer | transfer aborted, partial file deleted |
| Chunk index out of sequence | transfer aborted, partial file deleted |
| `FILE_END` size mismatch (`received != declared`) | transfer aborted, partial file deleted |

Only **one incoming transfer at a time** is accepted. This keeps the state machine simple; a second
concurrent offer is explicitly rejected rather than interleaved.

The receiver never writes anything to disk before a decision. For a prompted file the destination
folder is supplied at accept time; an auto-accepted image is written into the session's default
receive directory (`received/`) instead.

## 5. Disconnect

Two paths lead to a closed session:

1. **Clean** — one side sends `DISCONNECT` and closes. The receiver logs `Peer sent DISCONNECT` and
   reports `Peer disconnected`.
2. **Abrupt** — the socket is closed without `DISCONNECT`. The reader thread hits `EOFException`
   (or another `IOException`), reports the same `Peer disconnected` status, and releases resources.

Either way the GUI returns to the connection screen and no thread is leaked.

## 6. Example byte dumps

`CHAT("Hi")`:

```
01 00 00 00 02 48 69
^  ^^^^^^^^^^  ^^^^^
|  length = 2  UTF-8 "Hi"
type = CHAT
```

`FILE_START(id=7, name="a.txt", size=5)`:

```
02 00 00 00 0F  00 07  00 00 00 00 00 00 00 05  00 05  61 2E 74 78 74
^  ^^^^^^^^^^^  ^^^^^  ^^^^^^^^^^^^^^^^^^^^^^^  ^^^^^  ^^^^^^^^^^^^^^^
|  length = 15  id=7   size = 5                 len=5  "a.txt"
type = FILE_START
```

`FILE_END(id=7)`:

```
04 00 00 00 02 00 07
```

`FILE_ACCEPT(id=7)`:

```
06 00 00 00 02 00 07
```

`FILE_DECLINE(id=7)`:

```
07 00 00 00 02 00 07
```

`DISCONNECT`:

```
05 00 00 00 00
```

## 7. Limits

| Limit | Value | Source |
|---|---|---|
| Max payload per frame | 16 MiB | `PacketCodec.MAX_PAYLOAD_LENGTH` |
| Chunk size | 64 KiB | `FileSender.DEFAULT_CHUNK_SIZE` |
| Max file id | 65535 | `Packet.MAX_FILE_ID` |
| Send queue capacity | 256 packets | `Connection.OUTGOING_QUEUE_CAPACITY` |
| Max sanitized file name | 255 chars | `FileReceiver.MAX_FILE_NAME_LENGTH` |
| Decision timeout | 30 s | `FileTransferManager.DECISION_TIMEOUT_SECONDS` |
