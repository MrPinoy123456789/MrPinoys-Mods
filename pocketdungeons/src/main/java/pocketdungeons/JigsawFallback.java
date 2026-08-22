package pocketdungeons;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Stage 1 safety net: after a structure is placed, replace any leftover
 * {@code minecraft:jigsaw} block this mod authored with its configured final
 * state. This is cheap insurance whether or not the vanilla
 * {@link net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor}
 * ran during placement.
 *
 * <p><strong>The filter is the bounds, not the name.</strong> It started as
 * {@code pocketdungeons:door} only, widened to the {@code pocketdungeons}
 * namespace when spawn jigsaws arrived (U1), and is now "any jigsaw block left
 * standing inside a cell this mod just stamped". U6 borrows from vanilla's own
 * trial-chamber vocabulary, and anything borrowed carries {@code minecraft:}-named
 * jigsaws of its own ({@code minecraft:spawner}, {@code minecraft:reward_connector},
 * {@code minecraft:ominous_vault}) which a namespace filter would leave behind --
 * breaking M1's standing regression that a built instance contains
 * <strong>zero</strong> {@code minecraft:jigsaw} blocks anywhere.
 *
 * <p>The bounds are the safety argument. This only ever runs over a
 * {@code 16 x 7 x 16} cell the stamper wrote this tick, inside
 * {@code pocketdungeons:void} -- there is no player-built jigsaw in there to
 * destroy, and nothing outside those bounds is touched.
 */
final class JigsawFallback {

    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS;

    private JigsawFallback() {}

    static void replaceRemaining(ServerLevel level, BlockPos origin, Vec3i size) {
        BlockPos end = origin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1);
        for (BlockPos pos : BlockPos.betweenClosed(origin, end)) {
            if (!level.getBlockState(pos).is(Blocks.JIGSAW)) {
                continue;
            }
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof JigsawBlockEntity jigsaw)) {
                continue;
            }
            String finalState = jigsaw.getFinalState();
            BlockState state;
            try {
                state = BlockStateParser.parseForBlock(
                        level.getServer().registryAccess().lookupOrThrow(Registries.BLOCK),
                        finalState, false).blockState();
            } catch (CommandSyntaxException e) {
                PocketDungeonsMod.LOG.warn("Failed to parse jigsaw final state '{}', using air", finalState);
                state = Blocks.AIR.defaultBlockState();
            }
            level.setBlock(pos, state, FLAGS);
        }
    }
}
