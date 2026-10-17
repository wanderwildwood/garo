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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
 * Four labels at Messaging's size do not fit 480px ("Dates" fell off Calculator's row and Sky's
 * "Almanac" became "..."), so a row that would overflow steps down to titleSmall with tighter
 * stones rather than losing a tab.
 */
@Composable
fun StoneTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val roomy = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
    val tight = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    Column(modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fits = with(density) {
                val text = labels.sumOf { measurer.measure(it, roomy).size.width }
                val chrome = (labels.size * 28 + (labels.size - 1) * 6 + 24).dp.roundToPx()
                text + chrome <= constraints.maxWidth
            }
            val style = if (fits) roomy else tight
            val inner = if (fits) 14.dp else 8.dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 12.dp)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                labels.forEachIndexed { index, label ->
                    val chosen = index == selected
                    Box(
                        modifier = Modifier
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
                            .padding(horizontal = inner),
                        contentAlignment = Alignment.Center,
                    ) {
                        TextMMD(
                            text = label,
                            style = style,
                            color = if (chosen) Color.White else Color.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        // MMD's divider defaults to 3dp; the rule TopAppBarMMD draws is 1dp, and this matches it.
        HorizontalDividerMMD(thickness = 1.dp)
    }
}
