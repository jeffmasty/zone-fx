package judahzone.fx.analysis;

import static judahzone.util.WavConstants.AMPLITUDES;
import static judahzone.util.WavConstants.FFT_SIZE;
import static judahzone.util.WavConstants.TRANSFORM;

import java.util.function.Consumer;

import be.tarsos.dsp.util.fft.HammingWindow;
import judahzone.api.Transform;
import judahzone.util.AudioMetrics;
import judahzone.util.FFZ;
import judahzone.util.Recording;

/** FFTEffect: RMS + FFT (forward + modulus) and produces a Transform to listener. */
public class Transformer extends Analysis<Transform> {

	// enum Settings {Window, Channel, Factor}

	final FFZ fft = new FFZ(FFT_SIZE, new HammingWindow());
	int channel = 0;

    public Transformer(Consumer<Transform> listener) {
    	super(listener, FFT_SIZE);
    }

    @Override protected Transform analyze(Recording rec) {
    	float[] dat = rec.getChannel(channel);

        // compute RMS on left
        var rms = AudioMetrics.analyze(dat);

        // prepare transform buffer for FFT (zeroed by new)
        float[] transformBuffer = new float[TRANSFORM];
        System.arraycopy(dat, 0, transformBuffer, 0, FFT_SIZE);

        // forward transform and extract amplitudes
        fft.forwardTransform(transformBuffer);
        float[] amplitudes = new float[AMPLITUDES];
        fft.modulus(transformBuffer, amplitudes);

        return new Transform(amplitudes, rms);
    }

}