'use strict';
const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('ui', {
  invoke: (name, ...a) => ipcRenderer.invoke('ui:' + name, ...a),
  send: (name, ...a) => ipcRenderer.send('ui:' + name, ...a),
  on: (ev, cb) => ipcRenderer.on('ui-ev:' + ev, (_e, ...a) => cb(...a)),
});
