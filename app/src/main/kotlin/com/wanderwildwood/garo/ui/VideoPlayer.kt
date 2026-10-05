package com.wanderwildwood.garo.ui

import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.garo.R
import com.wanderwildwood.garo.media.Clock
import kotlinx.coroutines.delay

/**
 * A video, played here rather than handed to another app.
 *
 * Android's own VideoView, which needs no library and plays whatever the phone can. Below it,
 * one bar: back ten seconds, play or pause, on ten seconds, and where it is. The clock moves
 * once a second while playing — the picture above it is repainting anyway — and stands still
 * when paused, so a paused video costs the panel nothing.
 */
@Composable
fun VideoPlayer(uri: Uri, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var view by remember { mutableStateOf<VideoView?>(null) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableIntStateOf(0) }
    var length by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(playing) {
        // The end is said by the completion listener. Asking the view whether it is playing here
        // would ask in the instant after start(), when it does not yet say so, and stop the clock.
        while (playing) {
            view?.let { position = it.currentPosition }
            delay(1_000)
        }
        view?.let { position = it.currentPosition }
    }
    DisposableEffect(Unit) { onDispose { view?.stopPlayback() } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { context ->
                    VideoView(context).apply {
                        setOnPreparedListener {
                            length = duration
                            start()
                            playing = true
                        }
                        setOnCompletionListener {
                            playing = false
                            position = length
                        }
                        setOnErrorListener { _, _, _ ->
                            failed = true
                            playing = false
                            true
                        }
                        setVideoURI(uri)
                        view = this
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (failed) Explain(text = stringResource(R.string.video_failed))
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            TextMMD(
                text = "${Clock.format(position.toLong())} / ${Clock.format(length.toLong())}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButtonMMD(onClick = onClose, modifier = Modifier.height(44.dp)) {
                    TextMMD(text = stringResource(R.string.video_close), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButtonMMD(
                    onClick = { view?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)); position = it.currentPosition } },
                    modifier = Modifier.height(44.dp),
                ) { TextMMD(text = stringResource(R.string.video_back_ten), style = MaterialTheme.typography.bodySmall) }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        .clickable {
                            view?.let {
                                if (it.isPlaying) {
                                    it.pause()
                                    playing = false
                                } else {
                                    if (position >= length && length > 0) it.seekTo(0)
                                    it.start()
                                    playing = true
                                }
                            }
                        },
                ) {
                    Icon(
                        if (playing) Icons.Pause else Icons.Play,
                        stringResource(if (playing) R.string.video_cd_pause else R.string.video_cd_play),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp),
                    )
                }
                OutlinedButtonMMD(
                    onClick = { view?.let { it.seekTo((it.currentPosition + 10_000).coerceAtMost(it.duration)); position = it.currentPosition } },
                    modifier = Modifier.height(44.dp),
                ) { TextMMD(text = stringResource(R.string.video_on_ten), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
