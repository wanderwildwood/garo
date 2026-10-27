package com.wanderwildwood.garo.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// The same file in every app of this shop, apart from its package line.

/**
 * A list that turns a page to a swipe, and turns it on whole rows. It takes the place of MMD's
 * `LazyColumnMMD` and is called the same way.
 *
 * MMD's list steps four items to a swipe and lets the list clamp at its end, so its last page
 * lines up with the bottom and the row at the top sits half under the bar; four tall rows can
 * also step past one that was never fully seen. Here a swipe up brings the first row that did
 * not fit to the top, a swipe down is the same backwards, and a row that does not fit at the
 * foot is not drawn until it is the next page's first. The room it leaves is shared out between
 * the rows above it, so a full page runs from the top to the foot with no blank band under its
 * last row; the last page keeps its rows as they come and leaves the space under them blank. A
 * row taller than the screen is read a screen at a time.
 *
 * [contentPadding]'s top and bottom are a margin on every page, not only at the ends of the
 * list: no row is drawn in them. [isScrollbarVisible] is taken so a call written for MMD's
 * list still reads the same, and does nothing: no list in the house apps draws a scrollbar.
 *
 * The [state] may be shared, and the next swipe
 * goes on from wherever it stands.
 *
 * [onPullAtTop] is for a list fed from somewhere else: a swipe down while the first page is
 * already showing, its first row at the very top, calls it once for that swipe, so the list can
 * ask for its rows again. Left null, as in a list that lives only on the phone, that swipe does
 * nothing, as it always did. Saying what the pull set going is the caller's, in words.
 */
@Composable
fun PagedColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    @Suppress("UNUSED_PARAMETER") isScrollbarVisible: Boolean = false,
    onPullAtTop: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    // The gesture below is set up once for the list; read the caller's latest action, not the
    // one it was handed first.
    val pull by rememberUpdatedState(onPullAtTop)
    val direction = LocalLayoutDirection.current
    val mark = with(LocalDensity.current) { TAIL_MARK_PX.toDp() }
    // Only a list longer than its screen pages, and only that one gets the blank tail: a list
    // that fits keeps its arrangement, centred or at the foot as it was asked.
    val pages by remember(state) { derivedStateOf { state.overflows() } }
    val spread by remember(state) { derivedStateOf(structuralEqualityPolicy()) { state.spread() } }

    LaunchedEffect(state) {
        // The tail at the top is a blank page: the rows under it were taken away, or something
        // scrolled past the end. Go back to the last page of rows.
        snapshotFlow { state.layoutInfo.visibleItemsInfo.firstOrNull()?.key == TAIL }
            .collect { blank ->
                if (blank) {
                    state.scrollToItem(state.layoutInfo.totalItemsCount - 1)
                    state.pageBack()
                }
            }
    }

    BoxWithConstraints(
        modifier.padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()),
    ) {
        // Room after the last row for it to come to the top, so the list never has to line
        // its end up with the bottom of the screen.
        val tail = maxHeight
        // Read once here, in composition, not inside the LazyColumn's content below: there it ran
        // under the list's own item-provider state and so read the last layout, and every layout
        // rebuilt the items and asked for another — about 60 redraws a second while idle, and with
        // an accessibility service on (inkOS has one) a core kept busy in the background.
        val paged = pages
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(state) {
                    var turned = false
                    detectVerticalDragGestures(
                        onDragStart = { turned = false },
                        onDragEnd = { turned = false },
                        onDragCancel = { turned = false },
                    ) { _, drag ->
                        if (turned || drag == 0f) return@detectVerticalDragGestures
                        turned = true
                        val atTop = state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0
                        when (swipeTurn(drag, atTop, pull != null)) {
                            SwipeTurn.Forward -> scope.launch { state.pageForward() }
                            SwipeTurn.Back -> scope.launch { state.pageBack() }
                            SwipeTurn.Pull -> pull?.invoke()
                            SwipeTurn.None -> Unit
                        }
                    }
                },
            state = state,
            // One pixel after the tail marks that the last item is room, not a row.
            contentPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(direction),
                end = contentPadding.calculateEndPadding(direction),
                bottom = if (paged) mark else 0.dp,
            ),
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            // The list turns its own pages.
            userScrollEnabled = false,
        ) {
            // Read where the rows are placed, not when they are composed: a page turn moves them
            // without composing anything again.
            SpreadScope(this, horizontalAlignment) { index -> spread?.shift(index) ?: 0 }.content()
            if (paged) item(key = TAIL, contentType = TAIL) { Spacer(Modifier.height(tail)) }
        }
    }
}

private const val TAIL = "paged-column-tail"

/** What one swipe does to the list. */
internal enum class SwipeTurn { Forward, Back, Pull, None }

/**
 * What a swipe of [drag] pixels does: up turns a page on, down turns one back, and down on the
 * first page, [atTop], asks for the rows again if the list [canPull], or else does nothing.
 */
internal fun swipeTurn(drag: Float, atTop: Boolean, canPull: Boolean): SwipeTurn = when {
    drag < 0f -> SwipeTurn.Forward
    drag == 0f -> SwipeTurn.None
    !atTop -> SwipeTurn.Back
    canPull -> SwipeTurn.Pull
    else -> SwipeTurn.None
}

/** The padding after the tail, in pixels. */
private const val TAIL_MARK_PX = 1

private fun LazyListState.hasTail(): Boolean = layoutInfo.afterContentPadding == TAIL_MARK_PX

/** How many rows the list holds, without the blank room after the last. */
private fun LazyListState.rowCount(): Int = layoutInfo.totalItemsCount - if (hasTail()) 1 else 0

/** The rows on screen, without the blank room after the last. */
private fun LazyListState.rows(): List<LazyListItemInfo> =
    layoutInfo.visibleItemsInfo.filter { it.key != TAIL }

/** Whether the rows run past one screen, before it or after it. */
private fun LazyListState.overflows(): Boolean {
    val rows = rows()
    if (rows.isEmpty()) return false
    val first = rows.first()
    val last = rows.last()
    return first.index > 0 || first.offset < 0 || last.index < rowCount() - 1 ||
        last.offset + last.size > layoutInfo.viewportSize.height
}

/**
 * A full page: whole rows from [from] to [to], [room] pixels under the last of them, and from
 * [cut] on rows that do not fit. Each whole row moves down its share of the room, the first not
 * at all and the last all of it, so the last sits on the foot; the rows that do not fit move off
 * the screen, out of sight and out of reach of a press, until a page turn brings them to the top.
 *
 * A data class, so a layout that leaves the page as it was is no change: the rows read it where
 * they are placed, and a new one on every pass would lay them out again for ever.
 */
private data class Spread(val from: Int, val to: Int, val room: Int, val cut: Int, val away: Int) {
    fun shift(index: Int): Int = when {
        index >= cut -> away
        index < from || to == from -> 0
        else -> room * (index - from) / (to - from)
    }
}

/** How the rows on screen share out the room under them, while one runs off the foot. */
private fun LazyListState.spread(): Spread? {
    val bottom = layoutInfo.viewportSize.height
    val rows = rows()
    val cut = rows.firstOrNull { it.offset + it.size > bottom } ?: return null
    // A row taller than the screen, read a screen at a time, has nothing whole above it.
    if (cut.offset <= 0) return null
    val whole = rows.filter { it.index < cut.index && it.offset >= 0 }
    if (whole.isEmpty()) return null
    val last = whole.last()
    val room = (bottom - (last.offset + last.size)).coerceAtLeast(0)
    // Room wider than any row on the page is not what a row that did not fit left behind, but
    // the start of one far taller than the rest: shared out, it would scatter a few rows over
    // the screen. They stay as they come, and only the tall row waits for the next page.
    val share = if (room > whole.maxOf { it.size }) 0 else room
    return Spread(whole.first().index, last.index, share, cut.index, bottom)
}

/**
 * The list's own scope, with every row put in a column that [shift] moves down by its index. The
 * move is made where the row is placed, so the list still measures and pages on the rows as they
 * are, and a press lands on the row where it is drawn. [shift] and [alignment] are taken into
 * locals before each row, since the list's scope keeps its own members out of reach inside one.
 */
private class SpreadScope(
    private val list: LazyListScope,
    private val alignment: Alignment.Horizontal,
    private val shift: (Int) -> Int,
) : LazyListScope {
    private var next = 0

    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        val index = next++
        val shift = shift
        val alignment = alignment
        list.item(key, contentType) {
            val row = this
            Column(Modifier.offset { IntOffset(0, shift(index)) }, horizontalAlignment = alignment) { row.content() }
        }
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        val first = next
        next += count
        val shift = shift
        val alignment = alignment
        list.items(count, key, contentType) { i ->
            val row = this
            Column(Modifier.offset { IntOffset(0, shift(first + i)) }, horizontalAlignment = alignment) { row.itemContent(i) }
        }
    }

    // A header that sticks to the top stays where the list puts it.
    @ExperimentalFoundationApi
    override fun stickyHeader(key: Any?, contentType: Any?, content: @Composable LazyItemScope.(Int) -> Unit) {
        next++
        list.stickyHeader(key, contentType, content)
    }
}

/** The first row that did not fit, brought to the top. */
private suspend fun LazyListState.pageForward() {
    val bottom = layoutInfo.viewportSize.height
    val rows = rows()
    if (rows.isEmpty()) return
    val cut = rows.firstOrNull { it.offset + it.size > bottom }
    when {
        // The rows on screen fill it exactly: the next page opens on the row after them, if
        // there is one; if not, this is the last page.
        cut == null -> {
            val next = rows.last().index + 1
            if (next < rowCount()) scrollToItem(next)
        }
        // One row taller than the screen: through it a screen at a time.
        cut.offset <= 0 -> scrollBy(bottom.toFloat())
        else -> scrollToItem(cut.index)
    }
}

/**
 * Back a page: the row before the top one comes to rest whole at the foot, with as many whole
 * rows above it as fit.
 */
private suspend fun LazyListState.pageBack() {
    if (firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0) return
    val from = firstVisibleItemIndex
    scrollBy(-layoutInfo.viewportSize.height.toFloat())
    // A row cut by the top edge waits for the page before; unless it is the one row being
    // gone back to, taller than the screen, or it is cut by no more than the room left under
    // that row. A spaced list leaves its gap there, and the row still fits whole.
    val cut = firstVisibleItemScrollOffset
    if (cut > 0 && firstVisibleItemIndex + 1 < from) {
        val before = layoutInfo.visibleItemsInfo.firstOrNull { it.index == from - 1 }
        val room = before?.let { layoutInfo.viewportSize.height - (it.offset + it.size) } ?: 0
        scrollToItem(if (cut <= room) firstVisibleItemIndex else firstVisibleItemIndex + 1)
    }
}
