// Electron iç sayfalarının kullandığı `ghost` API'sini Android köprüsüne (GhostNative) bağlar.
(function () {
  const native = window.GhostNative;
  const pending = new Map();
  const handlers = {};
  let seq = 0;
  const emit = (ev, ...a) => (handlers[ev] || []).forEach((f) => { try { f(...a); } catch (e) { /* */ } });
  if (native) {
    native.onmessage = (e) => {
      let m; try { m = JSON.parse(e.data); } catch (x) { return; }
      const p = pending.get(m.id); if (!p) return; pending.delete(m.id);
      m.ok ? p.res(m.result) : p.rej(new Error(m.error || 'hata'));
    };
  }
  const sitesEv = new Set(['sites-refresh', 'source-add', 'source-remove', 'local-add', 'local-remove']);
  window.ghost = {
    invoke(name, ...args) {
      return new Promise((res, rej) => {
        if (!native) return rej(new Error('Köprü kullanılamıyor (WebView çok eski)'));
        const id = ++seq; pending.set(id, { res, rej });
        native.postMessage(JSON.stringify({ id, name, args }));
      }).then((r) => {
        if ((name === 'remote' || name === 'remote-regen') && r && r.urls && window.GhostQR) {
          r.urls.forEach((u) => { try { u.qr = GhostQR.toRows(GhostQR.encode(u.url)); } catch (e) { u.qr = []; } });
        }
        if (sitesEv.has(name)) setTimeout(() => emit('sites-updated'), 0);
        if (name === 'remote-regen' || (name === 'set' && args[0] && ('remoteEnabled' in args[0] || 'remotePort' in args[0]))) setTimeout(() => emit('remote-updated'), 600);
        return r;
      });
    },
    on(ev, cb) { (handlers[ev] = handlers[ev] || []).push(cb); },
  };
})();
