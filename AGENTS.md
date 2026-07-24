# Anilili — Agent Guide

Anilili is a native Android anime streaming client built with Kotlin, Jetpack Compose, and Media3. It runs on phones, tablets, Android TV, and Fire TV down to Android 5.1 / Fire OS 5 (API 22). The app is sideloaded only — there is no Play Store build.

## Technology Stack

- **Language**: Kotlin 2.0.21 (JVM target 17)
- **Build system**: Gradle 8.13 with Android Gradle Plugin 8.10.1
- **UI framework**: Jetpack Compose (BOM 2024.12.01), Material3, single dark theme
- **Media playback**: AndroidX Media3 ExoPlayer (1.8.1), HLS + DASH, Cast support
- **Networking**: OkHttp 4.12.0 with Cronet fallback
- **Persistence**: Room (2.7.2) for cache, DataStore for settings, SharedPreferences for library
- **Serialization**: kotlinx.serialization (JSON)
- **Image loading**: Coil Compose
- **Dependency injection**: Manual (`AppGraph` singleton — no Hilt/Dagger)
- **Background work**: WorkManager, DownloadManager, foreground services

## Project Structure

```
app/src/main/java/com/miruronative/
├── MainActivity.kt              # Scroll-aware root chrome, phone/TV navigation
├── MiruroApp.kt                 # Application class, init order, diagnostics hooks
├── data/
│   ├── AppGraph.kt              # Manual DI container (repository, HTTP client, TV flag)
│   ├── MiruroRepository.kt      # Single UI entry point: metadata + streaming backends
│   ├── ProviderCatalog.kt       # Provider classification (Miruro pipe vs Anivexa)
│   ├── auth/                    # AniList OAuth, MAL OAuth, SecureTokenStore
│   ├── cache/                   # Room-based bounded two-level cache (AppCache)
│   ├── library/                 # LibraryStore (history, watchlist, remote sync)
│   ├── model/                   # kotlinx.serialization data classes (AniList, pipe, etc.)
│   ├── remote/                  # All network clients (AniList, MAL, pipe, Anivexa, Jikan, etc.)
│   ├── reminder/                # Airing reminders, release sync, notifications
│   ├── settings/                # SettingsStore (DataStore preferences)
│   └── update/                  # In-app GitHub release updater
├── diagnostics/                 # CrashReporter, DiagnosticsLog, share diagnostics
├── playback/                    # PlaybackService (MediaSession), EpisodeDownloads, cache
├── ui/
│   ├── nav/Routes.kt            # Central route table
│   ├── theme/                   # Dark Material3 theme, TV typography
│   ├── adaptive/                # AppDeviceProfile (phone/tablet/TV), focus highlights
│   ├── components/              # Shared Compose components (AnimeCard, AppChrome, etc.)
│   ├── home/                    # HomeScreen, HomeViewModel, TV variant
│   ├── detail/                  # DetailScreen, DetailViewModel, episode catalog logic
│   ├── watch/                   # WatchScreen, WatchViewModel, source validation
│   ├── search/                  # SearchScreen, SearchViewModel
│   ├── schedule/                # ScheduleScreen, ScheduleViewModel
│   ├── profile/                 # Login WebViews, ProfileScreen
│   ├── settings/                # SettingsScreen
│   └── more/                    # MoreScreen
└── util/                        # Small helpers

app/src/test/java/com/miruronative/      # 44 unit-test files (JUnit 4)
app/src/androidTest/java/com/miruronative/ # 2 instrumented tests

shared/                                  # Kotlin Multiplatform module (KMP foundation)
├── src/commonMain/kotlin/com/miruronative/
│   ├── data/model/              # kotlinx.serialization models (AniList, pipe, advisory)
│   ├── data/ProviderCatalog.kt  # Provider classification (Miruro pipe vs Anivexa)
│   ├── data/remote/             # HttpEngine interface, JikanClient, AniSkipClient
│   └── util/Base64Compat.kt     # okio-based Base64 (KMP-safe)
└── src/androidMain/kotlin/com/miruronative/data/remote/
    └── OkHttpEngine.kt          # Android HttpEngine backed by OkHttp
```

## Build Commands

Requirements: JDK 17, Android SDK API 35.

```bash
# Debug build (generates universal + ARM splits)
./gradlew :app:assembleDebug

# Release build (requires keystore.properties)
./gradlew :app:assembleRelease

# Run unit tests
./gradlew :app:testDebugUnitTest

# Run Android lint
./gradlew :app:lintDebug

# Combined verification (used before commits)
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On Windows use `gradlew.bat` instead of `./gradlew`.

Debug builds produce three APKs in `app/build/outputs/apk/debug/`:
- `Anilili-debug.apk` (universal)
- `Anilili-debug_arm64-v8a.apk`
- `Anilili-debug_armeabi-v7a.apk`

Release builds use the same naming without the `-debug` suffix. **Do not rename splits with `-` before the ABI** — the in-app updater and legacy versions rely on GitHub's alphabetical asset ordering (`.` sorts before `_`).

## Code Style Guidelines

- Kotlin official code style (`kotlin.code.style=official` in `gradle.properties`).
- Use `com.miruronative` package everywhere.
- Prefer explicit `val`/`var` types for public APIs; local variables may use inference.
- Compose UI files use `\r\n` line endings (CRLF); data/remote files also use CRLF. Match the existing line ending style of the file you edit.
- All `\r` endings shown by `Read` must be matched exactly in `Edit.old_string`.
- Comments explain *why*, not *what*. Inline comments are common for product rules and fragility warnings.
- Diagnostics logging is pervasive: use `DiagnosticsLog.event("...")` and `DiagnosticsLog.throwable("...", e)` for anything that might help debug a user report.

## Architecture Conventions

- **No Hilt**: `AppGraph` is a manual singleton initialized in `MiruroApp.onCreate`. ViewModels read dependencies from `AppGraph`.
- **Repository pattern**: `MiruroRepository` is the single UI-facing data source. It coordinates two streaming backends (Miruro pipe + Anivexa) and AniList metadata.
- **Cache-first reads**: `AppCache` provides stale-while-revalidate with fallback to last-known-good on error.
- **StateFlow + Compose**: ViewModels expose `StateFlow<UiState<T>>`; screens collect with `collectAsState()`.
- **TV adaptations**: `AppDeviceProfile` resolves form factor. TV screens often have dedicated `Tv*Screen.kt` files with D-pad focus handling.
- **KMP `shared` module**: platform-neutral code (models, `ProviderCatalog`, `Base64Compat`, `HttpEngine`-based clients) lives in `:shared` under `commonMain`; packages are unchanged, so imports look identical. Network clients in `commonMain` never see OkHttp — they take the `HttpEngine` interface, and `AppGraph` injects the OkHttp-backed `OkHttpEngine` from `androidMain`. iOS targets are declared only on macOS hosts (Kotlin/Native can't link Apple binaries on Windows), so don't expect `compileKotlinIosX64` to exist on this machine.

## Testing Strategy

- **Unit tests**: JUnit 4, located in `app/src/test/`. Tests cover:
  - Provider parsers and protocol logic (e.g. `AniListPolicyTest`, `PipeDownloadUrlTest`)
  - UI business rules (e.g. `DetailEpisodeCatalogTest`, `WatchSourceOptionsTest`)
  - Settings and playback utilities (e.g. `CaptionStyleTest`, `SubtitleDelayTest`)
- **Instrumented tests**: Minimal — only 2 AndroidTest files (`AllAnimeWebViewTest`, `KickAssAnimePlaybackTest`).
- Run tests before committing: `./gradlew :app:testDebugUnitTest :app:lintDebug`
- No mocking framework is used; tests call pure functions with real data.

## Security Considerations

- **No secrets in source**: The Miruro pipe obfuscation key is public (shipped in the site's `env2.js`).
- **OAuth tokens**: Stored in `SecureTokenStore` (EncryptedSharedPreferences). AniList uses implicit grant; MAL uses OAuth 2.0 with PKCE.
- **TLS**: OkHttp is configured with certificate pinning disabled (relies on system trust store). Cleartext traffic is enabled **only** in debug builds (`manifestPlaceholders`).
- **Update integrity**: APKs must be signed with the same key as the installed release. The updater selects the correct ABI split by name.
- **ProGuard**: Release builds are minified and shrink resources. Keep rules preserve `kotlinx.serialization` metadata and model classes.
- **Crash reporting**: Local file only (`last_crash.txt`). No third-party crash reporter. Users manually share diagnostics.

## Key Files for Common Changes

| Task | File(s) |
|------|---------|
| Add a new streaming provider | `ProviderCatalog.kt`, `AnivexaClient.kt`, add parser in `data/remote/` |
| Change home screen layout | `HomeScreen.kt`, `TvHomeScreen.kt` |
| Change playback behavior | `PlaybackService.kt`, `WatchViewModel.kt` |
| Add a new setting | `SettingsStore.kt`, `SettingsScreen.kt` |
| Modify cache policy | `AppCache.kt`, TTL constants in `MiruroRepository.kt` |
| Update AniList queries | `AniListClient.kt` |
| Modify pipe protocol | `PipeClient.kt`, `PipeBridge.kt`, `docs/PIPE_PROTOCOL.md` |
| Add unit tests | Mirror the package under `app/src/test/java/com/miruronative/` |

## Deployment Notes

- The app is sideloaded via GitHub Releases (`kompoti121/anilili`, tag `APK-release`).
- `keystore.properties` (gitignored) is required for release signing:
  ```properties
  storeFile=keystore/anilili-release.jks
  storePassword=...
  keyAlias=...
  keyPassword=...
  ```
- Fire OS 5 compatibility (API 22) is intentionally maintained. Several dependency versions are pinned to the last API-22-compatible line (documented in `libs.versions.toml`).
