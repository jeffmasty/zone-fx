package judahzone.fx;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;

import judahzone.api.FX;
import judahzone.api.FX.RTFX;
import judahzone.util.Constants;

/**
 * Float-array backed version of StereoBus.
 *
 * - Uses float[] buffers (no FloatBuffer anywhere).
 * - Uses judahzone.api.FX and FX.RTFX for realtime effects.
 * - Preserves the same semantics as the original StereoBus:
 *     * per-channel working buffers owned here and exposed to callers
 *     * separate lists for known FX, active RTFX, pendingActive (hotswap),
 *       and offline FX
 *     * hot-swap behavior guarded by activeDirty
 *
 * Notes:
 * - This class intentionally contains no references to java.nio.FloatBuffer.
 * - Callers that previously relied on StereoBus.getLeft()/getRight() returning
 *   FloatBuffer will need to be updated to use float[] with this class.
 */
public class StereoBus {

    protected static final int N_FRAMES = Constants.bufSize();
    protected static final int S_RATE = Constants.sampleRate();

    // per-channel working buffers (owned here so GUI, headless and analyzers share the same buffers)
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

    protected StereoBus() {
        pendingActive.addAll(active);
    }

    /** Effects ready at creation */
    public StereoBus(FX... bus) {
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

    /** process active real-time effects on the supplied buffers */
    public void process(float[] l, float[] r) {
        hotSwap();
        for (RTFX fx : active)
            fx.process(l, r);
    }

    // pass gui changes to the rt thread
    protected void hotSwap() {
        if (activeDirty) {
            active.clear();
            active.addAll(pendingActive);
            activeDirty = false;
        }
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
                if (!pendingActive.contains(effect))
                    pendingActive.add((RTFX) effect);
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
}