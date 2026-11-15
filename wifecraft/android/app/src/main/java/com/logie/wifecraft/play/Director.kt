package com.logie.wifecraft.play

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.logie.wifecraft.data.Emotes
import com.logie.wifecraft.data.Lights
import com.logie.wifecraft.data.Settings
import com.logie.wifecraft.engine.Clock
import com.logie.wifecraft.engine.Placement
import com.logie.wifecraft.engine.Stage
import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Command
import com.logie.wifecraft.story.Script
import com.logie.wifecraft.story.Shots
import com.logie.wifecraft.story.Step
import com.logie.wifecraft.story.StoryRunner
import com.logie.wifecraft.story.StoryState
import com.logie.wifecraft.story.Vec3
import com.logie.wifecraft.ui.PlaceInfo
import com.logie.wifecraft.ui.Staging
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin

/**
 * Plays a story (the editor's project compiled to a script: editor/Compile): runs the script,
 * stages it in the 3D scene (people, their walks and emotes, the camera's moves and shakes) and
 * holds what the screen shows over it (the line being said, a choice, a chapter card, fades, the
 * letterbox, an ending). [people] are the script's names (`p_...`) with the name the talking head
 * shows; [dress] puts that person's saved look on the stage under the same key. Any ending id is
 * played: its title is the script's. Lives on the main thread; [tick] is called every frame.
 */
class Director(
    private val script: Script,
    private val places: Map<String, PlaceInfo>,
    private val people: Map<String, String>,
    private val settings: Settings,
    private val scene: Stage,
    private val sounds: Sounds = Sounds.None,
    private val dress: (key: String) -> Unit,
    private val onFinished: () -> Unit,
) {
    data class Line(val who: String, val name: String, val text: String, val index: Int, val shownAt: Int, val seen: Boolean) {
        /** When a tap showed the whole line (it then stays whole), or -1 while it's still typing. */
        var completedAt = -1
    }
    data class Choice(val prompt: String, val options: List<String>)

    var line by mutableStateOf<Line?>(null); private set
    var choice by mutableStateOf<Choice?>(null); private set
    var title by mutableStateOf<Pair<String, String>?>(null); private set
    var titleShownAt = 0; private set
    /** The ending reached: its title. */
    var ending by mutableStateOf<String?>(null); private set
    var letterbox by mutableStateOf(false); private set
    var fade by mutableFloatStateOf(1f); private set
    var loading by mutableStateOf(true); private set
    var revealed by mutableStateOf(0); private set
    var placeName by mutableStateOf(""); private set

    lateinit var runner: StoryRunner
        private set

    private class Actor(val key: String, var pos: Vec3, var facing: Float) {
        var from: Vec3 = pos
        var to: Vec3 = pos
        var moveStart = 0
        var moveMs = 0
        var visible = true
    }

    private val actors = mutableMapOf<String, Actor>()
    private var place = places.values.firstOrNull() ?: PlaceInfo.STUDIO
    private var camera = CameraPose(Vec3(0f, -10f, 3f), Vec3(0f, 0f, 1f), 45f)
    private var camFrom = camera
    private var camTo = camera
    private var camStart = 0
    private var camMs = 0
    private var shakeUntil = 0
    private var waitUntil = 0
    private var fadeFrom = 1f
    private var fadeTo = 1f
    private var fadeStart = 0
    private var fadeMs = 0
    private var fastForward = false
    private val cityReady = AtomicBoolean(false)
    private val seen = mutableSetOf<String>()

    /** The story from its beginning (the script's first command: Compile starts it at the scene asked for). */
    fun begin() {
        runner = StoryRunner(script, StoryState()) { "You" }
        // Whoever was on stage before (the editor's preview) goes; the city is loaded only when a scene is in it.
        scene.removeAll()
        fade = 1f; fadeFrom = 1f; fadeTo = 0f; fadeStart = Clock.now(); fadeMs = 900
        val inCity = script.commands.any { it is Command.Scene && places[it.place]?.void == false }
        if (!inCity || scene.cityLoaded.get()) { cityReady.set(true); return }
        val centres = Staging.allMarks(places.values.toList()).flatMap { listOf(it.x, it.y, it.z) }.toFloatArray()
        scene.loadCity(centres) { cityReady.set(true) }
    }

    // ------------------------------------------------------------------ input

    /** A tap: the whole line, then the next one; skips a chapter card. */
    fun tap() {
        val now = Clock.now()
        val l = line
        if (l != null) {
            if (revealed < l.text.length) {
                l.completedAt = now
                revealed = l.text.length
            } else if (l.completedAt < 0 || now - l.completedAt > 180) {
                // (a quick second tap is the same tap landing twice: it doesn't skip the line unread)
                advanceLine()
            }
            return
        }
        if (title != null && now - titleShownAt > 400) {
            title = null
            waitUntil = now
        }
    }

    /** Held: lines already read go by quickly, and waits are cut short. */
    fun hold(on: Boolean) {
        fastForward = on
    }

    fun choose(option: Int) {
        if (choice == null) return
        choice = null
        sounds.ui("click")
        runner.choose(option)
    }

    fun endingDone() {
        ending = null
        onFinished()
    }

    private fun advanceLine() {
        line?.let { seen += it.index.toString() }
        line = null
    }

    // ------------------------------------------------------------------ each frame

    fun tick() {
        val now = Clock.now()
        if (loading) {
            if (cityReady.get()) loading = false else { pushFrame(now); return }
        }
        // Fades.
        if (fadeMs > 0) {
            val t = ((now - fadeStart).toFloat() / fadeMs).coerceIn(0f, 1f)
            fade = fadeFrom + (fadeTo - fadeFrom) * t
            if (t >= 1f) fadeMs = 0
        }
        // Words appearing, and fast-forward through read lines.
        line?.let { l ->
            val speed = settings.textSpeed
            revealed = if (speed <= 0 || speed >= 120 || l.completedAt >= 0) l.text.length
            else ((now - l.shownAt) * speed / 1000).coerceAtMost(l.text.length)
            if (revealed >= l.text.length && l.completedAt < 0) l.completedAt = now
            if (fastForward && l.seen && now - l.shownAt > 90) advanceLine()
        }
        if (title != null && now - titleShownAt > 2800) title = null
        // The story runs on until something holds it.
        var guard = 0
        while (line == null && choice == null && ending == null && guard++ < 64) {
            if (now < waitUntil && !(fastForward && waitUntil - now < 6000)) break
            if (title != null) break
            when (val step = runner.next()) {
                is Step.Stage -> stage(step.command, now, instant = false)
                is Step.Pause -> pause(step.command, now)
                is Step.Line -> say(step, now)
                is Step.Choose -> {
                    choice = Choice(step.prompt, step.options)
                    sounds.ui("gossip")
                }
                is Step.Ending -> {
                    ending = step.title.ifBlank { step.id }
                    sounds.ui("ending")
                }
                Step.Finished -> {
                    onFinished()
                    return
                }
            }
        }
        pushFrame(now)
    }

    private fun say(step: Step.Line, now: Int) {
        val name = nameOf(step.who)
        if (step.who in actors) {
            val anim = step.emote?.let { Emotes.byName[it] } ?: Emotes.TALK
            postAnim(step.who, anim, loop = false, now)
        }
        step.emote?.let { voice(step.who, it) }
        revealed = 0
        line = Line(step.who, name, step.text, step.index, now, step.index.toString() in seen)
    }

    private fun pause(c: Command, now: Int) {
        when (c) {
            is Command.Fade -> {
                fadeFrom = fade; fadeTo = if (c.toBlack) 1f else 0f
                fadeStart = now; fadeMs = maxOf(1, if (fastForward) c.ms / 4 else c.ms)
                waitUntil = now + fadeMs
                if (c.ms == 0) { fade = fadeTo; fadeMs = 0 }
            }
            is Command.Wait -> waitUntil = now + if (fastForward) c.ms / 4 else c.ms
            is Command.Title -> {
                title = c.heading to c.sub
                titleShownAt = now
                waitUntil = now + 2800
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ the stage

    private fun stage(c: Command, now: Int, instant: Boolean) {
        when (c) {
            is Command.Scene -> {
                for (key in actors.keys) scene.removeActor(key)
                actors.clear()
                place = places[c.place] ?: place
                placeName = place.name
                scene.light(Lights.of(c.light))
                scene.showCity(!place.void)
                sounds.ambience(if (place.void) "" else if (c.light == "night") "night" else "day")
                place.views.values.firstOrNull()?.let { cut(it) }
            }
            is Command.Cast -> {
                val pos = place.marks[c.mark] ?: return
                val a = actors.getOrPut(c.who) { addActor(c.who, pos) }
                a.pos = pos; a.from = pos; a.to = pos; a.moveMs = 0; a.visible = true
                c.facing?.let { a.facing = facingToward(a, it) }
            }
            is Command.Remove -> {
                actors.remove(c.who)
                scene.removeActor(c.who)
            }
            is Command.Move -> {
                val a = actors[c.who] ?: return
                val to = place.marks[c.mark] ?: return
                a.facing = Shots.facing(a.pos, to)
                if (instant || c.ms <= 0) {
                    a.pos = to; a.from = to; a.to = to; a.moveMs = 0
                } else {
                    a.from = a.pos; a.to = to; a.moveStart = now; a.moveMs = c.ms
                    postAnim(c.who, if (c.run) Emotes.RUN else Emotes.WALK, loop = true, now)
                }
            }
            is Command.Face -> actors[c.who]?.let { it.facing = facingToward(it, c.target) }
            is Command.Emote -> if (!instant) {
                Emotes.byName[c.emote]?.let { postAnim(c.who, it, c.loop, now) }
                voice(c.who, c.emote)
            }
            is Command.Music -> sounds.music(if (c.name == "off") "" else c.name, if (instant) 1500 else c.fadeMs)
            is Command.Sound -> if (!instant) sounds.effect(c.name)
            is Command.Shot -> {
                val pose = shot(c) ?: return
                if (instant || c.ms <= 0) cut(pose) else {
                    camFrom = currentCamera(now); camTo = pose; camStart = now; camMs = c.ms
                }
            }
            is Command.Shake -> if (!instant) shakeUntil = now + c.ms
            is Command.Letterbox -> letterbox = c.on
            else -> Unit
        }
    }

    private fun addActor(who: String, pos: Vec3): Actor {
        if (who in people) dress(who)
        return Actor(who, pos, 0f)
    }

    /** The name over someone's line: the actor's, none for narration, "You" for the reader's choices. */
    fun nameOf(who: String): String = when (who) {
        "narrator" -> ""
        "you" -> "You"
        else -> people[who] ?: who
    }

    /** The speaker's voice for an emote, where sounds.json has one for them (none are listed today). */
    private fun voice(who: String, emote: String) = sounds.voice(who, emote)

    private fun postAnim(who: String, anim: Int, loop: Boolean, now: Int) {
        scene.anim(who, anim, loop, now)
    }

    private fun facingToward(a: Actor, target: String): Float {
        target.toFloatOrNull()?.let { return Math.toRadians(it.toDouble()).toFloat() }
        val point = when (target) {
            "camera" -> camera.eye
            else -> actors[target]?.pos ?: place.marks[target]
        } ?: return a.facing
        return Shots.facing(a.pos, point)
    }

    private fun shot(c: Command.Shot): CameraPose? = when (c.kind) {
        "view" -> place.views[c.args.firstOrNull()]
        "wide" -> place.views.values.firstOrNull()
        "bust" -> actors[c.args.firstOrNull()]?.let { a -> Shots.bust(a.to, a.facing, Staging.HEIGHT) }
        "two" -> {
            val a = actors[c.args.getOrNull(0)]
            val b = actors[c.args.getOrNull(1)]
            if (a != null && b != null) Shots.two(a.to, b.to, place.twoSide, Staging.HEIGHT) else null
        }
        else -> null
    }

    private fun cut(pose: CameraPose) {
        camera = pose; camFrom = pose; camTo = pose; camMs = 0
    }

    private fun currentCamera(now: Int): CameraPose {
        if (camMs <= 0) return camTo
        val t = Shots.ease((now - camStart).toFloat() / camMs)
        if (t >= 1f) camMs = 0
        return camFrom.lerp(camTo, t)
    }

    private fun pushFrame(now: Int) {
        camera = currentCamera(now)
        var cam = camera
        if (now < shakeUntil) {
            val k = (shakeUntil - now) / 400f
            val dx = sin(now * 0.09f) * 0.06f * k
            val dz = sin(now * 0.13f + 1f) * 0.05f * k
            cam = cam.copy(eye = cam.eye + Vec3(dx, 0f, dz), at = cam.at + Vec3(dx, 0f, dz))
        }
        val people = actors.mapValues { (key, a) ->
            if (a.moveMs > 0) {
                val t = ((now - a.moveStart).toFloat() / a.moveMs).coerceIn(0f, 1f)
                a.pos = a.from.lerp(a.to, t)
                if (t >= 1f) {
                    a.moveMs = 0
                    postAnim(key, Emotes.STAND, loop = true, now)
                }
            }
            Placement(a.pos.x, a.pos.y, a.pos.z, a.facing, a.visible)
        }
        scene.frame = cam to people
    }
}
