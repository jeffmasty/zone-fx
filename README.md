# zone-fx

Lightweight, realtime-aware Digital Signal Processing (DSP) units for JudahZone.

Summary
- Implements small, allocation-minimal DSP building blocks intended for realtime use in audio threads.
- Effects expose simple parameter setters so a GUI or host can update values asynchronously.

Where to find the code
- Main FX package: `src/main/java/judahzone/fx`
- Analysis utilities (FFT / tuner helpers): `src/main/java/judahzone/fx/analysis`

Key components

	•  Gain — simple gain stage
	•  EQ — multi‑band EQ wrapper that configures cascaded filters
	•  Filter, MonoFilter, StereoBiquad — filter primitives and biquad helper implementations
	•  Delay — delay/echo line with feedback and wet/dry controls
	•  Chorus — modulation‑based chorus effect
	•  Overdrive — distortion/drive algorithms (several styles)
	•  Compressor — dynamics compression stage
	•  Freeverb — reverb implementation (Freeverb + wrapper)
	•  Convolution — impulse‑response (IR) based cab/IR convolution
	•  StereoBus — combines per‑channel FX (gain, filters, delays) and manages activation and processing order
	•  FX — base utility/interface for effect units and parameter handling
	•  Analysis helpers: Analysis, Transformer, Tuner — FFT and pitch‑detection utilities
	
Design notes
- Minimal allocations and simple APIs to be safe for realtime audio callbacks.
- Parameters are settable from other threads; processing methods do not allocate per-buffer.

Build and checkout notes
- The project is a module of the `meta-zone` aggregator. Recommended workflow:
  - Clone the parent aggregator:
    - `git clone https://github.com/jeffmasty/meta-zone.git`
    - `cd meta-zone`
  - Build everything (recommended):
    - `mvn clean package`
  - Build only `zone-fx` (from the parent directory):
    - `mvn -pl zone-fx -am clean package`
  - If you `cd` into `zone-fx` and run `mvn package` directly, Maven expects the parent `meta-zone/pom.xml` at the relative path defined in the module pom.

Dependencies and repository notes
- Intended to keep external deps minimal.
- Core deps:
  - `zone-core` — project core utilities; usually provided via the parent aggregator.
  - TarsosDSP (`be.tarsos.dsp`) — used for FFT, audio I/O helpers and a tuner utility.
  - `lombok` — used as a provided compile-time annotation processor.
- If building standalone, add the Tarsos repository to your Maven settings or pom:
  - `https://mvn.0110.be/releases` (Tarsos \`.be\` repository).
- When building inside `meta-zone`, dependency versions and repository entries are managed by the parent `pom.xml`.

Runtime notes
- `zone-fx` is a library; runtime requirements depend on the host application that uses it. If used inside the JACK/JNAJack client, native JACK libraries and a running JACK server are required by that client layer (see the `zone-jnajack` module for JACK-specific runtime notes).
- `zone-test` provides a JackClient Java-Swing test channel strip that can be applied against a loaded-in MP3 file.

Credits
- Delay, MonoFilter, FreeVerb, Chorus and the 'Smith' OverDrive adapted from Neil C Smith's JAudioLibs.
- Additional Overdrive algorithms ported from JUCEGuitarAmpBasic.
- Compressor ported from Rakarrack.
- Filters/EQ concepts influenced by JackIIR.
- FFT / tuner utilities rely on TarsosDSP.

Reference
- See the top-level application README in the project root and the `meta-zone` aggregator `pom.xml` for full build and dependency details.


