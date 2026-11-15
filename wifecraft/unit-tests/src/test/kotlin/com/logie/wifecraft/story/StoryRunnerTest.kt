package com.logie.wifecraft.story

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StoryRunnerTest {
    private val script = Script.parse(
        """
        scene studio day
        cast p_mira center
        p_mira [talk]: Hello, {name}.
        choice Well?
          > Kind words. | set kind
          > Rude words. | goto rude
        end
        if flag kind goto warm
        :rude
        ending cold_end The Cold End
        :warm
        ending warm_end
        """.trimIndent(),
    )

    @Test
    fun walksThroughLinesChoicesAndEndings() {
        val r = StoryRunner(script, StoryState()) { "Aelthas" }
        assertIs<Step.Stage>(r.next())    // scene
        assertIs<Step.Stage>(r.next())    // cast
        val line = assertIs<Step.Line>(r.next())
        assertEquals("Hello, Aelthas.", line.text)
        val choice = assertIs<Step.Choose>(r.next())
        assertEquals(listOf("Kind words.", "Rude words."), choice.options)
        // Until chosen, the story stays on the choice.
        assertIs<Step.Choose>(r.next())
        r.choose(0)
        assertEquals(Step.Ending("warm_end", "warm_end"), r.next())
    }

    @Test
    fun anyEndingIsPlayedWithItsTitle() {
        val r = StoryRunner(script, StoryState())
        repeat(4) { r.next() }
        r.choose(1)
        assertEquals(Step.Ending("cold_end", "The Cold End"), r.next())
    }

    @Test
    fun keepsTheStageSinceTheLastCut() {
        val r = StoryRunner(script, StoryState())
        repeat(3) { r.next() }
        val stage = r.stageCommands()
        assertIs<Command.Scene>(stage[0])
        assertIs<Command.Cast>(stage[1])
        assertTrue(r.state.log.single().text.startsWith("Hello"))
    }
}
