package judahzone.fx.analysis;

import static judahzone.util.WavConstants.JACK_BUFFER;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import judahzone.api.FX.Calc;
import judahzone.util.Memory;
import judahzone.util.RTLogger;
import judahzone.util.Recording;

/**
 * Convenience Effect for "copy & analyze" style offline analysis effects.
 * - Standardize snapshot/copy from RT into a Recording
 * Subclasses must implement analyze(Recording) which runs on the executor thread.
 */
// public abstract T analyze(float[] left, float[] right);
public abstract class Analysis<T> implements Calc<T> {

    // Executor used for analysis jobs. Can be supplied by subclasses or use default.
    private final ExecutorService executor;

    private final Consumer<T> listener;
    private final int bufferSize;
    private Recording realtime = new Recording();

    /** int bufferSize buffering in samples */
    protected Analysis(Consumer<T> l, int bufferSize) {
        this(l, bufferSize, Executors.newSingleThreadExecutor(
        	r -> { Thread t = new Thread(r, defaultThreadName());
            			t.setDaemon(true);
            			return t; }));
    }

    protected Analysis(Consumer<T> listener, int bufferSize, ExecutorService executor) {
    	this.bufferSize = bufferSize;
        this.listener = Objects.requireNonNull(listener);
        this.executor = Objects.requireNonNull(executor);
    }

    /** Default thread name for the internal executor. Subclasses may override. */
    protected static String defaultThreadName() {
        return Analysis.class.getSimpleName() + "-analyzer";
    }

    /**
     * Snapshot the provided buffers and submit an analysis job.
     * Subclasses must implement analyze(left,right) which runs on executor thread.
     */
    @Override
    public final void process(float[] left, float[] right) {
        final float[][] frame = Memory.STEREO.getFrame();
        System.arraycopy(left,  0, frame[0], 0, N_FRAMES);
        System.arraycopy(right, 0, frame[1], 0, N_FRAMES);
        realtime.add(frame);
        if (realtime.size() * JACK_BUFFER < bufferSize)
            return; // building up

        // buffering complete, snapshot the accumulated Recording and submit an analysis job
        final Recording job = realtime;

        executor.submit(() -> {
            try {
                // Prepare contiguous float[] windows of length bufferSize.
                // Defensive: Recording may be exactly bufferSize long or slightly larger;
                // take the newest bufferSize samples if larger, zero-pad if smaller.
                float[] fullLeft = job.getLeft();
                float[] fullRight = job.getChannel(1);

                float[] inLeft = new float[bufferSize];
                float[] inRight = new float[bufferSize];

                int flen = (fullLeft != null) ? fullLeft.length : 0;
                int rlen = (fullRight != null) ? fullRight.length : 0;

                int startL = Math.max(0, flen - bufferSize);
                int copyL = Math.min(bufferSize, Math.max(0, flen - startL));
                if (copyL > 0) System.arraycopy(fullLeft, startL, inLeft, 0, copyL);

                int startR = Math.max(0, rlen - bufferSize);
                int copyR = Math.min(bufferSize, Math.max(0, rlen - startR));
                if (copyR > 0) System.arraycopy(fullRight, startR, inRight, 0, copyR);

                // Call subclass analyze on the prepared buffers
                T t = analyze(inLeft, inRight);
                if (t != null)
                    listener.accept(t);
                Memory.STEREO.release(job);
            } catch (Throwable t) {
                RTLogger.warn(t);
            }
        });

        // start fresh
        realtime = new Recording();
    }

    /** Stop accepting new jobs and shutdown the internal executor. */
    public void close() {
            try {
                executor.shutdownNow();
            } catch (Throwable t) {
                RTLogger.warn(t);
            }
    }

    @Override public int getParamCount() { return 0; }
    @Override public void set(int idx, int value) { /* no-op */ }
    @Override public int get(int idx) { throw new IllegalArgumentException("no params"); }
    @Override public void reset() { /* no-op */ }
    @Override public String getName() {return getClass().getSimpleName();}
}