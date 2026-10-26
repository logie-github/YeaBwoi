// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle.core

import org.json.JSONObject

/**
 * OctoWoW's installation manifest (`files/turtle/install.json`): what's in place, from which
 * version, and how it was checked. Written after each step that finishes (atomically, through
 * [SafeFs.writeAtomically]), so an install that stopped halfway knows what's done, and a repair
 * knows what to look at. Nothing in it is about the other games.
 */
data class InstallState(
    /** The realm package in `server/`: its SHA-256 (the release's), or null. */
    val serverSha256: String? = null,
    val serverTag: String? = null,
    /** The databases were made from the package's seed (the package's SHA-256 then). */
    val databaseFrom: String? = null,
    /** The world server applied its database updates at least once (it does at each start). */
    val databaseUpdated: Boolean = false,
    /** The client in `client/`: its WoW.exe's SHA-256, and how it compared with OctoWoW's torrent. */
    val clientExeSha256: String? = null,
    val clientCheck: ClientCheck = ClientCheck.NONE,
    /** Files of the client that differed (when [clientCheck] is DIFFERENT). */
    val clientDifferences: Int = 0,
    /** What the server's data is made from in the client: its MPQ archives' key (ClientSync.Result.dataKey). */
    val clientKey: String? = null,
    /** The torrent the client was last checked against (its info hash), and when. */
    val clientTorrent: String? = null,
    val clientCheckedAt: Long = 0L,
    /** The server's data in `data/`: made from the client with this [clientIdentity]. */
    val dataFrom: String? = null,
    val dataParts: Set<String> = emptySet(),
    val dbcCheck: ClientCheck = ClientCheck.NONE,
    /** OctoWoW's own DBC tables among the server's data (they differ from Turtle's 1.18.1 by design). */
    val dbcOwn: Int = 0,
    /** The step an install or repair was on when it last stopped without finishing, if one did. */
    val interruptedStep: String? = null,
    val updatedAt: Long = 0L,
) {
    enum class ClientCheck { NONE, EXACT, DIFFERENT }

    /** The client was last found to be exactly OctoWoW's (a check against Turtle WoW's list, before, doesn't count). */
    val clientIsOctoWow: Boolean get() = clientCheck == ClientCheck.EXACT && clientTorrent != null

    /** What the server's data has to be made from: the archives' key, or WoW.exe's hash for a client never checked. */
    val clientIdentity: String? get() = clientKey ?: clientExeSha256

    fun toJson(): String = JSONObject()
        .put("version", 1)
        .put("serverSha256", serverSha256 ?: JSONObject.NULL)
        .put("serverTag", serverTag ?: JSONObject.NULL)
        .put("databaseFrom", databaseFrom ?: JSONObject.NULL)
        .put("databaseUpdated", databaseUpdated)
        .put("clientExeSha256", clientExeSha256 ?: JSONObject.NULL)
        .put("clientCheck", clientCheck.name)
        .put("clientDifferences", clientDifferences)
        .put("clientKey", clientKey ?: JSONObject.NULL)
        .put("clientTorrent", clientTorrent ?: JSONObject.NULL)
        .put("clientCheckedAt", clientCheckedAt)
        .put("dataFrom", dataFrom ?: JSONObject.NULL)
        .put("dataParts", dataParts.sorted().joinToString(","))
        .put("dbcCheck", dbcCheck.name)
        .put("dbcOwn", dbcOwn)
        .put("interruptedStep", interruptedStep ?: JSONObject.NULL)
        .put("updatedAt", updatedAt)
        .toString(2)

    companion object {
        fun parse(text: String?): InstallState {
            if (text.isNullOrBlank()) return InstallState()
            return runCatching {
                val json = JSONObject(text)
                fun str(key: String) = json.optString(key, "").takeIf { it.isNotEmpty() && !json.isNull(key) }
                fun check(key: String) = runCatching { ClientCheck.valueOf(json.optString(key, "NONE")) }.getOrDefault(ClientCheck.NONE)
                InstallState(
                    serverSha256 = str("serverSha256"),
                    serverTag = str("serverTag"),
                    databaseFrom = str("databaseFrom"),
                    databaseUpdated = json.optBoolean("databaseUpdated", false),
                    clientExeSha256 = str("clientExeSha256"),
                    clientCheck = check("clientCheck"),
                    clientDifferences = json.optInt("clientDifferences", 0),
                    clientKey = str("clientKey"),
                    clientTorrent = str("clientTorrent"),
                    clientCheckedAt = json.optLong("clientCheckedAt", 0L),
                    dataFrom = str("dataFrom"),
                    dataParts = json.optString("dataParts", "").split(',').filter { it.isNotBlank() }.toSet(),
                    dbcCheck = check("dbcCheck"),
                    dbcOwn = json.optInt("dbcOwn", 0),
                    interruptedStep = str("interruptedStep"),
                    updatedAt = json.optLong("updatedAt", 0L),
                )
            }.getOrDefault(InstallState())
        }
    }
}

/**
 * The steps from nothing to Ready to Play, and where the phone is on them, from what's actually on
 * it ([Facts]) and the manifest. Plain logic: the launcher shows it, the installer follows it.
 */
object InstallPlan {
    enum class Step { CHECK, SERVER, DATABASE, CLIENT, DATA, ACCOUNT, READY }

    enum class Status {
        DONE,

        /** The app can do it by itself (Install does). */
        TODO,

        /** Needs the user: an account. */
        NEEDS_USER,

        /** Can't be done on this phone ([Facts.blocker] says why). */
        BLOCKED,

        /** Waits for an earlier step. */
        WAITING,
    }

    /** What the app found on the phone. */
    data class Facts(
        /** Why OctoWoW can't run on this phone at all (not arm64, not the phone's main user), or null. */
        val blocker: String? = null,
        val serverInstalled: Boolean,
        /** The installed package is the one this app version is made for. */
        val serverCurrent: Boolean,
        val databasesThere: Boolean,
        val clientThere: Boolean,
        val dataThere: Boolean,
        /** The data was made from the client that's there now. */
        val dataMatchesClient: Boolean,
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
        // OctoWoW's client: the app downloads it (Install does), or the user brings a copy.
        add(Step.CLIENT, if (facts.clientThere) Status.DONE else Status.TODO)
        add(
            Step.DATA,
            when {
                facts.dataThere && facts.dataMatchesClient -> Status.DONE
                !facts.clientThere || !facts.serverInstalled -> Status.WAITING
                else -> Status.TODO
            },
        )
        add(
            Step.ACCOUNT,
            when (facts.hasAccount) {
                true -> Status.DONE
                // The installer makes one (the user's choice of name and password) once the realm is up.
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
}
