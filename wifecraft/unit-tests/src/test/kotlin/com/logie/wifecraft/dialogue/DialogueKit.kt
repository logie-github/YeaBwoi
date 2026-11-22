package com.logie.wifecraft.dialogue

import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.random.Random

/** Test helpers: the real lore pack, a scripted engine and one that answers when told, scene views, a sink that records. */
object Kit {
    val lore: Lore by lazy { Lore(File(System.getProperty("wifecraft.assets"), "dialogue/lore.json").readText()) }

    const val HUMAN = 1; const val ORC = 2; const val DWARF = 3; const val NIGHTELF = 4; const val UNDEAD = 5; const val TAUREN = 6
    const val GNOME = 7; const val TROLL = 8; const val BLOODELF = 10; const val WORGEN = 22

    fun npc(id: String, race: Int, female: Boolean? = true, cls: Int = 0, traits: List<String> = emptyList(), bio: String = "", name: String = id.replaceFirstChar { it.uppercase() }) =
        NpcProfile(id, name, race, female, cls, traits, bio, Disposition.of(traits, bio))

    fun person(p: NpcProfile, doing: String = "standing about", withId: String? = null, conversation: Boolean = false, leads: Boolean = false,
        stage: Boolean = false, sexual: Boolean = false, stageName: String = "", dist: Float = 5f, selected: Boolean = false) =
        PersonView(p, doing, withId, conversation, leads, stage, sexual, stageName, false, dist, selected)

    fun view(vararg people: PersonView, cinematic: Boolean = false, place: PlaceView = PlaceView("A House in Brill, Tirisfal", true, 21.0, "light rain", listOf("a double bed", "a hearth")),
        relation: (String, String) -> Relation = { _, _ -> Relation() }) = SceneView(place, people.toList(), cinematic, relation)

    fun pairInConversation(a: NpcProfile, b: NpcProfile, cinematic: Boolean = false) =
        view(person(a, "talking with ${b.first}", b.id, conversation = true, leads = true), person(b, "talking with ${a.first}", a.id, conversation = true), cinematic = cinematic)

    fun pairInStage(a: NpcProfile, b: NpcProfile, sexual: Boolean = true, name: String = "Passionate Missionary", cinematic: Boolean = false,
        relation: (String, String) -> Relation = { _, _ -> Relation() }) =
        view(person(a, "in $name", b.id, stage = true, sexual = sexual, stageName = name), person(b, "in $name", a.id, stage = true, sexual = sexual, stageName = name),
            cinematic = cinematic, relation = relation)

    fun run(d: DialogueDirector, v: SceneView, seconds: Float, dt: Float = 0.25f) { var t = 0f; while (t < seconds) { d.update(dt, v); t += dt } }
}

class Said { val lines = ArrayList<Pair<String, String>>(); val sink = SpeechSink { id, t -> lines += id to t; true } }

/** Answers by a function, at once. Records every request. */
class ScriptedEngine(val answer: (DialogueRequest) -> String?) : DialogueEngine {
    val calls = ArrayList<DialogueRequest>()
    override suspend fun complete(req: DialogueRequest): String? { calls += req; return answer(req) }
}

/** Holds each call until [finish] is called: for the one-call-at-a-time budget. */
class ManualEngine : DialogueEngine {
    val calls = ArrayList<DialogueRequest>()
    private val waiting = ArrayDeque<Continuation<String?>>()
    var running = 0; var maxRunning = 0
    override suspend fun complete(req: DialogueRequest): String? {
        calls += req; running++; maxRunning = maxOf(maxRunning, running)
        return suspendCoroutine { waiting.addLast(it) }
    }
    fun finish(text: String?) { running--; waiting.removeFirst().resume(text) }
    val pendingCount get() = waiting.size
}

val FOURTH_WALL = listOf("game", "player", "menu", "screen", "npc", "simulation", "cinematic", "camera", "prompt", "language model", "artificial", "chatbot", "watched", "observed")
