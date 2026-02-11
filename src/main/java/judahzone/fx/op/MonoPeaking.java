package judahzone.fx.op;

import judahzone.util.Constants;
import judahzone.util.Filters;

/** Mono peaking biquad with coefficient smoothing suitable for RT audio. */
public class MonoPeaking implements Filters {

	private float frequency;
	private float bandwidth = 0.5f;
	private float gain_db;

	private static final float SR = Constants.sampleRate();
	private static final int N_FRAMES = Constants.bufSize();
    private static final float INV = 1.0f / N_FRAMES;

    private static final float PIISR = (float) (2f * Math.PI / SR);

    /* current coefficients */
    private float a0, a1, a2, b0, b1, b2;
    /* previous coefficients for smoothing across blocks */
    private float lastA0, lastA1, lastA2, lastB0, lastB1, lastB2;
    private boolean coeffDirty = true;
    private boolean haveLastCoeffs = false;

    /* per-instance filter state (mono) */
    private float xn1, xn2, yn1, yn2;

    /** Constructor with explicit sample rate / oversample factor. */
    public MonoPeaking(float frequency, float gainDb) {
        this.frequency = frequency;
        this.gain_db = gainDb;

        coefficients(); // compute initial coefficients
    }

    /** Non-alloc peaking-coeff computation */
    private void computePeaking(float freq, float sRate, float bw, float gDb, float[] out ) {
        // out: b0,b1,b2,a0,a1,a2
        float a = (float) Math.pow(10.0, gDb / 40.0);
        float w0 = PIISR * freq;
        float sinw0 = (float) Math.sin(w0);
        float cosw0 = (float) Math.cos(w0);

        float alpha = (float) (sinw0 * Math.sinh(LOG_2 / 2.0 * bw * w0 / sinw0));

        float ob0 = 1.0f + alpha * a;
        float ob1 = -2.0f * cosw0;
        float ob2 = 1.0f - alpha * a;
        float oa0 = 1.0f + alpha / a;
        float oa1 = -2.0f * cosw0;
        float oa2 = 1.0f - alpha / a;

        // normalize by a0 (avoid allocating Coeffs)
        out[0] = ob0 / oa0;
        out[1] = ob1 / oa0;
        out[2] = ob2 / oa0;
        out[3] = 1.0f;          // normalized a0
        out[4] = oa1 / oa0;
        out[5] = oa2 / oa0;
    }

    /** Recompute coefficients for a peaking filter and mark dirty. Non-alloc. */
    public void coefficients() {
        float bwClamped = clampBandwidth(bandwidth);
        float[] tmp = new float[6]; // one-time small array per non-realtime setter call OK
        computePeaking(frequency, SR, bwClamped, gain_db, tmp);

        b0 = tmp[0]; b1 = tmp[1]; b2 = tmp[2];
        a0 = tmp[3]; a1 = tmp[4]; a2 = tmp[5];

        coeffDirty = true;
    }

    // --- getters/setters for params ---
    public void setFrequency(float frequency) {
    	this.frequency = frequency;
    	coefficients(); // now uses non-alloc path internally
    }

	public void setBandwidth(float bandwidth) {
		this.bandwidth = clampBandwidth(bandwidth);
    	coefficients();
	}

    public void setGainDb(float gain_db) {
    	this.gain_db = gain_db;
    	coefficients();
    }

    public float getFrequency() { return frequency; }
    public float getBandwidth() { return bandwidth; }
    public float getGainDb() { return gain_db; }

    /** Process a mono buffer in-place. Non-allocating, real-time safe. */
    public void process(float[] buff) {


        // If first time, capture current coeffs as "last"
        if (!haveLastCoeffs) {
            lastA0 = a0; lastA1 = a1; lastA2 = a2;
            lastB0 = b0; lastB1 = b1; lastB2 = b2;
            haveLastCoeffs = true;
        }

        // If coefficients changed, clear dirty flag so interpolation runs
        if (coeffDirty) {
            coeffDirty = false;
        }

        // Fast path: identical coefficients
        if (lastA0 == a0 && lastA1 == a1 && lastA2 == a2
                && lastB0 == b0 && lastB1 == b1 && lastB2 == b2) {

            final float lb0 = b0, lb1 = b1, lb2 = b2;
            final float la1 = a1, la2 = a2;
            final float iao = 1.0f / a0; // usually 1 after normalization

            for (int i = 0; i < N_FRAMES; i++) {
                float xn = buff[i];
                float yn = (lb0 * xn + lb1 * xn1 + lb2 * xn2 - la1 * yn1 - la2 * yn2) * iao;
                if (Math.abs(yn) < 1.0E-8f) yn = 0f;
                buff[i] = yn;
                xn2 = xn1; xn1 = xn;
                yn2 = yn1; yn1 = yn;
            }

        } else {
            // Interpolate coefficients across the block for smooth transitions
            float curA0 = lastA0, curA1 = lastA1, curA2 = lastA2;
            float curB0 = lastB0, curB1 = lastB1, curB2 = lastB2;

            final float dA0 = (a0 - lastA0) * INV;
            final float dA1 = (a1 - lastA1) * INV;
            final float dA2 = (a2 - lastA2) * INV;
            final float dB0 = (b0 - lastB0) * INV;
            final float dB1 = (b1 - lastB1) * INV;
            final float dB2 = (b2 - lastB2) * INV;

            for (int i = 0; i < N_FRAMES; i++) {
                curA0 += dA0; curA1 += dA1; curA2 += dA2;
                curB0 += dB0; curB1 += dB1; curB2 += dB2;

                float iao = 1f / curA0;

                float xn = buff[i];
                float yn = (curB0 * xn + curB1 * xn1 + curB2 * xn2 - curA1 * yn1 - curA2 * yn2) * iao;
                if (Math.abs(yn) < 1.0E-8f) yn = 0f;
                buff[i] = yn;
                xn2 = xn1; xn1 = xn;
                yn2 = yn1; yn1 = yn;
            }

            // commit last coefficients for next block
            lastA0 = a0; lastA1 = a1; lastA2 = a2;
            lastB0 = b0; lastB1 = b1; lastB2 = b2;
        }
    }

    /** Reset filter state (useful on retrigger). */
    public void reset() {
        xn1 = xn2 = yn1 = yn2 = 0f;
    }
}
