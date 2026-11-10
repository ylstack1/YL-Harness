import { build } from 'esbuild';
import { readFile, readdir, mkdir, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const libDir = path.dirname(require.resolve('typescript'));
const libraries = {};
for (const name of await readdir(libDir)) {
  if (/^lib\..*\.d\.ts$/.test(name)) libraries[`/lib/${name}`] = await readFile(path.join(libDir, name), 'utf8');
}
const out = path.resolve(here, '../../app/src/main/assets/editor-tools');
await mkdir(out, { recursive: true });
await build({
  absWorkingDir: here, entryPoints: ['engine.js'], bundle: true, minify: true,
  outfile: path.join(out, 'engine.js'), platform: 'browser', format: 'iife', target: 'chrome80',
  define: { HARNESS_STANDARD_LIBRARIES: JSON.stringify(libraries) },
  external: ['fs', 'path', 'os', 'crypto', 'inspector', 'perf_hooks', 'buffer', 'source-map-support'],
  legalComments: 'none',
});
const licenses = [];
const packages = ['typescript', 'prettier', 'vscode-html-languageservice', 'vscode-css-languageservice',
  'vscode-json-languageservice', 'vscode-languageserver-textdocument', 'vscode-languageserver-types',
  'vscode-uri', 'jsonc-parser', '@vscode/l10n'];
for (const name of packages) {
  const root = path.join(here, 'node_modules', name);
  const metadata = JSON.parse(await readFile(path.join(root, 'package.json'), 'utf8'));
  const licenseName = (await readdir(root)).find(n => /^licen[cs]e(?:\.txt|\.md)?$/i.test(n));
  licenses.push(`${name} ${metadata.version}\n${licenseName ? await readFile(path.join(root, licenseName), 'utf8') : metadata.license}`);
  for (const notice of (await readdir(root)).filter(n => /^(?:third.party.notices|copyrightnotice)(?:\.md|\.txt)?$/i.test(n))) {
    licenses.push(`${name} third-party notices\n${await readFile(path.join(root, notice), 'utf8')}`);
  }
}
await writeFile(path.join(out, 'LICENSES.txt'), licenses.join('\n\n----------------------------------------\n\n'));
