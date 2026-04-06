# Building Lockable Clock

## Prerequisites

| Requirement | Version |
|---|---|
| JDK | 17 (Temurin recommended) |
| Android SDK | API 35 (compileSdk) |
| Android SDK min | API 23 (minSdk) |
| NDK | 27.2.12479018 |
| Gradle | Bundled via `gradlew` wrapper |

Install the Android SDK and NDK via [Android Studio](https://developer.android.com/studio) or the
[command-line tools](https://developer.android.com/studio#command-line-tools-only).

---

## Building Locally

### Debug build (for testing/side-loading)

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/Clock_<version>-debug.apk`

### Release build (unsigned, for distribution without signing)

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/Clock_<version>-unsigned.apk`

---

## Signing a Release APK

### 1. Generate a keystore (one-time)

```bash
keytool -genkeypair -v \
  -keystore my-release-key.jks \
  -keyalg RSA -keysize 2048 \
  -validity 10000 \
  -alias my-key-alias
```

### 2. Create `keystore.properties` at the project root

```properties
storePassword=YOUR_KEYSTORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

Place `my-release-key.jks` (renamed to `keystore.jks`) at the project root alongside
`keystore.properties`.

> **⚠️ Never commit `keystore.jks` or `keystore.properties` to version control.**
> Both files are listed in `.gitignore`.

### 3. Build the signed release APK

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/Clock_<version>-release.apk`

---

## CI/CD

### Build workflow (`.github/workflows/build.yml`)

Triggers on:
- Push to `main`
- Pull requests targeting `main`

Steps:
1. Check out the repository
2. Set up JDK 17 (Temurin)
3. Cache Gradle dependencies
4. Run `./gradlew assembleDebug` — uploads debug APK artifact (retained 14 days)
5. Run `./gradlew assembleRelease` — uploads unsigned release APK artifact (retained 14 days)

Download built APKs from the **Actions** tab → select the workflow run → **Artifacts**.

### Release workflow (`.github/workflows/release.yml`)

Triggers on:
- Git tag push matching `v*` (e.g., `v1.0.0`, `v2.1.3`)
- Manual dispatch via the **Actions** tab (**Run workflow** button)

Steps:
1. Check out the repository
2. Set up JDK 17
3. Optionally decode and configure keystore from GitHub Secrets
4. Run `./gradlew assembleRelease`
5. Upload the release APK as a downloadable artifact (retained 14 days)
6. *(Tag push only)* Create a GitHub Release with the APK attached and auto-generated release notes

#### Required GitHub Secrets for signed releases

| Secret | Description |
|---|---|
| `KEYSTORE_BASE64` | Base64-encoded keystore file (`base64 keystore.jks`) |
| `KEYSTORE_PASSWORD` | Password for the keystore |
| `KEY_ALIAS` | Key alias inside the keystore |
| `KEY_PASSWORD` | Password for the key |

Add these under **Settings → Secrets and variables → Actions** in the repository.

If the `KEYSTORE_BASE64` secret is absent, the release APK will be built unsigned.

To create a release, push a version tag:

```bash
git tag v1.0.0
git push origin v1.0.0
```
