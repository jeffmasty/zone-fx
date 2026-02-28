# Filters — Classification, Implementation & Real-Time Safety

A practical guide to filter types, and how JudahZone
implements them safely for real-time audio.

### By Linearity: Linear vs Nonlinear

- **Linear**: superposition and FFT analysis apply. Examples: biquads,
  combs, all‑pass filters. Predictable, analyzable, stable.
- **Nonlinear**: wavefolding, saturation, soft clipping. Examples:
  distortion, overdrive, soft-knee compression. More CPU per stage, but
  musical character.

### By Mathematical Structure: FIR vs IIR vs Delay-Line

- **FIR** (Finite Impulse Response): taps and convolution. Always stable,
  linear phase possible. Used for convolution reverbs and feedforward
  combs. CPU grows with impulse response length.
- **IIR** (Infinite Impulse Response): recursive feedback and internal
  state. Fewer multiplies for narrow responses, but nonlinear phase and
  potential instability if poorly designed. Biquads are the canonical
  2nd‑order IIR building block.
- **Delay-Line**: sample buffers, tapped delays, simple feedback/
  feedforward. Conceptually FIR, but feedback converts them to IIR
  behavior. Cheap per-sample cost, but delay resolution tied to sample
  rate (unless interpolated).

### By Frequency Response Goal: Magnitude vs Phase vs Time

- **Magnitude shaping**: LP/HP/BP/shelves/peaking. Change amplitude vs
  frequency (traditional EQ). Example: biquads for tone shaping.
- **Phase (all‑pass)**: preserve magnitude, shift phase per frequency.
  Creates group delay variation, diffusion, subtle coloration. No spectral
  energy loss.
- **Time effects**: echoes, resonances, reverb tails. Implemented via
  delay-lines and feedback. Adds space and sustain without changing
  spectral shape alone.

### By Time Behavior: Time‑Invariant vs Time‑Varying

- **Time‑invariant**: fixed coefficients/delays. Cheaper, analyzable,
  predictable.
- **Time‑varying**: smoothed parameter updates, delay modulation (chorus,
  flanging, diffusion sweeps). Requires interpolation and careful ramp
  handling to avoid artifacts.

---

## Practical Filter Classes & JudahZone Implementations

### Biquad: "Bi": 2nd‑order IIR  "Quad": state values (xn1, xn2, yn1, yn2)

**Structure**: Direct Form or transposed state-space. Normalized
coefficients: b0, b1, b2, a1, a2 (with a0 = 1 after normalization).

**Use cases**: LP/HP/Peaking/Notch/AllPass. Typical EQ, tonal shaping,
precise magnitude and phase control.

**Pros**:
- Compact, low per-sample cost (~5 multiplies + adds per sample).
- Stable when coefficients well-designed.
- Good for EQ, surgical tone shaping, and feedback isolation.

**Cons**:
- Per-sample state and multiplies.
- Phase is nonlinear (not flat at all frequencies).
- Cascading can introduce instability if Q values too high.

**In JudahZone**: `AllPass.java` — biquad all‑pass with coefficient
smoothing and stereo paths. `FilterCoefficients.java` provides computation
helpers for all standard types.

**Real-time notes**: Smooth coefficient changes per block (not per sample)
to avoid zipper noise. See `AllPass.java` for interpolation pattern.

---

### Schroeder / Delay-Line All-Pass (Freeverb Style)

**Structure**: single tapped delay line, feedforward of -input, feedback of
tapped sample × gain. Circular buffer, 1 memory read/write per sample.

**Use cases**: Diffusion stages in reverb, pre/post diffusion to break up
comb resonances, spatial widening without magnitude loss.

**Pros**:
- Very cheap per sample (~2 multiplies + adds, one read, one write).
- Easy to cascade; each stage adds diffusion.
- Natural coarse group delay control via integer sample lengths.
- No magnitude loss (pure phase reshaping).

**Cons**:
- Delay resolution locked to sample rate (integer samples only, unless
  interpolated).
- Integer tuning can produce strong combs if used alone; must cascade
  multiple stages with different delays.

**In JudahZone**: `Schroeder.java` — delay-line all‑pass used by
`Freeverb`. Also available as a standalone diffusion primitive.

**Real-time notes**: Use fixed delays set at construction or
pre-calculated. If you must change delay mid-stream, reset or crossfade to
avoid clicks.

---

### Comb Filters (Delay-Based Resonator)

**Structure**: feedback or feedforward (or both). Damping typically
implemented as a one-pole lowpass in the feedback path.

**Parameters**:
- Delay length (samples): sets resonance frequency (pitch).
- Feedback (roomsize): amplitude of resonant peak (0 = no feedback, 1 =
  unstable).
- Damping (lowpass coefficient): tames high-frequency buildup, adds
  realism.

**Use cases**: resonant echoes, artificial pitch emphasis, reverb building
blocks (parallel combs), metallic/ringing character.

**Pros**:
- Simple, cheap (~3 multiplies + adds per sample).
- Musically resonant; creates harmonic emphasis.
- Feedback combs with damping sound natural (like room resonances).

**Cons**:
- Can ring or pitch-shift noticeably if feedback too high.
- Delay-length quantization (integer samples) can produce unnatural
  coloration.

**In JudahZone**: `CombFilter.java` — feedback comb with internal one-pole
damping. Used in `Freeverb` and can be instantiated standalone.

**Real-time notes**: Set feedback and damping at configure time or smooth
changes over blocks. Integer delay lengths mean precise tuning requires
careful planning (consider multiple combs at different delays).

---

### Convolution (FFT-Based, Overlap-Add)

**Structure**: Impulse response (IR) precomputed in frequency domain. Input
processed in blocks via FFT, inverse FFT, overlap-add accumulation.

**Use cases**: high-quality impulse response emulation (room/cabinet
simulation, vintage gear character, convolver reverbs).

**Latency**: equal to FFT frame size (`FFT_SIZE`). Larger FFT = lower CPU
but higher latency (typical range: 512–2048 samples). Choose based on your
use case (real-time performance vs audio quality).

**Wet/Dry mixing**: typically applied post-convolution. IRs can be
normalized or pre-scaled for level matching (no volume loss).

**Pros**:
- Unmatched realism for cabinet, room, and vintage gear emulation.
- IR captured from real hardware or measured spaces.
- Decorrelates channels naturally (L/R IRs different).

**Cons**:
- High CPU (FFT + IFFT per block, ~2N log N operations).
- Fixed latency (unavoidable with FFT approach).
- IR loading/switching must happen off audio thread.

**In JudahZone**: `Convolution.java` — FFT-based mono/stereo convolver.
Uses `IRProvider` interface for dynamic IR loading. Pre-computes IR
frequency-domain storage (`irFreq`). Applies wet/dry mixing. Handles
missing IRs gracefully.

**Real-time notes**: Precompute IR on non-RT thread (avoid allocations in
`process()`). Reset overlap buffers when IR changes mid-stream to prevent
artifacts. Use memory efficiently; large IRs consume significant RAM.

---

### Nonlinear Filters (Waveshapers & Overdrive)

**Structure**: input signal passed through a nonlinear function (lookup
table, closed-form math, or feedback network). Often combined with input
gain ("drive") and output gain ("makeup") to control saturation and
compensation.

**Algorithms**:
- **tanh/atan**: smooth soft clipping, warm saturation.
- **Asymmetric tanh**: different curves for positive/negative (models
  diode behavior).
- **Foldback**: creates harmonic mirror aliases (intentional aliasing).
- **Mesa/exponential**: steep knee, dramatic distortion.
- **Tube**: exponential curve, warm coloration.
- **Fuzz**: aggressive clipping, old-school character.

**Drive & Makeup**:
- **Drive**: boosts signal before waveshaper (increases saturation).
- **Makeup**: compensates for energy loss post-shaping (preserves level).
- Rebuild waveshaper lookup table (if used) in `activate()` or on lazy
  parameter change, never per-sample.

**Clipping & Diode Safety**:
- Internal one-pole lowpass or soft clipping limits prevent runaway
  feedback in distortion chains.
- Diode modeling (asymmetric curves) adds realism to amp/pedal emulation.

**Anti-Aliasing**:
- Nonlinear processing generates high-frequency aliases.
- Oversampling recommended (2× or 4× internal) when drive high to suppress
  artifacts.
- Trade CPU for cleaner tone.

**Pros**:
- Adds musical character, warmth, aggression.
- Cheap per-sample if using closed-form functions (tanh, atan) or small
  LUTs.
- Combines well with combs and delays for feedback saturation effects.

**Cons**:
- Aliases easily (must oversample or be intentional).
- Feedback saturation can explode if not clipped properly.
- Lookup table approach requires memory and setup overhead.

**In JudahZone**: `Overdrive.java` — multiple waveshaper modes, drive/
makeup gain, optional internal damping, RT-safe parameter smoothing.

**Real-time notes**: avoid allocations in `process()`. Pre-build waveshaper
lookup tables in `activate()` or offline. Clamp outputs to prevent NaN/Inf
propagation. Monitor feedback paths for instability.

---

## Key Differences: When to Pick Which

 **EQ, tone shaping** | Biquad | Flexible, stable, precise magnitude/phase control. 
 
 **Phase-only shaping, diffusion** | All-pass (biquad or Schroeder) | Preserves energy, reshapes phase. Schroeder cheaper. 
 
 **Resonant echoes, pitchy tails** | Comb | Natural resonance, feedback emphasis. 
 
 **Cabinet, room, vintage gear** | Convolution | Unmatched realism. Accept latency tradeoff. 
 
 **Saturation, distortion, warmth** | Waveshaper/Overdrive | Musical character, CPU-efficient with oversampling. 
 
 **Reverb network, diffusion** | Schroeder + Comb combo | Cheap, natural, proven (see Freeverb). 

**CPU vs Musical Trade**:
- Delay-line Schroeder is cheapest for diffusion; 
	better for many reverb jobs than biquad all-pass.
- Biquad all-pass more precise for narrowband phase shaping or fractional delay/continuous tuning.
- Convolution unmatched for realism but heaviest on CPU.
- Waveshaper cheap; oversampling adds cost but prevents aliasing.

---

## Real-Time Constraints & Best Practices

### Denormal Handling

Tiny floating-point values (< 1e-8) can cause CPU stalls on some
processors. Clamp denormals to zero. Most filter classes in JudahZone
include denormal checks in feedback paths.


TODO:

Unify/refactor coefficient interpolation

	•  Shared responsibilities to extract into a base helper/BiquadOp:
	•  common fields: a0..a2, b0..b2, lastA*, lastB*, coeffDirty, haveLastCoeffs, N_FRAMES, INV, DENORM.
	•  per-channel state management pattern: mono xn1,xn2,yn1,yn2 and stereo xn1L,...xn2R,... with reset helpers.
	•  inner loop templates: (fast path) and (interpolation path) with prefetch of locals and denorm clamping.
	•  utilities: commitLastCoeffs(), markDirty(), setCoefficientsFrom(Filters.Coeffs) and normalizeCoeffs().
	•  safety helpers: clamp frequency/bandwidth, denormal fix, and optional tiny exponential smoothing for UI→RT transition.
	•  Per‑filter specialization:
	•  abstract method computeCoefficients() or Filters.Coeffs buildCoeffs() that each subclass overrides (Peaking, AllPass, BandPass, LowPass, HighPass).
	•  subclasses only provide parameter storage & setters that call computeCoefficients() (non‑RT) and then rely on markDirty() to trigger interpolation on next process call.
	•  API and threading considerations:
	•  compute coefficients off the audio thread (GUI thread) and then atomically publish by writing plain floats and setting coeffDirty = true. That pattern is already present; keep it but document threading contract: setters are not RT but must not allocate on RT.
	•  avoid synchronization/locking in audio path; use simple boolean flags and accept benign data races for parameter updates (common real‑time pattern).
	•  if stronger guarantees desired, publish new coeffs into a preallocated volatile struct and use a single atomic swap; but avoid volatile reads/writes inside inner sample loop. Prefer a single volatile flag or an RT-friendly queue if strict ordering needed.
	•  Edge cases and pitfalls to handle:
	•  a0 may not be 1.0 unless normalized; handle iao = 1.0f / curA0 inside inner loop for non‑normalized forms (as MonoPeaking does).
	•  floating equality checks for fast path are okay because setters recompute exact same float values; but small rounding on different threads can cause flip‑flop — still acceptable for performance path.
	•  denormals: keep the small clamp to zero and avoid branching per sample by doing a cheap check or by adding a very small DC offset outside the RT path if desired.
	•  stereo state duplication: keep independent states for L/R; share coefficient interpolation but separate state copies inside the inner loops.

How to split the work (practical plan)

	1. Create abstract class BiquadOp implements Filters containing shared fields, processMono() and processStereo() templates with hooks for:
	2. protected abstract void computeCoeffs();
	3. protected boolean needsNormalization() or accept Filters.Coeffs.
	4. Move coefficient bookkeeping (last*, commit, denorm clamp) into BiquadOp.
	5. Implement AllPass, MonoPeaking, BandPass, etc., as small subclasses that only compute coefficients via Filters.compute(...) and call setCoefficients(Coeffs).
	6. Keep Interpolation.java as the per‑sample enum for delay taps; keep that separate — it addresses a different interpolation domain (spatial vs coefficient ramping).
	7. Add tests for: fast path fidelity, smooth transitions across parameter jumps, extreme bandwidth/frequency clamping, and denormal handling.

Resulting benefits

	•  Remove duplicate inner‑loop code across biquads, reducing maintenance and bugs.
	•  Single place for performance tuning (e.g., unrolling, denorm policy).
	•  Easier addition of new biquad types (Notch, shelving) by implementing a small compute method.



