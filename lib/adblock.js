'use strict';
// Reklam / izleyici / pop-under engelleyici. Electron'a bağımlı değil (test edilebilir).

const BUILTIN = `
doubleclick.net googlesyndication.com googleadservices.com adservice.google.com pagead2.googlesyndication.com
google-analytics.com analytics.google.com stats.g.doubleclick.net adsense.google.com
popads.net popcash.net propellerads.com propellerclick.com exoclick.com exosrv.com exdynsrv.com juicyads.com
trafficjunky.net trafficjunky.com adsterra.com adsterratools.com a-ads.com ad-maven.com admaven.com
onclickads.net onclkds.com clickadu.com clickaine.com hilltopads.net hilltopads.com adcash.com adxpansion.com
mgid.com taboola.com outbrain.com revcontent.com adnxs.com criteo.com criteo.net pubmatic.com rubiconproject.com
openx.net openx.com adform.net smartadserver.com yieldmo.com 33across.com sharethrough.com teads.tv moatads.com
scorecardresearch.com quantserve.com zedo.com bidswitch.net casalemedia.com contextweb.com amazon-adsystem.com
adsrvr.org rlcdn.com adroll.com advertising.com adtech.com adtechus.com lijit.com sovrn.com indexww.com
popunder.net popmyads.com pop-ads.org push-ad.com pushwhy.com pushnami.com pushengage.com notix.io
richpush.co richads.com richpartners.com adspyglass.com bidvertiser.com adk2.co adk2x.com ad.gt
trafficstars.com tsyndicate.com tsyndicate.net stripchat-ads.com ero-advertising.com eroadvertising.com
realsrv.com syndication.realsrv.com mc.yandex.ru an.yandex.ru yandexadexchange.net
hotjar.com mouseflow.com fullstory.com crazyegg.com clarity.ms facebook.net connect.facebook.net
adskeeper.com adskeeper.co.uk lentainform.com cdn.adskeeper.com jsc.mgid.com
bongacams.com cam4ads.com livejasmin-ads.com awempire.com
ads.yahoo.com adtng.com adserverplus.com adbull.com adhigh.net adclickmedia.com admixer.net
betteradsystem.com bannersnack.com cpmstar.com cpx24.com cpagrip.com cpalead.com
smartadserver.com serving-sys.com tribalfusion.com undertone.com vibrantmedia.com
disqusads.com ib.adnxs.com mookie1.com nativeroll.tv videoplaza.tv spotxchange.com spotx.tv
springserve.com tremorhub.com tvpixel.com videohub.tv jwpltx.com ssl.jwpltx.com
imasdk.googleapis.com
`.split(/\s+/).filter(Boolean);

const PATTERN = /(^|[\/_.-])(popunder|popads|clickunder|adserver|adsterra|exoclick|juicyads|trafficjunky|adsbygoogle|prebid)([\/_.-]|$)/i;

const COSMETIC_CSS = `
ins.adsbygoogle, .adsbygoogle, [id^="google_ads_"], [id^="div-gpt-ad"], [id*="ScriptRoot"],
iframe[src*="doubleclick.net"], iframe[src*="googlesyndication"], iframe[src*="adsterra"], iframe[src*="exoclick"],
iframe[src*="juicyads"], iframe[src*="popads"], iframe[src*="propeller"], iframe[src*="mgid.com"],
a[href*="popads.net"], a[href*="exoclick"], a[href*="juicyads"], a[href*="trafficjunky"], a[href*="adsterra"],
.ad-banner, .ad-container, .adbox, .ads-box, .advert, .advertisement, .banner-ads, .sponsored-ad,
#ad-banner, #ads, #adbox, #advert, [class^="ad-slot"], [class*=" ad-slot"], [data-ad-slot], [data-adunit],
div[id^="taboola"], div[id^="outbrain"], .OUTBRAIN, .taboola, [id^="mgid"], [class*="mgid"]
{ display: none !important; visibility: hidden !important; height: 0 !important; min-height: 0 !important; }
`;

const SECOND_LEVEL = new Set(['co', 'com', 'org', 'net', 'gov', 'edu', 'ac']);
function registrable(host) {
  const p = host.split('.');
  if (p.length <= 2) return host;
  const last = p[p.length - 1], sl = p[p.length - 2];
  return (last.length === 2 && SECOND_LEVEL.has(sl)) ? p.slice(-3).join('.') : p.slice(-2).join('.');
}
function hostOf(url) { try { return new URL(url).hostname.toLowerCase(); } catch { return ''; } }

class AdBlock {
  constructor() {
    this.enabled = true;
    this.builtin = new Set(BUILTIN);
    this.custom = new Set();
    this.remote = new Set();
    this.pushed = new Set(); // GitHub sites.json içinden gelen blockHosts
    this.allowed = new Map(); // url -> expiry (kullanıcının kendi yazdığı adresler)
    this.stats = { blocked: 0, popups: 0, byHost: new Map() };
  }
  setCustom(list) { this.custom = new Set((list || []).map(s => String(s).trim().toLowerCase().replace(/^\|\|/, '').replace(/[\^\/].*$/, '')).filter(Boolean)); }
  setPushed(list) { this.pushed = new Set((list || []).map(s => String(s).trim().toLowerCase()).filter(Boolean)); }

  /** EasyList/AdGuard (||domain^) ve hosts biçimlerini okur. */
  loadRemoteText(text) {
    const set = new Set();
    for (let line of String(text).split(/\r?\n/)) {
      line = line.trim();
      if (!line || /^[!#\[]/.test(line) || line.startsWith('@@')) continue;
      let m = line.match(/^\|\|([a-z0-9.-]+)\^?$/i);               // ||domain^
      if (m) { set.add(m[1].toLowerCase()); continue; }
      m = line.match(/^(?:0\.0\.0\.0|127\.0\.0\.1)\s+([a-z0-9.-]+)/i); // hosts
      if (m && m[1] !== 'localhost') set.add(m[1].toLowerCase());
    }
    this.remote = set;
    return set.size;
  }

  isBlockedHost(host) {
    if (!host) return false;
    const parts = host.split('.');
    for (let i = 0; i <= parts.length - 2; i++) {
      const h = parts.slice(i).join('.');
      if (this.builtin.has(h) || this.custom.has(h) || this.pushed.has(h) || this.remote.has(h)) return h;
    }
    return false;
  }

  allowOnce(url) { this.allowed.set(url, Date.now() + 15000); }

  /** @returns {boolean} true => isteği iptal et */
  shouldBlock(url, resourceType, firstPartyUrl) {
    if (!this.enabled) return false;
    const exp = this.allowed.get(url);
    if (exp) { if (exp > Date.now()) return false; this.allowed.delete(url); }
    const host = hostOf(url);
    if (!host || host === 'localhost' || /^\d+\.\d+\.\d+\.\d+$/.test(host)) return false;
    const fp = hostOf(firstPartyUrl);
    // aynı site isteklerini asla engelleme (film sitesinin kendi oynatıcısı bozulmasın)
    if (fp && registrable(fp) === registrable(host)) return false;
    let hit = this.isBlockedHost(host);
    if (!hit && resourceType !== 'mainFrame' && PATTERN.test(url)) hit = 'pattern';
    if (!hit) return false;
    this.stats.blocked++;
    const k = typeof hit === 'string' ? hit : host;
    this.stats.byHost.set(k, (this.stats.byHost.get(k) || 0) + 1);
    return true;
  }

  summary() {
    const top = [...this.stats.byHost.entries()].sort((a, b) => b[1] - a[1]).slice(0, 8);
    return { enabled: this.enabled, blocked: this.stats.blocked, popups: this.stats.popups, top,
      rules: this.builtin.size + this.custom.size + this.remote.size + this.pushed.size };
  }
}

module.exports = { AdBlock, COSMETIC_CSS, registrable, hostOf };
