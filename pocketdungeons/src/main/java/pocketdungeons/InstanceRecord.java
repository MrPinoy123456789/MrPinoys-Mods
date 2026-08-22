package pocketdungeons;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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

    // ---- keystone run state (U7) --------------------------------------------
    // All of it dies with the instance, which is the whole point: the keystone
    // itself is the save file, so there is no run to persist and nothing here has
    // to survive a restart.

    /** The affix the spent keystone carried; {@code FRAGILE} doubles every depletion. */
    final Keystone.Affix affix;

    /** The clock, or null when {@code timerEnabled} is off or nobody paid a keystone. */
    RunTimer timer;

    /** U7's three completion offers, in offer order. Empty until the run is stamped. */
    final List<BlockPos> choiceVaults = new ArrayList<>();

    /**
     * Members who have reached the exit pad. Reaching it once <em>completes</em> a
     * keystone run -- pay, log, hand over the token -- and does not eject; the
     * second contact is the one that leaves, so a player has time to spend the
     * token on one of the three vaults standing in the same room.
     */
    final Set<UUID> completed = new HashSet<>();

    /** Members who completed before the clock ran out, and so were offered an upgrade. */
    final Set<UUID> completedInTime = new HashSet<>();

    /** Members currently standing on the pad, so a contact is an edge and not a state. */
    final Set<UUID> onPad = new HashSet<>();

    /**
     * Members already handed a keystone by a choice vault. The exit path reads
     * this to know not to hand them a second, consolation one.
     */
    final Set<UUID> keystoneGranted = new HashSet<>();

    /**
     * Members whose keystone has already been handed back (or parked). The guard
     * that makes {@code Keystones.returnTo} idempotent, which matters because a
     * disconnect can be followed by a purge for the same member in the same tick
     * and both are depletion sites.
     */
    final Set<UUID> keystoneReturned = new HashSet<>();

    InstanceRecord(int slot, BlockPos origin, long createdAtTick, InstanceLayout layout,
                   Keystone.Affix affix) {
        this.slot = slot;
        this.origin = origin;
        this.createdAtTick = createdAtTick;
        this.layout = layout;
        this.affix = affix;
    }

    /** Whether a keystone was spent to open this run. False for {@code /dungeon admin build}. */
    boolean isKeystoneRun() {
        return layout.keystoneLevel() > 0;
    }
}
