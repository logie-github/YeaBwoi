// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.warcraft.AddonStoreOf
import com.logie.warcraft.R
import com.logie.warcraft.turtle.TurtleAddonStore
import com.logie.warcraft.turtle.TurtleFiles
import com.logie.warcraft.ui.RemoteImage
import com.logie.warcraft.ui.drawFrame
import com.logie.warcraft.addons.AddonRecords
import com.logie.warcraft.addons.Warperia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Turtle WoW's add-on manager, the 3.3.5a launcher's (ui/WowAddons.kt) in Turtle WoW's look, over
 * the whole launcher. Browse: warperia.com's 1.12 add-ons, searched, by category and order, each
 * installed in one tap or opened for its page. Installed: what's in Turtle's client, from the site
 * or from zips, with updates, on and off, and delete ([TurtleAddonStore]).
 */
@Composable
fun TurtleAddonManager(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val hasClient = remember { TurtleFiles.hasClient(context) }
    var tab by rememberSaveable { mutableStateOf(AddonTab.BROWSE) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var picture by remember { mutableStateOf<String?>(null) }
    val browse = remember { BrowseState() }
    val jobs by TurtleAddonStore.jobs.collectAsState()
    val installed by TurtleAddonStore.installed.collectAsState()
    val updates by TurtleAddonStore.updates.collectAsState()
    val shelf = Shelf(jobs, installed, updates)

    LaunchedEffect(Unit) {
        TurtleAddonStore.refresh(context)
        TurtleAddonStore.checkUpdates(context, force = false)
    }
    LaunchedEffect(Unit) {
        TurtleAddonStore.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    BackHandler {
        when {
            picture != null -> picture = null
            open != null -> open = null
            else -> onDismiss()
        }
    }

    Box(Modifier.fillMaxSize().ownTouches()) {
        TurtleBackdrop()
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            ManagerBar(tab, installed.size, updates.size, onTab = { tab = it }, onClose = onDismiss)
            if (!hasClient) {
                Text(
                    stringResource(R.string.turtle_addons_no_client),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(20.dp),
                )
            } else {
                when (tab) {
                    AddonTab.BROWSE -> BrowsePane(browse, shelf, onOpen = { open = it })
                    AddonTab.INSTALLED -> InstalledPane(shelf, onOpen = { open = it })
                }
            }
        }
        open?.let { slug -> DetailsPane(slug, shelf, onBack = { open = null }, onPicture = { picture = it }) }
        picture?.let { PictureView(it, onClose = { picture = null }) }
    }
}

private enum class AddonTab { BROWSE, INSTALLED }

/**
 * Touches on this layer stay on it: the first layer a touch hits takes it, so the screen under
 * this one isn't pressed through it. Nothing is consumed here, or the buttons on it would take
 * their taps as cancelled.
 */
private fun Modifier.ownTouches() = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}

/** What's installed and what's under way, as one look for the whole screen. */
private class Shelf(
    val jobs: Map<String, AddonStoreOf.Job>,
    val installed: List<AddonStoreOf.Installed>,
    val updates: Map<String, Warperia.Details>,
) {
    fun installed(slug: String) = installed.firstOrNull { it.record?.slug == slug }

    /** Where the site's add-on [slug] stands; [updated] is the list's date for it, when known. */
    fun status(slug: String, updated: String? = null): AddonStatus {
        jobs[slug]?.let { job -> return if (job is AddonStoreOf.Job.Failed) AddonStatus.Failed else AddonStatus.Busy(job) }
        val record = installed(slug)?.record ?: return AddonStatus.New
        val newer = slug in updates || AddonRecords.isOutdated(record, null, updated)
        return if (newer) AddonStatus.Outdated else AddonStatus.Current
    }
}

private sealed interface AddonStatus {
    data object New : AddonStatus
    data object Current : AddonStatus
    data object Outdated : AddonStatus
    data object Failed : AddonStatus
    data class Busy(val job: AddonStoreOf.Job) : AddonStatus
}

// The bar at the top ------------------------------------------------------------------------------

@Composable
private fun ManagerBar(tab: AddonTab, installed: Int, updates: Int, onTab: (AddonTab) -> Unit, onClose: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 600.dp
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (wide) {
                Text(stringResource(R.string.wow_addons_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(28.dp))
            }
            TabLabel(stringResource(R.string.wow_addons_tab_browse), null, tab == AddonTab.BROWSE) { onTab(AddonTab.BROWSE) }
            Spacer(Modifier.width(18.dp))
            TabLabel(
                stringResource(R.string.wow_addons_tab_installed) + if (installed > 0) " ($installed)" else "",
                updates.takeIf { it > 0 },
                tab == AddonTab.INSTALLED,
            ) { onTab(AddonTab.INSTALLED) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.wow_close), tint = TurtleColors.Stone)
            }
        }
    }
}

@Composable
private fun TabLabel(text: String, badge: Int?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .drawBehind {
                if (selected) {
                    val y = size.height - 2.dp.toPx()
                    drawLine(TurtleColors.Orange, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx())
                }
            }
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = TurtleDisplayStyle.copy(fontSize = 14.sp, color = if (selected) TurtleColors.Parchment else TurtleColors.Stone),
            maxLines = 1,
        )
        if (badge != null) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .background(TurtleColors.Orange)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(badge.toString(), style = MaterialTheme.typography.labelSmall, color = TurtleColors.Night)
            }
        }
    }
}

// Browse ------------------------------------------------------------------------------------------

/** The list as far as it's been read, for what's searched; more pages as the list scrolls. */
@Stable
private class BrowseState {
    var search by mutableStateOf("")
    var query by mutableStateOf(Warperia.Query())
        private set
    val items = mutableStateListOf<Warperia.Listing>()
    var categories by mutableStateOf<List<Warperia.Category>>(emptyList())
    var page by mutableIntStateOf(0)
    var pages by mutableIntStateOf(1)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    val grid = LazyGridState()
    /** Changes with the query: an answer to an older one is dropped. */
    private var generation = 0

    val hasMore get() = page == 0 || page < pages

    fun change(query: Warperia.Query) {
        if (query == this.query) return
        generation++
        this.query = query
        items.clear()
        page = 0
        pages = 1
        loading = false
        error = null
    }

    suspend fun loadMore() {
        if (loading || !hasMore) return
        val asked = generation
        loading = true
        error = null
        try {
            val result = TurtleAddonStore.list(query, page + 1)
            if (asked != generation) return
            if (categories.isEmpty()) categories = result.categories
            // Pages can shift while they're read (an add-on updated): none twice.
            val seen = items.mapTo(HashSet()) { it.slug }
            items += result.items.filter { seen.add(it.slug) }
            page += 1
            pages = if (result.items.isEmpty()) page else result.pages
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (asked == generation) error = e.message ?: e.javaClass.simpleName
        } finally {
            if (asked == generation) loading = false
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrowsePane(state: BrowseState, shelf: Shelf, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    // What's typed is searched once the typing stops.
    LaunchedEffect(state.search) {
        delay(700)
        state.change(state.query.copy(search = state.search.trim()))
    }
    LaunchedEffect(state.query) {
        if (state.page == 0) {
            state.grid.scrollToItem(0)
            state.loadMore()
        }
    }
    // The next page when the list nears its end.
    LaunchedEffect(state) {
        snapshotFlow {
            val info = state.grid.layoutInfo
            val near = (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 6
            Triple(near, state.items.size, state.error)
        }.collect { (near, _, error) ->
            if (near && error == null && state.page > 0) state.loadMore()
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.search,
            onValueChange = { state.search = it },
            placeholder = { Text(stringResource(R.string.turtle_addons_search)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TurtleColors.Stone) },
            trailingIcon = {
                if (state.search.isNotEmpty()) {
                    IconButton(onClick = { state.search = ""; state.change(state.query.copy(search = "")) }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.turtle_addons_search_clear), tint = TurtleColors.Stone)
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSearch = {
                state.change(state.query.copy(search = state.search.trim()))
                focus.clearFocus()
            }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TurtleColors.Gold,
                unfocusedBorderColor = TurtleColors.Brown,
                cursorColor = TurtleColors.Gold,
                focusedContainerColor = TurtleColors.Bark.copy(alpha = 0.7f),
                unfocusedContainerColor = TurtleColors.Bark.copy(alpha = 0.7f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((order, label) in listOf(
                Warperia.Order.POPULAR to R.string.wow_addons_order_popular,
                Warperia.Order.NEW to R.string.wow_addons_order_new,
                Warperia.Order.UPDATED to R.string.wow_addons_order_updated,
            )) {
                TurtleChip(stringResource(label), state.query.order == order) { state.change(state.query.copy(order = order)) }
            }
        }
        if (state.categories.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TurtleChip(stringResource(R.string.wow_addons_all), state.query.category == null) {
                    state.change(state.query.copy(category = null))
                }
                for (category in state.categories) {
                    TurtleChip(category.name, state.query.category == category.slug) {
                        state.change(state.query.copy(category = category.slug))
                    }
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 340.dp),
            state = state.grid,
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.items, key = { it.slug }) { listing ->
                AddonCard(
                    listing,
                    shelf.status(listing.slug, listing.updated),
                    onOpen = { onOpen(listing.slug) },
                    onInstall = { TurtleAddonStore.install(context, listing.slug) },
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val error = state.error
                    when {
                        state.loading -> TurtleSpinner(36.dp)
                        error != null -> {
                            Text(stringResource(R.string.wow_addons_load_failed, error), style = MaterialTheme.typography.bodyMedium)
                            TurtleButton(stringResource(R.string.wow_addons_retry)) { scope.launch { state.loadMore() } }
                        }
                        state.items.isEmpty() && !state.hasMore ->
                            Text(stringResource(R.string.wow_addons_nothing), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(stringResource(R.string.wow_addons_source), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** A choice in a row of them: the chosen one lit in a gold frame. */
@Composable
private fun TurtleChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 36.dp)
            .drawBehind {
                if (selected) {
                    drawRect(TurtleColors.Gold.copy(alpha = 0.12f))
                    drawFrame(arm = 5.dp.toPx(), line = TurtleColors.Brown)
                } else {
                    drawRect(TurtleColors.Bark.copy(alpha = 0.6f))
                    drawFrame(corner = TurtleColors.Brown, alpha = 0.8f, arm = 4.dp.toPx())
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) TurtleColors.Parchment else TurtleColors.Stone, maxLines = 1)
    }
}

@Composable
private fun AddonCard(listing: Warperia.Listing, status: AddonStatus, onOpen: () -> Unit, onInstall: () -> Unit) {
    TurtleWindow(Modifier.fillMaxWidth(), padding = PaddingValues(12.dp), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(listing.logo, 60.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(listing.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                listing.author?.let {
                    Text(stringResource(R.string.wow_addons_by, it), style = MaterialTheme.typography.labelMedium, color = TurtleColors.Stone, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                listing.summary?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val meta = listOfNotNull(
                    listing.downloads?.let { stringResource(R.string.wow_addons_downloads, it) },
                    listing.updated?.let { Warperia.showDay(it) },
                    listing.categories.firstOrNull(),
                ).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(meta, style = MaterialTheme.typography.labelSmall, color = TurtleColors.Stone, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(10.dp))
            InstallButton(status, onInstall)
        }
    }
}

@Composable
internal fun Logo(url: String?, size: Dp) {
    RemoteImage(
        url,
        Modifier.size(size),
        placeholder = {
            Box(Modifier.fillMaxSize().background(TurtleColors.Earth), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Extension, contentDescription = null, tint = TurtleColors.Brown, modifier = Modifier.size(size * 0.5f))
            }
        },
    )
}

/** Install, Update, or how the install is going. */
@Composable
private fun InstallButton(status: AddonStatus, onInstall: () -> Unit) {
    val width = Modifier.widthIn(min = 112.dp)
    when (status) {
        AddonStatus.New -> TurtleButton(stringResource(R.string.wow_addons_install), width, onClick = onInstall)
        AddonStatus.Outdated -> TurtleButton(stringResource(R.string.wow_addons_update), width, onClick = onInstall)
        AddonStatus.Failed -> TurtleButton(stringResource(R.string.wow_addons_retry), width, onClick = onInstall)
        AddonStatus.Current -> TurtleButton(stringResource(R.string.wow_addons_is_installed), width, primary = false, enabled = false) {}
        is AddonStatus.Busy -> Row(
            width
                .heightIn(min = 44.dp)
                .drawBehind { drawFrame(corner = TurtleColors.Stone, alpha = 0.7f, arm = 7.dp.toPx()) }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            TurtleSpinner(20.dp)
            Spacer(Modifier.width(8.dp))
            val job = status.job
            Text(
                when (job) {
                    AddonStoreOf.Job.Queued -> stringResource(R.string.wow_addons_queued)
                    is AddonStoreOf.Job.Waiting -> stringResource(R.string.wow_addons_waiting, job.secondsLeft)
                    is AddonStoreOf.Job.Downloading -> job.fraction?.let { stringResource(R.string.wow_addons_percent, (it * 100).toInt()) }
                        ?: stringResource(R.string.wow_addons_downloading)
                    AddonStoreOf.Job.Installing -> stringResource(R.string.wow_addons_installing)
                    is AddonStoreOf.Job.Failed -> ""
                },
                style = MaterialTheme.typography.labelMedium,
                color = TurtleColors.Parchment,
                maxLines = 1,
            )
        }
    }
}

// An add-on's page --------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsPane(slug: String, shelf: Shelf, onBack: () -> Unit, onPicture: (String) -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    val result by produceState<Result<Warperia.Details>?>(null, slug, attempt) {
        value = null
        value = try {
            Result.success(TurtleAddonStore.details(slug))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    Box(Modifier.fillMaxSize().ownTouches()) {
        TurtleBackdrop()
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.wow_addons_back), tint = TurtleColors.Stone)
                }
                Text(
                    result?.getOrNull()?.title ?: "",
                    style = TurtleDisplayStyle.copy(fontSize = 15.sp, color = TurtleColors.Stone),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val current = result
            when {
                current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { TurtleSpinner(44.dp) }
                current.isFailure -> Column(
                    Modifier.fillMaxSize().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val error = current.exceptionOrNull()
                    Text(
                        stringResource(R.string.wow_addons_details_failed, error?.message ?: error?.javaClass?.simpleName.orEmpty()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TurtleButton(stringResource(R.string.wow_addons_retry)) { attempt++ }
                }
                else -> DetailsBody(current.getOrThrow(), shelf, onPicture)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsBody(details: Warperia.Details, shelf: Shelf, onPicture: (String) -> Unit) {
    val context = LocalContext.current
    val installed = shelf.installed(details.slug)
    val status = shelf.status(details.slug, details.updated).let {
        // The page's own version and date against what's installed.
        val record = installed?.record
        if (it == AddonStatus.Current && record != null && AddonRecords.isOutdated(record, details.version, details.updated)) AddonStatus.Outdated else it
    }
    var confirmDelete by remember(details.slug) { mutableStateOf(false) }
    var allChanges by remember(details.slug) { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val column = Modifier.widthIn(max = 860.dp).fillMaxWidth()
        item {
            Row(column, verticalAlignment = Alignment.CenterVertically) {
                Logo(details.logo, 88.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(details.title, style = MaterialTheme.typography.headlineMedium)
                    if (details.developers.isNotEmpty()) {
                        Text(stringResource(R.string.wow_addons_by, details.developers.joinToString(", ")), style = MaterialTheme.typography.labelMedium, color = TurtleColors.Stone)
                    }
                    details.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        item {
            FlowRow(
                column.padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                InstallButton(status) { TurtleAddonStore.install(context, details.slug) }
                if (installed != null) {
                    TurtleButton(stringResource(if (installed.on) R.string.wow_addons_turn_off else R.string.wow_addons_turn_on), primary = false) {
                        TurtleAddonStore.setOn(context, installed, !installed.on)
                    }
                    TurtleButton(stringResource(if (confirmDelete) R.string.wow_addons_delete_confirm else R.string.wow_addons_delete), primary = false) {
                        if (!confirmDelete) {
                            confirmDelete = true
                        } else {
                            TurtleAddonStore.delete(context, installed)
                            confirmDelete = false
                        }
                    }
                }
                TurtleButton(stringResource(R.string.wow_addons_website), primary = false) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Warperia.pageUrl(details.slug, Warperia.Flavor.VANILLA)))) }
                }
            }
        }
        item {
            Column(column, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                details.version?.let { TurtleStatusLine(stringResource(R.string.wow_addons_fact_version), it) }
                installed?.version?.let {
                    TurtleStatusLine(stringResource(R.string.wow_addons_fact_installed), it, good = status == AddonStatus.Current)
                }
                details.updated?.let { TurtleStatusLine(stringResource(R.string.wow_addons_fact_updated), Warperia.showDay(it)) }
                details.uploaded?.let { TurtleStatusLine(stringResource(R.string.wow_addons_fact_uploaded), Warperia.showDay(it)) }
                details.downloads?.let { TurtleStatusLine(stringResource(R.string.wow_addons_fact_downloads), it) }
                if (details.categories.isNotEmpty()) {
                    TurtleStatusLine(stringResource(R.string.wow_addons_fact_categories), details.categories.joinToString(", "))
                }
            }
        }
        if (details.screenshots.isNotEmpty()) {
            item { TurtleTitle(stringResource(R.string.wow_addons_pictures), column.padding(top = 8.dp)) }
            item {
                LazyRow(column, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(details.screenshots) { url ->
                        RemoteImage(
                            url,
                            Modifier
                                .height(170.dp)
                                .aspectRatio(16f / 10f)
                                .clickable { onPicture(url) },
                            maxPx = 720,
                            placeholder = {
                                Box(Modifier.fillMaxSize().background(TurtleColors.Earth), contentAlignment = Alignment.Center) { TurtleSpinner(28.dp) }
                            },
                        )
                    }
                }
            }
        }
        if (details.description.isNotEmpty()) {
            item { TurtleTitle(stringResource(R.string.wow_addons_about), column.padding(top = 8.dp)) }
            items(details.description) { block -> DescriptionBlock(block, column) }
        }
        if (details.changelog.isNotEmpty()) {
            item { TurtleTitle(stringResource(R.string.wow_addons_changes), column.padding(top = 8.dp)) }
            val shown = if (allChanges) details.changelog else details.changelog.take(5)
            items(shown) { change ->
                Column(column) {
                    Text(listOfNotNull(change.version, change.date?.let { Warperia.showDay(it) }).joinToString(" · "), style = MaterialTheme.typography.titleSmall)
                    change.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (!allChanges && details.changelog.size > shown.size) {
                item {
                    Box(column) {
                        TurtleButton(stringResource(R.string.wow_addons_show_all, details.changelog.size), primary = false) { allChanges = true }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DescriptionBlock(block: Warperia.Block, modifier: Modifier) {
    when (block) {
        is Warperia.Block.Heading -> Text(block.text, style = MaterialTheme.typography.titleSmall, color = TurtleColors.Orange, modifier = modifier.padding(top = 4.dp))
        is Warperia.Block.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyMedium, modifier = modifier)
        is Warperia.Block.Item -> Row(modifier) {
            Text(block.number?.let { "$it." } ?: "•", style = MaterialTheme.typography.bodyMedium, color = TurtleColors.Gold, modifier = Modifier.width(24.dp))
            Text(block.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        is Warperia.Block.Code -> Box(
            modifier
                .background(Color.Black.copy(alpha = 0.45f))
                .horizontalScroll(rememberScrollState())
                .padding(10.dp)
        ) {
            Text(block.text, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, color = TurtleColors.Stone))
        }
        Warperia.Block.Rule -> Box(modifier.padding(vertical = 4.dp).height(1.dp).background(TurtleColors.Brown.copy(alpha = 0.6f)))
    }
}

/** A screenshot over everything, as big as the screen allows; a tap closes it. */
@Composable
private fun PictureView(url: String, onClose: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        RemoteImage(
            url,
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(12.dp),
            maxPx = 2048,
            contentScale = ContentScale.Fit,
            placeholder = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { TurtleSpinner(44.dp) } },
        )
    }
}

// Installed ---------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstalledPane(shelf: Shelf, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val checking by TurtleAddonStore.checking.collectAsState()
    var importing by remember { mutableStateOf(false) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val result = runCatching { TurtleAddonStore.importZip(context, uri) }
            importing = false
            val message = result.fold(
                onSuccess = { context.getString(R.string.wow_addons_installed, it.joinToString(", ")) },
                onFailure = {
                    if (it is com.logie.warcraft.Addons.NoAddonException) context.getString(R.string.wow_addons_none_in_zip)
                    else context.getString(R.string.wow_addons_failed, it.message ?: it.javaClass.simpleName)
                },
            )
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val column = Modifier.widthIn(max = 860.dp).fillMaxWidth()
        item {
            Column(column, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (shelf.updates.isNotEmpty()) {
                        TurtleButton(stringResource(R.string.wow_addons_update_all, shelf.updates.size)) { TurtleAddonStore.updateAll(context) }
                    }
                    TurtleButton(
                        stringResource(if (checking) R.string.wow_addons_checking else R.string.wow_addons_check),
                        primary = false,
                        enabled = !checking,
                    ) { TurtleAddonStore.checkUpdates(context, force = true) }
                    TurtleButton(stringResource(R.string.wow_addons_import), primary = false, enabled = !importing) {
                        pick.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                    }
                }
                Text(stringResource(R.string.wow_addons_installed_body), style = MaterialTheme.typography.bodySmall)
                if (importing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TurtleSpinner(28.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.wow_addons_installing), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (shelf.installed.isEmpty() && !importing) {
                    Text(stringResource(R.string.wow_addons_empty), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        items(shelf.installed, key = { it.key }) { item -> InstalledRow(item, shelf, column, onOpen) }
    }
}

@Composable
private fun InstalledRow(item: AddonStoreOf.Installed, shelf: Shelf, modifier: Modifier, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    var confirmDelete by remember(item.key) { mutableStateOf(false) }
    val record = item.record
    val status = record?.let { shelf.status(it.slug) }
    val newer = record?.let { shelf.updates[it.slug] }
    TurtleWindow(modifier, padding = PaddingValues(12.dp), onClick = record?.let { { onOpen(it.slug) } }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(record?.logo, 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (item.on) TurtleColors.Parchment else TurtleColors.Stone,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val line = listOfNotNull(
                        item.version,
                        stringResource(if (record != null) R.string.wow_addons_from_site else R.string.wow_addons_from_zip),
                        if (item.on) null else stringResource(R.string.wow_addons_off),
                        if (item.forThisGame || record != null) null else stringResource(R.string.wow_addons_other_version),
                    ).joinToString(" · ")
                    Text(line, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (newer != null) {
                        Text(
                            stringResource(R.string.wow_addons_update_to, listOfNotNull(newer.version, newer.updated?.let { Warperia.showDay(it) }).joinToString(" · ")),
                            style = MaterialTheme.typography.labelMedium,
                            color = TurtleColors.Orange,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                TurtleSwitch(item.on) { TurtleAddonStore.setOn(context, item, it) }
            }
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (record != null && status != null && status != AddonStatus.Current) {
                    InstallButton(status) { TurtleAddonStore.install(context, record.slug) }
                }
                TurtleButton(stringResource(if (confirmDelete) R.string.wow_addons_delete_confirm else R.string.wow_addons_delete), primary = false) {
                    if (!confirmDelete) {
                        confirmDelete = true
                    } else {
                        TurtleAddonStore.delete(context, item)
                        confirmDelete = false
                    }
                }
            }
        }
    }
}

/** For the launcher's Add-ons tile: how many, and how many updates when there are some. */
@Composable
private fun turtleAddonCountText(count: Int): String {
    val updates by TurtleAddonStore.updates.collectAsState()
    val addons = pluralStringResource(R.plurals.wow_tile_addons_count, count, count)
    return if (updates.isEmpty()) addons else addons + " · " + pluralStringResource(R.plurals.wow_tile_addons_updates, updates.size, updates.size)
}

/** The Add-ons tile's line (as the 3.3.5a launcher's): how many add-ons Turtle's client has. */
@Composable
fun turtleAddonsSummary(context: android.content.Context, facts: TurtleFacts): String {
    if (!facts.client) return stringResource(R.string.wow_tile_addons_no_client)
    LaunchedEffect(facts) { TurtleAddonStore.refresh(context) }
    val installed by TurtleAddonStore.installed.collectAsState()
    val count = installed.size
    return if (count == 0) stringResource(R.string.wow_tile_addons_none) else turtleAddonCountText(count)
}
