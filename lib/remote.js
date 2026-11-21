'use strict';
// Yerel ağ kumanda sunucusu: telefon sayfası + SSE durum + komut POST. Bağımlılık yok.
const http = require('http');
const fs = require('fs');
const os = require('os');
const crypto = require('crypto');
const { EventEmitter } = require('events');

function isPrivate(ip) {
  ip = String(ip || '').replace(/^::ffff:/, '');
  return ip === '::1' || /^127\./.test(ip) || /^10\./.test(ip) || /^192\.168\./.test(ip) || /^172\.(1[6-9]|2\d|3[01])\./.test(ip)
    || /^169\.254\./.test(ip) || /^f[cd][0-9a-f]{2}:/i.test(ip) || /^fe80:/i.test(ip);
}

function lanAddresses() {
  const out = [];
  for (const [name, list] of Object.entries(os.networkInterfaces())) for (const i of list || []) {
    if (i.family === 'IPv4' && !i.internal) out.push({ name, ip: i.address });
  }
  const rank = a => /^192\.168\./.test(a.ip) ? 0 : /^10\./.test(a.ip) ? 1 : /^172\./.test(a.ip) ? 2 : 3;
  return out.sort((a, b) => rank(a) - rank(b));
}

class RemoteServer extends EventEmitter {
  constructor() { super(); this.server = null; this.clients = new Set(); this.port = 0; this.token = ''; this.timer = null; this.lastSites = ''; }

  start({ port, token, pagePath, getState, getSites, onCommand }) {
    return new Promise((resolve, reject) => {
      this.stop(); this.token = token; this.getState = getState; this.getSites = getSites; this.onCommand = onCommand;
      const server = http.createServer((req, res) => this.handle(req, res, pagePath));
      let tries = 0, p = port;
      server.on('error', e => {
        if (e.code === 'EADDRINUSE' && tries++ < 10) { p++; server.listen(p, '0.0.0.0'); } else reject(e);
      });
      server.on('listening', () => { this.port = p; this.server = server; this.timer = setInterval(() => this.pushState(), 1000); resolve(p); });
      server.listen(p, '0.0.0.0');
    });
  }

  stop() {
    clearInterval(this.timer);
    for (const c of this.clients) { try { c.end(); } catch { /* */ } }
    this.clients.clear();
    if (this.server) { try { this.server.close(); } catch { /* */ } this.server = null; }
    this.port = 0;
  }

  urls() {
    if (!this.port) return [];
    const list = lanAddresses();
    if (!list.length) list.push({ name: 'loopback', ip: '127.0.0.1' });
    return list.map(a => ({ ...a, url: `http://${a.ip}:${this.port}/r?t=${this.token}` }));
  }

  auth(req, url) {
    const t = url.searchParams.get('t') || req.headers['x-token'] || '';
    const a = Buffer.from(String(t)), b = Buffer.from(this.token);
    return a.length === b.length && crypto.timingSafeEqual(a, b);
  }

  async handle(req, res, pagePath) {
    if (!isPrivate(req.socket.remoteAddress)) { res.writeHead(403); return res.end('yalnızca yerel ağ'); }
    let url; try { url = new URL(req.url, 'http://x'); } catch { res.writeHead(400); return res.end(); }
    const send = (code, type, body) => { res.writeHead(code, { 'content-type': type, 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' }); res.end(body); };

    if (url.pathname === '/' || url.pathname === '/r') {
      if (!this.auth(req, url)) return send(401, 'text/html; charset=utf-8', '<meta name=viewport content="width=device-width"><body style="font:18px sans-serif;background:#111;color:#eee;padding:24px">Geçersiz anahtar. Tarayıcıdaki QR kodu yeniden tara.</body>');
      return send(200, 'text/html; charset=utf-8', fs.readFileSync(pagePath));
    }
    if (url.pathname === '/favicon.ico') return send(204, 'text/plain', '');
    if (!this.auth(req, url)) return send(401, 'text/plain', 'unauthorized');

    if (url.pathname === '/events') {
      res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-store', connection: 'keep-alive', 'x-accel-buffering': 'no' });
      res.write('retry: 1500\n\n');
      this.clients.add(res);
      req.on('close', () => this.clients.delete(res));
      this.lastSites = ''; this.pushState(true);
      return;
    }
    if (url.pathname === '/cmd' && req.method === 'POST') {
      if (!/application\/json/i.test(req.headers['content-type'] || '')) return send(415, 'text/plain', 'json bekleniyor');
      let body = ''; let dead = false;
      req.on('data', c => { body += c; if (body.length > 20000) { dead = true; req.destroy(); } });
      req.on('end', async () => {
        if (dead) return;
        try { const r = await this.onCommand(JSON.parse(body)); send(200, 'application/json', JSON.stringify({ ok: true, ...(r || {}) })); this.pushSoon(); }
        catch (e) { send(400, 'application/json', JSON.stringify({ ok: false, error: String(e.message || e) })); }
      });
      return;
    }
    send(404, 'text/plain', 'yok');
  }

  broadcast(event, data) {
    if (!this.clients.size) return;
    const msg = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
    for (const c of this.clients) { try { c.write(msg); } catch { this.clients.delete(c); } }
  }
  pushSoon() { clearTimeout(this._soon); this._soon = setTimeout(() => this.pushState(), 120); }
  async pushState(force) {
    if (!this.clients.size || this._busy) return;
    this._busy = true;
    try {
      this.broadcast('state', await this.getState());
      const sites = JSON.stringify(this.getSites());
      if (force || sites !== this.lastSites) { this.lastSites = sites; this.broadcast('sites', JSON.parse(sites)); }
    } catch (e) { /* sessiz */ } finally { this._busy = false; }
  }
}

module.exports = { RemoteServer, lanAddresses, isPrivate };
