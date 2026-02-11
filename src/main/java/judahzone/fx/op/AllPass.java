package judahzone.fx.op;

import judahzone.util.Constants;
import judahzone.util.Filters;

/**
 * AllPass biquad filter with mono and stereo support, RT-safe coefficient
 * smoothing. Suitable for Freeverb replacement (stereo) and DrumOsc integration
 * (mono).
 */
public class AllPass implements Filters {

	private static final int SR = Constants.sampleRate();
	private static final int N_FRAMES = Constants.bufSize();
	private static final float INV = 1.0f / N_FRAMES;

	private float frequency;
	private float q = 1.0f;

	/* biquad coefficients [b0, b1, b2, a0, a1, a2] */
	private float a0, a1, a2, b0, b1, b2;
	private float lastA0, lastA1, lastA2, lastB0, lastB1, lastB2;
	private boolean coeffDirty = true;
	private boolean haveLastCoeffs = false;

	/* mono filter state */
	private float xn1 = 0, xn2 = 0, yn1 = 0, yn2 = 0;
	/* stereo filter state (L/R independent) */
	private float xn1L = 0, xn2L = 0, yn1L = 0, yn2L = 0;
	private float xn1R = 0, xn2R = 0, yn1R = 0, yn2R = 0;

	public AllPass(float frequency) {
		this(frequency, 1.5f);
	}

	public AllPass(float frequency, float q) {
		this.frequency = frequency;
		this.q = q;
		computeCoefficients();
	}

	/**
	 * Compute AllPass biquad coefficients using FilterCoefficients.compute().
	 * Non-allocating.
	 */
	private void computeCoefficients() {
		Filters.Coeffs coeffs = Filters.computeAllPass(frequency, SR, q);
		coeffs.normalize();

		a0 = coeffs.a0;
		a1 = coeffs.a1;
		a2 = coeffs.a2;
		b0 = coeffs.b0;
		b1 = coeffs.b1;
		b2 = coeffs.b2;

		coeffDirty = true;
	}

	public void setFrequency(float freq) {
		this.frequency = freq;
		computeCoefficients();
	}

	public void setQ(float q) {
		this.q = q;
		computeCoefficients();
	}

	public float getFrequency() {
		return frequency;
	}

	public float getQ() {
		return q;
	}

	/** Process mono buffer in-place with coefficient smoothing. */
	public void process(float[] mono) {
		// initialize last coeffs if needed
		if (!haveLastCoeffs) {
			lastA0 = a0;
			lastA1 = a1;
			lastA2 = a2;
			lastB0 = b0;
			lastB1 = b1;
			lastB2 = b2;
			haveLastCoeffs = true;
		}
		if (coeffDirty)
			coeffDirty = false;

		// Fast path: coefficients identical -> simpler inner loop with locals
		if (lastA0 == a0 && lastA1 == a1 && lastA2 == a2 && lastB0 == b0 && lastB1 == b1 && lastB2 == b2) {

			final float lb0 = b0, lb1 = b1, lb2 = b2;
			final float la1 = a1, la2 = a2;

			// local state copies
			float lx1 = xn1, lx2 = xn2, ly1 = yn1, ly2 = yn2;

			for (int i = 0; i < N_FRAMES; i++) {
				float x = mono[i];
				float y = lb0 * x + lb1 * lx1 + lb2 * lx2 - la1 * ly1 - la2 * ly2;
				if (y > -DENORM && y < DENORM)
					y = 0f;
				mono[i] = y;
				lx2 = lx1;
				lx1 = x;
				ly2 = ly1;
				ly1 = y;
			}

			// write back state
			xn1 = lx1;
			xn2 = lx2;
			yn1 = ly1;
			yn2 = ly2;

		} else {
			// coefficient interpolation path
			float curA1 = lastA1, curA2 = lastA2;
			float curB0 = lastB0, curB1 = lastB1, curB2 = lastB2;

			final float dA1 = (a1 - lastA1) * INV;
			final float dA2 = (a2 - lastA2) * INV;
			final float dB0 = (b0 - lastB0) * INV;
			final float dB1 = (b1 - lastB1) * INV;
			final float dB2 = (b2 - lastB2) * INV;

			// local state copies
			float lx1 = xn1, lx2 = xn2, ly1 = yn1, ly2 = yn2;

			for (int i = 0; i < N_FRAMES; i++) {
				curA1 += dA1;
				curA2 += dA2;
				curB0 += dB0;
				curB1 += dB1;
				curB2 += dB2;

				float x = mono[i];
				// curA0 is expected to be 1.0 after normalization, but keep same form
				float y = curB0 * x + curB1 * lx1 + curB2 * lx2 - curA1 * ly1 - curA2 * ly2;
				if (y > -DENORM && y < DENORM)
					y = 0f;
				mono[i] = y;
				lx2 = lx1;
				lx1 = x;
				ly2 = ly1;
				ly1 = y;
			}

			// commit local states and last coefficients
			xn1 = lx1;
			xn2 = lx2;
			yn1 = ly1;
			yn2 = ly2;

			lastA0 = a0;
			lastA1 = a1;
			lastA2 = a2;
			lastB0 = b0;
			lastB1 = b1;
			lastB2 = b2;
		}
	}

	/**
	 * Process stereo buffers in-place with independent L/R states (Freeverb-style).
	 */
	public void processStereo(float[] left, float[] right) {
		if (right == null) {
			process(left); // fallback to mono if right is null
			return;
		}

		if (!haveLastCoeffs) {
			lastA0 = a0;
			lastA1 = a1;
			lastA2 = a2;
			lastB0 = b0;
			lastB1 = b1;
			lastB2 = b2;
			haveLastCoeffs = true;
		}
		if (coeffDirty)
			coeffDirty = false;

		if (lastA0 == a0 && lastA1 == a1 && lastA2 == a2 && lastB0 == b0 && lastB1 == b1 && lastB2 == b2) {

			final float lb0 = b0, lb1 = b1, lb2 = b2;
			final float la1 = a1, la2 = a2;

			// local L/R state copies
			float lx1 = xn1L, lx2 = xn2L, ly1 = yn1L, ly2 = yn2L;
			float rx1 = xn1R, rx2 = xn2R, ry1 = yn1R, ry2 = yn2R;

			for (int i = 0; i < N_FRAMES; i++) {
				float xL = left[i];
				float yL = lb0 * xL + lb1 * lx1 + lb2 * lx2 - la1 * ly1 - la2 * ly2;
				if (yL > -DENORM && yL < DENORM)
					yL = 0f;
				left[i] = yL;
				lx2 = lx1;
				lx1 = xL;
				ly2 = ly1;
				ly1 = yL;

				float xR = right[i];
				float yR = lb0 * xR + lb1 * rx1 + lb2 * rx2 - la1 * ry1 - la2 * ry2;
				if (yR > -DENORM && yR < DENORM)
					yR = 0f;
				right[i] = yR;
				rx2 = rx1;
				rx1 = xR;
				ry2 = ry1;
				ry1 = yR;
			}

			// write back states
			xn1L = lx1;
			xn2L = lx2;
			yn1L = ly1;
			yn2L = ly2;
			xn1R = rx1;
			xn2R = rx2;
			yn1R = ry1;
			yn2R = ry2;

		} else {
			// interpolation path
			float curA1 = lastA1, curA2 = lastA2;
			float curB0 = lastB0, curB1 = lastB1, curB2 = lastB2;

			final float dA1 = (a1 - lastA1) * INV;
			final float dA2 = (a2 - lastA2) * INV;
			final float dB0 = (b0 - lastB0) * INV;
			final float dB1 = (b1 - lastB1) * INV;
			final float dB2 = (b2 - lastB2) * INV;

			// local L/R state copies
			float lx1 = xn1L, lx2 = xn2L, ly1 = yn1L, ly2 = yn2L;
			float rx1 = xn1R, rx2 = xn2R, ry1 = yn1R, ry2 = yn2R;

			for (int i = 0; i < N_FRAMES; i++) {
				curA1 += dA1;
				curA2 += dA2;
				curB0 += dB0;
				curB1 += dB1;
				curB2 += dB2;

				float xL = left[i];
				float yL = curB0 * xL + curB1 * lx1 + curB2 * lx2 - curA1 * ly1 - curA2 * ly2;
				if (yL > -DENORM && yL < DENORM)
					yL = 0f;
				left[i] = yL;
				lx2 = lx1;
				lx1 = xL;
				ly2 = ly1;
				ly1 = yL;

				float xR = right[i];
				float yR = curB0 * xR + curB1 * rx1 + curB2 * rx2 - curA1 * ry1 - curA2 * ry2;
				if (yR > -DENORM && yR < DENORM)
					yR = 0f;
				right[i] = yR;
				rx2 = rx1;
				rx1 = xR;
				ry2 = ry1;
				ry1 = yR;
			}

			// commit states and last coeffs
			xn1L = lx1;
			xn2L = lx2;
			yn1L = ly1;
			yn2L = ly2;
			xn1R = rx1;
			xn2R = rx2;
			yn1R = ry1;
			yn2R = ry2;

			lastA0 = a0;
			lastA1 = a1;
			lastA2 = a2;
			lastB0 = b0;
			lastB1 = b1;
			lastB2 = b2;
		}
	}

	/** Reset filter state (useful for retriggering or parameter changes). */
	public void reset() {
		xn1 = xn2 = yn1 = yn2 = 0;
		xn1L = xn2L = yn1L = yn2L = 0;
		xn1R = xn2R = yn1R = yn2R = 0;
	}

}