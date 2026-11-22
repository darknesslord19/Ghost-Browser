/* Ghost tema sistemi: 100 tema. localStorage 'gbTheme' (tüm sayfalarda ortak). */
(function () {
  function hsl(h, s, l) { h = ((h % 360) + 360) % 360; s /= 100; l /= 100; const k = n => (n + h / 30) % 12, a = s * Math.min(l, 1 - l), f = n => l - a * Math.max(-1, Math.min(k(n) - 3, Math.min(9 - k(n), 1))); const x = v => Math.round(v * 255).toString(16).padStart(2, '0'); return '#' + x(f(0)) + x(f(8)) + x(f(4)); }
  const T = [];
  const add = (id, name, bg, bg2, bg3, line, txt, dim, acc, acc2) => T.push({ id, name, bg, bg2, bg3, line, txt, dim, acc, acc2 });
  add('ghost', 'Ghost', '#0b0b10', '#14141d', '#1d1d2a', '#2a2a3b', '#ececf4', '#9a9ab2', '#8b5cf6', '#ec4899');
  add('monochrome', 'Monochrome', '#000000', '#0f0f0f', '#1a1a1a', '#2b2b2b', '#f2f2f2', '#9a9a9a', '#ffffff', '#9a9a9a');
  add('mocha', 'Mocha', '#140d08', '#1e150e', '#2a1d13', '#3b2a1b', '#f1e6d8', '#b09a80', '#e0a458', '#c4793a');
  add('everforest', 'Everforest', '#0f140c', '#161d11', '#1f2918', '#2d3a22', '#e4ead6', '#9aa886', '#a7c957', '#6a994e');
  add('ocean-abyss', 'Ocean Abyss', '#03080d', '#08121b', '#0d1c29', '#16303f', '#e3f4fa', '#7fa3b3', '#22d3ee', '#2563eb');
  const H = [['Ruby', 350], ['Coral', 10], ['Amber', 35], ['Gold', 48], ['Lime', 80], ['Emerald', 145], ['Mint', 162], ['Teal', 178], ['Cyan', 190], ['Sky', 205], ['Azure', 216], ['Sapphire', 226], ['Indigo', 246], ['Violet', 266], ['Orchid', 286], ['Magenta', 310], ['Rose', 334], ['Crimson', 0], ['Copper', 22]];
  const V = [
    ['Night', h => [hsl(h, 35, 6), hsl(h, 30, 9), hsl(h, 28, 13), hsl(h, 25, 19), hsl(h, 85, 62), hsl(h + 40, 85, 62)]],
    ['Deep', h => [hsl(h, 45, 3.5), hsl(h, 40, 6.5), hsl(h, 36, 10), hsl(h, 32, 16), hsl(h, 80, 58), hsl(h - 25, 85, 60)]],
    ['Neon', h => [hsl(h, 30, 4.5), hsl(h, 30, 8), hsl(h, 30, 12), hsl(h, 40, 20), hsl(h, 100, 60), hsl(h + 160, 100, 62)]],
    ['Dusk', h => [hsl(h + 10, 22, 10), hsl(h + 10, 20, 14), hsl(h + 10, 19, 19), hsl(h + 10, 18, 26), hsl(h, 60, 68), hsl(h - 30, 70, 68)]],
    ['Glow', h => [hsl(h, 20, 7), hsl(h, 20, 11), hsl(h, 20, 15), hsl(h, 22, 22), hsl(h, 90, 70), hsl(h + 25, 95, 66)]]
  ];
  for (const [hn, h] of H) for (const [vn, fn] of V) { const c = fn(h); add((hn + '-' + vn).toLowerCase(), hn + ' ' + vn, c[0], c[1], c[2], c[3], hsl(h, 20, 94), hsl(h, 12, 66), c[4], c[5]); }
  const get = () => { try { return localStorage.getItem('gbTheme') || 'ghost'; } catch (e) { return 'ghost'; } };
  function apply(id) {
    const t = T.find(x => x.id === id) || T[0], r = document.documentElement.style;
    r.setProperty('--bg', t.bg); r.setProperty('--bg2', t.bg2); r.setProperty('--bg3', t.bg3); r.setProperty('--line', t.line);
    r.setProperty('--txt', t.txt); r.setProperty('--dim', t.dim); r.setProperty('--acc', t.acc); r.setProperty('--acc2', t.acc2);
    r.setProperty('--grad', 'linear-gradient(135deg,' + t.acc + ',' + t.acc2 + ')');
    return t;
  }
  window.GBTheme = { list: T, id: get, current: () => T.find(x => x.id === get()) || T[0], apply, set(id) { try { localStorage.setItem('gbTheme', id); } catch (e) {} return apply(id); } };
  apply(get());
})();
