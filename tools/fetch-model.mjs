// Downloads the pinned, verified Kokoro model and default voice for the real-model tests, using the
// extension's own model.ts (same revision, sizes and SHA-256): node tools/fetch-model.mjs [dir] [best|compact]
// Layout: <dir>/onnx/<model file> and <dir>/voices/af_heart.bin, as in the extension.
import { build } from 'esbuild';
import { existsSync, statSync } from 'node:fs';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const [directory = path.join(root, '.model'), quality = 'compact'] = process.argv.slice(2);
const temp = await mkdtemp(path.join(os.tmpdir(), 'amr-model-'));
try {
  const bundle = path.join(temp, 'model.mjs');
  await build({ entryPoints: [path.join(root, 'extension', 'src', 'model.ts')], outfile: bundle, bundle: true, platform: 'node', format: 'esm', logLevel: 'warning' });
  const { MODELS, MODEL_REPOSITORY, MODEL_REVISION, VOICES, downloadVerified } = await import(pathToFileURL(bundle).href);
  const url = file => `https://huggingface.co/${MODEL_REPOSITORY}/resolve/${MODEL_REVISION}/${file}`;
  const voice = VOICES.find(v => v.id === 'af_heart');
  const files = [[MODELS[quality].file, MODELS[quality]], ['voices/af_heart.bin', { size: 522240, sha256: voice.sha256 }]];
  for (const [file, expected] of files) {
    const target = path.join(directory, file);
    if (existsSync(target) && statSync(target).size === expected.size) continue;
    let last = -1;
    await downloadVerified(url(file), target, expected, received => {
      const percent = Math.floor((received / expected.size) * 10) * 10;
      if (percent !== last) { last = percent; process.stdout.write(`${file}: ${percent}%\n`); }
    }, new AbortController().signal);
  }
  console.log(`Model files ready in ${directory}`);
} finally {
  await rm(temp, { recursive: true, force: true });
}
