# TikTokVPN

Fast, private browsing on Android — one tap to connect.

**Developed with Love by Sithu Mht**

## What it does

- **One tap to connect** — a single button brings the tunnel up or down.
- **Warp in Warp** — optionally carries the tunnel inside a second one for extra stability on restricted networks.
- **Optimize** — searches for a server that answers and stays up, then remembers it so the next connection starts instantly.
- **Keeps to itself** — no accounts to sign into, no browsing history, no analytics. The account it creates lives encrypted on the device.

## Building

The core is written in Go and compiled into an Android library; the app itself is Kotlin and Jetpack Compose. You need Go 1.26+, JDK 17+ and the Android SDK (API 37, NDK 28.2.13676358).

### Local (Windows)

```powershell
.\scripts\build-mobile.ps1
.\scripts\build-android-local.ps1
```

Install on a connected device without losing your data:

```powershell
.\scripts\build-android-local.ps1 -Install -Serial <adb-serial>
```

Run only the unit tests or lint:

```powershell
.\scripts\build-android-local.ps1 -Tasks :app:testDebugUnitTest
.\scripts\build-android-local.ps1 -Tasks :app:lintDebug
```

On Linux/macOS use `./scripts/build-mobile.sh` for the first step.

### Continuous integration

| Workflow | Trigger | Produces |
| --- | --- | --- |
| `ci.yml` | every push and pull request | Go tests, unit tests, lint, a debug APK artifact |
| `release.yml` | a tag like `v1.0.0` | signed release APKs attached to the GitHub release |

Release signing is optional. Add these repository secrets to sign:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | your keystore, base64-encoded (`certutil -encode app.keystore app.b64`) |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_PASSWORD` | key password |

Without them the release workflow still builds, using the debug key.

## Project layout

| Path | Contents |
| --- | --- |
| `core/` | the engine: request schema, operations, state |
| `internal/` | tunnel, discovery, registration and connection logic |
| `mobileapi/` | the thin bridge gomobile compiles into the Android library |
| `android/` | the application: Compose UI, VPN service, encrypted storage |
| `scripts/` | local build entry points |

## Privacy

The app stores three things: the device account (encrypted with a key held by the Android keystore), the last server that worked, and your settings. Nothing leaves the device.

## License

MIT — see [LICENSE](LICENSE). This project is a derived work of [warpscout](https://github.com/vernette/warpscout); full credits are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
