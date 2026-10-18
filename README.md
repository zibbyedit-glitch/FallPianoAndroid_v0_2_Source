# Fall Piano Android — v0.2 prototype

Native Kotlin Android project for a local-music rhythm game.

## Implemented in source
- Import a local audio file using Android's Storage Access Framework.
- Two modes: 4-lane falling tiles and an 8-lane falling-piano-keyboard visual.
- On-device audio decoding with Android MediaExtractor/MediaCodec.
- Basic onset detection based on short-window RMS energy.
- Rough tempo estimate from median intervals between detected onsets.
- Automatic rough difficulty label based on event density.
- Local/offline processing; no song upload.

## Important accuracy limits
This is not a finished commercial music transcription engine. Onsets are energy changes, so it can miss soft notes and can detect drums or chord attacks as note events. The lane mapping uses zero-crossing rate as a rough timbre proxy; it does **not** identify true musical pitch, chords, or a piano melody. Consequently, generated tiles follow some detected audio attacks but are not guaranteed to match the melody. Real note transcription for arbitrary polyphonic songs needs a specialized model such as a suitable on-device audio-to-MIDI/pitch model, or a server-side model if online processing is acceptable.

## Build
Open this folder in Android Studio with Android SDK Platform 35 installed, sync Gradle, then Build > Build APK(s). This source ZIP does not itself contain a compiled APK.

Package: `com.example.fallpiano`
Minimum Android: 8.0 (API 26)
Target SDK: 35
