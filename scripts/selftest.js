// Electron'sız mantık testleri: node scripts/selftest.js
const assert = require('assert');
const http = require('http');
const { AdBlock } = require('../lib/adblock');
const { normalizeSourceUrl, parseSites, fetchSource } = require('../lib/sites');
const qr = require('../lib/qr');
const { RemoteServer, isPrivate } = require('../lib/remote');
const fs = require('fs'), path = require('path');

(async () => {
  // reklam engelleyici
  const ab = new AdBlock();
  assert(ab.shouldBlock('https://ads.doubleclick.net/x.js', 'script', 'https://film.example/izle'));
  assert(!ab.shouldBlock('https://film.example/player.js', 'script', 'https://film.example/izle'), 'aynı site engellenmemeli');
  assert(!ab.shouldBlock('https://cdn.example.org/lib.js', 'script', 'https://film.example/'));
  assert(ab.shouldBlock('https://x.y.exoclick.com/a', 'image', 'https://film.example/'));
  ab.loadRemoteText('! yorum\n||tracker.test^\n0.0.0.0 evil.test\n@@||ok.test^');
  assert(ab.shouldBlock('https://a.tracker.test/p', 'xhr', 'https://film.example/'));
  assert(ab.shouldBlock('https://evil.test/p', 'xhr', 'https://film.example/'));
  ab.allowOnce('https://ads.doubleclick.net/y'); assert(!ab.shouldBlock('https://ads.doubleclick.net/y', 'mainFrame', ''));
  ab.setCustom(['||benimreklam.com^']); assert(ab.shouldBlock('https://benimreklam.com/a', 'image', 'https://s.com'));
  ab.enabled = false; assert(!ab.shouldBlock('https://ads.doubleclick.net/x.js', 'script', 'https://s.com'));

  // GitHub link çözümleme
  const n = normalizeSourceUrl;
  assert.deepStrictEqual(n('https://github.com/darknesslord19/Ghost-Browser'), ['https://raw.githubusercontent.com/darknesslord19/Ghost-Browser/main/sites.json', 'https://raw.githubusercontent.com/darknesslord19/Ghost-Browser/master/sites.json']);
  assert.deepStrictEqual(n('https://github.com/a/b/blob/dev/data/x.json'), ['https://raw.githubusercontent.com/a/b/dev/data/x.json']);
  assert.deepStrictEqual(n('https://github.com/a/b/tree/main/lists'), ['https://raw.githubusercontent.com/a/b/main/lists/sites.json']);
  assert.deepStrictEqual(n('https://gist.github.com/u/abc123'), ['https://gist.githubusercontent.com/u/abc123/raw']);
  assert.deepStrictEqual(n('ftp://x'), []);
  const p1 = parseSites([{ name: 'A', url: 'a.com' }, { url: 'javascript:alert(1)' }, { name: 'B', url: 'https://b.com' }]);
  assert.strictEqual(p1.sites.length, 2); assert.strictEqual(p1.sites[0].url, 'https://a.com');
  const p2 = parseSites({ name: 'L', categories: [{ name: 'Dizi', sites: [{ name: 'X', url: 'https://x.com' }] }], blockHosts: ['ad.x'] });
  assert.strictEqual(p2.sites[0].category, 'Dizi'); assert.deepStrictEqual(p2.blockHosts, ['ad.x']);
  // main.js: ghost sayfası dışında hiçbir şeyin javascript: olarak geçmediğini doğrular
  assert(!parseSites([{ url: 'file:///etc/passwd' }]).sites.length);
  const fake = async (u) => ({ ok: !u.includes('/main/'), status: 404, text: async () => JSON.stringify({ sites: [{ name: 'Z', url: 'https://z.com' }] }) });
  const fr = await fetchSource('https://github.com/o/r', fake); assert.strictEqual(fr.sites.length, 1); assert(fr.resolvedUrl.includes('/master/'));

  // QR
  const q = qr.encode('http://192.168.1.20:8421/r?t=abcdef123456'); assert(q.size >= 29);

  // kumanda sunucusu
  assert(isPrivate('::ffff:192.168.1.5') && isPrivate('10.1.1.1') && !isPrivate('8.8.8.8') && !isPrivate('172.32.0.1'));
  const rs = new RemoteServer(); const cmds = [];
  const port = await rs.start({ port: 18421, token: 'tok123', pagePath: path.join(__dirname, '../pages/remote.html'), getState: async () => ({ title: 'T' }), getSites: () => [{ name: 'S', url: 'https://s.com' }], onCommand: async (c) => { cmds.push(c); } });
  const get = (p, opt = {}) => new Promise((res, rej) => { const r = http.request({ host: '127.0.0.1', port, path: p, ...opt }, (x) => { let b = ''; x.on('data', d => b += d); x.on('end', () => res({ code: x.statusCode, body: b })); }); r.on('error', rej); if (opt.body) r.write(opt.body); r.end(); });
  assert.strictEqual((await get('/r?t=bad')).code, 401);
  assert.strictEqual((await get('/r?t=tok123')).code, 200);
  assert.strictEqual((await get('/cmd?t=tok123', { method: 'POST', headers: { 'content-type': 'text/plain' }, body: '{}' })).code, 415);
  const ok = await get('/cmd?t=tok123', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ type: 'open', value: 'x' }) });
  assert.strictEqual(ok.code, 200); assert.strictEqual(cmds[0].type, 'open');
  // SSE
  const ev = await new Promise((res) => { const r = http.get({ host: '127.0.0.1', port, path: '/events?t=tok123' }, (x) => { let b = ''; x.on('data', d => { b += d; if (b.includes('event: sites')) { r.destroy(); res(b); } }); }); });
  assert(ev.includes('event: state') && ev.includes('"title":"T"') && ev.includes('event: sites'));
  assert(rs.urls().every(u => u.url.includes('/r?t=tok123')));
  rs.stop();

  // sayfa betikleri sözdizimi
  for (const f of ['ui', 'newtab', 'settings', 'player', 'remote']) {
    const html = fs.readFileSync(path.join(__dirname, '../pages', f + '.html'), 'utf8');
    const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]);
    scripts.forEach((s, i) => { try { new Function(s); } catch (e) { throw new Error(`${f}.html script ${i}: ${e.message}`); } });
  }
  console.log('Tüm testler geçti ✓');
})().catch(e => { console.error('TEST HATASI:', e); process.exit(1); });
