package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static pocketdungeons.RoomTemplateGenerator.CELL;
import static pocketdungeons.RoomTemplateGenerator.HORIZONTALS;
import static pocketdungeons.RoomTemplateGenerator.QUAD_SPAWNS;
import static pocketdungeons.RoomTemplateGenerator.WALL_HEIGHT;
import static pocketdungeons.RoomTemplateGenerator.column;
import static pocketdungeons.RoomTemplateGenerator.concat;
import static pocketdungeons.RoomTemplateGenerator.floorRing;
import static pocketdungeons.RoomTemplateGenerator.placeWallLodestone;

/**
 * M45: the room library as it stood before the situations round, moved out of
 * {@code RoomTemplateGenerator.specs()} verbatim.
 *
 * <p>The coverage floor (one template per door-mask family, each carrying all
 * three content roles), the two single-role end caps, and the flavour layer.
 * The anomaly set stays on {@code RoomTemplateGenerator}: it is selected by a
 * different path and is not part of this concatenation.
 *
 * <p>Nothing here changed in the move. The catalogue families add their rooms
 * in their own files instead, so no two template milestones ever open the same
 * one.
 */
final class BaseRoomSpecs {

    private BaseRoomSpecs() {}

    /** The base library, in the order it was authored. */
    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();

        // --- end caps: single-door, single-role. One template each covers all
        //     four single-door masks by rotation, which is only true because the
        //     graph generator holds the entrance and terminal cells to one door.

        // No mob: the entrance is where a party arrives and regroups, and where
        // the void guard bounces a falling player back to. It stays safe.
        // Selector doors belong in the staging room, not the entrance cell.
        specs.add(new RoomSpec("entrance_hall", EnumSet.of(Direction.SOUTH))
                .decor((level, o) -> {
                    placeWallLodestone(level, o);
                }));

        specs.add(new RoomSpec("exit_hall", EnumSet.of(Direction.WEST)).exitPad());

        // --- coverage floor: every mask family, all three content roles.

        specs.add(new RoomSpec("hall_dead_end", EnumSet.of(Direction.NORTH))
                .chests(new BlockPos(2, 1, 2))
                .spawns(new BlockPos(4, 1, 11), new BlockPos(11, 1, 11))
                .decor((level, o) -> {
                    // Decorative back wall, opposite the only door.
                    for (int x = 1; x <= CELL - 2; x++) {
                        for (int y = 1; y <= WALL_HEIGHT; y++) {
                            RoomBuilder.set(level, o.offset(x, y, CELL - 1),
                                    Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
                        }
                    }
                }));

        specs.add(new RoomSpec("hall_straight", EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(2, 1, 2))
                .spawns(QUAD_SPAWNS)
                .decor((level, o) -> {
                    // Half-height pillars either side of the through-route: they
                    // break the sightline without closing either doorway, which is
                    // two blocks wide.
                    column(level, o, 4, 1, 3, 7, Blocks.STONE_BRICK_WALL.defaultBlockState());
                    column(level, o, 11, 1, 3, 8, Blocks.STONE_BRICK_WALL.defaultBlockState());
                }));

        specs.add(new RoomSpec("hall_corner", EnumSet.of(Direction.NORTH, Direction.EAST))
                .chests(new BlockPos(2, 1, 2))
                .spawns(QUAD_SPAWNS)
                .decor((level, o) -> floorRing(level, o,
                        Blocks.CHISELED_STONE_BRICKS.defaultBlockState())));

        specs.add(new RoomSpec("hall_tee",
                EnumSet.of(Direction.NORTH, Direction.EAST, Direction.SOUTH))
                .chests(new BlockPos(2, 1, 2))
                .spawns(QUAD_SPAWNS)
                .decor((level, o) -> {
                    // Shelf along the doorless west wall, split around the lane so
                    // the room stays correct if a west door is ever authored in.
                    BlockState slab = Blocks.STONE_BRICK_SLAB.defaultBlockState();
                    for (int z = 1; z <= CELL - 2; z++) {
                        if (z >= RoomGeometry.DOOR_MIN - 1 && z <= RoomGeometry.DOOR_MAX + 1) {
                            continue;
                        }
                        RoomBuilder.set(level, o.offset(1, 2, z), slab);
                    }
                }));

        specs.add(new RoomSpec("hall_cross", HORIZONTALS)
                .chests(new BlockPos(2, 1, 2))
                .spawns(concat(QUAD_SPAWNS, new BlockPos(2, 1, 5), new BlockPos(13, 1, 10)))
                .decor((level, o) -> {
                    column(level, o, 5, 1, WALL_HEIGHT, 5, RoomBuilder.WALL);
                    column(level, o, 10, 1, WALL_HEIGHT, 5, RoomBuilder.WALL);
                    column(level, o, 5, 1, WALL_HEIGHT, 10, RoomBuilder.WALL);
                    column(level, o, 10, 1, WALL_HEIGHT, 10, RoomBuilder.WALL);
                }));

        // --- flavour layer: same shapes, higher weight, themed.

        specs.add(new RoomSpec("encounter_zombie", EnumSet.of(Direction.WEST, Direction.EAST))
                .spawns(QUAD_SPAWNS));

        // The chest moves off (8,1,2): that sits squarely in the north doorway
        // lane, harmless only for as long as the room is never rotated.
        specs.add(new RoomSpec("loot_vault", EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(2, 1, 2)));

        specs.add(new RoomSpec("crypt_corner", EnumSet.of(Direction.NORTH, Direction.EAST))
                .spawns(QUAD_SPAWNS)
                .decor((level, o) -> {
                    BlockState lid = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
                    for (int x = 3; x <= 5; x++) {
                        for (int z = 11; z <= 12; z++) {
                            RoomBuilder.set(level, o.offset(x, 1, z), lid);
                        }
                    }
                    BlockState web = Blocks.COBWEB.defaultBlockState();
                    RoomBuilder.set(level, o.offset(1, 1, 13), web);
                    RoomBuilder.set(level, o.offset(1, 2, 14), web);
                    RoomBuilder.set(level, o.offset(2, 1, 14), web);
                    RoomBuilder.set(level, o.offset(14, 1, 13), web);
                }));

        specs.add(new RoomSpec("treasure_alcove", EnumSet.of(Direction.NORTH))
                .chests(new BlockPos(2, 1, 2), new BlockPos(13, 1, 2))
                .decor((level, o) -> {
                    RoomBuilder.set(level, o.offset(8, 0, 12),
                            Blocks.GOLD_BLOCK.defaultBlockState());
                    RoomBuilder.set(level, o.offset(7, 0, 12),
                            Blocks.GOLD_BLOCK.defaultBlockState());
                }));

        specs.add(new RoomSpec("pillar_cross", HORIZONTALS)
                .spawns(concat(QUAD_SPAWNS, new BlockPos(2, 1, 5), new BlockPos(13, 1, 10)))
                .decor((level, o) -> {
                    BlockState deepslate = Blocks.DEEPSLATE_BRICKS.defaultBlockState();
                    column(level, o, 5, 1, WALL_HEIGHT, 5, deepslate);
                    column(level, o, 10, 1, WALL_HEIGHT, 5, deepslate);
                    column(level, o, 5, 1, WALL_HEIGHT, 10, deepslate);
                    column(level, o, 10, 1, WALL_HEIGHT, 10, deepslate);
                    RoomBuilder.set(level, o.offset(5, 0, 5), RoomBuilder.LAMP);
                    RoomBuilder.set(level, o.offset(10, 0, 5), RoomBuilder.LAMP);
                    RoomBuilder.set(level, o.offset(5, 0, 10), RoomBuilder.LAMP);
                    RoomBuilder.set(level, o.offset(10, 0, 10), RoomBuilder.LAMP);
                }));

        // Named for the look, not for water: a flooded room would need source
        // blocks that spread out through the doorways into the neighbouring cell,
        // and a contained cistern buys nothing a moss floor does not.
        specs.add(new RoomSpec("mossy_tee",
                EnumSet.of(Direction.NORTH, Direction.EAST, Direction.SOUTH))
                .spawns(QUAD_SPAWNS)
                .decor((level, o) -> {
                    BlockState mossy = Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
                    for (int x = 4; x <= 11; x++) {
                        for (int z = 4; z <= 11; z++) {
                            RoomBuilder.set(level, o.offset(x, 0, z), mossy);
                        }
                    }
                    for (int y = 1; y <= WALL_HEIGHT; y++) {
                        RoomBuilder.set(level, o.offset(1, y, 1), mossy);
                        RoomBuilder.set(level, o.offset(14, y, 14), mossy);
                    }
                }));

        specs.add(new RoomSpec("spawner_den", EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(2, 1, 12))
                .spawner(new BlockPos(8, 1, 8))
                .decor((level, o) -> {
                    // Classic vanilla-dungeon plinth. Dead centre blocks only one
                    // of the two doorway columns, so the room is still walkable.
                    RoomBuilder.set(level, o.offset(8, 0, 8),
                            Blocks.MOSSY_COBBLESTONE.defaultBlockState());
                    for (int x = 6; x <= 10; x++) {
                        for (int z = 6; z <= 10; z++) {
                            if (x == 8 && z == 8) {
                                continue;
                            }
                            RoomBuilder.set(level, o.offset(x, 0, z),
                                    Blocks.MOSSY_COBBLESTONE.defaultBlockState());
                        }
                    }
                }));

        // The authoring reference from M1: an empty sealed box with all four
        // doors and nothing else. Never selected, no metadata file.
        specs.add(new RoomSpec("jig_room", HORIZONTALS));

        return specs;
    }
}
