package ballot;

/**
 * A ballot box bound to an entry.
 *
 * <p>Deliberately stores coordinates as plain numbers and the dimension as a string,
 * so the core module stays free of Minecraft types and can be tested without a server.
 */
public final class Station {

    /** {@value #BOX} or {@value #SIGN}. Older files predate this and default to a box. */
    public static final String BOX = "box";
    public static final String SIGN = "sign";
    /** Bound to the poll itself rather than to an entry, so its entry id is unused. */
    public static final String LECTERN = "lectern";
    /**
     * A poll's single voting box, listing every option. Bound to the poll, not an
     * entry — a poll's options are words, so one box serves all of them. A build
     * competition uses {@link #BOX} instead, one per plot, because there the thing you
     * are voting for is somewhere you have to walk to.
     */
    public static final String BALLOT = "ballot";

    private int entryId;
    private String kind;
    private String dimension;
    private int x;
    private int y;
    private int z;

    Station() {}   // Gson

    public Station(int entryId, String kind, String dimension, int x, int y, int z) {
        this.entryId = entryId;
        this.kind = kind;
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public int entryId() {
        return entryId;
    }

    public String kind() {
        return kind == null ? BOX : kind;
    }

    public boolean isBox() {
        return kind().equals(BOX);
    }

    public boolean isSign() {
        return kind().equals(SIGN);
    }

    public boolean isLectern() {
        return kind().equals(LECTERN);
    }

    public boolean isBallot() {
        return kind().equals(BALLOT);
    }

    public String dimension() {
        return dimension;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public boolean isAt(String dimension, int x, int y, int z) {
        return this.x == x && this.y == y && this.z == z && this.dimension.equals(dimension);
    }

    @Override
    public String toString() {
        return x + " " + y + " " + z + " (" + dimension + ")";
    }
}
