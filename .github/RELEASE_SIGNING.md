# Release APK signing secrets

The `Build signed release APK` workflow expects these repository secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded JKS or PKCS#12 keystore file |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing key alias |
| `ANDROID_KEY_PASSWORD` | Password for the signing key |

Create an upload key locally:

```bash
keytool -genkeypair \
  -keystore usagi-release.jks \
  -storetype JKS \
  -alias usagi \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Encode the keystore without line wrapping:

```bash
base64 -w 0 usagi-release.jks
```

Save that output as `ANDROID_KEYSTORE_BASE64`, then set the other three secrets to the values used when creating the key.

Trigger a signed APK build either by pushing a tag beginning with `v`, or with **Actions → Build signed release APK → Run workflow** and a release tag such as `v2.0-beta1`.
