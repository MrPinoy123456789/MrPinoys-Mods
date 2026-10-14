package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static pocketdungeons.RoomDsl.AIR;
import static pocketdungeons.RoomDsl.box;
import static pocketdungeons.RoomDsl.column;
import static pocketdungeons.RoomDsl.put;

/**
 * Gilded Seam rooms: a dig gallery where the seam shows in the cut faces, an
 * excavated pit that drops a full story into a worked out gallery, and the
 * mother seam chamber for the final floor. Authored at rotation 0 in room-local
 * coordinates; door lanes stay clear.
 */
final class GildedSeamSpecs {

    private GildedSeamSpecs() {}

    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.add(veinGallery());
        specs.add(debrisPit());
        specs.add(seamHeart());
        return specs;
    }

    /**
     * Vein Gallery (corner, west north): a dig gallery between two worked faces,
     * blackstone to the north and basalt to the south, gold and debris showing
     * in the cuts. A crimson scaffold and a dead rail line mark where the crew
     * worked.
     */
    private static RoomSpec veinGallery() {
        return new RoomSpec("vein_gallery", EnumSet.of(Direction.WEST, Direction.NORTH))
                .chests(new BlockPos(13, 1, 4))
                .spawns(new BlockPos(3, 1, 5), new BlockPos(12, 1, 5), new BlockPos(5, 1, 10),
                        new BlockPos(11, 1, 9))
                .decor((level, o) -> {
                    BlockState rock = Blocks.BLACKSTONE.defaultBlockState();
                    BlockState basalt = Blocks.BASALT.defaultBlockState();
                    BlockState gilded = Blocks.GILDED_BLACKSTONE.defaultBlockState();
                    BlockState goldOre = Blocks.NETHER_GOLD_ORE.defaultBlockState();
                    BlockState debris = Blocks.ANCIENT_DEBRIS.defaultBlockState();
                    BlockState planks = Blocks.CRIMSON_PLANKS.defaultBlockState();
                    BlockState lamp = Blocks.SHROOMLIGHT.defaultBlockState();
                    // The dig faces: blackstone against the north wall, basalt along the
                    // south, gold and debris showing in the cut.
                    box(level, o, 2, 1, 1, 4, 3, 2, rock);
                    put(level, o, 3, 2, 1, gilded);
                    put(level, o, 4, 1, 2, goldOre);
                    box(level, o, 10, 1, 13, 13, 3, 14, basalt);
                    put(level, o, 11, 2, 14, gilded);
                    put(level, o, 12, 1, 13, goldOre);
                    put(level, o, 13, 3, 14, debris);
                    // A pocket carved in the south face for the manifest's quartz node.
                    put(level, o, 10, 2, 13, AIR);
                    // A crimson scaffold and one loose plank.
                    box(level, o, 2, 1, 10, 3, 1, 11, planks);
                    put(level, o, 2, 2, 10, planks);
                    put(level, o, 12, 1, 11, planks);
                    // A dead rail line out of the west door that turns at the north face.
                    BlockState railEW = Blocks.RAIL.defaultBlockState()
                            .setValue(BlockStateProperties.RAIL_SHAPE, RailShape.EAST_WEST);
                    BlockState railNS = Blocks.RAIL.defaultBlockState()
                            .setValue(BlockStateProperties.RAIL_SHAPE, RailShape.NORTH_SOUTH);
                    for (int x = 1; x <= 5; x++) {
                        put(level, o, x, 1, 7, railEW);
                    }
                    put(level, o, 5, 1, 7, Blocks.RAIL.defaultBlockState()
                            .setValue(BlockStateProperties.RAIL_SHAPE, RailShape.NORTH_WEST));
                    put(level, o, 5, 1, 6, railNS);
                    put(level, o, 5, 1, 5, railNS);
                    // Rubble and two shroomlights.
                    put(level, o, 8, 1, 12, rock);
                    put(level, o, 9, 1, 12, rock);
                    put(level, o, 7, 5, 4, lamp);
                    put(level, o, 7, 5, 11, lamp);
                });
    }

    /**
     * Debris Pit (straight, west east, two stories): an excavated pit cut down
     * through the floor into a worked out gallery a story below. A ladder hangs
     * in a notch on the east wall; ore shows in the gallery walls through the
     * manifest's nodes and in the baked veins below.
     */
    private static RoomSpec debrisPit() {
        return new RoomSpec("debris_pit", EnumSet.of(Direction.WEST, Direction.EAST))
                .spanY(2)
                .chests(new BlockPos(13, 1, 4), new BlockPos(8, 1, 14))
                .spawns(new BlockPos(3, 1, 6), new BlockPos(12, 1, 4), new BlockPos(5, 1, 8),
                        new BlockPos(4, 1, 10))
                .decor((level, o) -> {
                    BlockState rock = Blocks.BLACKSTONE.defaultBlockState();
                    BlockState gilded = Blocks.GILDED_BLACKSTONE.defaultBlockState();
                    BlockState goldOre = Blocks.NETHER_GOLD_ORE.defaultBlockState();
                    BlockState quartz = Blocks.NETHER_QUARTZ_ORE.defaultBlockState();
                    BlockState planks = Blocks.CRIMSON_PLANKS.defaultBlockState();
                    BlockState lamp = Blocks.SHROOMLIGHT.defaultBlockState();
                    // The pit mouth: floor, filler and lower ceiling cut away over a
                    // 5x5 shaft; a ladder climbs the east wall inside a one block notch.
                    box(level, o, 9, 0, 9, 13, 0, 13, AIR);
                    box(level, o, 9, -1, 9, 13, -1, 13, AIR);
                    box(level, o, 9, -2, 9, 13, -2, 13, AIR);
                    BlockState ladder = Blocks.LADDER.defaultBlockState()
                            .setValue(LadderBlock.FACING, Direction.WEST);
                    for (int y = -7; y <= 1; y++) {
                        put(level, o, 14, y, 10, ladder);
                    }
                    // Ore baked into the pit's cut faces and the gallery walls below.
                    put(level, o, 14, -1, 11, goldOre);
                    put(level, o, 9, -1, 12, gilded);
                    put(level, o, 1, -5, 6, gilded);
                    put(level, o, 1, -6, 9, goldOre);
                    put(level, o, 14, -6, 5, quartz);
                    put(level, o, 10, -7, 5, rock);
                    // Props in the worked out gallery below.
                    column(level, o, 4, -7, -4, 4, rock);
                    column(level, o, 11, -7, -4, 4, rock);
                    put(level, o, 6, -7, 12, rock);
                    put(level, o, 5, -7, 12, rock);
                    put(level, o, 4, -7, 12, planks);
                    // The overburden heap beside the pit mouth.
                    box(level, o, 5, 1, 12, 6, 1, 13, rock);
                    put(level, o, 6, 2, 12, rock);
                    // A crimson plank walk along the pit's south rim and rim markers.
                    box(level, o, 9, 1, 14, 13, 1, 14, planks);
                    put(level, o, 9, 1, 8, planks);
                    put(level, o, 11, 1, 8, planks);
                    // The worked face on the north wall with gold in the cut.
                    box(level, o, 1, 1, 1, 4, 3, 2, rock);
                    put(level, o, 2, 2, 1, gilded);
                    put(level, o, 4, 1, 1, goldOre);
                    put(level, o, 3, 3, 2, quartz);
                    // Props and two shroomlights.
                    put(level, o, 2, 1, 4, planks);
                    put(level, o, 7, 5, 5, lamp);
                    put(level, o, 7, 5, 10, lamp);
                });
    }

    /**
     * Seam Heart (straight, west east): the mother seam chamber for the final
     * floor. A gilded monolith crowned with smithed gold stands at the heart,
     * gold veins streak the walls and basalt buttresses hold the corners.
     */
    private static RoomSpec seamHeart() {
        return new RoomSpec("seam_heart", EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(12, 1, 12))
                .spawns(new BlockPos(3, 1, 5), new BlockPos(12, 1, 5), new BlockPos(3, 1, 10),
                        new BlockPos(12, 1, 10))
                .decor((level, o) -> {
                    BlockState basalt = Blocks.BASALT.defaultBlockState();
                    BlockState gilded = Blocks.GILDED_BLACKSTONE.defaultBlockState();
                    BlockState gold = Blocks.GOLD_BLOCK.defaultBlockState();
                    BlockState goldOre = Blocks.NETHER_GOLD_ORE.defaultBlockState();
                    BlockState planks = Blocks.CRIMSON_PLANKS.defaultBlockState();
                    BlockState lamp = Blocks.SHROOMLIGHT.defaultBlockState();
                    // The mother vein: a gilded monolith crowned with smithed gold.
                    box(level, o, 7, 1, 7, 8, 3, 8, gilded);
                    box(level, o, 7, 4, 7, 8, 4, 8, gold);
                    // Gold veins streak the walls; the manifest tops them up with ore.
                    int[][] veins = {{1, 2, 3}, {1, 3, 4}, {14, 2, 4}, {14, 3, 5},
                            {14, 1, 11}, {14, 2, 12}, {2, 1, 14}, {3, 2, 14},
                            {11, 2, 1}, {12, 1, 1}, {1, 2, 10}, {1, 1, 11}};
                    for (int[] v : veins) {
                        put(level, o, v[0], v[1], v[2], gilded);
                    }
                    put(level, o, 2, 3, 14, goldOre);
                    put(level, o, 13, 2, 6, goldOre);
                    put(level, o, 2, 2, 6, goldOre);
                    // Basalt buttresses at the chamber corners.
                    for (int[] p : new int[][]{{2, 2}, {13, 2}, {2, 13}, {13, 13}}) {
                        column(level, o, p[0], 1, 4, p[1], basalt);
                    }
                    // A crimson floor ring marking off the heart.
                    for (int i = 6; i <= 9; i++) {
                        put(level, o, i, 0, 6, planks);
                        put(level, o, i, 0, 9, planks);
                        put(level, o, 6, 0, i, planks);
                        put(level, o, 9, 0, i, planks);
                    }
                    put(level, o, 5, 5, 5, lamp);
                    put(level, o, 10, 5, 5, lamp);
                    put(level, o, 5, 5, 10, lamp);
                    put(level, o, 10, 5, 10, lamp);
                });
    }
}
