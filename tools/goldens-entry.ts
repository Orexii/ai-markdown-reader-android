// Produces the parity goldens for the Kotlin port by running the extension's own TypeScript. Bundled and
// run by tools/goldens.mjs. eSpeak is the real phonemizer package; ONNX Runtime is a deterministic fake,
// so token ids, style rows, word times and the split audio can be compared exactly.
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { phonemize } from 'phonemizer';
import { buildDocument, type ReaderDocument } from '../extension/src/document';
import { KokoroModel, type SynthesisRequest } from '../extension/src/engine/kokoro';
import { DEFAULT_VOICE, MODELS, MODEL_FOLDER, MODEL_REPOSITORY, MODEL_REVISION, VOICES } from '../extension/src/model';
import { Lexicon, type LexiconData } from '../extension/src/engine/lexicon';
import { dictionaryFrom, pronounce, pronounceAsText, type Accent } from '../extension/src/engine/pronounce';

/** Words whose lookups reach Object.prototype in the TypeScript tables; the Kotlin port deliberately differs. */
const PROTOTYPE_WORDS = new Set(Object.getOwnPropertyNames(Object.prototype));

const EXTRA_WORDS = [
  '8kg', '8–11', 'kg', '100%', '$12.50', '£3.01', '€1', '$1', '3:05', '12:00', '1999', '2000s', '1066', '2010', 'v2.4.1', 'V3',
  '~5', '3x', '1,234,567', '1,234.5', '10-20', '0.5', '42', 'IcM', 'ANFObservabilityDB', 'DataHarborConfig', 'IoT', 'JSON', 'JSONL',
  'HTTPS', 'URLs', 'APIs', "VM's", 'VMs', 'EC2', 'S3', 'M365', 'Ctrl+Shift+→', 'Shift+←', 'Cmd+K', 'Ctrl+Alt+P', 'e.g.', 'i.e.',
  'etc.', 'vs.', 'w/', 'w/o', '&', '+', '=', '->', '=>', '<-', '×', '≈', '±', '/', 'C#', 'C++', '.NET', 'OAuth', 'iOS', 'macOS',
  'PostgreSQL', 'kubectl', 'nginx', 'README', 'stdout', 'npm', 'snake_case', 'kebab-case', 'path/to/file', 'user@example.com',
  'https://example.com/a?b=c', 'www.github.com', 'foo.bar.baz', '_private', '.gitignore', 'a/b', 'NASA', 'OK', 'TODO', 'PhD',
  'iPhone', 'McDonald', 'XMLHttpRequest', 'camelCase', 'PascalCase', 'GiB', 'Mbps', 'ms', '°C', '5G', '80s', 'Kusto', 'Rakeem',
  'Wagyu', 'zoomies', "isn't", "dogs'", "Wagyu's", 'naïve', 'café', 'A', 'a', 'I', 'to', 'the', 'an', 'in', 'used'
];

const EXTRA_LEXICON = [
  'the', 'The', 'a', 'A', 'an', 'to', 'To', 'in', 'In', 'I', 'used', 'Used', 'read', 'reads', 'reading', 'readed', 'lives', 'live',
  'record', 'records', 'recorded', 'recording', 'cats', "cat's", "cats'", 'tables', 'tabled', 'tabling', 'stopped', 'stopping',
  'running', 'runs', 'kissed', 'kisses', 'boxes', 'flies', 'tried', 'hated', 'needed', 'wanted', 'waited', 'loaded', 'NASA',
  'USA', 'Paris', 'PARIS', 'paris', 'McDonald', 'Wagyu', 'shiba', 'Shiba', 'SHIBA', 'zoomies', 'Zoomies', 'isn’t', 'don’t',
  'xyzzy', 'Xyzzy', 'XYZZY', 'abc', 'ABC', 'Abc', 'hello', 'Hello', 'HELLO', 'markdown', 'Markdown', 'MARKDOWN'
];

function seeded(seed: number) {
  let state = seed >>> 0;
  return () => {
    state = (state * 1664525 + 1013904223) >>> 0;
    return state / 2 ** 32;
  };
}

/** FNV-1a over the float32 bytes, so the Kotlin port must produce bit-identical audio. */
function checksum(samples: Float32Array): string {
  const bytes = new Uint8Array(samples.buffer, samples.byteOffset, samples.byteLength);
  let hash = 0x811c9dc5;
  for (const byte of bytes) hash = Math.imul(hash ^ byte, 0x01000193) >>> 0;
  return hash.toString(16).padStart(8, '0');
}

/** The fake model: durations and waveform follow from the ids alone (see FakeInference in the Kotlin tests). */
function fakeRun(ids: number[]) {
  const durations = Float32Array.from(ids, (id, i) => ((id * 7 + i) % 5) + 1);
  const frames = durations.reduce((sum, value) => sum + value, 0);
  const length = Math.floor(frames * 600 * 0.98);
  const waveform = new Float32Array(length);
  for (let j = 0; j < length; j++) waveform[j] = (((j * 7919) % 2001) - 1000) / 1666;
  return { durations, waveform };
}

export async function generate(root: string): Promise<Record<string, unknown>> {
  const read = (file: string) => readFileSync(path.join(root, file), 'utf8');
  const wagyu = read('extension/examples/wagyu.md');
  const demo = read('extension/examples/demo.md');
  const documents: Record<string, ReaderDocument> = {
    'wagyu-rows-announce': buildDocument(wagyu),
    'wagyu-columns-read': buildDocument(wagyu, { tableMode: 'columns', codeBlocks: 'read' }),
    'wagyu-skip': buildDocument(wagyu, { codeBlocks: 'skip' }),
    demo: buildDocument(demo),
    'selection': buildDocument(wagyu.split('\n').slice(10, 30).join('\n'), { lineOffset: 10, characterOffset: 0 }),
    // The documents of the extension's scheduler tests.
    'scheduler-short': buildDocument([
      '# Short',
      'A first paragraph that is long enough to stand on its own when it is spoken aloud by the voice.',
      'Tiny.',
      'Another reasonably long paragraph that follows the tiny one and keeps the reader company for a while.',
      'Yet another paragraph, so that prefetching has something more to do after the first requests are done.'
    ].join('\n\n')),
    'scheduler-long': buildDocument(Array.from({ length: 12 }, (_, i) => `Sentence number ${i + 1} is a reasonably long sentence for testing batches.`).join(' '))
  };

  // pronounce: every word of the sample documents with its previous word, and the extra tokens.
  const dictionary = dictionaryFrom({ Kusto: 'kuss toe', Rakeem: '/ɹɑːkˈiːm/', Wagyu: 'wah gyoo' });
  const pronounceCases: unknown[] = [];
  const seen = new Set<string>();
  const addPronounce = (word: string, previous: string | undefined, accent: Accent, withDictionary: boolean) => {
    if (PROTOTYPE_WORDS.has(word.trim()) || (previous && PROTOTYPE_WORDS.has(previous.trim()))) return;
    const key = JSON.stringify([word, previous ?? null, accent, withDictionary]);
    if (seen.has(key)) return;
    seen.add(key);
    const dict = withDictionary ? dictionary : new Map<string, string>();
    pronounceCases.push({
      word, previous: previous ?? null, accent, dictionary: withDictionary,
      segments: pronounce(word, accent, dict, false, previous),
      text: pronounceAsText(word, dict, previous)
    });
  };
  for (const name of ['wagyu-rows-announce', 'wagyu-columns-read', 'demo']) {
    for (const utterance of documents[name]!.utterances) {
      utterance.words.forEach((word, k) => {
        for (const accent of ['us', 'gb'] as const) addPronounce(word.text, utterance.words[k - 1]?.text, accent, false);
      });
    }
  }
  EXTRA_WORDS.forEach((word, k) => {
    for (const accent of ['us', 'gb'] as const) {
      addPronounce(word, undefined, accent, false);
      addPronounce(word, undefined, accent, true);
      addPronounce(word, EXTRA_WORDS[k - 1], accent, false);
    }
  });
  for (const unit of ['kg', 'GB', 'ms', 'min', 'h', 's', 'm', '°C', 'mph']) {
    for (const previous of ['1', '8', '8–11', '(12)', '1,000', 'x']) addPronounce(unit, previous, 'us', false);
  }

  // Lexicon: a seeded sample of entries and inflections, in every context.
  const lexiconCases: unknown[] = [];
  const lexiconSample: string[] = [];
  const random = seeded(20261029);
  for (const accent of ['us', 'gb'] as const) {
    const data = JSON.parse(read(`extension/lexicon/${accent}.json`)) as LexiconData;
    const lexicon = new Lexicon(data, accent === 'gb');
    const keys = [...Object.keys(data.gold), ...Object.keys(data.silver)];
    const words = new Set(EXTRA_LEXICON);
    for (let i = 0; i < 1000; i++) {
      const key = keys[Math.floor(random() * keys.length)]!;
      words.add(key);
      if (accent === 'us' && i < 600) lexiconSample.push(key);
      if (i % 5 === 0) words.add(key.toUpperCase());
      if (i % 7 === 0) words.add(key.charAt(0).toUpperCase() + key.slice(1));
      if (i % 3 === 0) for (const suffix of ['s', 'es', 'ed', 'd', 'ing', "'s"]) words.add(key + suffix);
    }
    for (const name of ['wagyu-rows-announce', 'demo']) {
      for (const utterance of documents[name]!.utterances) for (const word of utterance.words) words.add(word.text.replace(/[^\p{L}']/gu, ''));
    }
    for (const word of words) {
      if (!word || PROTOTYPE_WORDS.has(word)) continue;
      for (const nextVowel of [true, false, null]) {
        for (const nextIsTo of nextVowel === false ? [false, true] : [false]) {
          // [word, accent, nextVowel, nextIsTo, phonemes or null], compact because there are many.
          lexiconCases.push([word, accent, nextVowel, nextIsTo, lexicon.word(word, { nextVowel, nextIsTo }) ?? null]);
        }
      }
    }
  }

  // Kokoro: whole requests through the frontend, a fake model and split().
  const voicesDir = mkdtempSync(path.join(os.tmpdir(), 'amr-goldens-'));
  const espeak: Record<string, string[]> = {};
  try {
    for (const voice of ['af_heart', 'bf_emma']) {
      const style = new Float32Array(510 * 256);
      for (let row = 0; row < 510; row++) for (let c = 0; c < 256; c++) style[row * 256 + c] = row + c / 1000;
      writeFileSync(path.join(voicesDir, `${voice}.bin`), new Uint8Array(style.buffer));
    }
    let runs: { ids: number[]; styleRow: number; speed: number }[] = [];
    const ort = {
      Tensor: class { constructor(readonly type: string, readonly data: BigInt64Array | Float32Array, readonly dims: number[]) {} },
      InferenceSession: {
        create: async () => ({
          run: async (feeds: Record<string, { data: BigInt64Array | Float32Array }>) => {
            const ids = [...(feeds.input_ids!.data as BigInt64Array)].map(Number);
            runs.push({ ids, styleRow: Math.round((feeds.style!.data as Float32Array)[0]!), speed: (feeds.speed!.data as Float32Array)[0]! });
            const { durations, waveform } = fakeRun(ids);
            return { waveform: { data: waveform }, durations: { data: durations } };
          }
        })
      }
    };
    const recordingPhonemize = async (text: string, language: string) => {
      const result = await phonemize(text, language);
      espeak[`${language}\u0000${text}`] = result;
      return result;
    };
    const model = await KokoroModel.load({
      ort: ort as never, modelPath: 'fake', voicesDir, threads: 1, phonemize: recordingPhonemize, lexiconDir: path.join(root, 'extension', 'lexicon')
    });
    const kokoroCases: unknown[] = [];
    const run = async (request: SynthesisRequest) => {
      runs = [];
      const segments = await model.synthesize(request);
      kokoroCases.push({
        request,
        runs,
        segments: segments.map(segment => ({ samples: segment.samples.length, checksum: checksum(segment.samples), wordTimes: segment.wordTimes, duration: segment.duration }))
      });
    };
    const inputs = (doc: ReaderDocument) => doc.utterances.map(u => ({ words: u.words.map(w => ({ text: w.text, sep: w.sep })), pause: u.pauseAfter }));
    for (const [name, voice, limit] of [['wagyu-rows-announce', 'af_heart', 999], ['wagyu-columns-read', 'af_heart', 999], ['demo', 'af_heart', 999], ['wagyu-rows-announce', 'bf_emma', 60]] as const) {
      const utterances = inputs(documents[name]!).filter(u => !u.words.some(w => PROTOTYPE_WORDS.has(w.text))).slice(0, limit);
      for (const utterance of utterances) await run({ voice, utterances: [utterance] });
      // Batches as the scheduler makes them when it is not in a hurry: up to 170 spoken characters.
      for (let i = 0; i < utterances.length;) {
        const batch = [utterances[i]!];
        let chars = utterances[i]!.words.reduce((sum, w) => sum + w.text.length + w.sep.length, 0);
        for (i++; i < utterances.length; i++) {
          const size = utterances[i]!.words.reduce((sum, w) => sum + w.text.length + w.sep.length, 0);
          if (chars + size > 170) break;
          batch.push(utterances[i]!);
          chars += size;
        }
        if (batch.length > 1) await run({ voice, utterances: batch });
      }
    }
    // Too long for one request: split by passages, then by words.
    const long = inputs(documents['wagyu-rows-announce']!).slice(0, 12);
    await run({ voice: 'af_heart', utterances: [...long, ...long] });
    const salad = Array.from({ length: 160 }, (_, k) => ({ text: ['alpha', 'Wagyu', 'IcM', 'v2.4.1', 'zoomies', '8kg'][k % 6]!, sep: k % 9 === 8 ? ', ' : ' ' }));
    salad.at(-1)!.sep = '.';
    await run({ voice: 'af_heart', utterances: [{ words: salad, pause: 0.6 }] });
    await run({ voice: 'af_heart', utterances: [{ words: [{ text: 'Kusto', sep: ' ' }, { text: 'Rakeem', sep: ' ' }, { text: 'Wagyu', sep: '.' }], pause: 0.3 }], dictionary: [...dictionary] });
    // eSpeak on its own: words, names, numbers and whole lines, in both accents.
    const espeakInputs = [...new Set([...EXTRA_WORDS, ...EXTRA_LEXICON, ...lexiconSample, ...wagyu.split('\n').map(line => line.replace(/[*_`#>|\[\]()<!-]/g, ' ').trim()).filter(line => line.length > 3).slice(0, 120)])];
    const espeakCases: unknown[] = [];
    for (const language of ['en-us', 'en']) {
      for (const text of espeakInputs) espeakCases.push([language, text, await phonemize(text, language)]);
    }
    return {
      'model.json': { repository: MODEL_REPOSITORY, revision: MODEL_REVISION, folder: MODEL_FOLDER, defaultVoice: DEFAULT_VOICE, models: MODELS, voices: VOICES },
      'documents.json': documents,
      'espeak.json': espeakCases,
      'pronounce.json': { dictionary: Object.fromEntries(dictionary), cases: pronounceCases },
      'lexicon.json': lexiconCases,
      'kokoro.json': { espeak: Object.entries(espeak).map(([key, clauses]) => ({ language: key.split('\u0000')[0], text: key.split('\u0000')[1], clauses })), cases: kokoroCases }
    };
  } finally {
    rmSync(voicesDir, { recursive: true, force: true });
  }
}
