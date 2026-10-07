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
        int q = turnFor(level, cellOrigin);
        int floorY = 1;
        // Counter: a row of spruce slabs across the back wall (local Z=2),
        // with oak log posts at each end as the counter supports.
        for (int x = 3; x <= 12; x++) {
            setBlock(level, at(cellOrigin, q, x, floorY, 2),
                    Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
            // Behind the counter: barrels for storage
            setBlock(level, at(cellOrigin, q, x, floorY, 1), Blocks.BARREL.defaultBlockState());
        }
        // Counter supports
        setBlock(level, at(cellOrigin, q, 2, floorY, 2), Blocks.OAK_LOG.defaultBlockState());
        setBlock(level, at(cellOrigin, q, 13, floorY, 2), Blocks.OAK_LOG.defaultBlockState());
        setBlock(level, at(cellOrigin, q, 2, floorY + 1, 2), Blocks.OAK_LOG.defaultBlockState());
        setBlock(level, at(cellOrigin, q, 13, floorY + 1, 2), Blocks.OAK_LOG.defaultBlockState());

        // Shelves on the left wall (local X=2): bookshelves and planks
        for (int z = 4; z <= 12; z++) {
            setBlock(level, at(cellOrigin, q, 2, floorY, z), Blocks.BOOKSHELF.defaultBlockState());
            setBlock(level, at(cellOrigin, q, 2, floorY + 1, z), Blocks.SPRUCE_PLANKS.defaultBlockState());
        }

        // Right wall (local X=13): a few chests and a crafting table
        setBlock(level, at(cellOrigin, q, 13, floorY, 5), Blocks.CHEST.defaultBlockState());
        setBlock(level, at(cellOrigin, q, 13, floorY, 7), Blocks.CRAFTING_TABLE.defaultBlockState());
        setBlock(level, at(cellOrigin, q, 13, floorY, 9), Blocks.CHEST.defaultBlockState());

        // A rug in the centre: spruce planks with a border of oak planks
        for (int x = 6; x <= 9; x++) {
            for (int z = 6; z <= 9; z++) {
                boolean edge = x == 6 || x == 9 || z == 6 || z == 9;
                setBlock(level, at(cellOrigin, q, x, floorY, z),
                        edge ? Blocks.OAK_PLANKS.defaultBlockState()
                                : Blocks.SPRUCE_PLANKS.defaultBlockState());
            }
        }

        // Hanging lanterns for light
        setBlock(level, at(cellOrigin, q, 7, floorY + 3, 7), Blocks.LANTERN.defaultBlockState());
        setBlock(level, at(cellOrigin, q, 9, floorY + 3, 7), Blocks.LANTERN.defaultBlockState());

        // A few decorative items on the counter
        setBlock(level, at(cellOrigin, q, 5, floorY + 1, 2), Blocks.FLOWER_POT.defaultBlockState());
        setBlock(level, at(cellOrigin, q, 10, floorY + 1, 2), Blocks.CAKE.defaultBlockState());

        // Clear the interior above the rug so the shop feels open
        for (int x = 5; x <= 10; x++) {
            for (int z = 5; z <= 10; z++) {
                for (int y = floorY + 1; y <= floorY + 2; y++) {
                    if (level.getBlockState(at(cellOrigin, q, x, y, z)).canBeReplaced()) {
                        setBlock(level, at(cellOrigin, q, x, y, z), Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
    }

    /**
     * Quarter turns that put the counter on the wall opposite the room's only
     * open door (PD-140: the shop is authored with its counter on the north
     * wall, which is a doorway wall for a spur whose door is north). A room
     * with two doors or none keeps the authored layout.
     */
    static int turnFor(ServerLevel level, BlockPos origin) {
        int last = RoomGeometry.CELL - 1;
        boolean north = openAt(level, origin.offset(7, 1, 0));
        boolean south = openAt(level, origin.offset(7, 1, last));
        boolean west = openAt(level, origin.offset(0, 1, 7));
        boolean east = openAt(level, origin.offset(last, 1, 7));
        if ((north ? 1 : 0) + (south ? 1 : 0) + (west ? 1 : 0) + (east ? 1 : 0) != 1) {
            return 0;
        }
        return north ? 2 : east ? 3 : west ? 1 : 0;
    }

    private static boolean openAt(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** The authored (x, z) after {@code q} clockwise quarter turns of the cell, matching TemplateStamper. */
    private static BlockPos at(BlockPos origin, int q, int x, int y, int z) {
        int m = RoomGeometry.CELL - 1;
        return switch (q) {
            case 1 -> origin.offset(m - z, y, x);
            case 2 -> origin.offset(m - x, y, m - z);
            case 3 -> origin.offset(z, y, m - x);
            default -> origin.offset(x, y, z);
        };
    }

    private static void setBlock(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
    }
}
