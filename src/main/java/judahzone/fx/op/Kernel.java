package judahzone.fx.op;

import java.util.List;

import judahzone.data.Letter;
import judahzone.data.Postage;
import judahzone.filter.Coord;
import judahzone.fx.Gain;
import judahzone.fx.Gain.GainT;
import judahzone.fx.MonoFilter;

public record Kernel(
		GainT gain,
		Postage env,
		Coord low,
		Coord hi
		) {

	public Kernel(Gain g, Letter l, MonoFilter lowCut, MonoFilter hiCut) {
		this(g.get(), Postage.adsr(l),lowCut.get(), hiCut.get());
	}

	public static final Kernel GENERIC = new Kernel(new GainT(1f), new Postage(50, 666, 0.73f, 333),
			new Coord(20, 1), new Coord(10000, 1));

	public static enum Params {
		PREAMP, GAIN, PAN, ATK, DK, SUS, REL, LOHZ, LORES, HIHZ, HIRES
	}

	public static int PARAM_COUNT = 11;

	public static interface KernelParams {
		public static int PREAMP = 0;
		public static int GAIN = 1;
		public static int PAN = 2;
		public static int ATK = 3;
		public static int DK = 4;
		public static int SUS = 5;
		public static int REL = 6;
		public static int LOHZ = 7;
		public static int LORES = 8;
		public static int HIHZ = 9;
		public static int HIRES = 10;
	}

	public static String[] LABELS = new String[] {"Preamp", "Pan", "Atk", "Dk", "Sus", "Rel", "LoHz", "LoRes", "HiHz", "HiRes"};
	public static List<String> LABELS_LIST = List.of(LABELS);


}
