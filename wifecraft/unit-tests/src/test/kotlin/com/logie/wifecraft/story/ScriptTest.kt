package com.logie.wifecraft.story

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScriptTest {
    private val sample = """
        # a comment
        alleria +10
        scene krasus dusk
        cast alleria rail face you
        walk you near 2500
        run saber past
        shot bust alleria 800
        shot two alleria you
        title Chapter I | Morning
        alleria [laugh]: Hello, {name}.
        narrator: The city glows.
        choice Where now?
          > Stay. | alleria +5 | goto stay
          > Go. | set left | goto go
        end
        :stay
        if alleria >= 15 goto go
        :go
        if flag left goto end
        ending windrunner
        :end
        finish
    """.trimIndent()

    @Test
    fun readsEveryKindOfCommand() {
        val s = Script.parse(sample)
        val c = s.commands
        assertEquals(Command.Affect("alleria", 10, 2), c[0])
        assertEquals(Command.Scene("krasus", "dusk", 3), c[1])
        assertEquals(Command.Cast("alleria", "rail", "you", 4), c[2])
        assertEquals(Command.Move("you", "near", 2500, false, 5), c[3])
        assertEquals(Command.Move("saber", "past", 2000, true, 6), c[4])
        assertEquals(Command.Shot("bust", listOf("alleria"), 800, 7), c[5])
        assertEquals(Command.Shot("two", listOf("alleria", "you"), 0, 8), c[6])
        assertEquals(Command.Title("Chapter I", "Morning", 9), c[7])
        assertEquals(Command.Say("alleria", "laugh", "Hello, {name}.", 10), c[8])
        assertEquals(Command.Say("narrator", null, "The city glows.", 11), c[9])
        val choice = assertIs<Command.Choice>(c[10])
        assertEquals("Where now?", choice.prompt)
        assertEquals(listOf(Effect.Affect("alleria", 5), Effect.Goto("stay")), choice.options[0].effects)
        assertEquals(listOf(Effect.SetFlag("left"), Effect.Goto("go")), choice.options[1].effects)
        assertEquals(Command.If(Condition.Compare("alleria", ">=", 15), "go", 17), c[12])
        assertEquals(Command.If(Condition.Flag("left"), "end", 19), c[14])
    }

    @Test
    fun readsMusicAndSounds() {
        val c = Script.parse("music alleria 2000\nmusic off\nmusic dawn\nsound saber_roar").commands
        assertEquals(Command.Music("alleria", 2000, 1), c[0])
        assertEquals(Command.Music("off", 1500, 2), c[1])
        assertEquals(Command.Music("dawn", 1500, 3), c[2])
        assertEquals(Command.Sound("saber_roar", 4), c[3])
        // The runner remembers the music playing (for saves), and "off" is none.
        val state = StoryState()
        val r = StoryRunner(Script(c), state)
        r.next(); assertEquals("alleria", state.music)
        r.next(); assertEquals("", state.music)
        r.next(); assertEquals("dawn", state.music)
        assertIs<Step.Stage>(r.next())
    }

    @Test
    fun refusesJumpsToNowhere() {
        val e = assertFailsWith<ScriptException> { Script.parse("goto nowhere") }
        assertTrue("nowhere" in e.message!!)
    }

    @Test
    fun reportsTheLineOfAMistake() {
        val e = assertFailsWith<ScriptException> { Script.parse("scene krasus\n\nthis is not a command") }
        assertTrue(e.message!!.startsWith("Line 3"), e.message)
    }
}
