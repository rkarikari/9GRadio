# 9GRadio — Full-Featured Android SDR App

A complete, production-quality Android SDR application written in Kotlin, purpose-built  
for the **RTL-SDR V4** dongle (RTL2832U + R828D, 28.8 MHz TCXO).

**Package:** `com.radiosport.ninegradio`  
**Version:** 1.33

---

![9GRadio](https://github.com/rkarikari/9GRadio/blob/master/images/9GRadio.gif)

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
- **APRS** decoder: AX.25 frame sync, NRZI, bit-stuffing removal, position parsing; optional
  **dual-watch** mode (two simultaneous APRS channels) on device sample rates ≥ ~820 kS/s
- **DSD-Neo digital voice decoding** (vendored `mbelib-neo`, GPL-2.0): decodes DMR, D-STAR,
  YSF, **dPMR**, and **NXDN** voice frames from the NFM discriminator output; `Dig` mode
  auto-identifies the protocol from its sync word and decodes voice for all five
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
│  AprsActivity · DebugPanelActivity                        │
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
│  └───────────────┘   └───────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
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

### Steps

```bash
git clone https://github.com/yourname/9GRadio
cd 9GRadio

# Build debug APK
./gradlew assembleDebug

# Install to connected device
./gradlew installDebug

# Build release APK (requires signing config)
# Output: app/build/outputs/apk/release/9GRadio_v1.33_release.apk
./gradlew assembleRelease
```

### First Run
1. Plug the RTL-SDR V4 dongle into your Android device via OTG adapter
2. Grant USB permission when prompted (tap **OK**)
3. The app auto-detects the dongle and starts streaming
4. Default: 100 MHz, NFM, 2.048 MS/s, AGC on

---

## File Structure

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
│       │   └── mbelib-neo/                  # vendored MBE vocoder library (GPL-2.0)
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
│       │   │   ├── ProtocolDecoders.kt     # APRS (AX.25)
│       │   │   └── SourceDiagnostic.kt     # IQ source health diagnostics
│       │   ├── recording/
│       │   │   └── IqRecorder.kt           # IQ to disk (raw/gz/f32)
│       │   ├── scanner/
│       │   │   └── FrequencyScanner.kt     # Range + memory scan
│       │   ├── ui/
│       │   │   ├── AprsActivity.kt          # APRS station list / packet log
│       │   │   ├── ControlsTabManager.kt    # RF/Display drawer tab controls
│       │   │   ├── DebugPanelActivity.kt    # Pipeline health monitor
│       │   │   ├── FrequencyView.kt        # LCD frequency display + scroll tuning
│       │   │   ├── MainActivity.kt         # Main screen
│       │   │   ├── MainViewModel.kt        # State + commands + per-mode settings
│       │   │   ├── OtherActivities.kt      # Settings, Recording, Spectrum activities
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
- Use **NFM** channel bandwidth (12.5 kHz) — the discriminator pipeline all digital voice
  modes share is tuned for this.
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

---



GPLv3 License — see LICENSE file.  
RTL-SDR® is a registered trademark of RTL-SDR Blog Ltd.  
9GRadio is not affiliated with Realtek Semiconductor or RTL-SDR Blog Ltd.  
Digital voice decoding uses vendored `mbelib-neo` (GPL-2.0-or-later) — see
`app/src/main/cpp/mbelib-neo/LICENSES/`.
