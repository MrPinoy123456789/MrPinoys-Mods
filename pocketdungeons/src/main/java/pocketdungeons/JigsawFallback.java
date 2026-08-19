package pocketdungeons;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
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
 * <p>The filter is by <em>namespace</em>, not by the {@code pocketdungeons:door}
 * name alone. Rooms also carry {@code pocketdungeons:spawn} jigsaws marking mob
 * spawn points, which the stamper reads out of the template and which must then
 * erase themselves exactly like doors do -- a name-only filter would leave them
 * standing in the finished dungeon. A jigsaw belonging to another mod is left
 * alone; it is not this pass's business.
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
            Identifier name = jigsaw.getName();
            if (name == null || !PocketDungeonsMod.MOD_ID.equals(name.getNamespace())) {
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
