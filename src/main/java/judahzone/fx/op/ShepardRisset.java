package judahzone.fx.op;

import judahzone.util.Constants;

/**Shepard-Risset Glissando (Infinite Scale) Generator.
 *
 * <p>Implements the acoustic illusion of a sound that continually rises or falls in pitch
 * without ever seemingly leaving a comfortable frequency range. This is achieved by
 * layering octaves of a sound and applying a spectral window (Gaussian-like) that
 * fades components in as they enter the bottom of the range and fades them out
 * as they approach the top (or vice versa).</p>
 *
 * <p><strong>DSP Architecture:</strong>
 * <ul><li><b>Wavetable:</b> A pre-computed sum of 8 harmonically related partials with
 *       power-of-two spacing (octaves).</li>
 *   <li><b>Spectral Windowing:</b> The {@code ampFade} logic acts as the Shepard
 *       envelope, ensuring the signal is near zero at the {@code RATE_LOWER} and
 *       {@code RATE_UPPER} boundaries to prevent clicks during octave wraps.</li>
 *   <li><b>Pitch Accumulation:</b> Uses a {@code baseRate} and {@code rateScalar}
 *       approach. The pitch is advanced per-buffer, allowing for exponential
 *       glissandi by multiplying the rate.</li>
 *   <li><b>Crossfading:</b> Features an optional {@code RESET_POS_ENABLED} block
 *       to smooth out discontinuities when the oscillator is manually reset
 *       or hard-synced.</li></ul></p>
 *
 * @see https://en.wikipedia.org/wiki/Shepard_tone#Shepard%E2%80%93Risset_glissando
 *
 * TODO: upgrade-to-ramp: Velocity, Rate
 * @see judahzone.prism.PrismRT */
public class ShepardRisset {

	private static final int DURATION_MS = 450;
	private static final int WAVETABLE_size = 512;
	private static final float[] WAVETABLE = new float[WAVETABLE_size];
	static {
	    double twopi = 2.0 * Math.PI;
	    for (int i = 0; i < WAVETABLE_size; i++) {
	        double phase = twopi * i / (WAVETABLE_size - 1);
	        float sample = 0f;
	        float amplitude = 1f;
	        for (int h = 0; h < 8; h++) {
	            sample += amplitude * (float)Math.sin(phase);
	            amplitude *= 0.5f;
	            phase *= 2.0;
	        }
	        WAVETABLE[i] = sample / 8f;
	    }
	}

	private static final float RATE_UPPER = 2f;
	private static final float RATE_LOWER = 0.5f;

	/** Idle gap between cycles: ~DURATION_MS of silence. Public for SirenSynth stagger calc. */
	public static final int IDLE_SAMPLES = (int)((DURATION_MS / 1000f) * Constants.sampleRate());

	/** Set false to remove the resetPos pitch-splice crossfade block in one line.
	 *  NOTE: when true, envLevel multiplies with ampFade — may darken transitions. */
	private static final boolean RESET_POS_ENABLED = true;

	/** Simple on/off gate — true while the voice is producing sound, false during IDLE gap.
	 *  ampFade already handles the Shepard spectral windowing at octave boundaries. */
	private boolean playing;

	private float phase;
	private float rate;        // current effective rate (baseRate * external scalar)
	private float baseRate;    // fundamental rate, reset on wrap - prevents accumulating drift
	private float amplitude;

	// --- resetPos crossfade block (guarded by RESET_POS_ENABLED) ---
	private float envLevel;
	private int resetPos;
	private int resetTotal;
	private int halfWindowSamples;
	private float preResetLevel;
	private float windowScaling = 1f;
	private float windowShape = 0.4f;
	// ----------------------------------------------------------------

	private final float resetPhaseOffset;
	private float fundamentalRatio;
	/** Starting rate fraction (0..1) across [RATE_LOWER..RATE_UPPER]: spreads voices in sweep-phase. */
	private final float startRateFraction;

	/** Counts samples while envelope is idle; self-triggers at IDLE_SAMPLES. */
	private int idleCounter;
	/** True if external caller requested stop (suppresses self-retrigger until next reset()). */
	private volatile boolean externalStop;

	/**
	 * @param phaseOffset    per-voice spectral offset (0..1)
	 * @param fundamentalRatio pitch multiplier (1.0, 1.5, 2.0 etc)
	 * @param startRateFraction position in sweep range at startup (0=bottom, 1=top); spreads voices
	 */
	public ShepardRisset(float phaseOffset, float fundamentalRatio, float startRateFraction) {
	    this.resetPhaseOffset = phaseOffset;
	    this.fundamentalRatio = fundamentalRatio;
	    this.startRateFraction = Math.max(0f, Math.min(1f, startRateFraction));
	    this.amplitude = 0.1f;
	    this.envLevel = this.amplitude;
	    this.resetPos = 0;
	    this.resetTotal = 0;
	    this.halfWindowSamples = Math.max(1, (int)((DURATION_MS / 1000f) * Constants.sampleRate()));
	    reset();
	}

	/** Convenience: start at bottom of sweep (fraction=0). */
	public ShepardRisset(float phaseOffset, float fundamentalRatio) {
	    this(phaseOffset, fundamentalRatio, 0f);
	}

	public void updateRateScaling(float normalizedRate) {
	    float adjustedRate = normalizedRate + (resetPhaseOffset * 0.3f);
	    adjustedRate = Math.max(-1f, Math.min(1f, adjustedRate));
	    float distanceFromCenter = Math.abs(adjustedRate);
	    windowScaling = 2f - (1.5f * distanceFromCenter);
	    windowScaling = Math.max(0.5f, Math.min(2.5f, windowScaling));
	}

	/** Fire-up: reset oscillator to staggered sweep position and open the gate. */
	public void reset() {
	    // Place this voice at its designated fraction of the sweep range
	    float sweepRange = RATE_UPPER - RATE_LOWER;
	    this.baseRate = (RATE_LOWER + startRateFraction * sweepRange) * fundamentalRatio;
	    this.rate = baseRate; // effective rate starts same as base
	    this.phase = 0f;

	    if (RESET_POS_ENABLED) {
	        int baseHalfWindow = (int)((DURATION_MS / 1000f) * Constants.sampleRate());
	        this.halfWindowSamples = Math.max(1, (int)(baseHalfWindow * windowScaling));
	        int requestedTotal = this.halfWindowSamples * 2;
	        int windowGuard = (int)(0.15f * Constants.sampleRate());
	        if (requestedTotal > windowGuard && windowGuard > 0)
	            requestedTotal = windowGuard;
	        this.halfWindowSamples = Math.max(1, requestedTotal / 2);
	        this.resetTotal = this.halfWindowSamples * 2;
	        this.resetPos = 0;
	        this.preResetLevel = this.envLevel;
	    }

	    idleCounter = 0;
	    externalStop = false;
	    playing = true;
	}

	public void setAmplitude(float amp) {
	    this.amplitude = amp;
	    if (!RESET_POS_ENABLED || resetPos == 0)
	        this.envLevel = amplitude;
	}

	public void updateRate(float rateScalar) {
	    // Apply scalar as a multiplicative per-buffer factor so pitch accumulates across buffers
	    // Expect rateScalar ≈ 1f; values slightly >1 raise pitch, <1 lower pitch per buffer.
	    if (!Float.isFinite(rateScalar)) return;
	    // Defensive clamp on rateScalar to prevent runaway; keep reasonably small per-buffer change
	    float clampedScalar = Math.max(0.5f, Math.min(1.5f, rateScalar));
	    // Multiply baseRate so the sweep accumulates over successive buffers
	    baseRate *= clampedScalar;
	    // Ensure baseRate remains in a numerically safe band — guard against extreme accumulation
	    float globalLower = RATE_LOWER * 0.125f; // allow a few octaves below lower bound
	    float globalUpper = RATE_UPPER * 8f;     // allow a few octaves above upper bound
	    if (baseRate < globalLower) baseRate = globalLower;
	    if (baseRate > globalUpper) baseRate = globalUpper;
	    // Sync effective rate for this buffer
	    rate = baseRate;
	    // Final safety clamp
	    rate = Math.max(0.01f, Math.min(10f, rate));
	}

	/** External shut-down: silence this voice; suppresses self-retrigger. */
	public void release() {
	    externalStop = true;
	    playing = false;
	}

	public float nextSample() {
	    // --- IDLE gate: count silence, self-retrigger unless externally stopped ---
	    if (!playing) {
	        if (!externalStop) {
	            idleCounter++;
	            if (idleCounter >= IDLE_SAMPLES) {
	                idleCounter = 0;
	                playing = true;
	            }
	        }
	        return 0f;
	    }

	    // --- resetPos pitch-splice crossfade (guarded) ---
	    if (RESET_POS_ENABLED && resetPos < resetTotal && resetTotal > 0) {
	        if (resetPos < halfWindowSamples) {
	            float t = (float)resetPos / (float)halfWindowSamples;
	            float shaped = (float)Math.pow(t, windowShape);
	            envLevel = preResetLevel * (1f - shaped);
	        } else if (resetPos == halfWindowSamples) {
	            envLevel = 0f;
	        } else {
	            float t = (float)(resetPos - halfWindowSamples) / (float)halfWindowSamples;
	            float shaped = (float)Math.pow(t, windowShape);
	            envLevel = amplitude * shaped;
	        }
	        resetPos++;
	        if (resetPos >= resetTotal) {
	            envLevel = amplitude;
	            resetPos = 0;
	            resetTotal = 0;
	        }
	    }

	    float ampFade = RESET_POS_ENABLED ? envLevel : amplitude;
	    float adjustedUpper = RATE_UPPER * (1f + 0.15f * resetPhaseOffset);
	    float adjustedLower = RATE_LOWER * (1f - 0.15f * resetPhaseOffset);

	    // Shepard spectral windowing: fade out as rate approaches octave boundaries
	    if (rate > adjustedUpper * 0.5f) {
	        ampFade = (RESET_POS_ENABLED ? envLevel : amplitude) * (adjustedUpper - rate) / (adjustedUpper * 0.5f);
	        ampFade = Math.max(0f, ampFade);
	    }
	    if (rate < adjustedLower * 2f)
	        ampFade *= rate / (adjustedLower * 2f);

	    // Octave wrap: signal is already near zero here (ampFade ≈ 0), enter IDLE gap
	    if (rate > adjustedUpper) {
	        baseRate *= 0.125f; // update base rate for next cycle
	        rate = baseRate;     // sync effective rate
	        phase *= 0.125f;
	        if (!externalStop) {
	            playing = false; // enter IDLE gap; idleCounter will self-retrigger
	            idleCounter = 0;
	        }
	    } else if (rate < adjustedLower) {
	        baseRate *= 8f;     // update base rate for next cycle
	        rate = baseRate;    // sync effective rate
	        phase *= 8f;
	        if (phase > WAVETABLE_size)
	            phase -= WAVETABLE_size;
	    }

	    phase += rate;
	    if (phase >= WAVETABLE_size)
	        phase -= WAVETABLE_size;
	    else if (phase < 0f) // handle negative rate scenarios
	        phase += WAVETABLE_size;

	    // Guard against numerical errors during extreme rate changes
	    if (!Float.isFinite(phase) || phase < 0f || phase >= WAVETABLE_size)
	        phase = 0f;

	    int idx1 = Math.max(0, Math.min(WAVETABLE_size - 1, (int)phase));
	    int idx2 = (idx1 + 1) % WAVETABLE_size;
	    float frac = phase - idx1;
	    frac = Math.max(0f, Math.min(1f, frac)); // clamp interpolation factor
	    float osample = (1f - frac) * WAVETABLE[idx1] + frac * WAVETABLE[idx2];

	    // ampFade IS the Shepard window
	    return ampFade * osample;
	}

}
