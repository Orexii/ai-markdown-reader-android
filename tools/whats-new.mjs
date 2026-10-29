// Prints a version's CHANGELOG entry as Google Play "What's new" text (plain, at most 500 characters).
//   node tools/whats-new.mjs 0.1.0
import { readFileSync } from 'node:fs';
import path from 'node:path';

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const version = process.argv[2];
const changelog = readFileSync(path.join(root, 'CHANGELOG.md'), 'utf8');
const entry = changelog.split(/^## /m).find(section => section.startsWith(`${version}\n`));
if (!entry) {
  console.error(`CHANGELOG.md has no entry for ${version}.`);
  process.exit(1);
}
const text = entry.slice(version.length).trim()
  .replace(/\*\*(.+?)\*\*/g, '$1')
  .replace(/`([^`]+)`/g, '$1')
  .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
  .replace(/\n {2,}/g, ' ')
  .replace(/^- /gm, '• ');
process.stdout.write(text.length <= 500 ? text : `${text.slice(0, 497).replace(/\s+\S*$/, '')}…`);
