package com.logie.wifecraft.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.logie.wifecraft.BuildConfig
import com.logie.wifecraft.anim.HumanScale
import com.logie.wifecraft.characters.NamedCast
import com.logie.wifecraft.creator.Sex
import com.logie.wifecraft.editor.Actor
import com.logie.wifecraft.simulation.Furniture
import com.logie.wifecraft.simulation.HeldPose
import com.logie.wifecraft.simulation.ItemClass
import com.logie.wifecraft.simulation.ItemFrame
import com.logie.wifecraft.simulation.ItemMapping
import com.logie.wifecraft.simulation.ItemOverride
import com.logie.wifecraft.simulation.ItemOverrides
import com.logie.wifecraft.simulation.ItemReport
import com.logie.wifecraft.simulation.ItemReports
import com.logie.wifecraft.simulation.ItemTarget
import com.logie.wifecraft.simulation.LiveScene
import com.logie.wifecraft.simulation.MappedItem
import com.logie.wifecraft.simulation.MappedItems
import com.logie.wifecraft.simulation.MovePhase
import com.logie.wifecraft.simulation.MoveState
import com.logie.wifecraft.simulation.NpcInfo
import com.logie.wifecraft.simulation.ReportFlag
import com.logie.wifecraft.simulation.ReportQueue
import com.logie.wifecraft.simulation.SceneObject
import com.logie.wifecraft.simulation.SendOutcome
import com.logie.wifecraft.simulation.SendResult
import com.logie.wifecraft.simulation.UrlTransport
import com.logie.wifecraft.story.Vec3
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

/**
 * Move mode in the live scene (the item mapper: simulation/ItemMapping, simulation/ItemReports). With an NPC controlled, the
 * HUD's Move chip turns it on: a tap on a piece of furniture asks what it is (Chair/seat, Table top, Floor, Bed/couch; a tap
 * on open floor is Floor), the NPC is put there and held, the panel nudges them (a drag pad, arrows, turn, up and down) and
 * Report… files a GitHub issue with what was placed (after a one-time yes). A report that can't go now waits in
 * files/reports/queue and goes when the phone is online. The player's mappings take effect at once ([local]); the shipped
 * ones come from assets/item_overrides.json ([shipped]). Main thread, but for the files and the network.
 */
class ItemMapper internal constructor(private val context: Context, private val live: LiveScene, private val scope: CoroutineScope) {
    var on by mutableStateOf(false)
        private set
    var state by mutableStateOf(MoveState())
        private set
    var reportOpen by mutableStateOf(false)
        private set
    var askConsent by mutableStateOf(false)
        private set
    var coarse by mutableStateOf(false)
    /** The shipped overrides and the phone's own mappings (overrides by key), and the place the scene is. */
    var shipped by mutableStateOf<Map<String, ItemOverride>>(emptyMap())
        internal set
    var local by mutableStateOf<Map<String, ItemOverride>>(emptyMap())
        private set
    var placeId by mutableStateOf("")
        internal set
    var placeName = ""
        internal set
    internal var models: Map<String, Int> = emptyMap()
    /** The screenshot of the held placement (files/reports/shots), once taken. */
    var shot by mutableStateOf<File?>(null)
        private set

    /** The controlled NPC held by Move mode, and the scene's object they're held on (null: open floor or not one). */
    var npc: String? = null
        private set
    private var heldItem: SceneObject? = null
    private var reportId = newId()

    private val dir get() = File(context.filesDir, "reports")
    private val queue get() = ReportQueue(File(dir, "queue"))
    private val mapped get() = MappedItems(File(dir, "mapped.json"))
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun keyOf(item: SceneObject): String = ItemMapping.key(placeId, item.name, models[item.name] ?: item.model)

    /** The class the player (or the shipped file) gave [item], else null (its own measure stands). */
    fun classOf(item: SceneObject): ItemClass? = ItemOverrides.lookup(keyOf(item), local, shipped).first?.cls

    /** The scene's furniture with the overrides applied (the phone's own first, then the shipped file's). */
    fun overridden(raw: Map<SceneObject, Furniture>): HashMap<SceneObject, Furniture> {
        val out = HashMap<SceneObject, Furniture>()
        for ((item, f) in raw) {
            val o = if (placeId.isEmpty()) null else ItemOverrides.lookup(keyOf(item), local, shipped).first
            out[item] = if (o != null) ItemOverrides.apply(f, o) else f
        }
        return out
    }

    fun start(id: String) {
        if (on && npc == id) return
        if (on) leave()
        on = true
        npc = id
        state = MoveState()
        flush()
    }

    /** Move mode off: the NPC let go (back to their day from where they stand). */
    fun leave() {
        val id = npc
        if (id != null && state.holding) live.command { it.endHold(id) }
        on = false
        npc = null
        heldItem = null
        state = MoveState()
        reportOpen = false
        askConsent = false
        shot = null
    }

    /** Back: the open window first, then the picker, then Move mode itself. */
    fun back() {
        when {
            askConsent -> askConsent = false
            reportOpen -> reportOpen = false
            state.phase == MovePhase.PICKING -> state = state.cancelPick()
            else -> leave()
        }
    }

    /** A piece tapped ([f] its measured shape before any override; null: not measured, it can't be mapped yet). */
    fun tapItem(item: SceneObject, f: Furniture?, toStage: (Vec3) -> Vec3, stageRotation: Float) {
        if (!on || state.phase == MovePhase.SENDING) return
        if (f == null) { state = state.copy(message = "${item.label} isn't measured yet: try again in a moment."); return }
        endHoldIfAny()
        val at = toStage(Vec3(f.x, f.y, f.z))
        val t = ItemTarget(placeId, placeName, item.name, models[item.name] ?: item.model, item.label, ItemFrame.of(f),
            ItemFrame(at.x, at.y, at.z, ItemFrame.wrapRad(f.yaw + stageRotation)), f.spec)
        heldItem = item
        reportId = newId(); shot = null
        state = state.tap(t)
    }

    /** Open floor tapped at [spot] (scene frame): held there as Floor, facing the way they would have walked from [from]. */
    fun tapFloor(spot: Vec3, from: Vec3?, toStage: (Vec3) -> Vec3, stageRotation: Float) {
        if (!on || state.phase == MovePhase.SENDING) return
        endHoldIfAny()
        val yaw = from?.let { ItemMapping.facingToward(it, spot) } ?: 0f
        val at = toStage(spot)
        val t = ItemTarget(placeId, placeName, "FLOOR", 0, "Floor", ItemFrame(spot.x, spot.y, spot.z, yaw),
            ItemFrame(at.x, at.y, at.z, ItemFrame.wrapRad(yaw + stageRotation)), null)
        heldItem = null
        reportId = newId(); shot = null
        state = state.tap(t).place(ItemClass.FLOOR)
        hold()
    }

    fun pick(c: ItemClass) { state = state.place(c); hold() }
    fun cancelPick() { state = state.cancelPick() }

    fun nudge(dx: Float = 0f, dy: Float = 0f, dz: Float = 0f, turn: Float = 0f) {
        val next = state.nudge(dx, dy, dz, turn)
        if (next == state) return
        state = next; shot = null
        hold()
    }

    fun nextPose() { state = state.nextPose(); shot = null; hold() }
    fun notes(text: String) { state = state.withNotes(text) }
    fun toggle(f: ReportFlag) { state = state.toggle(f) }
    fun clearMessage() { if (state.message != null && state.phase == MovePhase.PLACED) state = state.copy(message = null) }

    private fun endHoldIfAny() {
        val id = npc ?: return
        if (state.holding) live.command { it.endHold(id) }
    }

    /** The NPC put where the state says, held in its pose. */
    private fun hold() {
        val id = npc ?: return
        val (at, facing) = state.world() ?: return
        val activity = (state.pose ?: HeldPose.STAND).activity
        val item = heldItem
        live.order { sim -> if (sim.holdAt(id, item, at, facing, activity)) null else "Someone else is using the ${item?.label?.lowercase() ?: "spot"}." }
    }

    /** Report…: the screenshot taken (the scene without the HUD), then the one-time question, then the report window. */
    fun openReport(view: SurfaceView?) {
        if (!state.canReport) return
        if (view != null) capture(view)
        if (!prefs.getBoolean(KEY_CONSENT, false)) askConsent = true else reportOpen = true
    }

    fun consent(yes: Boolean) {
        askConsent = false
        if (yes) {
            runCatching { prefs.edit().putBoolean(KEY_CONSENT, true).apply() }
            reportOpen = true
        }
    }

    fun closeReport() { reportOpen = false }

    /** The report sent (or queued when it can't go now); the mapping kept and used at once either way. */
    fun send(npcInfo: NpcInfo) {
        val s = state
        val t = s.target ?: return
        val c = s.cls ?: return
        val o = s.offset ?: return
        val p = s.pose ?: return
        if (!s.canReport) return
        val report = ItemReport(reportId, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, t, c, npcInfo, o, p, s.notes.trim(), s.flags, shot != null)
        state = s.sending()
        reportOpen = false
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    mapped.upsert(MappedItems.of(report, MappedItem.Status.QUEUED))
                    queue.enqueue(report)
                    flushNow()
                }.getOrElse { SendResult(SendOutcome.RETRY, 0, "couldn't keep the report: ${it.javaClass.simpleName}") }
            }
            local = withContext(Dispatchers.IO) { runCatching { MappedItems.overrides(mapped.load()) }.getOrDefault(local) }
            val issue = withContext(Dispatchers.IO) { runCatching { mapped.load().firstOrNull { it.reportId == report.id }?.issue ?: 0 }.getOrDefault(0) }
            state = when {
                issue > 0 -> state.sent(issue)
                result?.outcome == SendOutcome.REFUSED -> state.failed("GitHub refused the report: ${result?.detail.orEmpty()}")
                result?.outcome == SendOutcome.NOT_CONFIGURED -> state.queued("Kept on the phone: this build can't send reports (${result?.detail.orEmpty()}).")
                else -> state.queued("Waiting to send: it goes when the phone is online.")
            }
            reportId = newId()
        }
    }

    /** Retry: whatever waits goes now if it can. */
    fun retry() {
        val s = state
        if (s.phase != MovePhase.QUEUED && s.phase != MovePhase.FAILED) return
        if (s.phase == MovePhase.FAILED) { state = s.copy(phase = MovePhase.PLACED, message = null); return }
        state = s.copy(message = "Sending…")
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { flushNow() }.getOrNull() }
            val last = withContext(Dispatchers.IO) { runCatching { mapped.load().maxByOrNull { it.at } }.getOrNull() }
            state = if (r?.outcome == SendOutcome.SENT && last != null && last.issue > 0) state.copy(phase = MovePhase.REPORTED, issue = last.issue, message = null)
                else state.copy(message = r?.detail?.let { "Still waiting: $it" } ?: "Nothing waiting.")
        }
    }

    /** What waits goes, off the main thread (at the start of Move mode and whenever the phone comes online). */
    fun flush() { scope.launch(Dispatchers.IO) { runCatching { flushNow() } } }

    /** Loads the shipped overrides, the phone's mappings and the place's id and models (IO). */
    fun load(locationName: String) {
        placeName = locationName
        scope.launch {
            val (place, ship, mine) = withContext(Dispatchers.IO) {
                val info = runCatching { context.assets.open("locations.json").bufferedReader().use { ItemMapping.placeModels(it.readText(), locationName) } }.getOrNull()
                val fromFile = runCatching { context.assets.open("item_overrides.json").bufferedReader().use { ItemOverrides.parse(it.readText()) } }.getOrDefault(emptyMap())
                val fromPhone = runCatching { MappedItems.overrides(mapped.load()) }.getOrDefault(emptyMap())
                Triple(info, fromFile, fromPhone)
            }
            models = place?.second.orEmpty()
            placeId = place?.first ?: locationName.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')
            shipped = ship
            local = mine
        }
    }

    private fun flushNow(): SendResult? = sendWaiting(context)

    /** The scene's picture without the HUD (PixelCopy of the GL surface), 640 wide, a JPEG of at most 200 KB. */
    private fun capture(view: SurfaceView) {
        val w = view.width; val h = view.height
        if (w <= 0 || h <= 0 || !view.holder.surface.isValid) return
        val bitmap = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } catch (e: Throwable) { return }
        val id = reportId
        try {
            PixelCopy.request(view, bitmap, { result ->
                if (result != PixelCopy.SUCCESS) { bitmap.recycle(); return@request }
                scope.launch {
                    val file = withContext(Dispatchers.IO) { runCatching { saveShot(bitmap, File(dir, "shots/$id.jpg")) }.getOrNull() }
                    bitmap.recycle()
                    if (file != null && id == reportId) shot = file
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: IllegalArgumentException) { bitmap.recycle() }
    }

    /** Share screenshot: the system's share sheet with the picture (the player chooses where it goes, if anywhere). */
    fun shareShot() {
        val f = shot ?: return
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.filesprovider", f)
            val send = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TEXT, state.target?.let { "Item report: ${state.cls?.label ?: ""} ${it.displayName} @ ${it.placeName}" + (if (state.issue > 0) " (#${state.issue})" else "") } ?: "")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, "Share screenshot").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { state = state.copy(message = "Couldn't share the screenshot.") }
    }

    companion object {
        private const val PREFS = "item_reports"
        private const val KEY_CONSENT = "consent"
        private const val SHOT_WIDTH = 640
        private const val SHOT_BYTES = 200 * 1024

        private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

        internal fun saveShot(full: Bitmap, file: File): File {
            file.parentFile?.mkdirs()
            val w = minOf(SHOT_WIDTH, full.width)
            val scaled = if (w == full.width) full else Bitmap.createScaledBitmap(full, w, (full.height * w.toFloat() / full.width).toInt().coerceAtLeast(1), true)
            var quality = 80
            while (true) {
                FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                if (file.length() <= SHOT_BYTES || quality <= 30) break
                quality -= 10
            }
            if (scaled !== full) scaled.recycle()
            return file
        }

        /** Sends whatever reports wait on this phone (any thread but the main one): the mappings marked as they go. */
        fun sendWaiting(context: Context): SendResult? {
            val dir = File(context.filesDir, "reports")
            val mapped = MappedItems(File(dir, "mapped.json"))
            val http = UrlTransport()
            return ReportQueue(File(dir, "queue")).flush(
                { title, body -> ItemReports.send(BuildConfig.WC_UPDATE_REPO, BuildConfig.WC_REPORT_TOKEN, title, body, BuildConfig.VERSION_NAME, http) },
                onSent = { e, issue -> mapped.status(e.reportId, MappedItem.Status.SENT, issue) },
                onRefused = { e, _ -> mapped.status(e.reportId, MappedItem.Status.FAILED) },
            )
        }
    }
}

/** [a] as a report gives the NPC: race, sex, size, and a name only when it is one of the game's named characters (a player's own NPC's never). */
fun npcInfoOf(a: Actor): NpcInfo {
    val female = a.appearance.sex == Sex.FEMALE
    return NpcInfo(a.appearance.raceId, female, HumanScale.bodyScale(a.appearance.raceId, female), a.name.takeIf { NamedCast.isNamed(a.id) })
}

/** Move mode's controller for this scene ([locationName]): its overrides and place loaded, the waiting reports sent when online. */
@Composable
fun rememberItemMapper(live: LiveScene, locationName: String): ItemMapper {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val mapper = remember(live) { ItemMapper(context, live, scope) }
    LaunchedEffect(mapper, locationName) { mapper.load(locationName) }
    // the phone back online: what waits goes
    DisposableEffect(mapper) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { mapper.flush() }
        }
        val registered = runCatching { cm?.registerDefaultNetworkCallback(callback) }.isSuccess && cm != null
        onDispose {
            if (registered) runCatching { cm?.unregisterNetworkCallback(callback) }
            mapper.leave()
        }
    }
    return mapper
}

/** Move mode's panel (over the scene, at the side): what is held, the nudges, Report… and Done. */
@Composable
fun MovePanel(mapper: ItemMapper, view: SurfaceView?, modifier: Modifier = Modifier) {
    if (!mapper.on) return
    val s = mapper.state
    WifePanel(modifier.width(264.dp).heightIn(max = 380.dp), padding = WifeTheme.gapM) { Column(Modifier.verticalScroll(rememberScrollState())) {
        val what = s.target?.let { t -> t.displayName + (s.cls?.let { " · ${it.label}" } ?: "") } ?: "Move"
        Text(what, style = WifeTheme.headingStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!s.holding) {
            WifeNote(if (s.phase == MovePhase.PICKING) "Say what it is." else "Tap a chair, a table, a bed or the floor: the NPC goes there and holds.")
        } else {
            val step = if (mapper.coarse) ItemMapping.STEP_COARSE else ItemMapping.STEP_FINE
            val turn = if (mapper.coarse) ItemMapping.TURN_COARSE else ItemMapping.TURN_FINE
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
                if ((s.cls?.holds?.size ?: 0) > 1) WifeChip("Pose: ${s.pose?.label ?: ""}", { mapper.nextPose() }, trailing = null)
                WifeChip(if (mapper.coarse) "Coarse" else "Fine", { mapper.coarse = !mapper.coarse }, trailing = null)
            }
            // the item's frame: up is its front, left its left
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
                Row(horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
                    NudgeKey("⟲") { mapper.nudge(turn = turn) }
                    NudgeKey("▲") { mapper.nudge(dx = step) }
                    NudgeKey("⟳") { mapper.nudge(turn = -turn) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs), verticalAlignment = Alignment.CenterVertically) {
                    NudgeKey("◀") { mapper.nudge(dy = step) }
                    DragPad(mapper)
                    NudgeKey("▶") { mapper.nudge(dy = -step) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
                    NudgeKey("Down") { mapper.nudge(dz = -step) }
                    NudgeKey("▼") { mapper.nudge(dx = -step) }
                    NudgeKey("Up") { mapper.nudge(dz = step) }
                }
            }
            s.offset?.let { o ->
                WifeNote(String.format(Locale.ROOT, "x %.2f  y %.2f  z %.2f yd  ·  %.0f°", o.x, o.y, o.z, o.yawDeg))
            }
        }
        val status = when (s.phase) {
            MovePhase.SENDING -> "Sending…"
            MovePhase.REPORTED -> "Report sent: #${s.issue}"
            else -> s.message
        }
        if (status != null) WifeNote(status, color = if (s.phase == MovePhase.FAILED) WifeTheme.danger else if (s.phase == MovePhase.REPORTED) WifeTheme.good else WifeTheme.textDim)
        Row(Modifier.fillMaxWidth().padding(top = WifeTheme.gapXs), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
            if (s.phase == MovePhase.QUEUED || s.phase == MovePhase.FAILED) WifeButton("Retry", { mapper.retry() }, Modifier.weight(1f))
            else WifeButton("Report…", { mapper.openReport(view) }, Modifier.weight(1f), enabled = s.canReport, kind = WifeButtonKind.PRIMARY)
            WifeButton("Done", { mapper.leave() }, Modifier.weight(1f))
        }
    } }
}

/** A nudge key: held, it repeats. */
@Composable
private fun NudgeKey(label: String, onPress: () -> Unit) {
    val shape = RoundedCornerShape(WifeTheme.buttonRadius)
    Box(
        Modifier.size(width = 56.dp, height = 36.dp).clip(shape).background(WifeTheme.slateRaised)
            .border(WifeTheme.trimWidth, WifeTheme.trimSoft, shape).warPress(repeatable = true, onClick = onPress),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = WifeTheme.labelStyle(), maxLines = 1, textAlign = TextAlign.Center) }
}

/** A small pad: a drag moves the NPC in the item's frame (up its front), a yard for every 200 dp (a quarter of that fine). */
@Composable
private fun DragPad(mapper: ItemMapper) {
    val shape = RoundedCornerShape(WifeTheme.buttonRadius)
    val pxPerDp = LocalDensity.current.density
    Box(
        Modifier.size(width = 56.dp, height = 36.dp).clip(shape).background(WifeTheme.track)
            .border(WifeTheme.trimWidth, WifeTheme.trim.copy(alpha = 0.6f), shape)
            .pointerInput(mapper) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val perDp = if (mapper.coarse) 1f / 200f else 1f / 800f
                    mapper.nudge(dx = -drag.y / pxPerDp * perDp, dy = -drag.x / pxPerDp * perDp)
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text("✥", style = WifeTheme.labelStyle(WifeTheme.textDim)) }
}

/** Move mode's windows: what is this (the class picker), the one-time question, and the report. */
@Composable
fun MoveWindows(mapper: ItemMapper, npcInfo: () -> NpcInfo?) {
    val s = mapper.state
    Overlay({ mapper.cancelPick() }, mapper.on && s.phase == MovePhase.PICKING) {
        WifeWindow("What is this?", { mapper.cancelPick() }, Modifier.widthIn(min = 260.dp, max = 420.dp).padding(WifeTheme.gapL),
            subtitle = s.target?.let { t -> "${t.displayName}${t.detected?.let { " (measured as ${it.label.lowercase()})" } ?: ""}" }) {
            Column(verticalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
                for (c in ItemClass.entries) WifeButton(c.label, { mapper.pick(c) }, Modifier.fillMaxWidth(),
                    kind = if (c == s.target?.detected) WifeButtonKind.PRIMARY else WifeButtonKind.SECONDARY)
            }
        }
    }
    Overlay({ mapper.consent(false) }, mapper.on && mapper.askConsent) {
        WifeWindow("Send item reports?", { mapper.consent(false) }, Modifier.widthIn(min = 280.dp, max = 460.dp).padding(WifeTheme.gapL)) {
            Text("This sends item data to the developer's repository: the place, the item, the class you chose, where the NPC is held, " +
                "your notes and the app's version, as an issue on its GitHub page. Nothing else from the phone (no account, device or files). " +
                "The screenshot stays on the phone unless you share it.", style = WifeTheme.bodyStyle())
            Row(Modifier.fillMaxWidth().padding(top = WifeTheme.gapM), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
                WifeButton("Not now", { mapper.consent(false) }, Modifier.weight(1f))
                WifeButton("Send reports", { mapper.consent(true) }, Modifier.weight(1f), kind = WifeButtonKind.PRIMARY)
            }
        }
    }
    Overlay({ mapper.closeReport() }, mapper.on && mapper.reportOpen) {
        WifeWindow("Item report", { mapper.closeReport() }, Modifier.widthIn(min = 300.dp, max = 520.dp).heightIn(max = 560.dp).padding(WifeTheme.gapL),
            subtitle = s.target?.let { t -> "${s.cls?.label ?: ""} · ${t.displayName} · ${t.placeName}" }) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
                WarField(s.notes, { mapper.notes(it) }, "What should change (optional)", Modifier.fillMaxWidth(), label = "Notes", singleLine = false)
                WifeSectionHeader("Fit")
                val flags = ReportFlag.entries
                for (row in flags.chunked(2)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
                    for (f in row) WifeChip((if (f in s.flags) "✓ " else "") + f.label, { mapper.toggle(f) }, Modifier.weight(1f), trailing = null)
                }
                WifeNote(if (mapper.shot != null) "A screenshot is kept on the phone; it isn't sent. Share it if you want to." else "No screenshot (the scene couldn't be copied).")
            }
            Row(Modifier.fillMaxWidth().padding(top = WifeTheme.gapM), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
                WifeButton("Share screenshot", { mapper.shareShot() }, Modifier.weight(1f), enabled = mapper.shot != null)
                WifeButton("Send report", { npcInfo()?.let { mapper.send(it) } }, Modifier.weight(1f), kind = WifeButtonKind.PRIMARY)
            }
        }
    }
}

/** Options → Mods → Mapped items: what the player classified (item, class, where the report is), each deletable. */
@Composable
fun MappedItemsSection() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<MappedItem>>(emptyList()) }
    var waiting by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    val dir = remember(context) { File(context.filesDir, "reports") }
    fun reload() {
        scope.launch {
            val (list, n) = withContext(Dispatchers.IO) {
                runCatching { MappedItems(File(dir, "mapped.json")).load().sortedByDescending { it.at } to ReportQueue(File(dir, "queue")).size }.getOrDefault(emptyList<MappedItem>() to 0)
            }
            items = list; waiting = n
        }
    }
    LaunchedEffect(Unit) { reload() }
    WifeSectionHeader("Mapped items")
    if (items.isEmpty()) WifeNote("None yet. In a scene, control an NPC and tap Move, then tap a piece of furniture.")
    for (m in items) {
        Row(Modifier.fillMaxWidth().heightIn(min = WifeTheme.touch), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
            Column(Modifier.weight(1f)) {
                Text("${m.displayName} · ${m.placeName}", style = WifeTheme.labelStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${m.cls.label} · ${m.pose.label} · ${m.statusLine}", style = WifeTheme.captionStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            WifeButton("Delete", {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { MappedItems(File(dir, "mapped.json")).delete(m.reportId); ReportQueue(File(dir, "queue")).remove(m.reportId); File(dir, "shots/${m.reportId}.jpg").delete() }
                    }
                    reload()
                }
            }, kind = WifeButtonKind.QUIET)
        }
    }
    if (waiting > 0) {
        WifeButton("Send $waiting waiting report${if (waiting == 1) "" else "s"}", {
            note = "Sending…"
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { ItemMapper.sendWaiting(context) }.getOrNull() }
                note = when (r?.outcome) {
                    SendOutcome.SENT -> "Sent."
                    null -> "Nothing waiting."
                    else -> "Still waiting: ${r?.detail.orEmpty()}"
                }
                reload()
            }
        })
    }
    note?.let { WifeNote(it) }
    WifeNote("A deleted mapping stops changing the item on this phone; a report already sent stays on GitHub.")
}
