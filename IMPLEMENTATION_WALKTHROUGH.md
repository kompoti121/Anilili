# UI and Playback Flow Implementation Walkthrough

## Purpose

This change brings the reference app's useful interaction patterns into Anilili without copying its branding or making the interface visually busy. The result stays aligned with Anilili's dark, minimal theme and adapts the same flows for touch phones and D-pad televisions.

The work covers:

- scroll-reactive app bars;
- phone and TV navigation differences;
- the redesigned Schedule screen;
- the redesigned anime detail screen and its Home, Episodes, and Related tabs;
- a provider-independent anime episode catalog;
- episode-specific server and Sub/Dub selection on the watch screen;
- a dismissible mobile server drawer;
- richer, compact episode metadata and favorite controls;
- TV focus, Center-button, Back-button, and fullscreen behavior.

## Product Rules

### Anime detail and episode discovery

The anime detail screen is for learning about a title and choosing an episode. It does not expose streaming providers or Sub/Dub controls.

The Episodes tab uses the same anime metadata source as the rest of the detail page:

1. Read cached/local anime information when available.
2. Fetch AniList information when the local information is absent or stale.
3. Build a provider-independent episode catalog from the AniList episode count or the next airing episode.
4. Open the selected episode with the user's preferred audio category and automatic provider selection.

This prevents a streaming provider's temporary catalog outage from making the anime appear to have no episodes.

For an airing show, the released count is the episode immediately before `nextAiringEpisode`. For a completed show, the total AniList episode count is used. The generated `anilist:<anime id>:<episode>` identifier is only a stable UI key and is never sent to a streaming provider.

### Playback source selection

Servers and Sub/Dub are playback decisions, so they appear only after the user opens an episode.

The picker follows these rules:

1. Start with the episode currently on screen.
2. Collect provider/category pairs whose catalog contains that exact episode number.
3. Resolve those pairs against their real source endpoint in background batches of four.
4. Show only confirmed pairs that return a playable stream or embed.
5. Keep the episode number unchanged when the user switches server or audio category.
6. If the selected server has only Dub for this episode, selecting that server changes the category to Dub for the same episode.
7. If a provider has neither a working Sub nor Dub source for the episode, it does not appear in the server list.

Availability is stored by `(episode number, provider, category)`. A source failure therefore hides only that exact combination instead of incorrectly removing the provider from every episode.

The first visit to an episode may briefly show `More servers...` while source validation finishes. That is intentional: a provider is not advertised until its actual stream has been confirmed.

## User Walkthrough

### 1. Shared scrolling behavior

On a phone, scroll any primary list or detail content:

- the screen's top app bar slides and fades away;
- the bottom navigation bar slides and fades away at the same time;
- both return about 360 ms after scrolling stops.

TV chrome remains visible because disappearing navigation creates an unclear D-pad focus target.

### 2. Search navigation

On a phone:

- Search is not present in the bottom navigation bar.
- Search opens from the search action at the top of Home.

On TV:

- Search remains in the navigation rail.
- It is reachable and selectable with the remote like the other rail destinations.

### 3. Schedule screen

Open Schedule to see a compact seven-day date strip. Today is labeled explicitly, and the selected day uses the app accent color.

The schedule is ordered by airing time and grouped into a vertical timeline. Each entry includes the title, episode, format/year tags when available, local airing time, and reminder action. Tapping or pressing Center on a title opens its anime detail page.

### 4. Anime detail screen

The detail page opens on Home and contains:

- a banner/cover hero;
- title and essential metadata;
- a primary Watch/Continue action;
- a heart action for adding or removing the title from the library;
- quick facts, next-airing information, genres, description, and metadata.

The sticky tab row contains:

- **Home** — title overview and actions;
- **Episodes** — AniList/local metadata episode rows only;
- **Related** — related seasons and titles.

Open Episodes and select a row. There are no Server, Sub, or Dub controls in this tab. The selection routes directly to the watch screen using automatic provider selection.

### 5. Phone watch screen

The phone watch page keeps the player inline and places a compact YouTube-like information block below it:

- episode title;
- AniList score when available;
- episode number;
- a two-line description with More/Less expansion;
- round anime cover avatar;
- popularity count;
- outline or filled heart for adding/removing the anime from favorites.

The source controls sit below the metadata.

Tap Server to open a bottom drawer. It can be closed in three ways:

- choose an option;
- tap outside the drawer;
- swipe the drawer down.

Only servers confirmed for the current episode are listed. Sub/Dub choices likewise reflect valid sources. A change resolves the new source and refreshes playback without navigating away or changing the episode.

### 6. TV watch screen

The nonfullscreen TV page uses a static black player preview with a Play affordance. This avoids placing a native player/WebView and Compose controls in the D-pad focus graph at the same time.

The intended remote path is:

1. More/Less description control.
2. Favorite heart.
3. Server control.
4. Sub/Dub control.
5. Episode and related content below.

Press Center on Server to open an in-layout TV dialog. Its first valid source receives focus. Use the D-pad to move between options, Center to select, and Back to close.

Use the Fullscreen control to launch the real player. The player controls receive remote focus in fullscreen. Pressing Back exits fullscreen first and returns focus to the episode/source area; pressing Back again leaves the watch screen.

The on-screen Back overlay is excluded from the normal TV D-pad sequence. The physical remote Back button remains functional.

## Internal Data Flow

### Detail flow

```text
Detail route
  -> cache-first animeInfo(id)
  -> local metadata, or AniList fallback
  -> anilistEpisodeCatalog(info)
  -> Home / Episodes / Related UI
  -> selected episode routes to Watch with provider = auto
```

### Watch flow

```text
Watch route (anime, episode, preferred category)
  -> merge fast and full provider episode catalogs
  -> filter pairs that contain the current episode
  -> validate each provider/category source endpoint
  -> expose only confirmed options
  -> resolve selected pair
  -> choose the provider-appropriate stream/embed
  -> refresh player while preserving the episode
```

The repository returns both a resolved result and the providers proven unavailable during the attempt. The view model uses that result to keep the visible server list accurate as the episode changes or a stream fails.

## Key Files

| File | Responsibility |
| --- | --- |
| `app/src/main/java/com/miruronative/MainActivity.kt` | Scroll-aware root chrome, phone navigation without Search, and unchanged TV rail Search. |
| `app/src/main/java/com/miruronative/ui/components/AppChrome.kt` | Shared visibility signal and animated top-bar wrapper. |
| `app/src/main/java/com/miruronative/ui/schedule/ScheduleScreen.kt` | Seven-day schedule strip, time-grouped timeline, cards, and reminders. |
| `app/src/main/java/com/miruronative/ui/detail/DetailViewModel.kt` | Cache/AniList detail loading and provider-independent episode catalog. |
| `app/src/main/java/com/miruronative/ui/detail/DetailScreen.kt` | Minimal hero, actions, sticky Home/Episodes/Related tabs, and episode routing. |
| `app/src/main/java/com/miruronative/data/MiruroRepository.kt` | Source resolution result with unavailable-provider reporting. |
| `app/src/main/java/com/miruronative/ui/watch/WatchViewModel.kt` | Episode-scoped source filtering, validation, resolution, fallback, and watch metadata. |
| `app/src/main/java/com/miruronative/ui/watch/WatchScreen.kt` | Phone bottom drawer, metadata block, favorites, TV dialogs, focus behavior, and fullscreen flow. |
| `app/src/test/java/com/miruronative/ui/detail/DetailEpisodeCatalogTest.kt` | Released/total episode catalog rules. |
| `app/src/test/java/com/miruronative/ui/watch/WatchSourceOptionsTest.kt` | Current-episode provider/category filtering. |

The shared scroll-aware top bar is also applied to Home, More, Notifications, Profile, Settings, Schedule, and Detail so their top chrome moves in sync with the root bottom bar.

## Verification Performed

The final implementation was built and checked with:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
git diff --check
```

Results:

- unit tests passed;
- Android lint passed;
- debug APK assembly passed;
- `git diff --check` reported no patch errors (only the repository's existing LF-to-CRLF conversion warnings);
- no Android runtime crash was observed in the final TV test;
- TV More/Less, favorite, Server focus, Center selection, dialog focus, D-pad movement, and Back behavior were exercised on the emulator.

The generated APK is:

`C:\Users\Admin\Documents\miruro-apk\app\build\outputs\apk\debug\anilili.apk`

## Emulator Evidence

- Phone watch metadata: `C:\Users\Admin\.codex\visualizations\2026\07\19\019f7974-a165-7942-a082-12fd9795f60b\phone-watch-final.png`
- TV watch layout: `C:\Users\Admin\.codex\visualizations\2026\07\19\019f7974-a165-7942-a082-12fd9795f60b\tv-static-final2.png`
- TV expanded description after Center: `C:\Users\Admin\.codex\visualizations\2026\07\19\019f7974-a165-7942-a082-12fd9795f60b\tv-center-static.png`
- TV server dialog with focused first option: `C:\Users\Admin\.codex\visualizations\2026\07\19\019f7974-a165-7942-a082-12fd9795f60b\tv-dialog-static-final.png`

## Pending / Known Follow-up

1. **Phone cold-start retest after emulator recovery.** The final phone emulator became overloaded after repeated dual-emulator installs. Android's `system_server`, sensor service, and Google services caused startup ANRs before the app completed `Application.onCreate`. The phone UI had already been exercised and captured before that degradation, and the final code passes unit tests, lint, and assembly. A clean phone emulator boot is still recommended for one final cold-start smoke test.
2. **First-load source timing.** Slow provider endpoints can take a few seconds to become visible because the app now confirms a source before showing it. This is expected and prevents dead servers from being offered.
3. **Version-control handoff.** The changes and tests remain unstaged and uncommitted so they can be reviewed as one working-tree update.

## Suggested Final Phone Smoke Test

After starting a clean phone emulator:

1. Install `anilili.apk`.
2. Open Home and confirm Search appears only in the top bar.
3. Scroll a list and confirm both top and bottom bars hide, then return after scrolling stops.
4. Open an anime, switch among Home, Episodes, and Related, and confirm Episodes has no source/audio controls.
5. Open an episode and expand More.
6. Toggle the heart.
7. Open Server, dismiss it once by tapping outside and once by swiping down.
8. Choose a different valid server or Sub/Dub option and confirm the episode number stays unchanged while playback refreshes.
9. Confirm a provider with no working source for that episode is absent from the list.
