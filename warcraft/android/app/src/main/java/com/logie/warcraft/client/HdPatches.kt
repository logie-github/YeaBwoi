// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.client

/**
 * The HD looks the launcher offers, as ChromieCraft's downloads page lists them, and which of their
 * patch files go into the client. Each comes from a zip on the web, of which only these files are
 * downloaded.
 */
object HdPatches {
    enum class Choice { OFF, CHROMIECRAFT, ALTERNATIVE }

    class Source(
        val url: String,
        /** The page a browser comes from; the server only sends the file to a browser that came from there. */
        val referer: String?,
        val files: List<File>,
    )

    class File(
        /** The end of its name in the zip (any case, with or without the patch menu's ".disabled"). */
        val entry: String,
        /** Its name in the client's `Data` folder; [localized] ones go in the language's folder. */
        val name: String,
        val localized: Boolean,
    ) {
        /** Its path in the client, for the client's language ("enUS"). */
        fun target(locale: String): String =
            if (localized) "Data/$locale/" + name.replace("{locale}", locale) else "Data/$name"
    }

    /** ChromieCraft's "HD Patch for 3.3.5a": additional_patches_for_335a.zip and its readme. */
    val CHROMIECRAFT = Source(
        url = "https://btground.dedyn.io/chmi/additional_patches_for_335a.zip",
        referer = "https://chromiecraft.com/",
        files = listOf(
            // Characters, Warlords of Draenor NPC models, goblin textures.
            File("Patch-H.MPQ", "patch-H.MPQ", localized = false),
            // Mounts and creatures (with their display tables).
            File("Patch-F.MPQ", "patch-F.MPQ", localized = false),
            File("Patch-G.MPQ", "patch-G.MPQ", localized = false),
            // Environment and city textures.
            File("Patch-T.MPQ", "patch-T.MPQ", localized = false),
            // Better sunlight.
            File("Patch-S.MPQ", "patch-S.MPQ", localized = false),
            // Patch-X (Cataclysm trees) stays out: its readme says it puts lower-resolution tree
            // textures over Patch-T's.
        ),
    )

    /**
     * ChromieCraft's "Alternative HD Patch": the community's HD client (stable, English), of which
     * the patches its patch menu turns on by default for the look of the game. Left out: its login
     * and realm screens (the app logs in on the phone's own realm), its character creation screen,
     * its interface and cursor, its add-ons, and the patches it keeps off itself.
     */
    val ALTERNATIVE = Source(
        url = "https://drive.usercontent.google.com/download?id=1QF5Vn8yaQkegTKsZ4boz4AU8rJi6_mHF&export=download&confirm=t",
        referer = null,
        files = listOf(
            // HD characters (models, textures, and their character tables).
            File("data/patch-a.mpq", "patch-A.MPQ", localized = false),
            File("data/enus/patch-enus-a.mpq", "patch-{locale}-A.MPQ", localized = true),
            // HD models and NPCs (with the creature display tables).
            File("data/patch-f.mpq", "patch-F.MPQ", localized = false),
            File("data/enus/patch-enus-f.mpq", "patch-{locale}-F.MPQ", localized = true),
            // HD textures of the world.
            File("data/patch-6.mpq", "patch-6.MPQ", localized = false),
            // New spell effects (with the SoundEntries table), not its Spell table.
            File("data/patch-s.mpq", "patch-S.MPQ", localized = false),
            // New water.
            File("data/patch-w.mpq", "patch-W.MPQ", localized = false),
            File("data/enus/patch-enus-w.mpq", "patch-{locale}-W.MPQ", localized = true),
            // New sky.
            File("data/patch-x.mpq", "patch-X.MPQ", localized = false),
            File("data/enus/patch-enus-x.mpq", "patch-{locale}-X.MPQ", localized = true),
            // New music.
            File("data/patch-e.mpq", "patch-E.MPQ", localized = false),
            // Loading screens.
            File("data/enus/patch-enus-q.mpq", "patch-{locale}-Q.MPQ", localized = true),
        ),
    )

    fun source(choice: Choice): Source? = when (choice) {
        Choice.OFF -> null
        Choice.CHROMIECRAFT -> CHROMIECRAFT
        Choice.ALTERNATIVE -> ALTERNATIVE
    }

    /** The files [choice] puts in the client. */
    fun files(choice: Choice): List<File> = source(choice)?.files.orEmpty()

    /** [file]'s entry among a zip's. */
    fun find(entries: List<RemoteZip.Entry>, file: File): RemoteZip.Entry? {
        val end = file.entry.lowercase()
        return entries.firstOrNull { entry ->
            val name = entry.name.lowercase().removeSuffix(".disabled")
            name == end || name.endsWith("/$end")
        }
    }
}
