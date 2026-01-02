// language: java
package judahzone.fx;

import java.security.InvalidParameterException;

import judahzone.api.FX;
import judahzone.util.Constants;
import lombok.Getter;

public class Filter implements FX.RTFX {

    public enum Settings { Type, Hz, Width, dB }

    public static final int MIN = 45;
    public static final int MAX = 13500;

    @Getter
    private final String name = Filter.class.getSimpleName();
    @Getter
    private final int paramCount = Settings.values().length;

    private final StereoBiquad filter;

    public Filter(boolean lowPass) {
        float hz = lowPass ? MAX : MIN;
        StereoBiquad.FilterType type = lowPass
                ? StereoBiquad.FilterType.LowPass
                : StereoBiquad.FilterType.HighPass;
        filter = new StereoBiquad(type, hz);
    }

    @Override
    public void set(int idx, int value) {
        if (idx == Settings.dB.ordinal())
            filter.gain_db = StereoBiquad.gainDb(value);
        else if (idx == Settings.Hz.ordinal())
            filter.frequency = Constants.logarithmic(value, MIN, MAX);
        else if (idx == Settings.Width.ordinal())
            filter.bandwidth = StereoBiquad.MAX_WIDTH * value * 0.01f;
        else if (idx == Settings.Type.ordinal()) {
            StereoBiquad.FilterType change =
                    value > 50 ? StereoBiquad.FilterType.HighPass : StereoBiquad.FilterType.LowPass;
            filter.filter_type = change;
        } else
            throw new InvalidParameterException("" + idx);
        filter.coefficients();
    }

    @Override
    public int get(int idx) {
        if (idx == Settings.dB.ordinal())
            return Math.round(filter.gain_db * 2 + 50);
        else if (idx == Settings.Hz.ordinal())
            return Constants.reverseLog(filter.frequency, MIN, MAX);
        else if (idx == Settings.Width.ordinal())
            return (int) (filter.bandwidth * 100 / StereoBiquad.MAX_WIDTH);
        else if (idx == Settings.Type.ordinal())
            return filter.filter_type == StereoBiquad.FilterType.HighPass ? 100 : 0;
        throw new InvalidParameterException("" + idx);
    }

    @Override
    public void process(float[] left, float[] right) {
        filter.process(left, right);
    }
}
