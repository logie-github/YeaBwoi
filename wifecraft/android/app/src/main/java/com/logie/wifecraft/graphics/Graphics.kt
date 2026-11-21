package com.logie.wifecraft.graphics

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.PowerManager
import android.view.Choreographer
import android.view.Surface
import android.view.View
import com.logie.wifecraft.engine.Native
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the performance overlay and the Graphics page show, about twice a second. */
data class GraphicsLive(
    val fps: Float = 0f,
    val frameMs: Float = 0f,
    val p95Ms: Float = Float.NaN,
    val cpuMs: Float = Float.NaN,
    val gpuMs: Float = Float.NaN,
    val budgetMs: Float = 1000f / 60f,
    /** Each timed pass and its GPU ms (only those that ran). */
    val passes: List<Pair<String, Float>> = emptyList(),
    /** What the governor has lowered now, in its order. */
    val lowered: List<QualityStep> = emptyList(),
    val thermal: Int = 0,
    val sceneW: Int = 0,
    val sceneH: Int = 0,
    val refreshHz: Float = 60f,
    val testing: Boolean = false,
    /** The last "Test for 10 seconds" result (null: none yet). */
    val test: String? = null,
)

/**
 * Options' Graphics at run time: the settings (kept in the app's preferences, "wifecraft" / "graphics", as JSON), the frame
 * pacing (a Choreographer callback asks the view for a frame on every n-th vsync, n from the screen's refresh rate and the
 * target: 60 fps on a 120 Hz screen draws every second vsync; the screen is also asked for the target's rate where Android
 * allows it, API 30), the thermal status (every 2 s), and once a frame on the GL thread ([glFrame]) the renderer's timings,
 * the governor (Governor.kt) and the settings it lowers sent to the renderer when they change.
 */
object Graphics {
    private const val PREFS = "wifecraft"
    private const val KEY = "graphics"
    private val passNames = listOf("Shadows", "Scene", "AO", "Lighting", "Bloom", "Depth of field", "Shafts", "Final")

    private val settingsFlow = MutableStateFlow(GraphicsSettings.DEFAULT)
    val settings: StateFlow<GraphicsSettings> = settingsFlow.asStateFlow()
    private val liveFlow = MutableStateFlow(GraphicsLive())
    val live: StateFlow<GraphicsLive> = liveFlow.asStateFlow()
    @Volatile private var loaded = false

    /** The settings read from the preferences once (any thread). */
    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            settingsFlow.value = GraphicsSettings.parse(prefs(context).getString(KEY, null))
            loaded = true
        }
    }

    /** New settings: applied from the next frame, and kept. */
    fun set(context: Context, s: GraphicsSettings) {
        val c = s.clamped()
        loaded = true
        settingsFlow.value = c
        prefs(context).edit().putString(KEY, c.toJson().toString()).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** "Test for 10 seconds": the next 10 s of frames measured (the result in [live]'s test). */
    fun startTest() { testAsked = true }

    // ---------------------------------------------------------------- the view: pacing and thermal status (main thread)

    @Volatile private var refreshHz = 60f
    @Volatile private var thermalSteps = 0

    /** The scene view's frames paced from now while it is attached to a window (SceneView's init). */
    fun attach(view: GLSurfaceView) {
        load(view.context)
        val pacer = Pacer(view)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = pacer.start()
            override fun onViewDetachedFromWindow(v: View) = pacer.stop()
        })
        if (view.isAttachedToWindow) pacer.start()
    }

    private class Pacer(private val view: GLSurfaceView) : Choreographer.FrameCallback {
        private var running = false
        private var vsync = 0L
        private var lastThermal = 0L
        private var askedRate = -1

        fun start() {
            if (running) return
            running = true
            view.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            Choreographer.getInstance().postFrameCallback(this)
        }

        fun stop() {
            if (!running) return
            running = false
            Choreographer.getInstance().removeFrameCallback(this)
            view.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val target = settingsFlow.value.targetFps
            val hz = view.display?.refreshRate?.takeIf { it > 1f } ?: 60f
            refreshHz = hz
            askRate(target)
            if (vsync++ % FramePacing.divisor(hz, target) == 0L) view.requestRender()
            if (frameTimeNanos - lastThermal > 2_000_000_000L) {
                lastThermal = frameTimeNanos
                thermalSteps = readThermal(view.context)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }

        /** The screen asked for the target's rate (API 30; a 120 Hz screen then may run at 60 or 90 and save power). */
        private fun askRate(target: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            if (target == askedRate) return
            try {
                val surface = view.holder.surface
                if (surface == null || !surface.isValid) return
                surface.setFrameRate(target.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                askedRate = target
            } catch (e: Exception) {
                askedRate = target   // (not asked again: the pacing alone keeps the rate)
            }
        }

        private fun readThermal(context: Context): Int = try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm == null) 0 else {
                val status = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else 0
                val headroom = if (Build.VERSION.SDK_INT >= 30) pm.getThermalHeadroom(10) else Float.NaN
                FramePacing.thermalSteps(status, headroom)
            }
        } catch (e: Exception) { 0 }
    }

    // ---------------------------------------------------------------- the GL thread, once a frame (SceneView)

    private val governor = Governor()
    private val stats = FloatArray(12)
    private var lastNs = 0L
    private var sentFor: GraphicsSettings? = null
    private var sentLevel = -1
    private var sentTimers = false
    @Volatile private var testAsked = false
    private var testUntil = 0L
    private var testFrames = 0
    private var testSum = 0.0
    private val testIntervals = ArrayList<Float>()
    private var lastTest: String? = null
    private var publishAt = 0L
    private var windowFrames = 0
    private var windowMs = 0.0

    /** GL thread, after Native.render: the frame's timings read, the governor stepped, the settings sent when they change. */
    fun glFrame(handle: Long) {
        val now = System.nanoTime()
        val interval = if (lastNs == 0L) Float.NaN else (now - lastNs) / 1e6f
        lastNs = now
        Native.graphicsStats(handle, stats)
        val timersWork = stats[9] > 0.5f
        var gpu = 0f
        var anyGpu = false
        for (i in 1..8) if (!stats[i].isNaN()) { gpu += stats[i]; anyGpu = true }
        val s = settingsFlow.value
        val budget = FramePacing.budgetMs(refreshHz, s.targetFps)
        val steps = s.applicableSteps()
        governor.maxLevel = steps.size
        if (s.adaptive) {
            governor.thermalFloor = thermalSteps
            val cpu = stats[0]
            val work = if (timersWork && anyGpu) maxOf(if (cpu.isNaN()) 0f else cpu, gpu) else null
            governor.frame(now / 1e9, interval, work, budget)
        } else if (governor.level != 0) governor.reset()
        val level = if (s.adaptive) governor.level else 0
        val timers = s.overlay || s.adaptive
        if (s !== sentFor || level != sentLevel || timers != sentTimers) {
            Native.graphics(handle, s.lowered(level).toNative(timers))
            sentFor = s; sentLevel = level; sentTimers = timers
        }
        // the 10-second test
        if (testAsked) {
            testAsked = false
            testUntil = now + 10_000_000_000L
            testFrames = 0; testSum = 0.0; testIntervals.clear()
        }
        val testing = testUntil != 0L
        if (testing && interval.isFinite()) {
            testFrames++; testSum += interval; testIntervals.add(interval)
            if (now >= testUntil) {
                testUntil = 0L
                val arr = testIntervals.toFloatArray()
                val p95 = Governor.percentile95(arr, arr.size)
                val fps = if (testSum > 0) testFrames * 1000.0 / testSum else 0.0
                val missed = arr.count { it > budget * 1.5f }
                lastTest = "10 s: %.1f fps on average, 95%% of frames within %.1f ms (budget %.1f), %d late; %d step%s lowered"
                    .format(fps, p95, budget, missed, level, if (level == 1) "" else "s")
            }
        }
        // the readout, twice a second
        if (interval.isFinite()) { windowFrames++; windowMs += interval }
        if (now >= publishAt) {
            publishAt = now + 500_000_000L
            val passes = ArrayList<Pair<String, Float>>()
            for (i in 1..8) if (!stats[i].isNaN()) passes.add(passNames[i - 1] to stats[i])
            liveFlow.value = GraphicsLive(
                fps = if (windowMs > 0) (windowFrames * 1000.0 / windowMs).toFloat() else 0f,
                frameMs = if (windowFrames > 0) (windowMs / windowFrames).toFloat() else 0f,
                p95Ms = governor.p95Interval,
                cpuMs = stats[0],
                gpuMs = if (timersWork && anyGpu) gpu else Float.NaN,
                budgetMs = budget,
                passes = passes,
                lowered = steps.take(level),
                thermal = thermalSteps,
                sceneW = stats[10].toInt(),
                sceneH = stats[11].toInt(),
                refreshHz = refreshHz,
                testing = testUntil != 0L,
                test = lastTest,
            )
            windowFrames = 0; windowMs = 0.0
        }
    }
}
