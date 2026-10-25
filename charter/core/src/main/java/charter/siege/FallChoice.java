package charter.siege;

/** What the besieger does with a keep that has fallen (DESIGN.md §10.8). */
public enum FallChoice {
    /** The nexus breaks and the keep's nine chunks are unclaimed. */
    RAZE,
    /** The keep stays recorded as the owner's; the occupier controls it for a capped time. */
    OCCUPY
}
