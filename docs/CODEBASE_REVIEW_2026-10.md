# Convx codebase review — 2026-10-12

## Scope and confidence

This is an ongoing static review of the current working tree: Gradle settings and dependencies, source layout, selected large files, provider adapters, comments, and test locations. The repository currently has 732 Kotlin files under `app/src/main/kotlin` and 1,955 Kotlin/Java source files overall. Git reports 806 changed or untracked paths; this includes existing work from multiple cleanup passes and is not a count of changes from this review alone.

Verification is incremental. Recent checks include `:spine:compileDebugKotlin`, `:integrations:compileDebugKotlin`, `:canvas:test`, `:innertube:testDebugUnitTest`, focused lyrics parser tests, and app Kotlin compilation. This does not constitute a full repository test or device runtime audit. Performance gains are not claimed without device measurements.

## Changes made

- Merged `applecanvas` and `convxcanvas` into `:canvas`. Their Kotlin package names and public provider names are unchanged, so existing imports and call sites stay intact. The Gradle project count is now 18, down from 20.
- Replaced `nearestValue`'s `filter` plus `minByOrNull` chain with a single pass. It keeps the nearest qualifying finite value, preserves first-in-list tie behavior, and avoids allocating an intermediate list for each candidate check.
- Removed the stale `//new link` comment from the Convx Music Canvas URL.
- Removed three playback comments that repeated the EQ collector and IP-version collector behavior; retained comments explaining startup timing and stream re-resolution constraints.

## Findings

### High: the app module is the architecture

The `app` module contains 693 Kotlin/Java source files and roughly 186,000 lines under `src/main`. `MainActivity.kt` and `HomeScreen.kt` are both around 2,700 lines. Most functionality, state orchestration, UI, playback, and provider selection therefore shares one build and ownership surface. This makes unrelated changes harder to isolate and makes the app module the default home for new code.

**Improvement:** split by stable product capability, not by arbitrary file size. A good first seam is playback orchestration versus UI, followed by library/catalog and lyrics. Give each extracted module one small interface and keep implementation details inside it. Do not turn every screen into a Gradle module.

### High: canvas source selection is repeated at call sites

Canvas source fallback and lookup is implemented across `Player.kt`, `Thumbnail.kt`, `PlayerV2Canvas.kt`, `DownloadUtil.kt`, and `CanvasAlbum.kt`. The same providers are imported and queried in multiple UI and playback paths. That spreads ordering, fallback, and error-handling decisions across callers.

**Improvement:** put lookup and source priority behind one canvas resolver. Have UI and download code call that interface rather than selecting providers themselves. This is a better simplification than adding more provider-specific dependencies to callers.

### High: automated verification is thin outside the core

The current scan finds 52 test source files under `app`, 6 under `canvas`, 4 under `innertube`, 3 under `spine`, and 1 under `spotify`. Most small provider modules and the 117-file `hig` module have no module-local tests. These counts do not measure coverage, but they show that many network and UI seams currently lack nearby regression checks.

**Improvement:** add focused tests at the provider and resolver interfaces: response parsing, empty/malformed payloads, cancellation, timeout/error behavior, cache expiry, and provider fallback order. Keep pure policy tests independent of Android and network clients.

### Medium: small provider modules duplicate Gradle setup

Several provider modules contain only one to three main source files and repeat nearly identical Ktor setup. The lyric adapters (`betterlyrics`, `lrclib`, `musixmatchlyrics`, `paxsenixlyrics`, `simpmusic`, and `youlyplus`) are the next consolidation candidate. Keep each provider in its own package and preserve an interface per source if callers need independent enablement or error reporting.

This consolidation needs a deliberate plugin/dependency decision: some are Android library modules, some use the Kotlin JVM plugin, and `paxsenixlyrics` depends on `betterlyrics`. Merge only after removing that incidental module dependency and confirming the common module does not pull Android concerns into pure Kotlin code. `jiosaavn`, `kugou`, `lastfm`, and `spotify` have different roles and should stay separate absent a stronger shared interface.

### Medium: comments often narrate implementation history

The search found no comments explicitly identifying themselves as AI-generated. It did find stale or low-value notes, including the removed `//new link` annotation, and several long comments in large files that explain old regressions or previous implementation attempts. Comments that preserve invariants and non-obvious performance constraints are useful; comments that paraphrase the next line or retain superseded history make code harder to scan.

**Improvement:** keep comments for contracts, invariants, platform behavior, and measured constraints. Move lasting architectural decisions to docs. Delete comments when the code changed and the comment no longer describes it. Avoid a repository-wide automatic comment deletion pass.

### Medium: network configuration is repeated across adapters

Provider implementations construct their own Ktor clients and repeat timeout, compression, cache, and serialization setup. Independent clients can be appropriate when providers need different engines or policies, but the repeated setup makes defaults and lifecycle behavior difficult to compare.

**Improvement:** first centralize only shared client configuration and observability. Keep provider-specific engine, headers, cache rules, and timeout overrides explicit. Avoid a global singleton that silently changes unrelated request behavior.

## Consolidation result

The two canvas modules are now source packages inside `:canvas`; they no longer appear as Gradle projects. Their package names remain `com.convx.music.applecanvas` and `com.convx.music.convxcanvas`. The next best candidate is a `lyrics-providers` module, but its Android/JVM plugin split and `paxsenixlyrics` → `betterlyrics` dependency should be resolved before moving files.

## Review verdict

The repository has real performance work and several useful isolated policy modules, but the current structure still puts too much product logic in `:app`, repeats provider selection at call sites, and gives many external integrations little local test protection. The biggest maintainability gain will come from centralizing repeated decisions behind a small number of well-tested interfaces. Renaming folders or deleting comments alone will not address those problems.

## 2026-10-12 follow-up

The cleanup since the initial review has focused on readability in existing code paths. It has simplified selected search summary rendering and filtering, playback transition planning and queue handling, media session search resolution, playback error recovery, room event dispatch, OpenRouter response parsing, and network connectivity initialization. Redundant comments were removed where the code already states the behavior. Comments that explain non-obvious constraints or rationale were retained.

The changes are targeted and do not complete the requested app-wide cleanup. The repository still has many large files and broad responsibilities, and no further Gradle module merges have been made in this pass. The current working tree has 486 modified tracked files and 141 untracked entries; these counts include ongoing work from multiple sources and should not be attributed solely to this cleanup.

This follow-up also removed unconditional debug output from YouTube URL parsing and canvas lookup, removed title normalization that was used only by those logs, and routed direct stack-trace output through the app's Timber-backed exception reporter. Playlist creation retry logic was moved out of the menu click handler to make the sync flow flatter.

The chart mapper no longer runs `filterNotNull()` after `mapNotNull()`, removing a redundant pass over each section.

Search suggestion state assembly now lives in a named helper, keeping URL parsing, history filtering, and result filtering out of the `flatMapLatest` branch. Recent history queries are collected into a set once per emission instead of rescanning the history list for each suggestion.

The sync routines now build remote-ID sets directly with `mapTo(mutableSetOf())`, avoiding a temporary list in seven song, album, artist, and playlist comparisons. Removed nearby branch comments that only restated the null and liked/uploaded checks.

`MediaLibrarySessionCallback.onGetChildren` now delegates each library path to a named loader, and the shared downloaded-song flow is used by both browsing and playback selection. This keeps the callback short and prevents the two paths from drifting apart.

`onSetMediaItems` now uses one helper to turn local song lists into a queue positioned at the requested song. This removes four copies of the same conversion and fallback-index logic.

Online media-library search now checks local IDs through a set before doing title-and-artist comparisons. The name-based match remains unchanged and is isolated in `isAlreadyInLocalLibrary`.

Verification since the initial review includes successful app Kotlin compilation, successful `:innertube:testDebugUnitTest` (including new URL parser cases), and focused passing tests for playback error classification, room permissions, and queue building. The latest app compile covers the media-library queue helper and search deduplication, both media-library callback refactors, the sync set-building change, search suggestion refactor, and chart mapper simplification, as well as the Listen Together chat parsing extraction, YouTube and canvas log cleanup, preference key and KDoc placement cleanup, crossfade plan logging extraction, centralized exception reporting, and playlist retry extraction. A full app unit test run reported 262 tests with three failures: two assertions in `GlassTintTokensTest` and one in `BotDetectionMitigatorTest`. Those failures remain unresolved, so the full suite cannot be described as passing. No device performance benchmarks were run; this cleanup makes no measured performance claims.

The next cleanup pass should keep working through large, high-churn files and complex functions, preserving behavior and adding or updating tests only where a refactor changes a meaningful decision path. Module consolidation needs a separate dependency and ownership review before moving code.

## 2026-10-12 current-worktree update

The current source inventory has 1,026 production Kotlin files and 68 Kotlin test files across seven Gradle modules. The app contains 732 production Kotlin files. The largest files checked in this pass are `MusicService.kt` (4,177 lines), `Player.kt` (3,527), `Player_v2.kt` (2,432), `Lyrics.kt` (2,211), and `DatabaseDao.kt` (1,919). File size alone is not a defect, but these are the best places to keep decomposing responsibilities for code reading.

The three-file `artistvideo` module was moved into `app`: all consumers were in the app, and its UI and Media3 dependencies were already present there. The app now includes seven Gradle projects. `lyrics` and `integrations` still share Ktor dependencies but represent separate domains; merging them only to lower the module count would weaken ownership boundaries. The other remaining modules have clearer standalone responsibilities, so this pass found no additional high-confidence merge.

This pass simplified media-library search assembly and media-item routing, playback service setup and transition scrobbling, pending room-sync naming, local MediaStore query construction, and Saavn/Tidal match and fallback helpers. Redundant comments were removed where the declarations or calls already explained the behavior; comments that explain invariants and fallback constraints remain.

Verification: `:app:compileUniversalGmsDebugKotlin` and `:app:compileUniversalFossDebugKotlin` passed after consolidating `artistvideo`. A later GMS compile passed after the Saavn search extraction. `git diff --check` passed on the touched files; Git reported only CRLF-to-LF normalization notices for two Kotlin files. No device runtime or performance benchmark was run. The app-wide cleanup is still in progress and is not a line-by-line review of all production files.

### Playback retry cleanup

`MusicService.PlaybackErrorRecoveryHandler` had five stream-error handlers repeating retry counting, job replacement, cache refresh, delay, seek, and prepare logic. These now share `retryStream`, with flags for cache clearing, restarting from the beginning, and bypassing quality cache. Audio renderer and decoder recovery remain separate because they pause playback and use different focus and timing rules. The GMS debug Kotlin compile passed after this change. No focused unit test covers this service-level retry flow, so this verifies compilation rather than runtime recovery behavior.

`startRadioSeamlessly` now delegates its related-songs fallback to `loadRelatedRadio`, which makes the normal radio path easier to scan. The fallback still runs only after an exception from the primary request; missing recommendations remain optional and leave the queue intact. The GMS debug Kotlin compile passed after this extraction.

The Home screen comment pass removed design-file identifiers and comments that repeated the surrounding state or color declaration. It retained comments that explain recomposition cost, background selection, and fallback behavior. The chart rail visibility conditions are now named before rendering, making the existing hero fallback rules easier to follow. `:app:compileUniversalGmsDebugKotlin` and `git diff --check` passed after this pass.

The Player V2 swipe code now uses shorter comments that describe the gestures and the axis-isolation requirement. Renamed the fling threshold from `SwipeFlingPxPerSec` to `SwipeFlingDpPerSecond`; its value is density-independent and is converted to pixels at the call site. `:app:compileUniversalGmsDebugKotlin` passed. The build reports two existing deprecated volume icon references in the same file.

In `LyricsMenu`, removed an obsolete commented-out romanization dialog and its now-unused state. The active inline setting and its song-change synchronization remain in place. The setting comment now describes why the value is refreshed. `:app:compileUniversalGmsDebugKotlin` passed after this cleanup.

In `ListenTogetherManager`, shortened event comments to state why room/session state survives transient disconnects and removed comments that only repeated the `bypassBuffer` argument or queue-title assignment. The immediate seek comment now explains its ordering before buffered sync. `git diff --check` passed; no executable code changed in this pass.

The `MainActivity` comment pass removed an obsolete note describing where navigation code had been moved and rewrote the player/navigation layer note around its current responsibility. A scan of app Kotlin sources found no remaining block comments that appear to contain disabled Kotlin code. `git diff --check` passed for the file; Git reports its existing CRLF line endings will normalize on a future write.

The local folder screen now routes both Play and Shuffle through one `playFolder` helper, which makes their only difference—the song order—explicit. Reindented the list under its `Box` to make the screen structure easier to follow. `:app:compileUniversalGmsDebugKotlin` and `git diff --check` passed. The nearby MediaStore comments were retained because they explain why the index and scanner use the same path rules and why displayed order is restored.

The local folder settings screen now shares the preference-write-then-rescan sequence between folder toggles and the folder picker, and no longer carries unused imports or a comment narrating the history of the permission prompt. The compile first exposed a missing `Screens` import in the working tree's new `AppNavigation.kt`; added the package import, then `:app:compileUniversalGmsDebugKotlin` passed. `git diff --check` passed for the settings screen. The compiler still reports the existing `hiltViewModel` package-move deprecation at this screen.

In `HomeFeedSections`, shortened two comments to state where the pinned mood filters live and why genre tiles use gradients without narrating their layout history. `git diff --check` passed; executable code is unchanged.

In `FloatingSideBar`, `HomeScreen`, and `GlassSwitch`, replaced comments describing past bugs or screen history with the current behavior they explain. `git diff --check` passed; executable code is unchanged.

In `OnlineSearchResult`, replaced the compound loading check with `isLoadingResults`, whose two branches correspond to unfiltered summary loading and filtered results loading. Removed an empty section comment and shortened the song queue explanation. In `LocalSearchScreen`, moved the clicked-song queue construction into `playSearchSongs` so the row click handler only chooses between toggle and queue. `:app:compileUniversalGmsDebugKotlin` and `git diff --check` passed after both changes.

`ComposeToImage.createLyricsImage` now reuses the `StaticLayout` that already fits instead of rebuilding it at the same text size. If no size fits, it still builds the layout at the same final size as before. `:app:compileUniversalGmsDebugKotlin` and `git diff --check` passed; no image-rendering test was run.

Expanded the comment scan into the standalone `hig` module. Removed an unused commented-out easing declaration from `CupertinoTopBars` and clarified why the haze fallback paints an opaque tint. `:hig:compileDebugKotlin` and `git diff --check` passed. The module sweep is ongoing.

In `Paxsenix.scoreAndFilterResults`, positive matches are now filtered before sorting and taking the top ten, avoiding sorting results that cannot be returned. `:lyrics:testDebugUnitTest` passed. There is no focused unit test for Paxsenix ranking, so the behavior preservation is based on the equivalent stable ordering of positive matches.

In `CupertinoSearchTextFieldNestedScroll`, clarified the two scroll comments: focused empty search can hold the list still, and the list consumes downward scroll until it reaches the top. `:hig:compileDebugKotlin` and `git diff --check` passed.

Reworded the Home shelf sizing comment in `HomeFigmaContent` so it explains why tile padding is added to the container width. `git diff --check` passed; rendering code is unchanged.

In `ModuleManager.loadModule`, extracted cached JS reads and writes into small suspend helpers. The load path now shows cache lookup, download fallback, JS initialization, then cache persistence in order; file errors remain non-fatal and the 64-file pruning behavior is unchanged. `:spine:testDebugUnitTest` and `git diff --check` passed.

In `MusicService.performAggressiveCacheClear`, split the source-cache key predicate across lines so each supported cache-key variant is visible. The predicate and cache operations are unchanged. `:app:compileUniversalGmsDebugKotlin` and `git diff --check` passed.

In `MusicService.recoverCodecFailure`, moved decoder error-code sets out of the playback error path and named the decoder-only and lossless-audio checks. The AAC and lossless fallback branches still use their original distinct conditions. `:app:compileUniversalGmsDebugKotlin` and `git diff --check` passed; no playback benchmark or device recovery test was run.

Continued simplifying `recoverCodecFailure`: reject missing media IDs once, then name the local-media, Opus-format, and AAC-eligibility checks before the two recovery branches. The one-time AAC retry and lossless fallback behavior are unchanged. `:app:compileUniversalGmsDebugKotlin` and `git diff --check` passed.

Removed the obsolete ringtone-settings permission check from four menu actions, along with its always-true helper and no-op request methods. The current action now opens the trimmer directly, which is the only path the permission check allowed. `:app:compileUniversalGmsDebugKotlin` and `git diff --check` passed; `rg` found no remaining references to the removed methods.

In `RingtoneHelper`, throttled download progress dispatches to the main thread to at most one update per percentage point instead of once per 8 KB read. Downloading and the 10–50% progress range are unchanged. `:app:compileUniversalGmsDebugKotlin` and `git diff --check` passed; no device download benchmark was run.

In `Player_v2`, shortened the remaining design-file comments to explain the wide-lyrics placement, timeline spacing, and controls-width constraint. Removed Figma identifiers and repeated pixel-by-pixel notes; layout code is unchanged. `git diff --check` passed.

In `IosMenuMaterial`, replaced design-history narration and Figma frame references with current material behavior: how blend colors map over frost, how dark liquid fill preserves the backdrop, and why the first frame paints a frost base. Blur-performance constraints remain documented. `git diff --check` passed; rendering code is unchanged.

In `IosMenu`, `Menu`, and `IosCompatMenus`, removed layout comments that repeated modifier values and design-file IDs. Kept the menu's press-slide selection behavior and the reason grouped menus reserve a separator band. `git diff --check` passed; UI code is unchanged.

In `FloatingNavBar`, removed comments that repeated font sizes and content padding values next to their modifiers, plus a design-file reference for icon size. Search-mode tap behavior and contrast notes remain. `git diff --check` passed; UI code is unchanged.

In the lyrics components, replaced typography frame IDs with short explanations of proportional tracking and tighter translation spacing. Rewrote the karaoke state-key comment to explain why line changes reset scroll and animation state. `git diff --check` passed; rendering code is unchanged.

Cleaned comment-only design references from `ChipsRow`, `NewMenuComponents`, `HomeHeroCard`, and `AppleDetailHeader`; retained a plain explanation that a selected chip becomes a pill and that the full navigation title is clickable. `git diff --check` passed; UI code is unchanged.

Removed two empty `//` separator comments from the blur implementations, retaining attribution and algorithm explanations. A scan of production Kotlin now finds no empty line comments; `git diff --check` passed.

In `IosColors`, rewrote two theme comments to describe the current dark-label opacity and black system background without version history or design-frame references. `git diff --check` passed; color values are unchanged.

Shortened theme comments in `Theme`, `HeroTheme`, and `IosTypography` while retaining why the shared top bar needs a separate text-color override, why hero screens reapply it, and how typography hierarchy is formed. `git diff --check` passed; theme behavior is unchanged.

Fixed indentation of the two PoToken assignments inside their existing `try` blocks in `YTPlayerUtils.resolvePlaybackData`, making the exception boundaries clear. No logic changed. `:app:compileUniversalGmsDebugKotlin` and `git diff --check` passed; the build still reports the two existing deprecated volume-icon references in `Player_v2`.

The Listen Together screen comment pass removed generic section labels and design-history narration, and shortened the room countdown, avatar, and action KDoc. It retained comments for server-side playback permissions, room expiry, and UI state behavior. `git diff --check` passed; no executable code changed in this pass.

The queue screen comment pass removed labels that repeated `useNewPlayerDesign`, shortened Figma-specific layout notes, and kept explanations for shared preferences, queue-edge fades, drag ownership, and saveable IDs. The secondary queue color now describes its use rather than its design-file source. `git diff --check` passed; executable code is unchanged.

The Player V2 comment pass shortened design-spec narration around lyrics translation, transport controls, slider layout, and the utility bar. It retained the recomposition guidance on the seek section and clarified the shared translation setting and touch-target spacing. `git diff --check` passed; no executable code changed.

The lyrics auto-scroll path now uses named viewport and item positions, a direct fallback when the target is off screen, and a flatter initial/seek/auto-scroll decision flow. Removed redundant intermediate variables and duration comments without changing the conditions or scroll targets. `:app:compileUniversalGmsDebugKotlin` passed; no focused UI test covers this effect.

The artist header play action is now a `when` over the playback and source cases, making pause, resume, local queue, online queue, and radio fallback visible as separate branches. Filtering and queue selection are unchanged. `:app:compileUniversalGmsDebugKotlin` passed after the refactor.

`SearchScreen` no longer carries unused tab-selection, search-active, or content-visibility state; these values had no readers. Removed their sole `rememberSaveable` import and clarified that results read the live nav-bar query. `:app:compileUniversalGmsDebugKotlin` and `git diff --check` passed.

In `MusicService`, `addToQueueAutomix` and `playNextAutomix` now share the suggestion-removal step, while the current-song lookup comment states why it reads player metadata directly instead of the potentially stale flow. `:app:compileUniversalGmsDebugKotlin` passed after the refactor.

Updated the stale `DownloadUtil` context comment to describe its current preference, image-loading, and download uses. Cache-key and stream-resolution comments remain because they explain correctness constraints. `git diff --check` passed; this comment-only change did not require a rebuild.

The MusicService comment pass shortened three explanations around player preferences, liked-track downloads, and track artwork transitions. Kept the reasons these behaviors are coordinated across components. :app:compileUniversalGmsDebugKotlin and git diff --check passed; executable behavior is unchanged.

Removed design-file IDs from four UI comments. Kept the artwork mirroring explanation, color-dodge behavior, tab spacing rationale, and page spacing values because they explain implementation or layout decisions. git diff --check passed; comment-only changes.

Removed remaining design-file identifiers from UI comments, retaining descriptions of current layout and rendering behavior. Simplified UpNextSong by removing two null fallbacks that Kotlin reported as unnecessary for non-null title and artist-list values. :app:compileUniversalGmsDebugKotlin and git diff --check passed.

In `DeepLService`, replaced nested translation-count branches and a mutable padding loop with `fitTranslationLines`. The helper returns exactly the requested number of lines, truncating extras and filling missing lines with empty strings as before. `:app:compileUniversalGmsDebugKotlin` and `git diff --check` are being run for this change.

The translation line-count helper is now shared by DeepL, Mistral, and OpenRouter instead of being duplicated in each provider. Added focused tests for unchanged, truncated, and padded results.

Verification for the shared translation helper passed: `:app:testUniversalGmsDebugUnitTest --tests com.convx.music.api.TranslationLinesTest`, `:app:compileUniversalGmsDebugKotlin`, and `git diff --check`.

Mistral and OpenRouter also had the same response-content parser. Moved that parser beside the shared line-count helper, so both providers now use the same markdown cleanup, JSON extraction, and plain-line fallback. Added focused parser tests.

The shared Mistral/OpenRouter parser and translation line helper passed `:app:testUniversalGmsDebugUnitTest --tests com.convx.music.api.TranslationLinesTest`; `:app:compileUniversalGmsDebugKotlin` and `git diff --check` also passed.

OpenRouter streaming now uses the shared `fitLineCount` and JSON-array parser, removing a second copy of each utility while keeping its streaming-specific fallback behavior intact.

OpenRouter streaming also passed the focused translation-line tests and app Kotlin compile after switching to the shared line-count and JSON-array helpers. `git diff --check` passed.

The shared line-count, JSON-array, bracket extraction, and plain-line helpers passed the focused translation tests and app Kotlin compile after OpenRouter streaming adopted them. Its response-specific fallback behavior remains in the streaming parser. `git diff --check` passed.

Removed an empty separator comment from `KeyDetectorTest` while keeping the explanation of why the minor-key fixture distinguishes it from its relative major. A production-source scan found no AI-identifying comments or empty line-comment placeholders outside that test. `git diff --check` passed.

In `MainActivity.DeepLinkRouter`, moved ID validation out of the intent handler and reused a compiled allowlist regex. The accepted character set and length limits stay the same, while each ID check no longer compiles a new regex. `:app:compileUniversalGmsDebugKotlin` and `git diff --check` passed.

In `MainActivity.DeepLinkRouter`, separated debug-screen intents into `handleDebugIntent`. The normal shared-link path now starts immediately after that early-return check; debug route validation, preference updates, navigation, and logging are unchanged. `:app:compileUniversalGmsDebugKotlin` and `git diff --check` passed.

`DeepLinkRouter.handleDeepLinkIntent` now delegates normal links to named helpers for room joins, playlists, searches, and YouTube links. Each route keeps its previous validation, navigation destination, and async playback behavior. The first compile found the missing `Uri` import; after adding it, `:app:compileUniversalGmsDebugKotlin` and `git diff --check` passed.

A follow-up formatting pass made Listen Together code extraction explicit and kept the non-null result from `cleanId` as a direct null check. The app Kotlin compile and `git diff --check` passed again.

Removed an unreadable mojibake comment above `initializeSettings()` in `App`. The comment only repeated the startup call. A Kotlin-source scan found no other mojibake comments; `git diff --check` passed. No executable code changed.

## Current source snapshot (2026-10-12)

The repository currently includes 7 Gradle projects. There are 732 Kotlin main-source files and 192,499 lines in `app`; all modules together contain 1,026 Kotlin main-source files and 229,137 lines. There are 68 Kotlin test source files across `app`, `canvas`, `innertube`, `spine`, `lyrics`, and `integrations`; `hig` has no test source directory.

The current worktree has 805 `git status --short` entries. It contains ongoing changes from multiple sources, so these counts cannot be attributed to this cleanup. Recent focused unit tests and `:app:compileUniversalGmsDebugKotlin` have passed, but this does not verify every module or runtime path. No device performance benchmark has been run. The app-wide readability and comment review remains incomplete.

In `SpotifyMapper`, the direct and precomputed match paths now use one weighted-score helper with named title, artist, and duration weights. Added a regression test confirming both paths return the expected score. `:integrations:testDebugUnitTest --tests com.music.spotify.SpotifyMapperTest`, `:integrations:compileDebugKotlin`, and `git diff --check` passed.

Removed the obsolete commented-out `.rotate(-90f)` modifier from `CupertinoActivityIndicator`; it had no effect on the rendered indicator. `:hig:compileDebugKotlin` and `git diff --check` passed.

In `SimpMusicLyrics.getAllLyrics`, changed the result loop to stop iterating after the existing result limit is reached. The same duration filtering and emitted lyrics are preserved; `continue` retains the behavior for non-matching tracks. `:lyrics:compileDebugKotlin`, `:lyrics:testDebugUnitTest`, and `git diff --check` passed.

Restored two short comments on `DeepLinkRouter`: the ID allowlist prevents route injection, and debug intents exist for emulator navigation tests. These explain why the checks exist. `:app:compileUniversalGmsDebugKotlin` and `git diff --check` passed.

Removed three commented-out range annotations from the Cupertino slider and swipeable APIs. They had no effect on parameter validation; the adjacent progress contract remains documented. `:hig:compileDebugKotlin` and `git diff --check` passed, and no commented range annotations remain in source Kotlin.

The local scanner's existing-track path no longer loads a full `Song` relation for each track just to backfill dates. A conditional DAO update fills `inLibrary` only when absent and fills `dateModified` only when absent; it leaves all other columns untouched. Room/KSP and `:app:compileUniversalGmsDebugKotlin` passed. No device scan benchmark or dedicated DAO behavior test was run.

`LrcLib.getAllLyrics` had the same non-breaking iteration after its result cap. It now breaks when the cap is reached and uses `continue` for the synced-only branch, preserving the existing cancellation check and callback order. `:lyrics:compileDebugKotlin`, `:lyrics:testDebugUnitTest`, and `git diff --check` passed.

`AppleArt.prefetchArtistIds` now breaks as soon as the shared warming set reaches its four-item cap. Duplicate in-flight artist IDs still continue to the next candidate, and the existing four-name sequence limit remains unchanged. `:app:compileUniversalGmsDebugKotlin` and `git diff --check` passed.

In `LyricsUtils.romanizeChinese`, moved the ASCII and CJK punctuation-spacing regexes out of the call path so repeated requests reuse compiled patterns. The pattern text and replacement order are unchanged. `:app:compileUniversalGmsDebugKotlin` and `git diff --check` passed; no focused romanization unit test exists.

In rich-sync lyric parsing, moved the timestamp-tag regex out of the per-line parser so it is compiled once and reused. The matching pattern and text cleanup are unchanged. `:app:compileUniversalGmsDebugKotlin` and `git diff --check` passed; no focused rich-sync parser test exists.

In Musixmatch track matching, moved the four `cleanText` regexes to object-level constants so candidate scoring reuses them. Replacement order and patterns are unchanged. Lyrics module compile and unit tests passed.

In Paxsenix Apple Music token refresh, moved the index-script and token regexes out of the refresh method so repeated refreshes reuse them. Match patterns and error behavior are unchanged. Lyrics module compile and unit tests passed.

In `MusicService.onCreate`, extracted foreground notification setup into `startForegroundNotification`. The call remains in the same startup position, with the same channel, pending intent, foreground type, exception logging, and exception reporting. `:app:compileUniversalGmsDebugKotlin` passed.

`MusicService.onCreate` now delegates media session wiring and initial repeat/shuffle settings to `initializeMediaSession`, at the same point after audio focus setup. Session callbacks, notification intent, bitmap loader, and controller creation are preserved. `:app:compileUniversalGmsDebugKotlin` passed.

The audio-quality, stream-source, and IP-version preference collectors in `MusicService.onCreate` now live in `observePlaybackPreferenceChanges`. The shared first-emission flag and collector launch order remain unchanged. `:app:compileUniversalGmsDebugKotlin` passed. Runtime playback preference changes were not exercised on a device.

Grouped the remaining long startup setup in `MusicService.onCreate` into named helpers for volume/lyrics observation, audio processing preferences, Discord/scrobbling preferences, transition preferences, persisted-state restoration, and queue persistence. The helper calls remain in their original order, so collectors are registered in the same sequence. `:app:compileUniversalGmsDebugKotlin` passed; runtime startup and playback settings were not exercised on a device.

Removed the stale inline `//added for audio device switching` and `//improvement` notes beside `preferredDeviceId`; the property and access restriction already state what the code does. The broad comment scan found no remaining explicit AI attribution phrases in production Kotlin. `git diff --check` passed.

Simplified the automatic lyrics collector in `MusicService` with early exits for disabled prefetch, missing metadata, and already-saved lyrics. The transaction still rechecks the database before writing so a user-selected source wins a race. Also expanded the loudness update lambda for readability. `:app:compileUniversalGmsDebugKotlin` passed.

Removed a redundant state comment from `CupertinoBottomSheet`; the preceding branch already excludes partially expanded sheets, so the remaining path is the expanded case. `git diff --check` passed; no executable code changed.

Corrected `CupertinoTextFieldDefaults.MinHeight` documentation, which incorrectly described it as a width, and shortened both min-size notes to state the dimension and override modifier. `:hig:compileDebugKotlin` passed.

Removed frame-number references from `AppleContextMenu` comments and rewrote `AppleDetailHeader` size notes to describe the current proportions and control grouping. `git diff --check` passed; executable code is unchanged.

In `BottomSheetPlayer`, replaced an exhaustive list of non-default backgrounds with the equivalent default-background check, and reused the already-derived dark-theme value for pure-black mode. This keeps the same outputs while removing duplicated conditions. `:app:compileUniversalGmsDebugKotlin` passed.

Split `MusicService`'s Discord and scrobbling preference collectors into separate helpers. `onCreate` still registers the collectors in the same order. `:app:compileUniversalGmsDebugKotlin` passed.

Flattened `LyricsUtils.parseRichSyncLyrics` by returning early for lines that do not match and for empty lyric text. Rewrote `parseStandardLyrics` as a direct indexed loop, preserving the association between a lyric line and the word-timing line after it. Timestamp conversion, agent/background handling, sorting, and result fields remain unchanged. Added rich-sync and standard-format parser tests. `:app:testUniversalGmsDebugUnitTest --tests com.convx.music.lyrics.LyricsUtilsTest` passed.

`parseLyrics` now filters lines through a sequence before materializing the list needed by indexed parsers, avoiding the intermediate list created by `lines().filter(...)`. Both parser tests passed after this change with `:app:testUniversalGmsDebugUnitTest --tests com.convx.music.lyrics.LyricsUtilsTest`.

Rewrote `DatabaseDao.insert(AlbumPage)` from chained list operations into explicit steps for inserting the album, songs, song links, artists, and artist links. Preserved the transaction and database write order, including artist-ID cache updates. `:app:compileUniversalGmsDebugKotlin` and `git diff --check` passed. There is no focused Room test for this method, so database runtime behavior remains unverified.

Applied the same explicit-step structure to `DatabaseDao.update(AlbumEntity, AlbumPage, ...)`: album update, old-artist cleanup, song writes, and replacement artist links are now easy to follow. Kept the previous conditional deletes and write order. `:app:compileUniversalGmsDebugKotlin` passed; no focused Room test covers this method.

Flattened `AppleMusicArtistBackgroundProvider.fetchArtistMotionByAppleId` to return the first usable editorial-video URL, then check editorial artwork as fallback. Removed comments that repeated the visible order and formatted the preferred URL keys one per line. `:canvas:compileKotlin` and `git diff --check` passed; this HTTP path has no focused response test.

In `AppleMusicCanvasProvider`, removed comments that restated album filtering and URL key lookup, clarified title override behavior, and hoisted artist matching regexes so each search reuses them. The editorial asset lookup keeps key order and eager JSON parsing while building only one filtered list. `:canvas:test` passed.

In `AccountMapper`, named the JSON stages in `visitorData`, shortened the account-channel KDoc to its contract, and removed a `runCatching`/non-local-return wrapper from `getMediaInfo`, which already returned a `Result` from its delegate. `:innertube:compileDebugKotlin`, `:innertube:testDebugUnitTest`, and `git diff --check` passed. The test suite has no direct visitor-data parser case.

Shortened the glass settings preview comment to explain the required backdrop sibling relationship and why sampling the ambient backdrop would recurse into the renderer. Removed historical screen-crash references while retaining the reason for the capture structure. `git diff --check` passed; executable code is unchanged.

Removed implementation-history phrasing from local-music permission, stroke clipping, player landing, and Apple library backdrop comments. Each now states the current behavior or constraint directly. `git diff --check` passed; executable code is unchanged.

Removed the malformed Gemini note and shortened comments in Discord login, theme settings, Home ordering, playlist synchronization, and the local song shelf to describe current behavior. The Motorola user-agent issue link remains. These edits do not change executable code; `git diff --check` passed.

In Spine's HTTP download helper, read each response body once and reuse it for both error parsing and the successful return path. This avoids a redundant body read while preserving status handling and addon error messages. `:spine:compileDebugKotlin` and `git diff --check` passed.

In the smart transition planner, replaced the filter-plus-minimum chain for phrase/downbeat candidates with a single pass. It keeps the same finite-value, minimum, and tolerance checks and preserves the first candidate when distances tie. `:app:compileUniversalGmsDebugKotlin` and `git diff --check` passed. The repository has no focused transition-planner test, so only compile-time validation covers this change.

Rewrote the planner's latest-valid-value lookup as a single pass with the original finite, minimum, target, and tolerance checks. Also removed three section labels from `Player_v2.kt` that only named the immediately following layout block. `:app:compileUniversalGmsDebugKotlin` passed; it reported two existing deprecated volume-icon references in `Player_v2.kt`.

In `YouTubeUtils.resize`, hoisted three reusable URL regexes and replaced prefix-only `split` calls with `substringBefore`. Added regression cases for Google artwork, Google avatar, and Apple artwork URLs, and replaced test comments that described old regressions with a short statement of what the tests cover. `:app:testUniversalGmsDebugUnitTest --tests com.convx.music.ui.utils.ThumbnailResizeTest` and `git diff --check` passed.

In `AppIcon.apply`, replaced the filtered list used to disable other launcher aliases with a direct loop; the selected alias is still enabled first. Removed a stale Wrapped-page history note and a comment that only restated `ModernSwitch`'s delegation, and rewrote the iOS app-bar note to state the current backdrop behavior. `:app:compileUniversalGmsDebugKotlin` and `git diff --check` passed. The compiler still reports an experimental API warning at the existing `CupertinoTopAppBar` call.

Rephrased the two duplicated swipe comments in `MiniPlayer` to describe the current velocity check, and removed an obsolete comparison to the previous landing-spring tuning. These are comment-only changes; `git diff --check` passed.

Removed historical wording from the loading dialog, transition settings, and grouped-section radius comments, keeping concise descriptions of their current purpose. Replaced `AppIcon.apply`'s filtered temporary list with a direct loop while preserving the enable-before-disable order. The app Kotlin compile passed before these comment-only edits; `git diff --check` passed afterward.

In `TransitionPlanner`, reused the single-pass latest-valid-value helper instead of keeping a second filter-and-maximum implementation. A full app compile exposed an incomplete `MainActivity` edit in the current worktree: a stray `so w` token and missing intent/action constants still used at call sites. Removed the token and restored the shortcut action values from the committed version, plus the intro extra shared with `WelcomeActivity`. `:app:compileUniversalGmsDebugKotlin` and `git diff --check` passed after these fixes. Compilation still emits existing deprecation and experimental API warnings elsewhere in the app.

In `buildHomeSections`, moved seeded section score calculation out of the sort comparator and into `randomizedSectionWeight`, so each section's seeded random score is computed once instead of on every comparison. The same seed, per-section random stream, weights, and stable descending sort are retained. Removed unused imports from the file. `:app:compileUniversalGmsDebugKotlin` and `git diff --check` passed; there is no focused `buildHomeSections` test.

Moved the default Home section weights to a file-level map so feed assembly does not recreate the constant map on each call. All weights and sort precedence remain unchanged. `:app:compileUniversalGmsDebugKotlin` and `git diff --check` passed.

Removed the unnecessary `run` wrapper from `buildHomeSections` and returned its ordering branch directly. The hidden-section filtering, custom order, seeded shuffle, and default order still run in the same sequence. `:app:compileUniversalGmsDebugKotlin` passed, and a direct whitespace check of the untracked source file found no trailing whitespace.

Shortened the `PlayerV2Canvas` overview to describe the current single-player lifecycle and clarified why fetched artwork is tagged with its media ID across `produceState` key changes. Expanded storefront normalization into named country-code and storefront values without changing the two-letter lowercase/fallback behavior. `:app:compileUniversalGmsDebugKotlin` and `git diff --check` passed.

Added `HomeSectionOrderTest` coverage for default section order, saved newline-delimited order, hidden sections, and stable per-section seeded ordering when more sections appear. `:app:testUniversalGmsDebugUnitTest --tests com.convx.music.ui.screens.HomeSectionOrderTest` passed; app test source count is now 50.

Replaced `NavBarSearchInputBar`'s migration-history KDoc with its current contract: the focused search input replaces the navigation bar and follows the keyboard. `git diff --check` passed; executable code is unchanged.

In `resolveSingerNames`, replaced the two-list trim/filter chain with `mapNotNull` and built the ranked name map with a direct indexed loop. The same credit trimming, lead ranking, majority threshold, and insertion order are preserved. Added tests for ranking, background-line exclusion, whitespace trimming, and ambiguous lead counts. `:app:testUniversalGmsDebugUnitTest --tests com.convx.music.ui.component.SingerBadgeNameTest` and `git diff --check` passed; app test source count is now 51.

Extracted the AI DJ concert-role ordering into a pure helper and replaced repeated list membership checks with a set lookup. Seed-first order, opener/rising/peak/closer order, and input order for unassigned roles are retained. Added focused role-order tests. Their Gradle test task could not reach test execution because app compilation currently fails in the independently modified `DatabaseDao`/`HistoryViewModel` path: generated `DatabaseDao_Impl` lacks `historyEventsPage`, and `HistoryViewModel` has unresolved `observeEvents` references. The changed helper has no reported compiler errors; whitespace checks passed. App test source count is now 52.
