package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Slot allocation and the two lookup maps every other part of the instance
 * system reads: which member belongs to which live run, and which slot holds
 * which run. No party logic, no run lifecycle, no teardown -- just the
 * bookkeeping those depend on, extracted first among {@code Instances}' six
 * M9 C3 splits (after {@link CellGeometry}) because everything else needs it
 * to exist before it can be extracted in turn.
 */
final class InstanceRegistry {

    /** Section 9: not config-exposed -- changing it mid-campaign would relocate
     *  every existing instance's world coordinates. */
    private static final int BASE_Y = 64;

    /** Every member of every live instance resolves to its record. Many-to-one. */
    static final Map<UUID, InstanceRecord> byMember = new HashMap<>();
    /** Live instances by slot, including any that momentarily have no members. */
    static final Map<Integer, InstanceRecord> bySlot = new LinkedHashMap<>();
    /** Allocated slots, owned instances and admin builds alike. */
    static final Set<Integer> usedSlots = new TreeSet<>();

    private InstanceRegistry() {}

    static boolean hasInstance(ServerPlayer player) {
        return byMember.containsKey(player.getUUID());
    }

    /** Origin of a slot, so admin tooling can point at the geometry. */
    static BlockPos slotOrigin(int slot) {
        return originForSlot(slot);
    }

    static int allocateSlot() {
        int slot = 0;
        while (usedSlots.contains(slot)) {
            slot++;
        }
        usedSlots.add(slot);
        return slot;
    }

    /**
     * M25: allocates a child slot adjacent to {@code anchor} when one is free.
     * The slot grid is linear (slot {@code n+1} sits one {@code slotPitch} east
     * of slot {@code n}), so adjacency keeps a Pocket2 child's geometry next to
     * its parent's. Falls back to the plain free-list when both neighbours are
     * taken.
     */
    static int allocateSlotNear(int anchor) {
        for (int candidate : new int[]{anchor + 1, anchor - 1}) {
            if (candidate >= 0 && !usedSlots.contains(candidate)) {
                usedSlots.add(candidate);
                return candidate;
            }
        }
        return allocateSlot();
    }

    static BlockPos originForSlot(int slot) {
        int slotsPerRow = PocketDungeonsConfig.slotsPerRow();
        int slotPitch = PocketDungeonsConfig.slotPitch();
        return new BlockPos((slot % slotsPerRow) * slotPitch, BASE_Y,
                (slot / slotsPerRow) * slotPitch);
    }

    /**
     * Every cell origin in the widest grid a layout is ever allowed to span,
     * config-driven rather than the live layout's own (possibly smaller)
     * footprint -- teardown clears against this so a slot never leaves a
     * stray cell behind if a layout somehow undershoots what it was allocated.
     */
    static List<BlockPos> maximalCellOrigins(BlockPos origin) {
        int span = PocketDungeonsConfig.maxGridSpan();
        List<BlockPos> out = new ArrayList<>(span * span);
        for (int cx = 0; cx < span; cx++) {
            for (int cz = 0; cz < span; cz++) {
                out.add(origin.offset(cx * RoomGeometry.CELL, 0, cz * RoomGeometry.CELL));
            }
        }
        return out;
    }

    /** The bounding box of {@link #maximalCellOrigins}, for a layout-less entity sweep. */
    static AABB maximalBounds(BlockPos origin) {
        int span = PocketDungeonsConfig.maxGridSpan() * RoomGeometry.CELL;
        return new AABB(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + span,
                origin.getY() + RoomGeometry.CEILING_Y + 1,
                origin.getZ() + span);
    }
}
