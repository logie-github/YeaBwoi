package com.logie.wifecraft.pack

import com.logie.wifecraft.anim.PairLocations
import com.logie.wifecraft.anim.StageNaming
import com.logie.wifecraft.bodyparts.ArousalMap
import com.logie.wifecraft.bodyparts.StageHints
import com.logie.wifecraft.simulation.PairKinds
import com.logie.wifecraft.sims.DbpfFile
import com.logie.wifecraft.sims.StageTuning
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every stage of the packages in WIFECRAFT_PACK_FILES as the app reads it: its category, people, the stage kind, the arousal map's
 * class for it, the intimacy the dialogue layer takes it for and the furniture classes its places become. Written to
 * WIFECRAFT_PACK_AUDIT as a table (the docs' stage table is made from it). Does nothing without the variable.
 */
class PackStageAuditTest {
    @Test fun auditsTheRealStages() {
        val dir = System.getenv("WIFECRAFT_PACK_FILES")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val map = ArousalMap.parse(File(System.getProperty("wifecraft.assets"), "arousal_map.json").readText())
        val out = StringBuilder("| Package | Stage | People | Category | Kind | Arousal | Intimacy | Furniture |\n|---|---|---:|---|---|---|---|---|\n")
        var stages = 0
        for (f in dir.walkTopDown().filter { it.isFile && it.name.endsWith(".package") }.sortedBy { it.name }) {
            val list = DbpfFile(f).use { StageTuning.stages(it) }
            for (s in list) {
                stages++
                val p = StageNaming.profile(s)
                val cls = map.classify(StageHints(listOf(s.name, s.stage) + s.actors.map { it.clip }, s.category, minOf(s.actors.size, 2)))
                val intimacy = PairKinds.of(s.name, s.category)
                val loc = PairLocations.normalize(s.locations).sorted().joinToString("/").ifEmpty { "anywhere" }
                out.append("| ${f.name.replace(Regex("^[0-9a-f]{8}-"), "").removeSuffix(".package")} | ${s.name} | ${s.actors.size}${if (p.playable) "" else " (not played)"} | ${s.category} | ${p.kind} | ${cls.category} ${cls.level} ${cls.entry} | $intimacy | $loc |\n")
            }
        }
        System.getenv("WIFECRAFT_PACK_AUDIT")?.let { File(it).writeText(out.toString()) }
        assertTrue(stages > 0)
    }
}
