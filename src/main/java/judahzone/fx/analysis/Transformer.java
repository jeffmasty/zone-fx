package judahzone.fx.analysis;

import static judahzone.util.WavConstants.AMPLITUDES;
import static judahzone.util.WavConstants.FFT_SIZE;
import static judahzone.util.WavConstants.TRANSFORM;

import java.util.function.Consumer;

import be.tarsos.dsp.util.fft.HammingWindow;
import judahzone.api.Transform;
import judahzone.util.AudioMetrics;
import judahzone.util.FFZ;

/** FFTEffect: RMS + FFT (forward + modulus) and produces a Transform to listener. */
public class Transformer extends Analysis<Transform> {

	// enum Settings {Window, Channel, Factor}

	final FFZ fft = new FFZ(FFT_SIZE, new HammingWindow());
	int channel = 0; // TODO L/R/MID/SIDE

	private final float[] transformBuffer = new float[TRANSFORM];

    public Transformer(Consumer<Transform> listener) {
    	super(listener, FFT_SIZE);
    }

    @Override
    public Transform analyze(float[] left, float[] right) {
    	float[] dat = channel == 0 ? left : right;

        // FFT time
        System.arraycopy(dat, 0, transformBuffer, 0, FFT_SIZE);
	    fft.forwardTransform(transformBuffer);
	    float[] amplitudes = new float[AMPLITUDES];
	    fft.modulus(transformBuffer, amplitudes);

        return new Transform(amplitudes, AudioMetrics.analyze(dat));
    }

}