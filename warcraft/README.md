<p align="center">
  <img src="branding/logo.png" height="110" alt="World of Warcraft: Wrath of the Lich King logo">
</p>

# World of Warcraft

An Android app for **World of Warcraft: Wrath of the Lich King 3.3.5a** that plays the game on the phone, your own 3.3.5a client run by Wine, Box64 and the phone's GPU inside the app, with its own private realm on the phone: a [ChromieCraft](https://www.chromiecraft.com/)-style [AzerothCore](https://www.azerothcore.org/) server with [playerbots](https://github.com/mod-playerbots/mod-playerbots), running natively on the phone's arm64 processor next to its MariaDB database. The launcher is styled after Wrath's Northrend: the Lich King before Icecrown Citadel with snow drifting across, the logo, and frost-blue panels with gold corners.

Two realms live in the app, one at a time, with the same accounts: **ChromieCraft**, and **Wrath Remix**, a Legion Remix-style replay of Northrend from level 10 with Moratari and the Infinite Dragonflight (see "Wrath Remix" below).

It's being built in stages (see [`PLAN.md`](PLAN.md)). **Stage 1, done:** the launcher and the server; a PC's 3.3.5a client on the same Wi-Fi can play on the realm. **Stage 2, this version:** the game itself in the app, started from the launcher straight to its login screen on the realm, with touch gestures (tap to click, swipe to walk, hold for bags, double tap to jump). **Stage 3:** every server option in the launcher (progression brackets, rates, realm settings), bot management, add-ons, controls and graphics settings.

| | |
| --- | --- |
| Package | `com.logie.warcraft` |
| Releases | **World of Warcraft 1.0.N**, tags `wow-build-N` (**Actions → Build World of Warcraft**) |
| Server | AzerothCore at the revision ChromieCraft runs, with the playerbots fork's core changes merged in, ChromieCraft's modules and mod-playerbots, built for Linux arm64 (musl) from the commits in `server-version.env`, and this repository's `mod-wrath-remix` (`modules/`) |
| Realms | ChromieCraft (realm 1, port 8085) and Wrath Remix (realm 2, port 8086): one auth server and set of accounts, each realm its own world server, configs and databases |
| Server data | AzerothCore's ready-made data, downloaded in the app (the version in `server-data.env`), or made from your client |
| Client | Yours: World of Warcraft 3.3.5a (build 12340), enUS. Never part of the app. |
| Game runtime | Wine, Box64, DXVK, the Vortek and Gladio graphics drivers and an X server, from Winlator 11.2's code compiled into the app (no Winlator app, screens or settings; see "How it's built") |

## First launch

1. Install the APK from the release page on the phone.
2. Open the app. The first **Press start** unpacks the server and creates its databases (a few minutes, once), then starts the login server. The world server waits for the server's data.
3. Get the server's data, once (**Game files**). One of:
   - **Download (the quick way):** **Download server data** gets AzerothCore's ready-made data: dbc, Cameras, maps, vmaps and mmaps (pathfinding, which creatures and bots need to move well), in the version this server is built for. It's a 1.2 GB download that unpacks to 3.2 GB, and it doesn't need your client. The app checks the file is the right one before unpacking it. If the download stops, the button carries on from where it got to. CI tests every build on this same data: it starts the world server on it and takes a character into the world.
   - **On the phone, from your client:** **Pick folder** (your 3.3.5a folder, the one with `Wow.exe` and `Data` in it) or **Pick zip** to add your client. The app copies it (about 17 GB) and checks that `Wow.exe` is 3.3.5.12340. Then **Make server data**: dbc, maps and vmaps take minutes; mmaps an hour or more. It carries on with the app in the background.
   - **On a Windows PC, from your client:** download **WarcraftServerData-windows.zip** from the same release, unzip it, and drag your client's folder onto `extract-server-data.bat`. Copy the `server-data.zip` it makes to the phone, then **Import server-data.zip**.
4. Add your client, if it isn't in yet (**Game files → Your client**: **Pick folder** or **Pick zip**).
5. **Accounts**: make an account to log in with (the first one is a game master by default).
6. **Press start**. The realm starts, and meanwhile, the first time, the app installs the game's Windows (from the app, a minute or two) and sets it up. Loading the world takes a few minutes on a phone; once the realm is online the music stops and the game starts, at its login screen on the realm.

After that, opening the app checks for an update and starts nothing by itself: the realm stays off until **Press start**, which starts the realm and the game (without the client, the realm alone, for a PC's client). So you can change add-ons, settings or accounts first.

Copy files onto the phone before picking them: files in cloud folders can't be read this way. The download needs about 5 GB free while it unpacks (3.2 GB after). Leave about 25 GB free for the client and the server's data, and another 2 GB for the game's Windows.

## The game

**Press start** (or **Play** once the realm is online) starts your client, `files/client/Wow.exe`, on Wine inside the app. Before each start the app points the client at the realm on the phone and sets it up for the screen:

- `Data/<locale>/realmlist.wtf`: `set realmlist 127.0.0.1`.
- `WTF/Config.wtf` (your other settings in it stay): `realmList` 127.0.0.1, `realmName` ChromieCraft (the realm is chosen for you), the intro movie off, the terms already read, windowed and maximized at the game's resolution, and your account's name in the login box when you have one account.
- The game's resolution: the screen's own (Graphics), set each start for the screen the phone is on, with the game's first-start hardware check off (`hwDetect 0`) so it keeps it. The game fills the screen edge to edge, the camera's edge included; a game window that comes up smaller than the screen anyway is scaled up to fill it.

The game's Windows is one Wine prefix, **World of Warcraft**, with the client as drive D:: DXVK for Direct3D, the Vortek Vulkan driver, ALSA sound. Its programs go in the app's data folder (`/data/data/com.logie.warcraft/rootfs`), the path they're built for.

While it starts, the app shows its own loading screen (the logo over the dark, with the snow), until the game's window is up.

### Touch controls

Nothing is drawn over the game, not even the mouse pointer: the screen itself is the controls, and your finger is the pointer. The pointer is still there for the game (it goes where you tap, and the game's cursor there tells the app what a tap does); it just isn't drawn. A mouse connected to the phone shows it again. Split like most phone MMOs: the left thumb walks, the right one looks. Each finger counts on its own, so you can walk and turn at once, and tap an ability while walking.

| Gesture | In the game |
| --- | --- |
| **Left half: swipe and hold** | Walk, as with a stick under your thumb (nothing is drawn): up W (forward), down S (back), left Q and right E (sideways steps), and the diagonals between. It follows a thumb that goes further, so changing direction is quick. Lift to stop. |
| **Right half: drag** | Turn the camera and your character (the right button held in the middle of the screen), so walking goes where you look |
| **On the spellbook: drag** | Drags the spell, as the mouse does on a PC: put your finger on a spell, move it onto an action bar button and lift. A tap there is a click (a tab, a page, a spell). Only while the spellbook is open; elsewhere the halves walk and turn as usual. |
| **Character creation: swipe** (either half) | Turns your new character, as dragging it with the mouse does on a PC (the left button held on the middle of the screen, going sideways with your finger). On that screen a hold presses nothing, so no B or M lands in the name box; taps click as anywhere. |
| **Tap** (anywhere) | What a click does in WoW, by the game's own cursor where you tap: on the interface and the ground, a left click (buttons, menus); on an **enemy**, selects it as your target; on anyone or anything else the cursor offers something for, does it at once, as a right click does on a PC: a speech bubble talks to the NPC, a bag loots, a gear or flower uses or gathers. |
| **Double tap** | On an enemy: attack it. On the action bars (the bottom of the screen and its right edge, where the game's interface puts them): two presses, so you can tap a button as fast as you like. Anywhere else: jump (Space). |
| **Hold** one finger still | Bags (B). While a cutscene plays (a new character's intro, a quest's cinematic or movie) it skips it instead (Escape). |
| **Tap, then tap and hold** | Map (M) |
| **Pinch** with two fingers | Zoom the camera: spread to zoom in, close to zoom out. Put both fingers down together (within about a third of a second) and move them apart or together. |
| **Two-finger tap** | Target the nearest enemy (the game's Tab; again for the next one) |
| **Two fingers swiped up** | Chat: opens it, with the keyboard |
| **Three-finger tap** | The keyboard, for any other text box (mail, auction house search, macros) |
| **Four-finger tap** | The game's menu: **Back to the game**, **Keyboard**, **Leave the game** (back to the launcher) |
| **Back** | Escape: closes a window, or opens the game menu |

How a tap reads the cursor: the app sees the game's cursor (WoW's hardware cursor, which the app turns on in `Config.wtf`) and learns the plain arrow at the login screen. When a tap lands on a special cursor, its left click selects what's there, and the realm on the phone says whether that's an enemy (the server writes it for the app; `patches/azerothcore/0007`): an enemy stays selected, anything else gets the right click too, when the same cursor is still there after the left click. So a tap that picks up an item, puts one down or aims a spell stays a single click. After the first enemy, the app knows the sword cursor and doesn't wait for the realm. If the game's cursor can't be seen (only one cursor ever shows), tapping the same NPC again within a second and a half talks to it.

How the app knows the character creation screen: once a second it looks at a small picture of the game's window (Winlator draws one, 256 pixels high) for the race list's two banners down the left, the Alliance's blue one with the Horde's red one beside it, where the 3.3.5a client puts them; the game's other screens don't have them there. An HD look or a client patch that redraws that screen's banners could hide it from the app; swipes there would then walk and turn the camera as elsewhere.

How a drag knows the spellbook: the app puts its own small add-on into your client before each start (`Interface/AddOns/WowTouch`, left out of the add-on manager's list). While the spellbook is open, it tells the realm on the phone where it is, in an add-on message to your character; the realm keeps it for the app instead of passing it on (`patches/azerothcore/0009`). A finger that comes down on that spot is the mouse with its left button held until it lifts.

### The keyboard

Text goes in through a prompt: a box at the top of the screen with the phone's keyboard under it. The game behind it stays as it is (its screen never moves or changes size); what you send is typed into the game. It comes up by itself where the game wants text:

- **The login screen:** as soon as the game appears, for your password (the account's name is filled in already). **Log in** types it and presses Enter. With **Log in automatically next time** on (it is, at first), the app keeps the password on the phone once the realm confirms it's right, and from then on the game logs in by itself: no prompt, the password typed for you as soon as the login screen is up.
- **Character creation:** when your account has no characters yet, the name prompt follows a few seconds after the login, once the game is there. **Type it** puts the name in the name box in place of what's there (a random name); pick the race and class and press Accept in the game. The game's **Randomize** under the name gives one name of the race (a realm name is one word, letters only).
- **Chat:** two fingers swiped up open the game's chat and the prompt. **Send** types the line and presses Enter; **Cancel** closes the chat.
- **Any other text box** (mail, the auction house search, a new character's name later): tap the box in the game, then three fingers (or the menu's **Keyboard**). **Type it** types the text into it.

**⌫** deletes a letter in the game's own box, and so does the keyboard's Backspace once the prompt's box is empty: for what's in the game's box already. The prompt has no suggestions or corrections, and tapping beside it closes it.

## Add-ons

The **Add-ons** tile opens the add-on manager over the whole screen. Its add-ons come from [warperia.com's 3.3.5a list](https://warperia.com/wotlk-addons/), or from zips on the phone.

**Browse** shows warperia.com's list, 20 at a time and more as you scroll (two columns on the open Fold):

- **Search** by name (it searches when you stop typing, or with the keyboard's search key), sort by **Popular**, **New** or **Updated**, and pick a category (Action Bars, Bags & Inventory, Unit Frames...).
- **Install** on a card installs that add-on in one tap. The button counts down the few seconds the site asks free downloads to wait (Premium members of the site skip them; the app waits like any visitor), then shows the download and the install. Installs go one at a time; tap Install on several and they queue. They carry on if you close the screen.
- **Tap a card** for the add-on's page: its pictures (tap one to see it whole), what it does, its versions and changes, downloads and categories, with **Install** / **Update**, **Turn off** / **Turn on**, **Delete** and **Website** (the page on warperia.com in the browser).

**Installed** lists what's in the client, from warperia.com or from zips:

- **Update all** and an **Update** button on each add-on with a newer version on the site (the site's version or its last-updated day differs from what's installed). The app looks when the screen opens (at most every half hour) or with **Check for updates**; the tile on the launcher says how many updates there are.
- The **switch** turns an add-on off without deleting it: it moves to `Interface/AddOns-off`, where the game doesn't look, and back when turned on. An update keeps it where it is. The game's own **AddOns** button at character select still works as usual.
- **Delete** (tap twice) removes the add-on's folders; a library folder another installed add-on also brought stays.
- **Import zip** picks a zip on the phone. The app finds the add-ons in it however it's packed (the add-on's folder at the top, several side by side, inside `Interface/AddOns/`, a download's own folder around it, or no folder at all), names each folder after its `.toc` as the game needs, and puts them in the client's `Interface/AddOns`, each replacing an older copy of itself.

Add-ons load the next time the game starts, or with `/reload` in the game. The app sets the game to load add-ons whatever version they say they're for (`checkAddonVersion 0`), so a 3.3.5a add-on marked for another version still loads; add-ons for today's game don't work in 3.3.5a. In-game settings of add-ons are saved by the game in the client's `WTF` folder.

## HD look

**Graphics → HD look** puts HD textures and models from [ChromieCraft's downloads page](https://chromiecraft.com/en/downloads/) into your client, or takes them out:

| Choice | What's in it | Download |
| --- | --- | --- |
| **Off** | The game's own look. | — |
| **HD patch** | ChromieCraft's HD patch (`additional_patches_for_335a.zip`): characters, Warlords of Draenor NPC models, goblin textures (`Patch-H`); mounts and creatures (`F`, `G`); the world's and cities' textures (`T`); sunlight (`S`). Its Cataclysm trees (`X`) are left out: its readme says they put lower-resolution textures over `T`'s. | about 3.4 GB |
| **Alternative HD patch** | The community HD client's look (the page's "Alternative HD Patch", the stable English client), the patches its patch menu turns on by default for the game's look: HD characters (`a`), HD models and NPCs (`f`), the world's textures (`6`), new spell effects (`s`, without its spell table), water (`w`), sky (`x`), music (`e`), loading screens (`q`). Left out: its login and realm screens (the app logs in to the phone's realm), its character creation screen, interface, cursor and add-ons. | about 7.6 GB |

Only those patch files are downloaded, each out of its zip by itself (the alternative client's zip is 29 GB), checked against the zip's checksum, into the client's `Data` folder (`patch-H.MPQ`, `enUS/patch-enUS-A.MPQ`...). Downloads carry on in the background with a notification, and a dropped connection carries on where it was. Switching between the two takes the first one's files out before the second's come in; **Off** takes them all out. A file of your client's own with the same name is kept aside and comes back.

With an HD look, the game runs as `WowPatched.exe`, a copy of your `Wow.exe` that may use 4 GB of memory (HD textures need more than the 2 GB a 32-bit game gets otherwise). The copy is made only from the 3.3.5a (12340) executable: every byte it changes is checked first. Your `Wow.exe` stays as it is, and **Off** removes the copy.


## Custom races (taken out)

Versions 1.0.44 to 1.0.48 had a **Custom races** switch. It's gone. On a phone that had it on, the app takes out what it left: its folder in your client (`Data/patch-Z.MPQ`) as the app opens, with the HD look's files it had set aside back in place, and the realm's part at the realm's next start (**Taking the custom races out**: the world database made again without them, a few minutes, once). Characters made with them stay in the realm's database, left out of the character list.

## Modules

**Modules** (a launcher tile) adds [AzerothCore modules](https://github.com/topics/azerothcore-module) to your realm: an auction house bot, transmogrification, solo dungeons and the hundreds more GitHub lists under that topic.

- **Browse** lists them from GitHub, by most stars or most recently changed, with a search. A module's page shows its facts and its README; **Add** puts it on your list (**In the realm** marks the realm's own modules, always in).
- **Your realm** has the list and the build. A module is code built into the realm's world server, so a realm with yours has to be built again: **Build the realm with them** asks this app's repository on GitHub to do it (the workflow `build-warcraft-modules.yml`), with each module at its branch's newest commit, from this version of the app's own release, with the main build's compiler cache (about 20 minutes with one module). The build is tested the way the app's own realm is (the world server started on the server data, a character taken into the world), then attached to the app's release as `server-<build>.tar.xz` (the last three are kept).
- The build carries on with the app closed. The screen follows it (the step it's on, **Open on GitHub**, **Stop the build**), and the app looks again each time it opens. Once it's built, **Put it in** (or the app at its next opening, while the realm is stopped) downloads it and puts it in place of the realm. Characters, accounts, bots and settings stay; a module's database changes are made at the realm's next start, and its settings are in `files/etc/modules`.
- **Kept realms**: each realm you put in stays on the phone (about 90 MB each, the last twelve), so you can swap modules without building again. **Put this one in** puts a kept realm back in place of the one in now (a minute or two); **The app's own realm back** puts the realm the app came with in its place; **Use these modules** makes a kept realm's modules your list again, to change it or build it again; **Delete** frees its space. Taking a module out cleans up after it: at the realm's next start, if a module whose SQL went into the world database isn't in the realm any more, the world database is made again without it (a few minutes, **Taking out what the modules you took out put in the world**), and the realm's modules put theirs in again. Characters and accounts stay, and what a module keeps in the characters database stays too, for when it's put back (an item a module added that's no longer in the world is gone from the bags that had it).
- An app update brings its own realm: a realm with modules is for the version it was built for (its package goes; it stays listed), so after an update, **Build it again for this version** builds the same modules for the new one.
- Starting a build needs your own GitHub token: on github.com, **Settings → Developer settings → Personal access tokens → Fine-grained tokens**, with access to this repository only and the permission **Actions: read and write**. The app checks it with GitHub and keeps it in its own private storage; it's sent to GitHub alone. Browsing works without one (GitHub allows fewer searches a minute without).
- Modules are other people's code, written for AzerothCore, not always for ChromieCraft's copy of it or its playerbots: some don't compile with it (written for an older or newer AzerothCore, for example: the build leaves those out, builds the realm with the rest and says which and why, and the list marks them **doesn't compile with this realm**), some need a client patch or settings of their own. The build runs them with a read-only token; only its last step, which runs none of their code, can add the realm to the release.

## Wrath Remix

A second realm, apart from ChromieCraft: Wrath of the Lich King's story retold as Blizzard's Legion Remix retold Legion. The Infinite Dragonflight and the Keepers of Time have made a contained copy of the war in Northrend; you're a **Timerunner** in it. History has to reach its recorded end (the Wrathgate falls, Arthas is defeated in Icecrown Citadel), but Moratari, your guide, skips the errands in between. Everything in it is its own: characters, bags, gold, the world. Nothing you do there changes ChromieCraft, and nothing in ChromieCraft changes it.

**Choosing it:** the **Realm** tile, **Wrath Remix**, then **Press start**. The realm you choose is the one that starts (and the one the game logs in to); choosing the other one while a realm runs stops the server first. The first start of Wrath Remix makes its databases (a few minutes, once, and about 1 GB more of the phone's storage). Your accounts work on both; your characters are per realm.

**What it is now** (the first part of the guide, `REMIX-GUIDE.md`; more chapters' transitions, Infinite Power and the rest follow):

- **Timerunners start at level 10** in the Caverns of Time, where Moratari waits (death knights start at Acherus as usual, and Moratari brings them to the Bazaar when the Ebon Blade lets them go). No race's intro plays. Quests give three times the usual experience and kills twice.
- **The prologue**, five short quests: *The Weight of Time* (three frozen moments of the war), *An Agreement Across the Hours* (Andormu's terms, Eternus' reply), *A Small Demonstration* (Moratari lets three temporal anomalies through: kill them), *The Bazaar Beneath the Violet Sky* (Moratari opens the way to the Infinite Bazaar), *The War We Must Remember* (Eternus sends you to Borean Tundra or Howling Fjord, where Moratari meets you). Then *In the Shadow of the City* from her, back at the Bazaar.
- **Northrend at your level.** Each chapter's zone and dungeons are moved to its levels: Borean Tundra and Howling Fjord 10 to 20, Dragonblight 20 to 32, Grizzly Hills 32 to 42, Zul'Drak 42 to 52, Sholazar Basin 52 to 60, the Storm Peaks 60 to 70, Icecrown 70 to 80 (Crystalsong Forest 60 to 70; the Battle for the Undercity at Chapter II's end). Creatures get the level, health and damage of their chapter, and their spells hit and heal for the same share. The quests are at those levels too, and their armor and weapons rewards shrink with them (a Borean Tundra reward is about a quarter of what it is at 71), as does their gold. Dungeons open at their chapter's levels on normal (Utgarde Keep and the Nexus at 13, Halls of Reflection at 76); heroic is still for 80, as are the raids.
- **Moratari's transitions**, where the story has an afternoon of errands: she stands by the quest giver, and her quest takes the place of the block. In Chapter I: Leryssa's search for Thassarian (Alliance, Borean Tundra), the parts for Mobu's tank (Horde, Borean Tundra), Westguard Keep's missions before Operation: Skornful Wrath (Alliance, Howling Fjord), and *Into the Dragonblight* at the end of each coast, which takes you to Wintergarde Keep or Agmar's Hammer. You can still do the skipped quests: the next one opens either way.
- **The Infinite Bazaar**, on the ground of Crystalsong Forest by the Violet Stand (the Kirin Tor's teleporter there goes up to Dalaran). Your hearthstone takes you home to it after the prologue. There: **Eternus**, **Moratari** (where you are in history; "the record became tangled" takes you back to the start of your chapter), **Andormu** and **Nozari**, **Alurmi** (Bronze), **Zephyr** (travel: Dalaran, the Caverns, the landings, each chapter's camp as your level reaches it), **Sa'at** (the chapters and dungeons), the **Chronologist** (your chapter; go to the start of any chapter your level allows), **Momentus** (Infinite Power, coming), and a **Timeworn Instructor** for every class (all your spells, as a capital's trainer teaches them).
- **Bronze**, earned for every quest (25, more for Remix's own) and kill (1, elites 4, rares 20, dungeon bosses 40, raid bosses 150). Alurmi sells, for Bronze: heirlooms (shoulders 300, chests and weapons 500, trinkets 400: they grow with you from 10 to 80), mounts (ground 1,500, drakes and proto-drakes 4,000 to 8,000), companions and bags. Your Bronze is shown at Alurmi, the Chronologist and Moratari, and with `.remix` in chat. The prologue gives enough for a first heirloom.
- **Riding** is taught for free at 20, 40, 60 and 70, and Cold Weather Flying at 77.
- Its settings are its own (`files/etc-remix`): its bots, rates and options don't follow ChromieCraft's, and ChromieCraft's progression brackets don't apply (all of Wrath is open). Modules you build into the realm are in both.

Not built yet: the transitions of Chapters II to VII (the campaign's quests from the guide are matched to the game's quest ids in `modules/mod-wrath-remix/data/campaign_manifest.csv`, ready for them), Infinite Power, the Keepers' world assignments, Heroic World Tier, timed dungeons, the epilogue after Arthas. The client's quest cache is cleared when you switch realms (Remix gives some quests and items other levels).

## Playing from a PC

On a PC on the same Wi-Fi as the phone, in the client's folder, open `Data\enUS\realmlist.wtf` and set its line to the address the app shows under **From a PC on this Wi-Fi** (also in **Server**):

```
set realmlist 192.168.1.42
```

Start `Wow.exe` and log in with an account made in the app. The realm is **ChromieCraft**. The phone has to stay on the Wi-Fi with the server running; the server keeps going with the screen off.

## The launcher

The launcher plays Wrath of the Lich King's main title theme, looping, while it's on screen. It fades in when the launcher opens and pauses when you leave the app, when another app plays sound, or during a call. The note at the top right of the picture turns it off and on, and the app remembers the choice. Once the game starts, the music stops until the app is opened anew.

| Tile | What it does |
| --- | --- |
| **Realm** | Which realm Press start starts: **ChromieCraft** or **Wrath Remix** (see **Wrath Remix**). |
| **Server** | How the server is (each step while it starts), Start and Stop, its logs (world, login, database, setup), the address for a PC, battery restrictions, and **Delete the databases** to start over. |
| **Game files** | Your client (add it from a folder or a zip) and the server's data: which parts are there, downloading them, making them on the phone, importing them from a PC. |
| **Accounts** | Make accounts, change their passwords, make them game masters or players, delete them; **Log in automatically** with one (the game types its password at the login screen; the first account you make here does it from the start). Needs the server running. |
| **Graphics** | The game's resolution: **the phone's screen** (every pixel, the default), **High** (1080 lines) or **Balanced** (720 lines, the smoothest), at the shape of the screen the phone is showing (a Fold's inside or outside). And its **HD look** (see below). Takes effect the next time the game starts. |
| **Add-ons** | The add-on manager: browse and search warperia.com's 3.3.5a add-ons, install in one tap or open an add-on's page; what's installed, with updates, on/off and delete; import zips. |
| **Modules** | AzerothCore's modules from GitHub: browse them, pick some, have GitHub build the realm with them and put it in (see **Modules**). |
| **App icon** | The home-screen icon: Wrath of the Lich King or Cataclysm, from your icon pack. |
| **Updates** | Checks for a newer version and installs it. |

While the server runs, Android shows a notification for it, with **Stop**. While the game is on screen the notification goes away (the game in front keeps the app running); it comes back when you leave the game, switch to another app or turn the screen off, so the server keeps going.

## The server

ChromieCraft runs a progressive realm: content opens bracket by bracket, as the original game did. The server starts the way ChromieCraft did, with the first brackets (`Bracket_0` and `Bracket_1_19`) on, at blizzlike rates. The playerbots module starts with 50 random bots (its own default is 500, too many for a phone), which only run while someone is logged in. Changing brackets, rates, realm options and bots in the launcher comes in stage 3; until then they're in the configs (`files/etc/worldserver.conf`, `files/etc/modules/*.conf`), and a game master can use the GM commands in the game's chat (`.help`).

Modules: [mod-playerbots](https://github.com/mod-playerbots/mod-playerbots), [mod-progression-system](https://github.com/azerothcore/mod-progression-system), [mod-zone-difficulty](https://github.com/azerothcore/mod-zone-difficulty), [mod-chromie-xp](https://github.com/azerothcore/mod-chromie-xp), and from ChromieCraft's own: [mod-chromiecraft-smartstone](https://github.com/chromiecraft/mod-chromiecraft-smartstone), [mod-hoard-collector](https://github.com/chromiecraft/mod-hoard-collector), [mod-npc-talent-template](https://github.com/chromiecraft/mod-npc-talent-template). ChromieCraft's staff-only modules (GM command presets, contributor bonuses, support tools, the test realm's changes) are left out.

How it runs: MariaDB listens on 127.0.0.1 only (port 3310); the login server on port 3724 and the world server on 8085 (Wrath Remix's on 8086), reachable from the Wi-Fi. Wrath Remix's configs are `files/etc-remix/worldserver.conf` and `files/etc-remix/modules/*.conf`. The realm list gives the phone's own client 127.0.0.1 and a PC the phone's Wi-Fi address.

If Android stops the server with the screen off: in **Server**, **Allow** the app to run without battery restrictions. On Android 14 and later, Developer options also have **Disable child process restrictions**, for phones that still stop it.

The server only runs for the phone's main user (its programs are built for the app's folder there), not in a second user or a work profile.

## Logging in by itself

The game logs in by itself with the account set to **Log in automatically** (in **Accounts**, or by keeping **Log in automatically next time** on in the game's password prompt). Its password is kept in the app's own storage on the phone, and nowhere else: not in the app's code, not on GitHub. The app checks it against the realm's account before keeping it, and puts that account's name in the login box. At the login screen it waits for the game's cursor to show (the screen is ready), then types the password and presses Enter. **Stop logging in automatically** (in **Accounts**, on that account) forgets it.

## Saves

Accounts, characters and the world are in MariaDB's databases in the app's storage (`files/mysql`). They stay through updates of the app; the server applies the new version's database updates when it starts. **Delete the databases** (in **Server**) starts over with new ones. Uninstalling the app deletes everything, your client copy included.

## Updates

Like the other apps: the app checks this repository's releases (`wow-build-N`) when it opens and from **Updates**, stops the server, and installs over itself. A private repository needs the `DOLPHINXD_UPDATE_TOKEN` secret (see the main README). It's signed with the same key as the other apps.

## How it's built

| Path | Contents |
| --- | --- |
| `server-version.env` | The core (ChromieCraft's copy of AzerothCore), the playerbots fork's core, the modules, each at a pinned commit |
| `server-data.env` | The server data the app downloads: AzerothCore's [client-data](https://github.com/wowgaming/client-data) release that the pinned core uses itself, with its size and SHA-256 |
| `patches/azerothcore/` | Changes to the core: building on musl, telling the app about selections, cutscenes and where the spellbook is, and module configs from the realm's own folder (`WOW_MODULES_CONF_DIR`) |
| `../scripts/fetch-azerothcore.sh` | Checks out the core, merges the playerbots fork's changes, applies the patches, checks out the modules (and those in `WOW_EXTRA_MODULES`, the user's, for a realm with modules) |
| `../scripts/compile-azerothcore.sh` | Compiles it all in an Alpine (musl) container, into `build/azerothcore-dist`: the programs, their configs and the SQL. The slow part (CI caches it, and ccache makes a new core revision quicker) |
| `../scripts/package-azerothcore.sh` | Packs `android/server/server.tar.xz` (git-ignored) from that, in the same Alpine, in a few minutes: the programs with their own musl loader and libraries, Alpine's MariaDB, OpenSSL's legacy provider, the system call shim, the SQL, the configs and a seed database folder |
| `../scripts/build-azerothcore.sh` | Both, one after the other |
| `server/ctl.sh` | Runs the server's programs, the same way on the phone and in CI; both realms (`main`, `remix`): their configs, databases, world servers and realm list entries |
| `modules/mod-wrath-remix/` | Wrath Remix (its own README): the module, its SQL for the Remix databases only, the campaign's quests matched to the game's (`tools/quest_audit.py`) |
| `REMIX-GUIDE.md`, `REMIX-ASSESSMENT.md` | The design Wrath Remix follows (yours), and the earlier assessment |
| `../scripts/test-warcraft-server.sh`, `server/auth_check.py` | CI's test of the package, unpacked where the phone puts it: MariaDB, the databases, a 3.3.5a login with the realm list, the world server started on the downloaded data, a character created and taken into the world by a 3.3.5a world client written in Python, a GM command, the app's add-on message kept for the app, and the shutdown the app does; then what earlier versions' custom races left taken out (the world database made again, their files gone, a character of one of them left out of the character list but kept), and a module taken out of the realm taking what it put in the world database with it (`ctl.sh modules`); then Wrath Remix: its databases, its world server with the module on (off in ChromieCraft's), both realms in the realm list, a level 10 Timerunner who plays the prologue to the first chapter (`server/remix_prologue.steps`, with a heirloom bought for Bronze), and the floor under every NPC it placed (`server/remix_check.py`) |
| `winlator-version.env` | The game's runtime: Winlator's Android app at the commit Winlator 11.2 pins, and its install path with this app's |
| `patches/winlator/` | Changes to Winlator: its runtime in this app's data folder and Exit back to the launcher; `if` chains for its `switch` statements on resource ids (built into the app's module they aren't constants); installing the runtime without its screens; the app's gestures, menu and loading screen over the game screen in place of Winlator's, and Back as Escape |
| `../scripts/prepare-winlator.sh` | Puts Winlator into `android/winlator` (git-ignored), which the app's module builds with its own code: Winlator's Java code (using the app's `R`), resources, native code (NDK 24, CMake 3.22), prebuilt libraries and assets |
| `../scripts/winlator-rewrite.py` | Rewrites the install path compiled into every archive of Winlator's runtime (`/data/data/com.winlator/files/rootfs` to `/data/data/com.logie.warcraft/rootfs`, the same length, so programs keep their layout) |
| `windows/` | `extract-server-data.bat` and the notes that go with the Windows data tools |
| `android/` | The app (Kotlin, Jetpack Compose); `server.tar.xz` goes in as an asset. `game/Game.kt` starts the game; `controls/TouchGestures.kt` is the gestures, `game/GestureView.kt` puts them on the game screen |
| `android/app/.../modules/`, `ModuleStore.kt`, `ui/WowModules.kt` | The module browser: GitHub's topic search, READMEs as text, the builds asked for and followed, the realm put in, the realms kept to swap between |
| `../.github/workflows/build-warcraft-modules.yml` | A realm with the user's modules, started by the app: checked inputs, the app's release checked out, compiled with the main build's compiler cache (a module that doesn't compile left out, the rest compiled again), packaged, tested, attached to the release with a record of what's in it (`server-<build>.json`) |
| `unit-tests/` | JVM tests: account passwords (SRP6), configs, the client's version and settings, zip layouts, downloads out of zips, the HD looks, updates, the servers' logs, GitHub's module lists and READMEs, the module builds' requests and runs |
| `branding/` | Your art and `make-icons.py`, which makes the launcher icons |
| `android/app/src/main/res/raw/wow_title_music.ogg` | The launcher's music: your MP3 of the main title theme, as Opus at 160 kbps (11 MB): `ffmpeg -i <mp3> -map 0:a -map_metadata -1 -c:a libopus -b:a 160k wow_title_music.ogg` |
| `fonts/` | Licenses of Cinzel and Jost (SIL OFL) |

CI (`.github/workflows/build-warcraft.yml`) builds the server on GitHub's arm64 runners: the compile is cached by the pins, the patches and the compile scripts, and the package by everything in it, so a change to the packaging takes minutes and a change to the app alone reuses the package. It downloads the server data and checks its SHA-256, tests the server on it, builds the Windows tools, runs the unit tests, prepares Winlator (cached by its commit and the patches), builds the APK and publishes the release with the Windows tools.

Local builds:

```sh
# The server, for this machine's architecture (arm64 on an arm64 machine), in Docker:
scripts/build-azerothcore.sh
# ...or after a change to the packaging only (ctl.sh, the shim, the SQL layout):
scripts/package-azerothcore.sh
scripts/test-warcraft-server.sh
# ...with the server data, all the way into the world (data.zip from server-data.env's URL):
WOW_DATA_ZIP=/path/to/data.zip scripts/test-warcraft-server.sh
# The game's runtime, once (and after a change to its commit or patches; needs Python's zstandard):
scripts/prepare-winlator.sh
# The app (copy server.tar.xz from a release build into warcraft/android/server/ to include it;
# needs the NDK 24.0.8215888 and CMake 3.22.1):
cd warcraft/android
./gradlew --init-script ../../scripts/maven-mirror.init.gradle assembleRelease \
  -PwowVersionCode=1 -PwowVersionName=1.0.1 -Pkeystore="$PWD/../../ci/dolphinxd-ci.p12" \
  -Pstorepass=dolphinxd -Pkeyalias=dolphinxd -Pkeypass=dolphinxd
# Unit tests:
gradle -p warcraft/unit-tests test
```

The app targets Android SDK 28, as Termux and Winlator do: Android lets apps that target 28 or lower run programs from their own storage, where the server and the game's Wine run.

## License

The app's code, and mod-wrath-remix, are GPLv3. AzerothCore and mod-playerbots are GPLv2; mod-progression-system, mod-zone-difficulty and mod-chromie-xp are AGPLv3; ChromieCraft's SmartStone and hoard collector modules are MIT (the talent template module's repository has no license file). MariaDB is GPLv2, musl MIT; the other libraries in the server package are under their Alpine packages' licenses. Winlator is LGPL-2.1; its runtime brings Wine (LGPL-2.1), Box64 (MIT), DXVK (zlib) and Mesa (MIT), under their own licenses. Cinzel and Jost are under the SIL Open Font License (`fonts/`). World of Warcraft, its art and its music belong to Blizzard Entertainment; the client is yours and isn't part of the app. The server data the app downloads is made from the game's files by the AzerothCore project and published by it. Playing on a private server is against Blizzard's terms of use. Wrath Remix's story and characters' words are new, after Blizzard's Legion Remix and its characters. This project isn't affiliated with Blizzard, ChromieCraft, AzerothCore, the playerbots project, MariaDB, or Winlator.
