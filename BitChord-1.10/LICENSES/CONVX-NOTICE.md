# Convx feature-port notices

This project includes adapted source from the Convx open-source Android project
(`DOC-20261122-WA0000.zip`, release folder `Convx-2.0.4-nightly`), redistributed
under the GNU General Public License, version 3. The upstream project LICENSE
file is the controlling license for the adapted Convx code. Adapted files in
this port identify themselves in their headers and are located primarily in:

- `app/src/main/java/com/music/bitchord/playback/concert/`
- `app/src/main/java/com/music/bitchord/playback/smart/stems/`

The Concert Mode adapter and UI have been changed to use BitChord's playback
queue, media models, audio pipeline, Compose UI, storage, and settings.

`MdxVocalSeparator.kt` also carries forward the Orchard-derived vocal-separation
implementation and its AGPL-3.0-or-later licensing notice. The source header
identifies that license; its official text is at
https://www.gnu.org/licenses/agpl-3.0.html. The fast UVR model
asset was included in the supplied Convx archive and is bundled as
`app/src/main/assets/uvr_mdxnet_9482.bin` so the Android resource ignore rule
for `.onnx` files does not omit it.

The optional Demucs htdemucs ONNX model was not present in the supplied Convx
archive. BitChord asks the user for a direct download URL and validates the
model contract before retaining it.
