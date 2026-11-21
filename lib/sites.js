'use strict';
// GitHub (veya herhangi bir https) JSON'undan film siteleri listesini çeker.
//
// Kabul edilen biçimler:
//   [ {"name":"..","url":"https://.."}, ... ]
//   { "name":"Liste adı", "sites":[ ... ], "blockHosts":["reklam.example"] }
//   { "categories":[ {"name":"Dizi","sites":[ ... ]} ] }
// Site alanları: name, url, category (ops.), icon (ops.), description (ops.)

function normalizeSourceUrl(input) {
  const raw = String(input || '').trim();
  let u; try { u = new URL(raw); } catch { return []; }
  if (u.hostname === 'raw.githubusercontent.com' || u.hostname === 'gist.githubusercontent.com') return [u.href];
  if (u.hostname === 'gist.github.com') { const p = u.pathname.split('/').filter(Boolean); return p.length >= 2 ? [`https://gist.githubusercontent.com/${p[0]}/${p[1]}/raw`] : []; }
  if (u.hostname === 'github.com' || u.hostname === 'www.github.com') {
    const p = u.pathname.split('/').filter(Boolean);
    if (p.length < 2) return [];
    const [owner, repo0] = p; const repo = repo0.replace(/\.git$/, '');
    const base = `https://raw.githubusercontent.com/${owner}/${repo}`;
    if (p[2] === 'blob' && p.length >= 5) return [`${base}/${p.slice(3).join('/')}`];
    if (p[2] === 'raw' && p.length >= 5) return [`${base}/${p.slice(3).join('/')}`];
    if (p[2] === 'tree' && p.length >= 4) { const dir = p.slice(4).join('/'); return [`${base}/${p[3]}/${dir ? dir + '/' : ''}sites.json`]; }
    return [`${base}/main/sites.json`, `${base}/master/sites.json`];
  }
  return /^https?:$/.test(u.protocol) ? [u.href] : [];
}

function cleanSite(s, category) {
  if (!s || typeof s !== 'object') return null;
  let url = String(s.url || s.link || '').trim();
  if (!/^https?:\/\//i.test(url)) { if (/^[\w-]+(\.[\w-]+)+/.test(url)) url = 'https://' + url; else return null; }
  let host; try { host = new URL(url).hostname; } catch { return null; }
  return {
    name: String(s.name || s.title || host.replace(/^www\./, '')).slice(0, 60),
    url, category: String(s.category || category || '').slice(0, 30),
    icon: /^https?:\/\//i.test(s.icon || '') ? s.icon : '',
    description: String(s.description || '').slice(0, 140),
  };
}

function parseSites(json) {
  let name = '', list = [], blockHosts = [];
  if (Array.isArray(json)) list = json.map(s => cleanSite(s));
  else if (json && typeof json === 'object') {
    name = String(json.name || json.title || '');
    if (Array.isArray(json.sites)) list = list.concat(json.sites.map(s => cleanSite(s)));
    if (Array.isArray(json.categories)) json.categories.forEach(c => (c.sites || []).forEach(s => list.push(cleanSite(s, c.name))));
    if (Array.isArray(json.blockHosts)) blockHosts = json.blockHosts.map(String);
  }
  return { name, sites: list.filter(Boolean), blockHosts };
}

async function fetchSource(input, fetchFn = fetch, timeoutMs = 9000) {
  const candidates = normalizeSourceUrl(input);
  if (!candidates.length) throw new Error('Geçersiz bağlantı');
  let lastErr;
  for (const url of candidates) {
    const ac = new AbortController(); const t = setTimeout(() => ac.abort(), timeoutMs);
    try {
      const res = await fetchFn(url + (url.includes('?') ? '&' : '?') + '_=' + Math.floor(Date.now() / 60000), { signal: ac.signal, headers: { accept: 'application/json,text/plain,*/*' } });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const parsed = parseSites(JSON.parse(await res.text()));
      return { ...parsed, resolvedUrl: url };
    } catch (e) { lastErr = e.name === 'AbortError' ? new Error('Zaman aşımı') : e; } finally { clearTimeout(t); }
  }
  throw lastErr || new Error('Alınamadı');
}

module.exports = { normalizeSourceUrl, parseSites, fetchSource, cleanSite };
