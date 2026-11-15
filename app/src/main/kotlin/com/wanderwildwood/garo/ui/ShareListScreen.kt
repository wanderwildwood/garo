package com.wanderwildwood.garo.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.garo.R

/** Apps that can take the picture, as plain rows. Back closes; a press starts that app. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ShareListScreen(uris: List<Uri>, types: List<String?>, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val send = remember(uris, types) { ShareTargets.intent(uris, types) }
    val targets = remember(send) { ShareTargets.find(context.packageManager, send, context.packageName) }
    // An app that would not start says so at the foot, until the next press.
    var refused by remember { mutableStateOf(false) }
    LaunchedEffect(refused) {
        if (refused) {
            NextPress.await()
            refused = false
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        TopAppBarMMD(
            title = { TextMMD(text = stringResource(R.string.share_title)) },
            navigationIcon = { BarButton(Icons.Back, stringResource(R.string.viewer_cd_back), onClose) },
        )
        if (targets.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(20.dp)) {
                TextMMD(
                    text = stringResource(if (uris.size > 1) R.string.share_none_many else R.string.share_none),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            PagedColumn(Modifier.fillMaxWidth().weight(1f), showCutRow = true) {
                itemsIndexed(targets, key = { _, it -> it.packageName + "/" + it.className }) { i, t ->
                    Column {
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .clickable {
                                runCatching { context.startActivity(ShareTargets.aimed(send, t)) }
                                    .onSuccess { onClose() }
                                    .onFailure { refused = true }
                            }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        TextMMD(text = t.label, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (i < targets.lastIndex) DottedRule(start = 20.dp)
                    }
                }
            }
        }
        if (refused) {
            TextMMD(
                text = stringResource(R.string.viewer_no_share),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }
    }
}
