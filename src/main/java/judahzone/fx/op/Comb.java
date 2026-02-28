package judahzone.fx.op;

import judahzone.api.OffOn;

/** A simple feedback comb filter with independent state.
    The delay is fixed at construction; feedback and damping can change in real time.
    damp ∈ [0,1]: 0 = no damping, 1 = maximum damping (lowest cutoff).  */
public final class Comb implements OffOn {

    private final float[] buf;
    private final float[] zeroes;
    private final int bufLen;
    private int idx = 0;

    private float feedback = 0.0f;         // user-set feedback (-0.999 .. 0.999)
    private float damp = 0.0f;             // damping [0..1]
    private float filterStore = 0.0f;

    // Auto-normalize fields (non-breaking: disabled by default).
    // When enabled the per-loop gain magnitude is computed from targetTauSamples:
    //   g = exp(-D / tauSamples)
    // and the sign is taken from `feedback`.
    private boolean autoNormalizeFeedback = false;
    private float targetTauSamples = 4800f; // default tau in samples (~0.1s @48k)

    public Comb(int delaySamples) {
        int d = Math.max(1, delaySamples);
        buf = new float[d];
        zeroes = new float[d];
        bufLen = d;
        idx = 0;
    }

    @Override public void trigger() {
        reset();
    }

    @Override public void reset() {
        // Clear internal circular buffer and all transient state so the comb
        // produces silence and has no residual memory after reset.
        System.arraycopy(zeroes, 0, buf, 0, bufLen);
        filterStore = 0f;
        idx = 0;
    }

    /** Set raw feedback coefficient in \[-0.999, 0.999\]. If auto-normalize is
        enabled this value supplies the sign only (magnitude comes from tau). */
    public void setFeedback(float fb) {
        feedback = Math.max(-0.999f, Math.min(0.999f, fb));
    }

    /** Enable/disable automatic feedback normalization (preserve API). */
    public void setAutoNormalizeFeedback(boolean on) {
        autoNormalizeFeedback = on;
    }

    /** Set desired decay/build time in samples used when auto-normalize is on.
        tauSamples must be > 0. */
    public void setTargetTauSamples(float tauSamples) {
        targetTauSamples = Math.max(1f, tauSamples);
    }

    /** Convenience: set decay time in seconds. Caller must supply sampleRate. */
    public void setTargetTauSeconds(float seconds, float sampleRate) {
        final float sr = Math.max(1f, sampleRate);
        targetTauSamples = Math.max(1f, seconds * sr);
    }

    public void setdamp(float d) {
        damp = Math.max(0f, Math.min(1f, d));
    }

    public float getFeedback() { return feedback; }
    public float getdamp() { return damp; }
    public boolean isAutoNormalizeFeedback() { return autoNormalizeFeedback; }
    public float getTargetTauSamples() { return targetTauSamples; }

    /** Adds comb output into out[]. Each Comb maintains independent filterStore state. */
    public void processMix(float[] in, float[] out) {
        if (in == null || out == null) return;
        final int n = Math.min(in.length, out.length);
        final float lDamp = damp;
        final float invDamp = 1.0f - lDamp;
        final float[] lBuf = buf;
        final int len = bufLen;

        // Compute effective per-loop feedback once per call (RT-friendly).
        float lFeed = feedback;
        if (autoNormalizeFeedback) {
            // magnitude based on tau formula: g = exp(-D / tau)
            // use bufLen (D) and targetTauSamples (tau)
            final float g = (float) Math.exp(-((float) len) / Math.max(1f, targetTauSamples));
            lFeed = Math.copySign(g, feedback); // keep sign from user feedback
        }

        float lFilter = filterStore;
        int lIdx = idx;

        for (int i = 0; i < n; i++) {
            float delayed = lBuf[lIdx];
            // One-pole lowpass in feedback path
            lFilter = (delayed * invDamp) + (lFilter * lDamp);

            float fbOut = in[i] + lFilter * lFeed;
            lBuf[lIdx] = fbOut;
            out[i] += fbOut;

            lIdx = (lIdx + 1) % len;
        }

        idx = lIdx;
        filterStore = lFilter;
    }
}
