package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.ChatFormatting;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PD-160: every iron door in a dungeon opens from the side you arrive on.
 *
 * <p>No connector is locked by design; only an {@code access: gated} room
 * locks. Two kinds of door needed code rather than redstone, because the
 * redstone could not be relied on:
 * <ul>
 *   <li><b>Latch doors</b> ({@link InstanceLayout#latchDoors}): a flooded hall's
 *       containment doors. A button in the neighbour's doorway slot can be
 *       overwritten by the neighbour's connector or template, and one inside the
 *       water is washed off, so using the door itself opens both leaves for
 *       {@link #LATCH_TICKS} and they close again.</li>
 *   <li><b>Connector openers</b> ({@link InstanceLayout#doorOpeners}): the lever on
 *       the near room's wall and the stone button in the far room. Neither touches
 *       the door, so using one sets the door's leaves directly. A lever is a
 *       standing switch (the door follows it); a button opens the door for good,
 *       since a connector is never a lock.</li>
 * </ul>
 *
 * <p>Doors are opened by setting {@code OPEN} alone, never {@code POWERED}: an
 * unpowered open door survives any neighbour update, where a powered one with no
 * signal behind it would be shut by the next block placed beside it.
 *
 * <p>A shut door that is neither explains itself on use, so a player is told
 * where the way through is instead of facing a silent door.
 */
final class IronDoorLatch {

    /** How long a latch door stays open after use. */
    static final int LATCH_TICKS = 60;

    /** Spacing of the explanatory action bar line, per player. */
    private static final int HINT_COOLDOWN_TICKS = 60;

    /** Lower door halves awaiting closure, mapped to the game tick they close at. */
    private static final Map<BlockPos, Long> closeAt = new HashMap<>();
    private static final Map<UUID, Long> lastHint = new HashMap<>();

    private IronDoorLatch() {}

    static void register() {
        UseBlockCallback.EVENT.register(IronDoorLatch::onUse);
        ServerTickEvents.END_SERVER_TICK.register(IronDoorLatch::onTick);
    }

    private static InteractionResult onUse(Player player, Level level, InteractionHand hand,
                                           BlockHitResult hit) {
        if (level.isClientSide() || hand != InteractionHand.MAIN_HAND
                || !(player instanceof ServerPlayer serverPlayer)
                || !(level instanceof ServerLevel serverLevel)
                || !level.dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
            return InteractionResult.PASS;
        }
        InstanceRecord record = InstanceRegistry.byMember.get(serverPlayer.getUUID());
        if (record == null || record.layout == null) {
            return InteractionResult.PASS;
        }
        InstanceLayout layout = record.layout;
        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);

        if (state.is(Blocks.IRON_DOOR)) {
            BlockPos lower = lowerOf(pos, state);
            if (layout.latchDoors().contains(lower)) {
                latch(serverLevel, lower, layout.latchDoors());
                return InteractionResult.SUCCESS_SERVER;
            }
            if (!state.getValue(DoorBlock.OPEN)) {
                hint(serverPlayer, serverLevel, lower, layout);
            }
            return InteractionResult.PASS;
        }

        Set<BlockPos> doors = layout.doorOpeners().get(pos);
        if (doors == null) {
            return InteractionResult.PASS;
        }
        if (state.is(Blocks.LEVER)) {
            // The lever toggles after this returns, so the door follows the state it is about to take.
            setOpen(serverLevel, doors, !state.getValue(LeverBlock.POWERED));
        } else if (state.is(BlockTags.BUTTONS)) {
            setOpen(serverLevel, doors, true);
        }
        return InteractionResult.PASS;
    }

    /** Opens both leaves of the latch doorway holding {@code lower}, and (re)starts its close timer. */
    static void latch(ServerLevel level, BlockPos lower, Set<BlockPos> latchDoors) {
        Set<BlockPos> leaves = leavesOf(lower, latchDoors);
        setOpen(level, leaves, true);
        long due = level.getGameTime() + LATCH_TICKS;
        for (BlockPos leaf : leaves) {
            closeAt.put(leaf, due);
        }
    }

    /** {@code lower} plus any latch door lower half beside it: the other leaf of its pair. */
    private static Set<BlockPos> leavesOf(BlockPos lower, Set<BlockPos> latchDoors) {
        Set<BlockPos> leaves = new java.util.HashSet<>();
        leaves.add(lower);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos next = lower.relative(side);
            if (latchDoors.contains(next)) {
                leaves.add(next);
            }
        }
        return leaves;
    }

    /** Test hook for {@link #leavesOf}. */
    static Set<BlockPos> leavesOfForTest(BlockPos lower, Set<BlockPos> latchDoors) {
        return leavesOf(lower, latchDoors);
    }

    private static void setOpen(ServerLevel level, Set<BlockPos> lowers, boolean open) {
        boolean changed = false;
        for (BlockPos lower : lowers) {
            changed |= setLeaf(level, lower, open);
        }
        if (changed) {
            BlockPos any = lowers.iterator().next();
            level.playSound(null, any, open ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.IRON_DOOR_CLOSE,
                    SoundSource.BLOCKS, 1.0f, 1.0f);
        }
    }

    /** Sets both halves of the door whose lower half is {@code lower}; whether anything changed. */
    private static boolean setLeaf(ServerLevel level, BlockPos lower, boolean open) {
        BlockState state = level.getBlockState(lower);
        if (!state.is(Blocks.IRON_DOOR) || state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
            return false;
        }
        boolean changed = state.getValue(DoorBlock.OPEN) != open;
        level.setBlock(lower, state.setValue(DoorBlock.OPEN, open), Block.UPDATE_CLIENTS);
        BlockPos upperPos = lower.above();
        BlockState upper = level.getBlockState(upperPos);
        if (upper.is(Blocks.IRON_DOOR)) {
            level.setBlock(upperPos, upper.setValue(DoorBlock.OPEN, open), Block.UPDATE_CLIENTS);
        }
        return changed;
    }

    private static BlockPos lowerOf(BlockPos pos, BlockState state) {
        return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
    }

    /** Which line a shut door shows: its lever, or the room that holds it shut; nothing otherwise. */
    static String hintFor(InstanceLayout layout, BlockPos lower, BlockPos cellOrigin) {
        if (layout.isConnectorDoor(lower)) {
            return "The lever is in this room.";
        }
        if (cellOrigin != null && (Locks.hint(cellOrigin) != null || Ordeals.objectiveAt(cellOrigin) != null)) {
            return "Opens when the room is solved.";
        }
        return null;
    }

    private static void hint(ServerPlayer player, ServerLevel level, BlockPos lower, InstanceLayout layout) {
        String line = hintFor(layout, lower, Instances.dungeonCellOriginAt(lower));
        if (line == null) {
            return;
        }
        long now = level.getGameTime();
        Long last = lastHint.get(player.getUUID());
        if (last != null && now - last < HINT_COOLDOWN_TICKS) {
            return;
        }
        lastHint.put(player.getUUID(), now);
        player.connection.send(new ClientboundSetActionBarTextPacket(
                Component.literal(line).withStyle(ChatFormatting.YELLOW)));
    }

    private static void onTick(MinecraftServer server) {
        if (closeAt.isEmpty()) {
            return;
        }
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return;
        }
        tick(level);
    }

    /** Closes every latch whose time is up in {@code level}. */
    static void tick(ServerLevel level) {
        long now = level.getGameTime();
        Set<BlockPos> due = new java.util.HashSet<>();
        for (Iterator<Map.Entry<BlockPos, Long>> it = closeAt.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, Long> entry = it.next();
            if (entry.getValue() <= now) {
                due.add(entry.getKey());
                it.remove();
            }
        }
        if (due.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (BlockPos lower : due) {
            if (level.isLoaded(lower)) {
                changed |= setLeaf(level, lower, false);
            }
        }
        if (changed) {
            level.playSound(null, due.iterator().next(), SoundEvents.IRON_DOOR_CLOSE,
                    SoundSource.BLOCKS, 1.0f, 1.0f);
        }
    }

    /** Test hook: forget every pending close and hint. */
    static void clear() {
        closeAt.clear();
        lastHint.clear();
    }
}
