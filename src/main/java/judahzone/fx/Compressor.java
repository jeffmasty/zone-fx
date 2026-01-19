package judahzone.fx;

import static java.lang.Math.abs;
import static java.lang.Math.log;

import java.security.InvalidParameterException;

import judahzone.api.FX;
import lombok.Getter;

public class Compressor implements FX.RTFX {

    public static enum Settings {
        Threshold, Ratio, Boost, Attack, Release, Knee
    }

    static final float LOG_10 = 2.302585f;
    static final float LOG_2 = 0.693147f;
    static final float MIN_GAIN = 0.00001f;
    private static final float MAX_REDUCTION_DB = 30f;

    @Getter
    private float lastReductionDb = 0f;

    private final double cSAMPLE_RATE = 1.0 / SAMPLE_RATE;

    private float lvolume = 0f;
    private int tratio = 4;
    private int toutput = -10;
    private int tknee = 30;
    private float boost_old = 1.0f;
    private double ratio = 1.0;
    private float kpct = 0.0f;

    private float thres_db = -24;
    private float att;
    private int attStash;
    private float rel;
    private int relStash;

    private double kratio;
    private float knee;
    private double coeff_kratio;
    private double coeff_ratio;
    private double coeff_knee;
    private double coeff_kk;
    private float thres_mx;
    private double makeup;
    private float makeuplin;
    private float outlevel;

    public Compressor() {
        reset();
    }

    @Override
    public void reset() {
        boost_old = 1.0f;
        setThreshold(-16);
        setRatio(7);
        setBoost(-14);
        setKnee(20);
        setRelease(90);
        set(Settings.Attack.ordinal(), get(Settings.Release.ordinal()));
    }

    public static float dB2rap(double dB) {
        return (float) (Math.exp((dB) * LOG_10 / 20.0f));
    }

    public static float rap2dB(float rap) {
        return (float) ((20 * log(rap) / LOG_10));
    }

    @Override
    public int getParamCount() {
        return Settings.values().length;
    }

    @Override
    public String getName() {
        return Compressor.class.getSimpleName();
    }

    public int getAttack() {
        return attStash;
    }

    public int getRelease() {
        return relStash;
    }

    public int getRatio() {
        return tratio;
    }

    public int getThreshold() {
        return Math.round(thres_db);
    }

    public void setRatio(int val) {
        tratio = val;
        ratio = tratio;
        compute();
    }

    public void setThreshold(int db) {
        thres_db = db;
        compute();
    }

    public void setAttack(int milliseconds) {
        attStash = milliseconds;
        att = (float) (cSAMPLE_RATE / ((attStash / 1000.0f) + cSAMPLE_RATE));
        compute();
    }

    public void setRelease(int milliseconds) {
        relStash = milliseconds;
        rel = (float) (cSAMPLE_RATE / ((relStash / 1000.0f) + cSAMPLE_RATE));
        compute();
    }

    public void setKnee(int knee) {
        tknee = knee;
        kpct = tknee / 100.1f;
        compute();
    }

    public void setBoost(int boost) {
        toutput = boost;
        compute();
    }

    @Override
    public int get(int idx) {
        if (idx == Settings.Threshold.ordinal())
            return (getThreshold() * -3) - 1;
        if (idx == Settings.Ratio.ordinal())
            return (getRatio() - 2) * 10;
        if (idx == Settings.Attack.ordinal())
            return getAttack() - 10;
        if (idx == Settings.Release.ordinal())
            return (int) ((getRelease() - 5) * 0.5f);
        if (idx == Settings.Boost.ordinal())
            return (toutput + 20) * 3;
        if (idx == Settings.Knee.ordinal())
            return tknee;
        throw new InvalidParameterException("idx: " + idx);
    }

    @Override
    public void set(int idx, int value) {
        if (idx == Settings.Threshold.ordinal())
            setThreshold((int) (value * -0.333f) - 1);
        else if (idx == Settings.Ratio.ordinal())
            setRatio(2 + (int) (value * 0.1f));
        else if (idx == Settings.Boost.ordinal())
            setBoost((int) Math.floor(value * 0.333334f - 20));
        else if (idx == Settings.Attack.ordinal())
            setAttack(value + 10);
        else if (idx == Settings.Release.ordinal())
            setRelease((value * 2) + 5);
        else if (idx == Settings.Knee.ordinal())
            setKnee(value);
        else
            throw new InvalidParameterException("Compressor set " + idx + "?  val: " + value);
    }

    private void compute() {
        kratio = Math.log(ratio) / LOG_2;
        knee = -kpct * thres_db;

        coeff_kratio = 1.0 / kratio;
        coeff_ratio = 1.0 / ratio;
        coeff_knee = 1.0 / knee;
        coeff_kk = knee * coeff_kratio;

        thres_mx = thres_db + knee;
        makeup = -thres_db - knee / kratio + thres_mx / ratio;
        makeuplin = dB2rap(makeup);
        outlevel = dB2rap(toutput) * makeuplin;
    }

    @Override
    public void process(float[] left, float[] right) {
        if (left != null) {
            processChannel(left);
        }
        if (right != null) {
            processChannel(right);
        }
    }

    void processChannel(float[] buf) {
        float val, ldelta, attl, rell, lvolume_db, gain_t, boost;
        double eratio;
        final float lvol = lvolume;
        final float outl = outlevel;
        final float threshold = thres_db;

        float minGain = 1.0f;

        final int n = N_FRAMES;
        for (int z = 0; z < n; z++) {
            val = buf[z];

            ldelta = abs(val);

            if (lvol < 0.9f) {
                attl = att;
                rell = rel;
            } else if (lvol < 1f) {
                attl = att + ((1f - att) * (lvol - 0.9f) * 10.0f);
                rell = rel / (1f + (lvol - 0.9f) * 9.0f);
            } else {
                attl = 1f;
                rell = rel * 0.1f;
            }

            float isRising = Math.max(0f, Math.signum(ldelta - lvolume));
            lvolume = isRising * (attl * ldelta + (1f - attl) * lvol) +
                      (1f - isRising) * (rell * ldelta + (1f - rell) * lvol);

            lvolume_db = rap2dB(lvolume);

            if (lvolume_db < threshold)
                boost = outl;
            else if (lvolume_db < thres_mx) {
                eratio = 1f + (kratio - 1f) * (lvolume_db - threshold) * coeff_knee;
                boost = outl * dB2rap(threshold + (lvolume_db - threshold) / eratio - lvolume_db);
            } else
                boost = outl * dB2rap(threshold + coeff_kk + (lvolume_db - thres_mx) * coeff_ratio - lvolume_db);

            boost = Math.max(boost, MIN_GAIN);

            gain_t = 0.4f * boost + 0.6f * boost_old;

            minGain = Math.min(minGain, gain_t);

            buf[z] = val * gain_t;
            boost_old = boost;
        }

        if (minGain >= 1.0f) {
            lastReductionDb = 0f;
        } else {
            lastReductionDb = Math.min(-rap2dB(minGain), MAX_REDUCTION_DB);
        }
    }
}
