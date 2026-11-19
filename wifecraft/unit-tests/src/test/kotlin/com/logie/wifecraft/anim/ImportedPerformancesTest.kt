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
}
