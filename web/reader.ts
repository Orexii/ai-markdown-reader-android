// The reader page's glue: renders Markdown with the extension's preview renderer, builds the narration
// with its document builder, and connects the extension's unmodified preview.js to the app.
import { buildDocument, stripFrontMatter, type ReaderDocument, type SourceRange } from '../extension/src/document';
import { renderPreview } from '../extension/src/preview';

interface Bridge {
  /** Messages from preview.js (readFrom, openLink, command, state, ready, contextTarget). */
  post(message: string): void;
  /** The narration built for the document or a selection. */
  narration(json: string, selection: boolean): void;
}

interface Options {
  tableMode: 'rows' | 'columns';
  codeBlocks: 'announce' | 'read' | 'skip';
  /** Whether images next to the document can be shown (the app has access to its folder). */
  localImages: boolean;
}

declare global {
  interface Window {
    AndroidBridge?: Bridge;
    AMR: typeof api;
    acquireVsCodeApi(): { postMessage(message: unknown): void; getState(): unknown; setState(state: unknown): void };
  }
}

const bridge = window.AndroidBridge;
const send = (message: unknown) => bridge?.post(JSON.stringify(message));
const deliver = (message: object) => window.postMessage(message, '*');

// preview.js talks to its host as it does in VS Code.
window.acquireVsCodeApi = () => ({ postMessage: send, getState: () => undefined, setState: () => undefined });

let markdown = '';
let options: Options = { tableMode: 'rows', codeBlocks: 'announce', localImages: false };
let doc: ReaderDocument | undefined;

/** Images: web and data URLs as they are; paths next to the document through the app's document handler. */
function resolveImage(src: string): string | undefined {
  if (/^(https?:|data:)/i.test(src)) return src;
  if (!src || /^[a-z][a-z0-9+.-]*:/i.test(src) || !options.localImages) return undefined;
  const path = src.split(/[?#]/)[0]!.replace(/^\.\//, '');
  return `https://appassets.androidplatform.net/document/${path.split('/').map(part => encodeURIComponent(decodeURIComponent(part))).join('/')}`;
}

function build(): void {
  doc = buildDocument(markdown, { tableMode: options.tableMode, codeBlocks: options.codeBlocks });
  bridge?.narration(JSON.stringify(doc), false);
}

/** Source position of a rendered node: the word it is in, or the start of its block. */
function sourceAt(node: Node | null, end: boolean): { line: number; character: number } | null {
  const element = node instanceof Element ? node : node?.parentElement;
  const word = element?.closest<HTMLElement>('.w[data-p]');
  if (word) {
    const [line, character] = word.dataset.p!.split(',').map(Number) as [number, number];
    return { line, character: end ? character + (word.textContent ?? '').length : character };
  }
  const block = element?.closest<HTMLElement>('[data-line]');
  return block ? { line: Number(block.dataset.line), character: 0 } : null;
}

/** The source text between two positions of the front-matter-stripped document. */
function slice(range: SourceRange): string {
  const lines = stripFrontMatter(markdown).split(/\r?\n/);
  const picked = lines.slice(range.line, range.endLine + 1);
  if (!picked.length) return '';
  picked[picked.length - 1] = picked[picked.length - 1]!.slice(0, range.endCharacter);
  picked[0] = picked[0]!.slice(range.character);
  return picked.join('\n');
}

const api = {
  /** Shows a document and builds its narration. */
  open(text: string, settings: Options): void {
    markdown = text;
    options = settings;
    deliver({ type: 'render', html: renderPreview(text, { resolveImage }) });
    build();
  },

  /** Rebuilds the narration after a setting change (tables, code blocks). */
  rebuild(settings: Options): void {
    const imagesChanged = settings.localImages !== options.localImages;
    options = settings;
    if (imagesChanged) deliver({ type: 'render', html: renderPreview(markdown, { resolveImage }) });
    build();
  },

  /** Builds the narration of the selected text only; returns false when nothing readable is selected. */
  readSelection(): boolean {
    const selection = window.getSelection();
    if (!selection || selection.isCollapsed || !selection.rangeCount) return false;
    const range = selection.getRangeAt(0);
    const start = sourceAt(range.startContainer, false);
    const end = sourceAt(range.endContainer, true);
    if (!start || !end) return false;
    const source = { line: start.line, character: start.character, endLine: end.line, endCharacter: end.character };
    const selected = buildDocument(slice(source), {
      tableMode: options.tableMode, codeBlocks: options.codeBlocks, lineOffset: source.line, characterOffset: source.character
    });
    if (!selected.utterances.length) return false;
    selection.removeAllRanges();
    doc = selected;
    bridge?.narration(JSON.stringify(selected), true);
    return true;
  },

  /** The source position of the start of the current selection, for "Read aloud from here". */
  selectionStart(): string | null {
    const selection = window.getSelection();
    if (!selection || !selection.rangeCount) return null;
    const at = sourceAt(selection.getRangeAt(0).startContainer, false);
    selection.removeAllRanges();
    return at ? JSON.stringify(at) : null;
  },

  /** Highlights passage `index` and word `word` (-1 for none) of the current narration. */
  position(index: number, word: number): void {
    const utterance = doc?.utterances[index];
    if (!utterance) return;
    deliver({ type: 'position', position: { sentence: utterance.range, word: word >= 0 ? utterance.words[word]?.range ?? null : null } });
  },

  clear(): void {
    deliver({ type: 'clear' });
  },

  settings(follow: boolean, words: boolean): void {
    deliver({ type: 'settings', settings: { follow, words } });
  },

  reveal(): void {
    deliver({ type: 'reveal' });
  },

  theme(dark: boolean): void {
    document.body.classList.toggle('vscode-dark', dark);
    document.body.classList.toggle('vscode-light', !dark);
  },

  /** Scrolls to the top of the document; used when a new document opens. */
  top(): void {
    window.scrollTo(0, 0);
  }
};

window.AMR = api;
