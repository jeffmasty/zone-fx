package judahzone.fx.op;

import judahzone.util.Filters;

/**
 * Schroeder all-pass delay-line (one-pole/all-pass comb).
 * <p>Suitable for diffusion stages in reverbs and other delay-network processing.
 * The filter uses a fixed-size circular buffer (samples) and a feedback coefficient
 * which controls the amount of signal recirculation and decay length.
 * <p>
 * - size: delay buffer length in samples (must be >= 1).
 * - feedback: feedback coefficient clamped to [0.0, 0.999]. High values produce longer decay/tail.
 * <p>Callers should use {@link #reset()} when changing major routing so internal state is deterministic.
 */
	public final class Schroeder implements Filters {

	private static final float MAX_FEEDBACK = 0.999f;

	/** feedback amount (0..1, clamped) */
	private float feedback;
	/** delay buffer length in samples (values < 1 will be clamped to 1) */
	private final int size;
	private final float[] buffer;
	private int bufidx;

	public Schroeder(int size) {
		this(size, 0.5f);
	}

	public Schroeder(int size, float feedback) {
		this.size = Math.max(1, size);
		this.buffer = new float[this.size];
		setFeedback(feedback);
	}

	public void setFeedback(float feedback) {
		this.feedback = Math.max(0f, Math.min(MAX_FEEDBACK, feedback));
	}

	public float getFeedback() { return feedback; }

	public int getSize() { return size; }

	public void reset() {
		bufidx = 0;
		for (int i = 0; i < buffer.length; i++)
			buffer[i] = 0f;
	}

	public void process(float[] mono) {
		if (mono == null)
			return;
		processReplace(mono, mono);
	}

	public void processReplace(float[] inputs, float[] outputs) {
		if (inputs == null)
			return;
		float[] target = outputs == null ? inputs : outputs;
		int frames = Math.min(inputs.length, target.length);
		for (int i = 0; i < frames; i++) {
			float tapped = buffer[bufidx];
			float input = inputs[i];
			target[i] = -input + tapped;
			buffer[bufidx] = input + tapped * feedback;
			if (++bufidx >= size)
				bufidx = 0;
		}
	}

	public static int decorrelate(int sizeL, int i) {
		return Math.max(1, sizeL + (i % 2 == 0 ? 11 : -7)); // small allpassR decorrelation
	}

}
/*	1. Amplitude Response: Flat across all frequencies (unlike HighPass/LowPass that attenuate)
	2. Benefit for drums: No volume loss; pure character without EQ-style coloration
	3. Phase Response: Different frequencies get different phase delays (group delay varies with frequency)
	4. Creates subtle comb-filtering artifacts
	5. Produces harmonic shifting and metallic/resonant characteristics
	6. Analog-synth warmth from frequency-dependent time smearing
	7. Cascading Effect: Multiple stages (2+ AllPass in series) create stronger resonant peaks and notches
	8. metallic timbre, analog warmth, shimmer

| Kick | Warmer low-end, subtle sub harmonics, less "thump" becomse "thud" |
| Bongo/Conga | Resonant body coloration, richer sustain, more "wooden" |
| Clap | Tighter attack, subtle reverb-like diffusion, natural room feel |
| Snare | Crisp metallic edge enhanced, transient shimmer |
| Closed/Open Hat | Brighter shimmer, frequency-dependent whitening |
| Ride | Bell-like resonance, harmonic thickness |
| Stick | Click definition with subtle sustain tail |

*/