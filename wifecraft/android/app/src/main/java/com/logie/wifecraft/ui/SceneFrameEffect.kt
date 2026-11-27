package com.logie.wifecraft.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.role
import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.engine.Clock
import com.logie.wifecraft.simulation.ClipPlayback
import com.logie.wifecraft.simulation.PlaybackKeys
import com.logie.wifecraft.simulation.HoldFrame
import com.logie.wifecraft.simulation.PreviewKeys
import java.util.concurrent.ConcurrentHashMap
import com.logie.wifecraft.engine.FrameDriver
import com.logie.wifecraft.engine.FurniturePose
import com.logie.wifecraft.engine.SceneView
import com.logie.wifecraft.simulation.CinematicLook
import com.logie.wifecraft.simulation.SceneSimulation
import com.logie.wifecraft.simulation.Activity
import com.logie.wifecraft.simulation.LiveScene
import com.logie.wifecraft.characters.DragonStages
import com.logie.wifecraft.simulation.PartnerSoundSink
import com.logie.wifecraft.simulation.SceneSnapshot
import com.logie.wifecraft.simulation.SceneObject
import com.logie.wifecraft.simulation.SpeechLayout
import com.logie.wifecraft.simulation.ClockSpeed
import com.logie.wifecraft.simulation.LifeSim
import com.logie.wifecraft.simulation.LightFeed
import com.logie.wifecraft.simulation.WorldClock
import com.logie.wifecraft.simulation.FlowPhase
import com.logie.wifecraft.simulation.ImmersiveSettings
import com.logie.wifecraft.graphics.Graphics
import com.logie.wifecraft.graphics.SkinSheen
import com.logie.wifecraft.simulation.IntensityEase
import com.logie.wifecraft.simulation.IntensityModel
import kotlin.math.abs

// The GL thread side of the live scene (see LiveSceneScreen).

/**
 * The GL thread's side of the scene: the frame driver ([FrameDriver]) that the view runs every frame, and the sink the live
 * scene's placements, speech, gestures and clips go through. [onSnapshot] and [onClock] are posted to the main thread when
 * what the menus show changed. Moved out of [LiveSceneScreen] as it was (the 64 KB rule: README, Scene screen layout).
 */
@Composable
internal fun SceneFrameEffect(
    live: LiveScene,
    view: SceneView,
    context: android.content.Context,
    clock: WorldClock?,
    life: LifeSim?,
    speechSpots: SpeechSpots,
    lines: MutableList<String?>,
    heads: FloatArray,
    creatureSpecs: Map<String, com.logie.wifecraft.characters.CreatureSpec>,
    playback: ConcurrentHashMap<String, Anim>,
    stageClasses: ConcurrentHashMap<String, com.logie.wifecraft.bodyparts.StageClass>,
    cineLook: CinematicLook,
    feed: ImmersionFeed,
    actorKey: (String) -> String,
    onSnapshot: (SceneSnapshot) -> Unit,
    onClock: (ClockReading) -> Unit,
    onAnimate: (String, Activity) -> Unit,
    partnerSounds: PartnerSoundSink,
    fetchAnim: (String, Int) -> Unit,
    onTimeOfDay: (hours: Double, realSecondsPerHour: Double) -> Unit,
    onLifeSave: (life: String?, clock: String?) -> Unit,
    onIntensity: (Float?) -> Unit,
) {
    // the GL thread drives every frame; the menus hear of changes only
    val animate by rememberUpdatedState(onAnimate)
    val sounds by rememberUpdatedState(partnerSounds)
    val fetch by rememberUpdatedState(fetchAnim)
    val timeOfDay by rememberUpdatedState(onTimeOfDay)
    val saveLife by rememberUpdatedState(onLifeSave)
    val intensityOut by rememberUpdatedState(onIntensity)
    DisposableEffect(live, view) {
        val main = Handler(Looper.getMainLooper())
        val ids = Array(live.count) { live.id(it) }
        val creatureOf = Array(live.count) { creatureSpecs[ids[it]] }
        val keys = Array(live.count) { actorKey(ids[it]) }
        // GL thread: the frame's time on the engine's clock; both of a pair start their clips from this one value
        var frameMs = 0
        val emoting = BooleanArray(live.count)
        // main thread: the talking gestures' files asked for once each
        val gestureFiles = HashSet<Long>()
        // GL thread: the world clock's last frame, the light's hand-over, and when the face and the sound were last posted
        val light = LightFeed()
        var clockNanos = 0L
        var clockKey = -1L
        var soundNanos = 0L
        // GL thread: each person's intensity (IntensityModel, eased), whom they look at, and when these were last looked at
        val eases = Array(live.count) { IntensityEase() }
        val sheens = Array(live.count) { SkinSheen.Ease() }   // the skin's wet sheen from the intensity (Options' Graphics)
        val goals = FloatArray(live.count) { IntensityModel.IDLE }
        // GL thread: each person's playing key (a partner stage's role), and their arousal (bodyparts/Arousal)
        val playingKeys = arrayOfNulls<String>(live.count)
        val arousal = Array(live.count) { com.logie.wifecraft.bodyparts.ArousalTracker() }
        val lookAt = arrayOfNulls<String>(live.count)
        val keyOf = ids.indices.associate { ids[it] to keys[it] }
        // the pack's props (Options → Mods → Import pack): the toys, dolls and layers of the stage that plays, drawn at its origin
        val propStore = com.logie.wifecraft.props.PropStore(java.io.File(context.filesDir, "pack/props"))
        val packOptions = com.logie.wifecraft.pack.PackOptions(java.io.File(context.filesDir, "pack/options.json"))
        val propDriver = com.logie.wifecraft.props.PropStageDriver(propStore, { kind -> packOptions.shows(kind) },
            { com.logie.wifecraft.props.PropStageDriver.DEFAULT_SCALE * com.logie.wifecraft.anim.CharacterSize.active })   // (the props grow with the bodies: Character size)
        var momentNanos = 0L
        var motionNanos = 0L
        var intensityNanos = 0L
        val sink = object : LiveScene.Sink {
            override fun camera(eyeX: Float, eyeY: Float, eyeZ: Float, atX: Float, atY: Float, atZ: Float, fov: Float) {
                view.glCamera(eyeX, eyeY, eyeZ, atX, atY, atZ, fov)
                speechSpots.layout(eyeX, eyeY, eyeZ, atX, atY, atZ, fov)   // the bubbles follow the heads with this camera
            }

            override fun focus(near: Float, far: Float, strength: Float) {
                // Options' Graphics: Focus follows the NPC off: the renderer's own band round the camera's target
                if (Graphics.settings.value.focusFollows) view.glFocus(near, far, strength) else view.glFocus(0f, 0f, 1f)
            }

            override fun place(index: Int, x: Float, y: Float, z: Float, facing: Float, furniture: SceneObject?, sx: Float, sy: Float, sz: Float) {
                val pose = when {
                    furniture?.action == Activity.SIT -> FurniturePose.SIT
                    furniture?.action == Activity.REST -> FurniturePose.REST
                    // a clip raised over its floor (onto a bed or seat), or lowered onto it in place of a bed (SceneSimulation's
                    // floor plays: under sz): drawn at its height, not stood on the floor
                    furniture == null && kotlin.math.abs(z - sz) > 0.01f -> FurniturePose.RAISED
                    else -> FurniturePose.FLOOR
                }
                view.glPlace(keys[index], x, y, z, facing, pose, sx, sy, sz)
                propDriver.place(index, x, y, z, facing)
                if (index == ids.size - 1) view.setProps(propDriver.frame(frameMs)) { id -> propStore.modelFile(id).takeIf { it.isFile }?.readBytes() }
                val standing = heads.getOrElse(index) { 2.05f }
                val base = if (pose == FurniturePose.RAISED) z else sz
                speechSpots.headAt(index, sx, sy, base + SpeechLayout.head(standing, pose == FurniturePose.SIT, pose == FurniturePose.REST))
            }

            override fun speech(index: Int, text: String?, id: Long) {
                if (index in speechSpots.speaking.indices) speechSpots.speaking[index] = text != null
                main.post { if (index in lines.indices) lines[index] = text }
            }

            override fun gesture(index: Int, animation: Int) {
                val key = keys[index]
                view.glAnimate(key, animation)   // this very frame
                main.post { if (gestureFiles.add(index.toLong() shl 32 or animation.toLong())) fetch(key, animation) }
            }

            override fun playback(index: Int, clip: ClipPlayback?) {
                propDriver.play(index, clip?.let { c -> playback[c.clip]?.sourceName }, frameMs)
                val key = keys[index]
                val creature = creatureOf[index]
                // a creature's own stage (DragonStages): the creature's layer over her own animation, the person's reach on her
                val stage = clip?.clip?.let { DragonStages.ofKey(it) }
                if (creature != null) {
                    // a creature never plays a Sims clip (nothing is retargeted onto her): her own game animation, looped
                    view.glPlayAnimation(key, null)
                    playingKeys[index] = clip?.clip
                    emoting[index] = false
                    view.glAnimate(key, creature.standAnimation)
                    view.glBoneLayer(key, if (stage != null && stage.second == 1 && stage.first.creature == creature.character) stage.first.baked.layer else null)
                    return
                }
                if (stage != null && stage.second == 0) {
                    val at = creatureOf.indexOfFirst { it?.character == stage.first.creature }
                    val r = stage.first.reach
                    view.glReach(key, if (at >= 0) keys[at] else null, r.bone, r.left, stage.first.baked.reach)
                } else view.glReach(key, null, 0, false, null)
                val emote = clip?.let { PlaybackKeys.emoteOf(it.clip) }
                playingKeys[index] = clip?.clip?.takeIf { emote == null }
                val wasEmote = emoting[index]
                emoting[index] = emote != null
                if (emote != null) {
                    // a Warcraft emote both play (no imported pair fits): its files, then the game animation from this frame,
                    // held only when it is a held one (Dance); a wave or a bow plays once
                    val start = frameMs
                    val held = emoteHeld(emote)
                    view.glPlayAnimation(key, null)
                    main.post { fetch(key, emote); view.anim(key, emote, held, start) }
                } else {
                    view.glPlayAnimation(key, clip?.let { c ->
                        playback[c.clip] ?: PreviewKeys.heldOf(c.clip)?.let { held -> playback[held]?.let(HoldFrame::of) }
                    })
                    if (wasEmote) { val start = frameMs; main.post { view.anim(key, 0, true, start) } }   // back to standing
                }
            }

            override fun bonePoints(index: Int, out: FloatArray): Boolean = view.glBonePoints(keys[index], out)

            override fun activity(index: Int, activity: Activity) {
                view.glAnimate(keys[index], activity.animation)   // this very frame, with the placement
                val id = ids[index]
                main.post { animate(id, activity) }                // the imported clips and the files (main thread)
            }

            override fun sound(index: Int, name: String) {
                val key = keys[index]
                main.post { sounds.play(name, key) }
            }
        }
        val emit: (SceneSnapshot) -> Unit = { next -> main.post { onSnapshot(next) } }
        view.driver = FrameDriver { now ->
            frameMs = Clock.now()
            if (clock != null) {
                // the world's time moves while the scene runs (not while it loads or ends), the light and sound follow it
                if (live.running) {
                    if (clockNanos != 0L) clock.advance((now - clockNanos) / 1e9)
                    clockNanos = now
                } else clockNanos = 0L
                light.next(clock)?.let { view.setTimeOfDay(it) }
                val key = LifeHud.clockKey(clock.simHours, clock.speed)
                if (key != clockKey) {
                    clockKey = key
                    val reading = ClockReading(clock.hourOfDay, clock.day, clock.speed)
                    main.post { onClock(reading) }
                }
                if (now - soundNanos >= LifeHud.UI_MS * 1_000_000L) {
                    soundNanos = now
                    val hour = clock.lightHour
                    val perHour = clock.realSecondsPerHour
                    main.post { timeOfDay(hour, perHour) }
                }
            }
            cineLook.frame(now)
            view.glCinematic(cineLook.mood(), cineLook.vignette(), cineLook.grain(), cineLook.letterbox())
            view.glZoneMood(cineLook.zoneMood(), cineLook.moodBoost())
            live.frame(now, sink, emit)
            // the body language (cpp engine/motionlayer.h) and the atmosphere, after the frame's placements
            val imm = feed.settings
            view.glMotionParams(imm.breathing / 100f, imm.idleMotion / 100f, imm.eyeContact)
            val step = if (motionNanos == 0L) 0f else ((now - motionNanos) / 1e9f).coerceIn(0f, 0.1f)
            motionNanos = now
            if (now - momentNanos >= MOMENT_NANOS) {
                momentNanos = now
                val fl = feed.flow
                for (i in 0 until live.count) {
                    val other = if (fl == null) null else if (ids[i] == fl.a) fl.b else if (ids[i] == fl.b) fl.a else null
                    goals[i] = if (fl != null && other != null) IntensityModel.of(live.moment(i), fl.phase, fl.tempo)
                        else IntensityModel.of(live.moment(i))
                    lookAt[i] = live.companion(i) ?: other
                }
            }
            var most = 0f
            for (i in 0 until live.count) {
                val v = eases[i].step(goals[i], step)
                if (v > most) most = v
                view.glMotion(keys[i], v, lookAt[i]?.let { keyOf[it] })
                view.glSheen(keys[i], sheens[i].step(SkinSheen.target(v, Graphics.effective), step))
            }
            // the body parts: each wearer's arousal from the stage they play and the intensity, and the partner whose entry the
            // erect shaft goes into (the engine draws nothing for someone without the parts)
            val partners = com.logie.wifecraft.bodyparts.StageKeys.partners(playingKeys)
            for (i in 0 until live.count) {
                val cls = playingKeys[i]?.let { stageClasses[it] } ?: com.logie.wifecraft.bodyparts.StageClass.NONE
                val tracker = arousal[i]
                val level = tracker.step(cls, eases[i].value, com.logie.wifecraft.bodyparts.ArousalControl.mode,
                    com.logie.wifecraft.bodyparts.ArousalControl.override(ids[i]), step)
                val p = partners[i]
                val entry = if (p >= 0 && cls.intimate) tracker.entryFor(cls) else com.logie.wifecraft.bodyparts.PartEntryKind.NONE
                view.glPartDrive(keys[i], level, if (p >= 0) keys[p] else null, entry.engine.coerceAtLeast(0), cls.rest)
            }
            val cine = feed.cinematic
            view.glParticles(if (imm.particlesOn(cine)) MOTES else 0, if (cine) 0.9f else 0.6f)
            live.camera.handheld = if (cine) imm.handheld / 100f else 0f
            if (now - intensityNanos >= LifeHud.UI_MS * 1_000_000L) {
                intensityNanos = now
                val v = most
                main.post { intensityOut(v) }
            }
        }
        onDispose {
            view.driver = null
            main.removeCallbacksAndMessages(null)
            sounds.stopAll()
            view.clearAtmosphere()
            intensityOut(null)
            // the scene's life and time as they were last (the GL thread drives them no more)
            // (the GL thread may still be finishing a frame: a failed read here loses only the last half minute, which the periodic save keeps)
            if (life != null || clock != null) runCatching { saveLife(life?.toJson(), clock?.toJson()?.toString()) }
        }
    }
}

/** The scene's intensity is looked at again this often (ns): the moments' goals; each frame eases toward them. */
private const val MOMENT_NANOS = 100_000_000L
/** The atmosphere's motes on screen. */
private const val MOTES = 48

/** What the GL thread reads of immersive mode each frame: Options' settings, the flow, whether cinematic mode is on. */
internal class ImmersionFeed {
    class FlowNow(val a: String, val b: String, val phase: FlowPhase, val tempo: Int)
    @Volatile var settings: ImmersiveSettings = ImmersiveSettings.DEFAULT
    @Volatile var flow: FlowNow? = null
    @Volatile var cinematic = false
}

/** The world clock as the top bar shows it (hour, day, speed). */
internal data class ClockReading(val hour: Double, val day: Int, val speed: ClockSpeed)
