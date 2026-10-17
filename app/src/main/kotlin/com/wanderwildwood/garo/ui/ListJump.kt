package com.wanderwildwood.garo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import kotlinx.coroutines.launch
import java.text.Normalizer

// The A-Z column and the page dots are adapted from inkOS by gezimos (GPL-3.0): AZSidebar in
// AZFiltering.kt and PageIndicator in HomeUI.kt. The same file in every app of this shop.

/**
 * A fast way through a long list, now that no list here draws a scrollbar.
 *
 * The bar went because it is clutter on this panel, but it did a job the swipe cannot: a
 * press on the rail landed near the row you wanted, and four hundred artists are thirty
 * swipes. This puts back the job and not the bar, in the shape the launcher already taught:
 *
 * - **Letters**, for a list in alphabetical order — inkOS's drawer column, A to Z down the
 *   right edge. The letter the screen is on is a filled disc; a press on a letter, or a drag
 *   down the column, opens the list there.
 * - **Pages**, for any other list — inkOS's page dots, one per screenful, the one on screen
 *   filled and the rest outlined. A press or a drag on a dot opens that page. Where there are
 *   more pages than dots will fit, each dot stands for a run of pages.
 *
 * The swipe is untouched; this is beside the list, not over it. It is drawn only where it saves
 * something: a list of fewer than three screens is quicker swiped, and shows nothing extra.
 */
sealed interface ListJump {
    /**
     * An alphabetical list: [names] is what each row is sorted by, in the list's own order.
     * [firstIndex] is where the first of them sits in the list, when a header comes before.
     *
     * A list in some other fixed order of its own, such as a shelf by Dewey hundreds, passes
     * the column's [labels] instead of # and A to Z, top to bottom, and each of [names] is then
     * already its row's label, one of them. The column looks and answers the same.
     */
    class Letters(
        val names: List<String>,
        val firstIndex: Int = 0,
        val labels: List<String>? = null,
    ) : ListJump

    /** Any other list: its own pages. */
    data object Pages : ListJump
}

/**
 * The list in [content], with the jump down its right edge once it is long enough. Pass the
 * same [state] the list is given; null [jump] draws the list alone. Where buttons float over
 * the foot of the list, [clearBelow] is their height, and the jump stops above them.
 *
 * ```
 * val state = rememberLazyListState()
 * ListJumpBox(state, ListJump.Letters(artists.map { it.name })) {
 *     LazyColumnMMD(state = state, isScrollbarVisible = false) { … }
 * }
 * ```
 */
@Composable
fun ListJumpBox(
    state: LazyListState,
    jump: ListJump?,
    modifier: Modifier = Modifier,
    clearBelow: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val isLong by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val onScreen = info.visibleItemsInfo.size
            onScreen > 0 && info.totalItemsCount > onScreen * MIN_SCREENS
        }
    }
    Row(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) { content() }
        if (jump != null && isLong) {
            Box(modifier = Modifier.fillMaxHeight().padding(bottom = clearBelow)) {
                when (jump) {
                    is ListJump.Letters -> LetterColumn(state, jump)
                    ListJump.Pages -> PageDots(state)
                }
            }
            Spacer(Modifier.width(EDGE_INSET / 2))
        }
    }
}

@Composable
private fun LetterColumn(state: LazyListState, jump: ListJump.Letters) {
    val scope = rememberCoroutineScope()
    val letters = jump.labels ?: LETTERS
    val labelOf: (String) -> String = if (jump.labels != null) { name -> name } else ::jumpLetterOf
    // Where each letter opens the list. A letter nobody is filed under opens it at the next
    // one that is, so every letter in the column does something and none of them moves.
    val target = remember(jump.names, jump.firstIndex, letters) {
        val firsts = HashMap<String, Int>()
        jump.names.forEachIndexed { i, name -> firsts.putIfAbsent(labelOf(name), i + jump.firstIndex) }
        val last = jump.firstIndex + (jump.names.size - 1).coerceAtLeast(0)
        letters.indices.associate { at ->
            letters[at] to (letters.drop(at).firstNotNullOfOrNull { firsts[it] }
                ?: letters.take(at).reversed().firstNotNullOfOrNull { firsts[it] }
                ?: last)
        }
    }
    val onScreen by remember(state, jump.names, jump.firstIndex, letters) {
        derivedStateOf {
            jump.names.getOrNull(state.firstVisibleItemIndex - jump.firstIndex)
                ?.let { letters.indexOf(labelOf(it)) } ?: -1
        }
    }
    var touched by remember { mutableStateOf<Int?>(null) }
    val active = touched ?: onScreen
    var heightPx by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .padding(vertical = EDGE_INSET)
            .width(EDGE_WIDTH)
            .fillMaxHeight()
            .onSizeChanged { heightPx = it.height }
            .pointerInput(target) {
                trackSlots(
                    count = { letters.size },
                    slotPx = { if (heightPx > 0) heightPx.toFloat() / letters.size else 0f },
                    onSlot = { at ->
                        touched = at
                        scope.launch { state.scrollToItem(target.getValue(letters[at])) }
                    },
                    onEnd = { touched = null },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEachIndexed { at, letter ->
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (at == active) {
                    Box(
                        modifier = Modifier
                            .size(EDGE_WIDTH)
                            .background(MaterialTheme.colorScheme.onSurface, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        TextMMD(
                            text = letter,
                            color = MaterialTheme.colorScheme.surface,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                } else {
                    TextMMD(
                        text = letter,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun PageDots(state: LazyListState) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // In whole pixels, as the column lays them out: summed from fractions, nineteen dots came to
    // a few pixels less than they take, and the last one was squeezed flat against the foot.
    val dotPx = with(density) { DOT_SIZE.roundToPx() }
    val gapPx = with(density) { DOT_SPACING.roundToPx() }
    val insetPx = with(density) { EDGE_INSET.roundToPx() }
    val slotPx = (dotPx + gapPx).toFloat()
    var heightPx by remember { mutableIntStateOf(0) }

    // A page is a screenful less a row, the step a swipe takes, so a dot lands where swiping
    // would have. The rows to a screen are measured once for each length of list and then held:
    // where rows differ in height a fresh count on every screen would add and drop dots under the
    // reader's thumb as the list moved. A list that changes length is measured again.
    val held = remember(state) { intArrayOf(-1, 0) } // the length measured at, rows to a screen
    val pages by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val total = info.totalItemsCount
            val viewport = info.viewportEndOffset - info.viewportStartOffset
            val row = info.visibleItemsInfo.firstOrNull()?.size ?: 0
            if (held[0] != total && viewport > 0 && row > 0) {
                held[0] = total
                held[1] = viewport / row
            }
            val onScreen = held[1].coerceAtLeast(1)
            val step = (onScreen - 1).coerceAtLeast(1)
            val count = if (total <= onScreen) 1 else (total - onScreen + step - 1) / step + 1
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val current = if (lastVisible >= total - 1) count - 1 else (state.firstVisibleItemIndex + step / 2) / step
            Triple(count, current.coerceIn(0, count - 1), step)
        }
    }
    val (pageCount, currentPage, step) = pages
    // Inset from the list's ends as the letters are, so no dot sits on a rule.
    val fits = ((heightPx + gapPx - insetPx * 2) / slotPx).toInt().coerceAtLeast(1)
    val dots = pageCount.coerceAtMost(fits)
    val latest by rememberUpdatedState(Triple(pageCount, dots, step))
    var touched by remember { mutableStateOf<Int?>(null) }
    val active = touched ?: (currentPage * dots / pageCount)

    Box(
        modifier = Modifier
            .width(EDGE_WIDTH)
            .fillMaxHeight()
            .onSizeChanged { heightPx = it.height },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(vertical = EDGE_INSET).pointerInput(Unit) {
                trackSlots(
                    count = { latest.second },
                    slotPx = { slotPx },
                    onSlot = { dot ->
                        touched = dot
                        val (count, shown, pageStep) = latest
                        // The first page of the dot's run, rounded up so the dot pressed is
                        // the dot that stays filled; the last dot is the foot of the list.
                        val page = if (dot >= shown - 1) count - 1 else (dot * count + shown - 1) / shown
                        scope.launch {
                            if (page >= count - 1) state.scrollToItem(state.layoutInfo.totalItemsCount - 1)
                            else state.scrollToItem(page * pageStep)
                        }
                    },
                    onEnd = { touched = null },
                )
            },
            verticalArrangement = Arrangement.spacedBy(DOT_SPACING),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            repeat(dots) { dot ->
                val shape = Modifier.size(DOT_SIZE)
                if (dot == active) {
                    Box(shape.background(MaterialTheme.colorScheme.onSurface, CircleShape))
                } else {
                    Box(
                        shape
                            .background(MaterialTheme.colorScheme.surface, CircleShape)
                            .border(DOT_BORDER, MaterialTheme.colorScheme.onSurface, CircleShape),
                    )
                }
            }
        }
    }
}

/**
 * inkOS's touch handling: the slot under the finger is chosen on the press, and again each
 * time a drag crosses into another, until the finger lifts.
 */
private suspend fun PointerInputScope.trackSlots(
    count: () -> Int,
    slotPx: () -> Float,
    onSlot: (Int) -> Unit,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        fun slotAt(y: Float): Int {
            val size = slotPx()
            return if (size > 0f) (y / size).toInt().coerceIn(0, (count() - 1).coerceAtLeast(0)) else 0
        }
        var last = slotAt(down.position.y)
        onSlot(last)
        down.consume()
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Release || event.changes.none { it.pressed }) break
            val change = event.changes.firstOrNull() ?: break
            val at = slotAt(change.position.y)
            if (at != last) {
                last = at
                onSlot(at)
            }
            event.changes.forEach { it.consume() }
        }
        onEnd()
    }
}

/**
 * The letter a name is filed under, matching how a list sorted by the name as written puts
 * it: "The Beatles" is under T. Accents come off (Ödland is under O); a digit, a symbol or a
 * script with no A to Z is under #.
 */
fun jumpLetterOf(name: String): String {
    val first = name.firstOrNull { it.isLetterOrDigit() } ?: return OTHER
    val base = Normalizer.normalize(first.toString(), Normalizer.Form.NFKD).first().uppercaseChar()
    return if (base in 'A'..'Z') base.toString() else OTHER
}

private const val OTHER = "#"

// Numbers sort before letters, so # heads the column where inkOS keeps its star.
private val LETTERS = listOf(OTHER) + ('A'..'Z').map { it.toString() }

// inkOS's measures: a 20dp column, 12dp dots with a 2dp rim and 0.8 of a dot between them.
private val EDGE_WIDTH = 20.dp
private val DOT_SIZE = 12.dp
private val DOT_SPACING = 9.6.dp
private val DOT_BORDER = 2.dp

// Clear of the rules above and below a list, and of the panel's edge.
private val EDGE_INSET = 8.dp

/** A list shorter than this many screens is quicker swiped than jumped. */
private const val MIN_SCREENS = 3
