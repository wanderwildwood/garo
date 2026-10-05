# Privacy

Gallery reads the pictures on the phone to show them to you. If you give it an Immich server,
it reads your albums from that server; if you also turn backing up on, it sends the camera's
pictures there. It talks to that server and nothing else.

That is the whole policy. The rest of this page is the evidence for it, because a privacy
policy that cannot be checked is just a promise.

## Two permissions

`app/src/main/AndroidManifest.xml` declares exactly two:

```
android.permission.READ_EXTERNAL_STORAGE
android.permission.INTERNET
```

On Android 12, which the Kompakt runs, the first is the permission that reads photos. Android
words it as "photos and media"; the app reads only pictures, from Android's own index of
them, and never lists or opens any other kind of file.

**`INTERNET` is there for Immich alone.** Until a server is set in settings the app opens no
connection at all. Once one is, it asks that server — and only that server — for the list of
albums, an album's pictures, and each picture's image. Every request carries the API key you
gave it. Every call it makes is in `media/Immich.kt`.

**Backing up is off until you turn it on.** Turned on, it sends the camera's pictures —
those under `DCIM` and nothing else — to the same server, and only when the network and the
charger allow as set. Before sending, it asks the server which it already has, by each file's
SHA-1; the checksums go to your server and nowhere else. It never deletes anything, on the
phone or the server. Plain `http://` is refused: Android blocks unencrypted
traffic for this app, so the address has to be `https://`.

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

The settings — how folders and pictures are ordered, how many go across a row, and the Immich
address and key — in `SharedPreferences`, which no other app can read. See
`media/Settings.kt`. The settings row never shows the key. Its dialog opens with the key
hidden, behind an eye that shows it — for reading back a key typed by hand.

The app is excluded from Android backups (`allowBackup="false"`), so the key does not travel
into a phone backup.

Pictures fetched from Immich are kept in the app's cache, up to 200 MB, so they need not be
fetched again; Android may clear it, and "Forget the Immich server" in settings deletes it
along with the address and key. The phone's own pictures are not copied anywhere.

## No analytics

No crash reporting, no telemetry, no advertising identifier, no third-party SDK of any kind.
The dependency list in `app/build.gradle.kts` is AndroidX, Jetpack Compose and Mudita's MMD
component library, and nothing else.

## Checking any of this for yourself

The source is here in full. If you would rather not read it:

```
aapt2 dump badging app-release.apk | grep uses-permission
```

That prints every permission the built app actually carries. It prints seven lines:

```
uses-permission: name='android.permission.READ_EXTERNAL_STORAGE'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.WAKE_LOCK'
uses-permission: name='android.permission.ACCESS_NETWORK_STATE'
uses-permission: name='android.permission.RECEIVE_BOOT_COMPLETED'
uses-permission: name='android.permission.FOREGROUND_SERVICE'
uses-permission: name='com.wanderwildwood.garo.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

The first two are the ones described above. The next four come with WorkManager, Android's
own library for work that waits on conditions, which is what runs the backup: it needs to
know whether the phone is on Wi-Fi, to keep the phone awake for an upload already started, to
put the schedule back after a restart, and to run a long job. None of them reads anything, and
with backing up off they are not used. The last is not mine either: AndroidX defines it
automatically for every app, it is signature-level and scoped to this package so only this
app can hold it, and it exists so a runtime-registered broadcast receiver is not exported to
other apps. It grants access to nothing.
