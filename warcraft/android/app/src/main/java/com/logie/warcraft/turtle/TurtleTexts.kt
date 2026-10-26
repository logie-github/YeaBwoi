// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle

import android.content.Context
import com.logie.warcraft.R

/** OctoWoW's states in words, for the launcher and the notification. */
object TurtleTexts {
    fun step(context: Context, step: TurtleJobs.Step): String = context.getString(
        when (step) {
            TurtleJobs.Step.CHECKING -> R.string.turtle_step_checking
            TurtleJobs.Step.DOWNLOADING -> R.string.turtle_step_downloading
            TurtleJobs.Step.UNPACKING -> R.string.turtle_step_unpacking
            TurtleJobs.Step.DATABASE -> R.string.turtle_step_database
            TurtleJobs.Step.DOWNLOADING_CLIENT -> R.string.turtle_step_downloading_client
            TurtleJobs.Step.COPYING_CLIENT -> R.string.turtle_step_copying
            TurtleJobs.Step.VERIFYING_CLIENT -> R.string.turtle_step_verifying
            TurtleJobs.Step.MAPS -> R.string.turtle_step_maps
            TurtleJobs.Step.VMAPS -> R.string.turtle_step_vmaps
            TurtleJobs.Step.MMAPS -> R.string.turtle_step_mmaps
            TurtleJobs.Step.VERIFYING_DATA -> R.string.turtle_step_verifying_data
            TurtleJobs.Step.ACCOUNT -> R.string.turtle_step_account
            TurtleJobs.Step.BACKUP -> R.string.turtle_step_backup
            TurtleJobs.Step.RESTORE -> R.string.turtle_step_restore
            TurtleJobs.Step.REMOVING -> R.string.turtle_step_removing
            TurtleJobs.Step.RUNTIME -> R.string.turtle_step_runtime
        }
    )

    fun server(context: Context, status: TurtleServer.Status): String = context.getString(
        when (status.phase) {
            TurtleServer.Phase.STOPPED -> R.string.turtle_realm_stopped
            TurtleServer.Phase.STOPPING -> R.string.turtle_realm_stopping
            TurtleServer.Phase.FAILED -> R.string.turtle_realm_failed
            TurtleServer.Phase.RUNNING -> if (status.worldUp) R.string.turtle_realm_running else R.string.turtle_realm_running_no_data
            TurtleServer.Phase.STARTING -> when (status.step) {
                TurtleServer.Step.CONFIGURING, null -> R.string.turtle_realm_step_configuring
                TurtleServer.Step.DATABASE -> R.string.turtle_realm_step_database
                TurtleServer.Step.LOGIN -> R.string.turtle_realm_step_login
                TurtleServer.Step.WORLD -> R.string.turtle_realm_step_world
            }
        }
    )
}
