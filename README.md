# Mischpult

*Mischpult* is German for a mixing console.

Two-deck DJ app for Android with the controls of a hardware console: music
files from the device, crates, waveforms, scratching, loops, hot cues, effects,
a sampler, MIDI controllers and set recording. Online sources are not built in:
they come as separate plugin apps (see [docs/PLUGINS.md](docs/PLUGINS.md)).

## Build

```bash
./gradlew :app:installDebug
```

Needs the Android SDK (compileSdk 37), JDK 21, NDK 29 and CMake 4.1 (Gradle
installs the last two on first build).

## How it works

```
Library: crates · device files (SAF) · online search via a source plugin
   │ Track
   ▼
TrackLoader ── SourcePlugins.prepare/openAudio   online tracks: the plugin's audio, through a pipe
           ── PcmDecoder.decode             MediaExtractor/MediaCodec → int16 stereo PCM file
           │                                (local files are decoded straight from their URI)
           ── WaveformAnalyzer              peak + bass ratio + onset energy per 64 frames
           ── BeatDetector                  BPM + first beat → BeatGrid
   │ PcmTrack (memory-mapped from cache/pcm, instant on reload)
   │ handed to the deck as soon as decoding starts, then keeps growing
   ▼
Native engine (C++, Oboe low-latency stream, src/main/cpp)
   Deck A: turntable → gain/EQ/filter/FX ─┬─ fader ─┐
                                          └─ PFL ───┼─ headphone bus ─┐
   Deck B: turntable → gain/EQ/filter/FX ─┬─ fader ─┤                 ├─ stereo or split output
                                          └─ PFL ───┘─ crossfader ─ master ┘
   Sampler: 16 pads ─ volume ───────────────── (after crossfader) ─ master / PFL
```

`DjController` owns the engine, decks and loading for the life of the process;
`PlaybackService` keeps that process in the foreground while music plays.

The sound is made in C++ (`Engine.cpp`): decks, channel DSP, sampler and mixer
render in an Oboe callback, on an exclusive MMAP stream where the device
allows it (Pixel 9 Pro: 48 kHz, 96-frame bursts, ~4 ms buffer). Kotlin's
`Deck`, `Sampler` and `MixerEngine` are thin control surfaces over the
engine's atomics (`NativeEngine` is the JNI bridge). The engine maps the same
decoded-PCM files as Kotlin, so progressive loading works unchanged; tracks
are reference counted and freed only once no callback can still see them. A
manager thread opens the stream on demand, closes it after 5 s of silence and
reopens it when the output device changes.

* **Source plugins** (`source/plugin/`): online sources are separate apps
  with a content provider, found by an intent action and called through
  `ContentResolver.call()`: describe, search, more results, open a link or
  playlist, check whether an item plays, prepare and read its audio. Their
  errors carry a message, a short reason and whether the source refused the
  item, which drives the "other uploads" search. Nothing of a source's own code
  is part of Mischpult; the protocol is in [docs/PLUGINS.md](docs/PLUGINS.md).
* **Decks** play from decoded PCM in memory-mapped files. The playhead moves
  with a smoothed velocity, so play/pause, tempo, sync and scratching all use
  the same mechanism: touching the scrolling waveform grabs the record (hold =
  stop, drag = scratch/scroll, release = continue). Let go mid-flick and the
  record coasts on, easing back to play speed (or to a stop) like a platter
  under friction, at most 6x play speed; a new touch catches it. Each update
  of the finger carries its speed, and the engine moves the record on at that
  speed between updates, so the record doesn't lurch at the touch rate (which
  sounded like a squeak); faster than play speed the audio is averaged over the
  stretch passed, so treble doesn't fold back as a whistle.
* **Scratch tab**: the left side of each turntable, the same on both decks as
  two real turntables stand side by side, scratched up and down (the record
  turns clockwise, so up is forward): the record turns by exactly the angle the
  finger moves around the spindle (33⅓ rpm), flicks coast as on the waveform.
  Below, a big crossfader that jumps to the finger for cuts, and a SMOOTH / CUT
  curve switch (cut: the far deck comes in within the first 6 % of travel;
  the gain is ramped per block, so cuts don't click).
* **Progressive loading**: decoding appends to a pre-sized, memory-mapped
  file and publishes how many frames are ready. The deck plays and scratches
  within that range and waits at its edge ("Buffering…") until more arrives;
  the waveforms fill in as they go. Only a finished file is kept in the cache.
* **Channel processing** (`DeckDsp`): gain (±12 dB), 3-band isolator EQ
  (Linkwitz-Riley splits at 300 Hz / 4 kHz, full kill), one-knob resonant
  filter (low-pass left, high-pass right), and one FX slot: beat-synced
  ping-pong echo (1/4–2 beats), reverb, or flanger. Echo and reverb tails ring
  out after switching the effect off.
* **Layout**: portrait phones stack the waveforms over the decks and mixer;
  landscape phones and tablets put the decks left and right at full height
  with the waveforms over the mixer in the middle (on short screens the deck
  cards scroll and switch pages by their handles). Pinch a waveform to zoom
  both; one finger still grabs the record (a touch waits 60 ms for a second
  finger, so starting a pinch never stops the music).
* **Deck card**: three pages, switched by swiping up/down on the card (or
  tapping its handles). DECK holds transport, tempo, EQ, filter, loop and the
  FX on/off; CUES holds hot cues and quick loops; FX holds the effect and its settings
  (amount, feedback/tone, size/damping, depth, beat timing, filter resonance).
* **Hot cues** (CUES page of the deck card): 8 coloured jump points per
  track, saved with it (`files/track_meta.json`) and shown on both waveforms.
  Tap an empty slot to set, a set one to jump, hold to clear.
* **Quick loops**: auto-loop 1/2/4/8 beats from the current beat (same button
  again leaves it), loop roll ¼/½/1 beat while held (on release the track
  continues where it would have been), and beat jump ±1/±4 (an active loop
  moves along).
* **Loops**: IN sets the start, OUT sets the end and starts looping, LOOP
  turns it off and on again (on outside the loop jumps back to its start).
  Markers snap to the beat grid and the length rounds to 1, 2, 4 or 8 beats;
  the wrap is sample-exact, so the beat never drifts. The loop is shaded on
  both waveforms.
* **Load feedback**: the library always shows what each deck holds and how
  far loading is; a track's A/B button fills when it is on that deck and shows
  a progress ring while it loads.
* **Sampler** (third tab): 4 banks of 16 pads for a gig. With Q set (¼, ½ or
  1 beat) a pad waits for the next grid line of the leading deck (playing,
  with a tempo, louder in the mix); a press just after a line starts at once.
  Pads know their BPM (from the deck loop they were grabbed from, detected
  for longer files, or typed in) and can *follow the tempo* of the leading
  deck: resampled like a record, or with *key lock* time-stretched so the
  pitch stays (one SoundTouch instance per pad).
* **Hot cue pad mode** (Sampler tab → HOT CUES): the grid turns into
  performance pads showing both decks' 8 hot cues in their cue colours, with
  their times. Tap empty to set, tap set to jump, hold to clear. A pad gets its sample from an
  audio file on the device or from a deck's current loop (IN/OUT), and plays
  one-shot, while held, or as a loop. Pads fire on touch-down; EDIT mode is
  the only way to change them, so a tap mid-set can never open a dialog. The
  sampler bus sits after the crossfader with its own volume and pre-listen,
  and its samples live in the app's files (`files/samples`), so clearing the
  decode cache never removes them.
* **Key lock** (KEY next to SYNC): tempo changes keep the pitch, via the
  vendored SoundTouch time-stretcher (LGPL 2.1,
  `app/src/main/cpp/third_party/soundtouch`). It runs only while the deck
  plays normally; scratching and braking stay vinyl. The reported position
  accounts for audio buffered in the stretcher.
* **BPM and sync**: tempo from onset autocorrelation, refined by folding the
  whole track at candidate periods, which also gives the beat phase (the grid
  ticks on the waveforms). SYNC locks a deck's tempo to the playing deck
  (half/double time allowed) and lines up the beats when engaged or when the
  deck starts.
* **Musical key**: a chromagram correlated with Temperley's major/minor key
  profiles (`KeyDetector`), shown in Camelot notation next to the BPM, in the
  library rows and the deck status bar. Without key lock the shown key follows
  the tempo shift. Keys that mix with what is playing (same, relative, ±1 on
  the wheel) are green. BPM and key are stored per track; offline prep
  analyses tracks too, so a prepared crate shows them without loading.
* **Headphone pre-listening**: phones have one stereo output, so *split*
  mode (output settings, ⚙) sends the master in mono to the left channel and
  the headphone mix to the right — use a splitter cable. The headphone buttons
  pick the pre-fader channels; the cue/master blend and both volumes are in
  the same dialog.
* **Background playback**: a `mediaPlayback` foreground service starts with
  the first playing deck, shows per-deck play/pause in its notification and
  stops after the decks have been quiet for a while (10 minutes in the
  background). The engine releases the audio output after 5 s of silence.
* **MIDI controllers** (mixer settings → MIDI controllers): USB controllers
  connect directly, Bluetooth LE MIDI ones after a scan. Mapping is MIDI
  learn: tap Learn next to a function, touch the control. Play, cue, sync,
  key lock, tempo, faders, gain/EQ/filter, FX, headphone cue, loops, beat
  jump, 8 hot cues and jog wheels per deck (touch = scratch, turn = nudge
  while playing, browse while stopped), crossfader, master and pads A1–A8.
  Holding a hot cue button for 0.6 s clears that cue, as on screen.
  Relative encoders in both common encodings are detected. The mapping is
  saved and the last controller reconnects when plugged in; on-screen knobs
  and faders follow the controller.
* **LED feedback**: mapped buttons light up on the controller to show the
  app's state: play, sync, key lock, FX, headphone cue and loop while on,
  cue while the deck stands at its cue point, loop in/out once set, set hot
  cues, sounding pads. Only changes are sent (every 50 ms), a new connection
  gets the full state, and LEDs go dark on disconnect. The value that lights
  an LED (127, 64 or 1) is a setting, since controllers differ.
* **Performance pads** (MIDI): 8 *Performance pad* functions per deck map a
  controller's pad section, and a *Pad mode* button switches it together
  with the Sampler tab. In hot cue mode a deck's pads are its 8 hot cues (set,
  jump, hold to clear; lit when set); in sample mode they play the Sampler
  tab's current bank, deck A's side pads 1–8 and deck B's side 9–16 (lit
  while sounding). The Pad mode button lights in hot cue mode.
* **Local files**: *Library → Files* imports files or whole folders through
  the system picker, keeping a read grant; tags give title/artist.
* **Recording the mix**: REC on the mixer records the stereo master to AAC
  (.m4a, 256 kbit/s) in Music/Mischpult. The audio thread only copies into a
  ring buffer; a separate thread encodes. While recording the engine keeps
  its stream open, so silence stays in the recording.
* **Set lists**: while recording, a track is logged once it is on air (its
  deck plays and fader × crossfader is at least 30 %), with the time into
  the recording, BPM and key. *Library → Sets* lists recordings with their
  set lists: play, share (audio + list), copy the list, delete.
* **Offline gig prep**: *Make offline* on a crate downloads and decodes all
  of its tracks ahead of time (two at a time, in a `dataSync` foreground
  service with progress and Cancel in its notification), so a gig never
  depends on the venue's network. Offline crates show how many tracks are
  ready, list failures, and their decodes are never evicted by cache
  trimming. Prep and a deck never decode the same track at once.
* **Storage**: decoded audio is ~10 MB per minute. *Crates* shows the total
  with *Clear all*; each track's menu can clear just its decode; removing a
  track from its last crate (or deleting a crate) deletes its decode too, and
  for local files gives up the read grant. Beyond 2 GB the least recently
  used decodes are dropped automatically.
* **Crates** (`Plattensammlung`, imported playlists, `Local files`) are stored
  as JSON in `files/crates.json`.

## Tests

`./gradlew :app:testDebugUnitTest` checks key detection on synthetic chord
progressions (C major, A minor), the Camelot conversions and mixing rules, and
MIDI parsing (running status, note-off as velocity 0), learning, the
relative-encoder decoding, which LED messages go out when, and where
performance pads land in each pad mode.

`native-tests/run.sh` builds the native deck with the host compiler and checks
that key lock keeps the pitch (440 Hz stays 440 Hz at +20 %/-8 % tempo, without
it the pitch follows the tempo), that the track advances at the right speed,
that a loop roll resumes exactly where the track would have been, that a
quantised sampler pad starts exactly on the next grid line, and that a pad
following a faster deck gets shorter while keeping (key lock) or raising its
pitch.

## Not done yet

* Load time: a new online track still downloads completely before decoding
  starts (a few seconds) unless its crate was prepared offline.
* Dragging loop markers, beatgrid
  editing (the detector assumes a constant tempo), and multi-channel USB
  interfaces as a proper alternative to the split cable.

## License

Mischpult is free software: you can redistribute it and/or modify it under the
terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version. See [LICENSE](LICENSE).

Third-party code and fonts (SoundTouch, Oboe, AndroidX, Kotlin, Coil with
OkHttp, Barlow Condensed, Share Tech Mono) are listed with their licences and copyright
holders in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The app ships
that file and every licence text in its assets (`app/src/main/assets/licenses/`);
a unit test keeps those copies identical to the files in the repository root.
