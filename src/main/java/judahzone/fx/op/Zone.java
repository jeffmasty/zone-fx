package judahzone.fx.op;

import java.util.List;

import judahzone.data.Postage;
import judahzone.fx.Gain;
import judahzone.fx.MonoFilter;

public interface Zone {

	Gain getGain();
	MonoFilter getLowCut();
	MonoFilter getHiCut();
	Postage getPostage();


	List<String> getParams();
	void set(int idx, int knob); // custom
	int get(int idx); // custom
	default String val(int idx) { // child overrides: bool/Colour/hz/note, etc
		return "" + get(idx);
	}

	// TODO knob support like Drum.java

}
