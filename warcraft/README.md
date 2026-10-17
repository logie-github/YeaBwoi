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

While it starts, the app shows its own loading screen, until the game's window is up: the launcher's Lich King picture, darkened, with the logo and the snow over it.

### Touch controls

Nothing is drawn over the game, not even the mouse pointer: the screen itself is the controls, and your finger is the pointer. The pointer is still there for the game (it goes where you tap, and the game's cursor there tells the app what a tap does); it just isn't drawn. A mouse connected to the phone shows it again. Split like most phone MMOs: the left thumb walks, the right one looks. Each finger counts on its own, so you can walk and turn at once, and tap an ability while walking.

| Gesture | In the game |
| --- | --- |
| **Left half: swipe and hold** | Walk, as with a stick under your thumb (nothing is drawn): up W (forward), down S (back), left Q and right E (sideways steps), and the diagonals between. It follows a thumb that goes further, so changing direction is quick. Lift to stop. |
| **Right half: drag** | Turn the camera and your character (the right button held in the middle of the screen), so walking goes where you look |
| **On the spellbook: drag** | Drags the spell, as the mouse does on a PC: put your finger on a spell, move it onto an action bar button and lift. A tap there is a click (a tab, a page, a spell), a hold a right click. Only while the spellbook is open; elsewhere the halves walk and turn as usual. |
| **Character creation: swipe** (either half) | Turns your new character, as dragging it with the mouse does on a PC (the left button held on the middle of the screen, going sideways with your finger). On that screen a hold presses nothing, so no B or M lands in the name box; taps click as anywhere. |
| **Tap** (anywhere) | What a click does in WoW, by the game's own cursor where you tap: on the interface and the ground, a left click (buttons, menus); on an **enemy**, selects it as your target; on anyone or anything else the cursor offers something for, does it at once, as a right click does on a PC: a speech bubble talks to the NPC, a bag loots, a gear or flower uses or gathers. |
| **Double tap** | On an enemy: attack it. On the action bars (the bottom of the screen and its right edge, where the game's interface puts them): two presses, so you can tap a button as fast as you like. Anywhere else: jump (Space). |
| **Hold** one finger still | On an item, a talent or an ability (in your bags, the bank, the character pane, the talents, the spellbook or the action bars): a right click there, as on a PC (use or equip an item, sell it to a merchant you have open). On an action bar's button it takes the ability or item off the bar. Anywhere else: bags (B). While a cutscene plays (a new character's intro, a quest's cinematic or movie) it skips it instead (Escape). |
| **Tap, then tap and hold** | Map (M) |
| **Pinch** with two fingers | Zoom the camera: spread to zoom in, close to zoom out. Put both fingers down together (within about a third of a second) and move them apart or together. |
| **Two-finger tap** | Target the nearest enemy (the game's Tab; again for the next one) |
| **Two fingers swiped up** | Chat: opens it, with the keyboard |
| **Three-finger tap** | The keyboard, for any other text box (mail, auction house search, macros) |
| **Four-finger tap** | The game's menu: **Back to the game**, **Keyboard**, **Leave the game** (back to the launcher) |
| **Back** | Escape: closes a window, or opens the game menu |

How a tap reads the cursor: the app sees the game's cursor (WoW's hardware cursor, which the app turns on in `Config.wtf`) and learns the plain arrow at the login screen. When a tap lands on a special cursor, its left click selects what's there, and the realm on the phone says whether that's an enemy (the server writes it for the app; `patches/azerothcore/0007`): an enemy stays selected, anything else gets the right click too, when the same cursor is still there after the left click. So a tap that picks up an item, puts one down or aims a spell stays a single click. After the first enemy, the app knows the sword cursor and doesn't wait for the realm. If the game's cursor can't be seen (only one cursor ever shows), tapping the same NPC again within a second and a half talks to it.

How the app knows the character creation screen: once a second it looks at a small picture of the game's window (Winlator draws one, 256 pixels high) for the race list's two banners down the left, the Alliance's blue one with the Horde's red one beside it, where the 3.3.5a client puts them; the game's other screens don't have them there. An HD look or a client patch that redraws that screen's banners could hide it from the app; swipes there would then walk and turn the camera as elsewhere.

How a drag knows the spellbook, and a hold the windows: the app puts its own small add-on into your client before each start (`Interface/AddOns/WowTouch`, left out of the add-on manager's list). It tells the realm on the phone where the spellbook is while it's open, and where your open bags, the bank, the character pane, the talents and the action bars are, in an add-on message to your character; the realm keeps it for the app instead of passing it on (`patches/azerothcore/0009`). A finger that comes down on the spellbook is the mouse there: once it moves, the left button goes down where it came and stays down until it lifts. A hold on any of those windows is a right click; the add-on makes an action bar button's right click take its action off the bar, and nothing else (set out of combat, as the game requires; if you log in fighting, once the fight ends). Interface add-ons that replace the action bars (Bartender, Dominos) have their own buttons, which this doesn't change.

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
- Starting a build needs your own GitHub token: on github.com, **Settings → Developer settings → Personal access tokens → Fine-grained tokens**, with access to this repository only and the permission **Actions: read and write** (and **Issues: read and write** for Wrath Remix's Report Time Anomaly to send its reports itself). The app checks it with GitHub and keeps it in its own private storage; it's sent to GitHub alone. Browsing works without one (GitHub allows fewer searches a minute without).
- Modules are other people's code, written for AzerothCore, not always for ChromieCraft's copy of it or its playerbots: some don't compile with it (written for an older or newer AzerothCore, for example: the build leaves those out, builds the realm with the rest and says which and why, and the list marks them **doesn't compile with this realm**), some need a client patch or settings of their own. The build runs them with a read-only token; only its last step, which runs none of their code, can add the realm to the release.

## Wrath Remix

A second realm, apart from ChromieCraft: Wrath of the Lich King's story retold as Blizzard's Legion Remix retold Legion. Eternus of the Infinite Dragonflight pulls you out of your own time and into Northrend as the war against the Lich King begins: you're a **Timerunner**, weaker for the journey, and he wants your help to make the war kinder than it was. Moratari, his lieutenant, is your guide. Then, after the first chapter, Sylvanas and Alleria Windrunner come back from the future to stop you. The whole story, quest by quest, is in [`modules/mod-wrath-remix/STORY.md`](modules/mod-wrath-remix/STORY.md). Everything in it is its own: characters, bags, gold, the world. Nothing you do there changes ChromieCraft, and nothing in ChromieCraft changes it.

**Choosing it:** the **Realm** tile, **Wrath Remix**, then **Press start**. The realm you choose is the one that starts (and the one the game logs in to); choosing the other one while a realm runs stops the server first. The first start of Wrath Remix makes its databases (a few minutes, once, and about 1 GB more of the phone's storage). Your accounts work on both; your characters are per realm.

**What it is now** (the guide's story, `REMIX-GUIDE.md`, from the prologue to the end; Infinite Power and the rest follow):

- **Timerunners start at level 10** at the Infinite Bazaar, in Crystalsong Forest, where Moratari and Eternus are waiting (death knights start at Acherus as usual, and Moratari brings them to the Bazaar when the Ebon Blade lets them go). No race's intro plays. Quests give three times the usual experience and kills twice.
- **Ready for level 10.** A Timerunner arrives with gear for their class (death knights keep Acherus' own): heirloom shoulders, chest, weapon (a rogue's two daggers, a hunter's bow and sword), trinket and ring, which grow with you to 80, and greens for legs, hands, feet, waist and back, all put on in place of the starting clothes (with the shaman's four totems in the bags). Every ability your class trainer teaches at your level is learned for you, free, at login and at every level, with those the class quests teach (Defensive Stance, the hunter's Tame Beast and pet spells, the warlock's demons, Bear Form, Redemption, Poisons...). Weapon and defense skills stay at your level's maximum. A Timerunner made before this gets the kit at their next login (into the bags where they've something better on, by mail if the bags are full).
- **The prologue**, at the Bazaar, each quest given where the last one ended: *Out of Time* (Moratari tells you where and when you are, and why you're weaker), *What the War Will Cost* (Eternus shows you three of the war's dead: a footman who rises for the Scourge, Dranosh Saurfang and Bolvar Fordragon at the Wrathgate; then his offer), *Time's Toll* (Bronze, Alurmi's heirlooms and your class's instructor; your hearthstone remembers the Bazaar, and Moratari tells you what her hourglass does), *A Small Demonstration* (Moratari lets three temporal anomalies through, one at a time: kill them), *To the Front* (Eternus sends you to the Borean Tundra: Valiance Keep or Warsong Hold, before the officer who enlists you).
- **Moratari's Hourglass** is in your bags from the moment you wake at the Bazaar (a death knight's from the moment Moratari brings them there). Use it anywhere and it takes you to the Infinite Bazaar; use it at the Bazaar and it takes you back to where you came from, however you came (the hourglass, your hearthstone, one of the story's quests). No cooldown; not in a fight, while dead, on a flight or in a battleground. If it's lost, it comes back at your next login or level (into the bags: make room if they're full). It stays at the Bazaar when you go home.
- **Chapter I** is the Borean Tundra for everyone, guided all the way: where the game leaves you to find the next quest giver yourself, Moratari sends you. Midway, Eternus's first "kindness": save a squad history lost (the crypt crawlers on Valiance Keep's beach, or the Nerub'ar at Mightstone Quarry). Howling Fjord is still there for whoever wants it.
- **Eternus at work.** Until the story turns, watch the quests: Eternus is quietly changing small things in some of them, and he will tell you when he has.
- **Chapter II** is the Dragonblight, and the chapter the story turns on, guided the same way: Chromie at Wyrmrest Temple sends for you by name (her own Mystery of the Infinite comes first), and Sylvanas and Alleria Windrunner, back from the future, tell you to stop (anywhere but the Bazaar they go hooded, as the **Hooded Ranger** and the **Cloaked Ranger**, two adventurers like any other: this year's Sylvanas is at war in the same Northrend and must never learn there's another one of her, and they keep well away from her); Eternus answers; right before the Wrathgate's chain he tells you his whole plan, and at the Bazaar the turn plays out as a scene. After it Eternus is gone from the Bazaar and Sylvanas and Alleria are there, and the next quest is a fight against his loyalists above the Wrathgate, so that it happens as it did. The Wrathgate and the Battle for the Undercity are played as the game has them.
- **Chapters III to VII, after the turn.** Eternus isn't guiding you any more; he keeps trying to save the war's dead, a quieter grave each time, and each chapter has one of those attempts at its heart: his loyal agents where he means to change things, and you, with Moratari, Sylvanas and Alleria, stopping them. Then history goes on as it did, and the game's own story with it (Drakuru's betrayal, Freya's Waygate, Thorim and Loken, the Argent Crusade, the Frozen Halls).
  - **Chapter III, the Grizzly Hills** (Alleria): *The Hollow Crown*, by Hierophant Thayreen or Windseer Grayhorn after the Ursoc chain's middle: three Temporal Anchors around Ursoc's Den; *A Shorter Path Through the Woods* (to Drakuru's cages); *A Friend of Questionable Judgment* at Drak'Tharon's door, where Sylvanas is waiting; *The Truth About Drakuru* (to Zul'Drak).
  - **Chapter IV, Zul'Drak** (Sylvanas): *The Things the Scourge Remembers*, *The Paperwork of Betrayal*, *A Pilgrim's Shortcut*; *One Life Too Many*, by Stefan Vadu: Eternus's timesnatchers on Voltarus opening a rift to carry Drakuru out of history before Betrayal; *The Gods Are Still Silent*; *A Kingdom Gone* (to Sholazar Basin).
  - **Chapter V, Sholazar Basin** (Moratari): *A Brief Hunting Expedition* (to Pilot Vic and the Avatar of Freya); *Stolen Hours* at the Maker's Overlook: six Infinite Siphoners and their Siphon on the Makers' pillar, and Eternus comes for Moratari himself; *The Makers' Trail* (to K3 in the Storm Peaks).
  - **Chapter VI, the Storm Peaks** (Eternus): *The Very Long Road to Thorim* (from Lok'lira to the Drakkensryd); *A Gift of Grief* on the Terrace of the Makers: four Infinite Weavers making an echo of Sif, and Eternus offering Thorim his wife back; *Storm's End*, by King Jokkum (to Tirion at the Argent Vanguard).
  - **Chapter VII, Icecrown** (all of them): *What He Was Offered*, at Crusaders' Pinnacle for those who walked Crusader Bridenbrad's road; *The Armies Are in Place* (to the Bazaar, where Sylvanas and Alleria see you off to the Frozen Halls, then to Dalaran and Inside the Frozen Citadel); the Forge of Souls, the Pit of Saron and the Halls of Reflection as the game has them; *The Last Hour*, outside the Frozen Halls: at the doors of Icecrown Citadel, Eternus and every Infinite still loyal to him, against you, Moratari, Sylvanas and Alleria (if it goes wrong, Moratari takes you back to the doors); *The Last Necessary Battle*: Icecrown Citadel and the Lich King, untouched, and the ending cinematic.
- **The end.** When the Lich King's cinematic is over (or seven minutes after he falls, if it doesn't play), you're brought back to the Infinite Bazaar, where everyone is waiting, and a letter from Eternus arrives. *See You Later*: say goodbye to Sylvanas, Alleria, Andormu and Nozari; until you go, Sylvanas and Alleria will talk with you about what comes after this war (Cataclysm, Pandaria, Draenor, Legion and the rest, one conversation for each, in their own words). *Back to Your Own Time*: Moratari sends Sylvanas and Alleria home first, then you. **Home** is the ChromieCraft realm, as Legion Remix sends its Timerunners home: the game closes, the app starts the server again on ChromieCraft, and the character is there, at the level they reached with everything they carry (Bronze and Moratari's Hourglass stay behind), in Stormwind or Orgrimmar. The character leaves Wrath Remix (if the way home is interrupted, the server stopping on the way, it finishes at your next login there). `.remix return` (a game master's command) brings you back to the Bazaar if the ending's return doesn't.
- **The scenes**: the story is told in scenes as quests are taken and turned in (each written out in `modules/mod-wrath-remix/STORY.md`): the people in them appear where they stand, move and speak, and only you see and hear them. A scene missed (you walked away, the server stopped) plays again when you speak to the one who gives the quest.
- **Northrend at your level.** Each chapter's zone and dungeons are moved to its levels: Borean Tundra and Howling Fjord 10 to 20, Dragonblight 20 to 32, Grizzly Hills 32 to 42, Zul'Drak 42 to 52, Sholazar Basin 52 to 60, the Storm Peaks 60 to 70, Icecrown 70 to 80 (Crystalsong Forest 60 to 70; the Battle for the Undercity at Chapter II's end). Creatures get the level, health and damage of their chapter, and their spells hit and heal for the same share; then a stat squish takes them to three quarters of that health and damage (`WrathRemix.Squish.Health` and `.Damage` in `files/etc-remix/modules/mod_wrath_remix.conf`, in percent). The quests are at those levels too, and their armor and weapons rewards shrink with them (a Borean Tundra reward is about a quarter of what it is at 71), as does their gold. Dungeons open at their chapter's levels on normal (Utgarde Keep and the Nexus at 13, Halls of Reflection at 76); heroic is still for 80, as are the raids.
- **Moratari's transitions**, where the story has an afternoon of errands: she stands by the quest giver, and her quest takes the place of the block (until the turn, they're Eternus's: he is folding the war to where he means to change it). In Chapter I: Leryssa's search for Thassarian (Alliance), the parts for Mobu's tank (Horde), Westguard Keep's missions before Operation: Skornful Wrath (Alliance, Howling Fjord), and *Into the Dragonblight* at the end of each coast, which takes you to Wintergarde Keep or Agmar's Hammer. In Chapter II: *The Road to Angrathar*, after Find Durkon! (Alliance: Wintergarde's crypt, tome, sermon, mine and demo-gnome, to Into Hostile Territory) or The Forsaken Blight (Horde: the taunka's oath, the frost wyrms and the Icemist chief, to The Kor'kron Vanguard!); *What We Cannot Change*, by Alexstrasza after the Wrathgate (it skips nothing: the Wrathgate and the Battle for the Undercity are played as the game has them); and *After Angrathar*, after the Battle for the Undercity, by King Varian Wrynn or by Thrall in Lordaeron's throne room, which takes you to the Grizzly Hills (Amberpine Lodge or Conquest Hold) and Chapter III. Chapters III to VII have theirs too (below). You can still do the skipped quests: the next one opens either way.
- **The Infinite Bazaar**, on the ground of Crystalsong Forest by the Violet Stand (the Kirin Tor's teleporter there goes up to Dalaran). Your hearthstone takes you home to it after the prologue. There: **Eternus**, **Moratari** (where you are in history; "the record became tangled" takes you back to the start of your chapter), **Alurmi** and the Bronze vendors (below), **Zephyr** (travel: Dalaran, the landings, each chapter's camp as your level reaches it), **Sa'at** (the chapters and dungeons), the **Chronologist** (your chapter; go to the start of any chapter your level allows), **Momentus** (Infinite Power, coming), and a **Timeworn Instructor** for every class (a capital's trainer: you know what they teach already, but they're there).
- **Bronze**, a lot of it, as in Legion Remix: meant to be enough over the story to buy everything the vendors below sell for your class, about twice over, by the time the last chapter is done (worked out from the vendors' prices and the story's quests and kills, not counted in a real playthrough). Every quest gives 4,000 (the story's own quests at least that, the late chapters' a lot more: 25,000 for *The Last Hour*, 50,000 for *The Last Necessary Battle*), kills 75 (elites 300, rares 3,000, dungeon bosses 6,000, raid bosses 30,000; summoned creatures give none), the first loot of a treasure chest, strongbox or cache out in the world 1,500, and every achievement 100 a point (the world events' included). The prologue gives 8,500, and the Level 10 achievement you have from the start 1,000, enough to shop at the Bazaar before you leave it. It's a currency, in the character pane's **Currency** tab under Wrath of the Lich King. (The realm's settings take these amounts unless you changed them, `WrathRemix.Bronze.*` in `files/etc-remix/modules/mod_wrath_remix.conf`.)
- **Dungeons and raids for one.** A dungeon or raid scales to the players in it (your bots count): alone in a five-player dungeon you take about two fifths of a group's damage and deal about four times as much; in a raid you take at most 15% (`WrathRemix.Solo.*`). The creatures' health is unchanged, so every boss's phases happen as written. In Icecrown Citadel with less than a third of a group, the three things only a group can do are done for you: Blood-Queen Lana'thel's bite passes, Sindragosa's Ice Tombs break by themselves, and Valithria Dreamwalker mends a little every second. Timed dungeons aren't part of it.
- **The Bronze vendors**, Legion Remix's Bazaar for Wrath: talk to one, pick a list, and its window opens with prices in Bronze. Each list shows you only what your class and side can use.
  - **Alurmi**: every heirloom (shoulders 300, chests and weapons 500, trinkets and the ring 400) and Frostweave Bags.
  - **Horos**, rare collections: Wrath's mounts, 109 of them: the raids' and dungeons' drakes and proto-drakes (Azure, Blue, Black, Twilight, Bronze, Onyxian, the Frostbrood Vanquishers, Mimiron's Head, Invincible), the achievement and reputation ones, the mammoths and bears, the Argent Tournament's, the Gladiators' frost wyrms and more, 1,000 to 20,000.
  - **Domelius**: Wrath's companions (65) and toys (24), 250 to 1,000.
  - **Arturos**: Northrend's dungeons' gear, normal and heroic, the Trial of the Champion's and the Frozen Halls'.
  - **Aeonicus**: the 10-player raids: every piece of Tier 7 to 10 for your class, and each raid's loot (Naxxramas, the Obsidian Sanctum, Vault of Archavon, Ulduar, Onyxia, the Trial of the Crusader, Icecrown Citadel and the Ruby Sanctum, heroic ones too).
  - **Durus**: the same for 25 players and the heroics, and the two legendaries, Val'anyr (20,000) and Shadowmourne (25,000).

  Gear costs more the higher its item level (200 Bronze at item level 200, about 3% more a level; weapons and trinkets a little more). The full lists and prices are in `modules/mod-wrath-remix/data/vendor_catalog.md`. The game shows Bronze and its prices from a patch the realm puts in your client's `Data` folder (`patch-Y.MPQ`) as Wrath Remix starts; ChromieCraft takes it out again. If you have a `patch-Y.MPQ` of your own there, it's left alone, and the vendor windows won't show Bronze prices (buying still works, and Bronze is shown when you talk to a vendor).
- **Riding** is taught for free at 20, 40, 60 and 70, and Cold Weather Flying at 77.
- **The realm's bots** live in Northrend: each one that turns up at the Infinite Bazaar (where every character is made) is sent to the start of its level's chapter, and makes its home there, so the prologue is yours alone. A bot in your group follows you anywhere.
- **Report Time Anomaly**, in the game's menu (Esc, under Macros), for when something in Wrath Remix is wrong: a quest that won't start or finish, someone who isn't where the story says, a transition that goes nowhere. It asks what went wrong (you can leave it empty; to write, tap the box, then three fingers for the keyboard); tap **Report**. The realm writes down what led up to it: where you are, the quest you're on and how far along its objectives are, who gave it to you and who you've spoken to since, the rest of your quest log, and your way through the story (the quests you took, finished, turned in and dropped, who you spoke to, the zones you entered, your levels and deaths: its last 300 steps). The app sends that to this app's repository on GitHub as an issue labeled *time anomaly*, without showing anything, if it has a token that may open issues there: yours (the **Modules** token, with **Issues: read and write** added) or the one the app was built with (`DOLPHINXD_UPDATE_TOKEN`, if it has that permission). Otherwise GitHub's new issue page opens in your browser, filled in, for you to send (a long report is shortened there, its oldest steps first). With no internet it waits, and goes once the phone is online. A note at the bottom of the screen says how it went. The reports stay on the phone in `files/run/reports` (`sent/`, `opened/`). The way through the story is noted from this version on.
- Its settings are its own (`files/etc-remix`): its bots, rates and options don't follow ChromieCraft's, and ChromieCraft's progression brackets don't apply (all of Wrath is open). Modules you build into the realm are in both.

Not built yet: Infinite Power, the Keepers' world assignments, Heroic World Tier, timed dungeons. The client's quest cache is cleared when you switch realms (Remix gives some quests and items other levels).

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
| **Turtle WoW — Vanilla+** | Switches the launcher to the app's second game, Turtle WoW (see **Turtle WoW**). So does the chip at the top left of the picture. |

While the server runs, Android shows a notification for it, with **Stop**. While the game is on screen the notification goes away (the game in front keeps the app running); it comes back when you leave the game, switch to another app or turn the screen off, so the server keeps going.

## The server

ChromieCraft runs a progressive realm: content opens bracket by bracket, as the original game did. The server starts the way ChromieCraft did, with the first brackets (`Bracket_0` and `Bracket_1_19`) on, at blizzlike rates. The playerbots module starts with 50 random bots (its own default is 500, too many for a phone), which only run while someone is logged in. Changing brackets, rates, realm options and bots in the launcher comes in stage 3; until then they're in the configs (`files/etc/worldserver.conf`, `files/etc/modules/*.conf`), and a game master can use the GM commands in the game's chat (`.help`).

Modules: [mod-playerbots](https://github.com/mod-playerbots/mod-playerbots), [mod-progression-system](https://github.com/azerothcore/mod-progression-system), [mod-zone-difficulty](https://github.com/azerothcore/mod-zone-difficulty), [mod-chromie-xp](https://github.com/azerothcore/mod-chromie-xp), and from ChromieCraft's own: [mod-chromiecraft-smartstone](https://github.com/chromiecraft/mod-chromiecraft-smartstone), [mod-hoard-collector](https://github.com/chromiecraft/mod-hoard-collector), [mod-npc-talent-template](https://github.com/chromiecraft/mod-npc-talent-template). ChromieCraft's staff-only modules (GM command presets, contributor bonuses, support tools, the test realm's changes) are left out.

How it runs: MariaDB listens on 127.0.0.1 only (port 3310); the login server on port 3724 and the world server on 8085 (Wrath Remix's on 8086), reachable from the Wi-Fi. Wrath Remix's configs are `files/etc-remix/worldserver.conf` and `files/etc-remix/modules/*.conf`. The realm list gives the phone's own client 127.0.0.1 and a PC the phone's Wi-Fi address.

If Android stops the server with the screen off: in **Server**, **Allow** the app to run without battery restrictions. On Android 14 and later, Developer options also have **Disable child process restrictions**, for phones that still stop it.

The server only runs for the phone's main user (its programs are built for the app's folder there), not in a second user or a work profile.

## Logging in by itself

The game logs in by itself with the account set to **Log in automatically** (in **Accounts**, or by keeping **Log in automatically next time** on in the game's password prompt). Its password is kept in the app's own storage on the phone, and nowhere else: not in the app's code, not on GitHub. The app checks it against the realm's account before keeping it, and puts that account's name in the login box. At the login screen it waits for the game's cursor to show (the screen is ready), then types the password and presses Enter. **Stop logging in automatically** (in **Accounts**, on that account) forgets it.

## Turtle WoW — Vanilla+

The app's second game: **Turtle WoW** (the fan-made "Vanilla+" World of Warcraft 1.12 continuation), played on the phone against its own realm on the phone, completely apart from the 3.3.5a game. The chip at the top left of the launcher's picture (or the **Turtle WoW — Vanilla+** tile) switches the launcher to it, and it turns into Turtle WoW's: the old map of Kalimdor and the Eastern Kingdoms with motes drifting up, Turtle WoW's logo, in turtlecraft.gg's dark warm brown and gold-to-coral, with its own music: Moonwhisper Coast, from Turtle WoW's own soundtrack (the note turns it off and on, as in the 3.3.5a launcher; switching games fades one theme into the other). The chip there (**Wrath**) switches back. Switching changes nothing of either game; if the other game's realm is running, the app asks whether to stop it first (both realms use the game's login port, so one runs at a time).

| | |
| --- | --- |
| Realm | [Tortoise-WoW](https://github.com/tortoise-wow/tortoise-wow), the community restoration of Turtle WoW's 1.18.1 server, built for the phone's arm64 with its own MariaDB (`turtle/server-version.env`) |
| Client | Yours: Turtle WoW **1.18.1 (build 7272)** with its 2026-04-12 hotfixes, Turtle WoW's last. Never part of the app, never downloaded by it. |
| Bots | [TortoiseBots](https://github.com/Sagiroth/TortoiseBots) by Sagiroth (v1.0.0), compiled into the realm: bots living in the world, companions to hire, your own alts as bots, controlled with its addon, TortoiseBotsManager (`/tbm`), which the app puts in your client |

### Installing

1. **Press start** downloads Turtle WoW's realm (one package, about the size the screen says: Tortoise-WoW with TortoiseBots, its database engine and libraries), checks it against the SHA-256 this version of the app was built with, installs it in Turtle's own folder and creates its databases. Then make an account under **Accounts** (the first account is the realm's administrator and logs in by itself).
2. **Add your Turtle WoW client** under **Game files**: **Pick folder** (the folder with `WoW.exe` and `Data` in it) or **Pick zip**. The app copies it (about 9 GB) and checks every file against Turtle WoW's final 1.18.1 checksums as it copies. A client that differs is taken with a warning. Then it makes the server's data from it by itself: the DBC tables and maps (minutes) and vmaps (minutes more), checked against Tortoise-WoW's checksums. Turn on **Include mmaps** there for pathfinding (hours on a phone), or make it on a computer instead (below).
3. **Press start** again: the realm starts (its database, login server and world server; the first start applies Tortoise-WoW's database updates, a few minutes more), the game's Windows is set up the first time (Turtle's own), the client is pointed at the realm on the phone, and the game starts at its login screen.

**Pathfinding on a computer (far faster):** on any computer with Docker (Linux, or Docker Desktop on a Mac or Windows) and a copy of your Turtle WoW client, run `scripts/make-turtle-server-data.sh /path/to/TurtleWoW` from this repository. It builds Tortoise-WoW's map tools from the same commit as the phone's realm (once: minutes to an hour, depending on the computer) and makes `build/turtle-server-data.zip`: dbc, maps, vmaps and mmaps, the pathfinding on every core of the computer. `scripts/make-turtle-server-data.sh /path/to/TurtleWoW mmaps` makes a zip of pathfinding alone, to add to the data the phone made. Copy the zip to the phone, then **Game files → Import zip**. The app takes only the four folders, checks every map, vmap and pathfinding file is this realm's format (anything else is refused before a file is replaced) and the DBC files against Turtle's checksums, and puts in the folders the zip has (the others stay). Nobody publishes Turtle's server data ready-made, as AzerothCore does for 3.3.5a: it can only be made from your own client.

**Why the client isn't downloaded:** Turtle WoW was shut down in 2026 after Blizzard won its lawsuit; the court's order also bars distributing Turtle's client. Copy your own onto the phone first (files in cloud folders can't be read this way).

Each part also has its own line, how it is and a button that does just that part: under **Server**, **Realm programs** (install, install again), **Databases** (create), **Windows runtime** (shared with the 3.3.5a game: program files only); under **Game files**, **Your client** (pick folder, pick zip, check files) and **Server data** (dbc, maps, vmaps, and mmaps if you want them; make again).

Everything carries on with the app in the background, with a notification and **Cancel**. A dropped download carries on from where it got to; a download that isn't the published file is deleted, never used. A step that stops leaves what was there before it, and pressing it again carries on.

### The game

Played like the 3.3.5a game (the same touch controls and keyboard prompt), with three differences, because Tortoise-WoW's realm doesn't tell the app what a tap selected, when a cutscene plays or where the spellbook is (those are the ChromieCraft realm's own patches): a tap on anything the game's cursor offers something for does it at once, as a right click does (talks, loots, gathers, and on an enemy, attacks it); a hold always opens the bags (Back is Escape, to skip a cutscene); and spells are dragged off the spellbook with the mouse only. It logs in by itself with Turtle's own saved login (never ChromieCraft's). While it starts, its loading screen is Turtle WoW's: the map of Azeroth filling the screen (its sides past the edges), Turtle WoW's logo and the launcher's motes drifting up.

### Bots

Turtle WoW's realm has [TortoiseBots](https://github.com/Sagiroth/TortoiseBots) by Sagiroth in it, made for one player on their own realm: the world fills with bots that play the way players do, and your group with bots who tank, heal and fight.

- **Bots in the world** start at level 1 in their starting zones and quest, grind, level, travel by foot and flight path, train, gather and craft, and spread across the world as they level. The first time the realm runs it makes them a few at a time. How many: **Bots & rates → Bots in the world** (none, 20, 40, 80 or 150; 40 at first). TortoiseBots' own default is 500, sized for a PC: more bots make the world livelier and the phone busier and warmer.
- **Your own party:** hire a companion from an inn's recruiter (a fresh character at your level), or make alts on your account and bring them in as bots. They follow you, tank, heal, deal damage, interrupt, dispel and loot, run dungeons and raids with you, and fill missing roles when you queue for the dungeon finder or a battleground.
- **Controls:** type `/tbm` in the game for TortoiseBotsManager, the bots' control panel (roster, log in and invite, party orders such as focus skull, pull back, stay, crowd control marks, and each bot's gear and behaviour). The app puts it in your client's `Interface/AddOns` each time you press start. Chat commands work too: `.bot add <name>`, `.bot invite <name>`, `.bot summon <name>`, `.bot role <name> tank|healer|dps`. TortoiseBots' guides: [getting started](https://github.com/Sagiroth/TortoiseBots/blob/main/docs/guides/getting-started.md), [player controls](https://github.com/Sagiroth/TortoiseBots/blob/main/docs/guides/player-controls.md).

What CI checks: the realm builds with it, its database updates go in with Tortoise-WoW's, and the realm starts with it as far as CI can take it (no game data in CI). How the bots play can only be seen on the phone.

### Its menu

The same tiles as the 3.3.5a launcher, in the same places, each for Turtle WoW's own things (Bots & rates where the 3.3.5a launcher has Modules):

| Tile | What it does |
| --- | --- |
| **Server** | How the realm is, **Start** and **Stop**; each part of it (realm programs, databases, Windows runtime) with a button to put it in again; **Let PCs on this Wi-Fi in** (off: the realm answers this phone only, offline); its logs (world, login, database, server data); battery; backups of the accounts and characters (**Back up now**, the last six, made by themselves before an update or a reset, and **Restore**); **Repair** (pick what to put back or check: the realm's programs, its configs, your client's files, the server's data, or new databases); **Uninstall** (type TURTLE to confirm: removes all of Turtle WoW, its realm, databases, copied client, data, Windows, logs, backups and settings, nothing else); and what this is and where it comes from. |
| **Game files** | Your Turtle WoW client (pick folder, pick zip, check its files against 1.18.1's checksums) and the server's data made from it (dbc, maps, vmaps; mmaps if you want pathfinding), on the phone or imported from a computer (**Import zip**). Making the data again without mmaps keeps the pathfinding already there. |
| **Accounts** | Turtle WoW's own accounts: make them, change passwords, ranks (player, moderator, developer, administrator), delete; stop logging in automatically. Its database starts by itself for this when the realm is stopped. |
| **Graphics** | The game's resolution, as in the 3.3.5a launcher (the same setting for both games). |
| **Add-ons** | The same add-on manager as the 3.3.5a launcher's (see **Add-ons**), for 1.12 add-ons from [warperia.com's Vanilla list](https://warperia.com/vanilla-addons/) and Turtle's own client: browse and search, install in one tap or open an add-on's page; what's installed, with updates, on/off and delete; import zips. The bots' TortoiseBotsManager comes with the realm, not from here. |
| **Bots & rates** | XP from kills, quests and exploring, money, reputation, rest, new characters' level; pathfinding; how many bots live in the world. Taken at the realm's next start. |
| **App icon** | The app's icon, as in the 3.3.5a launcher. |
| **Updates** | The app's updates. A newer realm comes with an app update, never one that doesn't fit; updating keeps accounts and characters (backed up first). |
| **Wrath of the Lich King** | Back to the 3.3.5a game and its ChromieCraft realm (as the chip at the top left). |

### Kept apart from the 3.3.5a game

Everything of Turtle WoW is in `files/turtle` (its own `server`, `etc`, `mysql`, `data`, `client`, `logs`, `run`, `downloads`, `backups`), with its own MariaDB on port 3311 (user and databases `tw_*`), its world server on 8090, its own Windows (the "Turtle WoW" container), saved login, settings, notification and service. Every delete and move Turtle's code makes goes through one check that refuses anything outside `files/turtle` or in the 3.3.5a game's folders and never follows a link; the unit tests also read the app's own sources to make sure of it. The two realms' login servers share port 3724, so only one runs at a time.

### How it's built

| Path | Contents |
| --- | --- |
| `turtle/server-version.env` | Tortoise-WoW's commit, ACE's release (with its SHA-256), the client build, TortoiseBots' release and its addon's commit |
| `turtle/patches/` | Tortoise-WoW on musl: two G3D fixes and a comparison with `NULL`; and the folder the world server reads modules' database updates from, set at build time (the package's, on the phone) |
| `turtle/server/ctl.sh`, `seed.sql` | Turtle's realm on the phone (refuses any folder but `files/turtle`; Tortoise's anticheat off, OpenSSL pointed at the package's legacy module), and its database seed |
| `../scripts/fetch-tortoise.sh`, `compile-tortoise.sh`, `package-tortoise.sh` | Checkout, compile (ACE too) and package, in Alpine; `-march=native` replaced by a baseline for phones |
| `../scripts/test-turtle-server.sh`, `turtle/server/auth_check.py` | CI's test of the package: its MariaDB, a 1.18.1 (7272) login with the realm list, the world server's database updates, a pathfinding tile built and taken as a success, a backup and restore, the system calls |
| `../scripts/make-turtle-server-data.sh` | Turtle's server data (pathfinding too) made on a computer with Docker from the user's client, for **Import zip** |
| `../scripts/turtle-manifests.py`, `android/app/src/main/assets/turtle/` | The client's and the DBC files' checksums, from Tortoise-WoW's `tools/dbc_verification` |
| `android/app/.../turtle/` | Turtle's code: `core/` (the folder guard, manifests, downloads, accounts, unit-tested), the realm, the installer, the game, `ui/` (its launcher) |
| `branding/turtle/` | Your art: the map header, the logo and the loading screen's map (`loading.png`, used as `res/drawable-nodpi/turtle_loading.jpg`) |
| `TURTLE.md` | The brief: what was asked, found and decided |

CI builds the realm once for each version of its inputs and publishes it as its own release (`turtle-server-<hash>`, a prerelease); each APK names the one it downloads. Local builds of the app have none (the launcher says so): `scripts/compile-tortoise.sh && scripts/package-tortoise.sh && scripts/test-turtle-server.sh` build and test one.

Tortoise-WoW is GPLv2 (from MaNGOS, VMaNGOS and AzerothCore); TortoiseBots combines original code with ported playerbots code under GPL-2.0 and AGPL-3.0 (its `LICENCE.md`), and its addon TortoiseBotsManager is MIT; ACE is under its own open license; Alegreya Sans SC (Turtle's titles) is SIL OFL (`fonts/`). Turtle WoW's name, logo, art and music, and World of Warcraft's music, belong to their owners and are used here only as the launcher's look and sound for the user's own install. Not affiliated with Turtle WoW, the Tortoise-WoW project, TortoiseBots or Blizzard.

## Saves

Accounts, characters and the world are in MariaDB's databases in the app's storage (`files/mysql`). They stay through updates of the app; the server applies the new version's database updates when it starts. **Delete the databases** (in **Server**) starts over with new ones. Uninstalling the app deletes everything, your client copy included.

## Updates

Like the other apps: the app checks this repository's releases (`wow-build-N`) when it opens and from **Updates**, stops the server, and installs over itself. A private repository needs the `DOLPHINXD_UPDATE_TOKEN` secret (see the main README). Given **Issues: read and write** as well, that token also sends Wrath Remix's time anomaly reports for every copy of the app, without a token of the player's own. It's signed with the same key as the other apps.

**A build made outside CI** (when GitHub won't run Actions, say): build it with the release's flags (`-PwowVersionCode` above the installed release, `-PturtleServer*` from a published `turtle-server-*` release's `turtle-server.json`) and the CI key in `ci/`, and it installs over the release, keeping everything. Without `server.tar.xz` in it, it runs the 3.3.5a server already unpacked from the release before (the same package unless the server changed). Without CI's token built in, it reads this repository's releases (updates, Turtle's realm) with your own token: **Modules → Your realm → Add your token**, a fine-grained token for this repository with Contents and Actions read.

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
| `../scripts/test-warcraft-server.sh`, `server/auth_check.py` | CI's test of the package, unpacked where the phone puts it: MariaDB, the databases, a 3.3.5a login with the realm list, the world server started on the downloaded data, a character created and taken into the world by a 3.3.5a world client written in Python, a GM command, the app's add-on message kept for the app, and the shutdown the app does; then what earlier versions' custom races left taken out (the world database made again, their files gone, a character of one of them left out of the character list but kept), and a module taken out of the realm taking what it put in the world database with it (`ctl.sh modules`); then Wrath Remix: its databases, its world server with the module on (off in ChromieCraft's), both realms in the realm list, a level 10 Timerunner who plays the prologue to the first chapter (`server/remix_prologue.steps`, with a heirloom bought for Bronze), Chapters II to VII and the end on each side (`server/remix_campaign_*.steps`: the story's quests and scenes, Eternus's last hour, the return to the Bazaar, the goodbyes, and the way home: both Timerunners leave Wrath Remix and ChromieCraft's world server takes them in, in their capitals), the floor under every NPC it placed (`server/remix_check.py`), and the realm's bots out of the prologue |
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
| `android/app/src/main/res/raw/felsong_title_music.ogg` | The Felsong launcher's music: your MP3 of Kingdoms Will Burn (Legion's soundtrack), made the same way, played at its ReplayGain (−6.5 dB) (13 MB) |
| `android/app/src/main/res/raw/turtle_title_music.ogg` | The Turtle WoW launcher's music: your MP3 of Moonwhisper Coast (Turtle WoW's soundtrack), made the same way, played at its ReplayGain (−2.5 dB) (2.6 MB) |
| `fonts/` | Licenses of Cinzel, Jost, Alegreya Sans SC and Marcellus (SIL OFL) |

CI (`.github/workflows/build-warcraft.yml`) builds the server on GitHub's arm64 runners: the compile is cached by the pins, the patches and the compile scripts, and the package by everything in it, so a change to the packaging takes minutes and a change to the app alone reuses the package. It downloads the server data and checks its SHA-256, tests the server on it (once for each package, server data and test script: a build of the app alone uses the earlier run's result), builds the Windows tools, runs the unit tests, prepares Winlator (cached by its commit and the patches), builds the APK and publishes the release with the Windows tools.

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

## Felsong — Legion 7.3.5 (not playable yet)

The launcher's **Games** chip (or the **Other games** tile) now lists the other games: Wrath of the Lich King, Turtle WoW and **Felsong**. Felsong's launcher is in its own look (felsong.gg's fel green on dark olive, Illidan over the Broken Isles, its logo, fel motes, Kingdoms Will Burn as its music) and says plainly where it stands: **there is no Play yet**. Its realm (LegionCore 7.3.5) is being ported to the phone and the Legion client hasn't been shown to run on Android (Box64 issue #3061). Choosing it changes nothing of the other games. The plan and findings: `FELSONG.md`.

## License

The app's code, and mod-wrath-remix, are GPLv3. AzerothCore and mod-playerbots are GPLv2; mod-progression-system, mod-zone-difficulty and mod-chromie-xp are AGPLv3; ChromieCraft's SmartStone and hoard collector modules are MIT (the talent template module's repository has no license file). MariaDB is GPLv2, musl MIT; the other libraries in the server package are under their Alpine packages' licenses. Winlator is LGPL-2.1; its runtime brings Wine (LGPL-2.1), Box64 (MIT), DXVK (zlib) and Mesa (MIT), under their own licenses. Cinzel, Jost, Alegreya Sans SC and Marcellus are under the SIL Open Font License (`fonts/`). World of Warcraft, its art and its music belong to Blizzard Entertainment; the client is yours and isn't part of the app. The server data the app downloads is made from the game's files by the AzerothCore project and published by it. Playing on a private server is against Blizzard's terms of use. Wrath Remix's story and characters' words are new, after Blizzard's Legion Remix and its characters. This project isn't affiliated with Blizzard, ChromieCraft, AzerothCore, the playerbots project, MariaDB, or Winlator.
