package charter.land;

/** A camp's life (DESIGN.md §10.2). The siege simulation moves a camp between phases; the land graph only reads it. */
public enum CampPhase {
    /** Planted and visible, but unprotected and not yet besieging. */
    RAISING,
    /** Finished raising: its claims are protected and it can besiege. */
    ACTIVE,
    /** Picked up and waiting to be replanted. */
    PACKED
}
