// Checks the device test results: AGP reports a crashed test process as a passing task, so CI reads the
// results itself. Fails on any failure or error, or when fewer tests ran than expected.
//   node tools/check-device-results.mjs <minimum tests run>
import { readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const minimum = Number(process.argv[2] ?? 1);
const folder = path.join(root, 'app', 'build', 'outputs', 'androidTest-results', 'connected');

function files(directory) {
  try {
    return readdirSync(directory).flatMap(name => {
      const full = path.join(directory, name);
      return statSync(full).isDirectory() ? files(full) : /^TEST-.*\.xml$/.test(name) ? [full] : [];
    });
  } catch {
    return [];
  }
}

let ran = 0;
const problems = [];
for (const file of files(folder)) {
  const xml = readFileSync(file, 'utf8');
  for (const match of xml.matchAll(/<testcase\b([^>]*?)(\/>|>([\s\S]*?)<\/testcase>)/g)) {
    const name = /\bname="([^"]*)"/.exec(match[1])?.[1] ?? '?';
    const owner = /\bclassname="([^"]*)"/.exec(match[1])?.[1]?.split('.').at(-1) ?? '?';
    const body = match[3] ?? '';
    if (/<skipped\b/.test(body)) {
      console.log(`skipped  ${owner}.${name}`);
      continue;
    }
    ran++;
    const failure = /<(failure|error)\b[^>]*?(?:message="([^"]*)")?[^>]*>([\s\S]*?)<\/\1>|<(failure|error)\b[^>]*\/>/.exec(body);
    if (failure) {
      const detail = (failure[2] || failure[3] || '').split('\n')[0].trim();
      problems.push(`${owner}.${name}: ${detail}`);
      console.log(`FAILED   ${owner}.${name}`);
    } else {
      console.log(`passed   ${owner}.${name}`);
    }
  }
}
if (problems.length) {
  console.error(`\n${problems.length} device test problem(s):\n  ${problems.join('\n  ')}`);
  process.exit(1);
}
if (ran < minimum) {
  console.error(`\nOnly ${ran} device test(s) ran; expected at least ${minimum}.`);
  process.exit(1);
}
console.log(`\n${ran} device tests passed.`);
