// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.felsong.core

import org.json.JSONObject

/**
 * Felsong's installation manifest (`files/felsong/install.json`): what's in place, from which
 * version, and how it was checked. Written after each step that finishes (atomically, through the
 * folder guard), so an install that stopped halfway knows what's done, and a repair knows what to
 * look at. Nothing in it is about the other games.
 */
data class FelsongInstallState(
    /** The realm package in `server/`: its SHA-256 (the release's), or null. */
    val serverSha256: String? = null,
    val serverTag: String? = null,
    /** The databases were made from the package's seed (the package's SHA-256 then). */
    val databaseFrom: String? = null,
    /** The world server applied its database updates at least once (it does at each start). */
    val databaseUpdated: Boolean = false,
    /** The client in `client/`: its Wow-64.exe's SHA-256 and the build it says it is. */
    val clientExeSha256: String? = null,
    val clientBuild: Int = 0,
    /** The client was patched for a local realm (Wow-64_Patched.exe beside Wow-64.exe) from this Wow-64.exe. */
    val patchedFrom: String? = null,
    /** The server's data in `data/`: made from the client with this Wow-64.exe. */
    val dataFrom: String? = null,
    val dataParts: Set<String> = emptySet(),
    /** The step an install or repair was on when it last stopped without finishing, if one did. */
    val interruptedStep: String? = null,
    val updatedAt: Long = 0L,
) {
    fun toJson(): String = JSONObject()
        .put("version", 1)
        .put("serverSha256", serverSha256 ?: JSONObject.NULL)
        .put("serverTag", serverTag ?: JSONObject.NULL)
        .put("databaseFrom", databaseFrom ?: JSONObject.NULL)
        .put("databaseUpdated", databaseUpdated)
        .put("clientExeSha256", clientExeSha256 ?: JSONObject.NULL)
        .put("clientBuild", clientBuild)
        .put("patchedFrom", patchedFrom ?: JSONObject.NULL)
        .put("dataFrom", dataFrom ?: JSONObject.NULL)
        .put("dataParts", dataParts.sorted().joinToString(","))
        .put("interruptedStep", interruptedStep ?: JSONObject.NULL)
        .put("updatedAt", updatedAt)
        .toString(2)

    companion object {
        fun parse(text: String?): FelsongInstallState {
            if (text.isNullOrBlank()) return FelsongInstallState()
            return runCatching {
                val json = JSONObject(text)
                fun str(key: String) = json.optString(key, "").takeIf { it.isNotEmpty() && !json.isNull(key) }
                FelsongInstallState(
                    serverSha256 = str("serverSha256"),
                    serverTag = str("serverTag"),
                    databaseFrom = str("databaseFrom"),
                    databaseUpdated = json.optBoolean("databaseUpdated", false),
                    clientExeSha256 = str("clientExeSha256"),
                    clientBuild = json.optInt("clientBuild", 0),
                    patchedFrom = str("patchedFrom"),
                    dataFrom = str("dataFrom"),
                    dataParts = json.optString("dataParts", "").split(',').filter { it.isNotBlank() }.toSet(),
                    interruptedStep = str("interruptedStep"),
                    updatedAt = json.optLong("updatedAt", 0L),
                )
            }.getOrDefault(FelsongInstallState())
        }
    }
}

/**
 * The numbered steps from nothing to Play (the user's ask: every file Felsong needs, 1 to N, in the
 * order they're needed), and where the phone is on them, from what's actually on it ([Facts]).
 * Plain logic: the launcher shows it, the installer follows it.
 */
object FelsongInstallPlan {
    enum class Step { CHECK, SERVER, DATABASE, CLIENT, DATA, PATCH, ACCOUNT, READY }

    enum class Status {
        DONE,

        /** The app can do it by itself (Install does). */
        TODO,

        /** Needs the user: their client (the app can't download it), or an account. */
        NEEDS_USER,

        /** Can't be done on this phone ([Facts.blocker] says why). */
        BLOCKED,

        /** Waits for an earlier step. */
        WAITING,
    }

    /** What the app found on the phone. */
    data class Facts(
        /** Why Felsong can't run on this phone at all (not arm64, not the phone's main user), or null. */
        val blocker: String? = null,
        val serverInstalled: Boolean,
        /** The installed package is the one this app version is made for. */
        val serverCurrent: Boolean,
        val databasesThere: Boolean,
        val clientThere: Boolean,
        val dataThere: Boolean,
        /** The data was made from the client that's there now. */
        val dataMatchesClient: Boolean,
        /** Wow-64_Patched.exe is there, made from the Wow-64.exe that's there now. */
        val clientPatched: Boolean,
        /** Null: not known (the realm isn't running to ask). */
        val hasAccount: Boolean?,
    )

    data class Line(val step: Step, val status: Status)

    fun of(facts: Facts): List<Line> {
        val lines = mutableListOf<Line>()
        fun add(step: Step, status: Status) = lines.add(Line(step, status))
        if (facts.blocker != null) {
            add(Step.CHECK, Status.BLOCKED)
            Step.entries.drop(1).forEach { add(it, Status.WAITING) }
            return lines
        }
        add(Step.CHECK, Status.DONE)
        add(Step.SERVER, if (facts.serverInstalled && facts.serverCurrent) Status.DONE else Status.TODO)
        add(Step.DATABASE, if (facts.databasesThere) Status.DONE else if (facts.serverInstalled) Status.TODO else Status.WAITING)
        add(Step.CLIENT, if (facts.clientThere) Status.DONE else Status.NEEDS_USER)
        add(
            Step.DATA,
            when {
                facts.dataThere && facts.dataMatchesClient -> Status.DONE
                !facts.clientThere || !facts.serverInstalled -> Status.WAITING
                else -> Status.TODO
            },
        )
        add(
            Step.PATCH,
            when {
                facts.clientPatched -> Status.DONE
                !facts.clientThere || !facts.serverInstalled -> Status.WAITING
                else -> Status.TODO
            },
        )
        add(
            Step.ACCOUNT,
            when (facts.hasAccount) {
                true -> Status.DONE
                // The installer makes one (the user's choice of e-mail and password) once the realm is up.
                false -> Status.NEEDS_USER
                null -> if (facts.databasesThere) Status.NEEDS_USER else Status.WAITING
            },
        )
        val ready = lines.all { it.status == Status.DONE }
        add(Step.READY, if (ready) Status.DONE else Status.WAITING)
        return lines
    }

    /** Everything's in place: Press start plays. */
    fun ready(lines: List<Line>): Boolean = lines.lastOrNull()?.let { it.step == Step.READY && it.status == Status.DONE } == true

    /** The first step that isn't done, which the launcher's main button is about. */
    fun next(lines: List<Line>): Line? = lines.firstOrNull { it.status != Status.DONE }

    /** The step's number as the launcher shows it (CHECK is 0: not a download; READY isn't a step). */
    fun number(step: Step): Int = step.ordinal
}
