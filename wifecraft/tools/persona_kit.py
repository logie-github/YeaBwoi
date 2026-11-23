"""Shared by profile_check.py and persona_probe.py: loads the character profiles and eras (assets/dialogue/profiles, eras) and
composes the same prompt pieces as dialogue/PersonaKit.kt (the Kotlin tests are authoritative; this mirrors them for review)."""
import json, math, os, re

ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "android", "app", "src", "main", "assets")
BUDGET = dict(CORE=280, ERA=150, REL=60, REL_MAX=2, TOPIC=70, TOPIC_MAX=2, CUSTOM=90)


def tokens(s):
    words = len(s.split())
    return max(math.ceil(words * 1.35), (len(s) + 3) // 4)


def fit(s, mx):
    if tokens(s) <= mx:
        return s
    out = ""
    for p in re.findall(r"[^.!?]+[.!?]+|[^.!?]+$", s):
        p = p.strip()
        nxt = p if not out else out + " " + p
        if tokens(nxt) > mx:
            break
        out = nxt
    if out:
        return out
    w = s.split()
    while len(w) > 1 and tokens(" ".join(w)) > mx:
        w.pop()
    return " ".join(w)


def load(assets=ASSETS):
    d = os.path.join(assets, "dialogue")
    idx = json.load(open(os.path.join(d, "eras", "index.json"), encoding="utf-8"))
    eras = [json.load(open(os.path.join(d, "eras", e + ".json"), encoding="utf-8")) for e in idx["eras"]]
    eras.sort(key=lambda e: e["order"])
    profiles = {}
    pdir = os.path.join(d, "profiles")
    for f in sorted(os.listdir(pdir)):
        if f.endswith(".json"):
            p = json.load(open(os.path.join(pdir, f), encoding="utf-8"))
            profiles[p["id"]] = p
    return idx, eras, profiles


def order(eras, eid):
    return next(e["order"] for e in eras if e["id"] == eid)


def field(p, key, e, eras):
    v = p["core"].get(key, "")
    for x in p.get("variants", []):
        lo = order(eras, x["from"]) if "from" in x else -10**9
        hi = order(eras, x["until"]) if "until" in x else 10**9
        if lo <= e["order"] <= hi and key in x:
            v = x[key]
    return v


def voice_lines(p, e, eras):
    out = []
    for v in p["voice"]:
        t, fr, un = (v, None, None) if isinstance(v, str) else (v["t"], v.get("from"), v.get("until"))
        if (order(eras, fr) if fr else -10**9) <= e["order"] <= (order(eras, un) if un else 10**9):
            out.append(t)
    return out


def core(p, e, eras, mx=BUDGET["CORE"]):
    f = lambda k: field(p, k, e, eras).strip()
    voice = voice_lines(p, e, eras)
    def sample(n):
        if not voice or n == 0:
            return []
        s = (e["order"] * 3) % len(voice)
        return [voice[(s + i) % len(voice)] for i in range(min(n, len(voice)))]
    attempts = [(d, n) for d in range(4) for n in (3, 2, 1)] + [(d, 0) for d in range(4)]
    for drop, lines in attempts:
        b = ["Who you are: " + f("who"), "Nature: " + f("temper"), "How you speak: " + f("speech")]
        if drop < 1: b.append("What you hold dear: " + f("values"))
        if drop < 2: b.append("Your look: " + f("look"))
        if drop < 3: b.append("In intimacy: " + f("intimate"))
        s = sample(lines)
        if s: b.append("You sound like this: " + " / ".join('"%s"' % x for x in s))
        b.append("Stay in this voice and this mind in every line.")
        out = "\n".join(b)
        if tokens(out) <= mx:
            return out
    return fit("Who you are: %s Nature: %s" % (f("who"), f("temper")), mx)


def era_slice(p, e, mx=BUDGET["ERA"]):
    st = p["eraStates"].get(e["id"]) if p else None
    state = None
    if st:
        state = ("You are placed here out of your own time, so live it as you would be then: " + st["t"]) if st["s"] in ("out_of_era", "dead") else "Your life now: " + st["t"]
    cut = "Nothing later than this age is known to you."
    tries = [[f"The age: {e['label']}, {e['years']}.", e["world"], e.get("idiom", ""), cut, state],
             [f"The age: {e['label']}.", e["world"], cut, state], [f"The age: {e['label']}.", cut, state]]
    for t in tries:
        s = " ".join(x for x in t if x)
        if tokens(s) <= mx:
            return s
    return fit(f"The age: {e['label']}. {cut} " + fit(state or "", mx - 20), mx)


def blocked_after(eras, e):
    return sorted({w for x in eras if x["order"] > e["order"] for w in x["introduces"]})


def find_blocked(text, eras, e):
    words = blocked_after(eras, e)
    if not words:
        return None
    m = re.search(r"(?<![\w'])(" + "|".join(re.escape(w) for w in words) + r")(?![\w'])", text, re.I)
    return m.group(1) if m else None


def relation_text(r, e, eras, mx=BUDGET["REL"]):
    cands = [(order(eras, k), v) for k, v in r.get("eras", {}).items() if order(eras, k) <= e["order"]]
    line = max(cands)[1] if cands else None
    body = f"{r['default']} Now: {line}" if line else r["default"]
    addr = f" You call them: {r['address']}." if r.get("address") else ""
    return fit(f"{r['name']}: {body}{addr}", mx)


def system_addition(p, e, eras):
    return core(p, e, eras) + "\n" + era_slice(p, e)
