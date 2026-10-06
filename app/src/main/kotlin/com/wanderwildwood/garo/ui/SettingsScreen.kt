package com.wanderwildwood.garo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import kotlinx.coroutines.delay
import android.text.format.DateFormat
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import com.mudita.mmd.components.switcher.SwitchMMD
import com.wanderwildwood.garo.media.BackupRecord
import com.wanderwildwood.garo.media.BackupWhen
import java.util.Date
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.garo.R
import com.wanderwildwood.garo.media.Choices
import com.wanderwildwood.garo.media.FolderOrder
import com.wanderwildwood.garo.media.PictureOrder

/**
 * Three rows that cycle through their few values in place, then the Immich server.
 *
 * Fossify has some sixty settings. What survives is what changes what a person sees on this
 * screen; the rest — themes, animations, swipe-to-dismiss, slideshow timing, the editor, video
 * playback — either cannot be honoured by the panel or belongs to a part that was left out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    choices: Choices,
    onChoose: (Choices) -> Unit,
    onClose: () -> Unit,
    serverAddress: String?,
    hasKey: Boolean,
    onServer: (String) -> Unit,
    onKey: (String) -> Unit,
    onForgetServer: () -> Unit,
    savedKey: () -> String = { "" },
    backup: BackupState = BackupState(),
    onBackup: (Boolean) -> Unit = {},
    onBackupWhen: (BackupWhen) -> Unit = {},
    hidden: Map<String, String> = emptyMap(),
    onShowFolder: (String) -> Unit = {},
) {
    var aboutOpen by remember { mutableStateOf(false) }
    var hiddenOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Entry?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.settings_title)) },
                navigationIcon = { BarButton(Icons.Close, stringResource(R.string.settings_cd_close), onClose) },
                actions = { BarButton(Icons.Info, stringResource(R.string.settings_cd_about)) { aboutOpen = true } },
            )
        },
    ) { padding ->
        LazyColumnMMD(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            item { Spacer(Modifier.height(12.dp)) }
            item {
                Setting(
                    title = stringResource(R.string.settings_folder_order),
                    value = stringResource(
                        when (choices.folderOrder) {
                            FolderOrder.NEWEST -> R.string.order_newest_first
                            FolderOrder.NAME -> R.string.order_name
                        },
                    ),
                    onClick = { onChoose(choices.copy(folderOrder = choices.folderOrder.next())) },
                )
            }
            item {
                Setting(
                    title = stringResource(R.string.settings_picture_order),
                    value = stringResource(
                        when (choices.pictureOrder) {
                            PictureOrder.NEWEST -> R.string.order_newest_first
                            PictureOrder.OLDEST -> R.string.order_oldest_first
                            PictureOrder.NAME -> R.string.order_name
                        },
                    ),
                    onClick = { onChoose(choices.copy(pictureOrder = choices.pictureOrder.next())) },
                )
            }
            item {
                val sizes = Choices.PER_ROW
                Setting(
                    title = stringResource(R.string.settings_per_row),
                    value = choices.perRow.toString(),
                    onClick = {
                        onChoose(choices.copy(perRow = sizes[(sizes.indexOf(choices.perRow) + 1) % sizes.size]))
                    },
                )
            }

            item { OpensRow() }

            // Only once something is hidden: a row for an empty list would be furniture.
            if (hidden.isNotEmpty()) {
                item {
                    Setting(
                        title = stringResource(R.string.settings_hidden),
                        value = pluralStringResource(R.plurals.settings_hidden_count, hidden.size, hidden.size),
                        onClick = { hiddenOpen = true },
                    )
                }
            }

            // Immich: the server and the key, then — only once there is something to forget —
            // the way to forget it, last, as the one row here that undoes anything.
            item {
                Setting(
                    title = stringResource(R.string.settings_immich_server),
                    value = serverAddress ?: stringResource(R.string.settings_not_set),
                    onClick = { editing = Entry.SERVER },
                )
            }
            item {
                Setting(
                    title = stringResource(R.string.settings_immich_key),
                    // Never the key itself, not even its first letters: this screen may be
                    // photographed, and the key opens every picture on the server.
                    value = stringResource(if (hasKey) R.string.settings_key_set else R.string.settings_not_set),
                    onClick = { editing = Entry.KEY },
                )
            }
            // Backing up asks for a server and a key first; without them there is nothing to
            // back up to, and a switch that cannot do anything is not shown.
            if (serverAddress != null && hasKey) {
                item { BackupRow(backup, onBackup) }
                if (backup.on) {
                    item {
                        Setting(
                            title = stringResource(R.string.settings_backup_when),
                            value = stringResource(
                                when (backup.whenTo) {
                                    BackupWhen.WIFI_CHARGING -> R.string.backup_when_wifi_charging
                                    BackupWhen.WIFI -> R.string.backup_when_wifi
                                    BackupWhen.ANY_NETWORK -> R.string.backup_when_any
                                },
                            ),
                            onClick = { onBackupWhen(backup.whenTo.next()) },
                        )
                    }
                }
            }
            if (serverAddress != null || hasKey) {
                item { ForgetRow(onForgetServer) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (aboutOpen) AboutDialog(onDismiss = { aboutOpen = false })

    if (hiddenOpen && hidden.isNotEmpty()) {
        HiddenDialog(hidden, onShow = onShowFolder, onDismiss = { hiddenOpen = false })
    }

    when (editing) {
        Entry.SERVER -> EntryDialog(
            title = stringResource(R.string.settings_immich_server),
            note = stringResource(R.string.settings_immich_server_note),
            initial = serverAddress ?: "https://",
            secret = false,
            onDone = onServer,
            onDismiss = { editing = null },
        )
        Entry.KEY -> EntryDialog(
            title = stringResource(R.string.settings_immich_key),
            note = stringResource(R.string.settings_immich_key_note),
            // The saved key, hidden: opening this is how a key typed by hand gets checked.
            initial = remember { savedKey() },
            secret = true,
            onDone = onKey,
            onDismiss = { editing = null },
        )
        null -> Unit
    }
}

private enum class Entry { SERVER, KEY }

/** Hidden folders the dialog lists as they are, before it needs a list that pages. */
private const val FITS = 5

/** The hidden folders, each a press away from coming back. Holding a folder hides it again. */
@Composable
private fun HiddenDialog(hidden: Map<String, String>, onShow: (String) -> Unit, onDismiss: () -> Unit) {
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = stringResource(R.string.settings_hidden), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        TextMMD(text = stringResource(R.string.hidden_note), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(8.dp))
        val rows = hidden.entries.sortedBy { it.value.lowercase() }
        val row: @Composable (Map.Entry<String, String>) -> Unit = { (key, label) ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onShow(key) }
                    .padding(vertical = 12.dp),
            ) {
                TextMMD(text = label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        // A few fit as they are; MMD's list fills whatever height it is given, so it is kept for
        // the case it is for — more than the dialog can hold — and the dialog is not left mostly
        // blank around one name.
        if (rows.size <= FITS) {
            rows.forEach { row(it) }
        } else {
            LazyColumnMMD(modifier = Modifier.fillMaxWidth().height(260.dp)) {
                items(rows, key = { it.key }) { row(it) }
            }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedButtonMMD(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            TextMMD(text = stringResource(R.string.info_close), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * The switch, and under it the one line worth reading: how far the backup has got, or what
 * stopped it. Said plainly, including "not yet" — a backup that only looks finished is worse
 * than none, because nobody goes looking.
 */
@Composable
private fun BackupRow(backup: BackupState, onBackup: (Boolean) -> Unit) {
    val context = LocalContext.current
    val status = when {
        !backup.on -> stringResource(R.string.backup_off)
        backup.problem == BackupRecord.Problem.REFUSED -> stringResource(R.string.backup_refused)
        backup.total == 0 -> stringResource(R.string.backup_nothing)
        else -> {
            val count = if (backup.done >= backup.total) {
                stringResource(R.string.backup_all, backup.total)
            } else {
                stringResource(R.string.backup_some, backup.done, backup.total)
            }
            val last = when {
                backup.problem == BackupRecord.Problem.UNREACHABLE -> stringResource(R.string.backup_last_unreachable)
                backup.lastRun > 0 -> stringResource(
                    R.string.backup_last_run,
                    DateFormat.getMediumDateFormat(context).format(Date(backup.lastRun)) + ", " +
                        DateFormat.getTimeFormat(context).format(Date(backup.lastRun)),
                )
                else -> stringResource(
                    when (backup.whenTo) {
                        BackupWhen.WIFI_CHARGING -> R.string.backup_waiting_wifi_charging
                        BackupWhen.WIFI -> R.string.backup_waiting_wifi
                        BackupWhen.ANY_NETWORK -> R.string.backup_waiting_network
                    },
                )
            }
            "$count. $last"
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onBackup(!backup.on) }
            .padding(vertical = 14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            TextMMD(text = stringResource(R.string.settings_backup), style = MaterialTheme.typography.bodyMedium)
            TextMMD(text = status, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(12.dp))
        // The row takes the press; the switch only draws the state.
        SwitchMMD(checked = backup.on, onCheckedChange = null)
    }
}

/** "Forget the server — tap again", disarming itself after four seconds, as every armed row does. */
@Composable
private fun ForgetRow(onForget: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(4_000)
            armed = false
        }
    }
    Setting(
        title = stringResource(if (armed) R.string.settings_forget_server_armed else R.string.settings_forget_server),
        value = stringResource(R.string.settings_forget_server_note),
        onClick = {
            if (armed) {
                armed = false
                onForget()
            } else {
                armed = true
            }
        },
    )
}

/**
 * Typing a server address or a key. Done on the keyboard saves it: a keyboard up over this
 * panel covers the dialog's own buttons.
 */
@Composable
private fun EntryDialog(
    title: String,
    note: String,
    initial: String,
    secret: Boolean,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    // Hidden to begin with, and shown only while the eye says so: keys are typed by hand on
    // this phone, from a screen elsewhere, and the only way to find a slip is to read it back.
    var shown by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Save with nothing typed changes nothing: an empty field is not a request to remove the key,
    // which only "Forget the Immich server" does, and asks first.
    val filled = value.text.isNotBlank() && value.text.trim() != "https://"
    val save = {
        if (filled) onDone(value.text)
        onDismiss()
    }
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(6.dp))
        TextMMD(text = note, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(12.dp))
        TextFieldMMD(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            singleLine = true,
            // A key in Lato puts l, I and 1 side by side looking alike; a monospaced face keeps
            // every character its own width and shape, which is what checking one by eye needs.
            textStyle = if (secret) LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace) else LocalTextStyle.current,
            visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = if (secret) {
                {
                    BarButton(
                        icon = if (shown) Icons.VisibilityOff else Icons.Visibility,
                        description = stringResource(if (shown) R.string.entry_hide_key else R.string.entry_show_key),
                        onClick = { shown = !shown },
                    )
                }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onDone = { save() }),
        )
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth()) {
            OutlinedButtonMMD(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp)) {
                TextMMD(text = stringResource(R.string.entry_cancel), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(10.dp))
            OutlinedButtonMMD(onClick = save, enabled = filled, modifier = Modifier.weight(1f).height(48.dp)) {
                TextMMD(text = stringResource(R.string.entry_save), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private inline fun <reified E : Enum<E>> E.next(): E {
    val all = enumValues<E>()
    return all[(ordinal + 1) % all.size]
}

@Composable
private fun Setting(title: String, value: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    ) {
        TextMMD(text = title, style = MaterialTheme.typography.bodyMedium)
        TextMMD(text = value, style = MaterialTheme.typography.labelSmall)
    }
}
