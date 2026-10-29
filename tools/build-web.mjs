// Builds the reader page into app/src/main/assets: the glue bundle (web/reader.ts with the extension's
// document builder and preview renderer), the extension's unmodified preview.js and preview.css, and the
// Wagyu sample documents. The output is committed so Android builds do not need Node.
//   node tools/build-web.mjs           build
//   node tools/build-web.mjs --check   fail if the committed output is out of date
import { build } from 'esbuild';
import { cp, mkdir, mkdtemp, readFile, readdir, rm, stat } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const assets = path.join(root, 'app', 'src', 'main', 'assets');
const check = process.argv.includes('--check');

async function generate(target) {
  const reader = path.join(target, 'reader');
  const samples = path.join(target, 'samples');
  await mkdir(reader, { recursive: true });
  await mkdir(path.join(samples, 'images'), { recursive: true });
  await build({
    entryPoints: [path.join(root, 'web', 'reader.ts')],
    outfile: path.join(reader, 'reader.js'),
    bundle: true,
    format: 'iife',
    // Intl.Segmenter and Array.prototype.at need Android System WebView 92 or later.
    target: ['chrome92'],
    minify: true,
    legalComments: 'eof',
    logLevel: 'warning'
  });
  await cp(path.join(root, 'extension', 'media', 'preview.js'), path.join(reader, 'preview.js'));
  await cp(path.join(root, 'extension', 'media', 'preview.css'), path.join(reader, 'preview.css'));
  await cp(path.join(root, 'web', 'reader.html'), path.join(reader, 'reader.html'));
  await cp(path.join(root, 'web', 'android.css'), path.join(reader, 'android.css'));
  for (const file of ['wagyu.md', 'demo.md']) await cp(path.join(root, 'extension', 'examples', file), path.join(samples, file));
  await cp(path.join(root, 'extension', 'examples', 'images', 'wagyu.png'), path.join(samples, 'images', 'wagyu.png'));
}

async function files(directory, base = directory) {
  const out = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const full = path.join(directory, entry.name);
    if (entry.isDirectory()) out.push(...await files(full, base));
    else out.push(path.relative(base, full));
  }
  return out.sort();
}

if (check) {
  const temp = await mkdtemp(path.join(os.tmpdir(), 'amr-web-'));
  try {
    await generate(temp);
    const stale = [];
    for (const dir of ['reader', 'samples']) {
      const expected = await files(path.join(temp, dir));
      const actual = await stat(path.join(assets, dir)).then(() => files(path.join(assets, dir)), () => []);
      if (expected.join('\n') !== actual.join('\n')) stale.push(`${dir}/ (file list differs)`);
      for (const file of expected) {
        const a = await readFile(path.join(temp, dir, file));
        const b = await readFile(path.join(assets, dir, file)).catch(() => null);
        if (!b || !a.equals(b)) stale.push(`${dir}/${file}`);
      }
    }
    if (stale.length) {
      console.error(`The reader page is out of date; run npm run build:web.\n  ${stale.join('\n  ')}`);
      process.exit(1);
    }
    console.log('The reader page is up to date.');
  } finally {
    await rm(temp, { recursive: true, force: true });
  }
} else {
  await rm(path.join(assets, 'reader'), { recursive: true, force: true });
  await rm(path.join(assets, 'samples'), { recursive: true, force: true });
  await generate(assets);
  console.log(`Built the reader page in ${path.relative(root, assets)}`);
}
