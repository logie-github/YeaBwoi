package com.logie.wifecraft.anim

import kotlin.test.*

class ImportedPerformancesTest {
    @Test
    fun pairsTheAuthoredRolesInOrderAndKeepsTheirOffsets() {
        val xml = """
            <I c="WickedWhimsAnimationPackage">
              <L n="animations_list"><U>
                <T n="animation_raw_display_name">Dance turn</T>
                <L n="animation_actors_list">
                  <U><T n="actor_id">1</T><T n="animation_clip_name">Follow</T><T n="animation_x_offset">1.25</T></U>
                  <U><T n="actor_id">0</T><T n="animation_clip_name">Lead</T></U>
                </L>
              </U></L>
            </I>
        """.trimIndent()
        val groups = ImportedPerformances.read(xml, "resource", mapOf("lead" to "a", "follow" to "b"))
        val group = groups.single()
        assertEquals(listOf("a", "b"), group.roles.map { it.source })
        assertEquals(1.25f, group.roles[1].offset.y)
        assertEquals(group, ImportedPerformance.fromJson(group.toJson()))
        assertEquals(listOf("a", ""), ImportedPerformances.read(xml, "resource", mapOf("lead" to "a")).single().roles.map { it.source })
    }

    @Test
    fun groupsNamedStagesAndFollowsTheirExplicitLinks() {
        fun stage(number: Int, next: Int) = """
            <U>
              <T n="animation_raw_display_name">Performance</T>
              <T n="animation_stage_name">Example_Stage_$number</T>
              <L n="animation_next_stages"><T>Example_Stage_$next</T></L>
              <T n="animation_loops">3</T>
              <L n="animation_actors_list"><U><T n="animation_clip_name">Clip_$number</T></U></L>
            </U>
        """.trimIndent()
        val xml = """<I c="WickedWhimsAnimationPackage"><L n="animations_list">
            ${stage(10, 2)}${stage(2, 10)}${stage(2, 10)}
            </L></I>"""
        val groups = ImportedPerformances.read(xml, "resource", mapOf("clip_2" to "a", "clip_10" to "b"))
        val clips = listOf("a", "b").map { source ->
            val clip = Anim(id = source, name = source, category = "", duration = 1f, loop = false, tracks = emptyList(),
                source = source, importedAt = 0L, performances = groups.filter { it.roles.single().source == source })
            CheckedAnimation(clip, ClipInspection(ClipType.ONE_SHOT))
        }
        val choices = animationChoices(clips)
        assertEquals(listOf("Stage 2 · Performance", "Stage 10 · Performance"), choices.map { it.name })
        assertTrue(choices.all { it.group == "Example sequence" && it.repetitions == 3 })
        assertEquals(choices, animationSequence(choices.first(), choices))
        assertEquals(groups.first(), ImportedPerformance.fromJson(groups.first().toJson()))
    }
}
