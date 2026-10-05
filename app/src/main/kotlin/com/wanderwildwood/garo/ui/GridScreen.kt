package com.wanderwildwood.garo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.garo.R
import com.wanderwildwood.garo.media.Clock
import com.wanderwildwood.garo.media.Decoder
import com.wanderwildwood.garo.media.Folder

/**
 * One folder's pictures, a few to a row.
 *
 * Each row is one item of MMD's list rather than a cell of a lazy grid, which is what gets it
 * the list's own way of moving — a screenful to a swipe, the chevron rail at the side — instead
 * of a grid that coasts. A row's height is known before any picture decodes, because every
 * cell is square, so stepping by rows is stepping by screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GridScreen(
    folder: Folder,
    perRow: Int,
    decoder: Decoder,
    listState: LazyListState,
    onBack: () -> Unit,
    onOpen: (index: Int) -> Unit,
    /** Pictures marked while choosing for another app; drawn with a bold edge. */
    chosen: Set<Long> = emptySet(),
    actions: @Composable RowScope.() -> Unit = {},
    /** Said in place of the grid while it has nothing to show: an album still being read. */
    note: String? = null,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = folder.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.grid_cd_back), onBack) },
                actions = actions,
            )
        },
    ) { padding ->
        val rows = folder.pictures.chunked(perRow)
        if (rows.isEmpty() && note != null) {
            Box(Modifier.fillMaxSize().padding(padding)) { Explain(text = note) }
            return@Scaffold
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            // Asked for at the size it is drawn, so the phone hands back a thumbnail that
            // fills the square without being stretched up from a smaller one.
            val cellPx = with(LocalDensity.current) { (maxWidth / perRow).roundToPx() }
            LazyColumnMMD(
                state = listState,
                scrollStep = rememberPageStep(listState),
                verticalArrangement = Arrangement.spacedBy(GAP),
                modifier = Modifier.fillMaxSize().padding(start = GAP, end = GAP, top = GAP),
            ) {
                items(rows.size, key = { rows[it].first().id }) { r ->
                    Row(horizontalArrangement = Arrangement.spacedBy(GAP), modifier = Modifier.fillMaxWidth()) {
                        rows[r].forEachIndexed { c, picture ->
                            Box(Modifier.weight(1f).aspectRatio(1f)) {
                            Thumbnail(
                                decoder = decoder,
                                picture = picture,
                                px = cellPx,
                                modifier = Modifier
                                    .matchParentSize()
                                    .clickable { onOpen(r * perRow + c) }
                                    .then(
                                        // State by the edge, not by a tick or a tint: a tick is
                                        // a smudge at this size, and a tint is lost in a photo.
                                        if (picture.id in chosen) {
                                            Modifier.border(CHOSEN_EDGE, MaterialTheme.colorScheme.onSurface)
                                        } else {
                                            Modifier
                                        },
                                    ),
                            )
                            // A video says so, and how long it is: black on a white label, which
                            // reads over any picture where a white triangle on the picture does not.
                            if (picture.video) {
                                TextMMD(
                                    text = Clock.format(picture.duration),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(4.dp)
                                        .background(MaterialTheme.colorScheme.surface)
                                        .padding(horizontal = 4.dp),
                                )
                            }
                            }
                        }
                        // A short last row keeps its squares the size of the rest.
                        repeat(perRow - rows[r].size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** White between pictures, wide enough to see where one photo ends and the next begins. */
private val GAP = 4.dp

/** Thick enough to read as a frame on any photograph, light or dark. */
private val CHOSEN_EDGE = 6.dp
