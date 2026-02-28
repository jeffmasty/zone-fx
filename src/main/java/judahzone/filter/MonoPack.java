package judahzone.filter;

import lombok.Getter;


// Standardized Mono Cut+EQ for Osc units MonoT for serialization
// separate fundamental pitch from peaking filter (but tie/track them together somehow on switch)
public class MonoPack /* implements FX */ {

	public static enum Settings{
		LOW_HZ, LOW_RESO, MID_HZ, MID_GAIN, MID_WIDTH, HI_CUT, HI_RESO
	}

	@Getter private final String name = MonoPack.class.getSimpleName();
	@Getter private final int paramCount = Settings.values().length;

	// private final MonoPeaking mid = new MonoPeaking(1000, 0, 1, 1);

	// MonoT encapsulates Settings

	// a low Cut
	// a mid Peaking
	// a high Cut

	// get(int) set(int, int)

	// process (mono, null)

}
