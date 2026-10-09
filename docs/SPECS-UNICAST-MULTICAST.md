# Spec — Unicast / Multicast / Broadcast chat addressing (protocol v5)

Status: **approved and implemented** (this document is the spec; `docs/PROTOCOL.md` is the byte-level
reference and `docs/ARCHITECTURE.md` explains how the code works).

> **Version note.** This design shipped as protocol v4. The protocol is now v5, which removed file
> transfer and renumbered the message ids compactly; **nothing in this document changed except the
> numbers** — `ROSTER` is now id `5` instead of `10`, and the `FILE_*` types it used to list as
> "unchanged" no longer exist. The rules, the addressing block and the delivery table below are
> exactly as specified.

---

## 1. Why

Today every chat message is a **broadcast**: a client sends `CHAT` to the server, the server
forwards a `RELAY` to *every* other client. There is no way to talk to one specific person, and no
way to talk to a chosen subset. That is the gap this change closes.

The goal is to give the application three explicit **delivery scopes**, which is the classic
Network Programming trio:

| Scope | Meaning here | Recipients |
|---|---|---|
| **Unicast** | one sender → exactly one client | 1 |
| **Multicast** | one sender → a chosen group of clients | 2..N-1 |
| **Broadcast** | one sender → every other client | N-1 |

(`N` is the number of connected clients. The server is never a sender and never a recipient, so it
is not counted in any of these.)

### 1.1 An honest note about layers

TCP has no multicast: a socket is a point-to-point byte stream. This application therefore
implements the three scopes as **application-level delivery scopes** — every chat packet carries an
explicit address, and the server performs *selective forwarding*: it copies the message only to the
connections named in that address.

That is exactly what an IP multicast router does with a group address: it replicates one incoming
datagram onto the subset of links that joined the group. Here the replication point is the server —
which is why the server deliberately does not chat: it is the router, not a participant. The "links"
are the accepted sockets, and the "group" is the recipient id set inside the packet. The mapping is:

| IP concept | This project |
|---|---|
| unicast address | one client id |
| multicast group address | a set of client ids selected in the UI |
| broadcast address | the empty target set + `BROADCAST` scope |
| IGMP join / leave | the roster (`ROSTER`) telling every client who is present |
| multicast router doing replication | `ChatSession.resolve` + `ChatSession.sendRelay` |

The rest of this document uses the IP vocabulary because that is the vocabulary of the course, but
the mechanism described is the application-level one above (it is stated again in `PROTOCOL.md` §4).

---

## 2. Client identity and the roster

Addressing needs addresses. Each **client** gets a **client id**: a 16-bit number, unique inside one
session, assigned by the server in connection order.

- The first client to connect is `#1` (`ChatSession.FIRST_CLIENT_ID`).
- The next ones get `#2`, `#3`, `#4`, ... as they connect.
- Id `0` means "not assigned yet" and must never be used as a target.
- **The server has no id at all** (`ChatSession.UNASSIGNED_CLIENT_ID`): it is not a participant, so no
  address can name it. Adding it to a target list is not an error — it simply reaches nobody.

Because a client only has one socket (to the server), it cannot know who else is connected. The
server therefore maintains the **roster** — the list of `(id, name)` pairs of every connected
client, itself **excluded** — and pushes it to every client with the `ROSTER` packet whenever
membership or a display name changes.

Rules:

1. On `HELLO` from a new connection the server assigns the next free id and re-broadcasts the roster.
2. The roster is authoritative only for the id → name mapping; it is *not* used for routing on the
   client side (clients never route).
3. A client learns its own id from `ROSTER.selfId`; before the first roster arrives it is `0`.
4. Ids are per-*connection*, not per-person: a client that reconnects gets the next id. Restarting the
   server renumbers from `#1`. Nothing persists.

---

## 3. Wire protocol changes (v3 → v4, kept in v5)

Every instance must run the same build — this was not backward compatible with v3, by design: `CHAT`
and `RELAY` changed shape.

### 3.1 New message type

| Id | Name | Direction | Purpose |
|---|---|---|---|
| 5 | `ROSTER` | server → every client | full client directory `(id, name)` + the receiver's own id |

(The id was `10` when this shipped; v5 renumbered the whole enum after file transfer was removed —
see the version note at the top.)

### 3.2 `CHAT` (client → server) — now addressed

```
+--------+--------------+---------------------------+-------------------+
| scope  | target count | target ids                | text              |
| 1 byte | 2 bytes (u16)| count × 2 bytes (u16)     | remainder, UTF-8  |
+--------+--------------+---------------------------+-------------------+
```

- `scope` — `0 = UNICAST`, `1 = MULTICAST`, `2 = BROADCAST`.
- `target count` — number of ids that follow. `BROADCAST` requires `0`; `UNICAST` requires exactly
  `1`; `MULTICAST` requires `2..64`.
- The **sender's own id must not appear in the target list**; the server drops it if it does. The
  server's own "id" cannot appear at all, because it has none.
- The sender's display name is *not* in this packet: the server already knows which connection it
  came from.

### 3.3 `RELAY` (server → recipient) — now carries the address too

```
+-------------+----------------+--------+--------------+-----------+----------------+
| sender len  | sender         | scope  | target count | target ids| text           |
| 2 bytes u16 | UTF-8          | 1 byte | 2 bytes u16  | count×u16 | remainder, UTF-8|
+-------------+----------------+--------+--------------+-----------+----------------+
```

Everything after `sender` is the original `CHAT` addressing block, forwarded unchanged, so a
receiver can render "sent to you only" or "sent to An, Binh" — the same wording the sender saw.

### 3.4 `ROSTER` payload

```
+---------+-------+-----------------------------------------------------+
| self id | count | count × { id u16 | name length u16 | name UTF-8 }   |
| 2 bytes | 2 by. | variable                                            |
+---------+-------+-----------------------------------------------------+
```

- `self id` — the receiver's own client id.
- `count` — number of entries, `1..64`. The server is not in the list, but the receiver always is, so
  a count of `0` is invalid.

### 3.5 Unchanged packets

`HELLO` and `DISCONNECT` keep their v3 layouts; only the `CHAT` / `RELAY` / `ROSTER` payloads above
are new. That is the whole protocol: five types, no more (`docs/PROTOCOL.md` §2).

---

## 4. Delivery rules (the server's forwarding table)

Given a message from source `S` (always a client — the server never originates chat) with
`(scope, targets)`:

```
recipients(scope, targets) =
    BROADCAST  -> every connected client except S
    UNICAST    -> the client whose id is in targets, except S
    MULTICAST  -> every connected client whose id is in targets, except S
```

1. Each recipient receives exactly **one** `RELAY` packet carrying the original `(scope, targets)`.
2. **The server itself never receives the message.** It has no UI to draw it in and no id to be named
   by; it only writes one line to its relay log per relayed message.
3. The **sender never receives an echo** of its own message — its UI draws the outbound bubble
   itself. This already existed for broadcast and is preserved.
4. Targets that are not connected are written into the relay log (`#7 NOT CONNECTED`) and silently
   dropped; no negative acknowledgement is sent back. Documented in §6.
5. Clients never forward. Only the server replicates, which is what keeps the topology loop-free.

Because the server checks each recipient against the target set, a unicast message physically leaves
the server on one socket only, and a multicast message on exactly the selected sockets.

---

## 5. API and UI surface

### 5.1 `ChatSession`

| Member | Purpose |
|---|---|
| `sendChat(String)` | broadcast (unchanged signature, kept for callers/tests) |
| `sendChat(String, ChatScope, List<Integer>)` | addressed send; **throws on a server**, which relays but never originates chat |
| `clients()` | `List<ClientInfo>` snapshot of `(id, name)` for the address picker |
| `selfClientId()` | this instance's id (`0` until the first roster on a client, and always `0` on a server) |
| `relayedCount()` | how many messages this server has copied onto at least one socket |

### 5.2 `SessionListener`

`onRosterChanged(int selfId, List<ClientInfo> clients)` is **added as a default (no-op) method**, so
every existing listener and test keeps compiling. `onChatMessage` gains a default overload
`(sender, text, ChatScope scope, List<String> audience)`; the old two-argument method is still called
through the default, so old listeners see broadcast-shaped behaviour. `onServerLog(String)` is a
third default (no-op): the server view fills its relay log from it, and clients never call it.

`audience` is the list of **display names of the clients the sender addressed** — the target set,
not "who actually received it". It therefore includes the client reading it (a multicast to `[An,
Binh]` reads `group · An, Binh` on Binh's own screen) and it may name a client that has since left.
It is empty for a broadcast. A target id that is no longer in the roster is rendered as `#id` rather
than being dropped, so a message still renders correctly if the roster moved on.

### 5.3 UI

- A **"To:" menu** next to the message box lists every other client with a checkbox. The server
  never appears in it — it is not a client and has no id — and the client's own id is never offered
  either.
- Nothing checked → `BROADCAST` (button reads `Everyone`).
- One checked → `UNICAST`. Two or more → `MULTICAST`. The button shows the current selection, e.g.
  `An, Binh`.
- Outbound bubbles show `→ An` / `→ An, Binh` when the message was directed; inbound bubbles show
  `private` (unicast) or `group · An, Binh` (multicast). Broadcast bubbles look exactly as before.
- The picker is rebuilt from every roster update; selected ids that leave the session are dropped.
- Sending with an empty message box is ignored, as before.

---

## 6. Validation, errors and limits

| Rule | Where enforced | On violation |
|---|---|---|
| `scope` byte ∈ {0,1,2} | `ChatScope.fromId` | `IllegalStateException("Unknown chat scope: N")` at decode (`Packet` wraps the `IOException`) |
| `target count ≤ 64` | `Packet` factories + parsers | `IllegalArgumentException` / `IllegalStateException` |
| `BROADCAST` ⇒ count `0` | factories + parsers | reject |
| `UNICAST` ⇒ count `1`; `MULTICAST` ⇒ count ≥ `2` | factories + parsers | reject |
| target id `1..65535` | factories | `IllegalArgumentException` |
| truncated payload | parsers | `IllegalStateException` |
| any malformed `CHAT`/`RELAY` | `PacketRouter.route` | logged and the packet is dropped; the reader thread survives |
| target not connected | server routing | `#N NOT CONNECTED` in the relay log, message still delivered to the rest |
| sender listed as its own target | server routing | dropped from the set |
| directed send with no targets | `ChatSession.sendChat` | normalised to `BROADCAST` |
| any send on a server | `ChatSession.sendChat` | `IllegalStateException` — a server has no chat of its own |
| `ROSTER` count `0`, or `> 64` | parser | `IllegalStateException` |

Limits: `MAX_CHAT_TARGETS = 64`, `MAX_ROSTER_ENTRIES = 64`. A payload over 16 MiB is still rejected
by the frame codec.

---

## 7. Explicitly out of scope

- **File transfer.** It existed in an earlier revision and has since been removed from the project
  entirely (`docs/SPECS.md` §12). If it were ever brought back, addressing it the same way would need
  a server relay table and per-hop id translation, because two senders may both number their first
  transfer `1` — the addressing model here covers chat only.
- Offline messages, delivery receipts, retries, persistence, per-client history.
- Named, server-managed groups: multicast here is an ad-hoc recipient set chosen at send time.
- Real `MulticastSocket`/IGMP traffic (see §1.1).

---

## 8. Test plan

Protocol:

- `CHAT` and `RELAY` round-trip with each scope, incl. unicode text and multi-target lists.
- Byte-level dump of a unicast `CHAT` and of a `ROSTER`, so the layout cannot drift silently.
- Rejections: unknown scope, count over the limit, truncation, `BROADCAST` with targets,
  `UNICAST` with two targets, `MULTICAST` with one target.
- `MessageType` ids stay stable (`ROSTER == 5`, and the enum is exactly `CHAT`, `DISCONNECT`,
  `HELLO`, `RELAY`, `ROSTER`).

Session, real sockets, one server plus **three** clients so "exactly this group" is falsifiable
(`AddressedChatTest`; the two-client cases stay in `MultiClientChatTest`):

- unicast from a client reaches only the named client — the other two clients stay silent, and so
  does the sender;
- multicast reaches exactly the named clients and nobody else;
- unicast to an id nobody holds delivers to nobody and does not crash;
- a unicast that names its own sender delivers to nobody;
- broadcast still reaches everyone but the sender, so the existing tests keep passing unchanged;
- an empty target list is treated as a broadcast (that is what an emptied UI picker sends);
- **the server never receives anything**: not a broadcast, not a multicast, not a unicast, and no
  client can address it, because it has no id;
- the receiver sees the scope and the resolved audience names, for unicast, multicast and broadcast;
- every client learns its own id from the roster, all rosters agree, and **the server is in none of
  them**;
- sending on a server throws `IllegalStateException`;
- the roster shrinks after a client leaves, and the server's relay log records the join, the relay
  and the leave — including the sender's `@ip:port` and the scope in capitals;
- the last client leaving does not end the server's session; it keeps listening.

Negative assertions poll for a short window (400 ms) rather than checking an empty queue immediately,
so "did not arrive" is actually tested.

UI model:

- `ChatItem` keeps scope + audience and exposes them; the broadcast factories behave as before.
- the selection rules are pure functions so they can be tested without standing up a JavaFX toolkit
  (`RecipientSelectionTest`): nothing selected → `BROADCAST`, one → `UNICAST`, several → `MULTICAST`;
  ids resolve to names and fall back to `#id` once the roster has moved on; the picker never offers
  your own id; a client that left is dropped from the selection, which is what stops the next send
  from addressing a ghost.

Real controls, driven the way the application drives them (`MainControllerPickerTest`,
`MainControllerSendTest`) — the JavaFX toolkit is started by the tests themselves, so these are
genuine `CheckBox`es and `MenuItem`s, with no TestFX and no extra dependency, and no window shown:

- a roster arriving fills the picker with every other client and never with yourself, and the
  placeholder is disabled when there is nobody to address;
- ticking a client renames the button, ticking several lists them in the order they were picked, and
  unticking them all goes back to `Everyone`;
- a client that leaves disappears from the picker *and* from the selection;
- a disconnect empties the picker;
- inbound messages render as bubbles carrying their scope and audience, in arrival order, without
  disturbing what is addressed next;
- **the server window** (`MainControllerServerViewTest`), driven the same way: *Start Server* is
  pressed, and the window lists its clients by number and name, repaints the list when one leaves,
  counts them in its header, shows the relay log it is handed, and **never grows a message box** — a
  message arriving cannot turn the server window into a client one;
- **end to end through the widgets**: the connection form is filled in, *Start Server* is pressed, a
  second form connects as a client, real clients connect, a client is ticked in the picker, the
  message box is typed into and *Send* is pressed — then what arrived on the other sockets is
  checked. One client ticked reaches only that client, two of three reach exactly those two, tick
  everyone is still an explicit `MULTICAST`, and nothing ticked broadcasts. A blank message box
  sends nothing at all.

That end-to-end test exists because the first version of it was too weak to be trusted: with only two
clients, "a group of two" and "everyone" produce identical traffic, so replacing the address with a
broadcast still passed. A third client makes the two distinguishable, and the deliberate break then
fails the group test as it should.

Robustness (`MalformedChatPacketTest`, writing bad bytes straight onto a socket because no client
would ever produce them):

- a well-framed packet with a broken addressing block — unknown scope, a target count that runs past
  the payload, a count over `MAX_CHAT_TARGETS`, a truncated header, a malformed `RELAY` — is dropped
  by the router, and the clients on that connection keep working afterwards;
- several in a row do not accumulate into a failure;
- a badly framed packet (a length the codec rejects) is the honest exception: a length-framed stream
  cannot be resynchronised, so that connection ends — and only that one, which the test pins down by
  keeping a second, well-behaved client chatting across it.

The malformed-packet tests were checked against a deliberately broken build: removing the router's
`catch (RuntimeException)` makes five of the seven fail, so they test the guard rather than the
happy path. The same check was made on the UI tests — dropping the `rebuildRecipientMenu()` call from
`onRosterChanged` fails nine of the fourteen, and replacing the addressed send with a broadcast fails
the unicast and group tests — so they are testing the wiring and not just passing by themselves.

---

## 9. Acceptance

The change is done when `.\mvnw.cmd test` is green, the instances from `run-chat.cmd` — one server,
three clients — can hold a private 1-1 conversation while a separate multicast conversation is in
flight between the other two, the server's window shows a relay log line for each of them and no
bubble at all, and every bubble is labelled with the scope it was sent under — with `docs/PROTOCOL.md`,
`docs/ARCHITECTURE.md` and `README.md` describing the shipped behaviour.
