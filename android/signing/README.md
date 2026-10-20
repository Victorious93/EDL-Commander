# EDL-Commander debug keystore (DEBUG ONLY, not secret)

`edl-commander-debug.keystore` is a dedicated **debug** signing key for the EDL-Commander companion
app only. The `debug` build type (`android/app/build.gradle.kts`) always uses it, so every debug APK,
from any laptop or CI run, has the same signing certificate (updates install over each other and the
fingerprint can be pinned for testing).

| Field      | Value |
|------------|-------|
| Store type | PKCS12 |
| Store pass | `android` |
| Key alias  | `androiddebugkey` |
| Key pass   | `android` |
| Key        | RSA 2048, valid 10000 days |
| Subject    | `CN=EDL-Commander Debug, O=Victorious93` |
| SHA-256    | `E5:60:B6:92:BD:E6:51:9E:B7:CB:B5:2C:E4:DC:91:5A:19:E4:41:A0:1A:5D:03:68:82:71:AC:99:78:8A:58:C8` |

Verify:

```sh
keytool -list -v -keystore android/signing/edl-commander-debug.keystore -storepass android -alias androiddebugkey
```

**This key is public. Never use it to sign a release build** or anything distributed to users:
anyone with this repo can sign an APK with it. It is not the fleet key, so debug builds are not
bind-compatible with a fleet-signed Droidcommand-AI. Release builds are signed only with the private
fleet key supplied through the `EDL_FLEET_KEYSTORE*` environment variables (see `docs/ANDROID.md`,
"Same-signing and permissions"), which is never committed.
