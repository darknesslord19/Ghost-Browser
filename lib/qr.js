'use strict';
// Bağımlılıksız minimal QR üretici: byte modu, hata düzeltme M, sürüm 1-10 (~210 bayta kadar).

const VERSIONS = { // ec = blok başına EC kelimesi, blocks = [blokSayısı, veriKelimesi]
  1: { ec: 10, blocks: [[1, 16]] },
  2: { ec: 16, blocks: [[1, 28]] },
  3: { ec: 26, blocks: [[1, 44]] },
  4: { ec: 18, blocks: [[2, 32]] },
  5: { ec: 24, blocks: [[2, 43]] },
  6: { ec: 16, blocks: [[4, 27]] },
  7: { ec: 18, blocks: [[4, 31]] },
  8: { ec: 22, blocks: [[2, 38], [2, 39]] },
  9: { ec: 22, blocks: [[3, 36], [2, 37]] },
  10: { ec: 26, blocks: [[4, 43], [1, 44]] },
};
const ALIGN = { 1: [], 2: [6, 18], 3: [6, 22], 4: [6, 26], 5: [6, 30], 6: [6, 34], 7: [6, 22, 38], 8: [6, 24, 42], 9: [6, 26, 46], 10: [6, 28, 50] };

function gfMul(x, y) {
  let z = 0;
  for (let i = 7; i >= 0; i--) { z = (z << 1) ^ ((z >>> 7) * 0x11d); z ^= ((y >>> i) & 1) * x; }
  return z & 0xff;
}
function rsDivisor(degree) {
  const r = new Array(degree).fill(0); r[degree - 1] = 1;
  let root = 1;
  for (let i = 0; i < degree; i++) {
    for (let j = 0; j < r.length; j++) { r[j] = gfMul(r[j], root); if (j + 1 < r.length) r[j] ^= r[j + 1]; }
    root = gfMul(root, 2);
  }
  return r;
}
function rsRemainder(data, divisor) {
  const r = new Array(divisor.length).fill(0);
  for (const b of data) {
    const f = b ^ r.shift(); r.push(0);
    divisor.forEach((c, i) => { r[i] ^= gfMul(c, f); });
  }
  return r;
}
const bit = (x, i) => ((x >>> i) & 1) !== 0;

function encode(text) {
  const bytes = Array.from(typeof Buffer !== 'undefined' ? Buffer.from(String(text), 'utf8') : new TextEncoder().encode(String(text)));
  let ver = 0, dataCw = 0;
  for (let v = 1; v <= 10; v++) {
    const total = VERSIONS[v].blocks.reduce((a, [n, d]) => a + n * d, 0);
    const need = 4 + (v < 10 ? 8 : 16) + bytes.length * 8;
    if (need <= total * 8) { ver = v; dataCw = total; break; }
  }
  if (!ver) throw new Error('QR için metin çok uzun');

  // bit tamponu
  const bits = [];
  const put = (val, len) => { for (let i = len - 1; i >= 0; i--) bits.push((val >>> i) & 1); };
  put(0b0100, 4); put(bytes.length, ver < 10 ? 8 : 16);
  bytes.forEach(b => put(b, 8));
  put(0, Math.min(4, dataCw * 8 - bits.length));
  while (bits.length % 8) bits.push(0);
  for (let pad = 0xec; bits.length < dataCw * 8; pad ^= 0xec ^ 0x11) put(pad, 8);
  const data = [];
  for (let i = 0; i < bits.length; i += 8) { let b = 0; for (let j = 0; j < 8; j++) b = (b << 1) | bits[i + j]; data.push(b); }

  // bloklara böl + RS
  const { ec, blocks: spec } = VERSIONS[ver];
  const div = rsDivisor(ec);
  const blocks = []; let off = 0;
  for (const [n, d] of spec) for (let i = 0; i < n; i++) {
    const chunk = data.slice(off, off + d); off += d;
    blocks.push({ data: chunk, ec: rsRemainder(chunk, div) });
  }
  const maxD = Math.max(...blocks.map(b => b.data.length));
  const final = [];
  for (let i = 0; i < maxD; i++) blocks.forEach(b => { if (i < b.data.length) final.push(b.data[i]); });
  for (let i = 0; i < ec; i++) blocks.forEach(b => final.push(b.ec[i]));

  return build(ver, final);
}

function build(ver, codewords) {
  const size = 17 + 4 * ver;
  const m = Array.from({ length: size }, () => new Array(size).fill(false));
  const fn = Array.from({ length: size }, () => new Array(size).fill(false));
  const setFn = (x, y, d) => { m[y][x] = d; fn[y][x] = true; };

  for (let i = 0; i < size; i++) { setFn(6, i, i % 2 === 0); setFn(i, 6, i % 2 === 0); }
  const finder = (cx, cy) => {
    for (let dy = -4; dy <= 4; dy++) for (let dx = -4; dx <= 4; dx++) {
      const d = Math.max(Math.abs(dx), Math.abs(dy)), x = cx + dx, y = cy + dy;
      if (x >= 0 && x < size && y >= 0 && y < size) setFn(x, y, d !== 2 && d !== 4);
    }
  };
  finder(3, 3); finder(size - 4, 3); finder(3, size - 4);
  const ap = ALIGN[ver];
  for (let i = 0; i < ap.length; i++) for (let j = 0; j < ap.length; j++) {
    if ((i === 0 && j === 0) || (i === 0 && j === ap.length - 1) || (i === ap.length - 1 && j === 0)) continue;
    for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) setFn(ap[i] + dx, ap[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
  }
  const drawFormat = (mask) => {
    const d = (0 << 3) | mask; // EC=M -> 00
    let rem = d;
    for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
    const bits = ((d << 10) | rem) ^ 0x5412;
    for (let i = 0; i <= 5; i++) setFn(8, i, bit(bits, i));
    setFn(8, 7, bit(bits, 6)); setFn(8, 8, bit(bits, 7)); setFn(7, 8, bit(bits, 8));
    for (let i = 9; i < 15; i++) setFn(14 - i, 8, bit(bits, i));
    for (let i = 0; i < 8; i++) setFn(size - 1 - i, 8, bit(bits, i));
    for (let i = 8; i < 15; i++) setFn(8, size - 15 + i, bit(bits, i));
    setFn(8, size - 8, true);
  };
  drawFormat(0);
  if (ver >= 7) {
    let rem = ver;
    for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25);
    const bits = (ver << 12) | rem;
    for (let i = 0; i < 18; i++) {
      const a = size - 11 + (i % 3), b = Math.floor(i / 3);
      setFn(a, b, bit(bits, i)); setFn(b, a, bit(bits, i));
    }
  }
  // veri yerleşimi
  let k = 0;
  for (let right = size - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5;
    for (let vert = 0; vert < size; vert++) for (let j = 0; j < 2; j++) {
      const x = right - j, upward = ((right + 1) & 2) === 0, y = upward ? size - 1 - vert : vert;
      if (!fn[y][x] && k < codewords.length * 8) { m[y][x] = bit(codewords[k >>> 3], 7 - (k & 7)); k++; }
    }
  }
  const applyMask = (mask) => {
    for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
      let inv;
      switch (mask) {
        case 0: inv = (x + y) % 2 === 0; break;
        case 1: inv = y % 2 === 0; break;
        case 2: inv = x % 3 === 0; break;
        case 3: inv = (x + y) % 3 === 0; break;
        case 4: inv = (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0; break;
        case 5: inv = ((x * y) % 2) + ((x * y) % 3) === 0; break;
        case 6: inv = (((x * y) % 2) + ((x * y) % 3)) % 2 === 0; break;
        default: inv = (((x + y) % 2) + ((x * y) % 3)) % 2 === 0;
      }
      if (!fn[y][x] && inv) m[y][x] = !m[y][x];
    }
  };
  const penalty = () => {
    let p = 0;
    const lines = [];
    for (let y = 0; y < size; y++) lines.push(m[y].map(Number));
    for (let x = 0; x < size; x++) lines.push(m.map(r => Number(r[x])));
    for (const ln of lines) {
      let run = 1;
      for (let i = 1; i <= size; i++) {
        if (i < size && ln[i] === ln[i - 1]) run++; else { if (run >= 5) p += 3 + (run - 5); run = 1; }
      }
      const s = ln.join('');
      for (const pat of ['10111010000', '00001011101']) { let idx = -1; while ((idx = s.indexOf(pat, idx + 1)) >= 0) p += 40; }
    }
    for (let y = 0; y < size - 1; y++) for (let x = 0; x < size - 1; x++) {
      const c = m[y][x]; if (c === m[y][x + 1] && c === m[y + 1][x] && c === m[y + 1][x + 1]) p += 3;
    }
    let dark = 0; m.forEach(r => r.forEach(c => { if (c) dark++; }));
    const total = size * size;
    p += (Math.ceil(Math.abs(dark * 20 - total * 10) / total) - 1) * 10;
    return p;
  };
  let best = 0, bestP = Infinity;
  for (let mask = 0; mask < 8; mask++) {
    applyMask(mask); drawFormat(mask);
    const p = penalty();
    if (p < bestP) { bestP = p; best = mask; }
    applyMask(mask);
  }
  applyMask(best); drawFormat(best);
  return { size, version: ver, modules: m };
}

/** Matrisi '0'/'1' satırlarına çevirir (IPC / JSON için küçük). */
function toRows(qr) { return qr.modules.map(r => r.map(c => (c ? '1' : '0')).join('')); }

function toSvg(qr, border = 4, dark = '#000', light = '#fff') {
  const n = qr.size + border * 2; let d = '';
  qr.modules.forEach((row, y) => row.forEach((c, x) => { if (c) d += `M${x + border},${y + border}h1v1h-1z`; }));
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${n} ${n}" shape-rendering="crispEdges"><rect width="100%" height="100%" fill="${light}"/><path d="${d}" fill="${dark}"/></svg>`;
}

module.exports = { encode, toRows, toSvg };
