// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.ui

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.warcraft.BuildConfig
import com.logie.warcraft.ImportService
import com.logie.warcraft.Imports
import com.logie.warcraft.ModuleStore
import com.logie.warcraft.R
import com.logie.warcraft.Server
import com.logie.warcraft.modules.ModuleBuild
import com.logie.warcraft.modules.ModuleCatalog
import com.logie.warcraft.modules.ModuleReadme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The module browser, over the whole launcher. Browse: AzerothCore's modules (GitHub's topic
 * azerothcore-module), searched and ordered, each added to the realm's list in one tap or opened
 * for its README. Your realm: the picked modules, the realm built with them on GitHub (this app's
 * repository, with the user's own token) and put in place of the app's own, or the app's own back.
 */
@Composable
fun ModuleManager(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(ModuleTab.BROWSE) }
    var open by remember { mutableStateOf<ModuleCatalog.Listing?>(null) }
    val browse = remember { ModuleBrowseState() }
    remember { ModuleStore.load(context) }
    val chosen by ModuleStore.chosen.collectAsState()
    val pending by ModuleStore.pending.collectAsState()

    BackHandler {
        if (open != null) open = null else onDismiss()
    }

    Box(Modifier.fillMaxSize().ownTouches()) {
        WowBackdrop()
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            ModulesBar(tab, chosen.size, building = pending?.isDone == false, onTab = { tab = it }, onClose = onDismiss)
            if (!ModuleStore.isAvailable) {
                Text(stringResource(R.string.wow_modules_unavailable), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp))
            } else {
                when (tab) {
                    ModuleTab.BROWSE -> ModuleBrowsePane(browse, chosen, onOpen = { open = it })
                    ModuleTab.REALM -> RealmPane()
                }
            }
        }
        open?.let { ModuleDetailsPane(it, chosen, onBack = { open = null }) }
    }
}

private enum class ModuleTab { BROWSE, REALM }

@Composable
private fun ModulesBar(tab: ModuleTab, chosen: Int, building: Boolean, onTab: (ModuleTab) -> Unit, onClose: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 600.dp
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (wide) {
                Text(stringResource(R.string.wow_modules_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(28.dp))
            }
            TabLabel(stringResource(R.string.wow_modules_tab_browse), null, tab == ModuleTab.BROWSE) { onTab(ModuleTab.BROWSE) }
            Spacer(Modifier.width(18.dp))
            TabLabel(
                stringResource(R.string.wow_modules_tab_realm) + if (chosen > 0) " ($chosen)" else "",
                if (building) 1 else null,
                tab == ModuleTab.REALM,
            ) { onTab(ModuleTab.REALM) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.wow_close), tint = WowColors.Mist)
            }
        }
    }
}

// Browse ------------------------------------------------------------------------------------------

/** The list as far as it's been read, for what's searched; more pages as the list scrolls. */
@Stable
private class ModuleBrowseState {
    var search by mutableStateOf("")
    var query by mutableStateOf("")
        private set
    var order by mutableStateOf(ModuleCatalog.Order.STARS)
        private set
    val items = mutableStateListOf<ModuleCatalog.Listing>()
    var total by mutableIntStateOf(0)
    var page by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    val grid = LazyGridState()
    private var generation = 0

    // GitHub's search answers the first thousand.
    val hasMore get() = page == 0 || (items.size < minOf(total, 1000) && page * ModuleCatalog.PER_PAGE < 1000)

    fun change(query: String = this.query, order: ModuleCatalog.Order = this.order) {
        if (query == this.query && order == this.order) return
        generation++
        this.query = query
        this.order = order
        items.clear()
        total = 0
        page = 0
        loading = false
        error = null
    }

    suspend fun loadMore(context: android.content.Context) {
        if (loading || !hasMore) return
        val asked = generation
        loading = true
        error = null
        try {
            val result = ModuleStore.search(context, query, order, page + 1)
            if (asked != generation) return
            val seen = items.mapTo(HashSet()) { it.fullName }
            items += result.items.filter { seen.add(it.fullName) }
            total = if (result.items.isEmpty()) items.size else result.total
            page += 1
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (asked == generation) error = e.message ?: e.javaClass.simpleName
        } finally {
            if (asked == generation) loading = false
        }
    }
}

@Composable
private fun ModuleBrowsePane(state: ModuleBrowseState, chosen: List<ModuleBuild.Chosen>, onOpen: (ModuleCatalog.Listing) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    LaunchedEffect(state.search) {
        delay(700)
        state.change(query = state.search.trim())
    }
    LaunchedEffect(state.query, state.order) {
        if (state.page == 0) {
            state.grid.scrollToItem(0)
            state.loadMore(context)
        }
    }
    LaunchedEffect(state) {
        snapshotFlow {
            val info = state.grid.layoutInfo
            val near = (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 6
            Triple(near, state.items.size, state.error)
        }.collect { (near, _, error) ->
            if (near && error == null && state.page > 0) state.loadMore(context)
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.search,
            onValueChange = { state.search = it },
            placeholder = { Text(stringResource(R.string.wow_modules_search)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = WowColors.Mist) },
            trailingIcon = {
                if (state.search.isNotEmpty()) {
                    IconButton(onClick = { state.search = ""; state.change(query = "") }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.wow_addons_search_clear), tint = WowColors.Mist)
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSearch = {
                state.change(query = state.search.trim())
                focus.clearFocus()
            }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = WowColors.Ice,
                unfocusedBorderColor = WowColors.Steel,
                cursorColor = WowColors.Ice,
                focusedContainerColor = WowColors.Night.copy(alpha = 0.7f),
                unfocusedContainerColor = WowColors.Night.copy(alpha = 0.7f),
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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WowChip(stringResource(R.string.wow_modules_order_stars), state.order == ModuleCatalog.Order.STARS) {
                state.change(order = ModuleCatalog.Order.STARS)
            }
            WowChip(stringResource(R.string.wow_modules_order_updated), state.order == ModuleCatalog.Order.UPDATED) {
                state.change(order = ModuleCatalog.Order.UPDATED)
            }
            if (state.total > 0) {
                Text(
                    pluralStringResource(R.plurals.wow_modules_count, state.total, state.total),
                    style = MaterialTheme.typography.labelMedium,
                    color = WowColors.Mist,
                    modifier = Modifier.padding(start = 6.dp),
                )
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
            items(state.items, key = { it.fullName }) { listing ->
                ModuleCard(listing, chosen, onOpen = { onOpen(listing) })
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
                        state.loading -> WowSpinner(36.dp)
                        error != null -> {
                            Text(stringResource(R.string.wow_modules_load_failed, error), style = MaterialTheme.typography.bodyMedium)
                            WowButton(stringResource(R.string.wow_addons_retry)) { scope.launch { state.loadMore(context) } }
                        }
                        state.items.isEmpty() && !state.hasMore ->
                            Text(stringResource(R.string.wow_addons_nothing), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(stringResource(R.string.wow_modules_source), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** A module's line of facts: stars, last change, language, archived. */
@Composable
private fun facts(listing: ModuleCatalog.Listing): String = listOfNotNull(
    stringResource(R.string.wow_modules_stars, listing.stars),
    ModuleCatalog.day(listing.updated)?.let { stringResource(R.string.wow_modules_updated, it) },
    listing.language,
    if (listing.archived) stringResource(R.string.wow_modules_archived) else null,
).joinToString(" · ")

@Composable
private fun ModuleCard(listing: ModuleCatalog.Listing, chosen: List<ModuleBuild.Chosen>, onOpen: () -> Unit) {
    WowWindow(Modifier.fillMaxWidth(), padding = PaddingValues(12.dp), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(listing.avatar, 52.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(listing.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.wow_addons_by, listing.owner), style = MaterialTheme.typography.labelMedium, color = WowColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                listing.description?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(facts(listing), style = MaterialTheme.typography.labelSmall, color = WowColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            AddButton(listing, chosen)
        }
    }
}

/** Add, Remove, or In the realm for the realm's own. */
@Composable
private fun AddButton(listing: ModuleCatalog.Listing, chosen: List<ModuleBuild.Chosen>) {
    val context = LocalContext.current
    val width = Modifier.widthIn(min = 112.dp)
    when {
        listing.name in ModuleStore.baseModules ->
            WowButton(stringResource(R.string.wow_modules_built_in), width, primary = false, enabled = false) {}
        chosen.any { it.fullName.equals(listing.fullName, ignoreCase = true) } ->
            WowButton(stringResource(R.string.wow_modules_remove), width, primary = false) { ModuleStore.remove(context, listing.fullName) }
        else -> WowButton(stringResource(R.string.wow_modules_add), width) { ModuleStore.add(context, listing) }
    }
}

// A module's page ---------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModuleDetailsPane(listing: ModuleCatalog.Listing, chosen: List<ModuleBuild.Chosen>, onBack: () -> Unit) {
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    val readme by produceState<Result<String?>?>(null, listing.fullName, attempt) {
        value = null
        value = try {
            Result.success(ModuleStore.readme(context, listing.fullName))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    val blocks = remember(readme) { readme?.getOrNull()?.let { ModuleReadme.blocks(it) } ?: emptyList() }

    Box(Modifier.fillMaxSize().ownTouches()) {
        WowBackdrop()
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.wow_addons_back), tint = WowColors.Mist)
                }
                Text(listing.fullName, style = WowDisplayStyle.copy(fontSize = 15.sp, color = WowColors.Mist), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val column = Modifier.widthIn(max = 860.dp).fillMaxWidth()
                item {
                    Row(column, verticalAlignment = Alignment.CenterVertically) {
                        Logo(listing.avatar, 80.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(listing.name, style = MaterialTheme.typography.headlineMedium)
                            Text(stringResource(R.string.wow_addons_by, listing.owner), style = MaterialTheme.typography.labelMedium, color = WowColors.Mist)
                            listing.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
                item {
                    FlowRow(
                        column.padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AddButton(listing, chosen)
                        WowButton(stringResource(R.string.wow_modules_github), primary = false) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(listing.url))) }
                        }
                    }
                }
                item {
                    Column(column, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        WowStatusLine(stringResource(R.string.wow_modules_fact_stars), listing.stars.toString())
                        ModuleCatalog.day(listing.updated)?.let { WowStatusLine(stringResource(R.string.wow_modules_fact_updated), it) }
                        WowStatusLine(stringResource(R.string.wow_modules_fact_branch), listing.defaultBranch)
                        listing.language?.let { WowStatusLine(stringResource(R.string.wow_modules_fact_language), it) }
                        if (listing.archived) WowStatusLine(stringResource(R.string.wow_modules_fact_state), stringResource(R.string.wow_modules_archived), good = false)
                        Text(stringResource(R.string.wow_modules_details_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                item { WowTitle(stringResource(R.string.wow_modules_readme), column.padding(top = 8.dp)) }
                val current = readme
                when {
                    current == null -> item { WowSpinner(36.dp) }
                    current.isFailure -> item {
                        Column(column, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val error = current.exceptionOrNull()
                            Text(stringResource(R.string.wow_modules_load_failed, error?.message ?: error?.javaClass?.simpleName.orEmpty()), style = MaterialTheme.typography.bodyMedium)
                            WowButton(stringResource(R.string.wow_addons_retry)) { attempt++ }
                        }
                    }
                    blocks.isEmpty() -> item { Text(stringResource(R.string.wow_modules_no_readme), column, style = MaterialTheme.typography.bodyMedium) }
                    else -> items(blocks) { block -> DescriptionBlock(block, column) }
                }
            }
        }
    }
}

// Your realm --------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RealmPane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chosen by ModuleStore.chosen.collectAsState()
    val pending by ModuleStore.pending.collectAsState()
    val realm by ModuleStore.realm.collectAsState()
    val saved by ModuleStore.saved.collectAsState()
    val hasToken by ModuleStore.hasToken.collectAsState()
    val imports by Imports.state.collectAsState()
    val server by Server.status.collectAsState()
    var asking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tokenDialog by remember { mutableStateOf(false) }
    var confirmStock by remember { mutableStateOf(false) }

    val working = imports is Imports.State.Working
    val installing = (imports as? Imports.State.Working)?.kind == Imports.Kind.MODULES
    val installFailed = (imports as? Imports.State.Failed)?.takeIf { it.kind == Imports.Kind.MODULES }
    val serverStopped = server.phase == Server.Phase.STOPPED || server.phase == Server.Phase.FAILED
    val current = realm?.takeIf { it.versionCode == BuildConfig.VERSION_CODE }
    // The modules the newest build left out (they don't compile with this realm).
    val leftOut = (pending?.leftOut ?: current?.leftOut).orEmpty()

    // How the build is going, every half minute while it goes (and once when the screen opens);
    // once it's built, what went in.
    LaunchedEffect(pending?.request, pending?.isDone, pending?.leftOut == null) {
        val build = pending ?: return@LaunchedEffect
        if (build.isDone) {
            if (build.succeeded && build.leftOut == null) {
                runCatching { ModuleStore.refresh(context) }.onFailure { if (it is CancellationException) throw it }
            }
            return@LaunchedEffect
        }
        while (true) {
            runCatching { ModuleStore.refresh(context) }
                .onFailure { if (it is CancellationException) throw it else error = it.message ?: it.javaClass.simpleName }
                .onSuccess { error = null }
            if (ModuleStore.pending.value?.isDone != false) break
            delay(30_000)
        }
    }

    fun startBuild() {
        asking = true
        error = null
        scope.launch {
            runCatching { ModuleStore.startBuild(context) }
                .onFailure { if (it is CancellationException) throw it else error = it.message ?: it.javaClass.simpleName }
            asking = false
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val column = Modifier.widthIn(max = 860.dp).fillMaxWidth()
        item { Text(stringResource(R.string.wow_modules_realm_body), column, style = MaterialTheme.typography.bodySmall) }

        // The realm in now.
        item {
            WowWindow(column, padding = PaddingValues(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.wow_modules_realm_now), style = MaterialTheme.typography.titleMedium)
                    when {
                        current != null -> {
                            Text(
                                stringResource(R.string.wow_modules_realm_with, current.modules.joinToString(", ") { it.name }),
                                style = MaterialTheme.typography.bodyMedium,
                                color = WowColors.Good,
                            )
                            if (current.builtAt > 0) {
                                Text(stringResource(R.string.wow_modules_realm_built, DateUtils.getRelativeTimeSpanString(current.builtAt).toString()), style = MaterialTheme.typography.bodySmall)
                            }
                            if (current.leftOut.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.wow_modules_left_out, current.leftOut.joinToString(", ") { it.name }),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = WowColors.Mist,
                                )
                            }
                            WowButton(
                                stringResource(if (confirmStock) R.string.wow_modules_stock_confirm else R.string.wow_modules_stock),
                                primary = false,
                                enabled = !working,
                            ) {
                                if (!confirmStock) {
                                    confirmStock = true
                                } else {
                                    confirmStock = false
                                    Imports.clearResult()
                                    ImportService.installModules(context, stock = true)
                                }
                            }
                        }
                        realm != null -> {
                            Text(stringResource(R.string.wow_modules_realm_old), style = MaterialTheme.typography.bodyMedium)
                            WowButton(
                                stringResource(R.string.wow_modules_build_again_here),
                                enabled = hasToken && !asking && pending?.isDone != false,
                            ) {
                                realm?.let { ModuleStore.useModules(context, it) }
                                startBuild()
                            }
                        }
                        else -> Text(stringResource(R.string.wow_modules_realm_stock), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        stringResource(R.string.wow_modules_realm_own, ModuleStore.baseModules.sorted().joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = WowColors.Mist,
                    )
                }
            }
        }

        // Putting a realm in.
        if (installing || installFailed != null) {
            item {
                WowWindow(column, padding = PaddingValues(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (installing) {
                            val state = imports as Imports.State.Working
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                WowSpinner(24.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(state.detail ?: importTitle(state), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                            }
                            state.progress?.let { WowProgressLine(it) }
                        } else if (installFailed != null) {
                            Text(installFailed.message, style = MaterialTheme.typography.bodyMedium, color = WowColors.Blood)
                            WowButton(stringResource(R.string.wow_hd_retry), primary = false) {
                                Imports.clearResult()
                                ImportService.installModules(context)
                            }
                        }
                    }
                }
            }
        }

        // The build.
        item {
            WowWindow(column, padding = PaddingValues(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.wow_modules_build_title), style = MaterialTheme.typography.titleMedium)
                    val build = pending
                    when {
                        build == null -> {
                            Text(
                                if (chosen.isEmpty()) stringResource(R.string.wow_modules_build_none)
                                else stringResource(R.string.wow_modules_build_ready, chosen.joinToString(", ") { it.name }),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                WowButton(
                                    stringResource(if (asking) R.string.wow_modules_build_asking else R.string.wow_modules_build),
                                    enabled = chosen.isNotEmpty() && hasToken && !asking,
                                ) { startBuild() }
                            }
                        }
                        !build.isDone -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                WowSpinner(24.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    build.step?.let { stringResource(R.string.wow_modules_build_step, it) }
                                        ?: stringResource(if (build.runId == null) R.string.wow_modules_build_waiting else R.string.wow_modules_build_going),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Text(
                                stringResource(
                                    R.string.wow_modules_build_since,
                                    build.modules.joinToString(", ") { it.name },
                                    DateUtils.getRelativeTimeSpanString(build.startedAt).toString(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                build.runUrl?.let { url ->
                                    WowButton(stringResource(R.string.wow_modules_build_open), primary = false) {
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                    }
                                }
                                WowButton(stringResource(R.string.wow_modules_build_cancel), primary = false) {
                                    scope.launch { runCatching { ModuleStore.cancelBuild(context) } }
                                }
                            }
                        }
                        build.succeeded && build.versionCode != BuildConfig.VERSION_CODE -> {
                            Text(stringResource(R.string.wow_modules_build_other_version), style = MaterialTheme.typography.bodyMedium)
                            WowButton(stringResource(R.string.wow_modules_build_dismiss), primary = false) { ModuleStore.dismissBuild(context) }
                        }
                        build.succeeded -> {
                            Text(stringResource(R.string.wow_modules_build_done), style = MaterialTheme.typography.bodyMedium, color = WowColors.Good)
                            build.leftOut?.takeIf { it.isNotEmpty() }?.let { out ->
                                Text(stringResource(R.string.wow_modules_left_out, out.joinToString(", ") { it.name }), style = MaterialTheme.typography.bodyMedium, color = WowColors.Blood)
                                for (module in out) {
                                    if (module.error.isNotBlank()) {
                                        Text("${module.name}: ${module.error}", style = MaterialTheme.typography.labelSmall, color = WowColors.Mist, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            if (!serverStopped) Text(stringResource(R.string.wow_modules_build_stops), style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                WowButton(stringResource(R.string.wow_modules_put_in), enabled = !working) {
                                    Imports.clearResult()
                                    ImportService.installModules(context)
                                }
                                WowButton(stringResource(R.string.wow_modules_build_dismiss), primary = false, enabled = !working) {
                                    ModuleStore.dismissBuild(context)
                                }
                            }
                        }
                        else -> {
                            Text(
                                build.step?.let { stringResource(R.string.wow_modules_build_failed_at, it) }
                                    ?: stringResource(R.string.wow_modules_build_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = WowColors.Blood,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                WowButton(
                                    stringResource(if (asking) R.string.wow_modules_build_asking else R.string.wow_modules_build_again),
                                    enabled = chosen.isNotEmpty() && hasToken && !asking,
                                ) { startBuild() }
                                build.runUrl?.let { url ->
                                    WowButton(stringResource(R.string.wow_modules_build_open), primary = false) {
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                    }
                                }
                                WowButton(stringResource(R.string.wow_modules_build_dismiss), primary = false) { ModuleStore.dismissBuild(context) }
                            }
                        }
                    }
                    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = WowColors.Blood) }

                    // The token GitHub takes the build from.
                    Text(
                        stringResource(if (hasToken) R.string.wow_modules_token_set else R.string.wow_modules_token_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasToken) WowColors.Good else WowColors.Mist,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        WowButton(stringResource(if (hasToken) R.string.wow_modules_token_change else R.string.wow_modules_token_add), primary = !hasToken) {
                            tokenDialog = true
                        }
                        if (hasToken) {
                            WowButton(stringResource(R.string.wow_modules_token_remove), primary = false) { ModuleStore.clearToken(context) }
                        }
                    }
                }
            }
        }

        // The realms kept, to swap between.
        if (saved.isNotEmpty()) {
            item { WowTitle(stringResource(R.string.wow_modules_saved_title), column.padding(top = 6.dp)) }
            item { Text(stringResource(R.string.wow_modules_saved_body), column, style = MaterialTheme.typography.bodySmall) }
            items(saved, key = { "realm-" + it.request }) { built ->
                SavedRealm(column, built, inNow = current?.request == built.request, working = working)
            }
        }

        // The picks.
        item { WowTitle(stringResource(R.string.wow_modules_yours), column.padding(top = 6.dp)) }
        if (chosen.isEmpty()) {
            item { Text(stringResource(R.string.wow_modules_yours_none), column, style = MaterialTheme.typography.bodyMedium) }
        }
        items(chosen, key = { it.fullName }) { module ->
            val built = current?.modules?.any { it.fullName.equals(module.fullName, ignoreCase = true) } == true
            val broken = !built && leftOut.any { it.name.equals(module.name, ignoreCase = true) }
            WowWindow(column, padding = PaddingValues(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Logo(module.avatar, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(module.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(
                                stringResource(R.string.wow_addons_by, module.fullName.substringBefore('/')),
                                stringResource(
                                    when {
                                        built -> R.string.wow_modules_in_realm
                                        broken -> R.string.wow_modules_doesnt_compile
                                        else -> R.string.wow_modules_not_built
                                    },
                                ),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = when {
                                built -> WowColors.Good
                                broken -> WowColors.Blood
                                else -> WowColors.Mist
                            },
                            maxLines = 1,
                        )
                        module.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                    Spacer(Modifier.width(8.dp))
                    WowButton(stringResource(R.string.wow_modules_remove), primary = false) { ModuleStore.remove(context, module.fullName) }
                }
            }
        }
    }

    if (tokenDialog) TokenDialog(onDismiss = { tokenDialog = false })
}

/** A realm kept in the app: put in again, its modules made the picks, or deleted. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedRealm(modifier: Modifier, built: ModuleBuild.Built, inNow: Boolean, working: Boolean) {
    val context = LocalContext.current
    var confirmDelete by remember(built.request) { mutableStateOf(false) }
    val usable = remember(built) { ModuleStore.canPutIn(context, built) }
    WowWindow(modifier, padding = PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.wow_modules_saved_with, built.modules.joinToString(", ") { it.name }),
                style = MaterialTheme.typography.titleMedium,
                color = if (inNow) WowColors.Good else Color.Unspecified,
            )
            Text(
                when {
                    usable -> stringResource(
                        R.string.wow_modules_saved_meta,
                        DateUtils.getRelativeTimeSpanString(built.builtAt).toString(),
                        android.text.format.Formatter.formatShortFileSize(context, built.size),
                    )
                    built.versionCode != BuildConfig.VERSION_CODE -> stringResource(R.string.wow_modules_saved_old)
                    else -> stringResource(R.string.wow_modules_error_saved_missing)
                },
                style = MaterialTheme.typography.bodySmall,
                color = WowColors.Mist,
            )
            if (built.leftOut.isNotEmpty()) {
                Text(
                    stringResource(R.string.wow_modules_left_out, built.leftOut.joinToString(", ") { it.name }),
                    style = MaterialTheme.typography.bodySmall,
                    color = WowColors.Mist,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (inNow) {
                    Text(stringResource(R.string.wow_modules_saved_in_now), style = MaterialTheme.typography.labelLarge, color = WowColors.Good)
                } else if (usable) {
                    WowButton(stringResource(R.string.wow_modules_saved_put_in), enabled = !working) {
                        Imports.clearResult()
                        ImportService.installModules(context, saved = built.request)
                    }
                }
                WowButton(stringResource(R.string.wow_modules_saved_use), primary = false) { ModuleStore.useModules(context, built) }
                if (!inNow) {
                    WowButton(
                        stringResource(if (confirmDelete) R.string.wow_modules_saved_delete_confirm else R.string.wow_modules_saved_delete),
                        primary = false,
                        enabled = !working,
                    ) {
                        if (!confirmDelete) confirmDelete = true else ModuleStore.deleteSaved(context, built.request)
                    }
                }
            }
        }
    }
}

/** The user's GitHub token: checked against this repository's builds, then kept in the app. */
@Composable
private fun TokenDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    WowDialog(
        title = stringResource(R.string.wow_modules_token_title),
        onDismiss = onDismiss,
        buttons = {
            WowButton(stringResource(R.string.wow_prompt_cancel), primary = false, onClick = onDismiss)
            WowButton(stringResource(if (checking) R.string.wow_modules_token_checking else R.string.wow_modules_token_save), enabled = token.isNotBlank() && !checking) {
                checking = true
                error = null
                scope.launch {
                    runCatching { ModuleStore.setToken(context, token) }
                        .onSuccess { onDismiss() }
                        .onFailure {
                            if (it is CancellationException) throw it
                            error = context.getString(R.string.wow_modules_token_failed, it.message ?: it.javaClass.simpleName)
                        }
                    checking = false
                }
            }
        },
    ) {
        Text(stringResource(R.string.wow_modules_token_body, BuildConfig.WOW_UPDATE_REPO), style = MaterialTheme.typography.bodySmall)
        WowTextField(token, { token = it }, stringResource(R.string.wow_modules_token_label), password = true)
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = WowColors.Blood) }
    }
}

/** For the launcher's Modules tile. */
@Composable
fun modulesSummary(context: android.content.Context): String {
    remember { ModuleStore.load(context) }
    val chosen by ModuleStore.chosen.collectAsState()
    val pending by ModuleStore.pending.collectAsState()
    val realm by ModuleStore.realm.collectAsState()
    val current = realm?.takeIf { it.versionCode == BuildConfig.VERSION_CODE }
    val build = pending
    return when {
        !ModuleStore.isAvailable -> stringResource(R.string.wow_tile_modules_unavailable)
        build != null && !build.isDone -> stringResource(R.string.wow_tile_modules_building)
        build != null && build.succeeded -> stringResource(R.string.wow_tile_modules_ready)
        build != null -> stringResource(R.string.wow_tile_modules_failed)
        current != null -> pluralStringResource(R.plurals.wow_tile_modules_in, current.modules.size, current.modules.size)
        chosen.isNotEmpty() -> pluralStringResource(R.plurals.wow_tile_modules_picked, chosen.size, chosen.size)
        else -> stringResource(R.string.wow_tile_modules_summary)
    }
}
