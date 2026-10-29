// Writes the parity goldens (core/src/test/resources/goldens) from the extension's TypeScript.
//   node tools/goldens.mjs           regenerate
//   node tools/goldens.mjs --check   fail if the committed goldens are out of date
import { build } from 'esbuild';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const target = path.join(root, 'core', 'src', 'test', 'resources', 'goldens');
const temp = await mkdtemp(path.join(os.tmpdir(), 'amr-goldens-'));
try {
  const bundle = path.join(temp, 'goldens.mjs');
  await build({
    entryPoints: [path.join(root, 'tools', 'goldens-entry.ts')],
    outfile: bundle,
    bundle: true,
    platform: 'node',
    format: 'esm',
    target: 'node22',
    external: ['phonemizer'],
    logLevel: 'warning',
    nodePaths: [path.join(root, 'node_modules')]
  });
  // The bundle imports phonemizer; resolve it from this repository's node_modules.
  await writeFile(path.join(temp, 'package.json'), '{"type":"module"}');
  await rm(path.join(temp, 'node_modules'), { force: true, recursive: true });
  await (await import('node:fs/promises')).symlink(path.join(root, 'node_modules'), path.join(temp, 'node_modules'), 'dir');
  const { generate } = await import(pathToFileURL(bundle).href);
  const goldens = await generate(root);
  const stale = [];
  if (!process.argv.includes('--check')) await mkdir(target, { recursive: true });
  for (const [file, value] of Object.entries(goldens)) {
    const text = `${JSON.stringify(value)}\n`;
    if (process.argv.includes('--check')) {
      const current = await readFile(path.join(target, file), 'utf8').catch(() => '');
      if (current !== text) stale.push(file);
    } else {
      await writeFile(path.join(target, file), text);
      console.log(`${file}: ${(text.length / 1024).toFixed(0)} KB`);
    }
  }
  if (stale.length) {
    console.error(`Goldens are out of date (${stale.join(', ')}); run npm run goldens and update the Kotlin port to match.`);
    process.exitCode = 1;
  } else if (process.argv.includes('--check')) {
    console.log('Goldens are up to date.');
  }
} finally {
  await rm(temp, { recursive: true, force: true });
}
