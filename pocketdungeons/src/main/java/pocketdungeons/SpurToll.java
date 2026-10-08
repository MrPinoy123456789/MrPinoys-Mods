package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * PD-164: the toll rooms (Barred Vault, Ominous Bargain) as they are stamped.
 *
 * <p>Both rooms were baked as "iron door plus filter hopper plus chest plus
 * comparator plus redstone". None of that worked: the hopper pointed into the
 * door block, so nothing ever reached the chest; the comparator and dust did
 * nothing; the 2 wide door stood in a partition only four blocks wide, so the
 * room could be walked around; and the filter was four stacks of the toll item
 * sitting in a hopper a player could open and empty (a stack of trial keys is
 * 64 emeralds at the clear line, J7).
 *
 * <p>The baked template cannot be fixed in place (the {@code .nbt} is generated
 * by a dev command), so each stamp repairs it, and {@link SpurSpecs} bakes the
 * same shape for the next regeneration:
 * <ul>
 *   <li>the hopper is emptied, so there is nothing to take;</li>
 *   <li>the dead comparator and redstone dust come out;</li>
 *   <li>the partition is closed from wall to wall with the iron door as the
 *       only way through;</li>
 *   <li>a {@link Locks.Kind#HOPPER_KEY} lock opens the door when the toll item
 *       is dropped in the hopper, and takes it.</li>
 * </ul>
 * Everything is found by scanning the cell, so it does not care which of the
 * four rotations the template was stamped at. Idempotent: running it on an
 * already repaired cell changes nothing.
 */
final class SpurToll {

    private SpurToll() {}

    /** The toll item of a toll room's {@code content} id, or {@code null} for any other room. */
    static Item tollFor(String content) {
        if ("barred_vault".equals(content)) {
            return Items.TRIAL_KEY;
        }
        if ("ominous_bargain".equals(content)) {
            return Items.GOLD_INGOT;
        }
        return null;
    }

    /** Repairs the toll room stamped at {@code origin} and arms its lock. */
    static void apply(ServerLevel level, BlockPos origin, Item toll) {
        List<BlockPos> doors = new ArrayList<>();
        List<BlockPos> dead = new ArrayList<>();
        for (int x = 0; x < RoomGeometry.CELL; x++) {
            for (int z = 0; z < RoomGeometry.CELL; z++) {
                for (int y = 1; y <= RoomGeometry.CEILING_Y; y++) {
                    BlockPos pos = origin.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.is(Blocks.IRON_DOOR)) {
                        doors.add(pos.immutable());
                    } else if (state.is(Blocks.COMPARATOR) || state.is(Blocks.REDSTONE_WIRE)) {
                        dead.add(pos.immutable());
                    } else if (state.is(Blocks.HOPPER)) {
                        BlockEntity be = level.getBlockEntity(pos);
                        if (be instanceof HopperBlockEntity hopper) {
                            hopper.clearContent();
                            hopper.setChanged();
                        }
                    }
                }
            }
        }
        for (BlockPos pos : dead) {
            RoomBuilder.set(level, pos, RoomBuilder.AIR);
        }
        closePartition(level, origin, doors);
        Locks.arm(level, origin, Locks.Kind.HOPPER_KEY, toll);
    }

    /**
     * Walls the room off along the line the doors stand on. The two door blocks
     * of the pair differ along one axis; the partition runs along that axis at
     * the other axis' constant. Only air is filled, so the doors, their frame
     * and anything already standing there are left alone.
     */
    private static void closePartition(ServerLevel level, BlockPos origin, List<BlockPos> doors) {
        if (doors.isEmpty()) {
            return;
        }
        int x0 = doors.getFirst().getX() - origin.getX();
        int z0 = doors.getFirst().getZ() - origin.getZ();
        boolean sameZ = doors.stream().allMatch(d -> d.getZ() - origin.getZ() == z0);
        boolean sameX = doors.stream().allMatch(d -> d.getX() - origin.getX() == x0);
        if (!sameZ && !sameX) {
            return;
        }
        for (int along = 1; along < RoomGeometry.CELL - 1; along++) {
            for (int y = 1; y <= RoomGeometry.CEILING_Y - 1; y++) {
                BlockPos pos = sameZ ? origin.offset(along, y, z0) : origin.offset(x0, y, along);
                if (level.getBlockState(pos).isAir()) {
                    RoomBuilder.set(level, pos, RoomBuilder.WALL);
                }
            }
        }
    }
}
