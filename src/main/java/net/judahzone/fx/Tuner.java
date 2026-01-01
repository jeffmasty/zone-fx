package net.judahzone.fx;

import java.util.Objects;
import java.util.function.Consumer;

import be.tarsos.dsp.pitch.PitchDetectionResult;
import be.tarsos.dsp.pitch.PitchDetector;
import be.tarsos.dsp.pitch.PitchProcessor.PitchEstimationAlgorithm;
import judahzone.api.Key;
import judahzone.api.Note;
import judahzone.api.Tuning;
import judahzone.util.Constants;
import judahzone.util.RTLogger;
import judahzone.util.Recording;
import judahzone.util.WavConstants;

/**
 * Tuner implemented on top of CopyEffect: the base class manages snapshot,
 * executor, isActive/close semantics and error handling.
 *
 * Params mapping (via set/get):
 *  - idx 0 : probability threshold (0..100 integer mapped to 0.0..1.0)
 *  - idx 1 : algorithm index (PitchEstimationAlgorithm.ordinal())
 */
public class Tuner extends Analysis<Tuning> {

	public enum Settings { Probability, Algo };
	private volatile PitchDetector detector;
    private volatile PitchEstimationAlgorithm algorithm;

    // Configuration: required probability threshold for a valid pitch (0..1).
    private volatile float probability = 0.8f;

    public Tuner(Consumer<Tuning> listener) {
        this(listener, PitchEstimationAlgorithm.MPM);
    }

    public Tuner(Consumer<Tuning> listener, PitchEstimationAlgorithm algo) {
        super(listener, WavConstants.JACK_BUFFER);
        this.algorithm = Objects.requireNonNull(algo);
        this.detector = algo.getDetector(Constants.sampleRate(), Constants.bufSize());
    }

    /** Set the probability threshold (0..1) below which detections are ignored. */
    public void setProbabilityThreshold(float p) {
        this.probability = p;
    }

    @Override
    protected Tuning analyze(Recording rec) {
        PitchDetector d = this.detector;
        if (d == null) {
            RTLogger.log(this, "No detector available");
            return null;
        }
        PitchDetectionResult res = d.getPitch(rec.getLeft());
        if (res == null) return null;
        float freq = res.getPitch();
        float prob = res.getProbability();
        if (freq <= 0 || prob < probability) return null;
        Note note = Key.toNote(freq);
        float deviation = 0f;
        if (note != null) deviation = freq - Key.toFrequency(note);
        return new Tuning(freq, prob, note, deviation);
    }

    @Override public String getName() { return Tuner.class.getSimpleName(); }
    @Override public int getParamCount() { return Settings.values().length; }

    @Override
    public void set(int idx, int value) {
        switch (idx) {
            case 0 -> { // probability threshold 0..100 => 0.0..1.0
                int v = Math.max(0, Math.min(100, value));
                this.probability = v * 0.01f;
                RTLogger.log(this, "set probability -> " + this.probability);
            }
            case 1 -> { // algorithm index
                PitchEstimationAlgorithm[] vals = PitchEstimationAlgorithm.values();
                int v = Math.max(0, Math.min(vals.length - 1, value));
                PitchEstimationAlgorithm newAlgo = vals[v];
                if (newAlgo != this.algorithm) {
                    this.algorithm = newAlgo;
                    this.detector = newAlgo.getDetector(Constants.sampleRate(), Constants.bufSize());
                    RTLogger.log(this, "algorithm set -> " + newAlgo.name());
                }
            }
            default -> throw new IllegalArgumentException("unknown param idx: " + idx);
        }
    }

    @Override
    public int get(int idx) {
        return switch (idx) {
            case 0 -> Math.round(this.probability * 100f);
            case 1 -> this.algorithm.ordinal();
            default -> throw new IllegalArgumentException("unknown param idx: " + idx);
        };
    }

    @Override
    public void reset() {
        set(Settings.Algo.ordinal(), PitchEstimationAlgorithm.MPM.ordinal());
    }

}