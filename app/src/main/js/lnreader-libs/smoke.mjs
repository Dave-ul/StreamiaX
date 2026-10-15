// Smoke test for assets/lnreader/{host,libs}.js against real lnreader plugins.
//
// Runs QuickJS compiled to WASM and, like quickjs-android on the device, never executes QuickJS's
// own pending-job queue, so it exercises the same Promise path. __native is backed by curl.
//
//   npm run smoke                      # default plugin set
//   npm run smoke -- royalroad novelbin # specific plugin ids from the index
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { getQuickJS } from 'quickjs-emscripten';

const INDEX = 'https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json';
const ASSETS = new URL('../../assets/lnreader/', import.meta.url);
const DEFAULT_PLUGINS = ['royalroad', 'novelbin', 'scribblehub', 'wuxiaworld.site'];

const storage = new Map();

function curl(req) {
  const dir = mkdtempSync(join(tmpdir(), 'lnsmoke-'));
  try {
    const args = ['-sS', '-L', '--compressed', '--max-time', '30', '-X', req.method,
      '-D', join(dir, 'h'), '-o', join(dir, 'b'), '-w', '%{http_code} %{url_effective}'];
    for (const [k, v] of Object.entries(req.headers || {})) args.push('-H', `${k}: ${v}`);
    if (!Object.keys(req.headers || {}).some(k => k === 'user-agent')) {
      args.push('-H', 'user-agent: Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36');
    }
    if (req.form) for (const [k, v] of req.form) args.push('-F', `${k}=${v}`);
    if (req.body !== undefined) args.push('--data-raw', req.body);
    else if (!req.form && req.method !== 'GET' && req.method !== 'HEAD') args.push('--data-raw', ''); // like OkHttp: empty body
    args.push(req.url);
    const [code, url] = execFileSync('curl', args, { encoding: 'utf8' }).split(' ');
    const headers = {};
    const blocks = readFileSync(join(dir, 'h'), 'utf8').trim().split(/\r?\n\r?\n/);
    for (const line of blocks[blocks.length - 1].split(/\r?\n/).slice(1)) {
      const i = line.indexOf(':');
      if (i > 0) headers[line.slice(0, i).trim().toLowerCase()] = line.slice(i + 1).trim();
    }
    const bytes = readFileSync(join(dir, 'b'));
    const type = headers['content-type'] || '';
    const textual = !type || /text|json|xml|javascript|html/.test(type);
    return { status: +code, url, headers, ...(textual ? { body: bytes.toString('utf8') } : { body64: bytes.toString('base64') }) };
  } catch (e) {
    return { error: String(e.message || e) };
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

const natives = {
  log: m => console.log('    [js]', m),
  sleep: () => {},
  atob: s => Buffer.from(s, 'base64').toString('latin1'),
  btoa: s => Buffer.from(s, 'latin1').toString('base64'),
  b64ToUtf8: s => Buffer.from(s, 'base64').toString('utf8'),
  utf8ToB64: s => Buffer.from(s, 'utf8').toString('base64'),
  fetch: json => JSON.stringify(curl(JSON.parse(json))),
  storageGet: k => storage.get(k) ?? null,
  storageSet: (k, v) => { storage.set(k, v); },
  storageDelete: k => { storage.delete(k); },
  storageKeys: p => JSON.stringify([...storage.keys()].filter(k => k.startsWith(p)).map(k => k.slice(p.length))),
};

const QuickJS = await getQuickJS();

function newVm() {
  const vm = QuickJS.newContext();
  const nat = vm.newObject();
  for (const [name, fn] of Object.entries(natives)) {
    const h = vm.newFunction(name, (...hs) => {
      const r = fn(...hs.map(x => vm.dump(x)));
      return r === undefined ? vm.undefined : r === null ? vm.null : vm.newString(String(r));
    });
    vm.setProp(nat, name, h);
    h.dispose();
  }
  vm.setProp(vm.global, '__native', nat);
  nat.dispose();
  for (const f of ['host.js', 'libs.js']) evalOrThrow(vm, readFileSync(new URL(f, ASSETS), 'utf8'), f);
  return vm;
}

function evalOrThrow(vm, code, name = '<eval>') {
  const r = vm.evalCode(code, name);
  if (r.error) {
    const e = vm.dump(r.error);
    r.error.dispose();
    throw new Error(`${name}: ${typeof e === 'object' ? `${e.message}\n${e.stack}` : e}`);
  }
  const v = vm.dump(r.value);
  r.value.dispose();
  return v;
}

function call(vm, method, args) {
  const out = JSON.parse(evalOrThrow(vm, `__call(${JSON.stringify(method)}, ${JSON.stringify(JSON.stringify(args))})`));
  if (!out.ok) throw new Error(`${method}: ${out.error}`);
  return out.value;
}

const index = await (await fetch(INDEX)).json();
const ids = process.argv.slice(2).length ? process.argv.slice(2) : DEFAULT_PLUGINS;
let failed = 0;

for (const id of ids) {
  const meta = index.find(p => p.id === id);
  if (!meta) { console.log(`✗ ${id}: not in index`); failed++; continue; }
  console.log(`• ${meta.name} (${id})`);
  const vm = newVm();
  try {
    const code = await (await fetch(meta.url)).text();
    evalOrThrow(vm, `__load(${JSON.stringify(id)}, ${JSON.stringify(code)})`, meta.url);
    const t0 = Date.now();
    const novels = call(vm, 'popularNovels', [1, { showLatestNovels: false }]);
    if (!novels?.length) throw new Error('popularNovels returned nothing');
    console.log(`  popular: ${novels.length} novels, first "${novels[0].name}" (${Date.now() - t0} ms)`);
    const novel = call(vm, 'parseNovel', [novels[0].path]);
    let chapters = novel.chapters || [];
    if (!chapters.length && novel.totalPages) chapters = call(vm, 'parsePage', [novel.path, '1']).chapters || [];
    if (!chapters.length) throw new Error('parseNovel returned no chapters');
    console.log(`  novel: "${novel.name}", ${chapters.length} chapters, status ${novel.status}, totalPages ${novel.totalPages ?? '-'}`);
    const text = call(vm, 'parseChapter', [chapters[0].path]);
    if (!text || text.length < 200) throw new Error(`parseChapter returned ${text?.length ?? 0} chars`);
    console.log(`  chapter: "${chapters[0].name}", ${text.length} chars`);
    const found = call(vm, 'searchNovels', [novels[0].name.split(/\s+/).slice(0, 2).join(' '), 1]);
    console.log(`  search: ${found.length} results`);
    console.log('  ✓ ok');
  } catch (e) {
    console.log(`  ✗ ${e.message}`);
    failed++;
  } finally {
    vm.dispose();
  }
}

process.exit(failed ? 1 : 0);
