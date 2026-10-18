package com.wanderwildwood.garo.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.wanderwildwood.garo.media.Decoder
import com.wanderwildwood.garo.media.Picture

/** A 48dp press with a 22dp glyph in it, the size every top bar in this shop uses. */
@Composable
internal fun BarButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(48.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * A square crop of a picture, from the phone's own thumbnail.
 *
 * Until it arrives the square is blank white, not a grey placeholder: grey is a repaint of
 * the whole square now and another when the picture lands, and on this panel the second one
 * is all anybody sees anyway.
 */
@Composable
internal fun Thumbnail(decoder: Decoder, picture: Picture, px: Int, modifier: Modifier) {
    val uri = picture.uri
    // Keyed on the picture, so a square reused for another one never shows the old one first.
    var bitmap by remember(uri, px) { mutableStateOf(decoder.cachedThumbnail(picture, px)) }
    LaunchedEffect(uri, px) {
        if (bitmap == null) bitmap = decoder.thumbnail(picture, px)
    }
    Box(modifier.background(MaterialTheme.colorScheme.surface)) {
        bitmap?.let {
            Image(
                bitmap = remember(it) { it.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
