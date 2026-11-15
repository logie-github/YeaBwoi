package com.logie.wifecraft.story

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScriptTest {
    private val sample = """
        # a comment
        set met
        scene street dusk
        cast p_mira left face camera
        walk p_mira center 2500
        run p_bob right
        shot bust p_mira 800
        shot two p_mira p_bob
        title Chapter I | Morning
        p_mira [laugh]: Hello, {name}.
        narrator: The city glows.
        choice Where now?
          > Stay. | set stayed | goto stay
          > Go. | set left | goto go
        end
        :stay
        if flag stayed goto go
        :go
        if flag left goto end
        ending a_kind_end A Kind End
        :end
        finish
    """.trimIndent()

    @Test
    fun readsEveryKindOfCommand() {
        val s = Script.parse(sample)
        val c = s.commands
        assertEquals(Command.SetFlag("met", 2), c[0])
        assertEquals(Command.Scene("street", "dusk", 3), c[1])
        assertEquals(Command.Cast("p_mira", "left", "camera", 4), c[2])
        assertEquals(Command.Move("p_mira", "center", 2500, false, 5), c[3])
        assertEquals(Command.Move("p_bob", "right", 2000, true, 6), c[4])
        assertEquals(Command.Shot("bust", listOf("p_mira"), 800, 7), c[5])
        assertEquals(Command.Shot("two", listOf("p_mira", "p_bob"), 0, 8), c[6])
        assertEquals(Command.Title("Chapter I", "Morning", 9), c[7])
        assertEquals(Command.Say("p_mira", "laugh", "Hello, {name}.", 10), c[8])
        assertEquals(Command.Say("narrator", null, "The city glows.", 11), c[9])
        val choice = assertIs<Command.Choice>(c[10])
        assertEquals("Where now?", choice.prompt)
        assertEquals(listOf(Effect.SetFlag("stayed"), Effect.Goto("stay")), choice.options[0].effects)
        assertEquals(listOf(Effect.SetFlag("left"), Effect.Goto("go")), choice.options[1].effects)
        assertEquals(Command.If(Condition.Flag("stayed"), "go", 17), c[12])
        assertEquals(Command.If(Condition.Flag("left"), "end", 19), c[14])
        assertEquals(Command.Ending("a_kind_end", "A Kind End", 20), c[15])
    }

    @Test
    fun anEndingWithoutATitleIsItsId() {
        assertEquals(Command.Ending("strangers", "strangers", 1), Script.parse("ending strangers").commands[0])
    }

    @Test
    fun affectionIsGone() {
        // The demo's reputations went with it: "who +n" and comparisons are no longer commands.
        assertFailsWith<ScriptException> { Script.parse("mira +10") }
        assertFailsWith<ScriptException> { Script.parse(":x\nif mira >= 10 goto x") }
    }

    @Test
    fun readsMusicAndSounds() {
        val c = Script.parse("music day 2000\nmusic off\nmusic dawn\nsound click").commands
        assertEquals(Command.Music("day", 2000, 1), c[0])
        assertEquals(Command.Music("off", 1500, 2), c[1])
        assertEquals(Command.Music("dawn", 1500, 3), c[2])
        assertEquals(Command.Sound("click", 4), c[3])
        // The runner remembers the music playing, and "off" is none.
        val state = StoryState()
        val r = StoryRunner(Script(c), state)
        r.next(); assertEquals("day", state.music)
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
        val e = assertFailsWith<ScriptException> { Script.parse("scene studio\n\nthis is not a command") }
        assertTrue(e.message!!.startsWith("Line 3"), e.message)
    }
}
