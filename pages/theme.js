/* Ghost tema sistemi: 100 tema. localStorage 'gbTheme' (tüm sayfalarda ortak). */
(function () {
  function hsl(h, s, l) { h = ((h % 360) + 360) % 360; s /= 100; l /= 100; const k = n => (n + h / 30) % 12, a = s * Math.min(l, 1 - l), f = n => l - a * Math.max(-1, Math.min(k(n) - 3, Math.min(9 - k(n), 1))); const x = v => Math.round(v * 255).toString(16).padStart(2, '0'); return '#' + x(f(0)) + x(f(8)) + x(f(4)); }
  const T = [];
  const add = (id, name, c, extra) => T.push(Object.assign({ id, name, bg: c[0], bg2: c[1], bg3: c[2], line: c[3], txt: c[4], dim: c[5], acc: c[6], acc2: c[7], light: false }, extra || {}));
  add('ghost', 'Ghost', ['#0b0b10', '#14141d', '#1d1d2a', '#2a2a3b', '#ececf4', '#9a9ab2', '#8b5cf6', '#ec4899']);
  // 10 geçişli (gradyan) tema
  const G = [
    ['aurora', 'Aurora', 175, 280, '#04121a', '#2dd4bf', '#a855f7'],
    ['sunset-strip', 'Sunset Strip', 20, 330, '#1a0a12', '#fb923c', '#ec4899'],
    ['deep-ocean', 'Deep Ocean', 200, 240, '#020b1c', '#22d3ee', '#3b82f6'],
    ['miami-neon', 'Miami Neon', 310, 190, '#0d0620', '#f472b6', '#22d3ee'],
    ['cotton-candy', 'Cotton Candy', 330, 200, '#160b1f', '#f9a8d4', '#93c5fd'],
    ['lava-flow', 'Lava Flow', 5, 40, '#1a0605', '#ef4444', '#f59e0b'],
    ['galaxy', 'Galaxy', 260, 320, '#0a0618', '#818cf8', '#e879f9'],
    ['fire-ice', 'Fire & Ice', 8, 200, '#0a0f1a', '#f97316', '#38bdf8'],
    ['cyberpunk', 'Cyberpunk', 55, 300, '#0f0a1f', '#facc15', '#d946ef'],
    ['emerald-gold', 'Emerald Gold', 150, 35, '#04140e', '#34d399', '#fbbf24']
  ];
  for (const [id, name, h1, h2, base, a1, a2] of G) add(id, name, [hsl(h1, 55, 6), hsl(h1, 48, 10), hsl(h2, 40, 15), hsl(h2, 38, 26), '#f4f4fb', '#b0b0c8', a1, a2], { bgimg: 'linear-gradient(160deg,' + hsl(h1, 60, 8) + ' 0%,' + hsl((h1 + h2) / 2, 55, 11) + ' 50%,' + hsl(h2, 58, 10) + ' 100%)' });
  // 10 açık tema
  const L = [['snow', 'Snow', 220, 90], ['pearl', 'Pearl', 40, 70], ['linen', 'Linen', 30, 60], ['mint-cream', 'Mint Cream', 150, 55], ['sky-light', 'Sky Light', 205, 85], ['blush', 'Blush', 345, 80], ['lavender-mist', 'Lavender Mist', 265, 75], ['sand', 'Sand', 38, 65], ['peach', 'Peach', 18, 85], ['sage', 'Sage', 105, 40]];
  for (const [id, name, h, s] of L) add(id, name, [hsl(h, 30, 97), hsl(h, 28, 94), hsl(h, 26, 90), hsl(h, 22, 82), hsl(h, 30, 12), hsl(h, 14, 38), hsl(h, s, 42), hsl(h + 40, s, 48)], { light: true });
  // 80 koyu tema: 40 koyu dark + 40 orta dark
  const H = [['Ruby', 350], ['Coral', 10], ['Amber', 35], ['Gold', 48], ['Lime', 80], ['Emerald', 145], ['Mint', 162], ['Teal', 178], ['Cyan', 190], ['Sky', 205], ['Azure', 216], ['Sapphire', 226], ['Indigo', 246], ['Violet', 266], ['Orchid', 286], ['Magenta', 310], ['Rose', 334], ['Crimson', 0], ['Copper', 22], ['Slate', 215]];
  const V = [
    ['Onyx', h => [hsl(h, 14, 3.5), hsl(h, 14, 6), hsl(h, 14, 9.5), hsl(h, 12, 15), hsl(h, 80, 62), hsl(h + 30, 80, 62)], 'koyu'],
    ['Abyss', h => [hsl(h, 30, 2.5), hsl(h, 30, 5), hsl(h, 28, 8), hsl(h, 26, 13), hsl(h, 90, 58), hsl(h - 25, 90, 60)], 'koyu'],
    ['Dusk', h => [hsl(h, 22, 11), hsl(h, 22, 15), hsl(h, 20, 20), hsl(h, 18, 28), hsl(h, 75, 66), hsl(h + 35, 80, 66)], 'orta'],
    ['Smoke', h => [hsl(h, 12, 14), hsl(h, 12, 18), hsl(h, 11, 23), hsl(h, 10, 31), hsl(h, 70, 68), hsl(h - 30, 75, 68)], 'orta']
  ];
  for (const [hn, h] of H) for (const [vn, fn] of V) {
    if (hn === 'Violet' && vn === 'Dusk') continue; // Ghost bunun yerine
    const c = fn(h); add((hn + '-' + vn).toLowerCase(), hn + ' ' + vn, [c[0], c[1], c[2], c[3], hsl(h, 14, 93), hsl(h, 10, 64), c[4], c[5]]);
  }
  const get = () => { try { return localStorage.getItem('gbTheme') || 'ghost'; } catch (e) { return 'ghost'; } };
  function apply(id) {
    const t = T.find(x => x.id === id) || T[0], r = document.documentElement.style;
    r.setProperty('--bg', t.bg); r.setProperty('--bg2', t.bg2); r.setProperty('--bg3', t.bg3); r.setProperty('--line', t.line);
    r.setProperty('--txt', t.txt); r.setProperty('--dim', t.dim); r.setProperty('--acc', t.acc); r.setProperty('--acc2', t.acc2);
    r.setProperty('--grad', 'linear-gradient(135deg,' + t.acc + ',' + t.acc2 + ')');
    if (t.bgimg) r.setProperty('--bgimg', t.bgimg); else r.removeProperty('--bgimg');
    r.setProperty('color-scheme', t.light ? 'light' : 'dark'); r.setProperty('--on', t.light ? '#000' : '#fff'); r.setProperty('--ovr', t.light ? '0,0,0' : '255,255,255');
    return t;
  }
  window.GBTheme = { list: T, id: get, current: () => T.find(x => x.id === get()) || T[0], apply, set(id) { try { localStorage.setItem('gbTheme', id); } catch (e) {} return apply(id); } };
  apply(get());
})();
