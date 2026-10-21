package com.wanderwildwood.garo.ui

import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.garo.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A list's answer to a swipe down at its top: run [run], which asks the server and returns how
 * it answered, and say so in [pulled] meanwhile. A second pull while one is still running or
 * still being reported is the same pull.
 */
class PullState internal constructor(
    private val scope: CoroutineScope,
    private val run: State<suspend () -> Server>,
) {
    var pulled by mutableStateOf<Pulled?>(null)
        private set
    private var job: Job? = null

    fun pull() {
        if (job?.isActive == true) return
        job = scope.launch {
            Log.i("garo", "pulled at the top of a list: asking Immich")
            pulled = Pulled.ASKING
            pulled = pulledAfter(run.value())
            delay(PULLED_LINE_MS)
            pulled = null
        }
    }
}

@Composable
fun rememberPull(run: suspend () -> Server): PullState {
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(run)
    return remember { PullState(scope, latest) }
}

/** The line above a pulled list, while there is something to say. */
@Composable
fun PulledLine(pulled: Pulled?) {
    if (pulled == null) return
    TextMMD(
        text = stringResource(
            when (pulled) {
                Pulled.ASKING -> R.string.pull_asking
                Pulled.UP_TO_DATE -> R.string.pull_up_to_date
                Pulled.UNREACHABLE -> R.string.pull_unreachable
                Pulled.REFUSED -> R.string.immich_refused
            },
        ),
        style = MaterialTheme.typography.labelSmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
    )
}
