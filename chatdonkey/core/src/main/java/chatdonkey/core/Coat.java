package chatdonkey.core;

/**
 * The donkey's coat: its real chest inventory, seen from {@code core} as a
 * count of burrs and a screen that is either open or not (SPEC.md section 4).
 *
 * <p>Grouped behind its own interface rather than bolted onto
 * {@link EventContext} because only one behavior uses any of it, and
 * {@code EventContext} is already the widest seam in the mod.
 *
 * <p>The container is the source of truth, not {@code core}. The player
 * physically drags burrs out, so the rules here ask "how many are left" rather
 * than tracking slots themselves -- anything else would desync the moment a
 * player rearranged the coat, which they are free to do.
 */
public interface Coat {

    /** Forces the coat open on the target player's screen. */
    void open();

    /** True while the player actually has the coat open. */
    boolean isOpen();

    /** Places {@code count} burrs in random empty slots. */
    void seed(int count);

    /**
     * Adds one more burr to a random empty slot.
     *
     * @return false if there was nowhere to put it
     */
    boolean addOne();

    /** How many burrs are left in the coat. */
    int remaining();

    /** How many slots the coat has in total. */
    int size();
}
