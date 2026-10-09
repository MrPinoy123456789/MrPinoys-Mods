package pocketdungeons;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The wolf rooms (design pass 2026-10-09, Q8; PD-188): the Kennels\u0027 pens, its leader\u0027s den, and the Lost
 * Dog, a rare room any early dungeon can hold. Wolves here are neutral and tameable with bones, exactly the
 * Feral affix\u0027s wolves ({@link FeralContent}); the Lost Dog is the exception, tamed by clearing the room.
 */
final class KennelSpecs {

    private KennelSpecs() {}

    private static final BlockState HAY = Blocks.HAY_BLOCK.defaultBlockState();
    private static final BlockState FENCE = Blocks.SPRUCE_FENCE.defaultBlockState();
    private static final BlockState BONES = Blocks.BONE_BLOCK.defaultBlockState();
    private static final BlockState LOG = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState();
    private static final BlockState WEB = Blocks.COBWEB.defaultBlockState();

    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.add(kennelRun());
        specs.add(alphasDen());
        specs.add(lostDog());
        return specs;
    }

    private static BlockState fence(boolean east, boolean west, boolean north, boolean south) {
        return FENCE.setValue(FenceBlock.EAST, east).setValue(FenceBlock.WEST, west)
                .setValue(FenceBlock.NORTH, north).setValue(FenceBlock.SOUTH, south);
    }

    private static BlockState gate(Direction facing) {
        return Blocks.SPRUCE_FENCE_GATE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing);
    }

    /**
     * The Kennel Run: a path between two rows of fenced pens, a two wide gate to each, hay in the corners and a
     * stray wolf in each pen. The wolves are neutral: do not hit them, feed them bones. West and east doors.
     */
    private static RoomSpec kennelRun() {
        return new RoomSpec("kennel_run", EnumSet.of(Direction.WEST, Direction.EAST))
                .spawns(new BlockPos(4, 1, 3), new BlockPos(11, 1, 3), new BlockPos(4, 1, 12), new BlockPos(11, 1, 12))
                .decor((level, o) -> {
                    for (int z : new int[]{5, 10}) {
                        for (int x = 1; x <= 14; x++) {
                            RoomDsl.put(level, o, x, 1, z, fence(x < 14, x > 1, false, false));
                        }
                        RoomDsl.put(level, o, 7, 1, z, gate(Direction.NORTH));
                        RoomDsl.put(level, o, 8, 1, z, gate(Direction.NORTH));
                        RoomDsl.put(level, o, 1, 2, z, LOG);
                        RoomDsl.put(level, o, 14, 2, z, LOG);
                    }
                    RoomDsl.put(level, o, 2, 1, 2, HAY);
                    RoomDsl.put(level, o, 13, 1, 2, HAY);
                    RoomDsl.put(level, o, 2, 1, 13, HAY);
                    RoomDsl.put(level, o, 13, 1, 13, HAY);
                    RoomDsl.put(level, o, 6, 1, 3, BONES);
                    RoomDsl.put(level, o, 9, 1, 12, BONES);
                    RoomDsl.put(level, o, 3, 4, 7, WEB);
                });
    }

    /**
     * The Alpha\u0027s Den: a deep den at the end of the Kennels. Hay beds, heaps of bone and spruce beams round
     * a wide floor, the spawner at the far end. A dead end, west door only.
     */
    private static RoomSpec alphasDen() {
        return new RoomSpec("alphas_den", EnumSet.of(Direction.WEST))
                .spawns(new BlockPos(11, 1, 4), new BlockPos(11, 1, 11), new BlockPos(12, 1, 8))
                .decor((level, o) -> {
                    // Spruce beams along the north and south walls.
                    for (int x = 3; x <= 13; x += 5) {
                        RoomDsl.column(level, o, x, 1, 4, 1, LOG);
                        RoomDsl.column(level, o, x, 1, 4, 14, LOG);
                    }
                    // Hay beds and bone heaps: the den\u0027s floor.
                    RoomDsl.box(level, o, 2, 1, 2, 3, 1, 3, HAY);
                    RoomDsl.box(level, o, 2, 1, 12, 3, 1, 13, HAY);
                    RoomDsl.box(level, o, 13, 1, 2, 14, 1, 3, HAY);
                    RoomDsl.box(level, o, 13, 1, 12, 14, 1, 13, HAY);
                    RoomDsl.box(level, o, 6, 1, 3, 7, 2, 3, BONES);
                    RoomDsl.box(level, o, 6, 1, 12, 7, 2, 12, BONES);
                    RoomDsl.put(level, o, 14, 1, 7, BONES);
                    RoomDsl.put(level, o, 14, 1, 8, BONES);
                    RoomDsl.put(level, o, 4, 4, 5, WEB);
                    RoomDsl.put(level, o, 4, 4, 10, WEB);
                });
    }

    /**
     * The Lost Dog: a trapper\u0027s camp. A spruce fenced pen holds one wolf; zombies hold the camp. Clear the
     * room and the dog is yours for the trip, no bones needed. West and east doors.
     */
    private static RoomSpec lostDog() {
        return new RoomSpec("lost_dog", EnumSet.of(Direction.WEST, Direction.EAST))
                .spawns(new BlockPos(5, 1, 4), new BlockPos(12, 1, 11))
                .decor((level, o) -> {
                    // The pen: a fence square at x 10..13, z 10..13 with a gate on its north side.
                    for (int x = 10; x <= 13; x++) {
                        RoomDsl.put(level, o, x, 1, 10, x == 11 ? gate(Direction.NORTH)
                                : fence(x < 13, x > 10, false, false));
                        RoomDsl.put(level, o, x, 1, 13, fence(x < 13, x > 10, false, false));
                    }
                    for (int z = 11; z <= 12; z++) {
                        RoomDsl.put(level, o, 10, 1, z, fence(false, false, true, true));
                        RoomDsl.put(level, o, 13, 1, z, fence(false, false, true, true));
                    }
                    RoomDsl.put(level, o, 11, 1, 12, HAY);
                    // The camp: a log seat, a hay bale, a cold fire pit.
                    RoomDsl.put(level, o, 4, 1, 6, LOG);
                    RoomDsl.put(level, o, 3, 1, 9, HAY);
                    RoomDsl.put(level, o, 5, 1, 9, Blocks.CAMPFIRE.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.CampfireBlock.LIT, false));
                });
    }

    // ---- handlers --------------------------------------------------------------------

    private static boolean handlersRegistered;

    /** The Lost Dog\u0027s wolf in each cell: its cell origin to the wolf. */
    private static final Map<BlockPos, UUID> LOST_DOGS = new HashMap<>();

    /** Registers the wolf room situations. Called once from {@link TrialContent#warmUp()}. */
    static void registerHandlers() {
        if (handlersRegistered) {
            return;
        }
        handlersRegistered = true;
        Situations.register("kennel_run", (level, o, role, depth, profile, spawns, seed, affixes, lootSuffix, theme,
                                           voidedFloor, content) -> {
            FeralContent.applyCount(level, o, spawns, profile.lootTier(), profile.keystoneLevel(), seed, 3);
            return null;
        });
        Situations.register("lost_dog", (level, o, role, depth, profile, spawns, seed, affixes, lootSuffix, theme,
                                         voidedFloor, content) -> {
            // The spawn point inside the fence holds the dog; the other holds the camp\u0027s spawner.
            BlockPos pen = null;
            int best = -1;
            for (BlockPos spawn : spawns) {
                int fences = 0;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    for (int reach = 1; reach <= 3; reach++) {
                        BlockState state = level.getBlockState(spawn.relative(d, reach));
                        if (state.getBlock() instanceof FenceBlock || state.is(Blocks.SPRUCE_FENCE_GATE)) {
                            fences++;
                            break;
                        }
                    }
                }
                if (fences > best) {
                    best = fences;
                    pen = spawn;
                }
            }
            List<BlockPos> camp = new ArrayList<>(spawns);
            if (pen != null && best >= 3) {
                camp.remove(pen);
                List<Wolf> dogs = FeralContent.applyCount(level, o, List.of(pen), profile.lootTier(),
                        profile.keystoneLevel(), seed, 1);
                if (!dogs.isEmpty()) {
                    LOST_DOGS.put(o.immutable(), dogs.get(0).getUUID());
                }
            }
            return TrialContent.applyEncounter(level, o, camp, profile.lootTier(), affixes, "lost_dog", false);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (LOST_DOGS.isEmpty() || server.getTickCount() % 20 != 0) {
                return;
            }
            ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
            if (level == null) {
                return;
            }
            for (Map.Entry<BlockPos, UUID> entry : new ArrayList<>(LOST_DOGS.entrySet())) {
                Entity dog = level.getEntity(entry.getValue());
                if (!(dog instanceof Wolf wolf) || !wolf.isAlive()) {
                    LOST_DOGS.remove(entry.getKey());
                    continue;
                }
                if (TrialContent.hasActiveSpawner(level, entry.getKey())) {
                    continue;
                }
                net.minecraft.world.phys.AABB room = CellGeometry.cellBounds(entry.getKey());
                for (ServerPlayer player : level.players()) {
                    if (room.contains(player.position())) {
                        wolf.tame(player);
                        wolf.setOrderedToSit(false);
                        player.sendOverlayMessage(Component.literal("A friend.").withStyle(ChatFormatting.AQUA));
                        LOST_DOGS.remove(entry.getKey());
                        break;
                    }
                }
            }
        });
    }
}
