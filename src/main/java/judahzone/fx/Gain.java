package judahzone.fx;

import java.security.InvalidParameterException;

import judahzone.api.FX.RTFX;
import judahzone.util.Constants;
import lombok.Getter;
import lombok.Setter;

@Getter public class Gain implements RTFX {

	public enum Settings {VOLUME, PAN};

	public static final int VOLUME = 0;
	public static final int PAN = 1;

	private final String name = "Gain";
	private final int paramCount = 2;
	private float gain = 0.5f;
	private float stereo = 0.5f;
	@Setter private float preamp = 1f;

	/** Last effective left/right gains used in preamp() (preamp * pan). */
	private float preCurrentL = 1f;
	private float preCurrentR = 1f;

	/** Last effective post-fader gain used in post(). */
	private float postCurrent = 1f;

	/** pan/balance */
	public boolean isActive() {
	    return stereo < 0.49f || stereo > 0.51f;
	}

	/** pan/balance */
	public void setActive(boolean active) {
	    if (!active) stereo = 0.5f;
	}

	@Override public int get(int idx) {
	    if (idx == VOLUME)
	        return (int) (gain * 100);
	    if (idx == PAN)
	        return (int) (stereo * 100);
	    throw new InvalidParameterException("idx " + idx);
	}

	@Override public void set(int idx, int value) {
	    if (idx == VOLUME)
	        setGain(value * 0.01f);
	    else if (idx == PAN)
	        setPan(value * 0.01f);
	    else throw new InvalidParameterException("idx " + idx);
	}

	public void setGain(float g) {
	    gain = g < 0 ? 0 : g > 1 ? 1 : g;
	}
	public void setPan(float p) {
	    stereo = p < 0 ? 0 : p > 1 ? 1 : p;
	}

	public float getLeft() {
	    if (stereo < 0.5f) // towards left, half log increase
	        return (1 + (0.5f - stereo) * 0.2f) * preamp;
	    return 2 * (1 - stereo) * preamp;
	}

	public float getRight() {
	    if (stereo > 0.5f)
	        return (1 + (stereo - 0.5f) * 0.2f) * preamp;
	    return 2 * stereo * preamp;
	}

	/**
	 * Apply Gain as a single combined preamp(pan) + post(fader) smoothing pass.
	 *
	 * Behavior mirrors the original FloatBuffer implementation but operates on float[].
	 * In mono mode (right == null) the left buffer is processed and preamp pan target is used.
	 * For stereo the number of frames processed is the minimum of left.length and right.length.
	 */
	@Override
	public void process(float[] left, float[] right) {
	    if (left == null) return;

	    if (right == null) {
	        // Mono: apply combined ramp for preamp * gain
	        float targetPre = getLeft(); // in mono, use left pan target
	        float targetPost = gain;
	        int n = left.length;
	        if (n <= 0) return;
	        float stepPre = (targetPre - preCurrentL) / n;
	        float stepPost = (targetPost - postCurrent) / n;
	        float curPre = preCurrentL;
	        float curPost = postCurrent;
	        for (int i = 0; i < n; i++) {
	            float m = curPre * curPost;
	            left[i] = left[i] * m;
	            curPre += stepPre;
	            curPost += stepPost;
	        }
	        preCurrentL = targetPre;
	        preCurrentR = targetPre;
	        postCurrent = targetPost;
	        return;
	    }

	    // stereo
	    float targetPreL = getLeft();
	    float targetPreR = getRight();
	    float targetPost = gain;

	    int n = Math.min(left.length, right.length);
	    if (n <= 0) return;

	    float stepPreL = (targetPreL - preCurrentL) / n;
	    float stepPreR = (targetPreR - preCurrentR) / n;
	    float stepPost = (targetPost - postCurrent) / n;

	    float curPreL = preCurrentL;
	    float curPreR = preCurrentR;
	    float curPost = postCurrent;

	    for (int i = 0; i < n; i++) {
	        float mL = curPreL * curPost;
	        float mR = curPreR * curPost;
	        left[i] = left[i] * mL;
	        right[i] = right[i] * mR;
	        curPreL += stepPreL;
	        curPreR += stepPreR;
	        curPost += stepPost;
	    }

	    preCurrentL = targetPreL;
	    preCurrentR = targetPreR;
	    postCurrent = targetPost;
	}

	/**
	 * Process mono buffer in-place with preamp * gain (no smoothing).
	 * Kept for compatibility with original utility.
	 */
	public void processMono(float[] mono) {
	    if (mono == null) return;
	    float precompute = preamp * gain;
	    for (int z = 0; z < mono.length; z++)
	        mono[z] = mono[z] * precompute;
	}

	/** preamp and panning, with smoothing, stereo only */
	public void preamp(float[] left, float[] right) {
	    if (left == null || right == null) return;
	    float targetL = getLeft();
	    float targetR = getRight();
	    int frames = Constants.bufSize();
	    ramp(left, frames, preCurrentL, targetL);
	    ramp(right, frames, preCurrentR, targetR);
	    preCurrentL = targetL;
	    preCurrentR = targetR;
	}

	/** gain only, with smoothing, stereo only */
	public void post(float[] left, float[] right) {
	    if (left == null || right == null) return;
	    float target = gain;
	    int frames = Constants.bufSize();
	    ramp(left, frames, postCurrent, target);
	    ramp(right, frames, postCurrent, target);
	    postCurrent = target;
	}

	// apply a linear ramp from start→end over up to 'frames' samples (bounded by buf.length)
	private static void ramp(float[] buf, int frames, float startGain, float endGain) {
	    if (frames <= 0 || buf == null || buf.length == 0) {
	        return;
	    }
	    int n = Math.min(frames, buf.length);
	    float step = (endGain - startGain) / n;
	    float g = startGain;
	    for (int i = 0; i < n; i++) {
	        buf[i] *= g;
	        g += step;
	    }
	}

	@Override
	public void reset() {
	    gain = 0.5f;
	    stereo = 0.5f;
	    preamp = 1f;
	    preCurrentL = 1f;
	    preCurrentR = 1f;
	    postCurrent = 1f;
	}
}