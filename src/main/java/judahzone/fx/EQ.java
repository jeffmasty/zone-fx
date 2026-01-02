// language: java
package judahzone.fx;

import java.security.InvalidParameterException;
import java.util.ArrayList;

import judahzone.api.FX;
import judahzone.util.Constants;
import lombok.Getter;

public class EQ implements FX.RTFX {

    public static enum Settings { Bass, Mid, High, LoHz, Q, HiHz }
    public static enum EqBand { Bass, Mid, High }
    public static enum Properties { dB, Hz, Width }

    public static final int MIN_HZ = Filter.MIN;
    public static final int MAX_HZ = Filter.MAX;
    public static final int MID_HZ = (int) Constants.logarithmic(50, MIN_HZ, MAX_HZ);
    private static final float HI_MIN = MID_HZ * 2;
    private static final float LOW_MAX = MID_HZ / 2f;

    public static record Frequencies(int low, int mid, int high) {}
    public static Frequencies DEFAULT = new Frequencies(90, MID_HZ, 5000);

    private static final int BASS = 0;
    private static final int MID = 1;
    private static final int HIGH = 2;
    private static final float MIN_WIDTH = 0.5f;
    private static final float MAX_WIDTH = 5f;
    private static final float RANGE = MAX_WIDTH - MIN_WIDTH;

    @Getter
    private final String name = EQ.class.getSimpleName();
    @Getter
    private final int paramCount = Settings.values().length;

    private final ArrayList<StereoBiquad> stereo = new ArrayList<>();

    public EQ() {
        this(DEFAULT);
    }

    public EQ(Frequencies hz) {
        stereo.add(new StereoBiquad(hz.low));
        stereo.add(new StereoBiquad(hz.mid));
        stereo.add(new StereoBiquad(hz.high));
    }

    private void update(StereoBiquad filter, Properties param, float value) {
        switch (param) {
            case dB -> filter.gain_db = value;
            case Hz -> filter.frequency = value;
            case Width -> filter.bandwidth = value;
        }
        filter.coefficients();
    }

    private void update(EqBand band, Properties param, float value) {
        update(stereo.get(band.ordinal()), param, value);
    }

    @Override
    public int get(int idx) {
        if (idx < Settings.LoHz.ordinal()) {
            float nonNormal = stereo.get(idx).gain_db;
            return Math.round(nonNormal * 2 + 50);
        }
        if (idx == Settings.LoHz.ordinal())
            return Constants.reverseLog(stereo.get(BASS).frequency, MIN_HZ, LOW_MAX);
        if (idx == Settings.Q.ordinal())
            return (int) ((getWidth() - MIN_WIDTH) / RANGE * 100);
        if (idx == Settings.HiHz.ordinal())
            return Constants.reverseLog(stereo.get(HIGH).frequency, HI_MIN, MAX_HZ);
        throw new InvalidParameterException("EQ param " + idx);
    }

    @Override
    public void set(int idx, int val) {
        switch (idx) {
            case BASS, MID, HIGH -> {
                EqBand band = EqBand.values()[idx];
                eqGain(band, val);
            }
            case 3 ->
                update(EqBand.Bass, Properties.Hz,
                        Constants.logarithmic(val, MIN_HZ, LOW_MAX));
            case 4 -> {
                float width = MIN_WIDTH + (val * 0.01f * RANGE);
                for (StereoBiquad b : stereo)
                    update(b, Properties.Width, width);
            }
            case 5 ->
                update(EqBand.High, Properties.Hz,
                        Constants.logarithmic(val, HI_MIN, MAX_HZ));
            default ->
                throw new InvalidParameterException("EQ param " + idx);
        }
    }

    public void eqGain(EqBand eqBand, int val) {
        update(eqBand, Properties.dB, StereoBiquad.gainDb(val));
    }

    public float getGain(EqBand band) {
        return stereo.get(band.ordinal()).gain_db;
    }

    @Override
    public void process(float[] left, float[] right) {
        for (StereoBiquad filter : stereo) {
            filter.process(left, right);
        }
    }

    public float getWidth() {
        return stereo.get(MID).bandwidth;
    }
}
