package com.wanderwildwood.garo.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.garo.R
import com.wanderwildwood.garo.media.Decoder
import com.wanderwildwood.garo.media.Folder

/**
 * The first screen: every folder that holds a picture, newest first, each with its newest
 * picture beside it.
 *
 * A row rather than Fossify's grid of covers. A cover the width of a third of this panel is
 * too small to tell one beach from another, and the folder's name is what is actually read;
 * the picture beside it only confirms it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    state: GalleryState,
    decoder: Decoder,
    listState: LazyListState,
    onOpen: (Folder) -> Unit,
    onSettings: () -> Unit,
    onAllow: () -> Unit,
    onAppSettings: () -> Unit,
    /** Set while choosing for another app: what the bar asks, and the way out without choosing. */
    choosing: String? = null,
    onCancel: () -> Unit = {},
    /** What an empty list says, when it is not simply that the phone has no pictures. */
    none: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    /** Hiding a folder by hand; null where hiding is not offered, as while choosing. */
    onHide: ((Folder) -> Unit)? = null,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            if (choosing == null) {
                TopAppBarMMD(
                    title = { TextMMD(text = stringResource(R.string.app_name)) },
                    actions = { BarButton(Icons.Settings, stringResource(R.string.folders_cd_settings), onSettings) },
                )
            } else {
                TopAppBarMMD(
                    title = { TextMMD(text = choosing, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { BarButton(Icons.Close, stringResource(R.string.pick_cd_cancel), onCancel) },
                    actions = actions,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.reading -> Unit
                state.access == Access.ASK -> Explain(
                    text = stringResource(R.string.folders_need_permission),
                    button = stringResource(R.string.folders_allow),
                    onClick = onAllow,
                )
                state.access == Access.REFUSED -> Explain(
                    text = stringResource(R.string.folders_refused),
                    button = stringResource(R.string.folders_open_app_settings),
                    onClick = onAppSettings,
                )
                state.folders.isEmpty() && state.server == Server.NONE ->
                    Explain(text = none ?: stringResource(R.string.folders_none))
                else -> FolderList(state, decoder, listState, onOpen, onHide)
            }
        }
    }
}

/**
 * The phone's folders, and below them, when a server is set, its albums under their own heading.
 * With no server there are no headings at all: a heading over the only list there is would be
 * a label on the obvious.
 */
@Composable
private fun FolderList(state: GalleryState, decoder: Decoder, listState: LazyListState, onOpen: (Folder) -> Unit, onHide: ((Folder) -> Unit)?) {
    val coverPx = with(LocalDensity.current) { COVER.roundToPx() }
    val withServer = state.server != Server.NONE
    LazyColumnMMD(
        state = listState,
        scrollStep = rememberPageStep(listState),
        modifier = Modifier.fillMaxSize().padding(start = 20.dp),
    ) {
        if (withServer) item(key = "h-phone") { Heading(stringResource(R.string.folders_on_phone)) }
        if (withServer && state.folders.isEmpty()) {
            item(key = "none-phone") { Note(stringResource(R.string.folders_none)) }
        }
        items(state.folders, key = { it.key }) { FolderRow(it, decoder, coverPx, onOpen, onHide) }

        if (withServer) {
            item(key = "h-immich") { Heading(stringResource(R.string.folders_immich)) }
            // Said, not hidden: the albums below may be what was last seen rather than what is
            // there now, and the reader should know which.
            val note = when (state.server) {
                Server.ASKING -> if (state.albums.isEmpty()) R.string.immich_asking else null
                Server.UNREACHABLE -> if (state.albums.isEmpty()) R.string.immich_unreachable else R.string.immich_unreachable_last_seen
                Server.REFUSED -> R.string.immich_refused
                else -> if (state.albums.isEmpty()) R.string.immich_no_albums else null
            }
            if (note != null) item(key = "immich-note") { Note(stringResource(note)) }
            items(state.albums, key = { it.key }) { FolderRow(it, decoder, coverPx, onOpen) }
        }
    }
}

/**
 * A folder: its newest picture, its name, how many. Held, it offers to hide itself, the way a
 * row in this shop asks — it says what a second tap will do, and forgets the offer after four
 * seconds, so a stray hold leaves nothing live.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(folder: Folder, decoder: Decoder, coverPx: Int, onOpen: (Folder) -> Unit, onHide: ((Folder) -> Unit)? = null) {
    var armed by remember(folder.key) { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(4_000)
            armed = false
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (armed) {
                        armed = false
                        onHide?.invoke(folder)
                    } else {
                        onOpen(folder)
                    }
                },
                onLongClick = if (onHide != null && !folder.remote) ({ armed = true }) else null,
            )
            .padding(vertical = 10.dp),
    ) {
        Box(Modifier.size(COVER).border(1.dp, MaterialTheme.colorScheme.onSurface)) {
            folder.cover?.let {
                Thumbnail(decoder = decoder, picture = it, px = coverPx, modifier = Modifier.matchParentSize())
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            TextMMD(
                text = folder.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val videos = folder.pictures.count { it.video }
            val stills = folder.count - videos
            TextMMD(
                text = when {
                    armed -> stringResource(R.string.folders_hide_armed)
                    videos == 0 -> pluralStringResource(R.plurals.folders_count, folder.count, folder.count)
                    stills == 0 -> pluralStringResource(R.plurals.folders_videos, videos, videos)
                    else -> stringResource(
                        R.string.folders_both,
                        pluralStringResource(R.plurals.folders_count, stills, stills),
                        pluralStringResource(R.plurals.folders_videos, videos, videos),
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (armed) FontWeight.Bold else null,
            )
        }
    }
}

/** A group's name, bold over a rule — the same as Files' start page. */
@Composable
private fun Heading(text: String) {
    Column(Modifier.fillMaxWidth()) {
        TextMMD(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
        )
        HorizontalDividerMMD()
    }
}

@Composable
private fun Note(text: String) {
    TextMMD(text = text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 12.dp))
}

/** A sentence in the middle of an empty screen, and the one thing to do about it. */
@Composable
internal fun Explain(text: String, button: String? = null, onClick: () -> Unit = {}) {
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
    ) {
        TextMMD(text = text, style = MaterialTheme.typography.bodyMedium)
        if (button != null) {
            Spacer(Modifier.height(20.dp))
            OutlinedButtonMMD(onClick = onClick, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                TextMMD(text = button, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private val COVER = 72.dp
