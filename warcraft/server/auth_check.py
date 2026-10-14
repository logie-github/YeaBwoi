#!/usr/bin/env python3
"""Checks the World of Warcraft app's server the way a 3.3.5a (12340) client uses it.

    auth_check.py account-sql USER PASSWORD   print the SQL that creates the account (the same
                                              SRP6 salt and verifier the app writes)
    auth_check.py login HOST USER PASSWORD    log in and print the realm list
    auth_check.py world HOST USER PASSWORD CHARACTER [COMMAND]
                                              log in, then into the world server: create the
                                              character (a human warrior, if the account hasn't
                                              one of that name), enter the world with it, run a
                                              GM command, log out
    auth_check.py world-as HOST USER PASSWORD CHARACTER RACE CLASS
                                              the same with a character of that race and class
    auth_check.py world-note HOST USER PASSWORD CHARACTER FILE
                                              enter the world with the character and send the
                                              app's add-on message (where the spellbook is); the
                                              realm has to write it into FILE
    auth_check.py characters HOST USER PASSWORD
                                              print the account's character list: "name race
                                              class level" a line
    auth_check.py script HOST USER PASSWORD CHARACTER RACE CLASS FILE
                                              enter the world with the character (made if it
                                              isn't there) and play the steps in FILE: GM
                                              commands, teleports, talking to creatures, taking
                                              and turning in quests, gossip choices, and checks
                                              of what the realm answered (see run_script)

WORLD_PORT in the environment names the realm's world server port (8085, ChromieCraft's, if not).

Exits non-zero if anything fails. Used by scripts/test-warcraft-server.sh in CI.
"""
import hashlib
import hmac
import os
import socket
import struct
import sys
import time
import zlib

N = int("894B645E89E1535BBDAD5B8B290650530801B18EBFBF5E8FAB3C82872A3E9BB7", 16)
G = 7


def sha1(*parts):
    h = hashlib.sha1()
    for p in parts:
        h.update(p)
    return h.digest()


def le_int(b):
    return int.from_bytes(b, "little")


def le_bytes(n, size=32):
    return n.to_bytes(size, "little")


def private_x(user, password, salt):
    return le_int(sha1(salt, sha1(f"{user.upper()}:{password.upper()}".encode())))


def verifier(user, password, salt):
    return le_bytes(pow(G, private_x(user, password, salt), N))


def interleave(s):
    p = 0
    while p < len(s) and s[p] == 0:
        p += 1
    if p & 1:
        p += 1
    p //= 2
    even, odd = s[0::2][p:], s[1::2][p:]
    h0, h1 = sha1(even), sha1(odd)
    return bytes(b for pair in zip(h0, h1) for b in pair)


def recv_exact(sock, n):
    data = b""
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            raise RuntimeError("connection closed")
        data += chunk
    return data


def login(host, user, password, port=3724):
    """The realm list, and the session key the world server checks."""
    user = user.upper()
    sock = socket.create_connection((host, port), timeout=10)
    name = user.encode()
    body = (b"WoW\0" + bytes([3, 3, 5]) + struct.pack("<H", 12340) + b"68x\0" + b"niW\0"
            + b"SUne" + struct.pack("<iI", 0, 0x0100007F) + bytes([len(name)]) + name)
    sock.sendall(bytes([0x00, 0x08]) + struct.pack("<H", len(body)) + body)
    cmd, _, error = recv_exact(sock, 3)
    if cmd != 0 or error != 0:
        raise RuntimeError(f"logon challenge refused: error {error}")
    B = recv_exact(sock, 32)
    g_len = recv_exact(sock, 1)[0]
    g = le_int(recv_exact(sock, g_len))
    n_len = recv_exact(sock, 1)[0]
    n = le_int(recv_exact(sock, n_len))
    salt = recv_exact(sock, 32)
    recv_exact(sock, 16)
    flags = recv_exact(sock, 1)[0]
    if flags:
        raise RuntimeError(f"account wants two-factor ({flags})")

    a = le_int(os.urandom(19))
    A = le_bytes(pow(g, a, n))
    u = le_int(sha1(A, B))
    x = private_x(user, password, salt)
    S = le_bytes(pow((le_int(B) - 3 * pow(g, x, n)) % n, a + u * x, n))
    K = interleave(S)
    hn, hg = sha1(le_bytes(n)), sha1(bytes([g]))
    M1 = sha1(bytes(i ^ j for i, j in zip(hn, hg)), sha1(name), salt, A, B, K)
    sock.sendall(bytes([0x01]) + A + M1 + bytes(20) + bytes([0, 0]))
    cmd, error = recv_exact(sock, 2)
    if cmd != 1 or error != 0:
        raise RuntimeError(f"logon proof refused: error {error}")
    M2 = recv_exact(sock, 20)
    recv_exact(sock, 10)
    if M2 != sha1(A, M1, K):
        raise RuntimeError("server proof doesn't match")

    sock.sendall(bytes([0x10]) + bytes(4))
    cmd = recv_exact(sock, 1)[0]
    size = struct.unpack("<H", recv_exact(sock, 2))[0]
    data = recv_exact(sock, size)
    count = struct.unpack_from("<H", data, 4)[0]
    pos, realms = 6, []
    for _ in range(count):
        rtype, locked, rflags = data[pos:pos + 3]
        pos += 3
        end = data.index(b"\0", pos)
        rname = data[pos:end].decode()
        pos = end + 1
        end = data.index(b"\0", pos)
        address = data[pos:end].decode()
        # Population, characters, time zone, then the realm's id.
        realm_id = data[end + 1 + 4 + 2]
        pos = end + 1 + 4 + 3
        if rflags & 0x04:
            pos += 5
        realms.append((rname, address, realm_id))
    sock.close()
    return realms, K


# The world server --------------------------------------------------------------------------------

# Opcodes (AzerothCore's src/server/game/Server/Protocol/Opcodes.h).
CMSG_CHAR_CREATE, CMSG_CHAR_ENUM, CMSG_PLAYER_LOGIN = 0x036, 0x037, 0x03D
SMSG_CHAR_CREATE, SMSG_CHAR_ENUM = 0x03A, 0x03B
CMSG_LOGOUT_REQUEST, SMSG_LOGOUT_RESPONSE, SMSG_LOGOUT_COMPLETE = 0x04B, 0x04C, 0x04D
CMSG_MESSAGECHAT, SMSG_MESSAGECHAT = 0x095, 0x096
SMSG_AUTH_CHALLENGE, CMSG_AUTH_SESSION, SMSG_AUTH_RESPONSE = 0x1EC, 0x1ED, 0x1EE
SMSG_LOGIN_VERIFY_WORLD = 0x236
SMSG_TIME_SYNC_REQ, CMSG_TIME_SYNC_RESP = 0x390, 0x391
SMSG_NEW_WORLD, MSG_MOVE_TELEPORT_ACK, MSG_MOVE_WORLDPORT_ACK = 0x03E, 0x0C7, 0x0DC
CMSG_GOSSIP_HELLO, CMSG_GOSSIP_SELECT_OPTION, SMSG_GOSSIP_MESSAGE = 0x17B, 0x17C, 0x17D
CMSG_SET_SELECTION = 0x13D
SMSG_LIST_INVENTORY, CMSG_BUY_ITEM, SMSG_BUY_ITEM, SMSG_BUY_FAILED = 0x19F, 0x1A2, 0x1A4, 0x1A5

# AUTH_CHECK_TRACE=1: every packet the world server sends, on stderr (for debugging a script).
TRACE = os.environ.get("AUTH_CHECK_TRACE") == "1"
CMSG_QUESTGIVER_ACCEPT_QUEST, CMSG_QUESTGIVER_CHOOSE_REWARD = 0x189, 0x18E
SMSG_QUESTGIVER_QUEST_COMPLETE, SMSG_QUESTUPDATE_COMPLETE, SMSG_QUESTUPDATE_ADD_KILL = 0x191, 0x198, 0x199
SMSG_QUESTGIVER_QUEST_INVALID, SMSG_QUESTGIVER_QUEST_FAILED = 0x18F, 0x192
AUTH_OK, CHAR_CREATE_SUCCESS, CHAR_CREATE_NAME_IN_USE = 0x0C, 0x2F, 0x31
CHAT_MSG_SYSTEM, CHAT_MSG_SAY, CHAT_MSG_WHISPER, LANG_COMMON, LANG_ADDON = 0x00, 0x01, 0x07, 7, 0xFFFFFFFF

# The header keys (AzerothCore's AuthCrypt.cpp): RC4 keyed with HMAC-SHA1(key, session key),
# its first 1024 bytes dropped.
SERVER_ENCRYPTION_KEY = bytes.fromhex("CC98AE04E897EACA12DDC09342915357")
SERVER_DECRYPTION_KEY = bytes.fromhex("C2B3723CC6AED9B5343C53EE2F4367CE")


class RC4:
    def __init__(self, key):
        S, j = list(range(256)), 0
        for i in range(256):
            j = (j + S[i] + key[i % len(key)]) & 0xFF
            S[i], S[j] = S[j], S[i]
        self.S, self.i, self.j = S, 0, 0
        self.process(bytes(1024))

    def process(self, data):
        S, i, j, out = self.S, self.i, self.j, bytearray()
        for b in data:
            i = (i + 1) & 0xFF
            j = (j + S[i]) & 0xFF
            S[i], S[j] = S[j], S[i]
            out.append(b ^ S[(S[i] + S[j]) & 0xFF])
        self.i, self.j = i, j
        return bytes(out)


class WorldSession:
    def __init__(self, host, port):
        self.sock = socket.create_connection((host, port), timeout=60)
        self.send_crypt = self.recv_crypt = None

    def start_crypt(self, K):
        self.send_crypt = RC4(hmac.new(SERVER_DECRYPTION_KEY, K, hashlib.sha1).digest())
        self.recv_crypt = RC4(hmac.new(SERVER_ENCRYPTION_KEY, K, hashlib.sha1).digest())

    def send(self, opcode, body=b""):
        header = struct.pack(">H", len(body) + 4) + struct.pack("<I", opcode)
        if self.send_crypt:
            header = self.send_crypt.process(header)
        self.sock.sendall(header + body)

    def recv(self):
        header = recv_exact(self.sock, 4)
        if self.recv_crypt:
            header = self.recv_crypt.process(header)
        if header[0] & 0x80:  # a big packet: a 3-byte size
            extra = recv_exact(self.sock, 1)
            if self.recv_crypt:
                extra = self.recv_crypt.process(extra)
            size = ((header[0] & 0x7F) << 16) | (header[1] << 8) | header[2]
            opcode = header[3] | (extra[0] << 8)
        else:
            size = (header[0] << 8) | header[1]
            opcode = header[2] | (header[3] << 8)
        return opcode, recv_exact(self.sock, size - 2)

    def answer(self, opcode, body):
        """What a client answers by itself: the time checks, and arriving where it was teleported."""
        if opcode == SMSG_TIME_SYNC_REQ:
            self.send(CMSG_TIME_SYNC_RESP, body[:4] + struct.pack("<I", int(time.monotonic() * 1000) & 0xFFFFFFFF))
        elif opcode == SMSG_NEW_WORLD:
            self.send(MSG_MOVE_WORLDPORT_ACK)
        elif opcode == MSG_MOVE_TELEPORT_ACK:
            # The player's packed guid, then the teleport's counter.
            mask = body[0]
            size = 1 + bin(mask).count("1")
            counter = struct.unpack_from("<I", body, size)[0]
            self.send(MSG_MOVE_TELEPORT_ACK, body[:size] + struct.pack("<II", counter, int(time.monotonic() * 1000) & 0xFFFFFFFF))

    def wait_for(self, wanted, seconds=60, seen=None):
        """Reads packets until one of [wanted] comes, answering the server's time checks."""
        end = time.time() + seconds
        while time.time() < end:
            opcode, body = self.recv()
            self.answer(opcode, body)
            if seen is not None:
                seen.append((opcode, body))
            if opcode in wanted:
                return opcode, body
        raise RuntimeError(f"the world server didn't send {[hex(w) for w in wanted]} in {seconds} s")

    def pump(self, seconds, seen):
        """Reads whatever comes for [seconds], answering as a client does."""
        end = time.time() + seconds
        while True:
            left = end - time.time()
            if left <= 0:
                return
            self.sock.settimeout(left)
            try:
                opcode, body = self.recv()
            except socket.timeout:
                return
            finally:
                self.sock.settimeout(60)
            self.answer(opcode, body)
            seen.append((opcode, body))
            if TRACE:
                print(f"trace: {opcode:#05x} {len(body)} bytes {body[:28].hex()}", file=sys.stderr)


def system_messages(packets):
    """The text of the system messages (a GM command's answer) among [packets]."""
    for opcode, body in packets:
        if opcode == SMSG_MESSAGECHAT and body[0] == CHAT_MSG_SYSTEM:
            # type, language, sender, flags, receiver, then the text's length and the text.
            size = struct.unpack_from("<I", body, 25)[0]
            yield body[29:29 + size].rstrip(b"\0").decode(errors="replace")


def world_session(host, user, password, port=None):
    """A session on the world server, logged in, and a function that lists the characters."""
    user = user.upper()
    realms, K = login(host, user, password)
    port = port or int(os.environ.get("WORLD_PORT", "8085"))
    # The world server checks the session is for its own realm: the one at this port.
    realm_id = next((rid for _, address, rid in realms if address.endswith(f":{port}")), 1)
    session = WorldSession(host, port)
    opcode, body = session.recv()
    if opcode != SMSG_AUTH_CHALLENGE:
        raise RuntimeError(f"expected the auth challenge, got {opcode:#x}")
    server_seed = body[4:8]
    client_seed = os.urandom(4)
    digest = sha1(user.encode(), bytes(4), client_seed, server_seed, K)
    addons = zlib.compress(struct.pack("<II", 0, int(time.time())))
    session.send(CMSG_AUTH_SESSION, struct.pack("<II", 12340, 0) + user.encode() + b"\0"
                 + struct.pack("<I", 0) + client_seed + struct.pack("<IIIQ", 0, 0, realm_id, 0) + digest
                 + struct.pack("<I", 8) + addons)
    session.start_crypt(K)
    _, body = session.wait_for([SMSG_AUTH_RESPONSE])
    if body[0] != AUTH_OK:
        raise RuntimeError(f"the world server refused the session: {body[0]:#x}")
    print("world: session accepted")

    def characters():
        session.send(CMSG_CHAR_ENUM)
        _, body = session.wait_for([SMSG_CHAR_ENUM])
        found, pos = [], 1
        for _ in range(body[0]):
            guid = struct.unpack_from("<Q", body, pos)[0]
            end = body.index(b"\0", pos + 8)
            name = body[pos + 8:end].decode()
            race, cls, _, _, _, _, _, _, level = body[end + 1:end + 10]
            zone, map_id = struct.unpack_from("<II", body, end + 10)
            found.append((guid, name, level, map_id, zone, race, cls))
            # Then position, guild, flags, first login, pet, and 23 equipment slots.
            pos = end + 10 + 8 + 12 + 4 + 4 + 4 + 1 + 12 + 23 * 9
        return found

    return session, characters


def world(host, user, password, character, command=None, race=1, cls=1, port=None, note_file=None, steps=None):
    session, characters = world_session(host, user, password, port)
    chars = characters()
    if not any(c[1] == character for c in chars):
        # Male, default looks.
        session.send(CMSG_CHAR_CREATE, character.encode() + b"\0" + bytes([race, cls, 0, 0, 0, 0, 0, 0, 0]))
        _, body = session.wait_for([SMSG_CHAR_CREATE])
        if body[0] not in (CHAR_CREATE_SUCCESS, CHAR_CREATE_NAME_IN_USE):
            raise RuntimeError(f"creating the character failed: {body[0]:#x}")
        print(f"world: created {character}")
        chars = characters()
    guid, name, level, map_id, zone, race, cls = next(c for c in chars if c[1] == character)
    print(f"world: {name}, race {race}, class {cls}, level {level}, map {map_id}, zone {zone}")

    session.send(CMSG_PLAYER_LOGIN, struct.pack("<Q", guid))
    _, body = session.wait_for([SMSG_LOGIN_VERIFY_WORLD], seconds=120)
    map_id, x, y, z, _ = struct.unpack("<Iffff", body[:20])
    print(f"world: in the world on map {map_id} at {x:.1f} {y:.1f} {z:.1f}")

    # The app's add-on (client/TouchAddon): where the spellbook is, whispered to the player itself,
    # which the realm keeps in a file for the app instead of passing it on (patches/azerothcore/0010).
    if note_file:
        note = "drag 156 2891 3613 8646"
        session.send(CMSG_MESSAGECHAT, struct.pack("<II", CHAT_MSG_WHISPER, LANG_ADDON) + character.encode() + b"\0"
                     + b"WowTouch\t" + note.encode() + b"\0")
        for _ in range(100):
            try:
                with open(note_file) as f:
                    if f.read().strip() == note:
                        break
            except OSError:
                pass
            time.sleep(0.1)
        else:
            raise RuntimeError(f"the realm didn't keep the add-on's note in {note_file}")
        print(f"world: the realm kept the add-on's note: {note}")

    transcript = run_script(session, guid, steps) if steps is not None else None

    # The server answers in order: the command's lines come before the logout. A game master's
    # account logs out at once.
    if command:
        session.send(CMSG_MESSAGECHAT, struct.pack("<II", CHAT_MSG_SAY, LANG_COMMON) + command.encode() + b"\0")
    seen = []
    session.send(CMSG_LOGOUT_REQUEST)
    session.wait_for([SMSG_LOGOUT_COMPLETE], seconds=40, seen=seen)
    for line in system_messages(seen):
        print(f"world: system: {line}")
    print("world: logged out")
    session.sock.close()
    return transcript


def gossip_menu(body):
    """SMSG_GOSSIP_MESSAGE: the menu id, the options (index, text) and the quests (id, title)."""
    menu_id, text_id, count = struct.unpack_from("<III", body, 8)
    pos, options = 20, []
    for _ in range(count):
        index = struct.unpack_from("<I", body, pos)[0]
        pos += 4 + 1 + 1 + 4
        end = body.index(b"\0", pos)
        text = body[pos:end].decode(errors="replace")
        pos = body.index(b"\0", end + 1) + 1
        options.append((index, text))
    quests = []
    for _ in range(struct.unpack_from("<I", body, pos)[0]):
        pos += 4
        quest = struct.unpack_from("<I", body, pos)[0]
        pos += 4 + 4 + 4 + 4 + 1
        end = body.index(b"\0", pos)
        quests.append((quest, body[pos:end].decode(errors="replace")))
        pos = end + 1
    return menu_id, text_id, options, quests


def run_script(session, player_guid, steps):
    """Plays [steps], one a line ("#" starts a comment):

        cmd TEXT                      a GM command (or anything said)
        go MAP X Y Z                  a GM teleport there
        talk ENTRY                    open the gossip of the nearest creature of that entry
                                      (its guid from mod-wrath-remix's `.remix guid ENTRY`)
        kill ENTRY                    select the nearest creature of that entry and `.damage` it
                                      to death (the character's own blow: it gets the kill,
                                      which `.die` doesn't give)
        select ENTRY TEXT             pick the option of its last menu whose text has TEXT
        buy ENTRY ITEM                buy ITEM from the vendor window it last opened (heard:
                                      "ware ITEM cost N" for each ware a window shows, then
                                      "bought ITEM" or "buy failed REASON")
        accept ENTRY QUEST            take a quest from it
        reward ENTRY QUEST            turn a quest in to it
        expect TEXT                   something since the last expect had TEXT in it (system
                                      messages, gossip options and quests, quest updates)
        expect-not TEXT               nothing since the last expect had TEXT
        wait SECONDS                  read what comes

    Returns each step with everything heard during it, in order.
    """
    heard = []
    menus = {}
    windows = {}
    transcript = []

    def creature_guid(entry):
        """The nearest creature of [entry], as the realm names it."""
        session.send(CMSG_MESSAGECHAT, struct.pack("<II", CHAT_MSG_SAY, LANG_COMMON) + f".remix guid {entry}".encode() + b"\0")
        packets = []
        session.pump(1.5, packets)
        note(packets)
        for line in system_messages(packets):
            words = line.split()
            if line.startswith("Wrath Remix guid:") and len(words) > 4 and words[3] == str(entry) and words[4].isdigit():
                return int(words[4])
        raise RuntimeError(f"no creature {entry} near the character")

    def note(packets):
        for opcode, body in packets:
            if opcode == SMSG_MESSAGECHAT and body[0] == CHAT_MSG_SYSTEM:
                for line in system_messages([(opcode, body)]):
                    print(f"script:   system: {line}")
                    heard.append(line)
            elif opcode == SMSG_MESSAGECHAT:
                # An NPC talking (Moratari, Eternus): its words are at the end.
                text = body.rstrip(b"\0").rsplit(b"\0", 1)[-1][-200:].decode(errors="replace")
                print(f"script:   chat type {body[0]}: ...{text[-80:]}")
                heard.append(f"chat: {text}")
            elif opcode == SMSG_GOSSIP_MESSAGE:
                guid = struct.unpack_from("<Q", body, 0)[0]
                menu_id, text_id, options, quests = gossip_menu(body)
                menus[guid] = (menu_id, options)
                print(f"script:   gossip text {text_id}, menu {menu_id}")
                heard.append(f"gossip text {text_id}")
                for index, text in options:
                    print(f"script:   option {index}: {text}")
                    heard.append(f"option: {text}")
                for quest, title in quests:
                    print(f"script:   quest {quest}: {title}")
                    heard.append(f"quest {quest}: {title}")
            elif opcode == SMSG_QUESTUPDATE_ADD_KILL:
                quest, entry, count, needed = struct.unpack_from("<IIII", body, 0)
                print(f"script:   quest {quest}: {entry} {count}/{needed}")
                heard.append(f"credit {quest} {entry} {count}/{needed}")
            elif opcode == SMSG_QUESTUPDATE_COMPLETE:
                quest = struct.unpack_from("<I", body, 0)[0]
                print(f"script:   quest {quest} complete")
                heard.append(f"complete {quest}")
            elif opcode == SMSG_QUESTGIVER_QUEST_COMPLETE:
                quest = struct.unpack_from("<I", body, 0)[0]
                print(f"script:   quest {quest} turned in")
                heard.append(f"rewarded {quest}")
            elif opcode in (SMSG_QUESTGIVER_QUEST_INVALID, SMSG_QUESTGIVER_QUEST_FAILED):
                print(f"script:   quest refused ({opcode:#x}: {body.hex()})")
                heard.append("quest refused")
            elif opcode == SMSG_LIST_INVENTORY:
                guid, count = struct.unpack_from("<QB", body, 0)
                wares = {}
                for n in range(count):
                    slot, item, _, _, price, _, _, cost = struct.unpack_from("<IIIiIIII", body, 9 + 32 * n)
                    wares[item] = slot
                    heard.append(f"ware {item} cost {cost} price {price}")
                windows[guid] = wares
                print(f"script:   vendor window: {count} wares")
                heard.append(f"vendor window {count}")
            elif opcode == SMSG_BUY_ITEM:
                _, slot = struct.unpack_from("<QI", body, 0)
                print(f"script:   bought from slot {slot}")
                heard.append(f"bought slot {slot}")
            elif opcode == SMSG_BUY_FAILED:
                _, item, reason = struct.unpack_from("<QIB", body, 0)
                print(f"script:   buying {item} failed ({reason})")
                heard.append(f"buy failed {reason}")
            elif opcode == SMSG_NEW_WORLD:
                map_id, x, y, z = struct.unpack_from("<Iffff", body, 0)[:4]
                print(f"script:   arrived on map {map_id} at {x:.1f} {y:.1f} {z:.1f}")
                heard.append(f"arrived on map {map_id}")

    for raw in steps:
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        print(f"script: {line}")
        word, _, rest = line.partition(" ")
        packets = []
        if word == "cmd":
            session.send(CMSG_MESSAGECHAT, struct.pack("<II", CHAT_MSG_SAY, LANG_COMMON) + rest.encode() + b"\0")
            session.pump(2.0, packets)
        elif word == "go":
            map_id, x, y, z = rest.split()
            session.send(CMSG_MESSAGECHAT, struct.pack("<II", CHAT_MSG_SAY, LANG_COMMON)
                         + f".go xyz {x} {y} {z} {map_id}".encode() + b"\0")
            session.pump(4.0, packets)
        elif word == "talk":
            session.send(CMSG_GOSSIP_HELLO, struct.pack("<Q", creature_guid(int(rest))))
            session.pump(2.0, packets)
        elif word == "kill":
            session.send(CMSG_SET_SELECTION, struct.pack("<Q", creature_guid(int(rest))))
            session.pump(0.5, packets)
            session.send(CMSG_MESSAGECHAT, struct.pack("<II", CHAT_MSG_SAY, LANG_COMMON) + b".damage 100 pct\0")
            session.pump(3.0, packets)
        elif word == "select":
            entry, text = rest.split(" ", 1)
            guid = creature_guid(int(entry))
            menu_id, options = menus.get(guid, (0, []))
            index = next((i for i, t in options if text.lower() in t.lower()), None)
            if index is None:
                raise RuntimeError(f"no option with \"{text}\" in {options}")
            session.send(CMSG_GOSSIP_SELECT_OPTION, struct.pack("<QII", guid, menu_id, index) + b"\0")
            session.pump(4.0, packets)
        elif word == "buy":
            entry, item = (int(v) for v in rest.split())
            guid = creature_guid(entry)
            slot = windows.get(guid, {}).get(item)
            if slot is None:
                raise RuntimeError(f"{item} isn't in the window {entry} last opened")
            session.send(CMSG_BUY_ITEM, struct.pack("<QIIIB", guid, item, slot, 1, 0))
            session.pump(2.0, packets)
            heard.append(f"bought {item}" if any(o == SMSG_BUY_ITEM for o, _ in packets) else "not bought")
        elif word == "accept":
            entry, quest = (int(v) for v in rest.split())
            session.send(CMSG_QUESTGIVER_ACCEPT_QUEST, struct.pack("<QII", creature_guid(entry), quest, 0))
            session.pump(2.0, packets)
        elif word == "reward":
            entry, quest = (int(v) for v in rest.split())
            session.send(CMSG_QUESTGIVER_CHOOSE_REWARD, struct.pack("<QII", creature_guid(entry), quest, 0))
            session.pump(3.0, packets)
        elif word == "wait":
            session.pump(float(rest), packets)
        elif word in ("expect", "expect-not"):
            found = any(rest.lower() in h.lower() for h in heard)
            if found != (word == "expect"):
                raise RuntimeError(f"{word} \"{rest}\" failed; heard: {heard}")
            heard.clear()
            continue
        else:
            raise RuntimeError(f"unknown step: {line}")
        before = len(heard)
        note(packets)
        transcript.append((line, heard[before:]))
    return transcript


def main():
    if len(sys.argv) == 4 and sys.argv[1] == "account-sql":
        user, password = sys.argv[2].upper(), sys.argv[3]
        salt = os.urandom(32)
        v = verifier(user, password, salt)
        print(f"INSERT INTO account (username, salt, verifier, expansion) VALUES "
              f"('{user}', X'{salt.hex()}', X'{v.hex()}', 2);")
        print(f"INSERT INTO account_access (id, gmlevel, RealmID) SELECT id, 3, -1 FROM account "
              f"WHERE username = '{user}';")
    elif len(sys.argv) == 5 and sys.argv[1] == "login":
        realms, _ = login(sys.argv[2], sys.argv[3], sys.argv[4])
        for rname, address, _ in realms:
            print(f"realm\t{rname}\t{address}")
    elif len(sys.argv) in (6, 7) and sys.argv[1] == "world":
        world(*sys.argv[2:])
    elif len(sys.argv) == 7 and sys.argv[1] == "world-note":
        world(*sys.argv[2:6], note_file=sys.argv[6])
    elif len(sys.argv) == 8 and sys.argv[1] == "world-as":
        world(*sys.argv[2:6], race=int(sys.argv[6]), cls=int(sys.argv[7]))
    elif len(sys.argv) == 9 and sys.argv[1] == "script":
        with open(sys.argv[8]) as f:
            steps = f.read().splitlines()
        world(*sys.argv[2:6], race=int(sys.argv[6]), cls=int(sys.argv[7]), steps=steps)
    elif len(sys.argv) == 5 and sys.argv[1] == "characters":
        session, characters = world_session(*sys.argv[2:5])
        for _, name, level, _, _, race, cls in characters():
            print(f"{name}\t{race}\t{cls}\t{level}")
        session.sock.close()
    else:
        print(__doc__, file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main()
