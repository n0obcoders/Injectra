# Injectra

A fast, privacy-focused Android browser with a built-in **cookie manager**,
**truly isolated private windows**, and **Shields** ad and tracker blocking.
Native Kotlin on top of Android System WebView (the same Chromium engine
Chrome uses).

![min SDK](https://img.shields.io/badge/Android-9%2B-3ddc84) ![version](https://img.shields.io/badge/version-1.3-8ab4f8)

## Features

### Browsing
- Tabs, a Chrome-style ⋮ menu with back / forward / bookmark / reload / home
- History (searchable), bookmarks, find in page, share, copy link
- **Desktop site** toggle per tab (identifies as desktop Chrome)
- Long-press a link: open in new tab, open in private window, copy, share, download image
- Fullscreen video, file uploads, downloads
- Can be set as your default browser for links from other apps
- Pick your search engine: Google, Bing, DuckDuckGo, Brave, Startpage

### Private windows (Firefox Focus style)
- Run in a **separate Android process with a separate WebView data directory**.
  That means a completely separate cookie jar, cache, localStorage and IndexedDB.
  Logins in your normal window are invisible in a private window, and nothing
  leaks back.
- Everything is wiped when the window closes (🔥 *Erase & close*), and again at
  the start of the next private session in case the app was killed.
- No history, no saved form data, third-party cookies always blocked,
  screenshots and the recents preview are blocked (`FLAG_SECURE`).

### Cookie Manager
Works on whichever window you open it from (normal or private jar).
- View the current site's cookies
- **Inject** cookies from a file or by pasting: JSON (Cookie-Editor /
  EditThisCookie export format) or Netscape `cookies.txt`
- **Export** the current site's cookies as JSON or Netscape
- Clear cookies for the site, or all cookies

> Android's `CookieManager` only exposes `name=value` for a URL, so exported
> cookies carry domain, path and secure inferred from the page, without expiry
> or HttpOnly flags.

### Shields, privacy & security
- **Shields**: blocks requests to ~200 major ad, tracker, analytics and
  fingerprinting networks, with a per-page blocked counter in the menu
- **HTTPS upgrade**: http links are upgraded to https, with a prompt to fall
  back if a site doesn't support it; mixed content is blocked
- **Pop-up blocker**: only windows opened by a tap are allowed
- Optional third-party cookie blocking, image blocking (data saver), JavaScript toggle
- Google Safe Browsing on
- Clear browsing data (history, cookies, site storage, cache)

## Security hardening

The WebView attack surface is locked down:

| Risk | What Injectra does |
|---|---|
| Pages reading local files / other apps' data | `allowFileAccess`, `allowContentAccess`, file-URL universal access all **off** |
| Page-triggered `file:`, `content:`, `javascript:`, `data:` navigations | **Blocked** |
| Typed `javascript:` / `file:` in the address bar | Treated as search text, never executed |
| `intent://` links launching arbitrary app components | Component and selector stripped, `BROWSABLE` enforced, user must confirm |
| Pages silently opening other apps | Requires a tap **and** a confirmation dialog |
| Camera, microphone, location | Always denied to websites |
| JavaScript-to-native bridges | None: the app never calls `addJavascriptInterface` |
| Data leaving the device via backups | `allowBackup="false"` |
| Invalid TLS certificates | Not overridden: WebView's default (refuse) applies |
| Download path tricks | Only http(s), file name from `URLUtil.guessFileName`, saved to the app's own Downloads folder |

Found a security issue? Please open a private security advisory on GitHub
rather than a public issue.

## Build

Requirements: JDK 17+, Android SDK (platform 34, build-tools 34). Android
Studio installs both automatically.

### Android Studio
Open the project folder, let Gradle sync, then **Build → Build APK(s)**.

### Command line
```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
```

### Signed release builds
Release signing is read from a `keystore.properties` file in the project root,
which is **git-ignored**. Without it, `assembleRelease` produces an unsigned APK.

```properties
storeFile=injectra-release.jks
storePassword=your-store-password
keyAlias=injectra
keyPassword=your-key-password
```

Put your `.jks` keystore next to it (also git-ignored). To create a new one:

```bash
keytool -genkeypair -v -keystore injectra-release.jks -storetype PKCS12 \
  -alias injectra -keyalg RSA -keysize 4096 -validity 10000
```

> **Never commit the keystore or its passwords.** Anyone with them can publish
> updates that Android will accept as yours. If you lose them, you can't ship
> updates to existing installs.

#### GitHub Actions (optional)
To build signed releases in CI, store the keystore as a base64 secret
(`base64 -w0 injectra-release.jks`) plus the passwords as repository secrets,
and have the workflow write `injectra-release.jks` and `keystore.properties`
before running `./gradlew assembleRelease`.

## Project layout

```
app/src/main/java/com/injectra/browser/
  MainActivity.kt     browser UI, tabs, menu, WebView setup & hardening
  PrivateActivity.kt  private window (runs in the :incognito process)
  InjectraApp.kt      gives the private process its own WebView data dir, wipes it
  Shields.kt          ad & tracker blocklist
  CookieIO.kt         JSON / Netscape cookie parsing and export
  Store.kt            history, bookmarks, settings
```

Tech: Kotlin 1.9, Android Gradle Plugin 8.5, compileSdk/targetSdk 34, minSdk 28 (Android 9).
