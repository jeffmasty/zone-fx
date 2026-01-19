package judahzone.fx;

import java.security.InvalidParameterException;

import judahzone.api.FX;
import judahzone.api.TimeFX;
import lombok.Getter;
import lombok.Setter;

public class Chorus implements TimeFX, FX.RTFX {

    public enum Settings {
        Rate, Depth, Feedback, Type, Sync, Phase
    }

    private static final float PI2 = (float) Math.PI * 2;
    private static final float defaultRate = 1.4f;
    private static final float defaultDepth = 0.4f;
    private static final float defaultFeedback = 0.4f;

    @Setter @Getter private boolean sync;
    @Setter @Getter private String type = TYPE[0];

    /** times per second */
    private float rate = defaultRate;
    /** between 0 and 1 */
    private float depth = defaultDepth;
    /** between 0 and 1 */
    private float feedback = defaultFeedback;
    /** between 0 and 1 */
    @Setter @Getter
    private float phase = 0.42f;

    private final LFODelay leftDsp = new LFODelay();
    private final LFODelay rightDsp = new LFODelay();

    @Override
    public void sync(float unit) {
        int reverseIndex = TimeFX.TYPE.length - TimeFX.indexOf(type);
        rate = 0.001f * (unit + unit * reverseIndex);
    }

    @Override
    public String getName() {
        return Chorus.class.getSimpleName();
    }

    @Override
    public int getParamCount() {
        return Settings.values().length;
    }

    @Override
    public int get(int idx) {
        if (idx == Settings.Rate.ordinal())
            return Math.round(rate * 20);
        if (idx == Settings.Depth.ordinal())
            return Math.round(depth * 100);
        if (idx == Settings.Feedback.ordinal())
            return Math.round(feedback * 100);
        if (idx == Settings.Type.ordinal())
            return TimeFX.indexOf(type);
        if (idx == Settings.Sync.ordinal())
            return sync ? 1 : 0;
        if (idx == Settings.Phase.ordinal())
            return Math.round(phase * 100);
        throw new InvalidParameterException();
    }

    @Override
    public void set(int idx, int value) {
        if (idx == Settings.Rate.ordinal()) {
            rate = value / 20f;
        } else if (idx == Settings.Depth.ordinal()) {
            setDepth(value / 100f);
        } else if (idx == Settings.Feedback.ordinal()) {
            feedback = value / 100f;
        } else if (idx == Settings.Type.ordinal() && value < TimeFX.TYPE.length) {
            type = TimeFX.TYPE[value];
        } else if (idx == Settings.Sync.ordinal()) {
            sync = value > 0;
        } else if (idx == Settings.Phase.ordinal()) {
            phase = value / 100f;
        } else {
            throw new InvalidParameterException();
        }
    }

    void setDepth(float depth) {
        leftDsp.setDelay(depth / 1000);
        rightDsp.setDelay(depth / 1000);
        this.depth = depth;
    }

    @Override
    public void process(float[] left, float[] right) {
        leftDsp.processReplace(left);
        rightDsp.processReplace(right);
    }

    private class LFODelay {
        private int lfocount;
        @Setter
        float delay = depth * 0.001f;
        float[] workArea = new float[N_FRAMES];
        float range = 0.5f;
        float delayTime;
        int rovepos;
        float lastdelay;

        // lowpass filter for feedback
        float fbFilterState = 0f;
        final float fbCut = 0.25f; // 0..1

        void goFigure() {
            if (rate > 0.01 && range > 0) {
                lfocount += N_FRAMES;
                float lfolength = SAMPLE_RATE / rate;
                lfocount %= (int) (lfolength);
                float r = lfocount / lfolength;
                r *= PI2;
                r += phase * PI2;
                if (r > PI2) {
                    r -= PI2;
                }
                r = delay * range * (float) Math.sin(r);
                delayTime = delay + r;
            } else {
                lfocount = 0;
                delayTime = delay;
            }
        }

        void processReplace(float[] buf) {
            goFigure();
            float delaySamples = delayTime * SAMPLE_RATE;
            float ldelay = lastdelay;

            float[] work = workArea;
            int rnlen = work.length;
            int pos = rovepos;
            float delta = (delaySamples - ldelay) / N_FRAMES;
            float fb = feedback;

            float r, s, a, b, o;
            int ri;
            for (int i = 0; i < N_FRAMES; i++) {
                r = pos - (ldelay + 2) + rnlen;
                ri = (int) r;
                s = r - ri;
                a = work[ri % rnlen];
                b = work[(ri + 1) % rnlen];
                o = a * (1 - s) + b * s;

                float in = buf[i];
                float fbSample = o * fb;
                fbFilterState = fbFilterState + fbCut * (fbSample - fbFilterState);

                work[pos] = in + fbFilterState;
                buf[i] = o;
                pos = (pos + 1) % rnlen;
                ldelay += delta;
            }

            rovepos = pos;
            lastdelay = delaySamples;
        }

        @SuppressWarnings("unused")
        public void processAdd(float[] buf) {
            goFigure();
            float delaySamples = delayTime * SAMPLE_RATE;
            float ldelay = lastdelay;

            int rnlen = workArea.length;
            int pos = rovepos;
            float delta = (delaySamples - ldelay) / N_FRAMES;

            float r, s, a, b, o;
            int ri;
            for (int i = 0; i < N_FRAMES; i++) {
                r = pos - (ldelay + 2) + rnlen;
                ri = (int) r;
                s = r - ri;
                a = workArea[ri % rnlen];
                b = workArea[(ri + 1) % rnlen];
                o = a * (1 - s) + b * s;

                float in = buf[i];
                float fbSample = o * feedback;
                fbFilterState = fbFilterState + fbCut * (fbSample - fbFilterState);
                workArea[pos] = in + fbFilterState;

                buf[i] = in + o;
                pos = (pos + 1) % rnlen;
                ldelay += delta;
            }
            rovepos = pos;
            lastdelay = delaySamples;
        }
    }
}
