package judahzone.fx.op;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;

import judahzone.api.FX;
import judahzone.api.FX.RTFX;
import judahzone.fx.Gain;
import judahzone.util.Constants;

/**
 * Stereo effects bus with hot-swappable real-time processing and offline effect chains.
 * Thread-safe via lock-free hot-swap pattern: RT thread reads {@code active} list only,
 * GUI thread modifies {@code pendingActive}, swap occurs at RT safe-point.
 *
 * <h3>RT Thread Usage (Audio Callback)</h3>
 * Call {@link #process(float[], float[])} in audio loop. Internally:
 * <ul>
 *   <li>{@code hotSwap()} applies pending GUI changes (lock-free swap of {@code active})</li>
 *   <li>Iterates immutable {@code active} list, no allocations</li>
 * </ul>
 *
 * <h3>GUI/Non-RT Thread Usage</h3>
 * Call {@link #toggle(FX)}, {@link #setActive(FX, boolean)}, or {@link #reset()}.
 * These modify {@code pendingActive} and set {@code activeDirty} flag.
 * RT thread picks up changes at next {@code process()} call (latency ≤ 1 buffer).
 *
 * <h3>Offline Effects</h3>
 * {@code offline} list (non-RT effects like LFOs) is NOT processed in RT path.
 */
public class FXBus {

    protected static final int N_FRAMES = Constants.bufSize();
    protected static final int S_RATE = Constants.sampleRate();

    // per-channel working buffers (owned here so RT, GUI and analyzers share the same buffers)
    protected final float[] left = new float[N_FRAMES];
    protected final float[] right = new float[N_FRAMES];

    // RT effects known to the channel
    protected final ArrayList<RTFX> rt = new ArrayList<>();

    // The list used by the RT thread while processing
    protected ArrayList<RTFX> active = new ArrayList<>();

    // The list modified by GUI / presets, then swapped in at a safe point
    private final ArrayList<RTFX> pendingActive = new ArrayList<>();

    // Offline-active effects (not iterated in RT loop)
    private final List<FX> offline = new ArrayList<>();

    // All effects known to this channel (RT + offline + LFOs etc.)
    protected final List<FX> effects = new ArrayList<>();

    // fx activate/deactivate flag
    private volatile boolean activeDirty = false;

    protected FXBus() {
        pendingActive.addAll(active);
    }

    /** Effects ready at creation */
    public FXBus(FX... bus) {
        this();
        for (FX fx : bus) {
            effects.add(fx);
            if (fx instanceof RTFX hot)
                rt.add(hot);
        }
    }

    /** Provide external access to the channel work buffers for offline analysis/capture */
    public float[] getLeft() { return left; }
    public float[] getRight() { return right; }

//    /** process active real-time effects on the supplied buffers */
//    public void process(float[] l, float[] r) {
//        hotSwap();
//        for (RTFX fx : active)
//            fx.process(l, r);
//    }

    // pass gui changes to the rt thread
    protected void hotSwap() {
        if (activeDirty) {
            active.clear();
            active.addAll(pendingActive);
            activeDirty = false;
        }
    }

    public void reset() {
        // deactivate everything through the same path as toggle()
        // but we can do it directly to avoid spamming UI updates for each effect

        for (RTFX rte : rt) {
            if (pendingActive.contains(rte)) {
                rte.reset();
            }
        }
        // turn off RT effects
        pendingActive.clear();
        activeDirty = true;  // RT thread will pick up empty active list

        // turn off offline effects
        for (FX fx : offline) {
            fx.reset();
        }
        offline.clear();

        // reset all effect internals (regardless of whether they were active)
        for (FX fx : effects) {
            fx.reset();
        }
        // gui updates left to callers
    }

    public void setActive(FX fx, boolean on) {
        boolean currentlyOn = isActive(fx);
        if (on == currentlyOn) return;
        toggle(fx);
    }

    public boolean isActive(FX effect) {
        if (rt.contains(effect))
            return pendingActive.contains(effect);
        return offline.contains(effect);
    }

    public List<FX> listAll() {
        return new ArrayList<>(effects);
    }

    /** Insert fx into pendingActive so processing order will match the canonical rt list. */
    private void insertPendingInRtOrder(RTFX fx) {
        if (pendingActive.contains(fx)) return; // already present, no-op

        int desired = rt.indexOf(fx);
        if (desired < 0) { // not known in rt — append
            pendingActive.add(fx);
            return;
        }

        int insertPos = pendingActive.size();
        for (int i = 0, n = pendingActive.size(); i < n; i++) {
            RTFX cur = pendingActive.get(i);
            int curIdx = rt.indexOf(cur);
            if (curIdx < 0) continue;                // unknown ordering, skip
            if (curIdx > desired) { insertPos = i; break; } // insert before first with larger rt index
        }
        pendingActive.add(insertPos, fx);
    }

    /** activate/deactive effect (hotswap gatekeeper) */
    public void toggle(FX effect) {
        boolean wasOn = isActive(effect);

        // Determine new "on" state
        boolean nowOn;
        if (!wasOn) {
            // turning on
            nowOn = true;
            effect.activate();
        } else {
            // turning off
            nowOn = false;
            effect.reset();
        }

        if (rt.contains(effect)) {
            // RT effect: operate on pendingActive; swap will occur on RT thread
            if (nowOn) {
                // preserve canonical rt order when adding
                insertPendingInRtOrder((RTFX) effect);
            } else {
                pendingActive.remove(effect);
            }
            activeDirty = true;
        } else if (effects.contains(effect)) {
            // offline effect: just track in offline list
            if (nowOn) {
                if (!offline.contains(effect))
                    offline.add(effect);
            } else {
                offline.remove(effect);
            }
        } else if (effect instanceof Gain) {
            // toggle mute or SOLO?
        } else
            throw new InvalidParameterException(effect.toString());
        // gui updates left to callers
    }

}