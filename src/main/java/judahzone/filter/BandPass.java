package judahzone.filter;

import judahzone.prism.PrismRT;
import judahzone.util.Constants;
import judahzone.util.Filters;
import lombok.Getter;

/** Bandpass biquad filter with mono/stereo RT-safe coefficient smoothing. */
@PrismRT
public class BandPass implements Filters {

	private static final int SR = Constants.sampleRate();
	private static final int N_FRAMES = Constants.bufSize();
	private static final float INV = 1.0f / N_FRAMES;

	@Getter private float frequency;
	@Getter private float bandwidth = 1.0f; // or Q
	private BWQType bwqType = BWQType.Q;

	private float a0, a1, a2, b0, b1, b2;
	private float lastA0, lastA1, lastA2, lastB0, lastB1, lastB2;
	private boolean coeffDirty = true;
	private boolean haveLastCoeffs = false;

	private float xn1 = 0, xn2 = 0, yn1 = 0, yn2 = 0;
	private float xn1L = 0, xn2L = 0, yn1L = 0, yn2L = 0;
	private float xn1R = 0, xn2R = 0, yn1R = 0, yn2R = 0;

	public BandPass(float frequency, float bandwidth) {
		this(frequency, bandwidth, BWQType.Q);
	}

	public BandPass(float frequency, float bandwidth, BWQType bwqType) {
		this.frequency = frequency;
		this.bandwidth = bandwidth;
		this.bwqType = bwqType;
		computeCoefficients();
	}

	private void computeCoefficients() {
		Filters.Coeffs coeffs = Filters.compute(FilterType.BandPass, frequency, SR, bandwidth, 0.0f, bwqType);
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

	public void setBandwidth(float bw) {
		this.bandwidth = Math.max(MIN_BANDWIDTH, Math.min(bw, MAX_BANDWIDTH));
		computeCoefficients();
	}

	public void process(float[] mono) {
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

		// Fast path: identical coefficients
		if (lastA0 == a0 && lastA1 == a1 && lastA2 == a2 && lastB0 == b0 && lastB1 == b1 && lastB2 == b2) {

			final float lb0 = b0, lb1 = b1, lb2 = b2;
			final float la1 = a1, la2 = a2;

			float lx1 = xn1, lx2 = xn2, ly1 = yn1, ly2 = yn2;

			for (int i = 0; i < N_FRAMES; i++) {
				float x = mono[i];
				float y = lb0 * x + lb1 * lx1 + lb2 * lx2 - la1 * ly1 - la2 * ly2;
				mono[i] = y;
				lx2 = lx1;
				lx1 = x;
				ly2 = ly1;
				ly1 = y;
			}

			xn1 = lx1;
			xn2 = lx2;
			yn1 = ly1;
			yn2 = ly2;

		} else {
			// Interpolation path
			float curA1 = lastA1, curA2 = lastA2;
			float curB0 = lastB0, curB1 = lastB1, curB2 = lastB2;

			final float dA1 = (a1 - lastA1) * INV;
			final float dA2 = (a2 - lastA2) * INV;
			final float dB0 = (b0 - lastB0) * INV;
			final float dB1 = (b1 - lastB1) * INV;
			final float dB2 = (b2 - lastB2) * INV;

			float lx1 = xn1, lx2 = xn2, ly1 = yn1, ly2 = yn2;

			for (int i = 0; i < N_FRAMES; i++) {
				curA1 += dA1;
				curA2 += dA2;
				curB0 += dB0;
				curB1 += dB1;
				curB2 += dB2;

				float x = mono[i];
				float y = curB0 * x + curB1 * lx1 + curB2 * lx2 - curA1 * ly1 - curA2 * ly2;
				mono[i] = y;
				lx2 = lx1;
				lx1 = x;
				ly2 = ly1;
				ly1 = y;
			}

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

		// sanitize small states once per block
		sanitizeMonoState();
	}

	public void processStereo(float[] left, float[] right) {
		if (right == null) {
			process(left);
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

			float lx1 = xn1L, lx2 = xn2L, ly1 = yn1L, ly2 = yn2L;
			float rx1 = xn1R, rx2 = xn2R, ry1 = yn1R, ry2 = yn2R;

			for (int i = 0; i < N_FRAMES; i++) {
				float xL = left[i];
				float yL = lb0 * xL + lb1 * lx1 + lb2 * lx2 - la1 * ly1 - la2 * ly2;
				left[i] = yL;
				lx2 = lx1;
				lx1 = xL;
				ly2 = ly1;
				ly1 = yL;

				float xR = right[i];
				float yR = lb0 * xR + lb1 * rx1 + lb2 * rx2 - la1 * ry1 - la2 * ry2;
				right[i] = yR;
				rx2 = rx1;
				rx1 = xR;
				ry2 = ry1;
				ry1 = yR;
			}

			xn1L = lx1;
			xn2L = lx2;
			yn1L = ly1;
			yn2L = ly2;
			xn1R = rx1;
			xn2R = rx2;
			yn1R = ry1;
			yn2R = ry2;

		} else {
			float curA1 = lastA1, curA2 = lastA2;
			float curB0 = lastB0, curB1 = lastB1, curB2 = lastB2;

			final float dA1 = (a1 - lastA1) * INV;
			final float dA2 = (a2 - lastA2) * INV;
			final float dB0 = (b0 - lastB0) * INV;
			final float dB1 = (b1 - lastB1) * INV;
			final float dB2 = (b2 - lastB2) * INV;

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
				left[i] = yL;
				lx2 = lx1;
				lx1 = xL;
				ly2 = ly1;
				ly1 = yL;

				float xR = right[i];
				float yR = curB0 * xR + curB1 * rx1 + curB2 * rx2 - curA1 * ry1 - curA2 * ry2;
				right[i] = yR;
				rx2 = rx1;
				rx1 = xR;
				ry2 = ry1;
				ry1 = yR;
			}

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

		// sanitize per-block
		sanitizeStereoState();
	}

	public void reset() {
		xn1 = xn2 = yn1 = yn2 = Filters.nudge(0f);
		xn1L = xn2L = yn1L = yn2L = Filters.nudge(0f);
		xn1R = xn2R = yn1R = yn2R = Filters.nudge(0f);
	}

	/* helpers */
	private void sanitizeMonoState() {
		if (Math.abs(xn1) < DENORM)
			xn1 = 0f;
		if (Math.abs(xn2) < DENORM)
			xn2 = 0f;
		if (Math.abs(yn1) < DENORM)
			yn1 = 0f;
		if (Math.abs(yn2) < DENORM)
			yn2 = 0f;
	}

	private void sanitizeStereoState() {
		if (Math.abs(xn1L) < DENORM)
			xn1L = 0f;
		if (Math.abs(xn2L) < DENORM)
			xn2L = 0f;
		if (Math.abs(yn1L) < DENORM)
			yn1L = 0f;
		if (Math.abs(yn2L) < DENORM)
			yn2L = 0f;

		if (Math.abs(xn1R) < DENORM)
			xn1R = 0f;
		if (Math.abs(xn2R) < DENORM)
			xn2R = 0f;
		if (Math.abs(yn1R) < DENORM)
			yn1R = 0f;
		if (Math.abs(yn2R) < DENORM)
			yn2R = 0f;
	}

}