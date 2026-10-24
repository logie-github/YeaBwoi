// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.local

import com.winlator.XServerDisplayActivity

/**
 * A local realm's Windows session (Winlator's screen, its realm servers in it), in a task of its own
 * (its `taskAffinity` in the manifest). In the launcher's task it was gone the moment the launcher came
 * back in front: [com.logie.warcraft.MainActivity] is `singleTask`, and Android ends the screens above
 * a `singleTask` screen in its task when it's started again, so the session's screen ended, and with
 * it Wine (killed: status 137), while the database was starting. In its own task the launcher's coming
 * back only puts it behind. The game's own sessions stay as they were ([XServerDisplayActivity]).
 */
class RealmSessionActivity : XServerDisplayActivity()
