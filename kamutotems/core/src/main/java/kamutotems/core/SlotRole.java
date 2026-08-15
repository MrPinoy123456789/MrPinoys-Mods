package kamutotems.core;

/**
 * What a slot is <em>for</em>. The frozen contract's section 3.1 model:
 *
 * <pre>
 *   [ DELIVERY ]      how and when the effect lands
 *       |
 *   [ MODIFIER ]      what it does
 *       |
 *   [ MODIFIER ]
 * </pre>
 */
public enum SlotRole {

    /** Slot 0. A delivery, or empty meaning "hit". */
    DELIVERY,

    /** Slots 1 and 2. An element or a behaviour. */
    MODIFIER;

    /**
     * Which category of kamu may occupy this role.
     *
     * <p>{@code DELIVERY} accepts {@link Category#DELIVERY}. With no delivery,
     * the player's own attack is the action, and the slot falls open to the
     * default "hit" delivery.
     */
    public boolean accepts(Category category) {
        if (category == null) {
            return false;
        }
        return switch (this) {
            case DELIVERY -> category == Category.DELIVERY;
            case MODIFIER -> category == Category.ELEMENT || category == Category.BEHAVIOUR;
        };
    }

    /** Player-facing name, used in fault text and slot labels. */
    public String label() {
        return switch (this) {
            case DELIVERY -> "Delivery";
            case MODIFIER -> "Modifier";
        };
    }
}
