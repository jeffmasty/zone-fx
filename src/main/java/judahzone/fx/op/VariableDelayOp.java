package judahzone.fx.op;

import java.util.Arrays;

import judahzone.util.Constants;

/**Mono Delay operator. Delay time and feedback are variable. Changes in delay time are
 * interpolated over the period of one buffer. Input and output buffers may be the same.
 * @author Neil C Smith (derived from code by Karl Helgason)  */

/* Key responsibilities if wrapping VariableDelayOp:
	•  Parameter scaling & clamping: convert seconds ↔ samples (or host UI units), clamp delay to buffer capacity (workArea length - 2).
	•  Validation: enforce feedback ∈ [0,1] and clamp/validate delay inputs on non-RT path.
	•  Non-RT vs RT rules: writes (setDelayTime, setFeedback, reset) run non-real-time and must not lock audio thread;
	•  Reset / initialization: zero buffers (non-RT), initialize smoothing/lastDelay to avoid audible jumps.
	•  Defaults & constants: provide MIN/MAX/DEFAULT delay constants used by UI/host.
	•  Sync/tempo mapping: convert sync units into seconds and call setDelayTime.
	•  Buffer sizing policy: choose and explain buffer size (and pow2 rounding if used); be aware rounding up increases max delay.
	•  Input validation in process: check null/length of input buffers and throw early from non-RT path. */
public class VariableDelayOp {

	private static final int N_FRAMES = Constants.bufSize();
	private static final int SAMPLE_RATE = Constants.sampleRate();

	private static final float DENORM_THRESHOLD = 0.00001f; // de-normalize

	// smoothing
	private static final int SMOOTHING_SAMPLES = 64;
	private final float smoothAlpha = 1.0f / Math.max(1, SMOOTHING_SAMPLES);

	// ring buffer (power-of-two length) and mask for cheap wrap
	private final float[] workArea;
	private final int mask;

	// runtime state (audio thread)
	private int rovepos = 0;
	private float lastdelay = 0f;

	// control (non-RT writes)
	private volatile float targetSamples = 0f;
	private volatile float feedback = 0.36f;

	public VariableDelayOp(int bufSize) {
		int pow2 = nextPow2(Math.max(4, bufSize));
		this.workArea = new float[pow2];
		this.mask = pow2 - 1;
		this.rovepos = 0;
		this.lastdelay = 0f;
		this.targetSamples = 0f;
	}

	/** Set delay time in seconds (non-RT). Clamped to buffer capacity. */
	public void setDelayTime(float delaySeconds) {
		float samples = Math.max(0f, delaySeconds) * SAMPLE_RATE;
		float maxSamples = workArea.length - 2;
		if (samples > maxSamples)
			samples = maxSamples;
		this.targetSamples = samples;
	}

	/** Set feedback (0..1). Non-RT. */
	public void setFeedback(float fb) {
		if (fb < 0f || fb > 1f)
			throw new IllegalArgumentException("" + fb);
		this.feedback = fb;
	}

	/** Getter used by host UI (non-RT). */
	public float getFeedback() {
		return feedback;
	}

	/** Reset internal state. Accepts seconds (non-RT) and clears buffer. */
	public void reset(float delaySeconds) {
		float samples = Math.max(0f, delaySeconds) * SAMPLE_RATE;
		float maxSamples = workArea.length - 2;
		if (samples > maxSamples)
			samples = maxSamples;

		this.lastdelay = samples;
		this.targetSamples = samples;
		this.rovepos = 0;
		Arrays.fill(workArea, 0f);
	}

	public void process(float[] in) {
		float ldelay = lastdelay;
		float fb = feedback; // volatile read
		final float[] work = workArea;
		final int rnlen = work.length; // local
		int pos = rovepos;

		final float target = targetSamples; // volatile read snapshot
		final float den = DENORM_THRESHOLD;
		final int m = mask;

		float r, s, a, b, o;
		int ri;
		float scratch;

		for (int i = 0; i < N_FRAMES; i++) {
			// exponential smoothing towards target (per-sample)
			ldelay += (target - ldelay) * smoothAlpha;

			// read index (pos - delay) with small safety offset for interpolation
			r = pos - (ldelay + 2f) + rnlen;
			ri = (int) r;
			s = r - ri;

			// fast wrap using power-of-two mask
			int idxA = ri & m;
			int idxB = (idxA + 1) & m;

			a = work[idxA];
			b = work[idxB];
			o = a * (1 - s) + b * s;

			float inSample = in[i];

			// store feedback into buffer
			scratch = inSample + o;
			if (scratch > -den && scratch < den)
				scratch = 0f; // cheaper than Math.abs
			work[pos & m] = scratch * fb;

			// output (wet)
			in[i] = scratch;

			pos = (pos + 1) & m;
		}

		// publish state back (audio thread)
		rovepos = pos;
		lastdelay = ldelay;
	}

	private static int nextPow2(int v) {
		if (v <= 1)
			return 1;
		int highest = Integer.highestOneBit(v - 1);
		return highest << 1;
	}

}