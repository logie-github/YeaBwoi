# mod-base-model: Base Model

Every character in the realm (yours, other players' and the random bots) drawn without its gear while
the launcher's **Game options → Base Model** is on, to see their races, skins, faces and hair. Only the
looks change: the gear stays on, with its stats. Both realms.

## How

The game draws a character's gear from its visible item fields (`PLAYER_VISIBLE_ITEM_*`: an item and its
enchantments a slot), its own character's and everyone else's, which the realm sends; with Base Model on
they're sent empty, and gear put on while it's on isn't shown either (`OnPlayerAfterSetVisibleItemSlot`).
Turning it off shows each slot's gear again (the same call the game's equipping makes, so a transmog
module's looks come back too).

The switch is a file: the app makes `files/base-model/enabled` while it's on. The world server looks for
it every two seconds, so it works while the realm runs; each character is bared or dressed again at its
next update. `BaseModel.Enable = 0` in the realm's `modules/mod_base_model.conf` keeps it off whatever
the switch says; deleting this folder takes the module out of the realm.

`.basemodel` says whether it's on, and in how many of your slots gear is worn and shown.

Not changed: creatures (their gear is drawn into their own textures), and the character list's pictures
(drawn from the gear the realm lists for them).

Tested: built against the core (its hooks and fields). Not run in the game or on a phone.
