// Regenerates THIRD_PARTY_NOTICES.md: the components the app bundles, with the license text of every npm
// package the reader page's bundle (web/reader.ts) actually includes. Run after dependency changes.
import { build } from 'esbuild';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const result = await build({ entryPoints: [path.join(root, 'web', 'reader.ts')], bundle: true, format: 'iife', write: false, metafile: true, logLevel: 'silent', outfile: 'unused.js' });
const packages = new Set();
for (const input of Object.keys(result.metafile.inputs)) {
  const match = /node_modules\/((?:@[^/]+\/)?[^/]+)/.exec(input);
  if (match) packages.add(match[1]);
}
const sections = [...packages].sort().map(name => {
  const directory = path.join(root, 'node_modules', name);
  const manifest = JSON.parse(readFileSync(path.join(directory, 'package.json'), 'utf8'));
  const file = ['LICENSE', 'LICENSE.md', 'LICENSE.txt', 'license'].map(candidate => path.join(directory, candidate)).find(existsSync);
  const text = file ? readFileSync(file, 'utf8').trim() : '';
  const repository = typeof manifest.repository === 'string' ? manifest.repository : manifest.repository?.url ?? '';
  return `## ${name} ${manifest.version}\n\nLicense: ${manifest.license}. Source: ${repository.replace(/^git\+/, '')}\n\n${text ? `\`\`\`\n${text}\n\`\`\`` : ''}`;
});
const misaki = readFileSync(path.join(root, 'extension', 'lexicon', 'MISAKI_COMMIT'), 'utf8').trim();
writeFileSync(path.join(root, 'THIRD_PARTY_NOTICES.md'), `# Third-party notices

AI Markdown Reader for Android's own source code is MIT-licensed (see LICENSE). The app (APK and app bundle)
also contains the components below.

**eSpeak NG.** The app includes eSpeak NG, compiled from source (third_party/espeak-ng, revision b864eac5), and
its English data. eSpeak NG is licensed under the GNU General Public License version 3 or later, so the app,
taken as a whole, is distributed under GPL-3.0-or-later (licenses/GPL-3.0.txt). The complete corresponding
source is this repository (https://github.com/Orexii/ai-markdown-reader-android) with its submodules: the VS
Code extension (https://github.com/Orexii/ai-markdown-reader) and eSpeak NG (https://github.com/espeak-ng/espeak-ng).

**Kokoro-82M.** The natural voice model and voice files are not included in the app. They are downloaded on
request from https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX-timestamped (an ONNX conversion of
https://huggingface.co/hexgrad/Kokoro-82M) and are licensed under the Apache License 2.0 (licenses/Apache-2.0.txt).

**misaki.** The app's lexicon/us.json and lexicon/gb.json are the English pronunciation lexicons of misaki
(https://github.com/hexgrad/misaki, commit ${misaki}), Copyright hexgrad, licensed under the
Apache License 2.0. core/.../Lexicon.kt and the toMisaki mapping in core/.../Misaki.kt are ports of misaki's word
lookup and eSpeak fallback rules (through the extension's TypeScript).

**kokoro.js.** The eSpeak phoneme corrections in Misaki.kt and the number and currency rules in Pronounce.kt
follow the approach of kokoro.js (https://github.com/hexgrad/kokoro, Apache-2.0).

**Sonic.** core/src/main/java/.../audio/Sonic.java is Sonic from AndroidX Media (Copyright 2017 The Android
Open Source Project; Copyright 2010 Bill Cox, https://github.com/waywardgeek/sonic), Apache License 2.0.

**ONNX Runtime.** The app includes ONNX Runtime 1.31.1 for Android (MIT, Copyright (c) Microsoft Corporation;
licenses/onnxruntime-LICENSE.txt) with the notices in licenses/onnxruntime-ThirdPartyNotices.txt.

**AndroidX, Jetpack Compose, Media3, Kotlin and kotlinx.** Apache License 2.0 (licenses/Apache-2.0.txt), from
https://android.googlesource.com, https://github.com/androidx/media, https://github.com/JetBrains/kotlin and
https://github.com/Kotlin. Media3 uses Guava (https://github.com/google/guava, Apache License 2.0).

**AI Markdown Reader for VS Code.** The reader page's renderer and narration builder (extension/src/document.ts,
extension/src/preview.ts), extension/media/preview.js and preview.css, and the Wagyu sample documents and art come
from https://github.com/Orexii/ai-markdown-reader (MIT, Copyright (c) 2026 Orexii).

The reader page (app/src/main/assets/reader/reader.js) bundles these npm packages:

${sections.join('\n\n')}
`);
console.log(`THIRD_PARTY_NOTICES.md lists ${packages.size} bundled packages.`);
