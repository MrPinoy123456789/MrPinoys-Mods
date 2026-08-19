package pocketdungeons;

/**
 * An undirected door connection between two adjacent {@link PlanCell}s. The
 * canonical constructor orders the pair so {@code new PlanEdge(a, b)} and
 * {@code new PlanEdge(b, a)} are equal and hash the same -- callers never need
 * to worry about which order they discovered an edge in.
 */
record PlanEdge(PlanCell a, PlanCell b) {

    PlanEdge {
        if (a.equals(b)) {
            throw new IllegalArgumentException("an edge cannot connect a cell to itself: " + a);
        }
        if (compare(b, a) < 0) {
            PlanCell tmp = a;
            a = b;
            b = tmp;
        }
    }

    private static int compare(PlanCell p, PlanCell q) {
        return p.x() != q.x() ? Integer.compare(p.x(), q.x()) : Integer.compare(p.z(), q.z());
    }

    /** True if this edge touches the given cell. */
    boolean touches(PlanCell cell) {
        return a.equals(cell) || b.equals(cell);
    }

    /** The cell on the other end of this edge from the given cell. */
    PlanCell other(PlanCell cell) {
        if (a.equals(cell)) return b;
        if (b.equals(cell)) return a;
        throw new IllegalArgumentException(cell + " is not part of edge " + this);
    }
}
