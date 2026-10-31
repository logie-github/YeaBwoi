# Body (mod-base-model)

**Game options → Body** previews female characters without starter gear in the character creation
menu. Male previews, the character list and characters in the world keep their equipment visible.
Both 3.3.5a realms support it. Restart the realm and game after changing the option.

The existing `files/base-model/enabled` preference is preserved. The final client patch
(`mod-skyborne`) clears equipment only from female `CharStartOutfit.dbc` records. The server's
outfits, equipment and visible item fields are untouched. Turning it off rebuilds the client
patch from the original outfits. `BaseModel.Enable = 0` disables the option.

The old module loader remains for compatibility and registers no player hooks.
