package pocketdungeons;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * M47 (SITUATIONS_SPEC 6.6 step 2): the seed of the solvability pass's
 * {@code available} set.
 *
 * <p>The pass walks the floor in BFS order carrying a cumulative set of tool
 * tags. Depth 0 is the entrance, and two things are true there before any room
 * has been picked: whatever the party carried in, and whether the party is more
 * than one person. This class is only that seed. Everything after depth 0 comes
 * out of a room's {@code provides} and belongs to {@link RoomSelector}.
 *
 * <p><strong>Why party size grants {@code mob}.</strong> Spec 6.1 defines
 * {@code mob} as "any upstream room that spawns a leashable mob, or party size
 * 2 or more": a second player stands on the other plate. Audit finding 2.8
 * narrows the room half of that to <em>leashable</em>, which is a datapack
 * rule and not this class's business; the party half is unconditional. A solo
 * Pilgrim gets nothing, which is exactly the case spec 6.3 makes the
 * generator's test.
 *
 * <p><strong>Where the bag data lives.</strong> The data-driven
 * {@link BagManifest} holds each bag's tag set, and {@link Bags#tagsFor}
 * is the lookup this class delegates to rather than keeping a second copy
 * that would drift. The touch is a plain static call over a
 * {@code Set<String>} and pulls in no server state.
 */
final class BagTags {

    /** Party size at or above which the party itself satisfies {@code mob}. */
    static final int MOB_PARTY_SIZE = 2;

    private BagTags() {}

    /**
     * The depth-0 tag set for a run.
     *
     * @param bagId     the chosen bag's id, or {@code null} for no bag at all.
     *                  An unknown id seeds nothing, which can only make the
     *                  generator work harder and never make a floor
     *                  unsolvable. See {@link Bags#tagsFor}.
     * @param partySize how many players enter. Two or more adds {@code mob}.
     * @return an immutable tag set, never null
     */
    static Set<String> seed(String bagId, int partySize) {
        Set<String> out = new LinkedHashSet<>(Bags.tagsFor(bagId));
        if (partySize >= MOB_PARTY_SIZE) {
            out.add(SituationTags.MOB);
        }
        return Set.copyOf(out);
    }

    /**
     * The Pilgrim case: an empty bag and a party of one. Spec 6.3's test seed,
     * and the seed every caller that has no bag to name should use, because it
     * is the strictest one.
     */
    static Set<String> pilgrim() {
        return seed(null, 1);
    }
}
