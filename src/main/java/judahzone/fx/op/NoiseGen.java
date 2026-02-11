package judahzone.fx.op;

import judahzone.util.Constants; // for sample rate used by velvet density


/** Simple, audio-thread safe colored noise generator.
 *  Supports WHITE, PINK, BROWN, BLUE, and GREY noise.
 *  No heap allocations in the audio path; small fixed state.
 *
 *  Precomputes per-micro-sample coefficients in the constructor so the
 *  inner audio loop can call `next()` at an oversampled rate and preserve
 *  the intended per-base-sample filter behaviour (where possible).
 */
public final class NoiseGen {

	private static final int SR = Constants.sampleRate();
    private static final float VELVET_DENSITY = 400f; // taps per second (sparse)
    private static final float PROBABILITY = VELVET_DENSITY / SR; // probability per sample for velvet noise

    @FunctionalInterface
    private interface ColourFn { float apply(NoiseGen g); }

    public enum Colour {
        /** -6dB/octave rolloff, 1/f² spectrum (deep, rumbling) */
        BROWN(g -> g.brown(g.white())),
        /** -3dB/octave rolloff, 1/f spectrum (natural ambient) */
        PINK(g -> g.pink(g.white())),
        /** sparse impulses with random sign, tuned density (airy, clicky) */
        VELVET(g -> g.velvet(g.white())), // fine-tune: VELVET_DENSITY
        /** perceptually flat (pink + equal-loudness curve) */
        GREY(g -> g.grey(g.white())),
        /** flat frequency response, uncorrelated samples */
        WHITE(g -> g.white());

        private final ColourFn fn;
        Colour(ColourFn fn) { this.fn = fn; }

        public float next(NoiseGen gen) { return fn.apply(gen); }
    }

    private int rngState = 0x13579BDF; // xorshift32 state (non-zero)
    private final float[] pinkB = new float[6]; // Kellet filter state b0..b5
    private float pinkB6; // b6 term
    private float brownState = 0f; // integrator for brown noise

    private Colour colour = Colour.WHITE;
    private float gain = 1.0f;

    private final int oversampleFactor;

    // Precomputed per-micro-sample coefficients (computed once in ctor)
    private final float[] pinkMul = new float[6];
    private final float[] pinkFeed = new float[6];
    private final float pinkB6Coef;
    private final float pinkDirectCoef;
    private final float pinkScale;

    private final float brownAlpha;
    private final float brownFeed;

    /** Construct with explicit oversample factor (>=1). */
    public NoiseGen(int factor) {
        oversampleFactor = Math.max(1, factor);

        // Paul Kellet base coefficients (original per-sample multipliers and feeds)
        final float[] BASE_M = new float[] {
            0.99886f, 0.99332f, 0.96900f, 0.86650f, 0.55000f, -0.7616f
        };
        final float[] BASE_C = new float[] {
            0.0555179f, 0.0750759f, 0.1538520f, 0.3104856f, 0.5329522f, -0.0168980f
        };
        final float BASE_B6 = 0.115926f; // assigned to pinkB6 each sample (non-stateful)
        final float BASE_DIRECT = 0.5362f; // direct w feed in sum
        final float BASE_SCALE = 0.11f;

        // For positive multipliers we can compute a real-valued micro-valued multiplier
        // microM^factor == BASE_M  => microM = pow(BASE_M, 1/factor)
        // For negative BASE_M (e.g. -0.7616) a real root may not exist for even factors;
        // in that case keep the base coefficients unchanged to avoid NaN/complex values.
        for (int i = 0; i < 6; i++) {
            float m = BASE_M[i];
            if (m > 0.0f) {
                float microM = (oversampleFactor == 1) ? m : (float)Math.pow(m, 1.0f / oversampleFactor);
                pinkMul[i] = microM;
                // adjust feed so the steady-state response per-base-sample is preserved:
                // original steady numerator ~ BASE_C / (1 - BASE_M)
                // set micro feed so after factor micro-steps the effect matches:
                // microC = BASE_C * (1 - microM) / (1 - BASE_M)
                float denom = (1.0f - BASE_M[i]);
                pinkFeed[i] = (denom == 0f) ? BASE_C[i] : (BASE_C[i] * (1.0f - microM) / denom);
            } else {
                // leave negative multiplier/feed as-is (cannot real-root safely)
                pinkMul[i] = BASE_M[i];
                pinkFeed[i] = BASE_C[i];
            }
        }

        pinkB6Coef = BASE_B6;
        pinkDirectCoef = BASE_DIRECT;
        pinkScale = BASE_SCALE;

        // Brown integrator: original alpha = 0.98 -> feed = 0.02
        final float baseBrownAlpha = 0.98f;
        if (oversampleFactor == 1) {
            brownAlpha = baseBrownAlpha;
        } else {
            // compute micro-sample alpha so microAlpha^factor == baseBrownAlpha
            brownAlpha = (float)Math.pow(baseBrownAlpha, 1.0f / oversampleFactor);
        }
        brownFeed = 1.0f - brownAlpha;

        // TODO: VELVET/VIOLET further tuning
    }

    public NoiseGen() {
        this(1);
    }

    public NoiseGen(int factor, Colour colour) {
        this(factor);
        setColor(colour);
    }

    public void setColor(Colour c) { if (c != null) colour = c; }

    // linear gain multiplier applied to samples
    public void setGain(float g) { gain = g; }

    // fast xorshift32 -> float in [-1,1]
    private float white() {
        rngState ^= (rngState << 13);
        rngState ^= (rngState >>> 17);
        rngState ^= (rngState << 5);
        // mask to 31 bits positive then map to -1..1
        int v = rngState & 0x7FFFFFFF;
        return (v / (float)0x7FFFFFFF) * 2f - 1f;
    }

    private float velvet(float white) {
        // Sparse impulse process (velvet noise). Real-time safe: uses RNG only.
        // Choose a density (taps/sec) and emit an impulse with random sign at that
        // probability. Scale impulses so perceived level is comparable to pink.

        // map w from [-1,1] to [0,1]
        float u = (white + 1f) * 0.5f;
        if (u < PROBABILITY) {
            // generate signed impulse using next RNG value
            float sign = white();
            // normalize amplitude so RMS is roughly similar to pink when averaged
            float amp = pinkScale / (float)Math.sqrt(Math.max(PROBABILITY, 1e-6f));
            return sign * amp;
        }
        return 0f;
    }

    // Paul Kellet pink filter approximation (uses precomputed micro coefficients)
    private float pink(float white) {
        // use precomputed multipliers and feeds to avoid per-sample pow/div work
        pinkB[0] = pinkB[0] * pinkMul[0] + white * pinkFeed[0];
        pinkB[1] = pinkB[1] * pinkMul[1] + white * pinkFeed[1];
        pinkB[2] = pinkB[2] * pinkMul[2] + white * pinkFeed[2];
        pinkB[3] = pinkB[3] * pinkMul[3] + white * pinkFeed[3];
        pinkB[4] = pinkB[4] * pinkMul[4] + white * pinkFeed[4];
        pinkB[5] = pinkB[5] * pinkMul[5] + white * pinkFeed[5];

        pinkB6 = white * pinkB6Coef;

        float pink = pinkB[0] + pinkB[1] + pinkB[2] + pinkB[3] + pinkB[4] + pinkB[5]
                + pinkB6 + white * pinkDirectCoef;

        return pink * pinkScale;
    }

    // leaky integrator for brown noise using precomputed micro alpha/feed
    private float brown(float white) {
        brownState = (brownState * brownAlpha) + (white * brownFeed);
        return brownState;
    }

    // grey = equal-loudness compensated pink (perceptually flat)
    private float grey(float white) {
        float pink = pink(white);
        // inverse A-weighting curve (boost lows and highs relative to mids)
        // simplified: apply gentle shelving filter via state variable
        return pink * 1.2f; // scaled for perceptual loudness match
    }

    // produce one sample (already in -1..1 range, multiplied by gain)
    public float next() {
        return colour.next(this) * gain;
    }

    /** Expose the raw white generator for callers that want to apply
     *  custom per-instance shaping (one-poles, gates, density, etc).
     *  Real-time safe: no allocations, mutates only RNG state. */
    public float nextWhite() {
        return white();
    }

    /** Pure one-pole helper: returns the updated state given the previous
     * state, the pole alpha, and uses noiseGen.next() as input. Caller must store the
     * returned value back into its state field. Kept for backward compatibility. */
    public float onePole(float prevState, float alpha) {
        return prevState * alpha + next() * (1.0f - alpha);
    }

    /** Overloaded one-pole that uses an explicit input sample */
    public float onePole(float prevState, float alpha, float input) {
        return prevState * alpha + input * (1.0f - alpha);
    }

    // fill a buffer segment with noise (audio thread friendly)
    public void fill(float[] buf, int off, int len) {
        int end = off + len;
        for (int i = off; i < end; i++)
            buf[i] = next();
    }

    public Colour getColour() { return colour; }
}