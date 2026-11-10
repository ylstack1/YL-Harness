import { test } from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';

const sandbox = vm.createContext({ console, setTimeout, clearTimeout, URL, TextDecoder, TextEncoder, performance });
vm.runInContext(readFileSync(new URL('../../app/src/main/assets/editor-tools/engine.js', import.meta.url), 'utf8'), sandbox);
const request = (op, path, files, extra = {}) => sandbox.handleEditorRequest({ op, path, files, reset: true, ...extra });

test('semantic definitions and references follow imports and exclude comments, strings and shadowed names', async () => {
  const files = { 'lib.ts': 'export function greet(name: string) { return name.toUpperCase(); }',
    'main.ts': 'import { greet } from "./lib";\ngreet("hi");\n// greet\nconst text = "greet";\nfunction other(greet: number) { return greet; }' };
  const at = files['main.ts'].indexOf('greet("');
  const definitions = await request('definition', 'main.ts', files, { offset: at });
  assert.equal(definitions[0].path, 'lib.ts');
  assert.equal(definitions[0].line, 1);
  const references = await request('references', 'main.ts', files, { offset: at });
  assert.equal(references.length, 3);
  const edits = await request('rename', 'main.ts', files, { offset: at, newName: 'welcome' });
  assert.equal(edits.length, 2);
  assert.ok(edits.every(e => e.path === 'main.ts'));
  assert.ok(edits.some(e => e.text === 'greet as welcome'));
});

test('typed member completion includes standard library methods and correct replacement spans', async () => {
  const text = 'const title: string = "hello";\ntitle.toUp';
  const items = await request('complete', 'main.ts', { 'main.ts': text }, { offset: text.length });
  assert.equal(items.find(i => i.label === 'toUpperCase').start, text.length - 4);
  assert.equal(items.find(i => i.label === 'toUpperCase').end, text.length);
});

test('outline uses syntax trees, preserves nested symbols and ignores fake declarations', async () => {
  const text = '// function fake() {}\nclass Account {\n balance = 0;\n deposit(amount: number) { this.balance += amount; }\n}\n';
  const outline = await request('outline', 'bank.ts', { 'bank.ts': text });
  assert.ok(!outline.some(s => s.name === 'fake'));
  assert.ok(outline.some(s => s.name === 'deposit' && s.line === 4 && s.depth === 1));
});

test('Prettier respects JSON project settings and errors preserve invalid source', async () => {
  const files = { 'src/main.ts': 'const x={name:"hello"}', '.prettierrc': '{"semi":false,"singleQuote":true,"tabWidth":4}' };
  const text = await request('format', 'src/main.ts', files);
  assert.equal(text, "const x = { name: 'hello' }\n");
  await assert.rejects(() => request('format', 'bad.ts', { 'bad.ts': 'function {' }));
  await assert.rejects(() => request('format', 'a.js', { 'a.js': 'const a=1', 'prettier.config.js': 'throw Error("must not execute")' }), /external formatter configuration/);
});

test('HTML CSS JSON and Markdown use structural outlines and web completions', async () => {
  const html = '<main><button id="save">Save</button></main>';
  const outline = await request('outline', 'index.html', { 'index.html': html });
  assert.ok(outline.some(s => s.name.includes('button') && s.depth === 1));
  const css = '.button { col }';
  const items = await request('complete', 'styles.css', { 'styles.css': css }, { offset: css.indexOf('col') + 3 });
  assert.ok(items.some(i => i.label === 'color'));
  const color = items.find(i => i.label === 'color');
  assert.ok(color.cursor <= color.text.length);
  assert.ok(!color.text.includes('$0'));
  const json = await request('outline', 'data.json', { 'data.json': '{"account":{"name":"Sam"}}' });
  assert.ok(json.some(s => s.name === 'name' && s.depth === 1));
  const md = await request('outline', 'guide.md', { 'guide.md': '# Title\n\n```js\n# fake\n```\n\n## Setup\n' });
  assert.equal(md.length, 2);
  assert.equal(md[1].name, 'Setup');
});

test('delta updates refresh definitions and deletion cannot leave stale results', async () => {
  const text = 'import { item } from "./item";\nitem;';
  await request('definition', 'main.ts', { 'main.ts': text, 'item.ts': 'export const item = 1;' }, { offset: text.lastIndexOf('item') });
  const results = await sandbox.handleEditorRequest({ op: 'definition', path: 'main.ts', files: {}, removed: ['item.ts'], offset: text.lastIndexOf('item') });
  assert.ok(!results.some(r => r.path === 'item.ts'));
});

test('rename preserves shorthand object properties through compiler prefix and suffix edits', async () => {
  const text = 'const value = 1;\nconst obj = { value };';
  const edits = await request('rename', 'main.ts', { 'main.ts': text }, { offset: 7, newName: 'amount' });
  assert.ok(edits.some(e => e.text === 'value: amount'));
  await assert.rejects(() => request('rename', 'main.ts', { 'main.ts': text }, { offset: 7, newName: 'class' }), /valid symbol name/);
});
