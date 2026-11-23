#!/usr/bin/env python3
"""
FilmCehennemi -> M3U kaydedici (Pydroid 3 icin, tarayici gerekmez)

Pydroid 3: Pip menusunden "requests" ve "beautifulsoup4" kur, sonra bu dosyayi calistir.
Site adresini sorar, kategorileri numarali listeler. "0" = hepsi, ya da "2,5" / "3-7".
Her kategori icin scriptin klasorune <kategori>.m3u yazilir
(yazilamazsa /storage/emulated/0/Download).
Cozulemeyen filmlerin sayfa adresleri <kategori>_cozulemeyen.txt dosyasina yazilir.
"""
import argparse
import os
import re
import sys
from urllib.parse import urljoin, urlparse

import requests
from bs4 import BeautifulSoup

UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
OUT_DIR = os.path.dirname(os.path.abspath(__file__))
CAT_HINT = re.compile(r"/(tur|category|kategori|film-kategori|turler|genre)/", re.I)
SKIP_PATH = re.compile(r"/(page|sayfa|tag|etiket|yil|login|register|iletisim|dmca|hakkimizda|feed|wp-)", re.I)
MEDIA = re.compile(r"\.(m3u8|mp4|mpd)(\?|$)", re.I)
MEDIA_SKIP = re.compile(r"(stub\.mp4|subtitle|/sub_|\.vtt|preview|trailer|/ads?/|doubleclick|googlesyndication)", re.I)

session = requests.Session()
session.headers["User-Agent"] = UA


def norm_base(u):
    u = u.strip()
    if not u:
        u = "https://www.hdfilmcehennemi.nl"
    if not u.startswith("http"):
        u = "https://" + u
    p = urlparse(u)
    return f"{p.scheme}://{p.netloc}"


_dbg = {"shown": set()}


def _try(name, fn, url):
    try:
        r = fn(url)
        ok = r.status_code == 200 and len(r.text) > 2000
        if not ok and name not in _dbg["shown"]:
            _dbg["shown"].add(name)
            blk = "Cloudflare" if "just a moment" in r.text[:3000].lower() or "cf-" in str(r.headers).lower() else ""
            print(f"  [{name}] HTTP {r.status_code}, {len(r.text)} bayt {blk}")
        return r.text if ok else ""
    except Exception as e:
        if name not in _dbg["shown"]:
            _dbg["shown"].add(name)
            print(f"  [{name}] hata: {type(e).__name__}: {str(e)[:120]}")
        return ""


def get_html(url, pw_fetch=None):
    t = _try("requests", lambda u: session.get(u, timeout=25), url)
    if t:
        return t
    try:
        import cloudscraper
        if "sc" not in _dbg:
            _dbg["sc"] = cloudscraper.create_scraper()
        t = _try("cloudscraper", lambda u: _dbg["sc"].get(u, timeout=30), url)
        if t:
            return t
    except ImportError:
        pass
    try:
        from curl_cffi import requests as cr
        t = _try("curl_cffi", lambda u: cr.get(u, impersonate="chrome", timeout=30), url)
        if t:
            return t
    except ImportError:
        pass
    return ""


def find_categories(base, html):
    soup = BeautifulSoup(html, "html.parser")
    host = urlparse(base).netloc
    seen, cats = set(), []
    for a in soup.find_all("a", href=True):
        href = urljoin(base, a["href"]).split("#")[0]
        p = urlparse(href)
        if p.netloc != host or not CAT_HINT.search(p.path):
            continue
        key = href.rstrip("/")
        name = " ".join(a.get_text(" ", strip=True).split())
        if key in seen or not name or len(name) > 40:
            continue
        seen.add(key)
        cats.append((name, href))
    return cats


def film_links(base, html):
    soup = BeautifulSoup(html, "html.parser")
    host = urlparse(base).netloc
    out = {}
    for a in soup.find_all("a", href=True):
        href = urljoin(base, a["href"]).split("#")[0].split("?")[0]
        p = urlparse(href)
        if p.netloc != host or p.path.count("/") < 1:
            continue
        if CAT_HINT.search(p.path) or SKIP_PATH.search(p.path) or p.path.strip("/") == "":
            continue
        img = a.find("img")
        title = (a.get("title") or (img.get("alt") if img else "") or a.get_text(" ", strip=True)).strip()
        if not img and len(title) < 3:
            continue
        poster = ""
        if img:
            poster = img.get("data-src") or img.get("data-lazy-src") or img.get("src") or ""
            poster = urljoin(base, poster) if poster and not poster.startswith("data:") else ""
        if href not in out and img:
            out[href] = (title or p.path.strip("/").replace("-", " "), poster)
    return out


def next_url(base, cat_url, html, page):
    soup = BeautifulSoup(html, "html.parser")
    a = soup.select_one("a[rel=next], a.next, .next a, .pagination a.next")
    if a and a.get("href"):
        return urljoin(base, a["href"])
    c = cat_url.rstrip("/")
    return f"{c}/page/{page + 1}/"


def crawl_category(base, name, url, max_pages, limit, pw_fetch):
    films, page, cur = {}, 1, url
    while page <= max_pages:
        html = get_html(cur, pw_fetch)
        if not html:
            break
        found = film_links(base, html)
        new = {k: v for k, v in found.items() if k not in films}
        if not new:
            break
        films.update(new)
        print(f"  [{name}] sayfa {page}: +{len(new)} (toplam {len(films)})", flush=True)
        if limit and len(films) >= limit:
            break
        page += 1
        cur = next_url(base, url, html, page - 1)
    items = list(films.items())
    return items[:limit] if limit else items


MEDIA_RE = re.compile(r"""https?:(?:\\?/){2}[^\s"'<>]+?\.(?:m3u8|mp4|mpd)(?:\?[^\s"'<>\\]*)?""", re.I)


def clean(u):
    return u.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")


def unpack(js):
    """Dean Edwards P.A.C.K.E.R. cozucu (eval(function(p,a,c,k,e,d)...)."""
    out = js
    for m in re.finditer(r"\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)", js, re.S):
        p, a, c, k = m.group(1), int(m.group(2)), int(m.group(3)), m.group(4).split("|")

        def enc(n):
            return (enc(n // a) if n >= a else "") + "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"[n % a]
        d = {enc(i): (k[i] or enc(i)) for i in range(c)}
        out += "\n" + re.sub(r"\b\w+\b", lambda w: d.get(w.group(0), w.group(0)), p.replace("\\'", "'"))
    return out


def media_in(text):
    text = unpack(text)
    res = []
    for m in MEDIA_RE.findall(text):
        u = clean(m)
        if not MEDIA_SKIP.search(u) and u not in res:
            res.append(u)
    res.sort(key=lambda u: 0 if ".m3u8" in u else 1)
    return res


def ok_ru(html):
    m = re.search(r'data-options="([^"]+)"', html)
    if not m:
        return None
    import html as h
    import json
    try:
        o = json.loads(h.unescape(m.group(1)))
        md = json.loads(o["flashvars"]["metadata"])
        return md.get("hlsManifestUrl") or (md.get("videos") or [{}])[-1].get("url")
    except Exception:
        return None


def fetch(url, ref):
    try:
        r = session.get(url, headers={"Referer": ref}, timeout=20)
        return r.text if r.status_code == 200 else ""
    except requests.RequestException:
        return ""


def candidates(base, html):
    soup = BeautifulSoup(html, "html.parser")
    urls = []
    for t in soup.find_all(["iframe", "embed", "source"]):
        for k in ("src", "data-src", "data-lazy-src"):
            if t.get(k):
                urls.append(urljoin(base, t[k]))
    for t in soup.find_all(True):
        for k, v in t.attrs.items():
            if isinstance(v, str) and k.startswith("data-") and v.startswith("http") and "player" in v + k:
                urls.append(v)
    for m in re.findall(r"https?:(?:\\?/){2}[^\s\"'<>\\]+(?:embed|player|iframe|video)[^\s\"'<>\\]*", html, re.I):
        urls.append(clean(m))
    return list(dict.fromkeys(u for u in urls if not MEDIA_SKIP.search(u)))


def resolve(film_url, depth=2):
    """Film sayfasi -> iframe/oynatici sayfalari -> video adresi (tarayici gerekmez)."""
    base = norm_base(film_url)
    seen = set()

    def walk(url, ref, d):
        if url in seen or d < 0:
            return None
        seen.add(url)
        html = get_html(url) if d == depth else fetch(url, ref)
        if not html:
            return None
        v = ok_ru(html)
        if v:
            return v, "https://ok.ru/"
        m = media_in(html)
        if m:
            return m[0], norm_base(url) + "/"
        for c in candidates(norm_base(url), html)[:8]:
            r = walk(c, url, d - 1)
            if r:
                return r
        return None
    return walk(film_url, base + "/", depth)


def resolve_all(films, workers):
    from concurrent.futures import ThreadPoolExecutor
    results, done = {}, [0]

    def one(url):
        r = None
        for _ in range(2):
            try:
                r = resolve(url)
            except Exception:
                r = None
            if r:
                break
        results[url] = r
        done[0] += 1
        print(f"  cozuldu {done[0]}/{len(films)}  {'OK' if r else 'yok'}  {url}", flush=True)
    with ThreadPoolExecutor(workers) as ex:
        list(ex.map(one, [u for u, _ in films]))
    return results


def write_m3u(name, films, results):
    safe = re.sub(r'[\\/:*?"<>|]+', "_", name).strip() or "kategori"
    out = OUT_DIR if os.access(OUT_DIR, os.W_OK) else "/storage/emulated/0/Download"
    path = os.path.join(out, safe + ".m3u")
    miss = [u for u, _ in films if not results.get(u)]
    if miss:
        with open(os.path.join(out, safe + "_cozulemeyen.txt"), "w", encoding="utf-8") as f:
            f.write("\n".join(miss))
    n = 0
    with open(path, "w", encoding="utf-8") as f:
        f.write("#EXTM3U\n")
        for url, (title, poster) in films:
            r = results.get(url)
            if not r:
                continue
            vid, ref = r
            title = title.replace(",", " ").replace("\n", " ")
            logo = f' tvg-logo="{poster}"' if poster else ""
            f.write(f"#EXTINF:-1{logo} group-title=\"{name}\",{title}\n")
            if ref:
                f.write(f"#EXTVLCOPT:http-referrer={ref}\n")
                f.write(f"#EXTVLCOPT:http-user-agent={UA}\n")
            f.write(vid + "\n")
            n += 1
    return path, n


def parse_selection(s, total):
    s = s.strip().lower()
    if s in ("0", "hepsi", "h", "all", "*"):
        return list(range(total))
    out = []
    for part in re.split(r"[,\s]+", s):
        if not part:
            continue
        m = re.fullmatch(r"(\d+)-(\d+)", part)
        if m:
            a, b = int(m.group(1)), int(m.group(2))
            out += list(range(a - 1, b))
        elif part.isdigit():
            out.append(int(part) - 1)
    return [i for i in dict.fromkeys(out) if 0 <= i < total]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("site", nargs="?", default="")
    ap.add_argument("--paralel", type=int, default=4)
    ap.add_argument("--sayfa", type=int, default=200)
    ap.add_argument("--limit", type=int, default=0)
    a = ap.parse_args()

    site = a.site or input("Site adresi (bos = hdfilmcehennemi.nl): ")
    base = norm_base(site)
    print("Kategoriler araniyor:", base)

    pw_fetch = None
    html = get_html(base)
    if not html:
        alt = base.replace("://www.", "://") if "://www." in base else base.replace("://", "://www.")
        print("Diger adres deneniyor:", alt)
        html = get_html(alt)
        if html:
            base = alt
    if not html:
        print("\nAna sayfa acilamadi. Yukaridaki satir nedeni gosterir:")
        print(" - HTTP 403/503 + Cloudflare: Pydroid pip'ten 'cloudscraper' (veya 'curl_cffi') kur, tekrar dene.")
        print(" - baglanti hatasi / zaman asimi: telefonda site tarayicida aciliyorsa DNS/VPN farkli olabilir;")
        print("   Ayarlar > Ozel DNS > dns.google dene ya da VPN ac.")
        sys.exit(1)
    cats = find_categories(base, html)
    if not cats:
        print("Kategori bulunamadi. Site yapisi farkli olabilir.")
        sys.exit(1)

    print("\n  0) HEPSI")
    for i, (n, u) in enumerate(cats, 1):
        print(f"{i:3d}) {n}")
    sel = parse_selection(input("\nSecim (ornek: 0 | 2,5 | 3-7): "), len(cats))
    if not sel:
        print("Gecerli secim yok.")
        sys.exit(1)

    for i in sel:
        name, url = cats[i]
        print(f"\n== {name} ==")
        films = crawl_category(base, name, url, a.sayfa, a.limit, pw_fetch)
        print(f"  {len(films)} film bulundu, video adresleri cozuluyor...")
        results = resolve_all(films, max(1, a.paralel))
        path, n = write_m3u(name, films, results)
        print(f"  Kaydedildi: {path}  ({n}/{len(films)} film)")
    print("\nBitti.")


if __name__ == "__main__":
    main()
