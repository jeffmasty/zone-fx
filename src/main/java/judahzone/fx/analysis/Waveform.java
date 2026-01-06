package judahzone.fx.analysis;

import static judahzone.util.WavConstants.JACK_BUFFER;

import java.util.function.Consumer;

import judahzone.util.AudioMetrics;
import judahzone.util.AudioMetrics.RMS;

/**
 * Simple RMS waveform analyzer built on top of Analysis.
 * Emits an AudioMetrics.RMS to the listener every JACK_BUFFER worth of data.
 */
public class Waveform extends Analysis<RMS> {

    public Waveform(Consumer<RMS> listener) {
        super(listener, JACK_BUFFER);
    }

    public Waveform(Consumer<RMS> listener, int bufferSize) {
        super(listener, Math.max(8, bufferSize));
    }

    @Override
    public RMS analyze(float[] left, float[] right) {
        // mirror RMSWidget / AudioMetrics logic to produce a compact RMS object
        RMS leftR = analyzeChannel(left);
        RMS rightR = analyzeChannel(right);
        return leftR.rms() > rightR.rms() ? leftR : rightR;
    }

    private static RMS analyzeChannel(float[] channel) {
        float sumPositive = 0f;
        float sumNegative = 0f;
        int countPositive = 0;
        int countNegative = 0;
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;

        for (float v : channel) {
            if (v > 0f) {
                sumPositive += v;
                countPositive++;
            } else if (v < 0f) {
                sumNegative += v;
                countNegative++;
            }
            if (v < min) min = v;
            if (v > max) max = v;
        }

        float avgPositive = countPositive > 0 ? sumPositive / countPositive : 0f;
        float avgNegative = countNegative > 0 ? sumNegative / countNegative : 0f;
        float rms = AudioMetrics.rms(channel);
        float peak = hiLo(max, min);
        float amp = hiLo(avgPositive, avgNegative);
        return new RMS(rms, peak, amp);
    }

    private static float hiLo(float pos, float neg) {
        return pos > Math.abs(neg) ? pos : Math.abs(neg);
    }
}