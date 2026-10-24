// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.felsong

import android.content.Context
import com.logie.warcraft.R

/** Felsong's states in words, for the launcher and the notification. */
object FelsongTexts {
    fun step(context: Context, step: FelsongJobs.Step): String = context.getString(
        when (step) {
            FelsongJobs.Step.CHECKING -> R.string.felsong_step_checking
            FelsongJobs.Step.DOWNLOADING -> R.string.felsong_step_downloading
            FelsongJobs.Step.DOWNLOADING_CLIENT -> R.string.felsong_step_downloading_client
            FelsongJobs.Step.UNPACKING -> R.string.felsong_step_unpacking
            FelsongJobs.Step.DATABASE -> R.string.felsong_step_database
            FelsongJobs.Step.RUNTIME -> R.string.felsong_step_runtime
            FelsongJobs.Step.COPYING_CLIENT -> R.string.felsong_step_copying
            FelsongJobs.Step.VERIFYING_CLIENT -> R.string.felsong_step_verifying
            FelsongJobs.Step.MAPS -> R.string.felsong_step_maps
            FelsongJobs.Step.VMAPS -> R.string.felsong_step_vmaps
            FelsongJobs.Step.MMAPS -> R.string.felsong_step_mmaps
            FelsongJobs.Step.VERIFYING_DATA -> R.string.felsong_step_verifying_data
            FelsongJobs.Step.PATCHING -> R.string.felsong_step_patching
            FelsongJobs.Step.ACCOUNT -> R.string.felsong_step_account
            FelsongJobs.Step.BACKUP -> R.string.felsong_step_backup
            FelsongJobs.Step.RESTORE -> R.string.felsong_step_restore
            FelsongJobs.Step.REMOVING -> R.string.felsong_step_removing
        }
    )

    fun server(context: Context, status: FelsongServer.Status): String = context.getString(
        when (status.phase) {
            FelsongServer.Phase.STOPPED -> R.string.felsong_realm_stopped
            FelsongServer.Phase.STOPPING -> R.string.felsong_realm_stopping
            FelsongServer.Phase.FAILED -> R.string.felsong_realm_failed
            FelsongServer.Phase.RUNNING -> if (status.worldUp) R.string.felsong_realm_running else R.string.felsong_realm_running_no_data
            FelsongServer.Phase.STARTING -> when (status.step) {
                FelsongServer.Step.CONFIGURING, null -> R.string.felsong_realm_step_configuring
                FelsongServer.Step.DATABASE -> R.string.felsong_realm_step_database
                FelsongServer.Step.LOGIN -> R.string.felsong_realm_step_login
                FelsongServer.Step.WORLD -> R.string.felsong_realm_step_world
            }
        }
    )
}
