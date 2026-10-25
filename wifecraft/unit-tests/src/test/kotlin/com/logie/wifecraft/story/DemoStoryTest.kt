package com.logie.wifecraft.story

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Plays the demo story (assets/story/demo.wfs) through every choice, and checks that every place,
 * mark, view, person and emote it names exists, and that every path ends in one of its endings.
 */
class DemoStoryTest {
    private val assets = File(System.getProperty("wifecraft.assets") ?: "../android/app/src/main/assets")
    private val script = Script.parse(File(assets, "story/demo.wfs").readText())
    private val places = JSONObject(File(assets, "places.json").readText()).getJSONObject("places")
    private val looks = JSONObject(File(assets, "looks.json").readText())

    private val people = setOf("alleria", "nyrelle", "saber", "you")
    private val speakers = people + "narrator"
    // As Emotes.byName in the app.
    private val emotes = setOf(
        "stand", "talk", "exclaim", "question", "bow", "wave", "cheer", "dance", "laugh", "rude", "roar", "kneel",
        "kiss", "cry", "beg", "applaud", "shout", "flex", "shy", "point", "salute", "yes", "no",
    )
    private val endings = setOf("windrunner", "arrows", "moonlight", "saber", "strangers")

    private fun copy(s: StoryState) = s.copy(
        affection = s.affection.toMutableMap(), flags = s.flags.toMutableSet(),
        stage = s.stage.toMutableList(), log = s.log.toMutableList(),
    )

    @Test
    fun everyPathEndsAndEverythingItNamesExists() {
        val reached = mutableSetOf<String>()
        var paths = 0
        // A branch: the story's state, the place, and who is on stage.
        val todo = ArrayDeque<Triple<StoryState, String, Set<String>>>()
        todo += Triple(StoryState(), "", emptySet())
        while (todo.isNotEmpty()) {
            val (state, startPlace, startCast) = todo.removeFirst()
            val r = StoryRunner(script, state) { "Tester" }
            var place = startPlace
            val cast = startCast.toMutableSet()
            loop@ while (true) {
                when (val step = r.next()) {
                    is Step.Stage -> {
                        val c = step.command
                        val at = "line ${c.line}"
                        when (c) {
                            is Command.Scene -> {
                                assertTrue(places.has(c.place), "$at: no place ${c.place}")
                                assertTrue(c.light in setOf("day", "dusk", "night"), "$at: light ${c.light}")
                                place = c.place
                                cast.clear()
                            }
                            is Command.Cast -> {
                                assertTrue(c.who in people, "$at: who is ${c.who}?")
                                assertTrue(marks(place).has(c.mark), "$at: no mark ${c.mark} in $place")
                                c.facing?.let { f -> assertTrue(f == "camera" || f.toFloatOrNull() != null || f in people || marks(place).has(f), "$at: face $f?") }
                                cast += c.who
                            }
                            is Command.Move -> {
                                assertTrue(c.who in cast, "$at: ${c.who} isn't on stage")
                                assertTrue(marks(place).has(c.mark), "$at: no mark ${c.mark} in $place")
                            }
                            is Command.Face -> {
                                assertTrue(c.who in cast, "$at: ${c.who} isn't on stage")
                                assertTrue(c.target == "camera" || c.target in cast || marks(place).has(c.target), "$at: face ${c.target}?")
                            }
                            is Command.Emote -> {
                                assertTrue(c.who in cast, "$at: ${c.who} isn't on stage")
                                assertTrue(c.emote in emotes, "$at: no emote ${c.emote}")
                            }
                            is Command.Remove -> cast -= c.who
                            is Command.Shot -> when (c.kind) {
                                "view" -> assertTrue(views(place).has(c.args.single()), "$at: no view ${c.args} in $place")
                                "bust" -> assertTrue(c.args.single() in cast, "$at: ${c.args} isn't on stage")
                                "two" -> assertTrue(c.args.size == 2 && c.args.all { it in cast }, "$at: ${c.args} aren't both on stage")
                                "wide" -> Unit
                                else -> fail("$at: no shot ${c.kind}")
                            }
                            else -> Unit
                        }
                    }
                    is Step.Line -> {
                        val c = script.commands[step.index] as Command.Say
                        assertTrue(c.who in speakers, "line ${c.line}: who is ${c.who}?")
                        c.emote?.let { assertTrue(it in emotes, "line ${c.line}: no emote $it") }
                        assertTrue(c.who == "narrator" || c.who == "you" || c.who in cast, "line ${c.line}: ${c.who} speaks off stage")
                        assertTrue("{name}" !in step.text)
                        // Three rows of the talking-head box at most.
                        assertTrue(step.text.length <= 150, "line ${c.line} is ${step.text.length} letters: too long for the box")
                    }
                    is Step.Choose -> {
                        for (i in step.options.indices.drop(1)) {
                            val branch = copy(r.state)
                            StoryRunner(script, branch).choose(i)
                            todo += Triple(branch, place, cast.toSet())
                        }
                        r.choose(0)
                    }
                    is Step.Ending -> {
                        assertTrue(step.id in endings, "unknown ending ${step.id}")
                        reached += step.id
                        paths++
                        break@loop
                    }
                    Step.Finished -> fail("a path ends without an ending")
                    is Step.Pause -> Unit
                }
            }
        }
        assertEquals(endings, reached, "every ending can be reached")
        assertTrue(paths > 20, "$paths paths")
    }

    @Test
    fun everyPersonHasALook() {
        for (key in listOf("alleria", "nyrelle", "saber", "player_man", "player_woman")) {
            val l = looks.getJSONObject(key)
            assertTrue(l.getInt("model") > 0, key)
        }
    }

    private fun marks(place: String) = places.getJSONObject(place).getJSONObject("marks")
    private fun views(place: String) = places.getJSONObject(place).getJSONObject("views")
}
