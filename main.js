'use strict';
const { app, BaseWindow, WebContentsView, session, ipcMain, protocol, net, Menu, shell, clipboard } = require('electron');
const path = require('path');
const fs = require('fs');
const { pathToFileURL } = require('url');

const { Settings, History } = require('./lib/store');
const { AdBlock, COSMETIC_CSS, hostOf } = require('./lib/adblock');
const { fetchSource, parseSites } = require('./lib/sites');
const { RemoteServer } = require('./lib/remote');
const qr = require('./lib/qr');

const PAGES = path.join(__dirname, 'pages');
const TOOLBAR_H = 84;

// ---- performans bayrakları (ready'den önce) ----
app.commandLine.appendSwitch('enable-gpu-rasterization');
app.commandLine.appendSwitch('ignore-gpu-blocklist');
app.commandLine.appendSwitch('enable-zero-copy');
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');
app.commandLine.appendSwitch('disable-features', 'MediaRouter,CalculateNativeWinOcclusion');

protocol.registerSchemesAsPrivileged([{ scheme: 'ghost', privileges: { standard: true, supportFetchAPI: true, stream: true, corsEnabled: true } }]);

if (!app.requestSingleInstanceLock()) { app.quit(); }

// ---- durum ----
let settings, history, win, ui;
const adblock = new AdBlock();
const remote = new RemoteServer();
const tabs = new Map();      // id -> tab
const byWc = new Map();      // webContents.id -> tab
const closedTabs = [];
let activeId = null, seq = 0;
let uiOverlay = false, htmlFsTab = null, winWasFs = false;
const sourceSites = new Map();   // url -> {name, sites, blockHosts, at}
const sourceState = new Map();   // url -> {ok, error, at, count, name}
let defaultSites = [];

const active = () => tabs.get(activeId);
const isInternal = (u) => /^ghost:\/\//.test(u || '');
const zoom = () => (settings.get().tvMode ? 1.3 : 1);
const toolbarH = () => Math.round(TOOLBAR_H * zoom());

// ================= yardımcılar =================
function resolveInput(input) {
  const s = String(input || '').trim();
  if (!s) return 'ghost://newtab';
  if (/^(ghost|https?|file|about|view-source):/i.test(s)) return s;
  if (!/\s/.test(s) && (/^localhost(:\d+)?([\/?#].*)?$/i.test(s) || /^\d{1,3}(\.\d{1,3}){3}(:\d+)?([\/?#].*)?$/.test(s))) return 'http://' + s;
  if (!/\s/.test(s) && /^[^\s\/]+\.[a-z]{2,}(:\d+)?([\/?#].*)?$/i.test(s)) return 'https://' + s;
  return settings.get().searchEngine.replace('%s', encodeURIComponent(s));
}
const isDirectVideo = (u) => /^https?:\/\/[^\s]+\.(mp4|m3u8|webm|mkv|mov|m4v)(\?[^\s]*)?(#.*)?$/i.test(u);
function playerUrl(src, { title, ref } = {}) {
  return 'ghost://player/?src=' + encodeURIComponent(src) + (title ? '&title=' + encodeURIComponent(title) : '') + (ref ? '&ref=' + encodeURIComponent(ref) : '');
}
function displayUrl(u) {
  if (!u || u.startsWith('ghost://newtab')) return '';
  if (u.startsWith('ghost://player')) return '🎬 Ghost Oynatıcı';
  return u;
}
const sendUi = (ch, ...a) => { if (ui && !ui.webContents.isDestroyed()) ui.webContents.send('ui-ev:' + ch, ...a); };
function sendGhostPages(ch, ...a) {
  for (const t of tabs.values()) if (isInternal(t.wc.getURL())) t.wc.send('g-ev:' + ch, ...a);
}

// ================= düzen =================
function layout() {
  if (!win || win.isDestroyed()) return;
  const { width: w, height: h } = win.getContentBounds();
  const fs = !!htmlFsTab;
  const th = fs ? 0 : toolbarH();
  ui.setVisible(!fs);
  ui.setBounds(uiOverlay && !fs ? { x: 0, y: 0, width: w, height: h } : { x: 0, y: 0, width: w, height: th });
  const t = active();
  if (t) t.view.setBounds({ x: 0, y: th, width: w, height: Math.max(0, h - th) });
}

// ================= sekmeler =================
function createTab(input, { activate = true } = {}) {
  const id = ++seq;
  const view = new WebContentsView({
    webPreferences: { preload: path.join(__dirname, 'preload-tab.js'), sandbox: true, contextIsolation: true, nodeIntegration: false, spellcheck: false },
  });
  view.setBackgroundColor('#0b0b10');
  const wc = view.webContents;
  const tab = { id, view, wc, title: 'Yeni sekme', favicon: '', media: [], cursor: { x: 240, y: 200 }, lastGesture: 0, tvAccel: 1, lastPopup: 0, playerRef: '' };
  tabs.set(id, tab); byWc.set(wc.id, tab);
  wireTab(tab);
  if (activate) activateTab(id);
  const url = resolveInput(input || settings.get().homepage || 'ghost://newtab');
  adblock.allowOnce(url);
  wc.loadURL(isDirectVideo(url) ? playerUrl(url) : url).catch(() => {});
  queueTabs();
  return tab;
}

function activateTab(id) {
  const t = tabs.get(id); if (!t) return;
  const prev = active();
  if (prev && prev !== t) { try { win.contentView.removeChildView(prev.view); } catch { /* */ } }
  activeId = id;
  win.contentView.addChildView(t.view);
  win.contentView.addChildView(ui); // arayüz her zaman üstte
  layout();
  t.wc.focus();
  queueTabs();
}

function closeTab(id) {
  const t = tabs.get(id); if (!t) return;
  const url = t.wc.getURL(); if (url && !url.startsWith('ghost://newtab')) closedTabs.push(url);
  const ids = [...tabs.keys()], idx = ids.indexOf(id);
  if (htmlFsTab === t) { htmlFsTab = null; win.setFullScreen(winWasFs); }
  try { win.contentView.removeChildView(t.view); } catch { /* */ }
  tabs.delete(id); byWc.delete(t.wc.id);
  try { t.wc.close(); } catch { /* */ }
  if (!tabs.size) { createTab('ghost://newtab'); return; }
  if (activeId === id) activateTab(ids[idx + 1] ?? ids[idx - 1]); else queueTabs();
}

function cycleTab(dir) {
  const ids = [...tabs.keys()]; if (ids.length < 2) return;
  activateTab(ids[(ids.indexOf(activeId) + dir + ids.length) % ids.length]);
}

function navigate(tab, input) {
  let u = resolveInput(input);
  if (isDirectVideo(u)) u = playerUrl(u);
  adblock.allowOnce(u);
  tab.wc.loadURL(u).catch(() => {});
  tab.wc.focus();
}

function openInternal(name) { // 'settings#remote'
  const base = 'ghost://' + name.split('#')[0];
  const ex = [...tabs.values()].find(t => t.wc.getURL().startsWith(base));
  if (ex) { activateTab(ex.id); ex.wc.loadURL('ghost://' + name).catch(() => {}); return ex; }
  return createTab('ghost://' + name);
}

function openPlayerFor(tab, m) {
  const t = createTab(playerUrl(m.url, { title: tab.title, ref: m.ref || tab.wc.getURL() }));
  return t;
}

let tabsTimer = null;
function tabInfos() {
  return [...tabs.values()].map(t => ({
    id: t.id, title: t.title || 'Sekme', url: displayUrl(t.wc.getURL()), favicon: t.favicon, loading: t.wc.isLoading(),
    active: t.id === activeId, canBack: t.wc.navigationHistory.canGoBack(), canForward: t.wc.navigationHistory.canGoForward(),
    media: t.media.length, audible: t.wc.isCurrentlyAudible(),
  }));
}
function queueTabs() {
  clearTimeout(tabsTimer);
  tabsTimer = setTimeout(() => { sendUi('tabs', { tabs: tabInfos(), active: activeId }); remote.pushSoon(); }, 30);
}

const DIRECT_ERR = (code, desc, url) => 'data:text/html;charset=utf-8,' + encodeURIComponent(
  `<meta charset=utf-8><body style="margin:0;display:grid;place-items:center;height:100vh;background:#0b0b10;color:#e8e8f0;font:16px system-ui"><div style="text-align:center;max-width:560px;padding:24px"><div style="font-size:54px">👻</div><h2>Sayfa açılamadı</h2><p style="opacity:.7;word-break:break-all">${url.replace(/[<>&]/g, '')}</p><p style="color:#ff7a90">${desc} (${code})</p><button onclick="location.href='${url.replace(/'/g, '')}'" style="padding:10px 18px;border-radius:10px;border:0;background:#8b5cf6;color:#fff;font-size:15px">Yeniden dene</button></div>`);

function wireTab(tab) {
  const wc = tab.wc;
  wc.on('page-title-updated', (_e, title) => { tab.title = title; history.setTitle(wc.getURL(), title); queueTabs(); });
  wc.on('page-favicon-updated', (_e, favs) => { tab.favicon = favs[0] || ''; queueTabs(); });
  wc.on('did-start-loading', queueTabs);
  wc.on('did-stop-loading', queueTabs);
  wc.on('audio-state-changed', queueTabs);
  wc.on('did-start-navigation', (_e, url, inPlace, isMain) => { if (isMain && !inPlace) { tab.media = []; } });
  const onNav = (_e, url) => {
    if (url.startsWith('ghost://player')) { try { tab.playerRef = new URL(url).searchParams.get('ref') || ''; } catch { tab.playerRef = ''; } }
    if (url.startsWith('ghost://newtab') || url.startsWith('ghost://settings')) tab.title = url.startsWith('ghost://newtab') ? 'Yeni sekme' : 'Ayarlar';
    history.add(url, wc.getTitle()); queueTabs();
  };
  wc.on('did-navigate', onNav);
  wc.on('did-navigate-in-page', (_e, url, isMain) => { if (isMain) onNav(_e, url); });
  wc.on('did-fail-load', (_e, code, desc, url, isMain) => { if (isMain && code !== -3 && code !== -27 && !isInternal(url)) wc.loadURL(DIRECT_ERR(code, desc, url)).catch(() => {}); });
  wc.on('dom-ready', () => {
    const u = wc.getURL();
    if (/^https?:/i.test(u) && settings.get().adblock && settings.get().cosmetic) wc.insertCSS(COSMETIC_CSS).catch(() => {});
  });
  wc.on('render-process-gone', () => { tab.title = 'Çöktü — yenile'; queueTabs(); });
  wc.on('enter-html-full-screen', () => { htmlFsTab = tab; winWasFs = win.isFullScreen(); win.setFullScreen(true); layout(); });
  wc.on('leave-html-full-screen', () => { if (htmlFsTab === tab) { htmlFsTab = null; win.setFullScreen(winWasFs); layout(); } });
  wc.on('before-input-event', (e, input) => handleKey(tab, e, input));
  wc.on('context-menu', (_e, p) => showContextMenu(tab, p));

  wc.setWindowOpenHandler((d) => {
    const s = settings.get(), now = Date.now();
    const blocked = adblock.enabled && adblock.isBlockedHost(hostOf(d.url));
    const noGesture = s.popupBlock && now - tab.lastGesture > 1500;
    const tooFast = s.popupBlock && now - tab.lastPopup < 1200;
    if (/^https?:/i.test(d.url) && !blocked && !noGesture && !tooFast) {
      tab.lastPopup = now;
      createTab(d.url, { activate: d.disposition !== 'background-tab' });
    } else if (/^https?:/i.test(d.url)) { adblock.stats.popups++; }
    return { action: 'deny' };
  });
}

// ================= klavye / TV =================
function handleKey(tab, e, input) {
  if (input.type !== 'keyDown') return;
  const ctrl = input.control || input.meta, k = input.key, kl = k.toLowerCase();
  const act = (fn) => { e.preventDefault(); fn(); };
  const t = active();
  if (ctrl && !input.alt) {
    if (!input.shift) {
      if (kl === 't') return act(() => createTab('ghost://newtab'));
      if (kl === 'w') return act(() => closeTab(activeId));
      if (kl === 'l') return act(() => sendUi('focus-omnibox'));
      if (kl === 'r') return act(() => t && t.wc.reload());
      if (kl === 'h') return act(() => openInternal('settings#history'));
      if (k === ',') return act(() => openInternal('settings'));
      if (k === 'Tab') return act(() => cycleTab(1));
      if (/^[1-9]$/.test(k)) return act(() => { const ids = [...tabs.keys()]; activateTab(k === '9' ? ids[ids.length - 1] : ids[+k - 1] ?? activeId); });
      if (k === '=' || k === '+') return act(() => t && t.wc.setZoomLevel(t.wc.getZoomLevel() + 0.5));
      if (k === '-') return act(() => t && t.wc.setZoomLevel(t.wc.getZoomLevel() - 0.5));
      if (k === '0') return act(() => t && t.wc.setZoomLevel(0));
    } else {
      if (kl === 't') return act(() => { const u = closedTabs.pop(); if (u) createTab(u); });
      if (k === 'Tab') return act(() => cycleTab(-1));
      if (kl === 'r') return act(() => t && t.wc.reloadIgnoringCache());
      if (kl === 'i') return act(() => t && t.wc.openDevTools({ mode: 'detach' }));
    }
  }
  if (input.alt && !ctrl) {
    if (k === 'ArrowLeft') return act(() => t && t.wc.navigationHistory.goBack());
    if (k === 'ArrowRight') return act(() => t && t.wc.navigationHistory.goForward());
    if (k === 'Home') return act(() => t && navigate(t, settings.get().homepage));
  }
  if (k === 'F5') return act(() => t && t.wc.reload());
  if (k === 'F11') return act(() => win.setFullScreen(!win.isFullScreen()));
  if (k === 'F12') return act(() => t && t.wc.openDevTools({ mode: 'detach' }));
  if (k === 'F9') return act(() => { settings.set({ tvMode: !settings.get().tvMode }); applySettings(); });
  if (k === 'MediaPlayPause' && t) return act(() => mediaCmd(t, 'toggle'));

  // TV modu: yön tuşlarıyla sanal imleç (yalnızca normal web sayfalarında)
  if (settings.get().tvMode && tab === t && !isInternal(tab.wc.getURL()) && !ctrl && !input.alt) {
    const dirs = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] };
    if (dirs[k]) {
      e.preventDefault();
      tab.tvAccel = input.isAutoRepeat ? Math.min(tab.tvAccel + 0.5, 8) : 1;
      const step = 20 * tab.tvAccel * settings.get().cursorSpeed;
      if (input.shift) scrollTab(tab, dirs[k][1] * step * 3); else moveCursor(tab, dirs[k][0] * step, dirs[k][1] * step);
    } else if (k === 'Enter') { e.preventDefault(); clickCursor(tab); }
    else if (k === 'PageDown') { e.preventDefault(); scrollTab(tab, 500); }
    else if (k === 'PageUp') { e.preventDefault(); scrollTab(tab, -500); }
  }
}

// ================= sanal imleç / giriş =================
const CURSOR_JS = (x, y) => `(()=>{if(!window.__gbc){const d=document.createElement('div');d.style.cssText='position:fixed;z-index:2147483647;width:24px;height:24px;border-radius:50%;background:rgba(255,70,90,.9);border:3px solid #fff;box-shadow:0 0 14px rgba(0,0,0,.8);pointer-events:none;left:0;top:0;transform:translate(-50%,-50%);transition:opacity .25s;opacity:0';document.documentElement.appendChild(d);let to;window.__gbc=(x,y)=>{d.style.left=x+'px';d.style.top=y+'px';d.style.opacity=1;clearTimeout(to);to=setTimeout(()=>{d.style.opacity=0},3500)}}window.__gbc(${x},${y})})()`;

function viewSize(tab) { const b = tab.view.getBounds(); return { w: b.width, h: b.height }; }
function moveCursor(tab, dx, dy) {
  const { w, h } = viewSize(tab), c = tab.cursor;
  c.x = Math.max(2, Math.min(w - 3, c.x + dx)); c.y = Math.max(2, Math.min(h - 3, c.y + dy));
  const x = Math.round(c.x), y = Math.round(c.y);
  tab.wc.sendInputEvent({ type: 'mouseMove', x, y });
  tab.wc.executeJavaScript(CURSOR_JS(x, y)).catch(() => {});
  if (dy > 0 && c.y >= h - 6) scrollTab(tab, dy * 2); else if (dy < 0 && c.y <= 6) scrollTab(tab, dy * 2);
}
function clickCursor(tab) {
  const x = Math.round(tab.cursor.x), y = Math.round(tab.cursor.y);
  tab.wc.focus();
  tab.wc.sendInputEvent({ type: 'mouseMove', x, y });
  tab.wc.sendInputEvent({ type: 'mouseDown', x, y, button: 'left', clickCount: 1 });
  setTimeout(() => { try { tab.wc.sendInputEvent({ type: 'mouseUp', x, y, button: 'left', clickCount: 1 }); } catch { /* */ } }, 45);
  tab.lastGesture = Date.now();
  tab.wc.executeJavaScript(CURSOR_JS(x, y)).catch(() => {});
}
function scrollTab(tab, dy) {
  tab.wc.sendInputEvent({ type: 'mouseWheel', x: Math.round(tab.cursor.x), y: Math.round(tab.cursor.y), deltaX: 0, deltaY: -dy, canScroll: true });
}
const KEYMAP = { Enter: 'Return', Backspace: 'Backspace', Esc: 'Esc', Escape: 'Esc', Left: 'Left', Right: 'Right', Up: 'Up', Down: 'Down', Tab: 'Tab', Space: 'Space', PageDown: 'PageDown', PageUp: 'PageUp' };
function sendKey(tab, key) {
  const keyCode = KEYMAP[key]; if (!keyCode) return;
  tab.wc.focus();
  tab.wc.sendInputEvent({ type: 'keyDown', keyCode });
  if (key === 'Enter') tab.wc.sendInputEvent({ type: 'char', keyCode: '\r' });
  if (key === 'Space') tab.wc.sendInputEvent({ type: 'char', keyCode: ' ' });
  tab.wc.sendInputEvent({ type: 'keyUp', keyCode });
  tab.lastGesture = Date.now();
}

// ================= medya kontrolü (tüm iframe'lerde) =================
const MEDIA_SCRIPT = (cmd, arg) => `(()=>{try{
const vs=[...document.querySelectorAll('video')].filter(v=>v.readyState>0||v.currentSrc||v.src);
if(!vs.length)return null;
vs.sort((a,b)=>((!b.paused)-(!a.paused))||((b.videoWidth*b.videoHeight)-(a.videoWidth*a.videoHeight)));
const v=vs[0],cmd=${JSON.stringify(cmd)},arg=${JSON.stringify(arg ?? null)},P=window.__ghostPlayer;
switch(cmd){
case'toggle':v.paused?v.play():v.pause();break;
case'play':v.play();break;case'pause':v.pause();break;
case'seek':v.currentTime=Math.max(0,Math.min(v.duration||1e9,v.currentTime+arg));break;
case'seekTo':v.currentTime=arg*(v.duration||0);break;
case'vol':v.muted=false;v.volume=Math.max(0,Math.min(1,v.volume+arg));break;
case'mute':v.muted=!v.muted;break;
case'speed':{const r=[0.5,0.75,1,1.25,1.5,2];const i=r.findIndex(x=>x>=v.playbackRate-0.001);v.playbackRate=r[((i<0?2:i)+1)%r.length];break}
case'fs':if(P&&P.fullscreen)P.fullscreen();else if(document.fullscreenElement)document.exitFullscreen();else{const c=v.closest('[class*="player" i],[id*="player" i]')||v;(c.requestFullscreen||c.webkitRequestFullscreen).call(c)}break;
case'subs':if(P&&P.toggleSubs)P.toggleSubs();else{const t=[...v.textTracks];if(t.length){const on=t.some(x=>x.mode==='showing');t.forEach(x=>x.mode=on?'disabled':'showing')}}break;
}
return{playing:!v.paused&&!v.ended,t:v.currentTime||0,d:isFinite(v.duration)?v.duration:0,vol:v.volume,muted:v.muted,rate:v.playbackRate,fs:!!document.fullscreenElement}
}catch(e){return null}})()`;

async function mediaCmd(tab, cmd, arg) {
  if (!tab || tab.wc.isDestroyed()) return null;
  const code = MEDIA_SCRIPT(cmd, arg);
  const frames = tab.wc.mainFrame.framesInSubtree;
  const res = await Promise.all(frames.map(f => f.executeJavaScript(code, cmd !== 'state').catch(() => null)));
  return res.find(Boolean) || null;
}

// ================= bağlam menüsü =================
function showContextMenu(tab, p) {
  const wc = tab.wc, items = [];
  if (p.linkURL) {
    items.push({ label: 'Bağlantıyı yeni sekmede aç', click: () => createTab(p.linkURL) });
    items.push({ label: 'Bağlantıyı kopyala', click: () => clipboard.writeText(p.linkURL) });
    if (isDirectVideo(p.linkURL)) items.push({ label: '🎬 Oynatıcıda aç', click: () => createTab(playerUrl(p.linkURL, { ref: wc.getURL() })) });
    items.push({ type: 'separator' });
  }
  if (p.mediaType === 'video' && p.srcURL && /^https?:/.test(p.srcURL)) {
    items.push({ label: '🎬 Ghost Oynatıcı\'da aç', click: () => createTab(playerUrl(p.srcURL, { title: tab.title, ref: wc.getURL() })) });
    items.push({ type: 'separator' });
  }
  if (p.mediaType === 'image' && p.srcURL) items.push({ label: 'Görsel adresini kopyala', click: () => clipboard.writeText(p.srcURL) });
  if (p.isEditable) items.push({ role: 'cut' }, { role: 'copy' }, { role: 'paste' }, { role: 'selectAll' }, { type: 'separator' });
  else if (p.selectionText) {
    items.push({ role: 'copy' });
    items.push({ label: `"${p.selectionText.slice(0, 24)}" ara`, click: () => createTab(settings.get().searchEngine.replace('%s', encodeURIComponent(p.selectionText))) });
    items.push({ type: 'separator' });
  }
  items.push({ label: 'Geri', enabled: wc.navigationHistory.canGoBack(), click: () => wc.navigationHistory.goBack() });
  items.push({ label: 'İleri', enabled: wc.navigationHistory.canGoForward(), click: () => wc.navigationHistory.goForward() });
  items.push({ label: 'Yenile', click: () => wc.reload() });
  items.push({ type: 'separator' }, { label: 'İncele', click: () => wc.inspectElement(p.x, p.y) });
  Menu.buildFromTemplate(items).popup({ window: win });
}

function showMainMenu() {
  const t = active();
  Menu.buildFromTemplate([
    { label: 'Yeni sekme', accelerator: 'CmdOrCtrl+T', click: () => createTab('ghost://newtab') },
    { label: 'Kapatılan sekmeyi aç', accelerator: 'CmdOrCtrl+Shift+T', click: () => { const u = closedTabs.pop(); if (u) createTab(u); } },
    { type: 'separator' },
    { label: 'Geçmiş', accelerator: 'CmdOrCtrl+H', click: () => openInternal('settings#history') },
    { label: 'Ayarlar', accelerator: 'CmdOrCtrl+,', click: () => openInternal('settings') },
    { label: 'Telefon kumandası (QR)', click: () => sendUi('show-remote') },
    { type: 'separator' },
    { label: 'TV modu', type: 'checkbox', checked: settings.get().tvMode, accelerator: 'F9', click: () => { settings.set({ tvMode: !settings.get().tvMode }); applySettings(); } },
    { label: 'Reklam engelleyici', type: 'checkbox', checked: settings.get().adblock, click: () => { settings.set({ adblock: !settings.get().adblock }); applySettings(); } },
    { label: 'Tam ekran', accelerator: 'F11', click: () => win.setFullScreen(!win.isFullScreen()) },
    { label: 'Geliştirici araçları', accelerator: 'F12', click: () => t && t.wc.openDevTools({ mode: 'detach' }) },
    { type: 'separator' },
    { label: 'Çıkış', role: 'quit' },
  ]).popup({ window: win });
}

// ================= siteler (GitHub JSON) =================
const cacheFile = () => path.join(app.getPath('userData'), 'sites-cache.json');
function loadSitesCache() { try { const c = JSON.parse(fs.readFileSync(cacheFile(), 'utf8')); for (const [u, v] of Object.entries(c)) sourceSites.set(u, v); } catch { /* */ } }
function saveSitesCache() { try { fs.writeFileSync(cacheFile(), JSON.stringify(Object.fromEntries(sourceSites))); } catch { /* */ } }

async function refreshSites() {
  const srcs = settings.get().sitesSources;
  await Promise.all(srcs.map(async (u) => {
    try {
      const r = await fetchSource(u, (url, o) => net.fetch(url, o));
      sourceSites.set(u, { name: r.name, sites: r.sites, blockHosts: r.blockHosts, at: Date.now() });
      sourceState.set(u, { ok: true, count: r.sites.length, name: r.name, at: Date.now(), resolved: r.resolvedUrl });
    } catch (e) {
      const c = sourceSites.get(u);
      sourceState.set(u, { ok: false, error: String(e.message || e), count: c ? c.sites.length : 0, cached: !!c, at: Date.now() });
    }
  }));
  for (const u of [...sourceSites.keys()]) if (!srcs.includes(u)) sourceSites.delete(u);
  saveSitesCache();
  adblock.setPushed([...sourceSites.values()].flatMap(v => v.blockHosts || []));
  sendGhostPages('sites-updated'); remote.pushSoon();
}
function mergedSites() {
  const out = [], seen = new Set();
  const add = (s, src) => { if (!seen.has(s.url)) { seen.add(s.url); out.push({ ...s, src }); } };
  for (const u of settings.get().sitesSources) for (const s of (sourceSites.get(u)?.sites || [])) add(s, 'github');
  for (const s of settings.get().localSites) add(s, 'local');
  if (!out.length) for (const s of defaultSites) add(s, 'builtin');
  return out;
}
function sourcesStatus() {
  return settings.get().sitesSources.map(u => ({ url: u, ...(sourceState.get(u) || { pending: true }) }));
}

// ================= reklam engelleyici bağlama =================
function filterCachePath() { return path.join(app.getPath('userData'), 'filter-cache.txt'); }
async function refreshFilterList(force = false) {
  const url = settings.get().filterListUrl; adblock.remote = new Set();
  if (!url) return;
  const f = filterCachePath();
  try { adblock.loadRemoteText(fs.readFileSync(f, 'utf8')); } catch { /* */ }
  let stale = true; try { stale = Date.now() - fs.statSync(f).mtimeMs > 3 * 864e5; } catch { /* */ }
  if (!force && !stale) return;
  try {
    const r = await net.fetch(url); if (!r.ok) throw new Error('HTTP ' + r.status);
    const text = await r.text(); const n = adblock.loadRemoteText(text);
    if (n > 100) fs.writeFileSync(f, text);
  } catch (e) { console.warn('filtre listesi alınamadı:', e.message); }
}

const MEDIA_URL = /\.(m3u8|mp4|webm|m4v|mov)(\?|#|$)/i;
function sniffMedia(d, ct, len) {
  const tab = byWc.get(d.webContentsId);
  if (!tab || tab.wc.getURL().startsWith('ghost://')) return;
  const isCt = /^(video\/|application\/(vnd\.apple\.mpegurl|x-mpegurl)|audio\/mpegurl)/i.test(ct || '');
  if (!isCt && !MEDIA_URL.test(d.url)) return;
  if (/\.(ts|m4s)(\?|$)/i.test(d.url) || /seg(ment)?[-_]?\d+/i.test(d.url)) return;
  if (len && len < 150000 && !/m3u8/i.test(d.url + ct)) return;
  if (tab.media.some(m => m.url === d.url)) return;
  tab.media.unshift({ url: d.url, ref: d.referrer && d.referrer !== 'no-referrer' ? d.referrer : tab.wc.getURL(), kind: /m3u8|mpegurl/i.test(d.url + ct) ? 'HLS' : 'Video', size: len || 0 });
  tab.media.length = Math.min(tab.media.length, 15);
  queueTabs();
}

function setupSession() {
  const ses = session.defaultSession;
  const ua = app.userAgentFallback.replace(/\s?Electron\/\S+/, '').replace(new RegExp('\\s?' + app.getName().replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\/\\S+'), '');
  app.userAgentFallback = ua; ses.setUserAgent(ua);

  ses.webRequest.onBeforeRequest({ urls: ['http://*/*', 'https://*/*', 'ws://*/*', 'wss://*/*'] }, (d, cb) => {
    const tab = byWc.get(d.webContentsId);
    if (tab) { // oynatıcı sekmesinin kendi istekleri engellenmesin
      const tu = tab.wc.getURL();
      if (tu.startsWith('ghost://player')) return cb({});
      if (adblock.shouldBlock(d.url, d.resourceType, d.resourceType === 'mainFrame' ? '' : tu)) return cb({ cancel: true });
    } else if (adblock.shouldBlock(d.url, d.resourceType, '')) return cb({ cancel: true });
    cb({});
  });

  ses.webRequest.onBeforeSendHeaders({ urls: ['http://*/*', 'https://*/*'] }, (d, cb) => {
    const tab = byWc.get(d.webContentsId);
    if (tab && tab.playerRef && tab.wc.getURL().startsWith('ghost://player')) {
      const h = d.requestHeaders; h['Referer'] = tab.playerRef;
      try { h['Origin'] = new URL(tab.playerRef).origin; } catch { /* */ }
      return cb({ requestHeaders: h });
    }
    cb({ requestHeaders: d.requestHeaders });
  });

  ses.webRequest.onHeadersReceived({ urls: ['http://*/*', 'https://*/*'] }, (d, cb) => {
    const rh = d.responseHeaders || {};
    const get = (n) => { const k = Object.keys(rh).find(x => x.toLowerCase() === n); return k ? String(rh[k][0] || '') : ''; };
    sniffMedia(d, get('content-type'), parseInt(get('content-length'), 10) || 0);
    const tab = byWc.get(d.webContentsId);
    if (tab && tab.wc.getURL().startsWith('ghost://player')) { // oynatıcıya CORS izni (hls.js için)
      for (const k of Object.keys(rh)) if (/^access-control-/i.test(k)) delete rh[k];
      rh['Access-Control-Allow-Origin'] = ['*']; rh['Access-Control-Allow-Headers'] = ['*']; rh['Access-Control-Allow-Methods'] = ['GET, HEAD, OPTIONS'];
      return cb({ responseHeaders: rh });
    }
    cb({ responseHeaders: rh });
  });

  ses.setPermissionRequestHandler((_wc, perm, cb) => cb(['fullscreen', 'pointerLock', 'clipboard-sanitized-write', 'clipboard-read'].includes(perm)));

  protocol.handle('ghost', async (req) => {
    const u = new URL(req.url); let file;
    if (u.hostname === 'static') {
      file = path.normalize(path.join(PAGES, decodeURIComponent(u.pathname)));
      if (!file.startsWith(PAGES + path.sep)) return new Response('yasak', { status: 403 });
    } else if (['newtab', 'settings', 'player'].includes(u.hostname)) file = path.join(PAGES, u.hostname + '.html');
    else return new Response('yok', { status: 404 });
    return net.fetch(pathToFileURL(file).toString());
  });
}

// ================= ayarlar =================
function applySettings() {
  const s = settings.get();
  adblock.enabled = s.adblock; adblock.setCustom(s.customBlockHosts);
  ui.webContents.setZoomFactor(zoom());
  for (const t of tabs.values()) if (isInternal(t.wc.getURL())) t.wc.setZoomFactor(zoom());
  layout();
  sendUi('settings', s); sendGhostPages('settings-updated', s); queueTabs();
}
const ALLOWED = { searchEngine: 's', homepage: 's', adblock: 'b', popupBlock: 'b', cosmetic: 'b', filterListUrl: 's', customBlockHosts: 'a', tvMode: 'b', remoteEnabled: 'b', remotePort: 'n', cursorSpeed: 'n' };
function sanitize(patch) {
  const out = {};
  for (const [k, v] of Object.entries(patch || {})) {
    const t = ALLOWED[k]; if (!t) continue;
    if (t === 's' && typeof v === 'string') out[k] = v.slice(0, 500);
    else if (t === 'b') out[k] = !!v;
    else if (t === 'n' && Number.isFinite(+v)) out[k] = +v;
    else if (t === 'a' && Array.isArray(v)) out[k] = v.map(String).slice(0, 2000);
  }
  if (out.remotePort) out.remotePort = Math.min(65000, Math.max(1024, Math.round(out.remotePort)));
  if (out.cursorSpeed) out.cursorSpeed = Math.min(4, Math.max(0.3, out.cursorSpeed));
  return out;
}

// ================= kumanda =================
function remoteInfo() {
  return { enabled: settings.get().remoteEnabled, port: remote.port, token: settings.get().remoteToken,
    urls: remote.urls().map(u => ({ ...u, qr: qr.toRows(qr.encode(u.url)) })) };
}
async function startRemote() {
  const s = settings.get(); remote.stop();
  if (s.remoteEnabled) {
    try {
      await remote.start({
        port: s.remotePort, token: s.remoteToken, pagePath: path.join(PAGES, 'remote.html'),
        getState: buildRemoteState,
        getSites: () => mergedSites().map(x => ({ name: x.name, url: x.url, category: x.category })),
        onCommand: onRemoteCommand,
      });
    } catch (e) { console.error('Kumanda başlatılamadı:', e.message); }
  }
  sendUi('remote', remoteInfo()); sendGhostPages('remote-updated');
}
async function buildRemoteState() {
  const t = active();
  const media = t ? await mediaCmd(t, 'state').catch(() => null) : null;
  return {
    tabs: tabInfos().map(x => ({ id: x.id, title: x.title, active: x.active })), title: t?.title || '', url: displayUrl(t?.wc.getURL()),
    loading: !!t?.wc.isLoading(), canBack: !!t?.wc.navigationHistory.canGoBack(), canForward: !!t?.wc.navigationHistory.canGoForward(),
    mediaCount: t?.media.length || 0, media, tv: settings.get().tvMode, blocked: adblock.stats.blocked, isPlayer: !!t?.wc.getURL().startsWith('ghost://player'),
  };
}
async function onRemoteCommand(c) {
  const t = active(); const sp = settings.get().cursorSpeed;
  switch (c.type) {
    case 'open': { const v = String(c.value || '').slice(0, 2000); if (!v.trim()) return; if (c.newTab || !t) createTab(v); else navigate(t, v); return; }
    case 'tab': if (c.action === 'new') createTab('ghost://newtab'); else if (c.action === 'close') closeTab(c.id || activeId); else if (c.action === 'activate') activateTab(c.id); return;
    case 'nav': if (!t) return;
      if (c.action === 'back') t.wc.navigationHistory.goBack(); else if (c.action === 'forward') t.wc.navigationHistory.goForward();
      else if (c.action === 'reload') t.wc.reload(); else if (c.action === 'home') navigate(t, settings.get().homepage); return;
    case 'key': if (t) sendKey(t, String(c.key)); return;
    case 'move': if (t) moveCursor(t, (+c.dx || 0) * sp, (+c.dy || 0) * sp); return;
    case 'click': if (t) clickCursor(t); return;
    case 'scroll': if (t) scrollTab(t, +c.dy || 0); return;
    case 'text': if (t && typeof c.value === 'string') { t.wc.focus(); await t.wc.insertText(c.value.slice(0, 1000)); } return;
    case 'media': return { media: await mediaCmd(t, String(c.cmd), c.arg) };
    case 'player': if (t && t.media.length) openPlayerFor(t, t.media[Math.min(+c.index || 0, t.media.length - 1)]); return;
    case 'fullscreen': win.setFullScreen(!win.isFullScreen()); return;
    case 'zoom': if (t) t.wc.setZoomLevel(c.reset ? 0 : t.wc.getZoomLevel() + (+c.delta || 0)); return;
    default: throw new Error('bilinmeyen komut');
  }
}

// ================= IPC =================
function setupIpc() {
  const fromUi = (e) => e.sender === ui.webContents;
  const U = (name, fn) => ipcMain.handle('ui:' + name, (e, ...a) => { if (!fromUi(e)) throw new Error('yasak'); return fn(...a); });
  const US = (name, fn) => ipcMain.on('ui:' + name, (e, ...a) => { if (fromUi(e)) fn(...a); });

  U('init', () => ({ tabs: tabInfos(), active: activeId, settings: settings.get(), stats: adblock.summary(), remote: remoteInfo() }));
  U('suggest', (q) => suggest(String(q || '')));
  U('media', () => { const t = active(); return t ? t.media : []; });
  U('remote', () => remoteInfo());
  U('regen-token', async () => { settings.set({ remoteToken: require('crypto').randomBytes(6).toString('hex') }); await startRemote(); return remoteInfo(); });
  US('newtab', () => createTab('ghost://newtab'));
  US('close', (id) => closeTab(id));
  US('activate', (id) => activateTab(id));
  US('go', (text, nt) => { const t = active(); if (nt || !t) createTab(text); else navigate(t, text); });
  US('back', () => active()?.wc.navigationHistory.goBack());
  US('forward', () => active()?.wc.navigationHistory.goForward());
  US('reload', () => { const t = active(); if (!t) return; t.wc.isLoading() ? t.wc.stop() : t.wc.reload(); });
  US('home', () => { const t = active(); if (t) navigate(t, settings.get().homepage); });
  US('overlay', (open) => { uiOverlay = !!open; layout(); if (!open) active()?.wc.focus(); });
  US('menu', () => showMainMenu());
  US('settings', (sec) => openInternal('settings' + (sec ? '#' + sec : '')));
  US('open-media', (i) => { const t = active(); if (t && t.media[i]) openPlayerFor(t, t.media[i]); });
  US('toggle-tv', () => { settings.set({ tvMode: !settings.get().tvMode }); applySettings(); });

  const internal = (e) => (e.senderFrame?.url || '').startsWith('ghost://');
  const G = (name, fn) => ipcMain.handle('g:' + name, (e, ...a) => { if (!internal(e)) throw new Error('yasak'); return fn(...a); });
  G('settings', () => settings.get());
  G('set', async (patch) => {
    const prev = { ...settings.get() }; const clean = sanitize(patch); settings.set(clean);
    applySettings();
    if (clean.filterListUrl !== undefined && clean.filterListUrl !== prev.filterListUrl) refreshFilterList(true);
    if ((clean.remoteEnabled !== undefined && clean.remoteEnabled !== prev.remoteEnabled) || (clean.remotePort && clean.remotePort !== prev.remotePort)) await startRemote();
    return settings.get();
  });
  G('newtab', () => ({ sites: mergedSites(), recent: history.recent(12), top: history.top(8), tv: settings.get().tvMode, sources: sourcesStatus(), searchEngine: settings.get().searchEngine }));
  G('sites', () => ({ sites: mergedSites(), sources: sourcesStatus(), local: settings.get().localSites }));
  G('sites-refresh', async () => { await refreshSites(); return { sites: mergedSites(), sources: sourcesStatus() }; });
  G('source-add', async (url) => {
    url = String(url || '').trim(); if (!/^https?:\/\//i.test(url)) throw new Error('Geçerli bir https bağlantısı gir');
    const s = settings.get(); if (!s.sitesSources.includes(url)) settings.set({ sitesSources: [...s.sitesSources, url] });
    await refreshSites(); return { sources: sourcesStatus(), sites: mergedSites() };
  });
  G('source-remove', async (url) => { settings.set({ sitesSources: settings.get().sitesSources.filter(u => u !== url) }); await refreshSites(); return { sources: sourcesStatus(), sites: mergedSites() }; });
  G('local-add', (s) => {
    const { cleanSite } = require('./lib/sites'); const c = cleanSite(s); if (!c) throw new Error('Geçersiz adres');
    const list = settings.get().localSites.filter(x => x.url !== c.url); list.push(c); settings.set({ localSites: list });
    sendGhostPages('sites-updated'); remote.pushSoon(); return { local: list };
  });
  G('local-remove', (url) => { const list = settings.get().localSites.filter(x => x.url !== url); settings.set({ localSites: list }); sendGhostPages('sites-updated'); remote.pushSoon(); return { local: list }; });
  G('history', (q, limit) => history.recent(Math.min(+limit || 100, 500), String(q || '')));
  G('history-clear', () => { history.clear(); return true; });
  G('history-remove', (u) => { history.remove(u); return true; });
  G('open', (url, o) => { const t = active(); if (o?.newTab || !t) createTab(url); else navigate(t, url); });
  G('adblock', () => adblock.summary());
  G('adblock-refresh', async () => { await refreshFilterList(true); return adblock.summary(); });
  G('remote', () => remoteInfo());
  G('remote-regen', async () => { settings.set({ remoteToken: require('crypto').randomBytes(6).toString('hex') }); await startRemote(); return remoteInfo(); });
  G('copy', (text) => { clipboard.writeText(String(text)); return true; });

  ipcMain.on('gesture', (e) => { const t = byWc.get(e.sender.id); if (t) t.lastGesture = Date.now(); });
}

function suggest(q) {
  q = q.trim().toLowerCase(); const out = [];
  if (!q) return history.top(6).map(h => ({ type: 'history', title: h.title, url: h.url }));
  for (const s of mergedSites()) if (s.name.toLowerCase().includes(q) || s.url.toLowerCase().includes(q)) out.push({ type: 'site', title: s.name, url: s.url });
  for (const h of history.recent(60, q)) if (!out.some(o => o.url === h.url)) out.push({ type: 'history', title: h.title, url: h.url });
  return out.slice(0, 7);
}

// ================= başlat =================
app.on('second-instance', (_e, argv) => {
  if (win) { if (win.isMinimized()) win.restore(); win.focus(); }
  const u = argv.find(a => /^https?:\/\//i.test(a)); if (u) createTab(u);
});
app.on('window-all-closed', () => app.quit());
app.on('before-quit', () => { remote.stop(); history.save(true); settings.save(true); });

app.whenReady().then(async () => {
  const dir = app.getPath('userData');
  settings = new Settings(path.join(dir, 'settings.json'));
  history = new History(path.join(dir, 'history.json'));
  try { defaultSites = parseSites(JSON.parse(fs.readFileSync(path.join(__dirname, 'sites.default.json'), 'utf8'))).sites; } catch { defaultSites = []; }
  loadSitesCache();
  adblock.enabled = settings.get().adblock; adblock.setCustom(settings.get().customBlockHosts);
  setupSession();

  win = new BaseWindow({ width: 1360, height: 840, minWidth: 820, minHeight: 520, backgroundColor: '#0b0b10', title: 'Ghost Browser', autoHideMenuBar: true });
  Menu.setApplicationMenu(Menu.buildFromTemplate([{ role: 'editMenu' }]));
  ui = new WebContentsView({ webPreferences: { preload: path.join(__dirname, 'preload-ui.js'), sandbox: true, contextIsolation: true } });
  ui.setBackgroundColor('#00000000');
  win.contentView.addChildView(ui);
  ui.webContents.on('before-input-event', (e, input) => handleKey({ wc: ui.webContents }, e, input));
  win.on('resize', layout); win.on('enter-full-screen', layout); win.on('leave-full-screen', layout);
  win.on('closed', () => { win = null; });
  setupIpc();
  await ui.webContents.loadFile(path.join(PAGES, 'ui.html'));
  ui.webContents.setZoomFactor(zoom());

  const argUrl = process.argv.slice(1).find(a => /^https?:\/\//i.test(a));
  createTab(argUrl || settings.get().homepage || 'ghost://newtab');
  layout();

  setInterval(() => { sendUi('stats', adblock.summary()); }, 2000);
  startRemote();
  refreshSites(); setInterval(refreshSites, 30 * 60 * 1000);
  refreshFilterList();
});
