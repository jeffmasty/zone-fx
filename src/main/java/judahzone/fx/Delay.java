package judahzone.fx;

import java.security.InvalidParameterException;
import java.util.Arrays;

import judahzone.api.FX.RTFX;
import judahzone.api.TimeFX;
import judahzone.util.Constants;
import lombok.Getter;
import lombok.Setter;

/**
 * Two Identical Mono Delays.
 * Delay time and feedback are interpolated over the period of one buffer.
 * Some smoothing applied.
 *
 * Ported from the FloatBuffer-based Effect implementation to float[] FX API.
 * All logic and behavior are preserved as closely as possible.
 */
public class Delay implements TimeFX, RTFX {

    public enum Settings {
        DelayTime, Feedback, Type, Sync
    }

    // in seconds
    public static final float MAX_DELAY = 3.75f;
    public static final float MIN_DELAY = 0.15f;
    public static final float DEFAULT_TIME = .4f;
    static final float THRESHOLD = 0.00001f; // de-normalize

    @Setter @Getter
    boolean sync;
    /** in seconds */
    private volatile float delayTime;
    private float calculated; // delay in samples
    @Getter
    private volatile float feedback = 0.36f;
    private final VariableDelayOp left;
    private final VariableDelayOp right;
    @Setter
    private boolean slapback;
    @Setter @Getter
    String type = TYPE[0];

    public Delay() {
        this(MAX_DELAY);
    }

    public Delay(float maxdelay) {
        int delayBufSize = (int) (maxdelay * SAMPLE_RATE) + 10;
        left = new VariableDelayOp(delayBufSize);
        right = new VariableDelayOp(delayBufSize);
        setDelayTime(DEFAULT_TIME);
        reset();
    }

    @Override
    public int getParamCount() {
        return Settings.values().length;
    }

    @Override
    public String getName() {
        return Delay.class.getSimpleName();
    }

    @Override
    public int get(int idx) {
        if (idx == Settings.DelayTime.ordinal())
            return Constants.reverseLog(delayTime, MIN_DELAY, MAX_DELAY);
        if (idx == Settings.Feedback.ordinal())
            return Math.round(getFeedback() * 100);
        if (idx == Settings.Type.ordinal())
            return TimeFX.indexOf(type);
        if (idx == Settings.Sync.ordinal())
            return sync ? 1 : 0;
        throw new InvalidParameterException();
    }

    @Override
    public void set(int idx, int value) {
        if (idx == Settings.DelayTime.ordinal()) {
            setDelayTime(Constants.logarithmic(value, MIN_DELAY, MAX_DELAY));
        } else if (idx == Settings.Feedback.ordinal()) {
            setFeedback(value / 100f);
        } else if (idx == Settings.Type.ordinal() && value < TimeFX.TYPE.length) {
            type = TimeFX.TYPE[value];
        } else if (idx == Settings.Sync.ordinal()) {
            sync = value > 0;
        } else {
            throw new InvalidParameterException("" + idx);
        }
    }

    public void setDelayTime(float msec) {
        delayTime = msec;
        calculated = delayTime * SAMPLE_RATE;
    }

    /** @return delay time in seconds */
    public float getDelay() {
        return delayTime;
    }

    public void setFeedback(float feedback) {
        if (feedback < 0 || feedback > 1) {
            throw new IllegalArgumentException("" + feedback);
        }
        this.feedback = feedback;
    }

    @Override
    public void reset() {
        if (left.workArea != null)
            Arrays.fill(left.workArea, 0);
        if (right.workArea != null)
            Arrays.fill(right.workArea, 0);
        // initialize internal delay state to the current target so we don't jump
        left.resetState(calculated);
        right.resetState(calculated);
    }

    /**
     * Process in-place on input/output buffers.
     *
     * \@param leftBuffer  non-null, length \>= FX.N_FRAMES
     * \@param rightBuffer may be null for mono; if non-null length \>= FX.N_FRAMES
     */
    @Override
    public void process(float[] leftBuffer, float[] rightBuffer) {
        if (leftBuffer == null) {
            // original FloatBuffer version assumed non-null left; preserve that expectation
            throw new IllegalArgumentException("left buffer must not be null");
        }
        if (leftBuffer.length < N_FRAMES) {
            throw new IllegalArgumentException("left buffer too small, need at least " + N_FRAMES);
        }
        if (rightBuffer != null && rightBuffer.length < N_FRAMES) {
            throw new IllegalArgumentException("right buffer too small, need at least " + N_FRAMES);
        }

        left.process(leftBuffer);

        if (slapback) { // not implemented further, same as original
            // slapback uses left channel input in original;
            // we keep the same behavior here.
            right.slapback(leftBuffer);
            return;
        }

        if (rightBuffer != null) {
            right.process(rightBuffer);
        }
    }

    /**
     * Per-sample exponential smoothing of the read delay avoids abrupt jumps
     * when the target delay changes (short or long).
     *
     * Not perfect but better; direct port from the FloatBuffer version.
     */
    private class VariableDelayOp {
        float[] workArea;
        int rovepos = 0;
        // current smoothed delay (in samples). Initialized in resetState().
        float lastdelay;
        // smoothing time constant: number of samples over which delay moves ~close to target.
        private static final int SMOOTHING_SAMPLES = 64;
        // derived smoothing coefficient (per-sample)
        private final float smoothAlpha = 1.0f / Math.max(1, SMOOTHING_SAMPLES);

        VariableDelayOp(int bufSize) {
            this.workArea = new float[bufSize];
            this.rovepos = 0;
            this.lastdelay = 0f;
        }

        void resetState(float initDelaySamples) {
            // initialize smoothing state to the current (target) delay to avoid jumps
            this.lastdelay = initDelaySamples;
            this.rovepos = 0;
        }

        void process(float[] in) {
            float ldelay = lastdelay; // smoothed delay (samples)
            float fb = feedback;
            float[] work = workArea;
            int rnlen = work.length;
            int pos = rovepos;

            // update ldelay per-sample with exponential smoothing towards
            // 'calculated' (the target delay in samples).
            float target = calculated;

            float r, s, a, b, o;
            int ri;
            float scratch;

            for (int i = 0; i < N_FRAMES; i++) {
                // smooth one sample towards target delay
                ldelay += (target - ldelay) * smoothAlpha;

                // compute read index for this (smoothed) delay
                r = pos - (ldelay + 2f) + rnlen;
                ri = (int) r;
                s = r - ri;

                // safe circular access (ri % rnlen)
                int idxA = ri % rnlen;
                if (idxA < 0) idxA += rnlen;
                int idxB = idxA + 1;
                if (idxB >= rnlen) idxB -= rnlen;

                a = work[idxA];
                b = work[idxB];
                o = a * (1 - s) + b * s;

                float inSample = in[i];

                // write feedback into buffer
                scratch = inSample + o;
                if (Math.abs(scratch) < THRESHOLD) // denormalize
                    scratch = 0f;
                work[pos] = scratch * fb;

                // write output (original wrote scratch back)
                in[i] = scratch;

                pos++;
                if (pos >= rnlen) pos = 0;
            }
            // store smoothed delay and position for next block
            rovepos = pos;
            lastdelay = ldelay;
        }

        void slapback(float[] in) {
            float ldelay = lastdelay;
            float fb = feedback;
            int rnlen = workArea.length;
            int pos = rovepos;
            float target = calculated;

            float r, s, a, b, o;
            int ri;

            for (int i = 0; i < N_FRAMES; i++) {
                ldelay += (target - ldelay) * smoothAlpha;

                r = pos - (ldelay + 2f) + rnlen;
                ri = (int) r;
                s = r - ri;

                int idxA = ri % rnlen;
                if (idxA < 0) idxA += rnlen;
                int idxB = idxA + 1;
                if (idxB >= rnlen) idxB -= rnlen;

                a = workArea[idxA];
                b = workArea[idxB];
                o = a * (1 - s) + b * s;

                float outSample = o;
                workArea[pos] = in[i] + outSample * fb;
                in[i] = outSample;

                pos++;
                if (pos >= rnlen) pos = 0;
            }

            rovepos = pos;
            lastdelay = ldelay;
        }
    }

    @Override
    public void sync(float unit) {
        float msec = 0.001f * (unit + unit * TimeFX.indexOf(type));
        setDelayTime(2 * msec);
    }
}
