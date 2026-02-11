package judahzone.fx.op;

import judahzone.util.Constants;
import lombok.Setter;

/** A simple feedback comb filter.
 * <p>
 * The feedback is a single delay line with a lowpass filter in the feedback path.
 * The delay length is fixed at construction time, and the feedback and damping can be changed in
 * real time. The filter is a simple one-pole lowpass, with the cutoff determined by the damp parameter.
 * damp = 0 means no damping (infinite cutoff), and higher values mean more damping (lower cutoff).
 * The filter state is stored in filterstore, and the buffer is a circular array of floats.
 * The processMix method takes an input array and an output array, and processes N_FRAMES samples at a time.
 * The output is the delayed signal from the buffer, and the input is added to the feedback signal before being stored in the buffer.
 * */
public class Comb {

	private static final int N_FRAMES = Constants.bufSize();

    @Setter float feedback; // roomsize
    float filterstore = 0;
    float damp1;
    float damp2;
    float[] buffer;
	int bufsize;
    int bufidx = 0;

    public Comb(int size) {
        bufsize = Math.max(1, size);
        reset();
    }

    public void reset() {
        buffer = new float[bufsize];
        bufidx = 0;
        filterstore = 0;
    }


    public void processMix(float inputs[], float outputs[]) {
        for (int i = 0; i < N_FRAMES; i++) {
            float output = buffer[bufidx];

            // undenormalise
            if (output > 0.0f && output < 1.0E-9f)
                output = 0;
            if (output < 0.0f && output > -1.0E-9f)
                output = 0;

            filterstore = (output * damp2) + (filterstore * damp1);
            // undenormalise(filterstore);
            if (filterstore > 0.0f && filterstore < 1.0E-9f)
                filterstore = 0;
            else if (filterstore < 0.0f && filterstore > -1.0E-9f)
                filterstore = 0;

            buffer[bufidx] = inputs[i] + (filterstore * feedback);

            if (++bufidx >= bufsize)
                bufidx = 0;

            outputs[i] += output;
        }
    }

    public void setdamp(float val) {
        damp1 = val;
        damp2 = 1 - val;
    }
}
