package com.wanderwildwood.garo.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
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
                state.folders.isEmpty() -> Explain(text = none ?: stringResource(R.string.folders_none))
                else -> FolderList(state.folders, decoder, listState, onOpen)
            }
        }
    }
}

@Composable
private fun FolderList(folders: List<Folder>, decoder: Decoder, listState: LazyListState, onOpen: (Folder) -> Unit) {
    val coverPx = with(LocalDensity.current) { COVER.roundToPx() }
    LazyColumnMMD(
        state = listState,
        scrollStep = rememberPageStep(listState),
        modifier = Modifier.fillMaxSize().padding(start = 20.dp),
    ) {
        items(folders, key = { it.key }) { folder ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(folder) }
                    .padding(vertical = 10.dp),
            ) {
                folder.cover?.let {
                    Thumbnail(
                        decoder = decoder,
                        uri = it.uri,
                        px = coverPx,
                        modifier = Modifier
                            .size(COVER)
                            .border(1.dp, MaterialTheme.colorScheme.onSurface),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    TextMMD(
                        text = folder.label,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextMMD(
                        text = pluralStringResource(R.plurals.folders_count, folder.pictures.size, folder.pictures.size),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
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
