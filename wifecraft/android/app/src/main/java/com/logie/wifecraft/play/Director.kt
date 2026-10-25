package com.logie.wifecraft.play

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.logie.wifecraft.data.Cast
import com.logie.wifecraft.data.Content
import com.logie.wifecraft.data.Emotes
import com.logie.wifecraft.data.Lights
import com.logie.wifecraft.data.Save
import com.logie.wifecraft.data.Saves
import com.logie.wifecraft.data.Settings
import com.logie.wifecraft.engine.Clock
import com.logie.wifecraft.engine.Placement
import com.logie.wifecraft.engine.Stage
import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Command
import com.logie.wifecraft.story.Shots
import com.logie.wifecraft.story.Step
import com.logie.wifecraft.story.StoryRunner
import com.logie.wifecraft.story.StoryState
import com.logie.wifecraft.story.Vec3
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin

/**
 * Plays the story: runs the script, stages it in the 3D scene (people, their walks and emotes, the
 * camera's moves and shakes) and holds what the screen shows over it (the line being said, a
 * choice, a chapter card, fades, the letterbox, an ending). Lives on the main thread; [tick] is
 * called every frame.
 */
class Director(
    private val content: Content,
    private val settings: Settings,
    private val saves: Saves,
    private val scene: Stage,
    private val onFinished: () -> Unit,
) {
    data class Line(val who: String, val name: String, val text: String, val index: Int, val shownAt: Int, val seen: Boolean)
    data class Choice(val prompt: String, val options: List<String>)

    var line by mutableStateOf<Line?>(null); private set
    var choice by mutableStateOf<Choice?>(null); private set
    var title by mutableStateOf<Pair<String, String>?>(null); private set
    var titleShownAt = 0; private set
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
    private var place = content.places.values.first()
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
    private var cityRequested = false
    private val seen = settings.seen.toMutableSet()

    private val playerName get() = settings.playerName.ifBlank { "Traveller" }

    /** A new story from its beginning. */
    fun begin() {
        runner = StoryRunner(content.script, StoryState()) { playerName }
        startCity()
    }

    /** A saved story, its stage set again as it was. */
    fun resume(save: Save) {
        settings.playerName = save.playerName
        settings.playerLook = save.playerLook
        runner = StoryRunner(content.script, save.state) { playerName }
        startCity()
        for (c in runner.stageCommands()) stage(c, Clock.now(), instant = true)
        fade = 1f; fadeFrom = 1f; fadeTo = 0f; fadeStart = Clock.now(); fadeMs = 900
    }

    private fun startCity() {
        // A story before this one may have left people on stage.
        scene.removeAll()
        if (cityRequested) return
        cityRequested = true
        if (scene.cityLoaded.get()) { cityReady.set(true); return }
        val centres = content.allMarks().flatMap { listOf(it.x, it.y, it.z) }.toFloatArray()
        scene.loadCity(centres) { cityReady.set(true) }
    }

    // ------------------------------------------------------------------ input

    /** A tap: the whole line, then the next one; skips a chapter card. */
    fun tap() {
        val now = Clock.now()
        val l = line
        if (l != null) {
            if (revealed < l.text.length) revealed = l.text.length else advanceLine()
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

    // ------------------------------------------------------------------ saving

    fun save(slot: Int) {
        saves.write(Save(slot, runner.state.copy(
            affection = runner.state.affection.toMutableMap(),
            flags = runner.state.flags.toMutableSet(),
            stage = runner.state.stage.toMutableList(),
            log = runner.state.log.toMutableList(),
        ).also { snapshot ->
            // A line or choice on screen is said again on loading: save from just before it.
            line?.let { snapshot.pc = it.index; snapshot.log.removeLastOrNull() }
        }, playerName, settings.playerLook, place.name, System.currentTimeMillis()))
        settings.seen = seen
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
            revealed = if (speed <= 0) l.text.length else ((now - l.shownAt) * speed / 1000).coerceAtMost(l.text.length)
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
                is Step.Choose -> choice = Choice(step.prompt, step.options)
                is Step.Ending -> {
                    settings.endings = settings.endings + step.id
                    settings.seen = seen
                    ending = step.id
                }
                Step.Finished -> {
                    settings.seen = seen
                    onFinished()
                    return
                }
            }
        }
        pushFrame(now)
    }

    private fun say(step: Step.Line, now: Int) {
        val person = Cast.person(step.who, settings.playerLook, playerName)
        val name = when (step.who) {
            "narrator" -> ""
            else -> person?.name ?: step.who.replaceFirstChar { it.uppercase() }
        }
        if (step.who in actors) {
            val anim = step.emote?.let { Emotes.byName[it] } ?: Emotes.TALK
            postAnim(step.who, anim, loop = false, now)
        }
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
                place = content.places[c.place] ?: place
                placeName = place.name
                val light = Lights.of(c.light)
                scene.light(light)
                place.views.values.firstOrNull()?.let { cut(it) }
                if (!instant) save(0)
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
            }
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
        val person = Cast.person(who, settings.playerLook, playerName)
        val look = person?.let { content.looks[it.look] }
        val a = Actor(who, pos, 0f)
        if (look != null) scene.addActor(who, look)
        return a
    }

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
        "bust" -> actors[c.args.firstOrNull()]?.let { a ->
            val h = Cast.person(a.key, settings.playerLook, playerName)?.height ?: 2f
            Shots.bust(a.to, a.facing, h)
        }
        "two" -> {
            val a = actors[c.args.getOrNull(0)]
            val b = actors[c.args.getOrNull(1)]
            if (a != null && b != null) Shots.two(a.to, b.to, place.twoSide) else null
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

    /**
     * Someone standing well in front of what the camera films (the player behind whom a close-up is
     * taken) is left out of the picture, as a visual novel leaves out the one it's seen through.
     */
    private fun inTheWay(a: Actor, cam: CameraPose): Boolean {
        val view = cam.at - cam.eye
        val length = kotlin.math.sqrt(view.x * view.x + view.y * view.y + view.z * view.z)
        if (length < 0.01f) return false
        val dir = view * (1f / length)
        val head = Vec3(a.pos.x, a.pos.y, a.pos.z + 1.5f) - cam.eye
        val along = head.x * dir.x + head.y * dir.y + head.z * dir.z
        if (along <= 0f || along > length - 1.0f) return false
        val across = head - dir * along
        return kotlin.math.sqrt(across.x * across.x + across.y * across.y + across.z * across.z) < 0.9f
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
            Placement(a.pos.x, a.pos.y, a.pos.z, a.facing, a.visible && !inTheWay(a, cam))
        }
        scene.frame = cam to people
    }
}
