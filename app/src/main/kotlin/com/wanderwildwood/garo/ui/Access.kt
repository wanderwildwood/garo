package com.wanderwildwood.garo.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
    val activity = LocalContext.current as Activity
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val mayAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_EXTERNAL_STORAGE)
        viewModel.permissionAnswered(granted, mayAskAgain)
    }
    return { launcher.launch(Manifest.permission.READ_EXTERNAL_STORAGE) }
}

/**
 * Read the index again whenever this screen comes to the front: the camera may have added a
 * picture, or the permission been given in Android's settings, while it was behind.
 */
@Composable
internal fun RefreshOnResume(viewModel: GalleryViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/** Android's own page for this app, where a refused permission can be given after all. */
internal fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
}
