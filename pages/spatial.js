// Ok tuşlarıyla (TV kumandası / klavye) odak gezintisi. <body data-spatial> olan sayfalarda çalışır.
(() => {
  if (!document.body.hasAttribute('data-spatial')) return;
  const SEL = 'a[href], button:not([disabled]), input:not([type=hidden]), select, textarea, [tabindex]:not([tabindex="-1"])';
  const visible = (el) => { const r = el.getBoundingClientRect(); return r.width > 4 && r.height > 4 && getComputedStyle(el).visibility !== 'hidden'; };
  window.addEventListener('keydown', (e) => {
    const dir = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }[e.key];
    if (!dir || e.ctrlKey || e.altKey || e.metaKey) return;
    const cur = document.activeElement;
    const typing = cur && (cur.tagName === 'TEXTAREA' || (/^(INPUT|SELECT)$/.test(cur.tagName) && (e.key === 'ArrowLeft' || e.key === 'ArrowRight') && cur.type !== 'button' && cur.type !== 'checkbox'));
    if (typing) return;
    const els = [...document.querySelectorAll(SEL)].filter(visible);
    if (!els.length) return;
    if (!cur || cur === document.body || !els.includes(cur)) { e.preventDefault(); els[0].focus(); return; }
    const a = cur.getBoundingClientRect(), ax = a.left + a.width / 2, ay = a.top + a.height / 2;
    let best = null, bs = Infinity;
    for (const el of els) {
      if (el === cur) continue;
      const r = el.getBoundingClientRect(), x = r.left + r.width / 2, y = r.top + r.height / 2;
      const dx = x - ax, dy = y - ay, along = dx * dir[0] + dy * dir[1];
      if (along <= 4) continue;
      const across = Math.abs(dx * dir[1]) + Math.abs(dy * dir[0]);
      const score = along + across * 2.2;
      if (score < bs) { bs = score; best = el; }
    }
    e.preventDefault();
    if (best) { best.focus(); best.scrollIntoView({ block: 'nearest', inline: 'nearest' }); }
    else window.scrollBy({ top: dir[1] * 160, behavior: 'smooth' });
  });
})();
