// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.local

import com.winlator.XServerDisplayActivity

/**
 * A local realm's game (Winlator's screen), joining the realm's Wine, which runs with no screen
 * ([WindowsRealmHost]); its end ends the game's programs only (Winlator patch 0011). In a task of its
 * own (its `taskAffinity` in the manifest): in the launcher's, it was gone the moment the launcher
 * came back in front, [com.logie.warcraft.MainActivity] being `singleTask` (Android ends the screens
 * above a `singleTask` screen in its task when it's started again). The game's own sessions stay as
 * they were ([XServerDisplayActivity]).
 */
class RealmSessionActivity : XServerDisplayActivity()
