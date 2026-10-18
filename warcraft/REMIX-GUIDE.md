# WRATH REMIX: THE WEIGHT OF TIME

**Master design and phased development guide — Version 1.0**  
**Target:** AzerothCore · ChromieCraft-compatible 3.3.5a client · dedicated *Wrath Remix* realm  
**Status:** Design specification, not an already-implemented module. Quest names are candidate anchors pending validation against the actual installed AzerothCore world database.

> **Primary compatibility requirement:** Wrath Remix **MUST work with the user's already-established AzerothCore server, its existing settings, modules, database revisions, and ChromieCraft-compatible 3.3.5a client.** It must run as a **separate realm/game mode**. The existing realm must retain its current configuration, characters, gameplay, world data, and progression unchanged. Do not silently replace existing configuration values, rewrite the normal realm's quests, force client upgrades, or assume the installation is an unmodified stock AzerothCore checkout.

## Executive design decisions

| Category | Design decision |
|---|---|
| Product | A permanent, optional *Wrath Remix* realm beside the user's existing realm |
| Story | The Keepers of Time and Eternus-aligned Infinite Dragonflight study Northrend's darkest events; the key historical events must occur as recorded |
| Narrator | Moratari, friendly, curious, practical; humorous when cutting filler and serious around tragedy |
| Starting experience | New Timerunner introduction, starting at level 10 for ordinary classes; a specifically planned exception for WotLK death knights |
| Level cap | 80, with separate post-level-80 Infinite Power progression |
| Campaign | Selected original Northrend story quests and entertaining side quests, interrupted by custom Moratari transition quests |
| Critical ending | The player defeats the Lich King in Icecrown Citadel; the original encounter and outcome remain intact |
| Hub | Ground-level *Infinite Bazaar: Crystalsong Expedition*, in a surveyed clear area of Crystalsong Forest outside/near Dalaran |
| Progression | XP bonuses, Bronze, gear upgrades, Infinite Power, account-scoped Infinite Knowledge, Heroic World Tier |
| Content | Original dungeons and raids, keeper/infinite-themed rotating world activities, challenge content |
| Client fallback | Existing 3.3.5a models, spells, gossip menus, daily quests, markers and items; optional addon for an enhanced UI |

## 1. Historical rules and story bible

### 1.1 Story premise

After the reconciliation of Eternus's faction of the Infinite Dragonflight with the bronze dragons, an experimental expedition enters a contained version of the Northrend campaign. Andormu and the Keepers of Time demand historical fidelity. Eternus wants to know whether a champion can become extraordinarily powerful without breaking the chain of events that ultimately defeats Arthas. Moratari accompanies the champion to document variables and discard irrelevant detours.

The campaign is **the Wrath story, deliberately retold**, not a time-travel rewrite. The story includes suffering and catastrophe. The player may **skip chores**, not prevent fixed events. Moratari's ethical development is part of the arc: delighted by efficiency in the first chapter; uncomfortable at the Wrathgate; grave at Icecrown. The experiment proves that a person's actions can matter profoundly even when the larger historical destination cannot be changed.

### 1.2 Non-negotiable fixed points

1. Northrend's invasion progresses and Horde/Alliance remain engaged in their historically faction-specific campaigns.
2. Arthas remains the Lich King throughout the expansion.
3. Drakuru betrays the player; his story proceeds to its canonical confrontation.
4. The Wrathgate tragedy occurs, with Bolvar's apparent death and the original 3.3.5a follow-up retained where playable.
5. The Drakkari empire falls; the central Thorim/Loken story unfolds; Icecrown's campaign advances.
6. The Frozen Halls culminate in the Halls of Reflection escape.
7. The player and allies confront and defeat Arthas in the **original Icecrown Citadel encounter**, with original essential encounter/cinematic scripting, not a custom replacement ending.
8. Temporal enemies threaten *small unstable branches* around these events, never actually cancel the events.

### 1.3 Tone and Moratari research

Moratari is an Infinite Dragonflight member introduced in WoW Remix. Her visage is a pale elven female with pointed ears, light hair, luminous blue/cyan eyes, and dark blue-black armor with vivid cyan highlights. She presents as inquisitive, encouraging, intelligent, economical with busywork, and occasionally mischievous. Her source dialogue includes a winking explanation that Nozdormu prohibits changing these events but does not expressly prohibit changing the player's involvement. Her recurring lines frame history as study and the player as a collaborator. In Legion Remix she interrupts low-value tasks, offers short skip options, and advances the plot past mission-table and supply chores. 

**Use source characterization, but write new lines for Wrath Remix.** Retail NPC ID 241140, voice data, retail appearances and retail spells are **not** assumed to exist in the WotLK client. Build a custom creature template and use an existing female blood-elf/high-elf display with suitably matching equipment. Bronze-dragon display IDs can substitute for Infinite dragon models; darker spell auras or particle effects are optional if confirmed present in 3.3.5a. No mandatory MPQ patch.

Character personalities:

- **Moratari (Infinite):** practical scholar, narrator, transition questgiver; from chirpy to sober as losses accumulate.
- **Eternus (Infinite):** sponsor of the test, intelligent skeptic of imposed history; acknowledges fixed points but tests the boundaries.
- **Andormu (Keeper):** accountable guardian of causality; issues approvals and investigates deviations.
- **Nozari (Keeper):** audit/verification lead and fixed-point historian, optional hub appearance.
- **Alurmi (Keeper):** Keeper quartermaster and Bronze-supply vendor.
- **Zephyr (Keeper):** travel coordinator; portal/transport/return-home functions.
- **Sa'at (Keeper):** temporal field researcher and dungeon assignments.
- **Lindormi / Horos / Erus / Momentus / Arturos / Aeonicus / Durus / Domelius:** Remix-inspired hub roles, implemented as custom copies/stand-ins with available client displays and custom IDs.

**Lore caution:** Eternus's accommodation with the bronze is a *post-Wrath* story premise. The expedition originates outside the 3.3.5 timeline. Present these characters as visitors; do not claim they were physically in original Wrath history.

## 2. Separate-realm server architecture and compatibility contract

### 2.1 Required realm layout

```text
Existing install / account authentication
    authserver + existing auth database (realmlist has original realm and Wrath Remix)
        |
        +-- Realm A: EXISTING WORLD — original worldserver.conf, original characters DB,
        |          original world DB, current modules/settings/behavior, no Remix edits
        |
        +-- Realm B: WRATH REMIX — separate worldserver process + port + RealmID,
                   separate characters database, separate world database,
                   cloned-and-differentiated configs; mod-wrath-remix enabled
```

Use the user's real installed settings and versions as inputs. **Do not reset their existing XP rates, enabled module settings, AH policies, loot rates, anti-cheat, scripts, limits, transports, NPC spawns or passwords.** First produce a compatibility inventory containing core git revision, database version, patch set, module list, realm IDs, ports, current config changes, and client build. Preserve existing overrides in a version-controlled backup. Shared auth permits one set of login credentials; **it does not imply shared characters, gear, achievements or Bronze**. Cross-realm collectible unlocks are a future opt-in feature, not assumed.

Launch gating: module enabled only for a listed Remix realm ID; defense-in-depth guard at runtime. World DB changes target only `acore_world_remix` (illustrative name) and character progression tables only `acore_characters_remix`. Where a module is compiled into both binaries, it must be dormant on the existing realm. Different ports and an additional realmlist entry are required. Check that current installed modules do not override the same XP/damage/creature hooks in an incompatible order.

### 2.2 Configuration illustration, not a drop-in deployment

```ini
# Illustrative values; substitute the actual existing realm IDs and ports.
# Normal realm: LEAVE CURRENT CONFIG UNCHANGED.
# Wrath Remix's OWN worldserver configuration:
RealmID = 2
WorldServerPort = 8086
# Database names and credentials are illustrative only:
WorldDatabaseInfo = "127.0.0.1;3306;<user>;<secret>;acore_world_remix"
CharacterDatabaseInfo = "127.0.0.1;3306;<user>;<secret>;acore_characters_remix"
# LoginDatabaseInfo points to the SAME current authentication DB.

# mod-wrath-remix.conf (new settings only)
WrathRemix.Enable = 1
WrathRemix.AllowedRealmID = 2
WrathRemix.StartLevel = 10
WrathRemix.LevelCap = 80
WrathRemix.XP.QuestRate = 3.0
WrathRemix.XP.KillRate = 2.0
WrathRemix.HeroicWorld.Enable = 0
WrathRemix.WorldAssignments.Enable = 0
WrathRemix.Hub.Enable = 1
```

Enable complex systems progressively. Exact field and hook names are version-sensitive and must be checked against the installed core. Realm 2/8086 are examples, not assertions about the user's existing setup.

### 2.3 Compatibility and no-client-patch rules

- Only spell/creature/gameobject/item display IDs known to the **installed 3.3.5a DBC and client** may be used by the base release.
- Preserve WotLK class/talent systems; no expectation of retail dragonriding, retail artifact UI, retail World Quest UI or Dragonflight-era spells.
- Reuse compatible existing 3.3.5 effects and enchant/trigger systems, with core scripts for custom calculations.
- New items, creature templates, quests, and vendors belong in carefully reserved custom ID ranges; detect collisions with installed modules.
- Keep one-click SQL rollback scripts, database migrations, versioned configs, upgrade checklists and tests.
- A client addon can provide advanced panels, but all essential quests and vendors must remain usable without it.
- Death knights in 3.3.5 normally start at 55 with a dedicated intro. **First release:** preserve the level-55 DK exception and give them a tailored entry to the closest unlocked chapter; lowering DK start to 10 is a separate, high-risk feature requiring class/starting-zone testing.

## 3. User experience from character creation to Arthas

1. Player chooses **Wrath Remix** on realm selection and creates a normal 3.3.5a character.
2. The character enters an isolated tutorial encounter at level 10 (death knight rule above). Moratari introduces the preserved-timeline experiment and teaches one interaction.
3. Moratari opens a time portal to the Infinite Bazaar in Crystalsong Forest. Players meet Eternus, Andormu, Alurmi and the research board.
4. Player receives a Temporal Insignia (hearth-like return token, if technically safe) and the first Infinite Power reward. A faction-appropriate portal takes them to a streamlined Borean Tundra or Howling Fjord invasion route.
5. Main original quests play normally when memorable. Where irrelevant work intervenes, Moratari appears to offer **a new short transition quest**, recaps what happened, records the skipped work and hands off to the next original quest.
6. Northrend's seven chapters proceed with 10–80 leveling. A short set of selected fun optional quests and dungeons punctuate the main questline.
7. The final chapter unlocks the Frozen Halls and Icecrown Citadel. Arthas is defeated through the original ICC Lich King encounter. A Keeper epilogue confirms the timeline is intact.
8. Player may continue power progression, Bronze collecting, field assignments, scaled dungeons, raids and world tiers on this Remix realm.

## 4. The introduction: original scripted quests

All quests and dialogue in this section are **new, original Wrath Remix material**. Names/IDs should be assigned from the server's free custom range.

### WRM-001 — The Weight of Time

**Start:** Moratari in a short isolated introduction; **end:** Moratari; **objective:** Speak with her, examine three frozen time-echoes (Northrend invasion, the Wrathgate, Icecrown); **reward:** small Bronze cache and Temporal Observer status.

**Quest description — Moratari:** “There is an interesting problem with Northrend. Nearly everyone wishes parts of its history could be different. I understand why. But change one event, and we may lose the very people who saved Azeroth. I would like to study what *you* can accomplish without disturbing that outcome.”

**Dialogue:** “Do you see the contradiction? A terrible chapter can still contain indispensable pages. We're going to read the important ones.”

### WRM-002 — An Agreement Across the Hours

**Start:** Moratari; **end:** Andormu at the tutorial portal; **objective:** Hear Andormu's terms and Eternus's reply; **reward:** Timekeeper's Accord flag.

**Andormu:** “The experiment may shorten the road. It will not rewrite the destination. The Wrathgate must fall. The Frozen Throne must stand until its appointed hour.”

**Eternus:** “Then let us determine how much of that road truly matters. A champion is more than an obedient witness.”

**Moratari:** “Wonderful. A controlled disagreement. These generally produce the best notes.”

### WRM-003 — A Small Demonstration

**Start:** Moratari; **end:** Moratari; **objective:** Defeat three temporary time anomalies, collect a Thread of Time and empower the Temporal Insignia; **reward:** first Infinite Power rank, starter gear appropriate to the player's class.

**Tutorial note:** Shows Bronze, Infinite Power and item tooltips via normal gossip/quest text. Uses an existing 3.3.5 usable spell or scripted item rather than a new retail action button.

### WRM-004 — The Bazaar Beneath the Violet Sky

**Start:** Moratari; **end:** Eternus at Crystalsong camp; **objective:** Walk through a portal, talk to Alurmi and the chapter steward, attune return portal; **reward:** hub travel access, optional Chronicle Journal (quest log substitute).

### WRM-005 — The War We Must Remember

**Start:** Eternus; **end:** Moratari at the Alliance or Horde embarkation point; **objective:** Pick Borean Tundra or Howling Fjord starting route; **reward:** first chapter unlock.

**Moratari:** “We'll be studying the war, not inventorying every crate sent to fight it. When the next important scene arrives, I'll see that you are there.”

### WRM-006 — A Useful Omission (first transition tutorial)

**Trigger:** Complete first important invasion combat quest; **objective:** Speak with Moratari to bypass a designated low-value logistical mini-chain; **effect:** server grants the correct *chapter milestone* and next original quest availability; **reward:** one small Bronze cache and XP.

**Moratari:** “There are several rather heroic stories about transporting spare equipment. I think our records can survive without another.”

### WRM-007 — In the Shadow of the City (hub return tutorial)

**Objective:** Return to Crystalsong via portal, choose an Infinite Research assignment, then re-enter the active chapter. Introduce daily activities only after the core campaign loop works.

## 5. Hub: Infinite Bazaar — Crystalsong Expedition

*(Moved at the user's request in build 117 to Malykriss: The Vile Hold in Icecrown; see `PLAN.md`.)*

**Preferred placement:** an existing traversable, sufficiently flat clearing in Crystalsong Forest on the ground *outside/near Dalaran*, not inside Dalaran, not inside an instance and not on the invisible floating-city geometry. This is inspired by Legion Remix's bazaar south of Dalaran but must be verified using the user's 3.3.5 maps with `.gps`, line-of-sight, collision, transport/flight, Z-height, and escape-route tests. **Coordinates are intentionally TBD** rather than fabricated. If no safe clearing is available, use a small ground-level site elsewhere in Crystalsong Forest and link to Dalaran by two-way portal.

Physical layout: central hourglass/obelisk (gossip); Moratari's research tent; Eternus's platform; Keeper observation station; Bronze market stalls; gear salvager; training and class-access alcove; dungeon and raid challenge board; portals to faction invasion sites and Dalaran; neutral resting area. Existing tents, torches, banners, gameobjects and Bronze dragon visuals keep client compatibility.

| NPC | Allegiance / job | Realm function |
|---|---|---|
| Moratari | Infinite, narrator | Intro, chapter transitions, chapter replay, research explanations |
| Eternus | Infinite, sponsor | Campaign introduction, Infinite Power explanation, epilogue |
| Andormu | Keeper | Fixed-point approvals, story chapter milestone tracker |
| Nozari | Keeper | Historical records, anomaly incident log |
| Alurmi | Keeper | Keeper rewards and Bronze vendor |
| Zephyr | Keeper | Travel and portal coordinator |
| Sa'at | Keeper | Dungeon/raid and timeline safety assignments |
| Lindormi | Infinite | Scaled dungeon challenge dispatcher |
| Horos | Infinite | Rare/mount collections vendor |
| Arturos / Aeonicus / Durus | Infinite | Dungeon, 10-player raid, higher raid cosmetics |
| Erus | Infinite | Gear salvaging and material conversion |
| Domelius | Infinite | Cosmetic and furniture-equivalent display items supported in WotLK |
| Momentus | Infinite | Infinite Power and unlock explanation, talent/power gossip |
| Custom Chronologist | Neutral | Choose chapter, view milestones, daily assignment board |

**Vendor policy:** Bronze for collection and aesthetic rewards. A separate salvage/upgrade material pays for item upgrades; never force players to spend cosmetic currency on mandatory gear progression. Newer models/appearances unavailable to 3.3.5a are substituted by existing client equipment and mounts. Faction-based quartermasters can use two NPCs if neutral-vendor flags cause problems.

## 6. Quest curation and Moratari transition engine

### 6.1 Keep, skip and handoff

Three quest categories are sufficient for implementation:

- **MAIN:** must occur as originally scripted (or with minor, tested XP/reward changes). Its story scene is part of the historical record.
- **FUN:** optional or selectable filler that emphasizes unusual gameplay, memorable writing, vehicle sequences, minibosses, exploration or humor.
- **BRIDGE:** new original Moratari quest that jumps over a *specific named block* of omitted low-value original quests and turns on the next kept original quest.

Optional **DUNGEON** objectives and keeper **WORLD ACTIVITY** quests use separate tracking. A normal original quest skipped by a bridge need **not** be falsely recorded as actually completed: prefer an independent chapter milestone/checkpoint flag and explicit prerequisite remapping. If some legacy script requires a quest-completion flag, use narrowly scoped compatibility mapping after audit; log exactly which flags were granted and avoid rewarding original items/XP a second time.

**Transition contract (per bridge):** `bridge_custom_id`, preceding original anchor quest ID, optional faction gate, chapter stage, omitted original quest IDs, next original anchor quest ID, required original scripted states, summary text, arrival map/x/y/z/o verified in-world, rewards, idempotency protection, rollback/recovery behavior. The transition must be one-time, not be available until preceding chapter progress is satisfied, and must never allow skipping the Wrathgate, Drakuru reveal, pivotal Thorim events, Frozen Halls escape or ICC Lich King encounter.

### 6.2 A sample bridge as it should actually play

**WRM-120 — The Important Part Is Next**  
**Appears:** immediately after the player's chosen invasion route establishes a foothold, before low-value ammunition and supply errands.  
**Moratari:** “You have already proven the expedition can survive its first day. The ledgers, provisions and enormous pile of correspondence that follow are wonderfully complete. I have read them for you.”  
**Player gossip:** *Show me what matters next.*  
**Moratari:** “The defenses hold. The important thing is what the Scourge is preparing beyond them. Come.”  
**Action:** confirmation prompt, mark the invasion logistics milestone, advance to the next retained historical scene, teleport only if the selected anchor demands it.  
**Safeguard:** the quest does **not** mark all original Northrend invasion quests as globally completed.

### 6.3 Campaign chapter selection — full candidate quest roster

**Validation rule:** The original quests listed below are candidates collected for the curated campaign. Before implementation, query `quest_template`, `quest_template_addon`, starters, enders, conditions, event/spell scripts and relevant phase/vehicle dependencies on the user's installed world DB. Names and ordering may differ across factions, versions, DB revisions and chain variants. A title in this design is **not** a verified 3.3.5a ID. Do not generate SQL against names alone.

#### CHAPTER I — The Northrend Invasion (10–20; Borean or Howling Fjord)

**Borean — Alliance MAIN anchors:** Enlistment Day; A Time for Heroes; The Siege; Death From Above; Plug the Sinkholes; Thassarian, My Brother; Thassarian, the Death Knight; Finding the Phylactery; Buying Some Time; Words of Power; Last Rites.

**Borean — Horde MAIN anchors:** Hellscream's Vigil; The Defense of Warsong Hold; Taking Back Mightstone Quarry; Cutting Off the Source; Wind Master To'bor; Magic Carpet Ride; Tank Ain't Gonna Fix Itself; Tanks a lot...; The Plains of Nasam.

**Howling Fjord — Alliance MAIN anchors:** Hell Has Frozen Over...; If Valgarde Falls...; Rescuing the Rescuers; Prisoners of Wyrmskull; Dragonflayer Battle Plans; To Westguard Keep!; Operation: Skornful Wrath; Burn Skorn, Burn!; Gruesome, But Necessary; Towers of Certain Doom; All Hail the Conqueror of Skorn!

**Howling Fjord — Horde MAIN anchors:** The New Plague; Spiking the Mix; Test at Sea; New Agamand; A Tailor-Made Formula; Apply Heat and Stir; Field Test; Time for Cleanup; Parts for the Job; Warning: Some Assembly Required.

**FUN side quests:** Help Those That Cannot Help Themselves; Kaw the Mammoth Destroyer; The Assassination of Harold Lane; It Goes to 11...; Let's Go Surfing Now; The Slumbering King; The Frost Wyrm and its Master.

**BRIDGE I-A — The Ledger Can Wait:** follows the first Horde/Alliance invasion foothold; skips quarry/stockpile chores (after prerequisite review); sends player to major Scourge/vrykul action. Moratari: “The army has acquired food, ammunition, and a remarkably complicated spreadsheet. We can skip the accounting.”

**BRIDGE I-B — Two Shores, One War:** after chosen zone's showcase battle or Last Rites/The Plains of Nasam/Skorn conclusion; summarizes the unchosen starting coast without awarding its quests; sends player to Dragonblight. “Both landings hold, in the version of the record that matters. Unfortunately, the war grows worse inland.”

**Chapter dungeon:** Utgarde Keep or The Nexus, selected by route; the other is optional. Group finder/solo access rules are remapped by the Remix module.

#### CHAPTER II — The Wrathgate (20–32; Dragonblight)

**Alliance MAIN anchors:** Naxxramas and the Fall of Wintergarde; Flight of the Wintergarde Defender; Return to the High Commander; Rescue from Town Square; Find Durkon!; Into Hostile Territory; Steamtank Surprise; Scattered To The Wind; Breaking Off A Piece; The Chain Gun And You; Plunderbeard Must Be Found!; Plunderbeard's Journal; Chasing Icestorm: The 7th Legion Front; Chasing Icestorm: Thel'zan's Phylactery; Finality; An End And A Beginning; To Fordragon Hold!

**Horde MAIN anchors:** Into the Fold; Victory Nears...; Strength of Icemist; The Forsaken Blight and You: How Not to Die; Emerald Dragon Tears; Spread the Good Word; The Forsaken Blight; The Kor'kron Vanguard! **Important:** this simplified candidate selection does not imply all original Horde Wrathgate prerequisites are satisfied; Taunka/Agmar/Forsaken conditions require manual chain analysis.

**Shared fixed-point MAIN:** Audience With The Dragon Queen; Galakrond and the Scourge; On Ruby Wings; Return To Angrathar. Preserve the Wrathgate cinematic and native follow-up event.

**Alliance post-Wrathgate MAIN:** Reborn From The Ashes; Fate, Up Against Your Will; A Royal Coup; The Killing Time; The Battle For The Undercity.

**Horde post-Wrathgate MAIN:** Darkness Stirs; Herald of War; A Life Without Regret; The Battle For The Undercity.

**FUN:** The Demo-gnome; Leave Nothing to Chance; Frostmourne Cavern (include only after validating its source chain).

**BRIDGE II-A — The Road to Angrathar:** after sufficient faction-specific military preparation, skip second-tier camp errands and deliver player to the last valid pre-Wrathgate anchor. “History cannot seem to agree on the number of wagons. It is very certain about what happens at Angrathar.”

**BRIDGE II-B — What We Cannot Change:** **no event skip**. Only opens after the actual Wrathgate sequence; prefaces the post-event follow-up. “I wanted to stop it. Andormu has shown me the paths that follow if we do. There are no kind paths through this choice.”

**BRIDGE II-C — A War Within a War:** after faction post-Wrathgate event, summarizes minor aftermath assignments, points to Grizzly Hills. “We will record the names of those lost. The rest of the marching orders can wait.”

**QA gate:** The Battle for the Undercity uses notoriously complex original event scripting; validate Alliance and Horde on this precise server build. If it fails, repair the event in the Remix world or provide a faithful playable story-preserving scenario, not an unannounced skip.

#### CHAPTER III — The Curse of Grizzly Hills (32–42)

**Alliance Silverbrook MAIN:** Local Support; Close the Deal; A Tentative Pact; An Exercise in Diplomacy; Wolfsbane Root; Northern Hospitality; Test of Mettle; Words of Warning; Escape from Silverbrook; A Swift Response; Descent into Darkness.

**Alliance Ursoc MAIN:** The Failed World Tree; A Dark Influence; A Possible Link; Children of Ursoc; Vordrassil's Sapling; Vordrassil's Seeds; Ursoc, the Bear God.

**Horde Ursoc MAIN:** Vordrassil's Fall; The Darkness Beneath; The Bear God's Offspring; A Possible Link; Vordrassil's Seeds; Destroy the Sapling; Ursoc, the Bear God.

**Shared Drakuru MAIN:** Filling the Cages; Truce?; Vial of Visions; Subject to Interpretation; Sacrifices Must be Made; Heart of the Ancients; My Heart is in Your Hands; Voices From the Dust; Cleansing Drak'Tharon.

**FUN:** Dun-da-Dun-tah!; Scourgekabob; Seared Scourge.

**BRIDGE III-A — A Shorter Path Through the Woods:** after the essential worgen reveal and/or Ursoc resolution, summarize local military chores and direct to Drakuru. “There are a dozen paths through these woods. Only one ends with a very interesting troll.”

**BRIDGE III-B — A Friend of Questionable Judgment:** after the Drakuru chain but before Drak'Tharon, keep the dungeon and reveal intact. “I have a theory about your new friend. I sincerely hope the theory is wrong.”

**Chapter dungeon:** Drak'Tharon Keep (required or solo equivalent).

#### CHAPTER IV — The Fallen Empire (42–52; Zul'Drak)

**Drakuru MAIN candidate chain:** In Search Of Answers; Orders From Drakuru; The Ebon Watch; An Invitation, of Sorts... / Darkness Calling; Near Miss / Close Call; You Can Run, But You Can't Hide / Silver Lining; Dressing Down / Suit Up!; Infiltrating Voltarus; Dark Horizon / Reunited; So Far, So Bad; It Rolls Downhill; Hazardous Materials; Zero Tolerance; Sabotage; Fuel for the Fire; Disclosure; Betrayal.

**Drakkari/Loa MAIN candidate selection:** Strange Mojo; Precious Elemental Fluids; Preparations for the Underworld; Seek the Wind Serpent Goddess; Setting the Stage; Foundation for Revenge; Hell Hath a Fury; Blood of a Dead God; You Reap What You Sow; The Gods Have Spoken; Convocation at Zol'Heb; Unfinished Business. These must be checked for actual dependencies, because related arcs may not share one linear chain.

**FUN:** Troll Patrol; Wooly Justice; Enchanted Tiki Warriors.

**BRIDGE IV-A — The Things the Scourge Remembers:** skips repetitive propaganda and collection objectives **between** the significant Drakuru infiltration moments; preserves Infiltrating Voltarus, Disclosure and Betrayal. “The paperwork of betrayal is astonishingly thorough. Its consequences, alas, are rather more interesting.”

**BRIDGE IV-B — The Gods Are Still Silent:** after a representative Loa arc and the final Drakuru encounter, omits the remaining optional shrines; directs to Gundrak. “We have enough evidence to understand what became of the Drakkari. No need to disturb every altar to prove it.”

**Chapter dungeon:** Gundrak.

#### CHAPTER V — The Lifewarden's Warning (52–60; Sholazar Basin)

**MAIN candidate anchors:** Where in the World is Hemet Nesingwary?; Welcome to Sholazar Basin; Force of Nature; An Issue of Trust; Returned Sevenfold; The Fallen Pillar; Cultist Incursion; Exterminate the Intruders; Weapons of Destruction; The Lifewarden's Wrath; Freya's Pact; Powering the Waygate - The Makers' Perch; Powering the Waygate - The Makers' Overlook; The Etymidian; The Activation Rune; Back Through the Waygate; Reclamation.

**FUN:** The Great Hunter's Challenge; Some Make Lemonade, Some Make Liquor; Still At It; The Taste Test; A Hero's Burden (requires an optional pre-chain, do not grant the finale by itself).

**BRIDGE V-A — A Brief Hunting Expedition:** after discovering the lost Titan/Lifewarden storyline, skip the optional Nesingwary hunt collections. “Mr. Nesingwary has an impressive catalog. We shall admire it from a safe temporal distance.”

**BRIDGE V-B — The Waygate Is Ready:** after necessary portal/titan keys are acquired, skip intervening logistics, resume at the final Waygate/Avatar scene. “Now *this* is a machine worth studying. The missing invoices are not.”

**Chapter finale:** Reclamation; earn a Titan-themed Infinite Power unlock. Sholazar may remain a shorter chapter.

#### CHAPTER VI — The Secrets of Ulduar (60–70; The Storm Peaks)

**Thorim/Sons of Hodir MAIN candidate anchors:** They Took Our Men!; Leave No Goblin Behind; The Crone's Bargain; Mildred the Cruel; Discipline; Examples to be Made; A Certain Prisoner; A Change of Scenery; Is That Your Goblin?; The Hyldsmeet; Taking on All Challengers; You'll Need a Bear; Bearly Hanging On; Cold Hearted; Deemed Worthy; Making a Harness; The Last of Her Kind; The Warm-Up; Into the Pit; Prepare for Glory; Lok'lira's Parting Gift; The Drakkensryd; Sibling Rivalry; Mending Fences; The Refiner's Fire; A Spark of Hope; Forging an Alliance; A New Beginning; Veranus; Territorial Trespass; Krolmir, Hammer of Storms; The Terrace of the Makers; The Earthen Oath; Loken's Lackeys; The Reckoning.

**FUN:** Data Mining; The Library Console; Fate of the Titans; The Brothers Bronzebeard (Alliance); Distortions in Time (Horde).

**BRIDGE VI-A — The Very Long Road to Thorim:** shorten collection/standing tasks and repetitive training fights without skipping Lok'lira, Hyldsmeet, The Drakkensryd or the major Thorim revelation. “If we count every sparring victory, we will be here for several centuries. I have annotated the results.”

**BRIDGE VI-B — Respect Earned, Time Saved:** substitute a carefully approved campaign reputation milestone for Sons of Hodir gating where technically necessary; preserve Krolmir/Thorim's lore scenes. “Respect is an important thing. Waiting a month to demonstrate it is not particularly useful to our experiment.”

**BRIDGE VI-C — The Door Beneath the Mountain:** after The Reckoning, summarize minor Titan side trails; send player to Halls of Lightning and then unlock Ulduar later. “Some doors should be opened gently. Loken appears to have misplaced the instruction manual.”

**Chapter dungeon:** Halls of Lightning. **Optional:** Halls of Stone. **Postgame raid:** Ulduar.

#### CHAPTER VII — Icecrown: The Last Necessary Battle (70–80)

**Argent Crusade MAIN candidate anchors:** Preparations for War; Judgment Day Comes!; Honor Above All Else; Scourge Tactics; Curing The Incurable; Defending The Vanguard; If There Are Survivors...; Into The Wild Green Yonder; A Cold Front Approaches; The Last Line Of Defense; Once More Unto The Breach, Hero; The Purging Of Scourgeholme; The Scourgestone; The Air Stands Still; Into The Frozen Heart Of Northrend; The Battle For Crusaders' Pinnacle; The Crusaders' Pinnacle.

**Ebon Blade MAIN candidate anchors:** It's All Fun and Games; I Have an Idea, But First...; Free Your Mind; If He Cannot Be Turned; The Shadow Vault; The Duke; Honor Challenge; Shadow Vault Decree; Get the Key; Let the Baron Know.

**FUN — preserve complete Bridenbrad story:** A Tale of Valor; A Hero Remains; The Keeper's Favor; Hope Within the Emerald Nightmare; The Boon of Remulos; Time Yet Remains; The Touch of an Aspect; Dahlia's Tears; The Boon of Alexstrasza; Hope Yet Remains; The Will of the Naaru; The Boon of A'dal; Light Within the Darkness.

**Frozen Halls MAIN finale chain:** Inside the Frozen Citadel; Echoes of Tortured Souls (Forge of Souls); The Pit of Saron; The Path to the Citadel; Deliverance from the Pit; Frostmourne (Halls of Reflection); Wrath of the Lich King (Halls of Reflection escape).

**BRIDGE VII-A — The Armies Are in Place:** shorten Argent military requisitions after the required assaults; preserve The Battle for Crusaders' Pinnacle and Shadow Vault capture. “Both armies are ready. You have done enough quartermaster work to supply all of history.”

**BRIDGE VII-B — A Light We Must Remember:** after Bridenbrad optional arc, no tonal joke; acknowledge his fate and send player back to the main campaign. “The record of this man matters. I will keep it carefully.”

**BRIDGE VII-C — The Hall of Memories:** after Halls of Reflection escape, direct player to the newly available ICC raid without bypassing its actual Lich King encounter. “We have reached the part of history we came to understand. This last battle is yours to fight.”

**Final raid:** Icecrown Citadel including Arthas's original multi-stage fight and ending. Do not replace the boss with Moratari, an Infinite villain, or an alternate ending.

### 6.4 Chapter transition quests between major regions

| Custom quest | From (kept anchor) | Skips / recaps | Into (next chapter) | Tone |
|---|---|---|---|---|
| WRM-121 — Into the Dragonblight | finale of selected coast | unchosen landing, transport paperwork | first faction Dragonblight anchor | energetic |
| WRM-221 — After Angrathar | Undercity follow-up | camp aftermath errands only | Silverbrook/Ursoc | grave |
| WRM-321 — The Truth About Drakuru | Cleansing Drak'Tharon | uneventful travel | Zul'Drak Ebon Watch | suspicious |
| WRM-421 — A Kingdom Gone | Betrayal + Gundrak milestone | peripheral shrines | Freya/Sholazar | regretful |
| WRM-521 — The Makers' Trail | Reclamation | optional hunts | They Took Our Men! / Storm Peaks | curious |
| WRM-621 — Storm's End | The Reckoning/Halls of Lightning | surplus reputation grinding | Icecrown campaign | resolute |
| WRM-721 — The Final Hour | Halls of Reflection escape | raid access chores | Icecrown Citadel | solemn |

Every transition above is a **new actual quest**, not an administrative `.quest complete` shortcut displayed without story. Show a cinematic-like small dialogue scene (gossip and timed NPC text), a brief historical summary of the omitted stages, explicit destination and appropriate rewards. Present a skip confirmation when forfeiting unearned original quest rewards or when a character has active quests in the removed segment.

## 7. Leveling, power and reward design

### 7.1 XP curve and target pacing

Proposed chapter level targets: I 10–20; II 20–32; III 32–42; IV 42–52; V 52–60; VI 60–70; VII 70–80. The total XP budget must be computed from the **installed** `player_xp_for_level` values or core equivalent and the verified kept quest list. Do not rely on retail XP tables. Design for 12–20 hours of solo campaign progression as a tuning starting point, subject to playtesting.

**Initial balance parameters, editable:** 3.0× selected quest XP; 2.0× eligible kill XP; chapter completion XP equal to the remaining calculated level-budget gap (with a soft ceiling against large unintended jumps); optional quests provide useful but nonessential rewards. Character can hit 80 via main questline alone. Clamp reward contributions to avoid repeated chapter reward exploits and flag alts separately.

The initial release should use **chapter-banded world creature levels** (set on the Remix world DB) rather than promise retail per-player dynamic outdoor scaling. Outdoor mobs need meaningful damage/health at level 10; WotLK's original Northrend 68–80 baseline is otherwise unplayable. Scaled instances can use carefully validated AutoBalance or dedicated scripts. Higher-level visitors to earlier chapters gain little XP, and mixed-level groups may need a stated level tolerance until dynamic scaling is implemented. Every required original vehicle, escort, spell and quest item must be tested at the new level band.

### 7.2 Systems — separated by purpose

| System | Scope | Function | Default implementation |
|---|---|---|---|
| Bronze | Character; future optional account rewards | collection vendors and optional cosmetics | DB-backed custom balance displayed by gossip/addon; optional token item |
| Infinite Power | Per character | persistent stat growth and thematic proc-tree points | DB progression + scripted stat/spell effects |
| Infinite Knowledge | Same account **within Remix realm** | additive power-acquisition catch-up | one DB row per auth account ID, stored in Remix characters DB; no normal-realm change |
| Gear upgrade material | Per character | upgrade selected item levels or transform WotLK-compatible gear | separate spendable resource, tied to blacksmith/salvager NPC |
| Threads of Time | Per character | episodic caches granting Power progress | granted by mobs, raids, tasks, chapter milestones |
| Heroic World Tier | Per character, opt-in | stronger enemies, better non-story rewards | initially disabled; isolated shard/spawn layer or explicit safe per-player design later |

**Example Infinite Power balancing (new design, not retail formula):** ranks 1–100 grant incremental stamina and capped secondary stats; after 100, convert earnings to trait upgrades with diminishing returns rather than unbounded attack-power stacking. The exact rank gain curve, caps, stacking and PvP behavior are configuration values and must be load-tested. Limit movement-speed bonuses so escorts, falling and vehicle mechanics do not break. Provide passive/active power choices themed by Wrath: Titan's Resilience, Frozen Echo, Argent Inspiration, Draconic Reversal, Stormbound Might. Reuse only present 3.3.5a visual/spell assets; server scripts calculate effects.

**Infinite Knowledge:** account milestones at completion of chapters 2, 4, 6 and ICC, giving e.g. +5%, +10%, +15%, +20% cumulative Infinite Power earnings for future Timerunners on the same Remix realm. These rates are tunable placeholders. Avoid multiplying base XP beyond tested curve unless explicitly configured.

### 7.3 Research, tier and activities

- **Infinite Research:** 2–3 daily selections or a single rotating assignment board. Store earned-day tokens for catch-up, bounded so absence does not permanently punish a player. Existing 3.3.5a daily/weekly quest flags support a basic assignment implementation.
- **Heroic World Tier:** future opt-in with an explicit off-switch at the hub, health/damage modifiers, selected affixes and higher Bronze/Power rewards. For the first release, do **not** claim a retail-like individual world-tier phase exists automatically: it requires entity isolation or a carefully designed phase/spawn strategy.
- **Timeworn dungeons:** timed 5-player dungeons with affixes after basic scaling passes. Use ordinary instance quest, teleport and challenge board systems; no retail Mythic+ UI dependency.
- **Raid progression:** Naxxramas → Ulduar → Trial of the Crusader → Icecrown Citadel, with Ruby Sanctum optional. All may remain replayable after Arthas is defeated; story completion credited once.

## 8. Keeper of Time / Infinite Dragonflight field assignments

3.3.5a does **not** have Legion's native World Quest interface. Ship **World-Quest-like Temporal Assignments** as repeatable original quests: an active zone bulletin at the Bazaar, local Keeper or Infinite agent, a temporal obelisk or rift object, and daily/weekly completion flags. An optional addon later paints zone markers and progress overlays. Keep faction 989 (Keepers of Time) as a possible reward faction only after confirming its installed DBC and reputation rules; new custom factions may require client DBC work.

| Code | Zone | Original activity | Assigned by | Reward/meaning |
|---|---|---|---|---|
| TA-01 — Rifts at the Landing | Borean Tundra | close 3 rifts that spawn hostile echoes near invasion site | Moratari | small Bronze + Power; teaches rifts |
| TA-02 — The Unshelled Future | Howling Fjord | defeat altered vrykul who have acquired weapons from another era | Sa'at | Bronze + gear salvage |
| TA-03 — A Breath Before the Wrathgate | Dragonblight | contain unstable echoes near Angrathar; **cannot enter fixed-point sequence or avert it** | Andormu | Keeper reputation + Power |
| TA-04 — Keep the Record | Dragonblight | collect lost chronicle fragments from Scourge remnants | Nozari | Bronze + journal page |
| TA-05 — Wolves Out of Time | Grizzly Hills | defeat alternate worgen time echoes, not original Silverbrook actors | Zephyr | Bronze + Power |
| TA-06 — Drakuru's Shadow | Zul'Drak | fight a temporal projection of Drakuru after canonical reveal | Moratari | Power; no alternate betrayal |
| TA-07 — Forgotten Loa | Zul'Drak | stabilize shrine echoes of fallen Loa | Andormu | Keeper rep + Bronze |
| TA-08 — A Pillar Out of Place | Sholazar | restore displaced Titan pylon settings | Nozari | Power + upgrade material |
| TA-09 — The Second Storm | Storm Peaks | eliminate anomaly echoes of an unmade Titan defense | Sa'at | Bronze + Power |
| TA-10 — Lessons of Loken | Storm Peaks | timed mini-boss reflection tied to corrupted Titan security | Eternus | Power + knowledge |
| TA-11 — The Ice That Remembers | Icecrown | retrieve frozen witness memories; protect original NPC state | Andormu | Keeper rep + Bronze |
| TA-12 — No Other Frozen Throne | Icecrown | close an alternate-Throne illusion; cannot change Arthas fight | Moratari | high Power + weekly cache |

**Rotations:** 3–4 active assignments per day, two special weekly temporal incursions, and a rotating anomaly rare. Spawn new NPC entries at surveyed locations with stable faction/aggro/respawn settings. Never reuse an original quest NPC's identity as a kill target. Avoid an event that changes the original Wrathgate cinematic or final ICC encounter for unrelated players.

### Sample assignment script — TA-03

**Questgiver Andormu:** “There are moments that cannot be moved, and moments that can be torn open around them. The Wrathgate is both a monument and a wound. Seal what is leaking through. Do not mistake containment for revision.”  
**Objective:** Interact with two temporal anchors and defeat a rift entity on the outskirts of the Wrathgate.  
**Moratari, on completion:** “The battle remains as it was. Its survivors may never know what else was waiting outside the hour. Perhaps that is merciful.”

## 9. The ending and evergreen state

### WRM-800 — The Last Necessary Battle

**Required:** final outdoor campaign milestone + Halls of Reflection escape + normal ICC access. **Objective:** defeat the Lich King in the original raid. Party size may be rebalanced; boss stages, roleplay, Tirion involvement, and cinematic outcome must remain coherent. Do not auto-grant success when entering the final room. If solo is intended, implement deterministic mechanic-safe solo tuning (not simply massive health reductions that skip scripts).

### WRM-801 — The History We Kept

**Start:** a custom story credit object/NPC after the canonical Lich King kill; **end:** Andormu and Moratari at the Bazaar. **Objectives:** witness historical recording, talk to the three expedition leaders. **Reward:** *Witness of the Last Hour* title-equivalent reward (only if 3.3.5a supports the chosen presentation), a Bronze bundle, epilogue cosmetic and a new Infinite Power track.

**Andormu:** “The last hour came as it must. The sacrifices are neither erased nor forgotten.”  
**Eternus:** “We strengthened the champion. The record endured. There is more space in time than I was taught to believe.”  
**Moratari:** “I have reviewed every note. I had hoped the answer would be simpler. Still... I am glad you were there.”

Remain on Wrath Remix at level 80, continue repeatable systems, optional raids and more challenging rift assignments. Do not automatically convert the character onto the original realm or claim account-wide retail achievements. That would require an entirely separate migration design.

## 10. AzerothCore implementation blueprint

### 10.1 Module layout

```text
modules/mod-wrath-remix/
  CMakeLists.txt
  conf/mod-wrath-remix.conf.dist
  src/loader.cpp
  src/RemixPlayerScript.cpp
  src/RemixQuestBridge.cpp
  src/RemixCampaignMgr.cpp
  src/RemixPowerMgr.cpp
  src/RemixCurrencyMgr.cpp
  src/RemixWorldAssignmentMgr.cpp
  src/RemixHubScripts.cpp
  src/RemixInstanceRules.cpp
  sql/world/0001_remix_world.sql
  sql/world/0002_remix_story_quests.sql
  sql/world/0003_remix_hub.sql
  sql/characters/0001_remix_progress.sql
  data/campaign_manifest.csv
  data/quest_validation_report.csv
  tests/
  README.md
```

Prefer a C++ module for XP, quest bridge actions, persistent progression, creature scaling and safe transactions. SQL defines custom quests/NPCs/items/spawns and the curated original quest visibility. Use Eluna only if the user's existing installation already includes it and its interaction with installed hooks has been tested; it is not a requirement.

### 10.2 Database concepts (schema proposal)

**World DB, Remix only:** `remix_campaign_chapter`, `remix_quest_manifest`, `remix_quest_bridge`, `remix_quest_bridge_skip`, `remix_hub_npc`, `remix_assignment_rotation`, custom `quest_template` rows, custom starters/enders/spawns.  
**Characters DB, Remix only:** `remix_character_state` (guid, chapter, stage, intro_completed, world_tier), `remix_character_power`, `remix_character_currency`, `remix_character_bridge_log`, `remix_character_assignment_state`, `remix_account_knowledge` (auth_account_id + rank).  
**Auth DB:** only new realmlist entry where necessary; do not add character-specific Remix progression to shared auth or touch current account credentials.

All custom transitions must be atomic and idempotent: check milestone, save transition log, award rewards once, open next anchor, then teleport if required. If a teleport fails, the character may retry without duplicate grants. Log `quest_id`, `chapter`, `from_stage`, `to_stage`, skipped IDs, and timestamp. Recovery NPC gossip: “The record became tangled. Return me to my last stable chapter.”

### 10.3 AzerothCore hook map — verify current signature

- `OnPlayerFirstLogin` / `OnPlayerLogin`: initialize Timerunner state, validate realm gate, restore progression and buffs.
- `OnPlayerGiveXP` and `OnPlayerLevelChanged`: custom XP and milestones; avoid double multiplicative scaling from other modules.
- `OnPlayerCompleteQuest`: update chapter checkpoints for *actual completion* of kept original quests.
- `CreatureScript::OnGossipHello`, `OnGossipSelect`, `OnQuestReward`: Moratari transitions, hub and vendors.
- `OnPlayerMapChanged` / map hooks: chapter/area and activity bookkeeping.
- `UnitScript` or creature callbacks: drop Power/Threads and handle custom anomaly combat.
- Appropriate logout, item and inventory hooks: persist rewards and stop double salvage.

**Important:** hook availability, return types, quest completion timing, and module override ordering depend on the installed AzerothCore revision. Prototype and test each hook, do not blindly paste signatures from a newer core.

### 10.4 Scripted skips: dependency audit

For each candidate original quest: identify ID and faction, level/category, `PrevQuestID`, `NextQuestID`, `ExclusiveGroup`, `RewardNextQuest`, `BreadcrumbForQuestId`, required items/spells, `conditions`, SMARTAI/SAI, escort AI, cinematic/vehicle scripts, required reputations, phasing/spawn groups. AzerothCore documents `quest_template_addon` and `quest_template` as the source of these dependencies. Skipping a *title* without testing these relationships can strand players. The new bridge quests should remap availability inside the Remix world's database and supplement completion checks in the C++ campaign manager, while retaining fixed-point story scripts.

### 10.5 Scalability and performance

- Avoid player-specific overworld creature-level changes without isolation; different-level players sharing one NPC can see inconsistent combat behavior.
- Set chapter baseline spawns/levels only in dedicated Remix world DB; test NPC aggro, escort speeds, hostility and level 10 quest mob abilities.
- Avoid excessive per-frame scripts; persist at quest/action boundaries and batch DB writes.
- Custom power buffs must recalculate safely on login, zone transition, death/resurrection, equipment changes and rank-up.
- If AutoBalance is already installed, validate that its per-instance scaling, map exclusions, reward scaling and cache invalidation do not conflict with custom Remix mechanics. Do not install or override it by default just because it exists on GitHub.
- Distinguish custom Rift rare spawns from original story AI to avoid cross-script state corruption.

## 11. Phased creation roadmap with exit criteria

| Phase | Work | Exact deliverable / acceptance gate |
|---|---|---|
| **0 — Inventory & safety** | Snapshot configs/database/client, list core commit/modules and custom assets, choose free ID ranges, record original realm baseline | Compatibility matrix; backed-up original realm; restoration exercise passes |
| **1 — Realm foundation** | Add second `realmlist` row, second worldserver and isolated world/characters DB; module disabled by default on normal realm | Both realms visible; login to each; existing realm regression untouched |
| **2 — Hub and Moratari** | Survey Crystalsong clearing, build no-patch camp, NPCs/vendors/portals, original prologue WRM-001..007 | New Timerunner enters, meets narrator, earns Power, reaches hub and invasion starter |
| **3 — Quest manifest & skip engine** | Audit original quest IDs/dependencies, MAIN/FUN tags, bridge state machine, rescue mechanism, faction checks | One full chapter can skip chores safely, keeps selected original scenes, never double-grants rewards |
| **4 — One vertical slice** | Complete one invasion route + Chapter II Wrathgate (with faction QA), milestone XP, basic Bronze/Power | 10-to-32 playable with tested fixed-point cinematics/Undercity route |
| **5 — Entire story** | Chapters III–VII, actual transitions, full campaign XP budget, dungeon finales, ICC unlock | A first-time Timerunner reaches 80 and defeats the original Lich King without progression dead ends |
| **6 — Remix power** | Infinite Power, upgrades, salvage, account Knowledge, rewards and balance | All rewards persist across login/restarts; no normal realm effects |
| **7 — World activities** | Keepers+Infinite NPC assignments, rotation, rifts, weekly encounters | Every zone has active repeatable content, reset cadence correct, original story unaffected |
| **8 — Challenge content** | Heroic World Tier isolation, scaled dungeons, optional solo-aware raid rules, affixes | Mixed levels and parties tested; no encounter softlocks or PvP cross-talk |
| **9 — Interface/polish** | Optional 3.3.5 addon, cosmetic vendors, NPC dialogue art, telemetry, localization | Mode understandable without addon; optional enhanced map/quest view |
| **10 — Regression and release** | Full backups, QA for both factions/classes, two concurrent worldservers, stress, rollback, upgrade notes | All fixed points preserved; existing server/settings still match baseline; launch checklist signed off |

**Recommended development order:** Do Phase 0 and 1 before editing a single original quest. Build the Moratari introduction and one representative skip. Then complete the Wrathgate as a scripted vertical slice; it tests the hardest historical invariant. Add the rest of the chapters once the bridge mechanism is reliable. Treat Heroic World Tier and fully dynamic outdoor scaling as advanced phases, not core MVP promises.

### 11.1 Milestone tests (minimum set)

1. Original realm normal quest/XP/loot/raid regressions unchanged after Remix worldserver launch.
2. Different realm IDs and ports; no accidental DB cross-write; auth shares account login while character data stays separate.
3. New level-10 character cannot bypass required intro or claim rewards twice; DK 55 branch works.
4. Both Alliance and Horde can reach Wrathgate; cinematic and post-event resolve correctly.
5. Drakuru's reveal and final betrayal remain playable.
6. The Drakkensryd vehicle, core Thorim/Loken sequences, Freya Waygate and Icecrown campaigns remain navigable at remapped levels.
7. Halls of Reflection and ICC Lich King mechanics produce canonical end state; no auto-skip.
8. Skipped chains do not strand follow-up quests or cause duplicate original quest rewards.
9. Normal/Heroic world-tier characters do not see conflicting creatures or inherit incorrect stat changes.
10. Restart during/after transition safely resumes; rewards idempotent; malformed DB migration rolls back.
11. Timed Keeper assignments reset correctly and are completable without retail World Quest UI.
12. All hub NPC models, equipment, items and teleport locations exist in the installed client/DB.

## 12. Project artifacts to produce in order

1. **Compatibility inventory** (`server_baseline.md`): anonymized existing config differences, all installed modules, client build, DB schema revisions; preserve actual values in private administrator notes.
2. **Quest audit spreadsheet** (`wrath_remix_quest_manifest.csv`): every candidate quest title resolved to actual installed ID, faction, role, dependencies, keep/omit decision, tested boolean.
3. **Transition script bible** (`moratari_bridges.md`): all new quest texts, preceding anchor, skipped IDs, next anchor, dialogue and travel mapping.
4. **Map/site report** (`crystalsong_hub_site.md`): verified location coordinates, collision and spawn screenshots, client display IDs.
5. **Realm deployment plan** (`realm_isolation.md`): port/realm IDs, separate SQL/backup conventions, server binaries, migration commands.
6. **C++ module and SQL migrations** (`mod-wrath-remix`): source-controlled, tests and config.
7. **QA matrix** (`tests.md`): faction/class/level/chapter/event regression, normal-realm regression.

## 13. Verification notes and research references

- **Confirmed sourced behavior:** Legion Remix's accelerated gameplay, Infinite Power, Research, Bronze, Heroic World Tier, obelisks and quest simplifications; Moratari's demeanor, looks and skip behavior; Keeper membership, and AzerothCore's multi-realm/multi-database architecture.
- **Original new design:** The Wrath experiment's story, hub name/placement proposal, WRM and TA quests/dialogue, all progression rates, chapter level bands, C++ module architecture, power trees, NPC positions, zone activity objectives, lore interpretations, balance and roadmap.
- **Not yet validated:** Individual quest IDs, every prerequisite and actual safe bridge boundary; installed ChromieCraft assets and display IDs; true portal/teleport coordinates; user's existing module interactions; 3.3.5 heroic world layering; scaled raid mechanics.
- **Scope:** design document only. Nothing here changes or inspects the user's actual server, settings or database.

### Source URLs (for verification during implementation)

1. Moratari biography, quotes, dialogue and visage: https://warcraft.wiki.gg/wiki/Moratari
2. Legion Remix overview (official design summary and systems): https://www.wowhead.com/guide/wow-remix-legion-overview
3. Legion Remix skips and Moratari lines: https://warcraft.wiki.gg/wiki/Legion_Remix#Skips
4. Moratari's introduction quest and intro skip: https://warcraft.wiki.gg/wiki/Time_Crisis
5. Keepers of Time roster and faction context: https://warcraft.wiki.gg/wiki/The_Keepers_of_Time
6. Wowhead Keepers reputation roster: https://www.wowhead.com/faction=989/keepers-of-time#members
7. Eternus and Infinite Dragonflight lore: https://warcraft.wiki.gg/wiki/Eternus ; https://wowpedia.fandom.com/wiki/Infinite_dragonflight
8. Infinite Bazaar roster and location precedent: https://warcraft.wiki.gg/wiki/Infinite_Bazaar
9. AzerothCore official multi-realm Docker example: https://github.com/azerothcore/acore-docker/blob/master/README.md
10. AzerothCore server process and auth structure: https://www.azerothcore.org/wiki/how-to-restart-and-debug
11. AzerothCore script hooks: https://www.azerothcore.org/wiki/hooks-script-reference
12. AzerothCore quest schema: https://www.azerothcore.org/wiki/quest_template ; https://www.azerothcore.org/wiki/quest_template_addon
13. AutoBalance project/config for *optional* instance scaling: https://github.com/azerothcore/mod-autobalance
14. User-provided Moratari NPC: https://www.wowhead.com/npc=241140/moratari

---

**Definition of finished:** Wrath Remix is a separate selectable realm running on the user's established AzerothCore/ChromieCraft-compatible 3.3.5a environment, with a Moratari-led curated Northrend story, real in-game skip-transition quests, Keeper/Infinite field activities, a usable Crystalsong hub, long-tail Remix power progression, and the original defeat of the Lich King as its story ending. **The original realm and all its existing configuration and characters remain unchanged.**
