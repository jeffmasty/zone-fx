package judahzone.fx;

import java.security.InvalidParameterException;

import judahzone.api.FX.RTFX;
import judahzone.api.FX.TimeFX;
import judahzone.fx.op.VariableDelayOp;
import judahzone.util.Constants;
import lombok.Getter;
import lombok.Setter;

/** Two Identical Mono Delays. Delay time and feedback are
 * interpolated over the period of one buffer. */
public class Delay implements TimeFX, RTFX {

    public enum Settings {
        DelayTime, Feedback, Type, Sync
    }

    /* in seconds */
    public static final float MAX_DELAY = 3.75f;
    public static final float MIN_DELAY = 0.15f;
    public static final float DEFAULT_TIME = .36f;

    @Getter private final int paramCount = Settings.values().length;
    @Getter private final String name = Delay.class.getSimpleName();

    /** in seconds */
    @Getter private volatile float delay;
    private final VariableDelayOp left;
    private final VariableDelayOp right;
    @Setter @Getter String type = TYPE[0];
    @Setter @Getter boolean sync;

    public Delay() {
        this(MAX_DELAY, false);
    }

    public Delay(float maxdelay) {
    	this(maxdelay, false);
    }

    public Delay(float maxdelay, boolean mono) {
        int minBufSize = (int) (maxdelay * SAMPLE_RATE) + 10; // delay ups to pow(2)
        left = new VariableDelayOp(minBufSize);
        right = mono ? null : new VariableDelayOp(minBufSize);
        setDelayTime(DEFAULT_TIME);
        reset();
	}

    @Override public int get(int idx) {
        if (idx == Settings.DelayTime.ordinal())
            return Constants.reverseLog(delay, MIN_DELAY, MAX_DELAY);
        if (idx == Settings.Feedback.ordinal())
            return Math.round(left.getFeedback() * 100);
        if (idx == Settings.Type.ordinal())
            return TimeFX.indexOf(type);
        if (idx == Settings.Sync.ordinal())
            return sync ? 1 : 0;
        throw new InvalidParameterException();
    }

    @Override public void set(int idx, int value) {
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
    	if (delay == msec)
    		return;
        delay = Math.max(0f, msec);
        left.setDelayTime(delay);
        if (right != null)
            right.setDelayTime(delay);
    }

    public void setFeedback(float feedback) {
        if (feedback < 0 || feedback > 1) {
            throw new IllegalArgumentException("" + feedback);
        }
        left.setFeedback(feedback);
        if (right != null)
        	right.setFeedback(feedback);
    }

    @Override public void reset() {
    	left.reset(delay);
        if (right != null)
        	right.reset(delay);
    }

    @Override public void sync(float unit) {
        float msec = 0.001f * (unit + unit * TimeFX.indexOf(type));
        setDelayTime(2 * msec);
    }

    public void process(float[] mono) {
		left.process(mono);
	}

    @Override public void process(float[] leftBuffer, float[] rightBuffer) {
        left.process(leftBuffer);
        if (rightBuffer != null && right != null)
            right.process(rightBuffer);
    }

}
