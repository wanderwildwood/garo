package com.wanderwildwood.garo.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Asking for the one permission, shared by the gallery and the picker so the two cannot
 * come to say different things about it. Returns the press that asks.
 */
@Composable
internal fun rememberAsk(viewModel: GalleryViewModel): () -> Unit {
    val activity = checkNotNull(LocalActivity.current) { "rememberAsk needs an Activity" }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val mayAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_EXTERNAL_STORAGE)
        viewModel.permissionAnswered(granted, mayAskAgain)
    }
    return { launcher.launch(Manifest.permission.READ_EXTERNAL_STORAGE) }
}

/**
 * Read the index again whenever this screen comes to the front: the camera may have added a
 * picture, or the permission been given in Android's settings, while it was behind. While it
 * is in sight, the index is watched too, so a picture added then shows without leaving.
 */
@Composable
internal fun RefreshOnResume(viewModel: GalleryViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val resolver = LocalContext.current.contentResolver
    DisposableEffect(lifecycleOwner, resolver) {
        val watcher = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = viewModel.indexChanged()
        }
        var watching = false
        fun stopWatching() {
            if (watching) resolver.unregisterContentObserver(watcher)
            watching = false
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.refresh()
                Lifecycle.Event.ON_START -> if (!watching) {
                    watching = runCatching {
                        resolver.registerContentObserver(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, watcher)
                    }.isSuccess
                }
                Lifecycle.Event.ON_STOP -> stopWatching()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stopWatching()
        }
    }
}

/** Android's own page for this app, where a refused permission can be given after all. */
internal fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
}
