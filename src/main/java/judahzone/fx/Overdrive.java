package judahzone.fx;

import java.security.InvalidParameterException;

import judahzone.api.FX;
import judahzone.util.Constants;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** See: references in original. Converted to FX.RTFX and float[] API. */
public final class Overdrive implements FX.RTFX {
    static final float MIN_DRIVE = 0.1f;
    static final float MAX_DRIVE = 0.9f;

    public enum Settings { Drive, Clipping, Algo }

    @RequiredArgsConstructor
    public enum Algo {
        SOFT(1.15f),    // x / (1 + |x|), hardness depends on drive
        HARD(1.15f),    // SOFT++, more hardness with drive
        BLUE(0.23f),    // blues-style asymmetric tanh pair
        SMITH(0.34f),   // atan (legacy flavour)
        ZONE(0.37f),    // x / (abs(x) + k), k depends on drive
        MESA(0.55f),    // exponential boogie-style
        TUBE(1f),       // S-curve tube screamer
        TWIN(0.15f),    // AMP1-style tube-ish curve
        FUZZ(0.23f),    // AMP2-style, hardness follows drive
        FOLD(0.7f);     // foldback glitch for synths

        private final float makeupGain;
    }

    @FunctionalInterface
    private interface Waveshaper {
        float apply(float x);
    }

    @Getter private final String name = Overdrive.class.getSimpleName();
    @Getter private final int paramCount = Settings.values().length;

    private float drive = 0.28f;
    private int clipping = 0;
    private float diode = 2f;
    private Algo algo = Algo.SMITH;
    private Waveshaper shaper = x -> x; // rebuild in activate()
    private static final float SAFETY_OUTPUT_CLAMP = 0.999f;

    @Override public int get(int idx) {
        return switch (idx) {
            case 0 -> {
                if (drive < 0.00002f)
                    yield 0;
                if (drive < 0.021f)
                    yield 1;
                yield Constants.reverseLog(drive, MIN_DRIVE, MAX_DRIVE);
            }
            case 1 -> clipping;
            case 2 -> algo.ordinal();
            default -> throw new InvalidParameterException("Setting " + idx);
        };
    }

    @Override public void set(int idx, int value) {
        switch (idx) {
            case 0 -> {
                if (value == 0)
                    drive = 0.00001f;
                else if (value == 1)
                    drive = 0.02f;
                else
                    drive = Constants.logarithmic(value, MIN_DRIVE, MAX_DRIVE);
                activate();
            }
            case 1 -> {
                clipping = value;
                diode = 1f + (3f - 2.7f * (0.01f * clipping));
            }
            case 2 -> {
                Algo[] algos = Algo.values();
                int clamped = Math.max(0, Math.min(value, algos.length - 1));
                algo = algos[clamped];
                activate();
            }
            default -> throw new InvalidParameterException("Setting " + idx + " (=" + value + ")");
        }
    }

    @Override
    public void activate() {
        final float driveGain = 1f + drive * 29f;

        switch (algo) {
            case SMITH -> {
                double preMulD = drive * 99 + 1;
                double postMulD = 1 / (Math.log(preMulD * 2) * 1.0 / Math.log(2));
                final float preMul = (float) preMulD;
                final float postMul = (float) postMulD;
                shaper = x -> (float) (Math.atan(x * preMul) * postMul);
            }
            case BLUE -> {
                final float driveShaped = (float) Math.pow(drive, 1.2f);
                final float posGain = 3f + 12f * driveShaped;
                final float negGain = 1.5f + 6f * driveShaped;
                final float posLevel = 0.9f + 0.45f * driveShaped;
                final float negLevel = 0.7f + 0.35f * driveShaped;

                final float posGainCapped = Math.min(posGain, 14f);
                final float negGainCapped = Math.min(negGain, 8f);

                shaper = x -> {
                    if (x >= 0f) {
                        return (float) (Math.tanh(posGainCapped * x) * posLevel);
                    } else {
                        return (float) (Math.tanh(negGainCapped * x) * negLevel);
                    }};
            }
            case TWIN -> {
                final float kDrive = 0.9f + 0.3f * drive;
                final float globalScale = 0.7f * (0.7f + 0.4f * drive);
                shaper = x -> {
                    float ax = Math.abs(x);
                    float denom1 = ax + kDrive;
                    if (denom1 == 0f) return 0f;
                    float num = (x / denom1) * 1.5f * driveGain;
                    float denom2 = x * x + (-1.0f) * ax + 1.0f;
                    if (denom2 == 0f) return 0f;
                    return (num / denom2) * globalScale;
                }; }
            case ZONE -> {
                final float baseK = 2.0f;
                final float k = baseK - 0.9f * drive;
                shaper = x -> {
                    float ax = Math.abs(x);
                    float denom = ax + k;
                    if (denom == 0f) return 0f;
                    return (x / denom) * driveGain * 0.5f;
                }; }
            case FUZZ -> {
                final float kDrive = 0.9f + 0.4f * drive;
                final float globalScale = 0.6f * (0.8f + 0.35f * drive);
                shaper = x -> {
                    float ax = Math.abs(x);
                    if (ax == 0f) return 0f;
                    float num = x * (ax + kDrive) * 1.5f * driveGain;
                    float denom = x * x + 0.3f * (0.1f / ax) + 1.0f;
                    if (denom == 0f) return 0f;
                    return (num / denom) * globalScale;
                }; }
            case SOFT -> {
                final float hardness = 0.7f + 2.3f * drive;
                shaper = x -> {
                    float ax = Math.abs(x);
                    float denom = 1.0f + ax * hardness;
                    if (denom == 0f) return 0f;
                    return (x / denom) * (0.8f + 0.6f * drive);
                }; }
            case HARD -> {
                final float driveShaped = (float) Math.pow(drive, 1.3);
                final float hardness    = 1.0f + 9.0f * driveShaped;
                final float outGain     = 0.7f + 1.0f * driveShaped;
                shaper = x -> {
                    float ax = Math.abs(x);
                    float denom = 1.0f + ax * hardness;
                    if (denom == 0f) return 0f;
                    return (x / denom) * outGain;
                }; }
            case MESA -> {
                final float a = 1f + 9f * drive;
                final float norm = (float) (1.0 - Math.exp(-a));
                final float driveMix = 0.4f + 0.6f * drive;
                shaper = x -> {
                    float v = (float) ((1.0 - Math.exp(-a * x)) / norm);
                    return (1f - driveMix) * x + driveMix * v;
                }; }
            case TUBE -> {
                final float shapeGain = 0.7f + 0.9f * drive;
                shaper = x -> {
                    float ax = Math.abs(x);
                    float denom = 2f + ax;
                    if (denom == 0f) return 0f;
                    float s = 3f * x / denom;
                    float mix = 0.5f + 0.4f * drive;
                    float wet = s * shapeGain;
                    return (1f - mix) * x + mix * wet;
                }; }
            case FOLD -> {
                final float foldGain = 1f + 4f * drive;
                final float k = 0.4f;
                final float dryMix = 0.5f - 0.3f * drive;
                final float wetMix = 1f - dryMix;
                final float range = 2f * k;
                final float limit = SAFETY_OUTPUT_CLAMP;

                shaper = x -> {
                    float v = x * foldGain;
                    float t = (v + k) % range;
                    if (t < 0f) t += range;
                    float folded = t - k;
                    float y = dryMix * x + wetMix * folded;
                    y = Math.max(-limit, Math.min(limit, y));
                    return y;
                }; }
            default -> {
                shaper = x -> x;
            }
        }
    }

    @Override public void process(float[] left, float[] right) {
        if (left != null) process(left, true);
        if (right != null) process(right, false);
    }

    /** Process 1 channel in-place using array indexing */
    public void process(float[] buf, boolean isLeft) {
        if (buf == null) return;
        final Waveshaper waveShaper = shaper;
        final float algoGain = algo.makeupGain;
        final int len = buf.length;

        if (clipping == 0) {
            for (int i = 0; i < len; i++) {
                float y = waveShaper.apply(buf[i]) * algoGain;
                y = Math.max(-SAFETY_OUTPUT_CLAMP, Math.min(SAFETY_OUTPUT_CLAMP, y));
                buf[i] = y;
            }
        } else {
            final float localDiode = this.diode;
            for (int i = 0; i < len; i++) {
                float x = buf[i];
                float y = waveShaper.apply(x) * algoGain;
                float max = localDiode * x;
                if (Math.abs(y) > Math.abs(max))
                    y = max;
                buf[i] = y;
            }
        }
    }
}
