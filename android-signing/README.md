# android-signing

`testkey.pk8` + `testkey.x509.pem` — AOSP'nin **herkese açık test imza anahtarı** (CN=android@android.com).
Bu anahtar gizli değildir; internetteki herkes aynı anahtara sahiptir.

- Geliştirme / test APK'larını (ör. Android TV sürümü) imzalamak için uygundur.
- **Yayın (Play Store / dağıtım) için KULLANMA.** Herkes bu anahtarla aynı paket adına imzalı APK üretebilir.

Örnek imzalama:

```bash
apksigner sign --key android-signing/testkey.pk8 --cert android-signing/testkey.x509.pem --out ghost-signed.apk ghost-unsigned.apk
```
