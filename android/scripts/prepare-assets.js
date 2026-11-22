// Electron sayfalarını Android assets klasörüne uyarlayarak kopyalar.
// Kullanım: node android/scripts/prepare-assets.js [hls.min.js yolu]
const fs = require('fs'), path = require('path');
const root = path.join(__dirname, '..', '..');
const out = path.join(__dirname, '..', 'app', 'src', 'main', 'assets');
fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(path.join(out, 'vendor'), { recursive: true });

const read = (...p) => fs.readFileSync(path.join(root, ...p), 'utf8');
const write = (name, data) => fs.writeFileSync(path.join(out, name), data);

for (const f of ['newtab', 'settings', 'player']) {
  let html = read('pages', f + '.html').replace(/ghost:\/\/static\//g, '');
  if (!html.includes('<head>')) throw new Error(f + ': <head> bulunamadı');
  html = html.replace('<head>', '<head><script src="qr-browser.js"></script><script src="android-shim.js"></script>');
  write(f + '.html', html);
}
write('remote.html', read('pages', 'remote.html'));
write('shared.css', read('pages', 'shared.css'));
fs.copyFileSync(path.join(root, 'pages', 'logo.png'), path.join(out, 'logo.png'));
write('theme.js', read('pages', 'theme.js'));
write('spatial.js', read('pages', 'spatial.js'));
write('android-shim.js', fs.readFileSync(path.join(__dirname, '..', 'shim', 'android-shim.js'), 'utf8'));
write('qr-browser.js', '(function(){const module={exports:{}};\n' + read('lib', 'qr.js') + '\n;window.GhostQR=module.exports;})();');
write('sites.default.json', read('sites.default.json'));
const { BUILTIN, COSMETIC_CSS } = require(path.join(root, 'lib', 'adblock.js'));
write('builtin-hosts.txt', [...new Set(BUILTIN)].join('\n'));
write('cosmetic.css', COSMETIC_CSS);

const hlsSrc = process.argv[2] || path.join(root, 'pages', 'vendor', 'hls.min.js');
if (fs.existsSync(hlsSrc)) fs.copyFileSync(hlsSrc, path.join(out, 'vendor', 'hls.min.js'));
else console.warn('UYARI: hls.min.js bulunamadı — HLS oynatma CDN yedeğine düşer');
console.log('Android assets hazır:', fs.readdirSync(out).join(', '));
