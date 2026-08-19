package pocketdungeons;

import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One live dungeon instance and the party inside it.
 *
 * <p>Membership, not ownership: the player who opened the instance is simply
 * the first member. An instance lives until its last member walks out, so that
 * player leaving early does not dissolve the dungeon around the rest of the
 * party.
 *
 * <p>Milestone 0 keeps these in memory only; the spec's {@code SavedData}
 * persistence (section 11) lands with M5, and the member map is why that codec
 * is written against a party from its first version.
 */
final class InstanceRecord {

    final int slot;
    /** Floor corner of the layout's anchor cell -- everything else is derived from this. */
    final BlockPos origin;
    final long createdAtTick;

    /**
     * The shape of this particular dungeon: entrance, exit pad, bounds, occupied
     * cells, tier. Procedural layouts differ per instance, so this travels with
     * the record rather than being recomputed from a constant.
     */
    final InstanceLayout layout;

    /** Member -> where that member came from. Insertion-ordered: opener first. */
    final Map<UUID, ReturnPoint> members = new LinkedHashMap<>();

    /**
     * Members already paid for completing this run. Declared here rather than in
     * the payout code because it has to die with the instance: reaching the exit
     * pad again means walking a fresh dungeon, which is a rate limit set by a
     * human rather than by a timer.
     */
    final Set<UUID> paid = new HashSet<>();

    InstanceRecord(int slot, BlockPos origin, long createdAtTick, InstanceLayout layout) {
        this.slot = slot;
        this.origin = origin;
        this.createdAtTick = createdAtTick;
        this.layout = layout;
    }
}
