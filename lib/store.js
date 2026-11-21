'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

class JsonFile {
  constructor(file, fallback) {
    this.file = file; this.data = fallback; this.timer = null;
    try { this.data = JSON.parse(fs.readFileSync(file, 'utf8')); } catch { /* ilk çalıştırma */ }
  }
  save(now = false) {
    clearTimeout(this.timer);
    const write = () => {
      try { fs.mkdirSync(path.dirname(this.file), { recursive: true }); fs.writeFileSync(this.file + '.tmp', JSON.stringify(this.data)); fs.renameSync(this.file + '.tmp', this.file); } catch (e) { console.error('kayıt hatası', e.message); }
    };
    if (now) write(); else this.timer = setTimeout(write, 400);
  }
}

const DEFAULTS = {
  searchEngine: 'https://www.google.com/search?q=%s',
  homepage: 'ghost://newtab',
  adblock: true, popupBlock: true, cosmetic: true,
  filterListUrl: 'https://raw.githubusercontent.com/AdguardTeam/AdGuardSDNSFilter/master/Filters/filter.txt',
  customBlockHosts: [],
  sitesSources: ['https://github.com/darknesslord19/Ghost-Browser'],
  localSites: [],
  tvMode: false,
  remoteEnabled: true, remotePort: 8421, remoteToken: '', cursorSpeed: 1,
};

class Settings extends JsonFile {
  constructor(file) {
    super(file, {});
    this.data = Object.assign({}, DEFAULTS, this.data);
    if (!this.data.remoteToken) { this.data.remoteToken = crypto.randomBytes(6).toString('hex'); this.save(true); }
  }
  get() { return this.data; }
  set(patch) { Object.assign(this.data, patch); this.save(); return this.data; }
}

class History extends JsonFile {
  constructor(file) { super(file, []); if (!Array.isArray(this.data)) this.data = []; this.map = new Map(this.data.map(h => [h.url, h])); }
  add(url, title) {
    if (!/^https?:/i.test(url)) return;
    let h = this.map.get(url);
    if (!h) { h = { url, title: title || url, time: Date.now(), visits: 0 }; this.map.set(url, h); this.data.push(h); }
    h.visits++; h.time = Date.now(); if (title) h.title = title;
    if (this.data.length > 5000) { this.data.sort((a, b) => b.time - a.time); this.data.length = 4000; this.map = new Map(this.data.map(x => [x.url, x])); }
    this.save();
  }
  setTitle(url, title) { const h = this.map.get(url); if (h && title) { h.title = title; this.save(); } }
  recent(limit = 30, q = '') {
    q = q.toLowerCase();
    return this.data.filter(h => !q || h.url.toLowerCase().includes(q) || (h.title || '').toLowerCase().includes(q))
      .sort((a, b) => b.time - a.time).slice(0, limit);
  }
  top(limit = 8) { return [...this.data].sort((a, b) => b.visits - a.visits || b.time - a.time).slice(0, limit); }
  remove(url) { const h = this.map.get(url); if (h) { this.data.splice(this.data.indexOf(h), 1); this.map.delete(url); this.save(); } }
  clear() { this.data.length = 0; this.map.clear(); this.save(true); }
}

module.exports = { Settings, History, DEFAULTS, JsonFile };
