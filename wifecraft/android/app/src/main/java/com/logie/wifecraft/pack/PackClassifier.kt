package com.logie.wifecraft.pack

import com.logie.wifecraft.sims.DbpfFile
import com.logie.wifecraft.sims.SimsFormatException
import com.logie.wifecraft.sims.Stage
import com.logie.wifecraft.sims.StageTuning
import java.io.File

/** What a Sims 4 package holds, by its resources: counts by type, its tuning's stages, its objects and its audio. */
class PackContent(
    val resources: Int,
    val typeCounts: Map<Int, Int>,
    val stages: List<Stage>,
    /** The object ids (OBJD instances) it defines. */
    val objects: Set<Long>,
) {
    fun count(type: Int): Int = typeCounts[type] ?: 0
    val clips: Int get() = count(T_CLIP)
    val models: Int get() = count(T_MODL)
    val audio: Int get() = count(T_SNR)

    /** The object ids its own stages' props name. */
    val propRefs: Set<Long> get() = stages.flatMapTo(HashSet()) { s -> s.props.flatMap { it.guids } }

    /** Whether one of its stages names an actor's clip (a pose pack's single poses and a purchase menu's tuning do not). */
    val hasActorClips: Boolean get() = stages.any { s -> s.actors.any { it.clip.isNotEmpty() } }

    companion object {
        const val T_CLIP = 0x6B20C4F3
        const val T_OBJD = 0xC0DB5AE7.toInt()
        const val T_MODL = 0x01661233
        const val T_MLOD = 0x01D10F34
        const val T_RIG = 0x8EAF13DE.toInt()
        const val T_SNR = 0x01A527DB
    }
}

/**
 * Tells what a package is from its resources, never from its file name (`tools/pack_inventory.py`'s `classify` is the same rule):
 *  - objects (OBJD with a model): [PackKind.PROPS] when its own stages name them, [PackKind.BODY_LAYER] when only another
 *    package's stages in the pack do (an animation-linked layer such as the cum mesh), [PackKind.FURNITURE] when no stage does;
 *  - animation clips with a WickedWhims tuning that names actor clips: [PackKind.ANIMATIONS] (also when it names props);
 *  - anything else: [PackKind.UNUSED].
 */
object PackClassifier {
    /** [file]'s content; [SimsFormatException] when it is not a Sims 4 package. */
    fun read(file: File): PackContent = DbpfFile(file).use { pkg ->
        val counts = HashMap<Int, Int>()
        val objects = LinkedHashSet<Long>()
        for (e in pkg.entries) {
            counts.merge(e.type, 1, Int::plus)
            if (e.type == PackContent.T_OBJD) objects += e.instance
        }
        PackContent(pkg.entries.size, counts, StageTuning.stages(pkg), objects)
    }

    fun kindOf(c: PackContent, refsElsewhere: Set<Long>): PackKind {
        if (c.count(PackContent.T_OBJD) > 0 && c.models > 0) {
            if (c.objects.any { it in c.propRefs }) return PackKind.PROPS
            if (c.objects.any { it in refsElsewhere }) return PackKind.BODY_LAYER
            return PackKind.FURNITURE
        }
        if (c.clips > 0 && c.hasActorClips) return PackKind.ANIMATIONS
        return PackKind.UNUSED
    }
}
