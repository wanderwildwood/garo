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
import com.wanderwildwood.garo.media.BackupStatus
import com.wanderwildwood.garo.media.Destination
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.font.FontWeight
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
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.garo.R
import com.wanderwildwood.garo.media.Choices
import com.wanderwildwood.garo.media.FolderOrder
import com.wanderwildwood.garo.media.PictureOrder

/**
 * The settings pages: the main one, and under "Servers and backup" the pages the server rows
 * moved to, so that the main one fits on one page of the panel.
 */
private enum class Page { MAIN, SERVERS, IMMICH, NEXTCLOUD, BACKUP }

/** What the Nextcloud page can ask for. The model does it. */
interface NextcloudActions {
    fun setNextcloudAddress(typed: String)
    fun startNextcloudSignIn()
    fun cancelNextcloudSignIn()
    fun signInNextcloudByHand(user: String, appPassword: String)
    fun nextcloudByHand(on: Boolean)
    fun signOutNextcloud()
    fun setNextcloudFolder(typed: String)
}

/**
 * Three rows that cycle through their few values in place, then a row each for the Immich
 * server and backing up, which open pages of their own.
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
    onBackUpNow: () -> Unit = {},
    onBackupFolders: (Set<String>) -> Unit = {},
    hidden: Map<String, String> = emptyMap(),
    onShowFolder: (String) -> Unit = {},
    onReceive: (() -> Unit)? = null,
    /** Each time a page opens: the backup line is read again, as the network may have changed. */
    onPage: () -> Unit = {},
    nextcloud: NextcloudState = NextcloudState(),
    nextcloudActions: NextcloudActions? = null,
    onBackupTo: (Destination) -> Unit = {},
) {
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    LaunchedEffect(page) { onPage() }
    val immichSet = serverAddress != null && hasKey
    val anySet = immichSet || nextcloud.user != null
    val immich = stringResource(R.string.immich_name)
    val nextcloudName = stringResource(R.string.nextcloud_name)
    fun name(to: Destination) = if (to == Destination.IMMICH) immich else nextcloudName
    // Backing up has nothing to go to once both servers are gone; the page above it then.
    when (if (page == Page.BACKUP && !anySet) Page.SERVERS else page) {
        Page.SERVERS -> {
            BackHandler { page = Page.MAIN }
            SubPage(stringResource(R.string.settings_servers), onBack = { page = Page.MAIN }) {
                item {
                    Setting(
                        title = stringResource(R.string.settings_immich_server),
                        value = serverAddress ?: stringResource(R.string.settings_not_set),
                        onClick = { page = Page.IMMICH },
                    )
                }
                if (nextcloudActions != null) {
                    item {
                        Setting(
                            title = nextcloudName,
                            value = nextcloud.user?.let { stringResource(R.string.nextcloud_signed_in_short, it, host(nextcloud.server)) }
                                ?: stringResource(R.string.nextcloud_signed_out),
                            onClick = { page = Page.NEXTCLOUD },
                        )
                    }
                }
                item {
                    // Backing up needs somewhere to go first; until then the row says so and leads there.
                    val to = backup.to
                    Setting(
                        title = stringResource(R.string.settings_backup_page),
                        value = when {
                            !anySet -> stringResource(R.string.settings_backup_needs_server)
                            backup.on && to != null -> stringResource(R.string.settings_backup_on_to, name(to))
                            else -> stringResource(R.string.settings_backup_off_short)
                        },
                        onClick = { page = if (anySet) Page.BACKUP else Page.IMMICH },
                    )
                }
            }
            return
        }
        Page.NEXTCLOUD -> {
            BackHandler { page = Page.SERVERS }
            if (nextcloudActions != null) {
                NextcloudPage(nextcloud, nextcloudActions, onBack = { page = Page.SERVERS })
                return
            }
        }
        Page.IMMICH -> {
            BackHandler { page = Page.SERVERS }
            ImmichPage(
                serverAddress = serverAddress,
                hasKey = hasKey,
                onServer = onServer,
                onKey = onKey,
                onForgetServer = onForgetServer,
                savedKey = savedKey,
                onBack = { page = Page.SERVERS },
            )
            return
        }
        Page.BACKUP -> {
            BackHandler { page = Page.SERVERS }
            BackupPage(
                backup = backup,
                destination = backup.to?.let { name(it) } ?: immich,
                name = ::name,
                onBackupTo = onBackupTo,
                onBackup = onBackup,
                onBackupWhen = onBackupWhen,
                onBackUpNow = onBackUpNow,
                onBackupFolders = onBackupFolders,
                onBack = { page = Page.SERVERS },
            )
            return
        }
        Page.MAIN -> Unit
    }

    var aboutOpen by remember { mutableStateOf(false) }
    var hiddenOpen by remember { mutableStateOf(false) }

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
        PagedColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
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

            item {
                SwitchSetting(
                    title = stringResource(R.string.settings_turn),
                    on = choices.turnWithPhone,
                    onChange = { onChoose(choices.copy(turnWithPhone = it)) },
                )
            }

            item { OpensRow() }

            // Only on a phone that has NFC; elsewhere the row could do nothing.
            if (onReceive != null) {
                item {
                    Setting(
                        title = stringResource(R.string.receive_title),
                        value = stringResource(R.string.receive_setting_note),
                        onClick = onReceive,
                    )
                }
            }

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

            item {
                Setting(
                    title = stringResource(R.string.settings_servers),
                    value = when {
                        !anySet -> stringResource(R.string.settings_servers_none)
                        backup.on && backup.to != null -> stringResource(R.string.settings_servers_backing_up, name(backup.to))
                        immichSet && nextcloud.user != null -> stringResource(R.string.settings_servers_both, immich, nextcloudName)
                        immichSet -> immich
                        else -> nextcloudName
                    },
                    onClick = { page = Page.SERVERS },
                )
            }
        }
    }

    if (aboutOpen) AboutDialog(onDismiss = { aboutOpen = false })

    if (hiddenOpen && hidden.isNotEmpty()) {
        HiddenDialog(hidden, onShow = onShowFolder, onDismiss = { hiddenOpen = false })
    }
}

/** A settings page under the main one: its title, a back arrow, and its rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubPage(title: String, onBack: () -> Unit, rows: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = title) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.settings_cd_back), onBack) },
            )
        },
    ) { padding ->
        PagedColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            item { Spacer(Modifier.height(12.dp)) }
            rows()
        }
    }
}

/** The Immich server: its address and key, and — once there is something to forget — the way to forget it. */
@Composable
private fun ImmichPage(
    serverAddress: String?,
    hasKey: Boolean,
    onServer: (String) -> Unit,
    onKey: (String) -> Unit,
    onForgetServer: () -> Unit,
    savedKey: () -> String,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf<Entry?>(null) }
    SubPage(stringResource(R.string.settings_immich_server), onBack) {
        item { Note(stringResource(R.string.settings_immich_page_note)) }
        item {
            Setting(
                title = stringResource(R.string.settings_immich_address),
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
        if (serverAddress != null || hasKey) {
            item { ForgetRow(onForgetServer) }
        }
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

/** Backing up: the switch and how far it has got, which folders, when, and now. */
@Composable
private fun BackupPage(
    backup: BackupState,
    destination: String,
    name: (Destination) -> String,
    onBackupTo: (Destination) -> Unit,
    onBackup: (Boolean) -> Unit,
    onBackupWhen: (BackupWhen) -> Unit,
    onBackUpNow: () -> Unit,
    onBackupFolders: (Set<String>) -> Unit,
    onBack: () -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }
    SubPage(stringResource(R.string.settings_backup_page), onBack) {
        item { BackupRow(backup, destination, onBackup) }
        // A choice only once there are two places to choose between; a tap moves to the other.
        if (backup.places.size > 1 && backup.to != null) {
            item {
                Setting(
                    title = stringResource(R.string.settings_backup_to),
                    value = name(backup.to),
                    onClick = { onBackupTo(backup.places[(backup.places.indexOf(backup.to) + 1) % backup.places.size]) },
                )
            }
        }
        item {
            Setting(
                title = stringResource(R.string.settings_backup_folders),
                value = stringResource(
                    if (backup.to == Destination.NEXTCLOUD) R.string.backup_folders_photos_videos else R.string.backup_folders_photos,
                    folderNames(backup.folders),
                ),
                onClick = { choosing = true },
            )
        }
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
            item {
                Setting(
                    title = stringResource(R.string.settings_backup_now),
                    value = stringResource(R.string.settings_backup_now_note),
                    onClick = onBackUpNow,
                )
            }
        }
    }
    if (choosing) {
        FoldersDialog(
            choosable = backup.choosable,
            chosen = backup.folders,
            onDone = { onBackupFolders(it); choosing = false },
            onDismiss = { choosing = false },
        )
    }
}

/**
 * "DCIM/" as "Camera (DCIM)", the top of the storage as the folders list names it, anything
 * else as its path without the last slash.
 */
@Composable
private fun folderName(path: String): String =
    folderName(path, stringResource(R.string.backup_folder_dcim), stringResource(R.string.folder_root_phone))

private fun folderName(path: String, dcim: String, top: String): String = when {
    path.equals("DCIM/", ignoreCase = true) -> dcim
    path == "/" -> top
    else -> path.trimEnd('/')
}

@Composable
private fun folderNames(folders: Set<String>): String {
    val dcim = stringResource(R.string.backup_folder_dcim)
    val top = stringResource(R.string.folder_root_phone)
    return folders.sortedWith(String.CASE_INSENSITIVE_ORDER).joinToString(", ") { folderName(it, dcim, top) }
}

/**
 * Which folders go up: a row each, a press to put it in or take it out, bold and marked when in.
 * At least one stays chosen; Done is there once the choice differs from what is saved.
 */
@Composable
private fun FoldersDialog(choosable: List<String>, chosen: Set<String>, onDone: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    var picked by remember { mutableStateOf(chosen) }
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = stringResource(R.string.settings_backup_folders), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        TextMMD(text = stringResource(R.string.backup_folders_note), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(8.dp))
        val row: @Composable (String) -> Unit = { path ->
            val on = picked.any { it.equals(path, ignoreCase = true) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable {
                        val next = if (on) picked.filterNot { it.equals(path, ignoreCase = true) }.toSet() else picked + path
                        if (next.isNotEmpty()) picked = next
                    }
                    .padding(vertical = 8.dp),
            ) {
                TextMMD(
                    text = folderName(path),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(16.dp))
                SwitchMMD(checked = on, onCheckedChange = null)
            }
        }
        if (choosable.size <= FITS) {
            choosable.forEach { row(it) }
        } else {
            PagedColumn(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                items(choosable, key = { it }) { row(it) }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            OutlinedButtonMMD(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp)) {
                TextMMD(text = stringResource(R.string.entry_cancel), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(10.dp))
            OutlinedButtonMMD(onClick = { onDone(picked) }, modifier = Modifier.weight(1f).height(48.dp)) {
                TextMMD(text = stringResource(R.string.entry_save), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** A line or two of explanation between the rows. */
@Composable
private fun Note(text: String) {
    TextMMD(text = text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 8.dp))
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
            PagedColumn(modifier = Modifier.fillMaxWidth().height(260.dp)) {
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
private fun BackupRow(backup: BackupState, destination: String, onBackup: (Boolean) -> Unit) {
    val status = backupLine(backup.line, destination, backup.to)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onBackup(!backup.on) }
            .padding(vertical = 14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            TextMMD(text = stringResource(R.string.settings_backup, destination), style = MaterialTheme.typography.bodyMedium)
            TextMMD(text = status, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(12.dp))
        // The row takes the press; the switch only draws the state.
        Spacer(Modifier.width(16.dp))
        SwitchMMD(checked = backup.on, onCheckedChange = null)
    }
}

/** The status line in words. */
@Composable
private fun backupLine(line: BackupStatus.Line, destination: String, to: Destination?): String {
    val context = LocalContext.current
    return when (line) {
        BackupStatus.Line.Off -> stringResource(R.string.backup_off)
        BackupStatus.Line.Refused -> stringResource(
            if (to == Destination.NEXTCLOUD) R.string.backup_refused_nextcloud else R.string.backup_refused,
        )
        BackupStatus.Line.Nothing -> stringResource(R.string.backup_nothing)
        is BackupStatus.Line.Progress -> {
            val count = if (line.done >= line.total) {
                stringResource(R.string.backup_all, line.total)
            } else {
                stringResource(R.string.backup_some, line.done, line.total)
            }
            val then = when (val t = line.then) {
                BackupStatus.Then.Unreachable -> stringResource(R.string.backup_unreachable, destination)
                BackupStatus.Then.Failed -> stringResource(R.string.backup_failed, destination)
                BackupStatus.Then.Soon -> stringResource(R.string.backup_soon)
                is BackupStatus.Then.LastRun -> stringResource(
                    R.string.backup_last_run,
                    DateFormat.getMediumDateFormat(context).format(Date(t.at)) + ", " +
                        DateFormat.getTimeFormat(context).format(Date(t.at)),
                )
                is BackupStatus.Then.Waiting -> stringResource(
                    when (t.wait) {
                        BackupStatus.Wait.WIFI_AND_CHARGER -> R.string.backup_waiting_wifi_charging
                        BackupStatus.Wait.WIFI -> R.string.backup_waiting_wifi
                        BackupStatus.Wait.CHARGER -> R.string.backup_waiting_charger
                        BackupStatus.Wait.NETWORK -> R.string.backup_waiting_network
                    },
                )
            }
            "$count. $then"
        }
    }
}

/** "Forget the server — tap again", disarming itself after six seconds, as every armed row does. */
@Composable
private fun ForgetRow(onForget: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(6_000)
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
    /** Plain words (a name, a folder) rather than an address. */
    words: Boolean = false,
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
                keyboardType = when {
                    secret -> KeyboardType.Password
                    words -> KeyboardType.Text
                    else -> KeyboardType.Uri
                },
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
            // Save once there is something to save, rather than standing there greyed.
            if (filled) {
                OutlinedButtonMMD(onClick = save, modifier = Modifier.weight(1f).height(48.dp)) {
                    TextMMD(text = stringResource(R.string.entry_save), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Spacer(Modifier.weight(1f))
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
            .padding(vertical = 12.dp),
    ) {
        TextMMD(text = title, style = MaterialTheme.typography.bodyMedium)
        TextMMD(text = value, style = MaterialTheme.typography.labelSmall)
    }
}

/** A setting that is on or off: the row takes the press, the switch only draws the state. */
@Composable
private fun SwitchSetting(title: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { onChange(!on) }
            .padding(vertical = 12.dp),
    ) {
        TextMMD(text = title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        SwitchMMD(checked = on, onCheckedChange = null)
    }
}

/** "https://cloud.example.com" as "cloud.example.com". */
private fun host(server: String?): String = server.orEmpty().substringAfter("://").trimEnd('/')

/**
 * Nextcloud: signing in through its own page in the browser, and once in, the folder pictures go
 * to and signing out. A phone with no browser takes an app password made on the server instead.
 */
@Composable
private fun NextcloudPage(state: NextcloudState, actions: NextcloudActions, onBack: () -> Unit) {
    var editing by remember { mutableStateOf<NcEntry?>(null) }
    var user by remember { mutableStateOf("") }
    SubPage(stringResource(R.string.nextcloud_name), onBack) {
        if (state.user != null) {
            item {
                Setting(
                    title = stringResource(R.string.nextcloud_account),
                    value = stringResource(R.string.nextcloud_signed_in_short, state.user, host(state.server)),
                    onClick = {},
                )
            }
            item {
                Setting(
                    title = stringResource(R.string.nextcloud_folder),
                    value = state.folder,
                    onClick = { editing = NcEntry.FOLDER },
                )
            }
            item { Note(stringResource(R.string.nextcloud_folder_note)) }
            item { Note(stringResource(R.string.nextcloud_browse_note)) }
            item {
                ArmedRow(
                    title = stringResource(R.string.nextcloud_sign_out),
                    armedTitle = stringResource(R.string.nextcloud_sign_out_armed),
                    note = stringResource(R.string.nextcloud_sign_out_note),
                    onConfirm = actions::signOutNextcloud,
                )
            }
        } else {
            item { Note(stringResource(R.string.nextcloud_explain)) }
            item {
                Setting(
                    title = stringResource(R.string.settings_immich_address),
                    value = state.typed.ifEmpty { stringResource(R.string.settings_not_set) },
                    onClick = { editing = NcEntry.ADDRESS },
                )
            }
            if (state.byHand) {
                item {
                    Setting(
                        title = stringResource(R.string.nextcloud_user),
                        value = user.ifEmpty { stringResource(R.string.settings_not_set) },
                        onClick = { editing = NcEntry.USER },
                    )
                }
                item {
                    Setting(
                        title = stringResource(R.string.nextcloud_app_password),
                        value = stringResource(R.string.nextcloud_app_password_note),
                        onClick = { editing = NcEntry.PASSWORD },
                    )
                }
            } else if (state.typed.isNotEmpty()) {
                item {
                    Setting(
                        title = stringResource(
                            when (state.signIn) {
                                SignIn.IDLE -> R.string.nextcloud_sign_in
                                SignIn.WAITING -> R.string.nextcloud_waiting
                                SignIn.CHECKING -> R.string.nextcloud_checking
                            },
                        ),
                        value = stringResource(
                            when (state.signIn) {
                                SignIn.IDLE -> R.string.nextcloud_sign_in_note
                                SignIn.WAITING -> R.string.nextcloud_waiting_note
                                SignIn.CHECKING -> R.string.nextcloud_checking_note
                            },
                        ),
                        onClick = {
                            when (state.signIn) {
                                SignIn.IDLE -> actions.startNextcloudSignIn()
                                SignIn.WAITING -> actions.cancelNextcloudSignIn()
                                SignIn.CHECKING -> Unit
                            }
                        },
                    )
                }
            }
            state.problem?.let { p ->
                item {
                    Note(
                        stringResource(
                            when (p) {
                                NextcloudProblem.PLAIN_HTTP -> R.string.nextcloud_plain_http
                                NextcloudProblem.UNREACHABLE -> R.string.nextcloud_unreachable
                                NextcloudProblem.REFUSED -> R.string.nextcloud_refused
                                NextcloudProblem.NO_BROWSER -> R.string.nextcloud_no_browser
                                NextcloudProblem.NOT_KEPT -> R.string.nextcloud_not_kept
                                NextcloudProblem.TIMED_OUT -> R.string.nextcloud_timed_out
                            },
                        ),
                    )
                }
            }
            if (!state.byHand) {
                item {
                    Setting(
                        title = stringResource(R.string.nextcloud_by_hand),
                        value = stringResource(R.string.nextcloud_by_hand_note),
                        onClick = { actions.cancelNextcloudSignIn(); actions.nextcloudByHand(true) },
                    )
                }
            }
        }
    }
    when (editing) {
        NcEntry.ADDRESS -> EntryDialog(
            title = stringResource(R.string.nextcloud_address_title),
            note = stringResource(R.string.nextcloud_address_note),
            initial = state.typed.ifEmpty { "https://" },
            secret = false,
            onDone = actions::setNextcloudAddress,
            onDismiss = { editing = null },
        )
        NcEntry.USER -> EntryDialog(
            title = stringResource(R.string.nextcloud_user),
            note = stringResource(R.string.nextcloud_user_note),
            initial = user,
            secret = false,
            onDone = { user = it.trim() },
            onDismiss = { editing = null },
            words = true,
        )
        NcEntry.PASSWORD -> EntryDialog(
            title = stringResource(R.string.nextcloud_app_password),
            note = stringResource(R.string.nextcloud_app_password_how),
            initial = "",
            secret = true,
            onDone = { actions.signInNextcloudByHand(user, it) },
            onDismiss = { editing = null },
        )
        NcEntry.FOLDER -> EntryDialog(
            title = stringResource(R.string.nextcloud_folder),
            note = stringResource(R.string.nextcloud_folder_note),
            initial = state.folder,
            secret = false,
            onDone = actions::setNextcloudFolder,
            onDismiss = { editing = null },
            words = true,
        )
        null -> Unit
    }
}

private enum class NcEntry { ADDRESS, USER, PASSWORD, FOLDER }

/** A row that asks for a second tap before it acts, and forgets the first after six seconds. */
@Composable
private fun ArmedRow(title: String, armedTitle: String, note: String, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(6_000)
            armed = false
        }
    }
    Setting(
        title = if (armed) armedTitle else title,
        value = note,
        onClick = {
            if (armed) {
                armed = false
                onConfirm()
            } else {
                armed = true
            }
        },
    )
}
