package com.wanderwildwood.garo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD

// The same file in every app of this shop. The look is Messaging's All / Groups / Unknown row.

/**
 * A row of tabs drawn as Messaging draws its filters: the chosen one a black stone with its
 * label in white, the others a plain label, all of them bold, and MMD's own thin rule beneath
 * the row, as under a top bar.
 *
 * It replaced MMD's own tab row everywhere on 2026-10-17. The thin bar under a chosen tab is
 * easy to miss on this panel, and the stone is not: it reads from arm's length, like a stone
 * set on a Go board. The tabs sit from the left at their own widths rather than spread across,
 * so a short label is not lost in a wide cell.
 *
 * Every tab always shows (5 Nov 2026: "Favourites", one letter longer, pushed Keypad off as
 * "K…", at a larger font size). The labels are measured as they will be drawn, at the phone's
 * own font scale, and the row takes the first of [TAB_STEPS] they fit: roomy at titleMedium,
 * then at the house floor of 16 sp with tighter stones (the row as it always was), then with less
 * room around and between the stones. Labels that still do not fit (a long translation) stay at
 * 16 sp and share the row, a label giving up width by taking two lines, rather than any of them
 * being cut.
 */
@Composable
fun StoneTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Tabs with something waiting in them: a small dot at the corner, said aloud as [markedSaid]. It takes no room. */
    marked: Set<Int> = emptySet(),
    markedSaid: String = "",
    /** The thin rule beneath; left off where the row sits in a bar that draws its own. */
    rule: Boolean = true,
) {
    val base = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    Column(modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val room = constraints.maxWidth
            val (set, shares) = remember(labels, room, density, base) {
                fun width(text: String, sp: Float) =
                    measurer.measure(text, base.copy(fontSize = sp.sp), maxLines = 1, softWrap = false).size.width + 1
                val px = { dp: Int -> with(density) { dp.dp.roundToPx() } }
                val set = fitTabs(room, { sp -> labels.map { width(it, sp) } }, px)
                // Two lines: each label gets at least its longest word and half its width, and the
                // rest of the row is shared by what each still wants.
                val shares = if (!set.wrap) null else {
                    val full = labels.map { width(it, set.sp) }
                    val least = labels.mapIndexed { i, l -> maxOf(l.split(' ').maxOf { width(it, set.sp) }, (full[i] + 1) / 2) }
                    val text = room - labels.size * 2 * px(set.inner) - (labels.size - 1) * px(set.gap) - 2 * px(set.edge)
                    shareTabs(text, full, least).map { with(density) { (it + 2 * px(set.inner)).toDp() } }
                }
                set to shares
            }
            val style = base.copy(fontSize = set.sp.sp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = set.edge.dp)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(set.gap.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                labels.forEachIndexed { index, label ->
                    val chosen = index == selected
                    Box(
                        modifier = Modifier
                            .then(if (shares != null) Modifier.width(shares[index]) else Modifier)
                            .heightIn(min = 40.dp)
                            .then(
                                if (chosen) Modifier.background(Color.Black, RoundedCornerShape(4.dp))
                                else Modifier
                            )
                            .selectable(
                                selected = chosen,
                                role = Role.Tab,
                                onClick = { onSelect(index) },
                            )
                            .semantics { if (index in marked) stateDescription = markedSaid }
                            .padding(horizontal = set.inner.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        TextMMD(
                            text = label,
                            style = style,
                            color = if (chosen) Color.White else Color.Black,
                            textAlign = TextAlign.Center,
                            maxLines = if (set.wrap) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (index in marked) {
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 1.dp).size(7.dp)
                                    .background(if (chosen) Color.White else Color.Black, CircleShape),
                            )
                        }
                    }
                }
            }
        }
        // MMD's divider defaults to 3dp; the rule TopAppBarMMD draws is 1dp, and this matches it.
        if (rule) HorizontalDividerMMD(thickness = 1.dp)
    }
}

/**
 * How the tab row is set: the labels' size in sp, the room inside each stone ([inner]), between
 * stones ([gap]) and at either end of the row ([edge]), in dp, and whether a label may take two
 * lines ([wrap]).
 */
internal data class TabSet(val sp: Float, val inner: Int, val gap: Int, val edge: Int, val wrap: Boolean = false)

/**
 * Tried in order; the first the labels fit is used. The first two are the row as it has always
 * been (roomy, then the labels at the floor with tighter stones), so a row that fits today looks
 * as it did. After them the room around and between the stones gives way, and last of all
 * ([fitTabs]) a label gives up width by taking two lines; the type never goes under the floor.
 */
internal val TAB_STEPS: List<TabSet> = listOf(
    TabSet(20f, 14, 6, 12),
    TabSet(HOUSE_TYPE_FLOOR_SP, 8, 6, 12),
    TabSet(HOUSE_TYPE_FLOOR_SP, 6, 4, 8),
    TabSet(HOUSE_TYPE_FLOOR_SP, 4, 2, 4),
)

/**
 * The first of [TAB_STEPS] whose labels fit [room] pixels: [widths] gives each label's width in
 * pixels at a size in sp, [px] turns dp into pixels. When none fits, the last (the floor, least
 * room) with labels allowed two lines.
 */
internal fun fitTabs(room: Int, widths: (Float) -> List<Int>, px: (Int) -> Int): TabSet {
    for (set in TAB_STEPS) {
        val w = widths(set.sp)
        if (w.isEmpty()) return set
        val need = w.sum() + w.size * 2 * px(set.inner) + (w.size - 1) * px(set.gap) + 2 * px(set.edge)
        if (need <= room) return set
    }
    return TAB_STEPS.last().copy(wrap = true)
}

/**
 * Widths for labels that do not fit [room] pixels on one line each: every label gets at least its
 * [least] width, and what is left is shared by how much more each wants to reach its [full] one.
 * Labels that cannot all have their least share the room by their full widths.
 */
internal fun shareTabs(room: Int, full: List<Int>, least: List<Int>): List<Int> {
    val total = full.sum()
    if (total <= room || total == 0) return full
    val floor = least.sum()
    if (floor >= room) return full.map { (it.toLong() * room / total).toInt() }
    val want = full.indices.map { (full[it] - least[it]).coerceAtLeast(0) }
    val more = want.sum().coerceAtLeast(1)
    return full.indices.map { least[it] + (want[it].toLong() * (room - floor) / more).toInt() }
}
