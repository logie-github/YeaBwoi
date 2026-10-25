package com.logie.wifecraft.story

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StoryRunnerTest {
    private val script = Script.parse(
        """
        alleria +10
        scene krasus day
        cast alleria rail
        alleria [talk]: Hello, {name}.
        choice Well?
          > Kind words. | alleria +60
          > Rude words. | alleria -50 | goto rude
        end
        if alleria >= 60 goto romance
        :rude
        ending strangers
        :romance
        ending windrunner
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
        assertEquals(70, r.affection("alleria"))
        assertEquals(Step.Ending("windrunner"), r.next())
    }

    @Test
    fun affectionStaysBetweenNoneAndAll() {
        val r = StoryRunner(script, StoryState())
        repeat(4) { r.next() }
        r.choose(1)
        assertEquals(0, r.affection("alleria"))
        assertEquals(Step.Ending("strangers"), r.next())
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

    @Test
    fun standingsFollowAffection() {
        assertEquals("Neutral" to 0.5f, Standing.of(10))
        assertEquals("Friendly", Standing.of(20).first)
        assertEquals("Revered", Standing.of(65).first)
        assertEquals("Exalted" to 1f, Standing.of(100))
    }
}
