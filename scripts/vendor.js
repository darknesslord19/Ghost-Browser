// npm install sonrası hls.js'i oynatıcının yerel klasörüne kopyalar (offline çalışsın diye).
const fs = require('fs'), path = require('path');
try {
  const src = require.resolve('hls.js/dist/hls.min.js');
  const dst = path.join(__dirname, '..', 'pages', 'vendor');
  fs.mkdirSync(dst, { recursive: true });
  fs.copyFileSync(src, path.join(dst, 'hls.min.js'));
  console.log('[vendor] hls.min.js kopyalandı');
} catch (e) { console.warn('[vendor] hls.js bulunamadı, oynatıcı CDN yedeğini kullanacak:', e.message); }
