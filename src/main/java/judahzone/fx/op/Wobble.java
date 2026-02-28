package judahzone.fx.op;

import judahzone.api.OffOn;
import judahzone.prism.PrismRT;

/** Helper that computes transient + filter smoothing and wobble increment. */
@PrismRT
public final class Wobble implements OffOn {
	private static final float TWO_PI = (float) (2.0 * Math.PI);

	/** Frames in the transient (factored for oversampling). */
	public int transientFrames;
	/** 1.0 / transientFrames for per-sample envelope stepping. */
	public float invTransient;
	/** One-pole alpha for hi-cut smoothing (exp(-omega / sr)). */
	public float hiAlpha;
	/** One-pole alpha for mid-cut smoothing. */
	public float midAlpha;
	/** Increment for wobble LFO (phase increment per oversampled step). */
	public float wobbleInc;

	private final float isrOver;

	/** Construct with oversampled rates from DrumOsc (SR_OVER, ISR_OVER). */
	public Wobble(float isrOver) {
		this.isrOver = isrOver;
		reset();
	}

	/** Reset to safe defaults (useful on init/retrigger). */
	@Override
	public void reset() {
		transientFrames = 1;
		invTransient = 1.0f;
		hiAlpha = 0f;
		midAlpha = 0f;
		wobbleInc = 0f;
	}

	@Override
	public void trigger() {
		// for interface
	}

	/**
	 * Update all derived values in one call. - attackFramesFactored:
	 * env.getAttackSamples() * FACTOR (already factored for oversampling) -
	 * hiCutHz/midCutHz: target cutoff freqs in Hz - wobbleRateHz: wobble LFO rate
	 * in Hz
	 */
	public void update(int attackFramesFactored, float hiCutHz, float midCutHz, float wobbleRateHz) {
		transientFrames = Math.max(1, attackFramesFactored);
		invTransient = 1.0f / transientFrames;
		hiAlpha = (float) Math.exp(-TWO_PI * hiCutHz * isrOver);
		midAlpha = (float) Math.exp(-TWO_PI * midCutHz * isrOver);
		wobbleInc = wobbleRateHz * isrOver;
	}

}