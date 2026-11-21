# 👻 Ghost Browser

Film izlemeye odaklı, Electron tabanlı tarayıcı.

- 🎬 **Kendi oynatıcısı** (`ghost://player`): MP4/WebM/MKV + HLS (.m3u8), altyazı (.srt/.vtt, Türkçe karakter otomatik), hız, kalite/ses parçası, PiP, kaldığın yerden devam, klavye/kumanda kısayolları
- 🔎 **Video yakalama**: Film sitesinde oynayan video adreslerini algılar → adres çubuğunda 🎬 düğmesi → "Ghost Oynatıcı'da aç" (Referer otomatik taşınır)
- 🛡 **Reklam engelleyici**: yerleşik liste + AdGuard/hosts filtre listesi (URL ayarlanabilir) + kendi alan adların, pop-up/pop-under engelleme, boş reklam kutularını gizleme
- ⚡ **Hızlı yeni sekme**: yerel sayfa, anında açılır; site kutuları + son ziyaretler
- 🌐 **GitHub kontrollü siteler**: Ayarlar → Siteler'e GitHub linkini yapıştır, liste JSON'dan çekilir (önbelleğe alınır, 30 dk'da bir yenilenir)
- 📱 **QR ile telefon kumandası**: aynı Wi-Fi'de QR'ı okut → link gönder, arama yap, oklar / touchpad imleci, tıkla, kaydır, yazı gönder, oynat-duraklat-ileri-geri-ses-altyazı-tam ekran, sekme değiştir
- 📺 **TV modu** (F9): büyük arayüz + yön tuşlarıyla sanal imleç (uzaktan kumanda / klavye)
- 🕘 Geçmiş kaydı (arama, silme)

## Kurulum

```bash
npm install     # electron + hls.js (postinstall hls.js'i pages/vendor'a kopyalar)
npm start
npm test        # Electron'sız mantık testleri
npm run dist    # Windows/Linux/macOS paketi (electron-builder)
```

## GitHub'dan site listesi

`sites.json` (bu depodaki örneği kullanabilirsin):

```json
{
  "name": "Benim listem",
  "sites": [
    { "name": "Site adı", "url": "https://ornek.com", "category": "Film", "icon": "https://.../logo.png" }
  ],
  "blockHosts": ["reklam-agi.example"]
}
```

Ayarlar → Siteler → "Ekle" alanına şunlardan biri girilir:
`https://github.com/kullanici/depo` (kökteki `sites.json`) · `.../blob/main/dosya.json` · `.../tree/main/klasor` · raw link · gist.
Düz dizi (`[ {...} ]`) ve `categories` biçimi de çalışır. `blockHosts` ile reklam alan adlarını da GitHub'dan yönetirsin.
Varsayılan kaynak `https://github.com/darknesslord19/Ghost-Browser` (kökte `sites.json`); Ayarlar'dan silip değiştirebilirsin. Kaynak yoksa/erişilemezse yerleşik `sites.default.json` gösterilir.

## Telefon kumandası

1. 📱 düğmesine bas (veya Ayarlar → Telefon kumandası) → QR çıkar
2. Telefon kamerasıyla okut (aynı Wi-Fi). Tarayıcıda açılan sayfa kumandadır, sunucu yalnızca özel/yerel ağ IP'lerinden erişilir ve gizli anahtar ister (Ayarlar'dan yenilenebilir).
3. Windows güvenlik duvarı sorarsa "Özel ağlara izin ver".

Oklar modu: yön tuşları + OK (yeni sekme kutuları, oynatıcı seek/ses). İmleç modu: touchpad (kaydır = imleç, dokun = tıkla, iki parmak = kaydırma).

## Kısayollar
Ctrl+T/W/Shift+T · Ctrl+L · Ctrl+Tab · Alt+←/→ · F5 · F9 TV modu · F11 · F12 · Ctrl+H geçmiş · Ctrl+, ayarlar
Oynatıcı: Boşluk, ←/→ (Shift 30 sn), ↑/↓ ses, F, M, C altyazı, [ ] hız, P PiP, Z/X altyazı gecikmesi, 0-9 atla

## Notlar
- Widevine DRM'li servisler (Netflix, Disney+…) stok Electron'da çalışmaz.
- Bazı HLS kaynakları Referer/Origin ister; oynatıcı bunu kaynak sayfadan otomatik gönderir.
- `android-signing/`: verdiğin AOSP **herkese açık test anahtarı** (`testkey.pk8`/`.x509.pem`). Yalnızca test imzası içindir, yayın için kullanma.
