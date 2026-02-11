package judahzone.fx.op;


/** Comb/delay-line specification used to configure CombFilter instances.
 \* Immutable, intended for offline/config use (no allocations on audio path). */
public final class CombSpec {

    public static final float MAX_FEEDBACK = 0.999f;
    public static final float MIN_FEEDBACK = 0f;

    private final int delaySamples;
    private final float feedback; // 0 .. MAX_FEEDBACK
    private final float damp;     // 0 .. 1 (comb's damp param matches CombFilter.setdamp)

    public CombSpec(int delaySamples, float feedback, float damp) {
        this.delaySamples = Math.max(1, delaySamples);
        this.feedback = clamp(feedback, MIN_FEEDBACK, MAX_FEEDBACK);
        this.damp = clamp(damp, 0f, 1f);
    }

    public int getDelaySamples() { return delaySamples; }
    public float getFeedback() { return feedback; }
    public float getDamp() { return damp; }

    /** Create a spec from milliseconds and sample rate. */
    public static CombSpec fromMs(float ms, int sampleRate, float feedback, float damp) {
        int samples = Math.max(1, Math.round(ms * sampleRate * 0.001f));
        return new CombSpec(samples, feedback, damp);
    }

    /** Convenience default spec. */
    public static CombSpec defaults(int delaySamples) {
        return new CombSpec(delaySamples, 0.5f, 0.5f);
    }

    private static float clamp(float v, float lo, float hi) {
    	return Math.min(hi, Math.max(lo, v));
    }

    @Override
    public String toString() {
        return "CombSpec[samples=" + delaySamples + ",feedback=" + feedback + ",damp=" + damp + "]";
    }
}
