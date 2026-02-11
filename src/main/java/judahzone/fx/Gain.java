
package judahzone.fx;

import java.security.InvalidParameterException;

import judahzone.api.FX.RTFX;
import judahzone.util.Constants;
import lombok.Getter;
import lombok.Setter;

public class Gain implements RTFX {

	public enum Settings {VOLUME, PAN, WIDTH};

	public static final int VOLUME = 0;
	public static final int PAN = 1;
	public static final int WIDTH = 2;

	@Getter private final String name = Gain.class.getSimpleName();
	@Getter private final int paramCount = Settings.values().length;
	@Setter @Getter private float preamp = 1f;
	private float gain = 0.5f; // parameter in [0..1], 0.5 = unity
	private float stereo = 0.5f; // pan/balance 0=left .. 0.5=center .. 1=right
	@Getter private float width = 1f; // mid/side stereo  0=mono .. 1=normal .. 2=wide

	/** Last effective left/right gains used in preamp() (preamp * pan). */
	private float preCurrentL = 1f;
	private float preCurrentR = 1f;
	private float widthRamp = 1f; // last applied width (smoothed across buffers)

	/** Last effective post-fader gain used in post(). (linear multiplier) */
	private float postCurrent = 1f;

	public float getGain() {
	    return 2 * gain;
	}

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
	    if (idx == WIDTH)
	        return (int) (width * 50); // width 0..2 -> 0..100
	    throw new InvalidParameterException("idx " + idx);
	}

	@Override public void set(int idx, int value) {
	    if (idx == VOLUME)
	        setGain(value * 0.01f);
	    else if (idx == PAN)
	        setPan(value * 0.01f);
	    else if (idx == WIDTH) // 0 to 100 -> 0 to 2
	        setWidth(value * 0.02f);
	    else throw new InvalidParameterException("idx " + idx);
	}

	public void setGain(float g) {
	    gain = g < 0 ? 0 : g > 1 ? 1 : g;
	}
	public void setPan(float p) {
	    stereo = p < 0 ? 0 : p > 1 ? 1 : p;
	}

	public void setWidth(float w) {
	    width = w < 0 ? 0 : w > 2 ? 2 : w;
	}

	// Map parameter [0..1] to linear multiplier: 0 -> 0.0, 0.5 -> 1.0, 1.0 -> 2.0
	private float gainToLinear() {
	    return 2.0f * gain;
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

	public void monoToStereo(float[] mono, float[] left, float[] right) {
		// complete pan, preamp, gain with ramping.
	    if (mono == null || left == null || right == null)
	    	return;

	    int n = Math.min(mono.length, Math.min(left.length, right.length));
	    if (n == 0)
	    	return;

	    float targetPreL = getLeft();
	    float targetPreR = getRight();
	    float targetPost = gainToLinear();

	    float inverse = 1 / n;

	    float stepPreL = (targetPreL - preCurrentL) * inverse;
	    float stepPreR = (targetPreR - preCurrentR) * inverse ;
	    float stepPost = (targetPost - postCurrent) * inverse;

	    float curPreL = preCurrentL;
	    float curPreR = preCurrentR;
	    float curPost = postCurrent;

	    for (int i = 0; i < n; i++) {
	        float m = curPost;  // combined multiplier per channel
	        left[i] = mono[i] * curPreL * m;
	        right[i] = mono[i] * curPreR * m;
	        curPreL += stepPreL;
	        curPreR += stepPreR;
	        curPost += stepPost;
	    }

	    preCurrentL = targetPreL;
	    preCurrentR = targetPreR;
	    postCurrent = targetPost;

	}

	/**
	 * Apply Gain as a single combined preamp(pan) + post(fader) smoothing pass.
	 *
	 * Width (mid/side) is applied first for stereo signals using buffer-level ramping
	 * from the last applied width (widthRamp) to the current width parameter.
	 */
	@Override
	public void process(float[] left, float[] right) {
	    if (left == null)
	    	return;
	    // Mono: width is irrelevant; apply combined ramp for preamp * gain (gain mapped to linear multiplier)
	    if (right == null) {
	    	processMono(left);
	        return;
	    }

	    // stereo: apply width first (ramped across buffer), then preamp(pan) + post(fader) ramp
	    int n = Math.min(left.length, right.length);
	    if (n <= 0) return;

	    // apply width with smoothing from widthRamp -> width
	    applyWidth(left, right);

	    float targetPreL = getLeft();
	    float targetPreR = getRight();
	    float targetPost = gainToLinear();

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
        float targetPre = getLeft(); // in mono, use left pan target (includes preamp)
        float targetPost = gainToLinear();
        int n = mono.length;
        if (n == 0)
        	return;

        float stepPre = (targetPre - preCurrentL) / n;
        float stepPost = (targetPost - postCurrent) / n;
        float curPre = preCurrentL;
        float curPost = postCurrent;
        for (int i = 0; i < n; i++) {
            float m = curPre * curPost;
            mono[i] = mono[i] * m;
            curPre += stepPre;
            curPost += stepPost;
        }
        preCurrentL = targetPre;
        preCurrentR = targetPre;
        postCurrent = targetPost;
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
	    float target = gainToLinear();
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

	// apply mid/side width in-place with smoothing across the buffer
	public void applyWidth(float[] left, float[] right) {
	    if (left == null || right == null) return;
	    int n = Math.min(left.length, right.length);
	    if (n <= 0) return;
	    float step = (width - widthRamp) / n;
	    float w = widthRamp;
	    for (int i = 0; i < n; i++) {
	        float L = left[i];
	        float R = right[i];
	        float M = 0.5f * (L + R);
	        float S = 0.5f * (L - R);
	        S *= w;                // scale side by current width
	        left[i]  = M + S;
	        right[i] = M - S;
	        w += step;
	    }
	    widthRamp = width;
	}

	@Override
	public void reset() {
	    gain = 0.5f;
	    stereo = 0.5f;
	    preamp = 1f;
	    preCurrentL = 1f;
	    preCurrentR = 1f;
	    postCurrent = 1f;
	    width = 1f;
	    widthRamp = 1f;
	}

	// Helpers for dB <-> linear
	public static float dbToLinear(float db) {
	    return (float) Math.pow(10.0, db / 20.0);
	}
	public static float linearToDb(float lin) {
	    if (lin <= 0f) return -60f;
	    return (float) (20.0 * Math.log10(lin));
	}

}
