'use strict';
// Her sekmede çalışır. Sayfa API'si (ghost.*) yalnızca ghost:// iç sayfalarına verilir.
const { contextBridge, ipcRenderer } = require('electron');

// Kullanıcı hareketi takibi: kullanıcı tıklamadan açılan pop-up'ları engellemek için.
for (const ev of ['pointerdown', 'keydown', 'touchstart']) {
  window.addEventListener(ev, () => ipcRenderer.send('gesture'), true);
}

if (location.protocol === 'ghost:') {
  contextBridge.exposeInMainWorld('ghost', {
    invoke: (name, ...a) => ipcRenderer.invoke('g:' + name, ...a),
    on: (ev, cb) => ipcRenderer.on('g-ev:' + ev, (_e, ...a) => cb(...a)),
  });
}
