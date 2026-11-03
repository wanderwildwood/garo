package com.wanderwildwood.garo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The rule between the rows of an ordinary list: a thin dotted line that starts where the row's
 * text starts, as Mudita's own lists and the InkApps store draw it. MMD 1.0.2 has only a solid
 * divider (HorizontalDividerMMD, 3 dp, edge to edge), so this is drawn here. Settings pages
 * draw no rules at all (STYLE.md).
 *
 * Self-contained on purpose, so another app can copy this one file. The dashes are the ones
 * measured off the Kompakt's contacts list: about 2.3 dp of ink, 1.5 dp of gap, 1 dp thick
 * (never more than 1.5 px, so it stays a hairline on the panel).
 *
 * @param start where the row's text starts; the rule begins there.
 * @param end how far short of the right edge it stops; by default where the text ends, the same
 *   inset as [start], as the Kompakt's contacts list does (house rule, 3 Nov 2026).
 */
@Composable
fun DottedRule(
    modifier: Modifier = Modifier,
    start: Dp = 16.dp,
    end: Dp = start,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(
        modifier
            .fillMaxWidth()
            .padding(start = start, end = end)
            .height(1.dp),
    ) {
        drawLine(
            color = ink,
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = 1.dp.toPx().coerceAtMost(1.5f),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.3.dp.toPx(), 1.5.dp.toPx())),
        )
    }
}
