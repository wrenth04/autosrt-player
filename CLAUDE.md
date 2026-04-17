# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and test commands

- Build debug APK: `./gradlew assembleDebug`
- Build the project: `./gradlew build`
- Run all unit tests: `./gradlew test`
- Run app unit tests only: `./gradlew :app:testDebugUnitTest`
- Run a single unit test class: `./gradlew :app:testDebugUnitTest --tests "com.example.autosrtplayer.data.playlist.PlaylistParserTest"`
- Run a single test method: `./gradlew :app:testDebugUnitTest --tests "com.example.autosrtplayer.data.playlist.PlaylistParserTest.parse playlist with headers and subtitle"`
- Android lint: `./gradlew lint`

Environment notes:
- CI uses Java 17.
- This is a single-module Android project with only `:app` included.

## High-level architecture

This repository is a Kotlin Android app built with Jetpack Compose for UI and Media3 ExoPlayer for playback. The app’s core flow is: fetch or accept EXTM3U content, parse playlist metadata, convert it into a Media3 `MediaItem`, then create and display an ExoPlayer instance.

### Main execution flow

1. `app/src/main/java/com/example/autosrtplayer/MainActivity.kt`
   - App entry point. Renders `PlayerScreen()` inside the Compose theme.

2. `app/src/main/java/com/example/autosrtplayer/ui/PlayerScreen.kt`
   - Main screen and UI orchestration.
   - Presents two input paths: playlist URL or pasted EXTM3U text.
   - Displays parsed playlist metadata and hosts inline/fullscreen player UI.
   - Recreates and prepares the player when the parsed media item changes.

3. `app/src/main/java/com/example/autosrtplayer/ui/PlayerViewModel.kt`
   - Coordinates the entire app flow.
   - Owns `PlayerUiState`, fetches playlist text from remote URLs, parses playlist content, builds the playback item, and exposes fullscreen/loading/error state.
   - This is the main place to change behavior when adjusting input handling, parsing flow, or UI state transitions.

4. `app/src/main/java/com/example/autosrtplayer/data/playlist/PlaylistRepository.kt`
   - Network boundary for loading playlist text via OkHttp.
   - Only responsible for downloading the raw playlist body.

5. `app/src/main/java/com/example/autosrtplayer/data/playlist/PlaylistParser.kt`
   - Converts EXTM3U text into a `PlaylistEntry`.
   - Extracts title from `#EXTINF`, request headers from `#EXTVLCOPT`, subtitle URL from `#EXTSUB`, and the first non-comment media URL.
   - If `#EXTSUB` is missing, it derives a `.srt` subtitle URL from the playlist URL when the playlist ends in `.m3u` or `.m3u8`.

6. `app/src/main/java/com/example/autosrtplayer/data/playback/MediaItemBuilder.kt`
   - Converts `PlaylistEntry` into a Media3 `MediaItem`.
   - Assumes HLS media and attaches subtitle configuration for `.srt` and `.vtt` only.

7. `app/src/main/java/com/example/autosrtplayer/data/playback/PlayerFactory.kt`
   - Creates ExoPlayer instances backed by an OkHttp data source.
   - Applies parsed `User-Agent` and `Referer` headers to playback requests.

### State model

- `app/src/main/java/com/example/autosrtplayer/ui/PlayerUiState.kt` is the single UI state container.
- `parsedEntry` is the parsed playlist model.
- `mediaItem` is the playback-ready representation derived from `parsedEntry`.
- If playback behavior and displayed metadata diverge, inspect the path from `PlaylistParser` -> `PlaylistEntry` -> `MediaItemBuilder` -> `PlayerScreen`.

### Test coverage

- `app/src/test/java/com/example/autosrtplayer/data/playlist/PlaylistParserTest.kt`
  - Current unit tests focus on playlist parsing rules and subtitle MIME inference.
  - If changing EXTM3U parsing, subtitle derivation, or supported subtitle extensions, update these tests first.

## Project structure notes

- Root Gradle files only configure plugins and repository resolution.
- Nearly all app logic lives under `app/src/main/java/com/example/autosrtplayer/`.
- There are no additional app modules, backend services, or custom build tooling in this repository.
