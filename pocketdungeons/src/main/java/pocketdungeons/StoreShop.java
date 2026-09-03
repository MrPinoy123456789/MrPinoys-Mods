package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * M35 store anomaly: builds a small village-shop interior inside an anomaly
 * room cell. The shop is purely decorative: a counter, some shelves, a few
 * barrels and a lantern, arranged so the room reads as a shop rather than
 * another dungeon room. The actual vendor is {@link StoreNPC}, spawned
 * separately by {@link RoomContent}.
 *
 * <p>The shop is stamped into the cell's interior (local 1..14, floor at
 * local Y+1) and never touches the wall ring or the door slots, so it
 * composes with whatever door mask the cell happened to need.
 */
final class StoreShop {

    private StoreShop() {}

    /**
     * Builds the shop interior. The layout is a counter across one wall
     * with barrels behind it, shelves on the side wall, and a hanging
     * lantern for light. All placement is relative to the cell origin.
     */
    static void build(ServerLevel level, BlockPos cellOrigin, long seed) {
        int floorY = 1;
        // Counter: a row of spruce slabs across the back wall (local Z=2),
        // with oak log posts at each end as the counter supports.
        for (int x = 3; x <= 12; x++) {
            setBlock(level, cellOrigin.offset(x, floorY, 2),
                    Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
            // Behind the counter: barrels for storage
            setBlock(level, cellOrigin.offset(x, floorY, 1), Blocks.BARREL.defaultBlockState());
        }
        // Counter supports
        setBlock(level, cellOrigin.offset(2, floorY, 2), Blocks.OAK_LOG.defaultBlockState());
        setBlock(level, cellOrigin.offset(13, floorY, 2), Blocks.OAK_LOG.defaultBlockState());
        setBlock(level, cellOrigin.offset(2, floorY + 1, 2), Blocks.OAK_LOG.defaultBlockState());
        setBlock(level, cellOrigin.offset(13, floorY + 1, 2), Blocks.OAK_LOG.defaultBlockState());

        // Shelves on the left wall (local X=2): bookshelves and planks
        for (int z = 4; z <= 12; z++) {
            setBlock(level, cellOrigin.offset(2, floorY, z), Blocks.BOOKSHELF.defaultBlockState());
            setBlock(level, cellOrigin.offset(2, floorY + 1, z), Blocks.SPRUCE_PLANKS.defaultBlockState());
        }

        // Right wall (local X=13): a few chests and a crafting table
        setBlock(level, cellOrigin.offset(13, floorY, 5), Blocks.CHEST.defaultBlockState());
        setBlock(level, cellOrigin.offset(13, floorY, 7), Blocks.CRAFTING_TABLE.defaultBlockState());
        setBlock(level, cellOrigin.offset(13, floorY, 9), Blocks.CHEST.defaultBlockState());

        // A rug in the centre: spruce planks with a border of oak planks
        for (int x = 6; x <= 9; x++) {
            for (int z = 6; z <= 9; z++) {
                boolean edge = x == 6 || x == 9 || z == 6 || z == 9;
                setBlock(level, cellOrigin.offset(x, floorY, z),
                        edge ? Blocks.OAK_PLANKS.defaultBlockState()
                                : Blocks.SPRUCE_PLANKS.defaultBlockState());
            }
        }

        // Hanging lanterns for light
        setBlock(level, cellOrigin.offset(7, floorY + 3, 7), Blocks.LANTERN.defaultBlockState());
        setBlock(level, cellOrigin.offset(9, floorY + 3, 7), Blocks.LANTERN.defaultBlockState());

        // A few decorative items on the counter
        setBlock(level, cellOrigin.offset(5, floorY + 1, 2), Blocks.FLOWER_POT.defaultBlockState());
        setBlock(level, cellOrigin.offset(10, floorY + 1, 2), Blocks.CAKE.defaultBlockState());

        // Clear the interior above the rug so the shop feels open
        for (int x = 5; x <= 10; x++) {
            for (int z = 5; z <= 10; z++) {
                for (int y = floorY + 1; y <= floorY + 2; y++) {
                    if (level.getBlockState(cellOrigin.offset(x, y, z)).canBeReplaced()) {
                        setBlock(level, cellOrigin.offset(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
    }

    private static void setBlock(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
    }
}
