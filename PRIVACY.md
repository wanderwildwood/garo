# Privacy

Gallery reads the pictures on the phone to show them to you. If you give it an Immich server,
it reads your albums and your timeline (every picture on your account) from that server; if you also turn backing up on, it sends the camera's
pictures there. It talks to that server and nothing else.

That is the whole policy. The rest of this page is the evidence for it, because a privacy
policy that cannot be checked is just a promise.

## Three permissions

`app/src/main/AndroidManifest.xml` declares exactly three:

```
android.permission.READ_EXTERNAL_STORAGE
android.permission.INTERNET
android.permission.NFC
```

On Android 12, which the Kompakt runs, the first is the permission that reads photos. Android
words it as "photos and media"; the app reads only pictures, from Android's own index of
them, and never lists or opens any other kind of file.

**`INTERNET` is there for Immich alone.** Until a server is set in settings the app opens no
connection at all. Once one is, it asks that server — and only that server — for the list of
albums, how many pictures the timeline holds, an album's or the timeline's pictures, and each
picture's image. Every request carries the API key you
gave it. Every call it makes is in `media/Immich.kt`.

**Backing up is off until you turn it on.** Turned on, it sends the camera's pictures —
those under `DCIM` and nothing else — to the same server, and only when the network and the
charger allow as set, or straight away on any network when you press **Back up now**. Before sending, it asks the server which it already has, by each file's
SHA-1; the checksums go to your server and nowhere else. It never deletes anything, on the
phone or the server. Plain `http://` is refused: Android blocks unencrypted
traffic for this app, so the address has to be `https://`.

There is **no all-files permission** (`MANAGE_EXTERNAL_STORAGE`), which the gallery this
started from asks for so it can search the storage itself.

**Deleting needs no permission of its own.** Every delete goes through Android's own
question — "Allow Gallery to delete this photo?", or "…delete 3 photos?" when several are
marked — and only a yes deletes anything.

**`NFC` is for Share by tap, and nothing else.** It is a normal permission, granted at install,
so nothing is asked. See the next section.

**Editing never changes a picture.** An edited picture is saved as a new picture beside the
original, through Android's own index; the original is left as it was.

## Share by tap

Choosing **Share by tap** on a picture serves that one picture to the other phone, and only on
the local network.

- A small web server starts inside the app, on the phone's Wi-Fi or hotspot address and no
  other: never on mobile data, a VPN or every address. It serves that one picture at a link
  with a random 128-bit part, and nothing else; every other address is a 404.
- The link is handed over by NFC: this phone acts as a tag holding it, and the other phone
  opens it in its browser. The link is also shown on screen as text.
- **Anyone on the same network who can read the link can download the picture**, once. It
  stops after one finished download, after two minutes, or when you leave the screen,
  whichever comes first.
- Nothing goes to the internet or to any server of mine or anyone's. `INTERNET` is not used
  for this; the picture crosses the local network only, in the clear (plain `http`, which is
  why the link works on any phone and why it only lasts two minutes).
- The picture is copied into the app's cache for the time it is served and deleted when the
  screen is left.
- **What is sent.** The file as the phone's library gives it. Android removes the location
  from a picture in the phone's own library before this app can read it (the app does not
  hold the permission that would allow the location through), the same as for Share; the
  camera, the date and other details stay in the file. A picture another app handed over is
  sent as that app gave it. There is no extra stripping setting.
- The phone answers NFC readers as a tag only while the Share by tap screen is showing; the
  component is switched off the rest of the time.

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

That prints every permission the built app actually carries. It prints eight lines:

```
uses-permission: name='android.permission.READ_EXTERNAL_STORAGE'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.NFC'
uses-permission: name='android.permission.WAKE_LOCK'
uses-permission: name='android.permission.ACCESS_NETWORK_STATE'
uses-permission: name='android.permission.RECEIVE_BOOT_COMPLETED'
uses-permission: name='android.permission.FOREGROUND_SERVICE'
uses-permission: name='com.wanderwildwood.garo.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

The first three are the ones described above. The next four come with WorkManager, Android's
own library for work that waits on conditions, which is what runs the backup: it needs to
know whether the phone is on Wi-Fi, to keep the phone awake for an upload already started, to
put the schedule back after a restart, and to run a long job. None of them reads anything, and
with backing up off they are not used. The last is not mine either: AndroidX defines it
automatically for every app, it is signature-level and scoped to this package so only this
app can hold it, and it exists so a runtime-registered broadcast receiver is not exported to
other apps. It grants access to nothing.
