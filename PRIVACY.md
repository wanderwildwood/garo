# Privacy

Gallery reads the pictures on the phone to show them to you, and has no way to send them
anywhere.

That is the whole policy. The rest of this page is the evidence for it, because a privacy
policy that cannot be checked is just a promise.

## One permission

`app/src/main/AndroidManifest.xml` declares exactly one:

```
android.permission.READ_EXTERNAL_STORAGE
```

On Android 12, which the Kompakt runs, this is the permission that reads photos. Android
words it as "photos and media"; the app reads only pictures, from Android's own index of
them, and never lists or opens any other kind of file.

There is **no `INTERNET` permission**. Without it Android will not let the app open a
network connection, so nothing it can see can leave the phone even by accident, and no
promise from me is load-bearing.

There is **no all-files permission** (`MANAGE_EXTERNAL_STORAGE`), which the gallery this
started from asks for so it can search the storage itself.

**Deleting needs no permission of its own.** Every delete goes through Android's own
question, "Allow Gallery to delete this photo?", and only a yes deletes anything.

## Pictures another app hands over

When Files, Email or Messaging opens a picture here, that app grants read access to that one
picture, for as long as it is on screen. It is shown and not kept.

When you choose pictures for another app — an attachment, say — that app is granted read
access to the ones you chose and nothing else.

## What is stored

Only the three things in settings — how folders are ordered, how pictures are ordered, and
how many go across a row — in `SharedPreferences`. See `media/Settings.kt`. Thumbnails are
held in memory while the app runs and are not written anywhere.

## No analytics

No crash reporting, no telemetry, no advertising identifier, no third-party SDK of any kind.
The dependency list in `app/build.gradle.kts` is AndroidX, Jetpack Compose and Mudita's MMD
component library, and nothing else.

## Checking any of this for yourself

The source is here in full. If you would rather not read it:

```
aapt2 dump badging app-release.apk | grep uses-permission
```

That prints every permission the built app actually carries. It prints two lines:

```
uses-permission: name='android.permission.READ_EXTERNAL_STORAGE'
uses-permission: name='com.wanderwildwood.garo.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

The first is the one described above. The second is not mine: AndroidX defines it
automatically for every app, it is signature-level and scoped to this package so only this
app can hold it, and it exists so a runtime-registered broadcast receiver is not exported to
other apps. It grants access to nothing.

There is no `INTERNET` in that list, which is the claim above without having to trust me.
