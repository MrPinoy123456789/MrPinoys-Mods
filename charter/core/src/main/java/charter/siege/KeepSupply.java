package charter.siege;

/** A keep's supply state (DESIGN.md §10.5). */
public enum KeepSupply {
    /** No counting camp touches the ring. The granary is not drawn on. */
    SUPPLIED,
    /** Besieged with some sides open: runners can still carry food in. */
    BESIEGED,
    /** Every perimeter chunk held by besiegers: nothing gets in. */
    ENCIRCLED
}
