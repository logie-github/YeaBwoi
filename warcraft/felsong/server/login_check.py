#!/usr/bin/env python3
"""Checks Legion's realm (LegionCore 7.3.5) the way the 7.3.5 client logs in.

    login_check.py account-sql NAME PASSWORD [LEVEL]   print the SQL that makes the account, as the
                                                       app writes it (FelsongAccountRules.createSql)
    login_check.py login CERT NAME PASSWORD            log in to the Battle.net server's REST service
                                                       (https://127.0.0.1:8081/bnetserver/login/, the
                                                       server's own certificate CERT pinned) and print
                                                       the state and whether a ticket came back
    login_check.py hash NAME PASSWORD                  print the account's sha_pass_hash

Exits non-zero if the login gives no ticket. Used by scripts/test-legion-server.sh in CI.
"""
import hashlib
import json
import ssl
import sys
import urllib.request

URL = "https://127.0.0.1:8081/bnetserver/login/"
BUILD = "7.3.5.26972"


def sha_pass_hash(name, password):
    """SHA-256 of "<SHA-256 of the name, in capital hex>:<password>", bytes reversed, capital hex
    (FelsongAccountRules.shaPassHash; the core's LoginRESTService::CalculateShaPassHash)."""
    inner = hashlib.sha256(name.upper().encode()).hexdigest().upper()
    digest = hashlib.sha256((inner + ":" + password.upper()).encode()).digest()
    return digest[::-1].hex().upper()


def create_sql(name, password, level):
    user = name.upper()
    sql = ("INSERT INTO account (username, sha_pass_hash, email, joindate) VALUES "
           "('%s', '%s', '%s', NOW());\n" % (user, sha_pass_hash(name, password), user))
    if level > 0:
        sql += ("INSERT INTO account_access (id, gmlevel, RealmID) SELECT id, %d, -1 FROM account "
                "WHERE username = '%s';\n" % (level, user))
    sql += ("REPLACE INTO realmcharacters (realmid, acctid, numchars) SELECT realmlist.id, account.id, 0 "
            "FROM realmlist, account LEFT JOIN realmcharacters ON acctid = account.id WHERE acctid IS NULL;")
    return sql


def login(cert, name, password):
    context = ssl.create_default_context(cafile=cert)
    # The certificate is the server's own (TrinityCore's, which the patched client trusts by its
    # fingerprint); it has no address for 127.0.0.1, so it is pinned instead of matched by name.
    context.check_hostname = False
    context.verify_flags &= ~ssl.VERIFY_X509_STRICT
    with urllib.request.urlopen(URL, timeout=15, context=context) as response:
        form = json.loads(response.read(16384).decode())
    if not any(i.get("input_id") == "account_name" for i in form.get("inputs", [])):
        raise SystemExit("the login form has no account_name input: %r" % (form,))
    body = {"platform_id": "Win", "program_id": "WoW", "version": BUILD,
            "inputs": [{"input_id": "account_name", "value": name}, {"input_id": "password", "value": password}]}
    request = urllib.request.Request(URL, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=30, context=context) as response:
        result = json.loads(response.read(16384).decode())
    state = result.get("authentication_state", result.get("authenticationState"))
    ticket = result.get("login_ticket", result.get("loginTicket"))
    print("state=%s ticket=%s" % (state, "yes" if ticket else "no"))
    return str(state).upper() == "DONE" and bool(ticket)


def main(argv):
    if len(argv) >= 4 and argv[1] == "account-sql":
        print(create_sql(argv[2], argv[3], int(argv[4]) if len(argv) > 4 else 0))
    elif len(argv) == 4 and argv[1] == "hash":
        print(sha_pass_hash(argv[2], argv[3]))
    elif len(argv) == 5 and argv[1] == "login":
        return 0 if login(argv[2], argv[3], argv[4]) else 1
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
