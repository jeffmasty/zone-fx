package judahzone.filter;

import judahzone.util.Constants;
import judahzone.util.Filters;
import judahzone.util.Filters.BWQType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/* Stereo compute unit of an EQ and CutFilter */
public class StereoBiquad {

	@RequiredArgsConstructor @Getter
	public static enum FilterType {
		LowPass("HiCut"), HighPass("LoCut"), Peaking("EQ");
		final String display;
	}

	public static final float LOG_2 = 0.693147f;
	public static final float MAX_WIDTH = 5f;
	private static final int N_FRAMES = Constants.bufSize();
	private static final float I_FRAMES = 1f / N_FRAMES;
	private static final float SAMPLE_RATE = Constants.sampleRate();


	// public operator //
	public float frequency;
	public float bandwidth;
	public float gain_db = 0;
	public FilterType filter_type;

	protected final BWQType bwq_type = BWQType.BW;
	private final Biquad left, right;

	// current coefficients
	private float a0, a1, a2, b0, b1, b2;
	// previous coefficients for smoothing
	private float lastA0, lastA1, lastA2, lastB0, lastB1, lastB2;
	private boolean coeffDirty = true;
	private boolean haveLastCoeffs = false;

	public StereoBiquad(FilterType type, float frequency) { // Hi/Lo pass
		this(type, frequency, 2, 16f);
	}

	public StereoBiquad(float hz) {
		this(hz, 1.5f);
	}

	public StereoBiquad(float hz, float bandwidth) { // EQ
		this(FilterType.Peaking, hz, bandwidth, 0f);
	}

	public StereoBiquad(FilterType type, float frequency, float bandwidth, float gain) {
		this.frequency = frequency;
		this.filter_type = type;
		this.bandwidth = bandwidth;
		this.gain_db = gain;
		left = new Biquad();
		right = new Biquad();
		coefficients();
	}

	public void coefficients() {
		// Map local enums to FilterCoefficients enums
		Filters.FilterType fcType = switch (filter_type) {
			case LowPass -> Filters.FilterType.LowPass;
			case HighPass -> Filters.FilterType.HighPass;
			case Peaking -> Filters.FilterType.Peaking;
		};
		Filters.BWQType fcBWQ = switch (bwq_type) {
			case Q -> Filters.BWQType.Q;
			case BW -> Filters.BWQType.BW;
			case S -> Filters.BWQType.S;
		};

		// Compute coefficients and normalize for direct use in the processing code.
		Filters.Coeffs coeffs = Filters.compute(
				fcType, frequency, SAMPLE_RATE, bandwidth, gain_db, fcBWQ);
		coeffs.normalize();

		b0 = coeffs.b0;
		b1 = coeffs.b1;
		b2 = coeffs.b2;
		a0 = coeffs.a0;
		a1 = coeffs.a1;
		a2 = coeffs.a2;

		coeffDirty = true;
	}

	public static float gainDb(int val) {
		float result = Math.abs(50 - val) / 2f;
		if (val < 50)
			result *= -1;
		return result;
	}

	public void process(float[] l, float[] r) {
		// snapshot current coeffs into Biquads with smoothing across this block
		left.updateCoefficients();
		right.updateCoefficients();
		left.processBuffer(l);
		right.processBuffer(r);
	}

	private class Biquad {

		private float xn1, xn2, yn1, yn2 = 0;

		void updateCoefficients() {
			// If coefficients changed since last time, set up interpolation
			if (coeffDirty || !haveLastCoeffs) {
				// if we have previous coefficients, keep them for smoothing
				if (!haveLastCoeffs) {
					lastA0 = a0;
					lastA1 = a1;
					lastA2 = a2;
					lastB0 = b0;
					lastB1 = b1;
					lastB2 = b2;
					haveLastCoeffs = true;
				}
				coeffDirty = false;
			}
		}

		void processBuffer(float[] buff) {
			// If we don't have previous coefficients yet, just use current ones, no smoothing
			if (!haveLastCoeffs
					|| (lastA0 == a0 && lastA1 == a1 && lastA2 == a2 && lastB0 == b0 && lastB1 == b1 && lastB2 == b2)) {

				final float lb0 = b0;
				final float lb1 = b1;
				final float lb2 = b2;
				final float la0 = a0;
				final float la1 = a1;
				final float la2 = a2;
				final float ia0 = 1.0f / la0;

				for (int i = 0; i < N_FRAMES; i++) {
					float xn = buff[i];
					float yn = (lb0 * xn + lb1 * xn1 + lb2 * xn2 - la1 * yn1 - la2 * yn2) * ia0;
					if (Math.abs(yn) < 1.0E-8f)
						yn = 0f; // de-normalize
					buff[i] = yn;
					xn2 = xn1;
					xn1 = xn;
					yn2 = yn1;
					yn1 = yn;
				}

			} else {
				// Smoothly interpolate coefficients from lastA* / lastB* to a* / b* over this buffer
				float curA0 = lastA0;
				float curA1 = lastA1;
				float curA2 = lastA2;
				float curB0 = lastB0;
				float curB1 = lastB1;
				float curB2 = lastB2;

				final float dA0 = (a0 - lastA0) * I_FRAMES;
				final float dA1 = (a1 - lastA1) * I_FRAMES;
				final float dA2 = (a2 - lastA2) * I_FRAMES;
				final float dB0 = (b0 - lastB0) * I_FRAMES;
				final float dB1 = (b1 - lastB1) * I_FRAMES;
				final float dB2 = (b2 - lastB2) * I_FRAMES;
				final float ia0 = 1.0f / curA0;

				for (int i = 0; i < N_FRAMES; i++) {
					curA0 += dA0;
					curA1 += dA1;
					curA2 += dA2;
					curB0 += dB0;
					curB1 += dB1;
					curB2 += dB2;

					float xn = buff[i];
					float yn = (curB0 * xn + curB1 * xn1 + curB2 * xn2 - curA1 * yn1 - curA2 * yn2) * ia0;
					if (Math.abs(yn) < 1.0E-8f)
						yn = 0f;
					buff[i] = yn;
					xn2 = xn1;
					xn1 = xn;
					yn2 = yn1;
					yn1 = yn;
				}
			}

			// At the end of this block, current coeffs become "last" for the next one
			lastA0 = a0;
			lastA1 = a1;
			lastA2 = a2;
			lastB0 = b0;
			lastB1 = b1;
			lastB2 = b2;
		}
	}

}