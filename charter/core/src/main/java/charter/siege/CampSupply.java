package charter.siege;

/** A camp's own supply (DESIGN.md §10.10). */
public enum CampSupply {
    /** Raising, packed, or active with nobody to fight: it does not eat. */
    IDLE,
    /** Besieging or raiding with a side open. Burns at the normal rate. */
    OPEN,
    /** Every side closed by hostile claims. Nothing gets in and it burns twice as fast. */
    CUT_OFF,
    /** Its store is empty, so it no longer counts as besieging anyone. */
    EMPTY
}
