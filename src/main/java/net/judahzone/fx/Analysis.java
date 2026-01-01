package net.judahzone.fx;

import static judahzone.util.WavConstants.JACK_BUFFER;

import java.nio.FloatBuffer;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import judahzone.api.Effect;
import judahzone.util.AudioTools;
import judahzone.util.Memory;
import judahzone.util.RTLogger;
import judahzone.util.Recording;

/**
 * Convenience Effect for "copy & analyze" style offline analysis effects.
 * - Standardize snapshot/copy from RT into a Recording
 * Subclasses must implement analyze(Recording) which runs on the executor thread.
 */
public abstract class Analysis<T> implements Effect {

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
    public final void process(FloatBuffer left, FloatBuffer right) {
        final float[][] frame = Memory.STEREO.getFrame();
        AudioTools.copy(left, frame[0]);
        AudioTools.copy(right, frame[1]);
        realtime.add(frame);
        int current = realtime.size() * JACK_BUFFER;
        if (realtime.size() * JACK_BUFFER < bufferSize) {
        	RTLogger.log(this, "buffered " + current + " vs " + bufferSize);
        	return;
        }

        // buffering complete, do your job
        Recording job = realtime;
        executor.submit(() -> {
            try {
                T t = analyze(job);
                if (t != null)
                	listener.accept(t);
            } catch (Throwable t) {
                RTLogger.warn(t);
            }
        });
        realtime = new Recording();
    }

    /**
     * Subclass hook executed on the analysis thread. Left and right arrays are
     * buffers from Memory.STEREO.getFrame() and may be reused by other callers;
     * if you need to keep them beyond the scope of this method you must copy them.
     */
    protected abstract T analyze(Recording rec);

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