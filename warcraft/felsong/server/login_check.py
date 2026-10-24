#!/usr/bin/env python3
"""Checks the Legion realm (LegionCore 7.3.5) the way a 7.3.5 (build 26972) client logs in.

    login_check.py account-sql EMAIL PASSWORD [LEVEL]  print the SQL that creates the account, as the
                                                     app writes it (FelsongAccountRules.createSql)
    login_check.py form HOST [PORT]                   GET the Battle.net server's login form
                                                     (https://HOST:8081/bnetserver/login/, TLS with
                                                     its own certificate) and print its input ids
    login_check.py login HOST EMAIL PASSWORD [PORT]   POST the login form as the client does and
                                                     print the login ticket; exits 1 without one
    login_check.py stand-in-data FOLDER               an empty dbc/enUS and maps folder, so the
                                                     world server gets past its DataDir check to
                                                     the DB2 load, where it stops without a client
                                                     (CI only; never on a phone)

Exits non-zero if anything fails. Used by scripts/test-felsong-server.sh.
"""
import hashlib
import json
import os
import ssl
import sys
import urllib.request


def sha_pass_hash(email, password):
    # LegionCore's AccountMgr::CalculateShaPassHash: SHA-256 of HEX(SHA-256(email)) ":" password,
    # both upper-cased, the digest's bytes reversed, in capital hex.
    email_hash = hashlib.sha256(email.upper().encode()).hexdigest().upper()
    digest = hashlib.sha256((email_hash + ":" + password.upper()).encode()).digest()
    return digest[::-1].hex().upper()


def account_sql(email, password, level=3):
    user = email.upper()
    level = max(0, min(3, int(level)))
    sql = (f"INSERT INTO account (username, sha_pass_hash, email, joindate) VALUES "
           f"('{user}', '{sha_pass_hash(user, password)}', '{user}', NOW());\n")
    if level > 0:
        sql += f"INSERT INTO account_access (id, gmlevel, RealmID) SELECT id, {level}, -1 FROM account WHERE username = '{user}';\n"
    sql += ("REPLACE INTO realmcharacters (realmid, acctid, numchars) SELECT realmlist.id, account.id, 0 "
            "FROM realmlist, account LEFT JOIN realmcharacters ON acctid = account.id WHERE acctid IS NULL;")
    return sql


def context():
    # The realm's certificate is its own (bnetserver.cert.pem): the patched client trusts it, this check doesn't look.
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx


def form(host, port=8081):
    with urllib.request.urlopen(f"https://{host}:{port}/bnetserver/login/", context=context(), timeout=10) as r:
        data = json.loads(r.read())
    ids = [i["input_id"] for i in data.get("inputs", [])]
    if "account_name" not in ids or "password" not in ids:
        raise SystemExit(f"the login form has no account_name and password inputs: {data}")
    return ids


def login(host, email, password, port=8081):
    body = json.dumps({
        "platform_id": "Wn64",
        "program_id": "WoW",
        "version": "7.3.5.26972",
        "inputs": [
            {"input_id": "account_name", "value": email},
            {"input_id": "password", "value": password},
        ],
    }).encode()
    request = urllib.request.Request(
        f"https://{host}:{port}/bnetserver/login/", data=body, method="POST",
        headers={"Content-Type": "application/json;charset=utf-8"},
    )
    with urllib.request.urlopen(request, context=context(), timeout=20) as r:
        result = json.loads(r.read())
    ticket = result.get("login_ticket")
    if result.get("authentication_state") != "DONE" or not ticket or not ticket.startswith("TC-"):
        raise SystemExit(f"no login ticket: {result}")
    return ticket


def stand_in_data(folder):
    os.makedirs(os.path.join(folder, "dbc", "enUS"), exist_ok=True)
    os.makedirs(os.path.join(folder, "maps"), exist_ok=True)


def main():
    args = sys.argv[1:]
    if args[:1] == ["account-sql"] and len(args) in (3, 4):
        print(account_sql(*args[1:]))
    elif args[:1] == ["form"] and len(args) in (2, 3):
        print(" ".join(form(args[1], *map(int, args[2:]))))
    elif args[:1] == ["login"] and len(args) in (4, 5):
        print(login(args[1], args[2], args[3], *map(int, args[4:])))
    elif args[:1] == ["stand-in-data"] and len(args) == 2:
        stand_in_data(args[1])
    else:
        print(__doc__)
        sys.exit(2)


if __name__ == "__main__":
    main()
