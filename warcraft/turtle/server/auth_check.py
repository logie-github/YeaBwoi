#!/usr/bin/env python3
"""Checks Turtle WoW's realm (Tortoise-WoW) the way a 1.18.1 (build 7272) client logs in.

    auth_check.py account-sql USER PASSWORD [RANK]  print the SQL that creates the account, as the
                                                   app writes it (TurtleAccountRules.createSql)
    auth_check.py login HOST USER PASSWORD [N]     log in to the login server (SRP6, the 1.12
                                                   protocol) and print the realm list: "name
                                                   address flags" a line; asks for it N times in
                                                   a row (default 1), as the game does
    auth_check.py stand-in-maps FOLDER             header-only map files for maps 0 and 1, which get
                                                   the world server past its check of the start
                                                   areas (no game data in CI; never on a phone)

Exits non-zero if anything fails. Used by scripts/test-turtle-server.sh in CI.
"""
import hashlib
import os
import socket
import struct
import sys

N = int("894B645E89E1535BBDAD5B8B290650530801B18EBFBF5E8FAB3C82872A3E9BB7", 16)
G = 7
BUILD = 7272
VERSION = (1, 18, 1)


def sha1(*parts):
    h = hashlib.sha1()
    for p in parts:
        h.update(p)
    return h.digest()


def le_int(b):
    return int.from_bytes(b, "little")


def le_bytes(n, size=32):
    return n.to_bytes(size, "little")


def sha_pass_hash(user, password):
    return hashlib.sha1(f"{user.upper()}:{password.upper()}".encode()).hexdigest().upper()


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


def account_sql(user, password, rank=4):
    user = user.upper()
    rank = max(0, min(4, int(rank)))
    return (f"INSERT INTO account (username, sha_pass_hash, `rank`, joindate) VALUES "
            f"('{user}', '{sha_pass_hash(user, password)}', {rank}, NOW());\n"
            "REPLACE INTO realmcharacters (realmid, acctid, numchars) SELECT realmlist.id, account.id, 0 "
            "FROM realmlist, account LEFT JOIN realmcharacters ON acctid = account.id WHERE acctid IS NULL;")


def login(host, user, password, port=3724, lists=1):
    """The realm list: (name, address, flags) for each realm. Asked for [lists] times in a row, as
    the game does, each of which must be answered."""
    user = user.upper()
    sock = socket.create_connection((host, port), timeout=10)
    name = user.encode()
    body = (b"WoW\0" + bytes(VERSION) + struct.pack("<H", BUILD) + b"68x\0" + b"niW\0"
            + b"SUne" + struct.pack("<iI", 0, 0x0100007F) + bytes([len(name)]) + name)
    sock.sendall(bytes([0x00, 0x03]) + struct.pack("<H", len(body)) + body)
    cmd, _, error = recv_exact(sock, 3)
    if cmd != 0 or error != 0:
        raise RuntimeError(f"logon challenge refused: error {error}")
    B = recv_exact(sock, 32)
    g = le_int(recv_exact(sock, recv_exact(sock, 1)[0]))
    n = le_int(recv_exact(sock, recv_exact(sock, 1)[0]))
    salt = recv_exact(sock, 32)
    recv_exact(sock, 16)
    if recv_exact(sock, 1)[0]:
        raise RuntimeError("the realm wants a PIN")

    # The login server makes its verifier from sha_pass_hash: x = SHA1(salt, SHA1("USER:PASSWORD")).
    x = le_int(sha1(salt, bytes.fromhex(sha_pass_hash(user, password))))
    a = le_int(os.urandom(19))
    A = le_bytes(pow(g, a, n))
    u = le_int(sha1(A, B))
    S = le_bytes(pow((le_int(B) - 3 * pow(g, x, n)) % n, a + u * x, n))
    K = interleave(S)
    hn, hg = sha1(le_bytes(n)), sha1(bytes([g]))
    M1 = sha1(bytes(i ^ j for i, j in zip(hn, hg)), sha1(name), salt, A, B, K)
    # A, M1, the client's file hash (not checked for build 7272: no hash on the server), no keys,
    # no PIN.
    sock.sendall(bytes([0x01]) + A + M1 + bytes(20) + bytes([0, 0]))
    cmd, error = recv_exact(sock, 2)
    if cmd != 1 or error != 0:
        raise RuntimeError(f"logon proof refused: error {error}")
    M2 = recv_exact(sock, 20)
    recv_exact(sock, 4)  # 1.12's proof answer: M2, then one 32-bit value
    if M2 != sha1(A, M1, K):
        raise RuntimeError("server proof doesn't match")

    for _ in range(lists):
        sock.sendall(bytes([0x10]) + bytes(4))
        recv_exact(sock, 1)
        size = struct.unpack("<H", recv_exact(sock, 2))[0]
        data = recv_exact(sock, size)
    count = data[4]
    pos, realms = 5, []
    for _ in range(count):
        flags = data[pos + 4]
        pos += 5
        end = data.index(b"\0", pos)
        rname = data[pos:end].decode()
        pos = end + 1
        end = data.index(b"\0", pos)
        address = data[pos:end].decode()
        pos = end + 1 + 4 + 3
        realms.append((rname, address, flags))
    sock.close()
    return realms


def stand_in_maps(folder):
    os.makedirs(folder, exist_ok=True)
    header = b"MAPS" + b"z1.4" + bytes(32)
    for mapid in (0, 1):
        for gy in range(64):
            for gx in range(64):
                with open(os.path.join(folder, f"{mapid:03d}{gy:02d}{gx:02d}.map"), "wb") as out:
                    out.write(header)


def main():
    args = sys.argv[1:]
    if args[:1] == ["account-sql"] and len(args) in (3, 4):
        print(account_sql(*args[1:]))
    elif args[:1] == ["login"] and len(args) in (4, 5):
        for realm in login(args[1], args[2], args[3], lists=int(args[4]) if len(args) == 5 else 1):
            print(*realm)
    elif args[:1] == ["stand-in-maps"] and len(args) == 2:
        stand_in_maps(args[1])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
