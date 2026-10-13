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
    auth_check.py characters HOST USER PASSWORD
                                              print the account's character list: "name race
                                              class level" a line

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
        pos = end + 1 + 4 + 3
        if rflags & 0x04:
            pos += 5
        realms.append((rname, address))
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
AUTH_OK, CHAR_CREATE_SUCCESS, CHAR_CREATE_NAME_IN_USE = 0x0C, 0x2F, 0x31
CHAT_MSG_SYSTEM, CHAT_MSG_SAY, LANG_COMMON = 0x00, 0x01, 7

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

    def wait_for(self, wanted, seconds=60, seen=None):
        """Reads packets until one of [wanted] comes, answering the server's time checks."""
        end = time.time() + seconds
        while time.time() < end:
            opcode, body = self.recv()
            if opcode == SMSG_TIME_SYNC_REQ:
                self.send(CMSG_TIME_SYNC_RESP, body[:4] + struct.pack("<I", int(time.monotonic() * 1000) & 0xFFFFFFFF))
            if seen is not None:
                seen.append((opcode, body))
            if opcode in wanted:
                return opcode, body
        raise RuntimeError(f"the world server didn't send {[hex(w) for w in wanted]} in {seconds} s")


def system_messages(packets):
    """The text of the system messages (a GM command's answer) among [packets]."""
    for opcode, body in packets:
        if opcode == SMSG_MESSAGECHAT and body[0] == CHAT_MSG_SYSTEM:
            # type, language, sender, flags, receiver, then the text's length and the text.
            size = struct.unpack_from("<I", body, 25)[0]
            yield body[29:29 + size].rstrip(b"\0").decode(errors="replace")


def world_session(host, user, password, port=8085):
    """A session on the world server, logged in, and a function that lists the characters."""
    user = user.upper()
    _, K = login(host, user, password)
    session = WorldSession(host, port)
    opcode, body = session.recv()
    if opcode != SMSG_AUTH_CHALLENGE:
        raise RuntimeError(f"expected the auth challenge, got {opcode:#x}")
    server_seed = body[4:8]
    client_seed = os.urandom(4)
    digest = sha1(user.encode(), bytes(4), client_seed, server_seed, K)
    addons = zlib.compress(struct.pack("<II", 0, int(time.time())))
    session.send(CMSG_AUTH_SESSION, struct.pack("<II", 12340, 0) + user.encode() + b"\0"
                 + struct.pack("<I", 0) + client_seed + struct.pack("<IIIQ", 0, 0, 1, 0) + digest
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


def world(host, user, password, character, command=None, race=1, cls=1, port=8085):
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
        for rname, address in realms:
            print(f"realm\t{rname}\t{address}")
    elif len(sys.argv) in (6, 7) and sys.argv[1] == "world":
        world(*sys.argv[2:])
    elif len(sys.argv) == 8 and sys.argv[1] == "world-as":
        world(*sys.argv[2:6], race=int(sys.argv[6]), cls=int(sys.argv[7]))
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
