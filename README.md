<img src="assets/icon.svg" width="64" height="64" alt="">

# Looper

A bare-bones loop pedal for Android. Record a loop, play it back, layer over it.

- No ads, no accounts, no analytics, no in-app purchases
- One permission: the microphone. The app does not request `INTERNET`, so it cannot send data anywhere.
- No third-party libraries. The app uses only the Android framework (APK is ~75 KB).
- Loops are saved in app-private storage on the phone. Use **Export** if you want a WAV file in `Music/Looper`.

## Using it

The big circle is the pedal. Each tap does the next thing:

| State      | Tap does                                          |
|------------|---------------------------------------------------|
| Record     | Start recording the first layer                   |
| Rec        | Close the loop (sets its length) and start playing |
| Playing    | Start recording a new layer over the loop         |
| Layer N    | Finish the layer (it keeps playing)               |
| Paused     | Play from where you paused                        |

- **Pause**: pauses playback where it is. A layer you're recording is kept.
- **Scrub bar** / **⏮ Start**: jump anywhere in the loop, while paused or playing.
- **Undo**: throws away the layer in progress, or the last layer.
- **New** / **Save** / **Loops**: start a fresh loop, save the current one, or browse and search saved loops. Long-press a saved loop to rename, export, or delete it.
- **Layers**: mute or delete individual layers, or tap **Pitch** to add a pitch-shifted copy of a layer (up or down a third, fourth, fifth, or octave) as a new layer. Instant harmonies. Timing stays locked to the loop.
- **Sync**: the app measures audio latency so new layers line up with what you heard. If layers still come out early or late on your phone, nudge them here.
- A Bluetooth page-turner pedal or keyboard works the pedal too (Space, Page Up/Down, or the headset button).

Use wired headphones when adding layers. Otherwise the mic re-records the loop coming out of the speaker. Bluetooth headphones add a lot of delay.

The loop pauses when you leave the app and picks up where it was when you come back (until the app is closed).

## Building

Everything the build needs (JDK, Android SDK, Gradle cache) goes into `.toolchain/` inside this repo. Nothing is installed system-wide, and deleting `.toolchain/` removes it all.

```sh
./scripts/setup-toolchain.sh   # one time: downloads JDK 21 + Android SDK into .toolchain/
./scripts/build.sh             # builds app/build/outputs/apk/debug/app-debug.apk
```

## Installing on your phone

Either:

- **USB**: enable Developer options → USB debugging on the phone, plug it in, then
  `source scripts/env.sh && adb install -r app/build/outputs/apk/debug/app-debug.apk`
- **Copy the file**: send `app-debug.apk` to the phone (cable, Drive, etc.), open it, and allow
  your file manager to "install unknown apps" when prompted.

Requires Android 8.0 or newer. Exporting WAVs requires Android 10 or newer.

The APK is signed with a debug key stored in `.toolchain/android-home/`. If you delete `.toolchain/`, a new key gets generated and Android will refuse to update the installed app. You'd have to uninstall it first, which deletes your saved loops (export anything you want to keep).
