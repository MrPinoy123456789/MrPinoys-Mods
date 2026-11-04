package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Places a {@link WildRuin} in the world: clears the space above the plaza, fills a foundation under it down to solid
 * ground, then lays the plan in the theme's blocks. A block in {@code pocketdungeons:wild_builder_blocks} (what players
 * build with) is never replaced, so rebuilding an old ruin beside a player's outpost leaves the outpost standing.
 */
final class WildRuinStamp {

    private static final int FLAGS = Block.UPDATE_ALL;

    /** The blocks of one theme: one or more per role, picked by position so the mix does not repeat. */
    private static final Map<WildRuin.Theme, Map<WildRuin.Role, List<Block>>> PALETTES =
            new EnumMap<>(WildRuin.Theme.class);
    /** What fills the space under a plaza on a slope. */
    private static final Map<WildRuin.Theme, Block> FOUNDATION = new EnumMap<>(WildRuin.Theme.class);

    static {
        palette(WildRuin.Theme.STONE, Blocks.COBBLESTONE,
                List.of(Blocks.STONE_BRICKS), List.of(Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS),
                List.of(Blocks.POLISHED_ANDESITE), List.of(Blocks.CHISELED_STONE_BRICKS), List.of(Blocks.ANDESITE),
                List.of(Blocks.STONE_BRICKS), List.of(Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS),
                List.of(Blocks.CHISELED_STONE_BRICKS), List.of(Blocks.STONE_BRICKS), List.of(Blocks.STONE_BRICKS),
                Blocks.LANTERN);
        palette(WildRuin.Theme.MINE, Blocks.COBBLESTONE,
                List.of(Blocks.COBBLESTONE, Blocks.ANDESITE), List.of(Blocks.MOSSY_COBBLESTONE, Blocks.GRAVEL),
                List.of(Blocks.POLISHED_ANDESITE), List.of(Blocks.COAL_BLOCK), List.of(Blocks.STONE),
                List.of(Blocks.COBBLESTONE), List.of(Blocks.MOSSY_COBBLESTONE),
                List.of(Blocks.STRIPPED_SPRUCE_LOG), List.of(Blocks.SPRUCE_LOG), List.of(Blocks.STRIPPED_SPRUCE_LOG),
                Blocks.LANTERN);
        palette(WildRuin.Theme.COPPER, Blocks.COBBLESTONE,
                List.of(Blocks.WAXED_CUT_COPPER, Blocks.WAXED_EXPOSED_CUT_COPPER),
                List.of(Blocks.WAXED_WEATHERED_CUT_COPPER, Blocks.WAXED_OXIDIZED_CUT_COPPER),
                List.of(Blocks.POLISHED_ANDESITE), List.of(Blocks.WAXED_CHISELED_COPPER), List.of(Blocks.STONE_BRICKS),
                List.of(Blocks.WAXED_EXPOSED_COPPER), List.of(Blocks.WAXED_OXIDIZED_COPPER),
                List.of(Blocks.WAXED_CHISELED_COPPER), List.of(Blocks.WAXED_WEATHERED_COPPER),
                List.of(Blocks.WAXED_WEATHERED_CUT_COPPER), Blocks.LANTERN);
        palette(WildRuin.Theme.NETHER, Blocks.BLACKSTONE,
                List.of(Blocks.POLISHED_BLACKSTONE_BRICKS), List.of(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS),
                List.of(Blocks.POLISHED_BLACKSTONE), List.of(Blocks.CRYING_OBSIDIAN), List.of(Blocks.BLACKSTONE),
                List.of(Blocks.POLISHED_BLACKSTONE_BRICKS), List.of(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS),
                List.of(Blocks.CHISELED_POLISHED_BLACKSTONE), List.of(Blocks.POLISHED_BASALT),
                List.of(Blocks.POLISHED_BLACKSTONE_BRICKS), Blocks.SOUL_LANTERN);
        palette(WildRuin.Theme.END, Blocks.END_STONE,
                List.of(Blocks.END_STONE_BRICKS), List.of(Blocks.END_STONE),
                List.of(Blocks.PURPUR_BLOCK), List.of(Blocks.OBSIDIAN), List.of(Blocks.END_STONE),
                List.of(Blocks.PURPUR_PILLAR), List.of(Blocks.PURPUR_BLOCK),
                List.of(Blocks.PURPUR_BLOCK), List.of(Blocks.PURPUR_PILLAR), List.of(Blocks.PURPUR_BLOCK),
                Blocks.END_ROD);
        palette(WildRuin.Theme.DEEP, Blocks.COBBLED_DEEPSLATE,
                List.of(Blocks.DEEPSLATE_TILES, Blocks.DEEPSLATE_BRICKS),
                List.of(Blocks.CRACKED_DEEPSLATE_TILES, Blocks.CRACKED_DEEPSLATE_BRICKS),
                List.of(Blocks.POLISHED_DEEPSLATE), List.of(Blocks.CHISELED_DEEPSLATE), List.of(Blocks.COBBLED_DEEPSLATE),
                List.of(Blocks.DEEPSLATE_BRICKS), List.of(Blocks.CRACKED_DEEPSLATE_BRICKS),
                List.of(Blocks.CHISELED_DEEPSLATE), List.of(Blocks.POLISHED_DEEPSLATE), List.of(Blocks.DEEPSLATE_TILES),
                Blocks.LANTERN);
    }

    private static void palette(WildRuin.Theme theme, Block foundation, List<Block> floor, List<Block> floorWorn,
                                List<Block> inner, List<Block> accent, List<Block> edge, List<Block> pillar,
                                List<Block> pillarWorn, List<Block> cap, List<Block> post, List<Block> lintel,
                                Block light) {
        Map<WildRuin.Role, List<Block>> map = new EnumMap<>(WildRuin.Role.class);
        map.put(WildRuin.Role.FLOOR, floor);
        map.put(WildRuin.Role.FLOOR_WORN, floorWorn);
        map.put(WildRuin.Role.INNER, inner);
        map.put(WildRuin.Role.ACCENT, accent);
        map.put(WildRuin.Role.EDGE, edge);
        map.put(WildRuin.Role.PILLAR, pillar);
        map.put(WildRuin.Role.PILLAR_WORN, pillarWorn);
        map.put(WildRuin.Role.CAP, cap);
        map.put(WildRuin.Role.POST, post);
        map.put(WildRuin.Role.LINTEL, lintel);
        map.put(WildRuin.Role.LIGHT_STANDING, List.of(light));
        map.put(WildRuin.Role.LIGHT_HANGING, List.of(light));
        map.put(WildRuin.Role.COBWEB, List.of(Blocks.COBWEB));
        PALETTES.put(theme, map);
        FOUNDATION.put(theme, foundation);
    }

    private WildRuinStamp() {}

    /** Whether a player built this block, so the ruin leaves it alone. */
    static boolean playerBuilt(BlockState state) {
        return state.is(WildTopUp.BUILDER_BLOCKS);
    }

    /** Lays the ruin around {@code pos} (the lodestone block, which the caller places afterwards). */
    static void stamp(ServerLevel level, BlockPos pos, WildRuin.Theme theme) {
        Map<WildRuin.Role, List<Block>> palette = PALETTES.get(theme == null ? WildRuin.Theme.STONE : theme);
        Block foundation = FOUNDATION.get(theme == null ? WildRuin.Theme.STONE : theme);
        long seed = pos.asLong() * 31L + level.getSeed();
        for (int dx = -WildRuin.RADIUS; dx <= WildRuin.RADIUS; dx++) {
            for (int dz = -WildRuin.RADIUS; dz <= WildRuin.RADIUS; dz++) {
                if (!WildRuin.inFootprint(dx, dz)) {
                    continue;
                }
                for (int dy = 0; dy <= WildRuin.CLEAR_HEIGHT; dy++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos at = pos.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(at);
                    if (!state.isAir() && !playerBuilt(state)) {
                        level.setBlock(at, Blocks.AIR.defaultBlockState(), FLAGS);
                    }
                }
                for (int dy = -2; dy >= -1 - WildRuin.FOUNDATION_DEPTH; dy--) {
                    BlockPos at = pos.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(at);
                    boolean hollow = state.isAir() || state.canBeReplaced() || !state.getFluidState().isEmpty()
                            || state.is(BlockTags.LEAVES);
                    if (!hollow) {
                        break;
                    }
                    level.setBlock(at, foundation.defaultBlockState(), FLAGS);
                }
            }
        }
        for (WildRuin.Part part : WildRuin.plan(seed)) {
            BlockPos at = pos.offset(part.dx(), part.dy(), part.dz());
            if (playerBuilt(level.getBlockState(at))) {
                continue;
            }
            List<Block> choices = palette.get(part.role());
            Block block = choices.get(Math.floorMod(at.hashCode(), choices.size()));
            level.setBlock(at, shaped(block.defaultBlockState(), part.role()), FLAGS);
        }
    }

    /** Turns the blocks that have a direction: a hung light hangs, a lintel log lies along the arch. */
    private static BlockState shaped(BlockState state, WildRuin.Role role) {
        if (role == WildRuin.Role.LIGHT_HANGING) {
            if (state.hasProperty(BlockStateProperties.HANGING)) {
                return state.setValue(BlockStateProperties.HANGING, true);
            }
            if (state.hasProperty(BlockStateProperties.FACING)) {
                return state.setValue(BlockStateProperties.FACING, Direction.DOWN);
            }
        }
        if (role == WildRuin.Role.LINTEL && state.hasProperty(BlockStateProperties.AXIS)) {
            return state.setValue(BlockStateProperties.AXIS, Direction.Axis.X);
        }
        return state;
    }

    /**
     * Turns a version 2 ruin to rubble (a collapse): the plaza becomes broken stone and everything standing falls, the
     * ruin's own pillars, arch and lights included. A player's blocks on the plaza stay.
     */
    static void rubble(ServerLevel level, BlockPos pos) {
        for (int dx = -WildRuin.RADIUS; dx <= WildRuin.RADIUS; dx++) {
            for (int dz = -WildRuin.RADIUS; dz <= WildRuin.RADIUS; dz++) {
                if (!WildRuin.inFootprint(dx, dz)) {
                    continue;
                }
                BlockPos floor = pos.offset(dx, -1, dz);
                if (!playerBuilt(level.getBlockState(floor))) {
                    boolean gravel = Math.floorMod(dx * 31 + dz * 17, 3) == 0;
                    level.setBlock(floor, (gravel ? Blocks.GRAVEL : Blocks.COBBLESTONE).defaultBlockState(), FLAGS);
                }
                for (int dy = 0; dy <= WildRuin.CLEAR_HEIGHT; dy++) {
                    BlockPos at = pos.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(at);
                    if (!state.isAir() && !playerBuilt(state)) {
                        level.setBlock(at, Blocks.AIR.defaultBlockState(), FLAGS);
                    }
                }
            }
        }
        // The ruin's own blocks go whatever they are: its lanterns are in the builder tag, and would otherwise hang
        // in the air over the rubble.
        for (WildRuin.Part part : WildRuin.plan(0L)) {
            if (part.dy() >= 0) {
                level.setBlock(pos.offset(part.dx(), part.dy(), part.dz()), Blocks.AIR.defaultBlockState(), FLAGS);
            }
        }
        // A few fallen stones where the lodestone, the arch and the banner pillar stood.
        level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), FLAGS);
        level.setBlock(pos.offset(-2, 0, -2), Blocks.MOSSY_COBBLESTONE.defaultBlockState(), FLAGS);
        level.setBlock(pos.offset(-3, 0, -3), Blocks.COBBLESTONE.defaultBlockState(), FLAGS);
        level.setBlock(pos.offset(2, 0, -2), Blocks.COBBLESTONE_SLAB.defaultBlockState(), FLAGS);
    }
}
