// language: java
package judahzone.fx;

import static judahzone.util.WavConstants.FFT_SIZE;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import be.tarsos.dsp.util.fft.FFT;
import judahzone.api.FX;
import judahzone.api.IRProvider;
import judahzone.util.RTLogger;
import lombok.Getter;

public abstract class Convolution implements FX {

    public enum Settings { Cabinet, Wet }

    private static List<String> names = settingNames();

    private static List<String> settingNames() {
        ArrayList<String> build = new ArrayList<>();
        for (Settings s : Settings.values()) {
            build.add(s.name());
        }
        return Collections.unmodifiableList(build);
    }

    protected static IRProvider db;
    public static void setIRDB(IRProvider provider) { db = provider; }

    @Getter
    protected final String name = Convolution.class.getSimpleName();
    @Getter
    protected final int paramCount = Settings.values().length;

    @Override
    public List<String> getSettingNames() {
        return names;
    }

    // ======================================================================
    /** Wrapper around 2 Mono Convolvers */
    public static class Stereo extends Convolution implements FX.RTFX {

        private final Mono leftIR = new Mono();
        private final Mono rightIR = new Mono();

        @Override
        public void set(int idx, int value) {
            leftIR.set(idx, value);
            rightIR.set(idx, value);
        }

        @Override
        public void process(float[] left, float[] right) {
            if (left != null) {
                leftIR.process(left);
            }
            if (right != null) {
                rightIR.process(right);
            }
        }

        @Override
        public int get(int idx) {
            return leftIR.get(idx);
        }
    }

    // ======================================================================
    /** MONO: Convolute a selected IR against live audio */
    public static class Mono extends Convolution {

        protected final FFT fft = new FFT(FFT_SIZE);
        protected final FFT ifft = new FFT(FFT_SIZE);

        protected final int overlapSize = FFT_SIZE - N_FRAMES;

        protected float[] irFreq = new float[FFT_SIZE * 2];
        protected float wet = 0.9f;
        protected int cabinet = -1;

        protected final float[] fftInOut = new float[FFT_SIZE * 2];
        protected final float[] overlap = new float[overlapSize];
        protected final float[] work0 = new float[N_FRAMES];
        protected final float[] work1 = new float[N_FRAMES];

        @Override
        public void reset() {
            Arrays.fill(overlap, 0f);
        }

        @Override
        public void set(int idx, int value) {
            if (idx == Settings.Cabinet.ordinal()) {
                if (db == null) {
                    RTLogger.warn(this, "No IRDB set");
                    return;
                }
                if (db.size() == 0) {
                    RTLogger.warn(this, "No cabinets loaded");
                    return;
                }
                if (value < 0 || value >= db.size()) {
                    throw new InvalidParameterException("Cabinet index out of range: " + value);
                }
                cabinet = value;
                irFreq = db.get(cabinet).irFreq();
                reset();
                return;
            }

            if (idx == Settings.Wet.ordinal()) {
                wet = value * 0.01f;
                if (wet > 1) wet = 1;
                if (wet < 0) wet = 0;
                return;
            }
            throw new InvalidParameterException("Unknown param index: " + idx);
        }

        @Override
        public void activate() {
            if (cabinet < 0) {
                if (db != null) {
                    set(Settings.Cabinet.ordinal(), 0);
                }
            }
        }

        @Override
        public int get(int idx) {
            if (idx == Settings.Cabinet.ordinal()) {
                return cabinet;
            }
            if (idx == Settings.Wet.ordinal()) {
                return Math.round(wet * 100f);
            }
            throw new InvalidParameterException("Unknown param index: " + idx);
        }

        /** Convolve Add and make stereo (caller supplies mono and stereo arrays) */
        public void monoToStereo(float[] mono, float[] stereo) {
        	if (wet <= 0f) {
                int len = Math.min(N_FRAMES, Math.min(mono.length, stereo.length));
                System.arraycopy(mono, 0, stereo, 0, len);
                return;
            }
            float dryGain = 1.0f - wet;
            float wetGain = wet;

            System.arraycopy(mono, 0, work0, 0, N_FRAMES);

            Arrays.fill(fftInOut, 0f);
            System.arraycopy(overlap, 0, fftInOut, 0, overlapSize);
            System.arraycopy(work0, 0, fftInOut, overlapSize, N_FRAMES);

            System.arraycopy(fftInOut, N_FRAMES, overlap, 0, overlapSize);

            fft.forwardTransform(fftInOut);

            for (int k = 0, idx = 0; k < FFT_SIZE; k++, idx += 2) {
                float a = fftInOut[idx];
                float b = fftInOut[idx + 1];
                float c = irFreq[idx];
                float d = irFreq[idx + 1];
                float real = a * c - b * d;
                float imag = a * d + b * c;
                fftInOut[idx] = real;
                fftInOut[idx + 1] = imag;
            }

            ifft.backwardsTransform(fftInOut);

            for (int i = 0; i < N_FRAMES; i++) {
                float proc = fftInOut[overlapSize + i];
                float in = work0[i];
                float mixed = dryGain * in + wetGain * proc;
                work1[i] = mixed;
            }

            System.arraycopy(work1, 0, mono, 0, N_FRAMES);
            if (stereo != null) {
                System.arraycopy(work1, 0, stereo, 0, N_FRAMES);
            }
        }

        /** Realtime mono convolve-add */
        public void process(float[] mono) {
            final float dryGain = 1.0f - wet;
            final float wetGain = wet;

            System.arraycopy(mono, 0, work0, 0, N_FRAMES);

            Arrays.fill(fftInOut, 0f);
            System.arraycopy(overlap, 0, fftInOut, 0, overlapSize);
            System.arraycopy(work0, 0, fftInOut, overlapSize, N_FRAMES);

            System.arraycopy(fftInOut, N_FRAMES, overlap, 0, overlapSize);

            fft.forwardTransform(fftInOut);

            for (int k = 0, idx = 0; k < FFT_SIZE; k++, idx += 2) {
                float a = fftInOut[idx];
                float b = fftInOut[idx + 1];
                float c = irFreq[idx];
                float d = irFreq[idx + 1];
                float real = a * c - b * d;
                float imag = a * d + b * c;
                fftInOut[idx] = real;
                fftInOut[idx + 1] = imag;
            }

            ifft.backwardsTransform(fftInOut);

            for (int i = 0; i < N_FRAMES; i++) {
                float proc = fftInOut[overlapSize + i];
                float in = work0[i];
                work1[i] = dryGain * in + wetGain * proc;
            }

            System.arraycopy(work1, 0, mono, 0, N_FRAMES);
        }

        @Override
        public void process(float[] left, float[] right) {
            // no-op base implementation for FX; actual use is via process(mono) or monoToStereo
        }
    }

    @Override
    public void process(float[] left, float[] right) {
        // base class no-op
    }
}
