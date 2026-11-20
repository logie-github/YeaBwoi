package com.logie.wifecraft.anim

import kotlin.test.*

class StageRulesTest {
    private fun part(name: String) = StageRules.parse(name)?.part
    private fun family(name: String) = StageRules.parse(name)?.family

    @Test
    fun readsThePartFromTheNameAlone() {
        assertEquals(StagePart.START, part("EA:a_money_makeItRain_start_x"))
        assertEquals(StagePart.LOOP, part("EA:a_money_makeItRain_loop_x"))
        assertEquals(StagePart.END, part("EA:a_money_makeItRain_stop_x"))
        assertEquals(family("EA:a_money_makeItRain_start_x"), family("EA:a_money_makeItRain_stop_x"))
        // a strong word wins over a weak one: "idle_start" is a start, "idle_loop" a loop, one family
        assertEquals(StagePart.START, part("WickedWhims_CrampsPain_idle_start_x"))
        assertEquals(family("WickedWhims_CrampsPain_idle_start_x"), family("WickedWhims_CrampsPain_idle_loop_x"))
        assertNotEquals(family("WickedWhims_CrampsPain_idle_start_x"), family("WickedWhims_CrampsPain_idle_start_seated_x"))
        // spaces and capitals
        assertEquals(StagePart.LOOP, part("wild_guy_Camera Recording Loop_x"))
        assertEquals(family("wild_guy_Camera Recording Start_x"), family("wild_guy_Camera Recording Stop_x"))
        // intro / idle, regular, animN / outro
        assertEquals(StagePart.START, part("a_floordancing_intro_y"))
        assertEquals(StagePart.LOOP, part("a_floordancing_anim3_y"))
        assertEquals(StagePart.END, part("a_floordancing_outro_y"))
        assertNotEquals(family("a_floordancing_intro_x"), family("a_floordancing_intro_y"), "each actor's clips apart")
        // a numbered stage's words are not the family's
        assertEquals(family("lb_female_job_start_stage_1"), family("lb_female_job_end_stage_3"))
        assertNotEquals(family("lb_female_job_end_stage_3"), family("lb_female_job_end_stage_3_caught"))
        // no word for a part: nothing
        assertNull(StageRules.parse("mike24:PosePack_202003280003327030_set_1"))
        assertNull(StageRules.parse("New_Default_Stage_2"))
        assertNull(StageRules.parse("weekend_walk"), "words are whole: weekend is not end")
        assertNull(StageRules.parse("loop"), "a part's word alone has no family")
    }

    @Test
    fun groupsAFamilyWithALoopAndAStartOrAnEnd() {
        val names = listOf(
            "pkg:hug_start", "pkg:hug_loop", "pkg:hug_end",                       // staged
            "dance_intro_x", "dance_idle_x", "dance_anim2_x", "dance_anim1_x", "dance_outro_x", // staged, loops in order
            "dance_intro_y", "dance_idle_y", "dance_outro_y",                      // the other actor's own
            "lonely_loop",                                                          // a loop alone: stays a clip
            "two_start", "two_end",                                                 // no loop: stays clips
            "twice_loop", "twice_end", "twice_stop",                                // two ends: stays clips
            "seq_stage_1", "seq_stage_2", "seq_stage_3",                            // numbered stages, no words: clips
            "set_pack_set_1", "set_pack_set_2",
        )
        val groups = StageRules.group(names, { it })
        val byFirst = groups.associateBy { it.members.first() }
        assertEquals(3, groups.size, groups.toString())
        val hug = assertNotNull(byFirst["pkg:hug_start"])
        assertEquals(listOf("pkg:hug_start", "pkg:hug_loop", "pkg:hug_end"), hug.members)
        assertEquals(listOf(StagePart.START, StagePart.LOOP, StagePart.END), hug.segments.map { it.first })
        val x = assertNotNull(byFirst["dance_intro_x"])
        assertEquals(listOf("dance_idle_x", "dance_anim1_x", "dance_anim2_x"), x.loops)
        assertEquals("dance_outro_x", x.end)
        assertEquals(listOf("dance_intro_y", "dance_idle_y", "dance_outro_y"), assertNotNull(byFirst["dance_intro_y"]).members)
    }

    @Test
    fun aScopeKeepsFamiliesApart() {
        val items = listOf("a" to "kiss_start", "a" to "kiss_loop", "b" to "kiss_end")
        // "b"'s end is not "a"'s: a's start and loop are one animation without an end
        val scoped = StageRules.group(items, { it.second }, { it.first }).single()
        assertNull(scoped.end)
        assertEquals(listOf("kiss_start", "kiss_loop"), scoped.members.map { it.second })
        assertNotNull(StageRules.group(items, { it.second }).single().end)
    }

    @Test
    fun theBrowserShowsAStagedAnimationAsOneEntry() {
        fun clip(name: String, seconds: Float = 2f) = CheckedAnimation(
            Anim(name, name, "", seconds, true, emptyList(), "src:$name", 0L, sourceName = name), ClipInspection(ClipType.LOOP))
        val clips = listOf(clip("statue_start", 5f), clip("statue_loop", 4f), clip("statue_end", 5f), clip("wave"))
        val choices = animationChoices(clips)
        assertEquals(2, choices.size, choices.map { it.name }.toString())
        val staged = choices.single { it.segments.isNotEmpty() }
        assertEquals(STAGED_PREFIX + "statue_loop", staged.id)
        assertEquals(listOf("statue_start", "statue_loop", "statue_end"), staged.segments.map { it.choice.id })
        assertEquals(listOf(StagePart.START, StagePart.LOOP, StagePart.END), staged.segments.map { it.part })
        assertEquals("statue_loop", staged.clips.single().clip.id, "it plays and is measured as its loop")
        assertEquals("Statue", staged.name)
        assertTrue(staged.playable)
        assertTrue(choices.none { it.id == "statue_start" || it.id == "statue_end" }, "the parts are hidden")
        // an author's numbered stages after the words: one animation, named without them
        val numbered = animationChoices(listOf(clip("lb_rub_start_stage_1"), clip("lb_rub_loop_stage_2"), clip("lb_rub_end_stage_3"),
            clip("lb_rub_end_stage_3_caught")))
        assertEquals(2, numbered.size)
        assertEquals("Lb rub", numbered.single { it.segments.isNotEmpty() }.name)
    }
}
