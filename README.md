<p align="center"><img src="extension/media/extension-icon.png" width="128" alt="Wagyu, a red Shiba Inu, reading a Markdown page aloud"></p>

# AI Markdown Reader: Read Aloud for Android

Listen to Markdown on your phone with a **natural AI voice that runs on the phone itself**. The rendered page
highlights each sentence and the word being spoken, and scrolls along. Tables are read with their column names,
lists flow as sentences, and technical text such as `IcM`, `v1.2.3` or `$12.50` is said the way people say it.

It is the Android version of [AI Markdown Reader for VS Code](https://github.com/Orexii/ai-markdown-reader), and
reads documents exactly the same way: it runs the extension's own code to render the page and build the narration,
and a Kotlin port of its pronunciation engine that is tested to give identical results.

## Install

- **Google Play:** coming soon.
- **APK:** download `ai-markdown-reader-<version>.apk` from the
  [latest release](https://github.com/Orexii/ai-markdown-reader-android/releases/latest) and open it.

Android 8.0 or later. The natural voice is a one-time download the first time you listen: **92 MB** (Compact,
the default) or **326 MB** (Best). After that, speech is generated entirely on the phone, even offline. Phones from
the last few years read faster than real time; you can also use the phone's own voice instead.

## Use

- **Open** a Markdown file, or a folder (images and links to other files then work too). You can also choose
  **AI Markdown Reader** under **Open with** or **Share** in a file manager, Gmail, Drive and other apps.
- **Play** in the player at the bottom. It resumes where you stopped last time.
- **Double-tap a word** to read from there. Or select text and choose **Read aloud from here** or
  **Read selection aloud**.
- Scroll away and the page stops following the reading; **Back to reading** brings it back.
- Reading continues with the screen off. The notification, lock screen, watch, car and Bluetooth headset control
  it; headset previous and next skip by paragraph.

| Player | Action |
| --- | --- |
| \|◀ ▶\| | Previous / next paragraph (previous goes to the start of this one first) |
| ↺ 10 / 30 ↻ | Back 10 / forward 30 seconds |
| 1× | Speed: 0.5×, 1×, 1.5×, 2×; **More speeds…** offers every quarter step from 0.25× to 5× |
| ⚙ | Voice, tables, code blocks, word highlighting, following, more speeds, skip lengths, pronunciations, theme |

With a keyboard (a Fold with a keyboard case, a tablet, a Chromebook), the extension's keys work in the page:
← / → paragraph, Shift+← / Shift+→ sentence, Shift+↑ / Shift+↓ section, Ctrl+← / Ctrl+→ or J / L skip, K play or
pause, - / + speed.

## How documents are read

As in the extension: lists read as sentences, numbered steps keep their numbers and task items say whether they
are completed; tables say how many rows and which columns they have, then read each row with its column names (or
column by column); code blocks are announced with their language and length (or read line by line, or skipped);
headings, quotes and callouts get natural pauses and a short cue; links read their text, bare URLs just the site
name, and images their description; acronyms are spelled, identifiers split, and money, times, years, ranges,
versions, units and keyboard shortcuts normalized. Add your own words under **Pronunciations**, for example
`Kusto` → `kuss toe`, or IPA between slashes such as `/ɹɑːkˈiːm/`.

## Voices

The natural voice is [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) (Apache-2.0), run with ONNX
Runtime on the phone. Words get the exact phonemes Kokoro was trained with, from the lexicons of
[misaki](https://github.com/hexgrad/misaki), with eSpeak NG for words they do not know. **Heart** (American
English) is the default; 28 American and British voices are in the settings (0.5 MB each, downloaded when chosen).
The player prepares a minute or two of speech ahead and plays the sentences as one continuous stream, so there are
no gaps between them at any speed.

**Phone's voice** uses the phone's text-to-speech app instead (for example Speech Services by Google). It needs
no download; quality varies and word highlighting is approximate where the voice does not report word timing.

## Privacy

Your documents never leave the phone. The only network access is the voice download you ask for (from a pinned
revision on Hugging Face, size and SHA-256 checked), images on the web that a document shows, and links you open.
No accounts, ads, analytics or tracking. See [PRIVACY.md](PRIVACY.md).

## Development

The extension is a submodule (`extension/`); the page renderer and narration builder are its TypeScript, bundled
into the app's assets. eSpeak NG is a submodule (`third_party/espeak-ng`) at the revision whose English data and
output match the extension's phonemizer package byte for byte.

```sh
git clone --recurse-submodules https://github.com/Orexii/ai-markdown-reader-android.git
npm ci                                   # Node.js 22
npm run build:web                        # app/src/main/assets/reader and samples, from the extension
npm run goldens                          # core/src/test/resources/goldens, from the extension's TypeScript
tools/espeak-data.sh                     # English eSpeak data (committed) and the host JNI library for tests
node tools/fetch-model.mjs .model compact   # pinned test model (92 MB)
./gradlew :core:test                     # unit, parity, player and real-model tests on the JVM
./gradlew :app:assembleDebug             # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:connectedDebugAndroidTest # on a device or emulator, with the model pushed (see below)
```

Requires JDK 17 or later and the Android SDK (platform 37, NDK 28.2). The parity tests compare the Kotlin port
with goldens produced by the extension's own TypeScript; when the extension submodule changes, `npm run goldens`
shows what the port must follow. The device tests read the model from `/data/local/tmp/amr-model` (same layout as
`.model`): `adb push .model/onnx/model_quantized.onnx /data/local/tmp/amr-model/onnx/` and
`adb push .model/voices/af_heart.bin /data/local/tmp/amr-model/voices/`. `ReaderE2ETest` opens the Wagyu field
guide with real taps, plays at 3× and checks that the page highlights the spoken word with at most one stall.

CI checks the generated files, runs the JVM tests with the real model, builds and lints the app, then runs the
device tests on an x86_64 emulator.

Building on Arm64 Linux (for example WSL on a Snapdragon laptop) works with `qemu-user-static` and amd64 libc
(`dpkg --add-architecture amd64`) for Google's x86_64 build tools; set `cmake.dir=/usr` in `local.properties` to
use the system CMake. Android emulators do not run on Arm64 Linux or Windows on Arm; use a phone with wireless
debugging, or CI.

## License

The source code is [MIT](LICENSE). The app includes eSpeak NG, which is GPL-3.0-or-later, so the app as a whole
is distributed under GPL-3.0-or-later. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
