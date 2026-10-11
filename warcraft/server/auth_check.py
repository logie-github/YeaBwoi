#!/usr/bin/env python3
"""Checks the World of Warcraft app's auth server the way a 3.3.5a (12340) client uses it.

    auth_check.py account-sql USER PASSWORD   print the SQL that creates the account (the same
                                              SRP6 salt and verifier the app writes)
    auth_check.py login HOST USER PASSWORD    log in and print the realm list

Exits non-zero if the login fails. Used by scripts/test-warcraft-server.sh in CI.
"""
import hashlib
import os
import socket
import struct
import sys

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
    return realms


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
        for rname, address in login(sys.argv[2], sys.argv[3], sys.argv[4]):
            print(f"realm\t{rname}\t{address}")
    else:
        print(__doc__, file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main()
