# 9GRadio — Full-Featured Android SDR App

A complete, production-quality Android SDR application written in Kotlin, purpose-built  
for the **RTL-SDR V4** dongle (RTL2832U + R828D, 28.8 MHz TCXO).

**Package:** `com.radiosport.ninegradio`  
**Version:** 1.55

---

![9GRadio](https://github.com/rkarikari/9GRadio/blob/master/images/9GRadio.gif) | [9GRadio](https://github.com/rkarikari/9GRadio/blob/master/images/9GRadio2.gif)

---

## Features

### RF / Hardware Control
| Feature | Details |
|---|---|
| **Frequency range** | 500 kHz – 1766 MHz (full V4 tuner range) |
| **HF direct sampling** | I-branch or Q-branch (Q recommended on V4) |
| **Auto HF switching** | Enables Q-branch automatically below 28.8 MHz |
| **Bias tee** | One-tap ~4.5 V on antenna port for powered LNAs/filters |
| **PPM correction** | ±50 ppm software compensation (TCXO ≈ 0) |
| **Gain control** | 29-step manual gain table + hardware AGC |
| **Sample rates** | 27 presets from 240 kS/s to 2.5 MS/s (low-rate narrow-mode band + main full-spectrum band) |
| **USB hot-plug** | Auto-detect and auto-launch on dongle insert |
| **rtl_tcp client** | Connect to a remote `rtl_tcp` / `rtl_tcp_andro` server over the network instead of a local USB dongle |

### Demodulation Modes
`AM` · `FM` · `NFM` · `WFM` · `WFM Stereo` · `USB` · `LSB` · `CW` · `CWR` · `DSB` · `RAW IQ`  
`APRS`

**Digital voice** (12.5 kHz NFM channel, shared discriminator pipeline):  
`DMR` · `D-STAR` · `YSF` · `dPMR` · `NXDN` — all fully auto-detected and voice-decoded via `Dig`
(auto-detect — tries all known sync words and reports back whichever protocol locks); `DMR`,
`D-STAR`, and `YSF` additionally have their own dedicated tabs

Each digital voice tab shows a **Recent Calls** list rather than a raw frame log: consecutive
frames from the same transmission (same source/destination/talkgroup) are grouped into a single,
single-line row that updates in place — showing start time, call duration, frame type, IDs,
talker alias, encryption/emergency flags, and total frame count — instead of one row per frame.
A new row only appears when a genuinely new call starts (different talker/destination, or the
same one keying up again after the previous transmission has clearly ended).

### Memory Slots
Every demodulation mode gets **9 memory slots** to save frequencies and settings into, so you
can jump between favorite stations on the same mode without retyping the frequency or re-doing
your gain/filter setup each time.

- Tap the **[ Slot # ]** button on the Mode tab to save what you're currently listening to and
  move to the next slot — frequency, gain, filters, display settings, everything, all saved
  together as one snapshot.
- Each mode (AM, FM, USB, DMR, etc.) remembers its **own** set of 9 slots and its own **current**
  slot, so switching from FM to USB and back always returns you to where you left off on each.
- A little arrow button next to **[ Slot # ]** reverses the cycling direction (forward or
  backward through the slots), for quickly stepping back to a slot you just passed.
- A slot you've never used yet starts out matching Slot 0 (your mode's baseline setup), so it's
  never a jarring blank/wrong-frequency surprise the first time you land on it.
- **Nothing is ever lost**: 9GRadio automatically saves your current slot's settings the moment
  before it switches away, whether you're cycling slots, switching modes, or tuning to an EiBi
  station (see below) — you never need to manually "save" first.

### EiBi Shortwave Schedule
A built-in, searchable schedule of shortwave broadcast stations from around the world (sourced
from the well-known EiBi frequency list), right inside the app — no need to look anything up
separately.

- Open the **EiBi** tab to see the full station table: frequency, station name, language,
  target audience/region, broadcast times (UTC), and transmitter site.
- **Tap any row** to tune straight to that station — 9GRadio switches to the right mode and
  frequency for you automatically.
- **Sort** the list by frequency, station name, language, target area, time, or transmitter
  site, in either direction, using the sort chip above the table.
- **Filter** the list down to what you actually want to hear, using the filter button:
  - By **language** or **target area** (e.g. only English-language broadcasts, or only stations
    aimed at your part of the world)
  - By **mode** (AM, USB, LSB, CW, etc.)
  - **Broadcast stations only** — hides utility/ham-adjacent signals mixed into the same list
  - **Regular, dependable schedules only** — hides entries the schedule itself flags as
    irregular, tentative, or test transmissions
  - **On air right now** — only shows stations broadcasting at this exact moment (UTC)
  - **Hide likely-dead bands** — uses your phone's location and the current time to estimate
    which shortwave bands are realistically able to reach you right now (a real
    propagation-based estimate, similar in spirit to what shortwave listeners call "MUF/FOT"),
    and hides entries that are very unlikely to be audible so you're not wasting time on dead
    frequencies. This is a helpful estimate, not a guarantee — real band conditions can always
    differ.
- Your sort order and filter choices are **remembered** between app launches, so you don't have
  to set them up again every time.
- Tuning to an EiBi station uses its own dedicated memory slot, so it never overwrites any of
  your regular saved frequencies on the mode you were using.
- **Stays current on its own** — once the EiBi tab is open, the list keeps itself up to date
  automatically:
  - Filters that depend on the clock ("On air right now", "Hide likely-dead bands") are
    re-checked every minute, so stations quietly appear and disappear on their own as their
    broadcast windows open/close and as propagation conditions change — no need to close and
    reopen the tab or tap anything to see the current picture.
  - The underlying schedule data itself is checked periodically and re-downloaded in the
    background whenever it's gone stale (older than a week, or a new EiBi broadcast season has
    started), so the list of stations stays fresh without ever needing to remember to hit
    UPDATE. The **UPDATE** button is still there if you want to force a fresh download right
    away.

### Aircraft, Marine & Datalink Tracking
| Mode | Details |
|---|---|
| **ADS-B** | 1090 MHz Mode S decoding via a vendored, unmodified `readsb` — live aircraft list, radar-scope view, and full map view with callsign, altitude, speed, and position |
| **AIS** | 161.975/162.025 MHz marine VHF (both channels captured in one 300 kHz-wide tune), decoded via vendored AIS-catcher — live vessel map with MMSI, ship name, course, and speed |
| **ACARS** | 8 preset VHF channels (129–131 MHz band), up to 4 monitored simultaneously — live message log with registration/flight/label filtering and per-channel stats |
| **RDS** | Program Identification (PI), Program Service name, and RadioText decoded from any WFM broadcast station via vendored `redsea`, shown as a live overlay on the spectrum display |

### Multilateration (MLAT)
A full multilateration stack for locating transmitters that don't self-report a position —
turns 2+ networked 9GRadio receivers (or any receiver speaking the same wire protocol) into a
station network that solves for a transmitter's position from time-difference-of-arrival (TDOA)
across stations, the same principle real-world ADS-B MLAT networks use.

- **Protocol-agnostic**: ADS-B, AIS, ACARS, RDS, and digital voice (DMR/D-STAR/YSF/dPMR/
  NXDN) can all be located this way — each protocol's decoded identity (ICAO, MMSI, RDS PI code,
  radio ID) is what lets multiple stations recognize they've heard the *same* transmission and
  contribute it to the same fix, no manual coordination required. Analog voice/AM/FM (no
  decodable identity) is also supported on a best-effort basis, keyed by carrier-onset timing on
  a shared tuned frequency. POCSAG/FLEX paging is intentionally excluded — pagers are fixed
  infrastructure, so locating them adds nothing.
- **Own GPS position** pulled automatically from the device's location provider — nothing to
  enter manually, and a moved/newly-set-up receiver never contributes a stale position by
  accident.
- **Run your own station**, **join a station network** (this device's observations flow to an
  aggregation server — a remote one, or another 9GRadio phone acting as one), and/or **run the
  aggregation server yourself** for other receivers to join, all independently toggleable.
- **Contribute to a real public MLAT network** (ADSBExchange, adsb.lol, or a private
  `mlat-server`) using the genuine upstream wire protocol, entirely separate from this app's own
  station-network mode — run either, both, or neither.
- **Live MLAT Dashboard**: GPS fix quality, client/server link status, station/peer counts,
  fixes solved per second, sent/received observation counts, and a rolling **network self-test**
  that solves a synthetic moving target every cycle to continuously verify the whole pipeline
  — station timing, geometry, and solver — end to end, without needing a real transmission in
  the air to test against.
- **Station roster + map**: every contributing station plotted on a live map, including
  synthesized "virtual" stations (shown distinctly) that fill in for a missing 3rd/4th station
  when only 2 real receivers are connected, so the self-test and accuracy estimate still run.
- **Geometry-aware accuracy estimate**: a live GDOP-based estimate of achievable fix accuracy at
  your stations' actual current spacing, alongside what it would be at an ideal reference
  spacing — makes it immediately clear whether your receivers are placed usefully far apart.

### DSP Engine
- **GNU Radio Android backend** ([gnuradio-android](https://github.com/bastibl/gnuradio-android)) — when the
  toolchain is present, all core DSP primitives are accelerated by **VOLK** (Vectorized Library for Kernels),
  which auto-dispatches to the fastest SIMD implementation (NEON, NEON FP16, SVE) at runtime.
  VOLK fixes the poor/no-audio problems that occurred at IQ sample rates whose ratio to the audio sink rate
  is not a simple integer.
- **High-quality polyphase FIR resampler** (windowed sinc, Blackman-Harris)
- **NEON-accelerated** uint8→float conversion, FM discriminator, AM envelope, FIR filter
- Pure-Kotlin fallback on x86 or if native library unavailable
- **FM de-emphasis** (75 µs NA / 50 µs EU)
- **WFM stereo pilot** decoder (19 kHz PLL → 38 kHz subcarrier)
- **RDS decoder** (vendored `redsea`, MIT): Program Identification, Program Service name,
  RadioText, and Programme Type decoded from any WFM broadcast station, shown live over the
  spectrum display
- **APRS** decoder: AX.25 frame sync, NRZI, bit-stuffing removal, position parsing; optional
  **dual-watch** mode (two simultaneous APRS channels) on device sample rates ≥ ~820 kS/s
- **DSD-Neo digital voice decoding** (vendored `mbelib-neo`, GPL-2.0): decodes DMR, D-STAR,
  YSF, **dPMR**, and **NXDN** voice frames from the NFM discriminator output; `Dig` mode
  auto-identifies the protocol from its sync word and decodes voice for all five
- **ADS-B decoding** (vendored, unmodified `readsb`, GPL-2.0): full Mode S/1090ES decode running
  as a native child process fed raw IQ over stdin, with hardware/GPS-referenced timestamps
  precise enough to drive multilateration directly
- **AIS decoding** (vendored `AIS-catcher`, GPL-3.0): dual-channel marine VHF Class A/B
  transponder decode
- **ACARS decoding** (vendored `acarsdec`, GPL-2.0): up to 4 VHF channels demodulated
  simultaneously
- **Noise blanker** and **noise reducer** (adaptive noise-floor calibration, re-calibrates on
  mode/bandwidth change)
- Squelch gate with per-mode threshold
- Configurable audio volume (0–200%), selectable audio sink rate

### Spectrum & Waterfall Display
- Real-time **FFT spectrum** with pinch-zoom, pan, click-to-tune; zoom/pan is mirrored live
  between the spectrum and waterfall views so both always show the same window
- **Waterfall** (scrolling spectrogram) — 10 color palettes: Rainbow, Heat, Grayscale, Blue-White,
  Purple-Yellow, Viridis, Inferno, Magma, Turbo, Solar
- **10 spectrum themes**: Classic, Futuristic, Amber, Grayscale, Purple, Solar, Neon, Ice,
  Midnight, Sakura
- **FFT sizes**: 256 – 8192 points
- **FFT decimation**: Off / ÷2 / ÷4 / ÷8 / ÷16 / ÷32 / ÷64, narrowing the analysis bandwidth for
  a sharper, lower-noise view of narrowband signals
- **Frame averaging**: Off / ×2 / ×4 / ×8 / ×16 / ×32 (N-frame linear-power accumulation, reduces
  the displayed noise floor by up to ~15 dB)
- **Intelligent per-protocol auto-configuration**: the first time a mode is selected, FFT size
  (2048), frame averaging (×8), and a protocol-appropriate decimation factor are seeded
  automatically — e.g. CW/CWR ÷64, USB/LSB and NFM ÷32, AM and APRS ÷16, DMR/YSF/D-STAR ÷8,
  WFM left at full bandwidth. Any manual change you make for a mode is remembered per-mode from
  then on and is never overwritten by the auto-defaults again.
- **8 window functions**: Rectangular, Hann, Hamming, Blackman, Blackman-Harris, Flat Top, Kaiser
  (adjustable β), Nuttall
- Configurable smoothing (exponential moving average)
- **True-intelligence Auto dB Range**: analyses the live spectrum and sets the floor just above
  the noise (barely visible / near-black on the waterfall) and the ceiling so the strongest
  signal sits at ~70% of the display height; falls back to a fixed 20 dB window above the noise
  floor when no signal is present. Converges within 5 seconds of being enabled, then holds
  steady — it does not creep or hunt indefinitely — and only re-acquires if the signal picture
  changes significantly (e.g. after retuning)
- **Peak hold** trace with configurable decay rate
- **Estimated noise-floor line** (15th-percentile of the visible spectrum), shown as a dashed
  overlay
- **Peak annotations**: top-3 local spectral maxima auto-labelled with frequency and dBFS
- **Live crosshair**: shows exact frequency + dBFS under a finger while touching the display
- **Long-press reference marker**: locks a persistent frequency/level marker for comparison
- Frequency axis with zoom-aware labels; minor grid lines at 5 dB steps
- **Squelch threshold line** with dashed overlay
- **Demodulated channel bandwidth highlight**, correctly anchored to the dial frequency for USB/LSB
- **Bookmark markers** pinned on spectrum
- Double-tap to reset zoom

### Frequency Display & Input
- Large 4-group LCD-style readout (GHz · MHz · kHz · Hz)
- Tap group to select step size; scroll to tune
- Up/down step buttons
- Fling gesture for rapid tuning
- Mode-aware recommended tuning step

### S-Meter
- Logarithmic segment bar (S1–S9 + S9+10 to S9+60)
- Peak hold with slow decay
- Colour-coded zones: green / yellow / red
- Live dBFS numeric readout

### Memory Channels
- Unlimited channels in named groups
- Stores: frequency, mode, sample rate, gain, squelch, bias-tee, direct sampling, PPM, notes
- Swipe-to-delete, tap-to-tune, long-press to edit
- JSON export / import

### Frequency Database (built-in)
50+ pre-loaded entries across: FM Broadcast · Aviation · Weather · Ham Radio · Marine ·  
ISM/IoT · Shortwave · HF Beacons · Satellite · Paging · APRS

### Bookmarks
- Add, label, and colour-code frequency bookmarks
- Shown as marker lines on spectrum

### Frequency Scanner
- Continuous range scan with configurable step, squelch, dwell time
- Memory scan (list of specific frequencies)
- Pause / resume / direction reversal
- Hit log (tap entry to tune)
- Real-time progress bar and signal readout

### Protocol Activities
- **APRS Activity**: live station list, packet log, map-style position tracking

### Per-Mode Settings Memory
Every demodulation mode remembers its own complete RF and display configuration — frequency,
gain, AGC state, sample rate, FFT size/decimation/frame averaging, dB range, squelch, IF
bandwidth, spectrum theme, waterfall palette, and more — and restores it automatically the next
time that mode is selected, so switching between e.g. NFM and USB never requires re-tuning your
display or RF settings from scratch.

### Recording
- **IQ recording**: raw uint8 (`.iq`), GZip-compressed (`.iq.gz`), float32 (`.cf32`)
- **Audio recording**: WAV, 16-bit PCM
- Recordings browser with playback (WAV) and share
- Recording metadata stored in Room database
- Auto-stop on size limit with optional 2 GB splitting

### Settings
- Full PreferenceScreen with 75+ configurable options across RF, display, and recording categories
- Export / import settings as JSON
- Per-feature category organisation

### Diagnostics
- **Debug Panel**: live per-stage health monitor for the full RTL-SDR → DSP → Audio/Waterfall/
  Spectrum pipeline, plus live DSP metrics and audio routing status
- **RTL-SDR Test Activity**: standalone USB/tuner connectivity and register-level test screen

### Background Operation
- **Foreground service** keeps SDR running with screen off
- Wake-lock option for unattended recording
- Persistent notification with quick Disconnect action
- Survives activity lifecycle changes via service binding

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│  Activities / Fragments (UI layer)                       │
│  MainActivity · ScannerActivity · MemoryActivity        │
│  RecordingActivity · SettingsActivity · SpectrumActivity│
│  AprsActivity · AdsbActivity · AcarsActivity            │
│  MlatSettingsActivity · DebugPanelActivity              │
│  RtlSdrTestActivity                                       │
└──────────────────┬──────────────────────────────────────┘
                   │ bind
┌──────────────────▼──────────────────────────────────────┐
│  MainViewModel (single source of truth)                  │
│  StateFlow: freq · mode · gain · biastee · spectrum …   │
│  Per-mode settings snapshot store (save/restore)         │
└──────────────────┬──────────────────────────────────────┘
                   │ bind
┌──────────────────▼──────────────────────────────────────┐
│  RtlSdrService  (foreground, survives orientation)       │
│  ┌───────────────┐   ┌───────────────────────────────┐  │
│  │ RtlSdrDevice  │   │ DspEngine                     │  │
│  │  USB driver   │──▶│  FFT · Demodulator · Squelch  │  │
│  │  R828D tuner  │   │  Resampler · AudioEngine      │  │
│  │  Bias-tee     │   │  IqRecorder · ProtocolDecoders│  │
│  │  Direct-samp  │   │  DigitalVoiceDecoder (mbelib) │  │
│  │               │   │  ReadsbProcess (ADS-B)        │  │
│  │               │   │  AisCatcherProcess (AIS)      │  │
│  │               │   │  AcarsMultiChannelDecoder     │  │
│  │               │   │  RdsDecoder (redsea)          │  │
│  └───────────────┘   └───────────────┬───────────────┘  │
└───────────────────────────────────────┼──────────────────┘
                                         │ submitDetection()
                        ┌────────────────▼────────────────┐
                        │  MlatManager                     │
                        │  MlatClient · MlatServer          │
                        │  UpstreamMlatClient · BeastReader │
                        └────────────────┬──────────────────┘
                                         │
┌──────────────────▼──────────────────────────────────────┐
│  Room Database                                           │
│  MemoryChannel · Bookmark · ScanEntry · Recording       │
└─────────────────────────────────────────────────────────┘
```

---

## Build Instructions

### Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34, NDK 25+
- Gradle 8.2+
- Physical Android device with **USB Host** support (API 26+)
- RTL-SDR V4 dongle + USB-C OTG adapter

### GNU Radio VOLK kernels

VOLK kernel implementations are vendored directly in
`app/src/main/cpp/include/volk_android.h`, extracted from the
[gnuradio-android](https://github.com/bastibl/gnuradio-android) toolchain (GPLv3).
No separate toolchain build is required — the kernels compile as part of the
normal Gradle build and activate NEON automatically on ARM devices.

### DSD-Neo / mbelib-neo

Digital voice decoding (DMR, D-STAR, YSF, dPMR, and NXDN — the latter two auto-detected
and decoded via the `Dig` mode) is powered by a vendored copy of `mbelib-neo`
(GPL-2.0-or-later) under `app/src/main/cpp/mbelib-neo/`, built as part of the normal NDK
build alongside the rest of `app/src/main/cpp/`.

### readsb (ADS-B / Mode S)

ADS-B decoding is powered by a vendored, unmodified copy of
[`readsb`](https://github.com/rkarikari/readsb) (GPL-2.0-or-later) under
`app/src/main/cpp/readsb/`, built by `cpp/CMakeLists.txt` as a standalone
native executable (not a JNI library — readsb is an application with its own
main loop and network servers, so it is run as a child process rather than
called into). readsb is a maintained fork/successor of dump1090-fa /
dump1090-mutability and preserves the same `--ifile`/`--iformat UC8` stdin
input mode and Basestation ("SBS") network output this app relies on. The
app feeds it raw IQ samples over stdin in RTL-SDR's native UC8 format and
reads back decoded aircraft state over its SBS output; see
`app/src/main/java/com/radiosport/ninegradio/dsp/ReadsbProcess.kt`. readsb's
history/trace compression additionally needs `libzstd`, which the NDK
doesn't ship, so it is vendored verbatim under `app/src/main/cpp/zstd/`
(from https://github.com/facebook/zstd) and built as a small static object
library the same way `mbelib-neo` is above.

### AIS-catcher (marine AIS)

AIS decoding is powered by a vendored, unmodified copy of
[`AIS-catcher`](https://github.com/jvde-github/AIS-catcher) (GPL-3.0-or-later) under
`app/src/main/cpp/ais-catcher/`, built the same way as readsb above — a standalone native
executable run as a child process (AIS-catcher, like readsb, is a full application with its own
device manager and network servers, not a single-entry-point library), fed raw IQ over stdin
and read back over its own network output; see
`app/src/main/java/com/radiosport/ninegradio/dsp/AisCatcherProcess.kt`.

### redsea (RDS)

RDS decoding is powered by a vendored, unmodified copy of
[`redsea`](https://github.com/windytan/redsea) (MIT) under `app/src/main/cpp/redsea/`, built
and run the same subprocess way as readsb/AIS-catcher above — fed raw composite MPX samples over
stdin, decoded groups read back as JSON; see
`app/src/main/java/com/radiosport/ninegradio/dsp/RedseaProcess.kt`.

### acarsdec (ACARS)

ACARS decoding is powered by a vendored copy of
[`acarsdec`](https://github.com/f00b4r0/acarsdec) (GPL-2.0-or-later) under
`app/src/main/cpp/acarsdec/`, built as a **JNI library** rather than a subprocess (unlike
readsb/AIS-catcher/redsea above, acarsdec's demodulate/decode logic is called directly per IQ
block rather than run as an independent standalone application); see
`app/src/main/java/com/radiosport/ninegradio/dsp/AcarsNative.kt`.

### Steps

```bash
git clone https://github.com/yourname/9GRadio
cd 9GRadio

# Build debug APK
./gradlew assembleDebug

# Install to connected device
./gradlew installDebug

# Build release APK (requires signing config)
# Output: app/build/outputs/apk/release/9GRadio_v1.48_release.apk
./gradlew assembleRelease
```

### First Run
1. Plug the RTL-SDR V4 dongle into your Android device via OTG adapter
2. Grant USB permission when prompted (tap **OK**)
3. The app auto-detects the dongle and starts streaming
4. Default: 100 MHz, NFM, 2.048 MS/s, AGC on

---

## File Structure

<details>
<summary>Click to expand</summary>

```
9GRadio/
├── app/
│   └── src/main/
│       ├── cpp/
│       │   ├── CMakeLists.txt              # NDK build config
│       │   ├── dsp_jni.cpp                 # NEON-accelerated DSP (JNI)
│       │   ├── dsdcc_jni.cpp                # DSD-Neo / dsdcc JNI bridge
│       │   ├── dsd_neo.cpp                  # DSD-Neo digital voice core
│       │   ├── gnuradio_dsp.cpp             # GNU Radio / VOLK bridge
│       │   ├── iq_convert.cpp / .h
│       │   ├── fir_filter.cpp
│       │   ├── fm_demod.cpp
│       │   ├── resampler.cpp
│       │   ├── include/                     # vocoder.h, volk_android.h, iq_convert.h
│       │   ├── mbelib-neo/                  # vendored MBE vocoder library (GPL-2.0)
│       │   ├── readsb/                      # vendored, unmodified readsb — ADS-B (GPL-2.0)
│       │   ├── ais-catcher/                 # vendored, unmodified AIS-catcher (GPL-3.0)
│       │   ├── redsea/                      # vendored, unmodified redsea — RDS (MIT)
│       │   ├── acarsdec/                    # vendored acarsdec — ACARS, JNI (GPL-2.0)
│       │   ├── liquid-dsp/                  # DSP primitives used by redsea/ais-catcher (MIT)
│       │   └── zstd/                        # vendored zstd, readsb history compression (BSD/GPLv2)
│       ├── java/com/radiosport/ninegradio/
│       │   ├── RtlSdrApplication.kt        # Application class
│       │   ├── audio/
│       │   │   └── AudioEngine.kt          # AudioTrack + WAV recorder
│       │   ├── data/
│       │   │   └── AppDatabase.kt          # Room DB, entities, DAOs
│       │   ├── debug/
│       │   │   └── DebugBus.kt             # Cross-stage pipeline health bus
│       │   ├── dsp/
│       │   │   ├── DemodMode.kt            # Mode enum + intelligent per-mode defaults
│       │   │   ├── Demodulators.kt         # AM, FM, WFM, SSB, CW, DSB, RAW
│       │   │   ├── DigitalFrameFilter.kt   # Digital voice frame post-processing
│       │   │   ├── DigitalVoiceDecoder.kt  # DMR/D-STAR/YSF/dPMR/NXDN decoder
│       │   │   ├── DsdccNative.kt          # JNI wrapper for dsdcc/mbelib-neo
│       │   │   ├── DspEngine.kt            # Main DSP pipeline
│       │   │   ├── FftEngine.kt            # FFT + windowing + frame averaging
│       │   │   ├── NativeDsp.kt            # JNI wrapper with Kotlin fallback
│       │   │   ├── PolyphaseResampler.kt   # High-quality sample rate converter
│       │   │   ├── ProtocolDecoders.kt     # APRS (AX.25), RDS submission
│       │   │   ├── ReadsbProcess.kt        # ADS-B: feeds/reads vendored readsb subprocess
│       │   │   ├── AisCatcherProcess.kt    # AIS: feeds/reads vendored AIS-catcher subprocess
│       │   │   ├── AcarsNative.kt          # ACARS: JNI wrapper for vendored acarsdec
│       │   │   ├── AcarsMultiChannelDecoder.kt # ACARS: up to 4 simultaneous channels
│       │   │   ├── RdsDecoder.kt / RedseaProcess.kt # RDS: feeds/reads vendored redsea subprocess
│       │   │   └── SourceDiagnostic.kt     # IQ source health diagnostics
│       │   ├── mlat/
│       │   │   ├── MlatManager.kt          # App-wide MLAT owner; protocol-agnostic submitDetection()
│       │   │   ├── MlatMath.kt             # TDOA solver, GDOP, virtual station placement
│       │   │   ├── MlatServer.kt           # Embedded aggregation server + network self-test
│       │   │   ├── MlatClient.kt           # Forwards this device's observations to a server
│       │   │   ├── MlatModels.kt           # Wire protocol / protocol-tag constants
│       │   │   ├── BeastReader.kt          # Reads readsb's Beast output for ADS-B timestamps
│       │   │   ├── UpstreamMlatClient.kt   # Contributes ADS-B to a real public MLAT network
│       │   │   └── GnssTimeSource.kt       # GPS-referenced timing
│       │   ├── recording/
│       │   │   └── IqRecorder.kt           # IQ to disk (raw/gz/f32)
│       │   ├── scanner/
│       │   │   └── FrequencyScanner.kt     # Range + memory scan
│       │   ├── ui/
│       │   │   ├── AprsActivity.kt          # APRS station list / packet log
│       │   │   ├── AdsbActivity.kt          # ADS-B live list, radar scope, map view
│       │   │   ├── AcarsActivity.kt         # ACARS message log / channel filtering
│       │   │   ├── MlatSettingsActivity.kt  # MLAT dashboard, config, self-test, station map
│       │   │   ├── MlatStationMapView.kt    # MLAT station roster map
│       │   │   ├── AdsbMapView.kt / AisMapView.kt # Map views for aircraft / vessels
│       │   │   ├── ControlsTabManager.kt    # RF/Display drawer tab controls
│       │   │   ├── DebugPanelActivity.kt    # Pipeline health monitor
│       │   │   ├── FrequencyView.kt        # LCD frequency display + scroll tuning
│       │   │   ├── MainActivity.kt         # Main screen
│       │   │   ├── MainViewModel.kt        # State + commands + per-mode settings
│       │   │   ├── OtherActivities.kt      # Settings, Recording, Spectrum, ACARS activities
│       │   │   ├── MemoryActivity.kt       # Memory channels browser
│       │   │   ├── RtlSdrTestActivity.kt    # USB/tuner connectivity test screen
│       │   │   ├── ScannerActivity.kt      # Scanner UI
│       │   │   ├── SMeterView.kt           # Analog S-meter widget
│       │   │   ├── SpectrumView.kt         # FFT spectrum display + Auto dB Range
│       │   │   └── WaterfallView.kt        # Scrolling waterfall
│       │   └── usb/
│       │       ├── RtlSdrDevice.kt         # USB driver + R828D tuner
│       │       └── RtlSdrService.kt        # Foreground service
│       └── res/
│           ├── layout/                     # All XML layouts
│           ├── values/                     # Strings, colors, themes, arrays
│           ├── xml/
│           │   ├── preferences.xml         # Full settings screen
│           │   └── usb_device_filter.xml   # USB VID/PID whitelist
│           └── drawable/                   # Vector icons
└── build.gradle / settings.gradle
```

</details>

---

## RTL-SDR V4 Hardware Notes

### Direct HF Sampling
The V4 uses the R828D which supports bypassing the tuner entirely below ~28.8 MHz.
9GRadio auto-enables **Q-branch** (mode 2) for frequencies below 28.8 MHz — this is  
the correct choice for the V4 hardware revision. I-branch (mode 1) is also available  
for older hardware or experimentation.

### Bias Tee
The V4 bias tee outputs ~4.5 V on the SMA antenna connector, controlled via GPIO bit 3  
of the RTL2832U. Use it to power external LNAs, filtered pre-amps, or the official RTL-SDR  
Blog LNA. **Do not enable with passive antennas or direct coax connections.**

### TCXO
The V4 includes a 28.8 MHz TCXO with ±1 ppm accuracy. Set PPM correction to **0** unless  
you have a reference to calibrate against. The TCXO eliminates the frequency drift seen  
on older RTL-SDR designs.

### Gain
Use **AGC** for most purposes. For weak-signal work (HF, satellite), try manual gain at  
index 20–26 (~30–45 dB). For strong local FM, reduce to 0–5 to avoid ADC saturation.

---

## User Guide: Optimal Settings for High Performance

Quick-reference settings for the smoothest experience on the RTL-SDR V4.

| Setting | Recommended | Why |
|---|---|---|
| **Sample rate** | 2.048 or 2.4 MS/s for general monitoring; drop to 912 kS/s–1.024 MS/s on older/slower devices | Balances spectrum width against CPU load; VOLK/NEON handles these rates smoothly on most phones |
| **PPM correction** | 0 | The V4's TCXO is already ±1 ppm accurate — added correction only helps if you've calibrated against a known reference |
| **Gain** | AGC for general use; manual 20–26 (~30–45 dB) for weak-signal HF/satellite work; 0–5 for strong local FM | Prevents ADC saturation on strong signals while keeping enough gain for weak ones |
| **FFT size** | 2048 | Sweet spot between frequency resolution and render cost; raise to 4096+ only for detailed narrowband analysis |
| **Frame averaging** | ×8 | Cuts displayed noise floor by up to ~15 dB with negligible added latency |
| **FFT decimation** | Leave on auto (per-mode default) | Digital voice/NFM narrows to ÷8–÷32 automatically for a cleaner, lower-noise view without hand-tuning |
| **Noise blanker / reducer** | On only if you have impulsive/broadband noise (ignition, switching PSUs) | Adds CPU overhead; skip it on a clean RF environment |
| **Bias tee** | Off unless powering an LNA/filter | Never enable with passive antennas or direct coax |

### Digital voice (DMR / D-STAR / YSF / dPMR / NXDN)
- Leave channel bandwidth and IF settings on their **default** values for each digital voice
  mode — they're already tuned for the shared discriminator pipeline and don't benefit from
  manual adjustment.
- Start on the specific mode tab (**DMR**/**D-STAR**/**YSF**) when you know the protocol —
  it's marginally lighter than **Dig** auto-detect, which keeps trying every known sync word.
- Use **Dig** for **dPMR**/**NXDN** traffic (and when the protocol is otherwise unknown) —
  these are fully auto-detected and voice-decoded, they just don't have their own dedicated tab.
- Set squelch just above the noise floor — the Recent Calls list groups frames into one row
  per transmission using a timing gap, so a squelch that's chattering open/closed on noise can
  fragment a single real call into several separate rows.

### Background recording / unattended monitoring
- Enable the **foreground service** wake-lock to survive screen-off.
- Cap IQ recordings with the **2 GB auto-split** to avoid single giant files.
- Prefer `.iq.gz` over raw `.iq` if storage is limited — GZip typically halves file size on
  narrowband captures with only a small CPU cost.

### Aircraft, marine & datalink tracking (ADS-B / AIS / ACARS / RDS)
- **ADS-B**: tune to 1090 MHz and select the **ADS-B** mode, then open the ADS-B activity for
  the live aircraft list, radar scope, and map. Works best with a dedicated 1090 MHz antenna
  (a general-purpose one will still pick up strong nearby traffic).
- **AIS**: tune to 162.000 MHz and select **AIS** — this single tune covers both marine VHF
  channels (161.975/162.025 MHz) at once, no channel switching needed.
- **ACARS**: open the ACARS activity and pick a channel from the 8 presets, or leave it on the
  default (131.725 MHz) — up to 4 channels can be monitored simultaneously.
- **RDS**: select **WFM** or **WFM Stereo** on any strong local FM broadcast station — PI,
  Program Service name, and RadioText appear automatically as an overlay on the spectrum display
  once decoded; no separate activity to open.

### Memory Slots
- Set up a favorite frequency exactly how you like it (mode, gain, filters), then tap
  **[ Slot # ]** to save it and move to the next slot — repeat for each station you want quick
  access to.
- Use the small reverse-direction arrow next to **[ Slot # ]** if you overshoot and need to step
  back one slot instead of cycling all the way around.
- Slots are per-mode, so your FM slots and your USB slots are completely separate lists — no
  need to keep them organized around a single shared set of 9.
- You don't need to remember to save before switching — 9GRadio does that automatically
  whenever you cycle slots, change modes, or tune in from the EiBi tab.

### EiBi shortwave schedule
- Open the **EiBi** tab and tap any station in the list to tune to it instantly — no need to
  know or type the frequency yourself.
- If you just want to browse what's on **right now**, turn on the **"On air right now"** filter.
- If you're aiming for a specific audience or language, use the **language** and **target area**
  filters rather than scrolling the full list.
- Turn on **"Hide likely-dead bands"** for a quick way to skip stations that almost certainly
  won't come in given your location and the current time of day — handy when you just want to
  browse stations that are actually worth trying right now. Allow location access when prompted
  so this filter has something to work with; without it, this filter is left off automatically.
- If a station on the list is unreliable (marked irregular/tentative/test by the schedule
  itself), turn on **"Regular, dependable schedules only"** to skip those and focus on stations
  that reliably show up as scheduled.
- Just leave the tab open (or come back to it later) and the list will keep itself current —
  time-based filters re-check themselves automatically, and the schedule data itself refreshes
  in the background on its own, so you never have to remember to tap UPDATE.

### Multilateration (MLAT)
- Open the **MLAT** dashboard from the Settings drawer tab. Enable **Client** to contribute this
  receiver's observations to a station network; GPS position is acquired automatically.
- **Two receivers is the practical minimum** to get any fix at all, but accuracy depends heavily
  on how far apart they are — closer receivers give a less reliable fix. The dashboard's
  geometry-aware accuracy estimate tells you directly whether your current spacing is good
  enough; if it isn't, moving a receiver farther away (or adding a 3rd/4th) is the fix, not a
  setting.
- To also **run the aggregation server** locally (so other 9GRadio receivers, or anything
  speaking the same protocol, can join your network), enable **Server** and set a listen port.
- To **contribute your ADS-B feed to a real public MLAT network** (ADSBExchange, adsb.lol, or a
  private `mlat-server`), use the separate **upstream** section rather than the Client/Server
  toggles above — it speaks the genuine upstream wire protocol, not this app's own.
- Use the **network self-test** on the dashboard to sanity-check the whole pipeline (timing,
  geometry, and solver) at any time — it doesn't need a real transmission in the air, and with
  only 2 real stations connected it automatically fills in synthetic "virtual" stations so the
  test can still run a full solve.

---



## License

GPLv3 License — see LICENSE file.  
RTL-SDR® is a registered trademark of RTL-SDR Blog Ltd.  
9GRadio is not affiliated with Realtek Semiconductor or RTL-SDR Blog Ltd.  
Digital voice decoding uses vendored `mbelib-neo` (GPL-2.0-or-later) — see
`app/src/main/cpp/mbelib-neo/LICENSES/`.  
ADS-B decoding uses a vendored, unmodified copy of
`readsb` (GPL-2.0-or-later) — see `app/src/main/cpp/readsb/COPYING` —
plus a vendored copy of `zstd` (BSD/GPLv2 dual-licensed) — see
`app/src/main/cpp/zstd/LICENSE`.  
AIS decoding uses a vendored, unmodified copy of `AIS-catcher`
(GPL-3.0-or-later) — see `app/src/main/cpp/ais-catcher/LICENSE`.  
RDS decoding uses a vendored, unmodified copy of `redsea` (MIT) — see
`app/src/main/cpp/redsea/LICENSE`.  
ACARS decoding uses a vendored copy of `acarsdec` (GPL-2.0-or-later) — see
`app/src/main/cpp/acarsdec/LICENSE.md`.

---

© RNK 9G5AR RadioSport
