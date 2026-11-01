# SPDX-License-Identifier: GPL-3.0-or-later
"""The naga's converted looks by name (bible 18.6: models from wago.tools made the 3.3.5 client's as the server
is packaged, never committed), each its CreatureDisplayInfo id (40000-40199) in the patch. A chapter names them
as any look: dict(key="serena", look="serena", ...). The naga's assets write this list; these few stand in
until they do (no chapter may use one before the assets make it: the server would have no such look). Data only."""

LOOKS = {
    "serena": 40000,
    "watcher": 40001,
}
