# BitChord — Convx Concert Mode and Stem Separation port

This BitChord source tree has been adapted to bring the Concert Mode and related stem-separation features from the supplied Convx 2.0.4 nightly project into BitChord's existing Android/Compose and Media3 architecture.

## Added to BitChord

- **Concert Mode settings entry and screen.** Build an artist/theme-based running order from YouTube Music search results. An optional OpenRouter-compatible API key lets the AI arrange the candidate songs; without a key, BitChord uses a basic search-result order.
- **Concert playback.** Each song is played as a planned highlight clip in BitChord's normal media queue. The show has opener/rising/peak/closer roles, optional spoken introductions and an outro using Android text-to-speech, and music ducking while the host speaks.
- **Fast stem separation.** UVR-MDX separation creates vocals and instrumental files. The included UVR model is bundled in `app/src/main/assets/uvr_mdxnet_9482.bin`.
- **Four-stem separation.** Optional Demucs separation creates drums, bass, other and vocals. A compatible `htdemucs` ONNX model is not included in the supplied Convx archive; the settings page accepts a direct model URL and checks the model before retaining it.
- **Stem settings and storage controls.** Choose Fast or Demucs quality, install/remove the optional Demucs model, split a local music library, play generated stems, view storage use, and clear saved stems.

## Integration notes and limits

- Concert playback uses BitChord's own `MediaController`, Media3 queue, crossfade-aware playback service, and audio DSP. It does not bring over Convx's database or replace BitChord's player.
- The setlist builder uses BitChord's `YtMusicRepository` and encrypted `AuthStore` for the optional AI credential.
- The library batch action scans local audio files available to BitChord. It does not reproduce Convx's database-specific beat-grid/energy analysis workflow.
- Stem separation requires a local `content://`/`file://` audio source or a fully cached stream rendition. Partial live streams are not enough. Whole-track decoding is capped at 12 minutes to match the upstream implementation and limit memory use.
- The optional Demucs model must be a compatible ONNX export and must be used in accordance with the model's own licence. The classic pretrained HTDemucs weights may have non-commercial licence terms; do not assume they are suitable for commercial distribution.
- The phone's installed text-to-speech engine supplies the concert host voice. If no usable voice is available, the music clips still play without narration.

## Build

The intended debug build command is:

```bash
./gradlew :app:assembleProdDebug --no-daemon
```

The build requires the Android SDK/platform configured by this project (compile SDK 37), the configured NDK/CMake toolchain, and a working Gradle distribution/dependency source. No APK is included in this source archive: the build could not be run in the current workspace because the Gradle wrapper distribution host was unreachable and no Android SDK was installed here.

## Licences

The adapted Convx files are identified in their headers and covered by the included Convx GPL-3.0 licence copy and `LICENSES/CONVX-NOTICE.md`. The Orchard-derived MDX implementation retains its AGPL-3.0-or-later notice in the source header; the official licence text is published at <https://www.gnu.org/licenses/agpl-3.0.html>.
