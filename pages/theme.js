/* Ghost tema sistemi: 200 tema. localStorage 'gbTheme' (tüm sayfalarda ortak). */
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
  for (const [id, name, h, s] of L) add(id, name, [hsl(h, 30, 97), hsl(h, 28, 94), hsl(h, 26, 90), hsl(h, 22, 82), hsl(h, 30, 12), hsl(h, 14, 38), hsl(h, Math.min(s, 55), 40), hsl(h + 8, Math.min(s, 50), 46)], { light: true });
  // 80 koyu tema: sakin, doğa/toprak tonları. Her ailede koyu + orta koyu; vurgu ve ikinci renk birbirine yakın.
  const E = [['Deve Tüyü', 34, 32], ['Mint Yeşili', 150, 28], ['Askeri Yeşil', 88, 24], ['Yeşim Taşı', 160, 30], ['Defne Yaprağı', 105, 22], ['Zeytin', 70, 26], ['Adaçayı', 120, 16], ['Kum', 40, 24], ['Toprak', 25, 26], ['Kahve', 22, 32],
    ['Çikolata', 15, 30], ['Bakır', 18, 38], ['Pas', 12, 36], ['Kiremit', 10, 32], ['Bordo', 350, 34], ['Şarap', 340, 28], ['Mürdüm', 300, 22], ['Lavanta Grisi', 265, 16], ['Duman', 220, 8], ['Kömür', 210, 6],
    ['Çelik', 210, 16], ['Petrol', 195, 30], ['Deniz Mavisi', 200, 32], ['Gece Mavisi', 225, 30], ['Buz Mavisi', 200, 18], ['Çam Yeşili', 140, 28], ['Orman', 130, 26], ['Ceviz', 28, 22], ['Taş', 40, 10], ['Kurşun', 220, 10],
    ['Bulut', 210, 12], ['Hardal', 48, 36], ['Amber', 38, 38], ['Gül Kurusu', 345, 22], ['Mercan', 8, 30], ['Kaktüs', 110, 20], ['Okaliptüs', 165, 18], ['Turkuaz', 180, 28], ['Antrasit', 215, 9], ['Fındık', 30, 28]];
  E.forEach(([name, h, s], i) => {
    [['Koyu', [5, 8, 12, 19], .5], ['Orta', [12, 16, 21, 29], .5]].forEach(([vn, L, k], vi) => {
      if (i === E.length - 1 && vi === 1) return; // Ghost için yer
      add('e' + i + (vi ? 'o' : 'k'), name + ' ' + vn, [hsl(h, s * k, L[0]), hsl(h, s * k, L[1]), hsl(h, s * k * .9, L[2]), hsl(h, s * k * .8, L[3]), hsl(h, s * .4, 92), hsl(h, s * .3, 62), hsl(h, Math.min(s + 22, 60), 60), hsl(h + 8, Math.min(s + 14, 52), 68)]);
    });
  });
  // 50 orta-koyu düz renkli tema + 50 ek düz tema (gradyan yok)
  const S1 = ['Kadife', 'Akik', 'Obsidyen', 'Kobalt', 'Safir', 'Zümrüt', 'Rubi', 'Ametist', 'Topaz', 'Opal', 'Garnet', 'Turmalin', 'Lapis', 'Malakit', 'Granit', 'Basalt', 'Arduvaz', 'Mermer', 'Kuvars', 'Oniks', 'Fırtına', 'Sis', 'Pus', 'Alacakaranlık', 'Şafak', 'Gölge', 'Dolunay', 'Yıldız Tozu', 'Kor', 'Köz', 'Lagün', 'Körfez', 'Fiyort', 'Vaha', 'Bataklık', 'Çayır', 'Eğrelti', 'Yosun', 'Süngertaşı', 'Kuzey', 'Orkide', 'Frenk Üzümü', 'Erik', 'Böğürtlen', 'Kiraz', 'Nar', 'Kayısı', 'İncir', 'Badem', 'Kestane'];
  S1.forEach((name, i) => { const h = (i * 7.2 + 12) % 360, s = 22 + (i % 5) * 5; add('m' + i, name, [hsl(h, s, 17), hsl(h, s, 21), hsl(h, s, 26), hsl(h, s * .9, 35), hsl(h, 22, 93), hsl(h, 14, 68), hsl(h + 6, Math.min(s + 38, 68), 66), hsl(h + 14, Math.min(s + 30, 60), 72)]); });
  const S2 = ['Polar', 'Tundra', 'Step', 'Savan', 'Sahra', 'Kanyon', 'Mesa', 'Vadi', 'Yayla', 'Dağ Evi', 'Liman', 'Fener', 'İskele', 'Dalga', 'Mercan Kayalığı', 'Dere', 'Şelale', 'Göl', 'Nehir', 'Buğday', 'Lavanta Tarlası', 'Gül Bahçesi', 'Çay Bahçesi', 'Bağ', 'Zeytinlik', 'Fıstık', 'Ceylan', 'Geyik', 'Kurt', 'Ayı', 'Baykuş', 'Kartal', 'Yunus', 'Kaplan', 'Panter', 'Tilki', 'Karga', 'Leylek', 'Pelikan', 'Flamingo', 'Neon Gece', 'Retro', 'Synthwave', 'Terminal', 'Matrix', 'Piksel', 'Devre', 'Radar', 'Sonar', 'Kokpit'];
  S2.forEach((name, i) => { const h = (i * 7.2 + 200) % 360, s = 14 + (i % 6) * 6, l0 = 9 + (i % 4) * 3; add('x' + i, name, [hsl(h, s, l0), hsl(h, s, l0 + 4), hsl(h, s, l0 + 9), hsl(h, s * .9, l0 + 18), hsl(h, 20, 92), hsl(h, 12, 64), hsl(h + 170 * (i % 2), Math.min(s + 45, 72), 62), hsl(h + 30 + 170 * (i % 2), Math.min(s + 35, 62), 70)]); });
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
