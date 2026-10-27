# Privacy

Gallery reads the pictures on the phone to show them to you. If you give it an Immich server,
it reads your albums and your timeline (every picture on your account) from that server; if you also turn backing up on, it sends the
pictures in the folders you choose (the camera's, DCIM, unless you choose others) there. Apart from Share by tap and Receive
by tap, which pass one picture to another phone on the same Wi-Fi, it talks to that server and nothing else.

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

**`INTERNET` is there for Immich, and for Share by tap and Receive by tap.** Android asks for
the same permission whether an app talks to a server across the world or to the phone next to
it on the same Wi-Fi, so the two tap features need it too; what they do with it is in their
sections below, and none of it leaves the local network. For Immich: until a server is set in
settings the app opens no connection to one at all. Once one is, it asks that server — and only that server — for the list of
albums, how many pictures the timeline holds, an album's or the timeline's pictures, and each
picture's image. Every request carries the API key you
gave it. Every call it makes is in `media/Immich.kt`.

**Backing up is off until you turn it on.** Turned on, it sends the pictures in the folders
chosen under **Settings → Back up → Folders backed up** — `DCIM`, the camera's, until you choose
others; everything below a chosen folder goes, the pictures already there as well as new ones —
to the same server, and only when the network and the
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
  opens it in its browser. The same link is also drawn on screen as a QR code, for a phone
  whose camera can read it (the link is not shown as text).
- **Anyone on the same network who can read the link can download the picture**, once. It
  stops after one finished download, after two minutes, or when you leave the screen,
  whichever comes first.
- Nothing goes to the internet or to any server of mine or anyone's. The local server needs
  `INTERNET`, which is Android's permission for any network socket, but the picture crosses
  the local network only, in the clear (plain `http`, which is
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

## Receive by tap

**Settings → Receive by tap** turns this phone into the reader for the other side of a tap:
while that screen is showing, and only then, it reads the link off a phone held to it that is
showing Share by tap, and fetches the picture.

- The link must be exactly the shape Share by tap writes: `http://`, a numeric IPv4 address
  in a private range (`10.x`, `172.16`–`172.31`, `192.168.x`, or the link-local `169.254.x`),
  a port, and a 32-digit token. Any other address, a host name, `https`, a public address, is
  ignored. So the only place this phone will ever connect to on a tap is a machine on its own
  local network, and nothing leaves that network.
- It makes one plain `http` request to that address, over a socket opened by the app itself;
  the cleartext setting for the rest of the app is not changed, so Immich still has to be
  `https://`. At most 64 MB is taken.
- The download goes to the app's cache and is checked to be a picture Android can read before
  anything is kept. Then it is saved into Pictures/Received through Android's media index,
  which needs no permission; a file that is not a picture is deleted.
- No account, no server of mine or anyone's, nothing past the local network; `INTERNET` (for
  the one local request) and `NFC` are the two permissions it uses, both already above.

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
The dependency list in `app/build.gradle.kts` is AndroidX, Jetpack Compose, Mudita's MMD
component library and ZXing's `core` (Apache-2.0, which only turns a link into the dots of a QR
code: no scanner, no camera), and nothing else.

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

## Contact

Questions and problems: email@wanderthe.dev. The same text in short is in the app, under
Settings → About, with the terms: the app is free software under the GNU General Public License
v3 and comes with no warranty.
