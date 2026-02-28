package judahzone.filter;

public record FilterT (float hz, float dB, float bandwidth) {
	public FilterT (Coord coord) {
		this(coord.hz(), coord.reso(), 0.4f);
	}

}
