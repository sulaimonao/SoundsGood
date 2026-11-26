# SoundsGood Personal Edition

A native Android music player with on-device playback, listening history, smart
playlists and optional local Mac sync. Android 7.0 (API 24) or newer is required.

## License

Copyright © 2026 Akeem Oluwadamilare Sulaimon. This project is **source-available**
under the [SoundsGood Personal Use Only License](LICENSE), not OSI-approved open
source. Personal noncommercial use, source inspection, local compilation and
private modifications are free. GitHub viewing and forking rights are preserved.
Other redistribution, commercial use, resale, monetization and commercial derivative
products require separate written permission. Do not misrepresent authorship or
official affiliation. Third-party licenses and previously granted rights remain
unchanged; see [third-party notices](THIRD_PARTY_NOTICES.md).

SoundsGood Full is a separate commercial product. Its source is not included here;
the Personal license does not license Full. Contact: thedream.akeems@gmail.com.

## Privacy and documents

Private app data includes music metadata, listening events, playlists, mood tags
and playback state. Optional sync uses network permission to communicate with a
paired Mac over local Wi-Fi, including automatic transfer-result reports. The app
has no developer account, advertising or telemetry-upload service. Android backup
is disabled for private app data; shared music/recovery records and exported reports
remain outside that exclusion. File providers and OS folder sync can apply their
own cloud behavior. See the [Personal privacy policy](docs/personal-privacy.html).

The Library's **Personal Edition · About & legal** button opens bundled privacy,
license and attribution text without a network connection.

- [Full privacy](docs/full-privacy.html) and [Full terms](docs/full-terms.html)
- [Support](docs/support.html) and [Mac companion 0.3.1: availability, installation and pairing](docs/mac-companion.html) (signed and timestamped candidate; not notarized or published)
- [Build the Mac review image offline](companion/README.md) using the separate, authorized companion source checkout.
- [All public documentation](docs/index.html)

`docs/` contains static GitHub Pages files with no scripts, external fonts, tracking,
forms or storefront. Hosting is prepared locally; these links do not mean a site is
already published. Pages must be explicitly enabled after publication review.

## Personal edition behavior

MediaStore and selected-file imports feed a local Room library. Media3 handles
background playback. Local rules provide play/skip counting, fair shuffle, repeat,
and smart playlists. A selected Music folder stores audio and recovery metadata so
synced music can be recovered after reinstall. Synced playlists preserve ordering.

For Mac Wi-Fi sync, keep Personal Edition open and the screen on. Backgrounding
cancels its transfer; completed verified files can be reused when continuing.
Full's additional background-sync, annotation and backup features are not ported
into this edition. Actual playback support depends on encoding and device codecs.

## Build from source

Use the versions declared in this checkout's `gradle/libs.versions.toml` and
`gradle/wrapper/gradle-wrapper.properties`. The source baseline pins AGP 9.1.1,
Kotlin 2.2.10, KSP 2.3.5 and Gradle 9.3.1, with Android SDK 36.1 and minSdk 24.
Use a compatible JDK (the Android Studio bundled JBR can be used).

This export does not include Gradle wrapper launchers or the wrapper JAR. Use an
already installed compatible Gradle executable, not a nonexistent `./gradlew`:

```sh
gradle --offline :app:testDebugUnitTest
gradle --offline :app:assembleDebug
```

Offline commands require dependencies and the Android SDK already in local caches.
Set `ANDROID_HOME` to your SDK location, or create an untracked `local.properties`.
The inherited AI Studio debug signing configuration expects an untracked root
`debug.keystore`. Release signing expects a private keystore and environment
variables. No signing identity or key is supplied by this repository. Creating an
independent development key does not grant update compatibility with an installed
app. Never publish keys or install over an existing library without an explicit
backup and migration plan. Signing/build portability and a device-tested Personal
release remain release review items; source compilation alone is not installation
or release acceptance.

Public legal assets are regenerated with `python3 scripts/build_legal_assets.py`.
Verify prepared documents with `python3 scripts/verify_public_docs.py`.

Current local preparation compiled with a temporary installed-toolchain overlay
(Gradle 9.6.0, AGP 9.4.1, KSP 2.3.6); the source pins above remain unchanged.
The declared toolchain could not complete offline because some artifacts were
not cached. Four folder-recovery/protocol test failures reproduced on the unchanged
parent source under the installed toolchain. The broad run was unsuccessful and
cancelled, so no full-suite pass or device-release qualification is claimed.
