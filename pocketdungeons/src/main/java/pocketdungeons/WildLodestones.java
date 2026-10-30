package pocketdungeons;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.FrontAndTop;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.HashMap;
import java.util.Map;

/**
 * The blocks of wild mode (decision 2026-10-30): the entrance ruin with its jigsaw block in the overworld, and the Exit
 * jigsaws inside a dungeon. A jigsaw is bedrock hard and blast proof in survival (hardness -1, resistance 3600000), so
 * players cannot break one and explosions do nothing to it.
 *
 * <p>The saved {@link WildState}, not the block's name, says which lodestone an entrance belongs to. The exit blocks live
 * inside a record that is memory only, so their positions are kept in {@link #EXITS} and cleared at teardown.
 */
final class WildLodestones {

    static final String NAME_ENTRANCE = "wild_entrance";
    static final String NAME_EXIT_ARRIVAL = "wild_exit_arrival";
    static final String NAME_EXIT_CLEAR = "wild_exit_clear";

    /** Which lodestone an Exit block belongs to, and whether it is the clear exit. */
    record ExitRef(String lodestoneId, boolean clear) {}

    private static final Map<BlockPos, ExitRef> EXITS = SessionState.clearedAtStop(new HashMap<>());

    private WildLodestones() {}

    static void register() {
        UseBlockCallback.EVENT.register(WildLodestones::onUse);
    }

    static String dimensionKey(Level level) {
        return level.dimension().identifier().toString();
    }

    // ---- clicks -----------------------------------------------------------

    private static InteractionResult onUse(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (!WildMode.active() || level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        BlockPos pos = hit.getBlockPos();
        if (!level.getBlockState(pos).is(Blocks.JIGSAW)) {
            return InteractionResult.PASS;
        }
        ExitRef exit = EXITS.get(pos);
        WildState state = WildStore.state();
        WildState.Lodestone entrance = state == null ? null
                : state.atEntrance(dimensionKey(level), pos.getX(), pos.getY(), pos.getZ());
        if (exit == null && entrance == null) {
            return InteractionResult.PASS;
        }
        // Both hands fire; only the main hand opens the screen, but neither may fall through to the vanilla jigsaw editor.
        if (hand == InteractionHand.MAIN_HAND) {
            if (entrance != null) {
                WildDialogs.showOutside(serverPlayer, entrance);
            } else {
                WildState.Lodestone owner = state == null ? null : state.get(exit.lodestoneId());
                if (owner != null) {
                    WildDialogs.showInside(serverPlayer, owner, exit.clear());
                }
            }
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    // ---- the entrance ruin --------------------------------------------------

    /**
     * Stamps the ruin: a 5 by 5 mossy brick floor, two broken pillars, a cobweb, and the entrance jigsaw at {@code pos}.
     * The space above the floor is cleared so the block is never buried. The placer guarantees an open sky column; an
     * operator placing by hand gets exactly where they stand.
     */
    static void stamp(ServerLevel level, BlockPos pos) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean cracked = (Math.abs(dx) + Math.abs(dz)) % 2 == 1;
                level.setBlock(pos.offset(dx, -1, dz),
                        (cracked ? Blocks.CRACKED_STONE_BRICKS : Blocks.MOSSY_STONE_BRICKS).defaultBlockState(), 3);
                for (int dy = 0; dy <= 3; dy++) {
                    level.setBlock(pos.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        for (int dy = 0; dy <= 2; dy++) {
            level.setBlock(pos.offset(-2, dy, -2), Blocks.STONE_BRICKS.defaultBlockState(), 3);
        }
        level.setBlock(pos.offset(2, 0, 2), Blocks.MOSSY_STONE_BRICKS.defaultBlockState(), 3);
        level.setBlock(pos.offset(2, 1, 2), Blocks.CRACKED_STONE_BRICKS.defaultBlockState(), 3);
        level.setBlock(pos.offset(2, 2, -2), Blocks.COBWEB.defaultBlockState(), 3);
        placeJigsaw(level, pos, NAME_ENTRANCE);
    }

    /** Turns the ruin to rubble and removes the jigsaw (a collapse). */
    static void rubble(ServerLevel level, BlockPos pos) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean gravel = (dx * 31 + dz * 17) % 3 == 0;
                level.setBlock(pos.offset(dx, -1, dz),
                        (gravel ? Blocks.GRAVEL : Blocks.COBBLESTONE).defaultBlockState(), 3);
                for (int dy = 0; dy <= 3; dy++) {
                    level.setBlock(pos.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), 3);
        level.setBlock(pos.offset(-2, 0, -2), Blocks.COBBLESTONE.defaultBlockState(), 3);
        level.setBlock(pos.offset(2, 0, 2), Blocks.COBBLESTONE_SLAB.defaultBlockState(), 3);
    }

    // ---- exit blocks ----------------------------------------------------------

    /** Places an Exit jigsaw inside a dungeon and remembers it for the click handler. */
    static void placeExit(ServerLevel level, BlockPos pos, String lodestoneId, boolean clear) {
        placeJigsaw(level, pos, clear ? NAME_EXIT_CLEAR : NAME_EXIT_ARRIVAL);
        EXITS.put(pos.immutable(), new ExitRef(lodestoneId, clear));
    }

    /** Forgets every Exit block of one lodestone (its dungeon is being torn down). */
    static void forgetExits(String lodestoneId) {
        EXITS.values().removeIf(ref -> ref.lodestoneId().equals(lodestoneId));
    }

    static ExitRef exitAt(BlockPos pos) {
        return EXITS.get(pos);
    }

    // ---- the jigsaw itself ------------------------------------------------------

    private static void placeJigsaw(ServerLevel level, BlockPos pos, String name) {
        BlockState state = Blocks.JIGSAW.defaultBlockState().setValue(JigsawBlock.ORIENTATION, FrontAndTop.UP_NORTH);
        level.setBlock(pos, state, 3);
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof JigsawBlockEntity jigsaw) {
            jigsaw.setName(Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, name));
            jigsaw.setTarget(JigsawBlockEntity.EMPTY_ID);
            jigsaw.setPool(ResourceKey.create(Registries.TEMPLATE_POOL, JigsawBlockEntity.EMPTY_ID));
            jigsaw.setFinalState("minecraft:air");
            jigsaw.setJoint(JigsawBlockEntity.JointType.ALIGNED);
            jigsaw.setChanged();
        }
    }
}
