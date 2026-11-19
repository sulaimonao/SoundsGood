# SoundsGood — Local Android Music Player

SoundsGood is a native Android local music player built with Kotlin, Jetpack Compose (Material 3), AndroidX Media3 ExoPlayer, and Room. All audio scanning, playback, listening analytics, and smart playlist rules execute locally on-device with no cloud account or external service dependency.

---

## Selected Dependency Versions

Recorded in `gradle/libs.versions.toml`:

| Component | Coordinate / Plugin | Version |
|---|---|---|
| Android Gradle Plugin | `com.android.application` | `9.1.1` |
| Kotlin / Compose / Serialization | `org.jetbrains.kotlin.*` | `2.2.10` |
| KSP | `com.google.devtools.ksp` | `2.3.5` |
| Compile / Target / Min SDK | Android SDK | `36` / `36` / `24` |
| Jetpack Compose BOM | `androidx.compose:compose-bom` | `2024.09.00` |
| Navigation Compose | `androidx.navigation:navigation-compose` | `2.8.9` |
| AndroidX Media3 (ExoPlayer, Session, Common) | `androidx.media3:media3-*` | `1.6.1` |
| Room (Runtime, KTX, Compiler) | `androidx.room:room-*` | `2.7.0` |
| Coil Compose | `io.coil-kt:coil-compose` | `2.7.0` |
| Kotlinx Serialization JSON | `org.jetbrains.kotlinx:kotlinx-serialization-json` | `1.8.0` |
| Kotlinx Coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-*` | `1.10.2` |
| Robolectric (JVM tests) | `org.robolectric:robolectric` | `4.16.1` |

---

## Implemented Architecture & Features

### 1. Local Audio Scanning & Picker
- **`MediaStoreScanner`**: Queries `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` via `ContentResolver` on `Dispatchers.IO`, retrieving title, artist, album, album artwork URI, duration, date added, MIME type, file size, and genre (API 30+ `MediaStore.Audio.Media.GENRE` with legacy `MediaStore.Audio.Genres` fallback on API 24–29), with clean fallbacks for missing tags.
- **Permissions**: Requests `READ_MEDIA_AUDIO` on Android 13+ (API 33+) and `READ_EXTERNAL_STORAGE` (`maxSdkVersion="32"`) on Android 12 and below, plus graceful permission-denied and app-settings recovery states.
- **Document Picker**: `ActivityResultContracts.OpenMultipleDocuments()` lets users pick individual local audio files (MP3, FLAC, AAC/M4A, Ogg/Vorbis) even without full library access, attempting `takePersistableUriPermission()` and reading metadata via `MediaMetadataRetriever` + `OpenableColumns`.
- **Non-destructive Rescan**: `TrackDao.syncMediaStoreTracks` updates additions, tag edits, and removals by deterministic `trackKey` (`ms:<id>` / `doc:<uri>`) without duplicating tracks or erasing `track_stats` / `moodTag`.

### 2. Background Playback, On-Device Privacy & Lifecycle (Media3)
- **`PlaybackService : MediaSessionService`**: Owns a single `ExoPlayer` and `MediaSession`, configured with `USAGE_MEDIA` / `AUDIO_CONTENT_TYPE_MUSIC` (`handleAudioFocus = true`), `setHandleAudioBecomingNoisy(true)` for headphone/Bluetooth disconnects, `C.WAKE_MODE_LOCAL`, and `DefaultMediaNotificationProvider` (`foregroundServiceType="mediaPlayback"`).
- **`PlaybackClient`**: Connects `MainActivity` via `MediaController` in `onStart()` and releases it in `onStop()`, ensuring screen rotation and background/foreground transitions reconnect to the same active player without duplicate playback.
- **Race-Free Snapshot Restore & Resumption (`PlaybackRestoreCoordinator`)**:
  - `restoreSnapshotOnStartup()` and `SessionCallback.onPlaybackResumption()` track a player-thread `userCommandGeneration` token and active job/future references.
  - Any user queue or playback command on the session player increments the generation and cancels in-flight restore/resumption work immediately.
  - Immediately after `loadPlaybackSnapshot()` returns to the player thread—with no further suspension—state and generation are rechecked (`mediaItemCount == 0`, `!playWhenReady`, `!isPlaying`, `STATE_IDLE`, unchanged generation) before mutating the player, so a user's newly selected track always wins while genuinely idle startups still restore the saved snapshot paused.
- **Bounded Error Recovery (`PlaybackErrorRecovery`)**:
  - Tracks failed queue occurrence indices (`0 until mediaItemCount`) within the active recovery pass, advancing along the active traversal order (normal or shuffled) without retrying a failed occurrence in the same pass, handling duplicate queue occurrences distinctly by index.
  - Normalizes `REPEAT_MODE_ONE` during error recovery and bounds `REPEAT_MODE_ALL` passes so an all-broken queue terminates cleanly (`playWhenReady = false`, `stop()`, user-facing error banner) instead of looping forever.
  - Clears the active listening session without incrementing `skipCount` on file/codec errors, and resets the recovery pass once `STATE_READY` / `isPlaying` is reached or a new user command arrives.
- **On-Device Backup Exclusion**:
  - `AndroidManifest.xml` sets `android:allowBackup="false"`, and both `res/xml/backup_rules.xml` (`<full-backup-content>`) and `res/xml/data_extraction_rules.xml` (`<cloud-backup>` and `<device-transfer>`) explicitly exclude `database`, `file`, `sharedpref`, `external`, `root`, and their `device_*` equivalents (`device_database`, `device_file`, `device_sharedpref`, `device_root`) so Room data, listening history, smart playlist rules, and playback snapshots stay strictly on-device. No `INTERNET` permission or backup/account service is declared.

### 3. Listening Counting Rules, Shuffle & Smart Playlists
- **Single-Count Play/Skip Rules (`ListeningRules`)**:
  - **Play**: Recorded once per `ActiveTrackSession` (enforced both in-memory and via SQLite `UNIQUE(playbackSessionToken, eventType)`) when active playing time (excluding pauses/seeks) reaches `min(30s, max(5s, duration / 2))` or on natural track completion.
  - **Skip**: Recorded once per session when the user explicitly skips away before qualifying as a play, provided at least `1.5s` of active playback elapsed.
- **Transparent Preference Score**:
  - `preferenceScore = (playCount * 1.0) - (skipCount * 0.5)`.
- **Unbiased Shuffle vs Preference Ranking**:
  - Playback shuffle uses `ShuffleEngine` (uniform **Fisher-Yates** permutation, anchored at the current track) wrapped in `DeterministicShuffleOrder` so `Next` and `Previous` traverse a consistent order independent of preference weights.
  - Supports **Repeat Off**, **Repeat All**, and **Repeat One**.
- **Smart Playlists & Mac Synced Playlists**:
  - Built-in **Recently Added** and **Most Played** playlists plus editable rule playlists (duration range, artist, album, genre, explicit user **Mood Tag**, minimum plays, sort order, and max track limit). Mood is strictly an explicit user tag, never inferred from missing metadata.
  - **Synced from Mac** playlists live in separate Room tables (`synced_playlists` / `synced_playlist_entries`) alongside Smart Playlists, clearly badged with the Mac library name, preserving exact order and repeated occurrences of the same song.

### 4. Local Wi-Fi Sync from Mac Companion
- **Pairing URI & Session-Only Credentials (`PairingUriParser`)**:
  - Accepts pasted links or Android `VIEW` deep links matching `soundsgood://pair?v=1&host=<rfc1918-ipv4>&port=<1024..65535>&fingerprint=<64-lower-hex>&token=<43-base64url>` with empty path, no fragment, no userinfo, and no duplicate/extra query parameters.
  - Rejects DNS names, public IPs, loopback/link-local addresses, non-canonical IPv4 octets, and clears pasted/Intent URI inputs immediately after consumption. Pairing tokens stay strictly in memory for the current session and are redacted from logs/error strings.
- **Wi-Fi Network & Pinned TLS 1.2+ Security (`AndroidWifiNetworkSelector`, `PinnedTlsSyncTransport`)**:
  - Binds exclusively to a connected non-VPN Wi-Fi `Network` with a direct local IPv4 subnet route (API 24+ CIDR bitmask match) using `network.openConnection(url, Proxy.NO_PROXY)` and cancels active transfers immediately if Wi-Fi drops.
  - `usesCleartextTraffic="false"`. Scoped `X509TrustManager` verifies validity dates, self-signed signature, constant-time leaf DER SHA-256 fingerprint, and IPv4 SAN (type 7) **before** sending `Authorization: Bearer` headers. Refuses HTTP redirects (`instanceFollowRedirects = false`) and encoded/chunked bodies.
- **Staging, Atomic Commit & Room `MIGRATION_1_2` (`SyncFileStorage`, `SyncPlanner`, `LocalSyncManager`)**:
  - Manifests and audio streams validate strict headers (`Content-Length`, `Content-Type`, `X-SoundsGood-Revision`), allowlisted `(extension, mimeType)` pairs, and streaming byte count + SHA-256 into `.part` staging files before atomic promotion to immutable `filesDir/synced_audio/<sha256>.<ext>`.
  - `LocalSyncManager` runs transfers in the foreground only, keeps the screen awake only while transferring (`view.keepScreenOn`), survives rotation, and cancels active downloads cleanly when backgrounded (`MainActivity.onStop()` when `!isChangingConfigurations`). Incomplete `.part` files are removed while already-verified files remain reusable on retry; replacement downloads keep existing playable versions intact until commit.
  - Room v1 → v2 (`MIGRATION_1_2`) adds sync columns to `tracks` plus `synced_playlists` / `synced_playlist_entries` without modifying or erasing existing MediaStore/document tracks, stats, smart playlists, playback snapshots, or backup exclusions.

---

## How to Build & Run Exported Source

1. Open the exported project directory in **Android Studio** (or use a local Gradle 8/9 + JDK 17+ toolchain with `ANDROID_SDK_ROOT` configured).
2. Run unit & Robolectric tests:
   ```bash
   ./gradlew :app:testDebugUnitTest
   ```
3. Build the installable debug APK:
   ```bash
   ./gradlew :app:assembleDebug
   ```
   Output APK: `app/build/outputs/apk/debug/app-debug.apk`.
4. Install onto a connected device/emulator:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

---

## Verification Scope & Pending Device Checks

- **Source & Build Verification**: Compiled with `compile_applet` and `gradle :app:assembleDebug` (`app/build/outputs/apk/debug/app-debug.apk`), using only existing dependencies (no downloads added).
- **JVM & Robolectric Tests (`gradle :app:testDebugUnitTest`)**:
  - `DomainLogicUnitTest`: Fisher-Yates shuffle, `DeterministicShuffleOrder`, `ListeningRules`, transparent preference ranking.
  - `ExampleRobolectricTest`: `app_name`, Room rescan stats preservation, SQLite single-count deduplication, snapshot persistence, smart playlist evaluation.
  - `PlaybackErrorRecoveryUnitTest`: bounded recovery, shuffle wrap, duplicate queue occurrences, `REPEAT_MODE_ONE` normalization, `PlaybackRestoreCoordinator` generation checks.
  - `PlaybackRegressionRobolectricTest`: `allowBackup=false` + XML exclusions, delayed snapshot A vs user track B (`PlaybackService`), delayed `onPlaybackResumption` vs user track B (`PlaybackService`), idle snapshot restore (`PlaybackService`), broken-to-valid recovery without skip inflation, and all-broken `REPEAT_MODE_ALL` termination.
  - `LocalSyncProtocolUnitTest`: `PairingUriParser` strictness & redaction, `SyncManifestValidator` bounds & schema rules, `PinnedSelfSignedTrustManager` pin/IPv4 SAN/expiry/signature checks, and real JVM `SSLServerSocket` tests against `PinnedTlsSyncTransport` (valid handshake + headers, 302 redirect refusal, 401/409 handling, gzip/truncated body rejection, pin mismatch refusal).
  - `LocalSyncRobolectricTest`: Room `MIGRATION_1_2` data preservation, `AndroidWifiNetworkSelector` Wi-Fi vs cellular/VPN/subnet checks, injected-transport `LocalSyncManager` orchestration (verified downloads, ordered duplicate playlist entries, metadata-only stat/mood preservation, cancellation `.part` cleanup & retry reuse, replacement failure preserving playable prior audio, rescan survival, `MediaItemMapper` `file://` URI mapping, background cancellation, and `MainActivity` `soundsgood://pair` deep-link token scrubbing).
- **Pending Companion & Physical Device Checks (Unverified Here)**:
  - Compiling and running the Mac companion against this protocol, real Mac-to-Pixel local Wi-Fi TLS transfers across actual router subnets, Pixel camera/QR handler launch of `soundsgood://pair`, real device `keepScreenOn`/rotation/multitasking transfers, and hardware offline Media3 decoder playback of synced Mac audio tracks (MP3, AAC/M4A/ALAC, FLAC, Ogg, Opus, WAV, AIFF) remain pending physical Mac and Pixel verification.

