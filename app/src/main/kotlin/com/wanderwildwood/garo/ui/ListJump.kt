package com.wanderwildwood.garo.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * swipes. This puts back the job and not the bar, in the shape the launcher already taught,
 * drawn as inkOS draws it with square corners:
 *
 * - **Letters**, for a list in alphabetical order — inkOS's drawer column down the right edge,
 *   only the letters the list has, spread down its height. The letter the screen is on sits
 *   white in a black square; a press on a letter, or a drag down the column, opens the list
 *   there.
 * - **Pages**, for any other list — inkOS's page dots, small squares in a short column, the one
 *   on screen filled and the rest outlined. A press or a drag on a dot opens that page. There
 *   are never more than seven; past seven pages each dot stands for a run of them.
 *
 * The swipe is untouched; this is beside the list, not over it. It is drawn only where it saves
 * something: a list of fewer than three screens is quicker swiped, and shows nothing extra.
 *
 * It can be turned off for every list in the app at once with [ListJumpPrefs]. Each app puts
 * the switch in its Settings, in that app's own row style, with these strings (English only):
 *
 * ```
 * <string name="list_jump_setting">Letters and page dots</string>
 * <string name="list_jump_shown">Shown</string>
 * <string name="list_jump_hidden">Hidden</string>
 * ```
 *
 * ```
 * val jumpShown by rememberListJumpShown()
 * SwitchRow(stringResource(R.string.list_jump_setting), jumpShown) { ListJumpPrefs.setShown(context, it) }
 * // or, where Settings rows say their value: note = if (jumpShown) list_jump_shown else list_jump_hidden
 * ```
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
 * Whether the letters and page dots are drawn, for every list in the app: off unless turned on
 * (2026-10-17: the lists read better without them; the rail is there for anyone who wants it).
 * Kept in a small file of its own, so no app's settings store has to know about it.
 */
object ListJumpPrefs {
    private const val FILE = "list_jump"
    private const val SHOWN = "shown"

    internal fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun shown(context: Context): Boolean = prefs(context).getBoolean(SHOWN, false)

    fun setShown(context: Context, shown: Boolean) {
        prefs(context).edit().putBoolean(SHOWN, shown).apply()
    }

    internal const val KEY = SHOWN
}

/** [ListJumpPrefs.shown], kept up to date while on screen: for the lists and the Settings row. */
@Composable
fun rememberListJumpShown(): State<Boolean> {
    val context = LocalContext.current
    val shown = remember { mutableStateOf(ListJumpPrefs.shown(context)) }
    DisposableEffect(context) {
        val prefs = ListJumpPrefs.prefs(context)
        shown.value = ListJumpPrefs.shown(context)
        // Held here: the store keeps its listeners only weakly.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == ListJumpPrefs.KEY) shown.value = ListJumpPrefs.shown(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return shown
}

/**
 * The list in [content], with the jump down its right edge once it is long enough. Pass the
 * same [state] the list is given; null [jump] draws the list alone, as does the setting turned
 * off. Where buttons float over the foot of the list, [clearBelow] is their height, and the
 * jump stops above them.
 *
 * ```
 * val state = rememberLazyListState()
 * ListJumpBox(state, ListJump.Letters(artists.map { it.name })) {
 *     PagedColumn(state = state) { … }
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
    // Rows to a screen as the page dots count them, held for each length of list: counted
    // afresh on every page, the short last page made a three-screen list look long and the
    // dots came and went as it was paged.
    val held = remember(state) { intArrayOf(-1, 0) }
    val isLong by remember(state) {
        derivedStateOf {
            val (total, onScreen) = state.rowsToAScreen(held)
            onScreen > 0 && total > onScreen * MIN_SCREENS
        }
    }
    val shown by rememberListJumpShown()
    val drawn = jump != null && isLong && shown
    val paper = MaterialTheme.colorScheme.surface
    Box(modifier = modifier.fillMaxSize()) {
        // The rows run to just short of the rail: what the rail covers, less the end padding a
        // row here already has, so the text stops a hair to the left of it.
        Box(Modifier.fillMaxSize().padding(end = if (drawn) RAIL_RESERVE else 0.dp)) { content() }
        if (drawn) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .padding(end = RAIL_END, bottom = clearBelow)
                    .width(RAIL_WIDTH)
                    // Plain panel behind the rail, so a rule running the row's width stops at it;
                    // short of the ends, so the rules above and below the list stay whole.
                    .drawBehind {
                        val inset = MASK_INSET.toPx()
                        drawRect(paper, Offset(0f, inset), Size(size.width, (size.height - inset * 2).coerceAtLeast(0f)))
                    },
            ) {
                when (jump) {
                    is ListJump.Letters -> LetterColumn(state, jump)
                    ListJump.Pages -> PageDots(state)
                    null -> {}
                }
            }
        }
    }
}

@Composable
private fun LetterColumn(state: LazyListState, jump: ListJump.Letters) {
    val scope = rememberCoroutineScope()
    val labelOf: (String) -> String = if (jump.labels != null) { name -> name } else ::jumpLetterOf
    // Only the letters somebody is filed under, in the column's order, and where each first
    // opens the list: inkOS's drawer leaves out the letters no app starts with, so no press
    // lands on nothing.
    val firsts = remember(jump.names, jump.firstIndex, jump.labels) {
        val at = HashMap<String, Int>()
        jump.names.forEachIndexed { i, name -> at.putIfAbsent(labelOf(name), i + jump.firstIndex) }
        at
    }
    val letters = remember(firsts, jump.labels) { (jump.labels ?: LETTERS).filter { it in firsts } }
    val onScreen by remember(state, jump.names, jump.firstIndex, letters) {
        derivedStateOf {
            val row = (state.firstVisibleItemIndex - jump.firstIndex).coerceIn(0, (jump.names.size - 1).coerceAtLeast(0))
            jump.names.getOrNull(row)?.let { letters.indexOf(labelOf(it)) } ?: -1
        }
    }
    var touched by remember { mutableStateOf<Int?>(null) }
    val active = touched ?: onScreen
    var heightPx by remember { mutableIntStateOf(0) }
    val latest by rememberUpdatedState(letters to firsts)
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    val type = MaterialTheme.typography.bodyMedium

    // inkOS: every letter an equal share of the column's height, the letter centred in it.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { heightPx = it.height }
            .pointerInput(Unit) {
                trackSlots(
                    count = { latest.first.size },
                    slotPx = { if (heightPx > 0 && latest.first.isNotEmpty()) heightPx.toFloat() / latest.first.size else 0f },
                    onSlot = { at ->
                        val (shownLetters, opens) = latest
                        val letter = shownLetters.getOrNull(at) ?: return@trackSlots
                        touched = at
                        opens[letter]?.let { scope.launch { state.scrollToItem(it) } }
                    },
                    onEnd = { touched = null },
                )
            },
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEachIndexed { at, letter ->
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (at == active) {
                    Box(
                        modifier = Modifier.size(RAIL_WIDTH).background(ink, SQUARE),
                        contentAlignment = Alignment.Center,
                    ) {
                        TextMMD(
                            text = letter,
                            color = paper,
                            maxLines = 1,
                            style = type.copy(fontSize = LETTER_ACTIVE_SIZE, lineHeight = LETTER_ACTIVE_SIZE, fontWeight = FontWeight.Normal, textAlign = TextAlign.Center),
                        )
                    }
                } else {
                    TextMMD(
                        text = letter,
                        color = ink,
                        maxLines = 1,
                        style = type.copy(fontSize = LETTER_SIZE, lineHeight = LETTER_SIZE, fontWeight = FontWeight.Normal, textAlign = TextAlign.Center),
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
    val slotPx = (dotPx + gapPx).toFloat()
    var heightPx by remember { mutableIntStateOf(0) }

    // A page is what a swipe turns, so a dot lands where swiping would have. In a PagedColumn
    // that is the rows that fit whole, and its last item is blank room after them, not a row;
    // it says so with one pixel of padding after that room. In MMD's list it is a screenful
    // less a row. The rows to a screen are measured once for each length of list and then held:
    // where rows differ in height a fresh count on every screen would add and drop dots under the
    // reader's thumb as the list moved. A list that changes length is measured again.
    val held = remember(state) { intArrayOf(-1, 0) } // the length measured at, rows to a screen
    val pages by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val paged = info.afterContentPadding == PAGED_MARK_PX
            val (total, rows) = state.rowsToAScreen(held)
            val onScreen = rows.coerceAtLeast(1)
            val step = if (paged) onScreen else (onScreen - 1).coerceAtLeast(1)
            val count = when {
                total <= onScreen -> 1
                paged -> (total + step - 1) / step
                else -> (total - onScreen + step - 1) / step + 1
            }
            val atEnd = if (paged) {
                info.visibleItemsInfo.any { it.index == total - 1 && it.offset + it.size <= info.viewportSize.height }
            } else {
                (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= total - 1
            }
            val current = if (atEnd) count - 1 else (state.firstVisibleItemIndex + step / 2) / step
            JumpPage(count, current.coerceIn(0, count - 1), step, paged)
        }
    }
    val pageCount = pages.count
    val currentPage = pages.current
    // Seven at most, as many as fit in any case.
    val fits = ((heightPx + gapPx) / slotPx).toInt().coerceAtLeast(1)
    val dots = pageCount.coerceAtMost(MAX_DOTS).coerceAtMost(fits)
    val latest by rememberUpdatedState(pages to dots)
    var touched by remember { mutableStateOf<Int?>(null) }
    val active = touched ?: (currentPage * dots / pageCount)
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface

    // A short column in the middle of the list, the squares flush with the rail's right edge as
    // inkOS's sit flush with its page margin.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { heightPx = it.height },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier.pointerInput(Unit) {
                trackSlots(
                    count = { latest.second },
                    slotPx = { slotPx },
                    onSlot = { dot ->
                        touched = dot
                        val (at, shown) = latest
                        val count = at.count
                        // The first page of the dot's run, rounded up so the dot pressed is
                        // the dot that stays filled; the last dot is the foot of the list. A
                        // PagedColumn's last page starts where a swipe would have turned to it.
                        val page = if (dot >= shown - 1) count - 1 else (dot * count + shown - 1) / shown
                        scope.launch {
                            if (page >= count - 1 && !at.paged) state.scrollToItem(state.layoutInfo.totalItemsCount - 1)
                            else state.scrollToItem(page * at.step)
                        }
                    },
                    onEnd = { touched = null },
                )
            },
            verticalArrangement = Arrangement.spacedBy(DOT_SPACING),
            horizontalAlignment = Alignment.End,
        ) {
            repeat(dots) { dot ->
                val shape = Modifier.size(DOT_SIZE)
                if (dot == active) {
                    Box(shape.background(ink, SQUARE))
                } else {
                    Box(shape.background(paper, SQUARE).border(DOT_BORDER, ink, SQUARE))
                }
            }
        }
    }
}

/** The pages of a list: how many, the one on screen, the items a page turns, and whether it is a PagedColumn. */
private data class JumpPage(val count: Int, val current: Int, val step: Int, val paged: Boolean)

/** The padding PagedColumn leaves after its blank tail, in pixels: the same number there. */
private const val PAGED_MARK_PX = 1

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

// inkOS's measures, with the square corners of the house theme: a 20dp rail; the current letter
// white in a 20dp black square, the rest small and plain; 12dp page dots with a 2dp rim and
// 10dp between them, seven at most.
private val RAIL_WIDTH = 20.dp
private val SQUARE = RoundedCornerShape(0.dp)
private val LETTER_SIZE = 13.sp
private val LETTER_ACTIVE_SIZE = 15.sp
private val DOT_SIZE = 12.dp
private val DOT_SPACING = 10.dp
private val DOT_BORDER = 2.dp
private const val MAX_DOTS = 7

// Where the rail sits: its right edge 32.5dp in from the list's, where inkOS's sits on the
// Kompakt (x 436 of 480).
private val RAIL_END = 32.5.dp
private val MASK_INSET = 2.dp
// What the rows give up for it: up to the rail and a 4dp gap, less the 16dp end padding the
// narrowest row here already has.
private val RAIL_RESERVE = RAIL_END + RAIL_WIDTH + 4.dp - 16.dp

/**
 * How many rows the list holds, and how many fit on a screen, as a pair. The rows to a screen
 * are measured once for each length of list and kept in [held] (the length measured at, the
 * rows), so they cannot change from one page to the next. In a PagedColumn they are the rows
 * that fit whole, and the blank room after the last row is not counted as one; in MMD's list,
 * a screenful.
 */
private fun LazyListState.rowsToAScreen(held: IntArray): Pair<Int, Int> {
    val info = layoutInfo
    val paged = info.afterContentPadding == PAGED_MARK_PX
    val total = info.totalItemsCount - if (paged) 1 else 0
    val viewport = info.viewportEndOffset - info.viewportStartOffset
    val row = info.visibleItemsInfo.firstOrNull()?.size ?: 0
    if (held[0] != total && viewport > 0 && row > 0) {
        held[0] = total
        held[1] = if (paged) {
            val gap = info.mainAxisItemSpacing
            (viewport + gap) / (row + gap)
        } else {
            viewport / row
        }
    }
    return total to held[1]
}

/** A list shorter than this many screens is quicker swiped than jumped. */
private const val MIN_SCREENS = 3
