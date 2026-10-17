# Warcraft Collection — the brief

The user's requirements, what was decided, what's open, and where it stands. The manual is
`README.md`.

## What the user asked for

- "Look how we made World of Warcraft and all these other games. Can you do the same with this?"
  with Warcraft III 1.30 (a Google Drive zip, "Warcraft III 1.30 (16by9 ratio) (Multi-Language) by
  Mashiro", 1.8 GB, and its r/warcraft3 post: a pre-Reforged 1.30.4.11274 repack, RoC and TFT).
- "A private server not connecting to Blizzard at all", "play the campaign or whatever as long as I
  upload files", "tell me what I need to import", "a launcher like we did for Warcraft but its own
  separate app".
- Warcraft I Remastered and Warcraft II Remastered too; the user provides all the files.
- "Like the WoW launcher with themes based on the games"; the art came in `branding/`: each game's
  hero, splash and logo (Warcraft III: RoC and TFT logos and heroes, one splash) and the app icon
  (the orc portrait in its frame).
- Asked mid-build: "Are there any key binds that may need touch gesture reworks?" → the quick keys.

## Decided

- **One app, four looks**: `com.logie.warcraftrts`, "Warcraft Collection". Warcraft I, Warcraft II,
  Reign of Chaos, The Frozen Throne; RoC and TFT share one install (1.30 is one program; the game's
  main menu switches, since 1.28.3).
- **Winlator**, as in the World of Warcraft app, at the same commit, compiled into the app
  (`scripts/prepare-winlator-app.sh`, a copy of `prepare-winlator.sh` that takes the app's folder,
  so the WoW app's build is untouched). The runtime goes to `/data/data/com.logie.warcraftrts/rfs`:
  the package is 3 characters longer than `com.logie.warcraft`, so the folder is 3 shorter to keep
  the rewritten path as long as Winlator's.
- **No server**: the campaigns, custom games against the computer and LAN need none. "Private" is
  done in the runtime: its glibc reads `<rootfs>/etc/hosts` and `etc/nsswitch.conf` (compiled-in
  paths, checked in `libc.so.6`; neither file ships), so the app writes `hosts: files dns` and a
  hosts file sending Blizzard's names to 0.0.0.0 before each start (`library/Offline`).
  A PvPGN Battle.net server was considered: its version checks go up to Warcraft III 1.28.2, not
  1.30, so it isn't in.
- **Gestures** for a mouse-driven RTS (`controls/RtsGestures`): tap left click, hold right click,
  one-finger drag box select, two-finger tap right click, two-finger drag camera (arrow keys),
  pinch wheel, three fingers quick keys, four fingers menu, Back Escape.
- **Quick keys** (the user's key-bind question): Shift/Ctrl/Alt latches, control groups 1–0 (tap
  select, hold Ctrl+N), WC3's F1–F3, Tab, Space, F10, Esc, Chat, Type; a strip that doesn't block
  the game (`FLAG_NOT_TOUCH_MODAL`), top or bottom.
- **Warcraft III setup**: registry `Video\reswidth/resheight/windowmode=2` (the phone's size) and the
  repack's `Classic Launcher\w3\Locale` (enUS unless set). The repack's first start asks for CD keys:
  the game menu lists keys found in a text file next to the program (`_SERIAL_KEYS.txt`) and types
  them.
- **Remastered games**: the program is found by known names, else the largest non-helper `.exe`
  (`library/GameLayout`), changeable in Game files; started with `-launch` by default.

## Open (not knowable without the phone and the files)

- Whether 1.30's CEF-based first-run screen (the classic installer choice) works under Wine/Box64.
- Whether Warcraft III's Direct3D 9 runs well on DXVK + the Fold's Adreno (OpenGL is the fallback
  in Graphics), and its sound.
- Whether the Remastered games start without Battle.net at all (`-launch` is unconfirmed for them;
  Blizzard's forum reports they need a Battle.net login). If they don't, the classic versions inside
  their folders, or the GOG releases, are the alternatives to try.
- Whether the registry values above are the ones 1.30 reads (the game also has `preferences.json`).

## Stages

1. **Launcher, imports, the games on Winlator, gestures, quick keys, offline** — built (build 1).
2. After the user's first try: whatever the phone shows (first-run screen, renderer, Remastered).

## Where it stands

Build 1: built locally and in CI; unit tests pass; launcher screenshots made with Robolectric.
Nothing has run on a phone, and no game has been started anywhere (no Windows/ARM device here).
