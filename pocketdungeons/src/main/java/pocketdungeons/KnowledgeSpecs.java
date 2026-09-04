package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static pocketdungeons.RoomTemplateGenerator.CELL;
import static pocketdungeons.RoomTemplateGenerator.QUAD_SPAWNS;

/**
 * M52: the knowledge family's room templates (spec 4.3, 4.4) and the situation
 * handlers that bring them to life at stamp time.
 *
 * <p>Seven knowledge rooms (4.3): Bazaar, Don't Look, The Herd, Deep Dark
 * Landing, Elder's Chamber, Blaze Loft, Infested Wall. Five of these use
 * {@link RoomContent#spawnMobs} through a registered Situations handler; the
 * other two (Deep Dark Landing, Infested Wall) are template only.
 *
 * <p>Seven combat variant rooms (4.4): Breeze Arena, Bogged Marsh, Ledge
 * Archers, The Raid, Slime Pit, Wither Loft, Creeper Kennel. Each carries a
 * classic spawner in its template (the {@code spawner} field on RoomSpec) and a
 * Situations handler that calls {@link TrialContent#applyEncounter} with a
 * room-specific config prefix and the cell's {@code gated} flag. The handler
 * owns the cell, so the spawner anchor is not collected by LayoutStamper;
 * instead it is tracked in {@link TrialContent}'s situation-spawner map for the
 * clear-gate filter.
 *
 * <p>All rooms are authored at rotation 0 with WEST as the entrance and EAST as
 * the exit, matching the convention from {@code MechanismSpecs}. The doorway
 * lane rule (x in [7,8] clear for z in [1,3] and [12,14]; z in [7,8] clear for x
 * in [1,3] and [12,14]) is honoured throughout.
 */
final class KnowledgeSpecs {

    private KnowledgeSpecs() {}

    private static final Direction ENTRANCE = Direction.WEST;
    private static final Direction EXIT = Direction.EAST;
    private static final int WALL_X = CELL - 1;
    private static final int CEILING_Y = RoomGeometry.CEILING_Y;

    /** The four ceiling-lamp positions placed by {@link RoomBuilder#stampShell}. */
    private static final int[][] LAMPS = {{4, 4}, {4, 11}, {11, 4}, {11, 11}};

    /** The knowledge and combat family's templates. */
    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        // Knowledge (spec 4.3)
        specs.add(bazaar());
        specs.add(dontLook());
        specs.add(theHerd());
        specs.add(deepDarkLanding());
        specs.add(eldersChamber());
        specs.add(blazeLoft());
        specs.add(infestedWall());
        // Combat variants (spec 4.4)
        specs.add(breezeArena());
        specs.add(boggedMarsh());
        specs.add(ledgeArchers());
        specs.add(theRaid());
        specs.add(slimePit());
        specs.add(witherLoft());
        specs.add(creeperKennel());
        return specs;
    }

    // ---- Situations handler registration -----------------------------------

    private static boolean handlersRegistered;

    /**
     * Registers the Situations handlers for the knowledge and combat rooms.
     * Called once from {@link TrialContent#warmUp()} on server start. The flag
     * guards against double registration on a server restart within the same
     * JVM.
     */
    static void registerHandlers() {
        if (handlersRegistered) {
            return;
        }
        handlersRegistered = true;

        // Knowledge rooms that use spawnMobs (spec 4.3).
        Situations.register("bazaar", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) ->
                RoomContent.spawnMobs(level, o, EntityTypes.PIGLIN, 8, spawns, seed,
                        entity -> {
                            if (entity instanceof Piglin piglin) {
                                piglin.setImmuneToZombification(true);
                            }
                        }));
        Situations.register("dont_look", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) ->
                RoomContent.spawnMobs(level, o, EntityTypes.ENDERMAN, 4, spawns, seed, null));
        Situations.register("the_herd", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) ->
                RoomContent.spawnMobs(level, o, EntityTypes.ZOMBIFIED_PIGLIN, 12, spawns, seed, null));
        Situations.register("elders_chamber", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) ->
                RoomContent.spawnMobs(level, o, EntityTypes.ELDER_GUARDIAN, 1, spawns, seed, null));
        Situations.register("blaze_loft", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) ->
                RoomContent.spawnMobs(level, o, EntityTypes.BLAZE, 3, spawns, seed, null));

        // Combat rooms (spec 4.4). Each handler places a trial spawner with a
        // room-specific config and records the cell's gated flag.
        Situations.register("breeze_arena", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) ->
                TrialContent.applyEncounter(level, o, spawns, profile.lootTier(), affixes,
                        "breeze_arena", true));
        Situations.register("bogged_marsh", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) ->
                TrialContent.applyEncounter(level, o, spawns, profile.lootTier(), affixes,
                        "bogged_marsh", false));
        Situations.register("ledge_archers", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) ->
                TrialContent.applyEncounter(level, o, spawns, profile.lootTier(), affixes,
                        "ledge_archers", false));
        Situations.register("the_raid", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) ->
                TrialContent.applyEncounter(level, o, spawns, profile.lootTier(), affixes,
                        "the_raid", true));
        Situations.register("slime_pit", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) ->
                TrialContent.applyEncounter(level, o, spawns, profile.lootTier(), affixes,
                        "slime_pit", false));
        Situations.register("wither_loft", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) ->
                TrialContent.applyEncounter(level, o, spawns, profile.lootTier(), affixes,
                        "wither_loft", true));
        Situations.register("creeper_kennel", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) ->
                TrialContent.applyEncounter(level, o, spawns, profile.lootTier(), affixes,
                        "creeper_kennel", false));
    }

    // ---- shared helpers -----------------------------------------------------

    /**
     * Replaces the four ceiling lamps (sea lantern plus four stairs each) with
     * a solid block, making the room dark. Used by Don't Look and Deep Dark
     * Landing where light would spoil the room's point.
     */
    private static void removeLamps(ServerLevel level, BlockPos o, BlockState replacement) {
        for (int[] lp : LAMPS) {
            int lx = lp[0], lz = lp[1];
            RoomBuilder.set(level, o.offset(lx, CEILING_Y, lz), replacement);
            RoomBuilder.set(level, o.offset(lx + 1, CEILING_Y, lz), replacement);
            RoomBuilder.set(level, o.offset(lx - 1, CEILING_Y, lz), replacement);
            RoomBuilder.set(level, o.offset(lx, CEILING_Y, lz + 1), replacement);
            RoomBuilder.set(level, o.offset(lx, CEILING_Y, lz - 1), replacement);
        }
    }

    /**
     * Fills the top row (y=3) of the exit doorway with wall, leaving y=1..2 as
     * jigsaw blocks so the stamper opens a 2-block-high gap instead of the full
     * 3. Used by Don't Look: an enderman (3 tall) cannot path through, a
     * crouching player can.
     */
    private static void fillDoorwayTop(ServerLevel level, BlockPos o) {
        for (int z = RoomGeometry.DOOR_MIN; z <= RoomGeometry.DOOR_MAX; z++) {
            RoomBuilder.set(level, o.offset(WALL_X, 3, z), RoomBuilder.WALL);
        }
    }

    /**
     * Fills the entire exit doorway (y=1..3, z=7..8) with {@code material},
     * sealing it. The jigsaw blocks are overwritten so the stamper does not open
     * the doorway. Used by gated knowledge rooms (Elder's Chamber, Infested
     * Wall) whose gate is a soft wall the player mines through.
     */
    private static void sealExitDoorway(ServerLevel level, BlockPos o, BlockState material) {
        for (int y = 1; y <= RoomGeometry.DOOR_HEIGHT; y++) {
            for (int z = RoomGeometry.DOOR_MIN; z <= RoomGeometry.DOOR_MAX; z++) {
                RoomBuilder.set(level, o.offset(WALL_X, y, z), material);
            }
        }
    }

    /** A decorated pot at {@code pos} with {@code item} inside, for Infested Wall. */
    private static void placePot(ServerLevel level, BlockPos pos, ItemStack item) {
        RoomBuilder.set(level, pos, Blocks.DECORATED_POT.defaultBlockState());
        if (item != null) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof DecoratedPotBlockEntity pot) {
                pot.setTheItem(item.copy());
                pot.setChanged();
            }
        }
    }

    // ---- 1. Bazaar (tier 2, open) ------------------------------------------

    /**
     * Eight piglins around a table of gold blocks. Exit door open behind them.
     * The handler spawns piglins with {@code IsImmuneToZombification} set; this
     * dimension is not the Nether. {@code provides: ["gold"]}.
     */
    private static RoomSpec bazaar() {
        return new RoomSpec("bazaar", EnumSet.of(ENTRANCE, EXIT))
                .spawns(new BlockPos(4, 1, 4), new BlockPos(4, 1, 8), new BlockPos(4, 1, 12),
                        new BlockPos(8, 1, 5), new BlockPos(8, 1, 11),
                        new BlockPos(12, 1, 4), new BlockPos(12, 1, 6), new BlockPos(12, 1, 12))
                .decor((level, o) -> {
                    for (int x = 7; x <= 9; x++) {
                        for (int z = 7; z <= 9; z++) {
                            RoomBuilder.set(level, o.offset(x, 0, z),
                                    Blocks.GOLD_BLOCK.defaultBlockState());
                        }
                    }
                });
    }

    // ---- 2. Don't Look (tier 2, open) --------------------------------------

    /**
     * Pitch dark. Four endermen. The exit is a two-block-high gap that an
     * enderman cannot path through. Tinted glass window so light does not leak.
     */
    private static RoomSpec dontLook() {
        return new RoomSpec("dont_look", EnumSet.of(ENTRANCE, EXIT))
                .spawns(QUAD_SPAWNS)
                .decor((level, o) -> {
                    removeLamps(level, o, RoomBuilder.CEILING);
                    fillDoorwayTop(level, o);
                });
    }

    // ---- 3. The Herd (tier 1, open) ----------------------------------------

    /**
     * Twelve zombified piglins milling about. Gold blocks sunk into the floor
     * among the herd (audit 2.9): a player who mines quietly carries
     * {@code gold} forward. {@code provides: ["gold"]}.
     */
    private static RoomSpec theHerd() {
        return new RoomSpec("the_herd", EnumSet.of(ENTRANCE, EXIT))
                .spawns(QUAD_SPAWNS)
                .decor((level, o) -> {
                    RoomBuilder.set(level, o.offset(6, 0, 6), Blocks.GOLD_BLOCK.defaultBlockState());
                    RoomBuilder.set(level, o.offset(10, 0, 10), Blocks.GOLD_BLOCK.defaultBlockState());
                    RoomBuilder.set(level, o.offset(6, 0, 10), Blocks.GOLD_BLOCK.defaultBlockState());
                    RoomBuilder.set(level, o.offset(10, 0, 6), Blocks.GOLD_BLOCK.defaultBlockState());
                });
    }

    // ---- 4. Deep Dark Landing (tier 3, open) -------------------------------

    /**
     * Sculk floor, four sensors, one shrieker by the exit, dark. Tinted glass
     * window so light does not leak in. The shrieker has {@code can_summon:
     * true}. {@code pressure: omen}. Template only, no handler.
     */
    private static RoomSpec deepDarkLanding() {
        return new RoomSpec("deep_dark_landing", EnumSet.of(ENTRANCE, EXIT))
                .decor((level, o) -> {
                    removeLamps(level, o, Blocks.SCULK.defaultBlockState());
                    for (int x = 2; x < CELL - 2; x++) {
                        for (int z = 2; z < CELL - 2; z++) {
                            RoomBuilder.set(level, o.offset(x, 0, z),
                                    Blocks.SCULK.defaultBlockState());
                        }
                    }
                    RoomBuilder.set(level, o.offset(4, 1, 4),
                            Blocks.SCULK_SENSOR.defaultBlockState());
                    RoomBuilder.set(level, o.offset(11, 1, 4),
                            Blocks.SCULK_SENSOR.defaultBlockState());
                    RoomBuilder.set(level, o.offset(4, 1, 11),
                            Blocks.SCULK_SENSOR.defaultBlockState());
                    RoomBuilder.set(level, o.offset(11, 1, 11),
                            Blocks.SCULK_SENSOR.defaultBlockState());
                    RoomBuilder.set(level, o.offset(13, 1, 8),
                            Blocks.SCULK_SHRIEKER.defaultBlockState()
                                    .setValue(net.minecraft.world.level.block.SculkShriekerBlock.CAN_SUMMON, true));
                });
    }

    // ---- 5. Elder's Chamber (tier 3, gated) --------------------------------

    /**
     * A flooded room with an elder guardian. Mining Fatigue III the moment you
     * enter. The exit is bricked with a one-block soft wall of gravel (audit
     * 2.11: hand-breakable, still punishing under Fatigue III). Glass window
     * for water containment. {@code provides: ["water"]}.
     */
    private static RoomSpec eldersChamber() {
        return new RoomSpec("elders_chamber", EnumSet.of(ENTRANCE, EXIT))
                .spawns(new BlockPos(8, 1, 8))
                .decor((level, o) -> {
                    for (int x = 2; x < CELL - 2; x++) {
                        for (int z = 2; z < CELL - 2; z++) {
                            RoomBuilder.set(level, o.offset(x, 1, z),
                                    Blocks.WATER.defaultBlockState());
                        }
                    }
                    sealExitDoorway(level, o, Blocks.GRAVEL.defaultBlockState());
                });
    }

    // ---- 6. Blaze Loft (tier 2, open) --------------------------------------

    /**
     * Three blazes on a ledge, lava below the ledge, exit under them. Lava at
     * y=0 (audit 4.2), kerbed (audit 4.3). {@code provides: ["lava"]}.
     */
    private static RoomSpec blazeLoft() {
        return new RoomSpec("blaze_loft", EnumSet.of(ENTRANCE, EXIT))
                .spawns(new BlockPos(11, 4, 7), new BlockPos(11, 4, 9), new BlockPos(12, 4, 8))
                .decor((level, o) -> {
                    // Lava pool at y=0 near the east wall, 4x4.
                    for (int x = 10; x <= 13; x++) {
                        for (int z = 6; z <= 9; z++) {
                            RoomBuilder.set(level, o.offset(x, 0, z),
                                    Blocks.LAVA.defaultBlockState());
                        }
                    }
                    // Kerb: stone brick wall around the lava at y=1, open on
                    // the east side so the player can pass under the ledge.
                    for (int z = 5; z <= 10; z++) {
                        RoomBuilder.set(level, o.offset(9, 1, z), RoomBuilder.WALL);
                    }
                    for (int x = 10; x <= 13; x++) {
                        RoomBuilder.set(level, o.offset(x, 1, 5), RoomBuilder.WALL);
                        RoomBuilder.set(level, o.offset(x, 1, 10), RoomBuilder.WALL);
                    }
                    // Ledge at y=3 above the lava for the blazes to stand on.
                    for (int x = 10; x <= 13; x++) {
                        for (int z = 6; z <= 9; z++) {
                            RoomBuilder.set(level, o.offset(x, 3, z), RoomBuilder.WALL);
                        }
                    }
                });
    }

    // ---- 7. Infested Wall (tier 2, gated) ----------------------------------

    /**
     * The exit is bricked over with stone bricks, some infested. A stone
     * pickaxe in a pot (audit 2.10) supplies the tool that breaks the wall.
     * {@code provides: ["blocks"]}. Template only, no handler.
     */
    private static RoomSpec infestedWall() {
        BlockState normal = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState infested = Blocks.INFESTED_STONE_BRICKS.defaultBlockState();
        return new RoomSpec("infested_wall", EnumSet.of(ENTRANCE, EXIT))
                .decor((level, o) -> {
                    for (int y = 1; y <= RoomGeometry.DOOR_HEIGHT; y++) {
                        for (int z = RoomGeometry.DOOR_MIN; z <= RoomGeometry.DOOR_MAX; z++) {
                            RoomBuilder.set(level, o.offset(WALL_X, y, z),
                                    y == 1 ? infested : normal);
                        }
                    }
                    placePot(level, o.offset(4, 1, 4), new ItemStack(Items.STONE_PICKAXE));
                });
    }

    // ---- 8. Breeze Arena (tier 2, gated) -----------------------------------

    /**
     * Platforms over lava; knockback is the danger. Spawner: breeze x2.
     * {@code provides: ["wind_charge", "lava"]}.
     */
    private static RoomSpec breezeArena() {
        return new RoomSpec("breeze_arena", EnumSet.of(ENTRANCE, EXIT))
                .spawner(new BlockPos(8, 1, 8))
                .decor((level, o) -> {
                    // Lava floor in the centre, platforms around the edges.
                    for (int x = 5; x <= 10; x++) {
                        for (int z = 5; z <= 10; z++) {
                            RoomBuilder.set(level, o.offset(x, 0, z),
                                    Blocks.LAVA.defaultBlockState());
                        }
                    }
                    // Stone brick platforms at the corners and sides.
                    BlockState plat = Blocks.STONE_BRICKS.defaultBlockState();
                    for (int z = 2; z <= 13; z++) {
                        if (z >= 5 && z <= 10) continue;
                        RoomBuilder.set(level, o.offset(7, 0, z), plat);
                        RoomBuilder.set(level, o.offset(8, 0, z), plat);
                    }
                    for (int x = 2; x <= 13; x++) {
                        if (x >= 5 && x <= 10) continue;
                        RoomBuilder.set(level, o.offset(x, 0, 7), plat);
                        RoomBuilder.set(level, o.offset(x, 0, 8), plat);
                    }
                });
    }

    // ---- 9. Bogged Marsh (tier 2, open) ------------------------------------

    /**
     * Mud floor (audit 4.3: mud does not flow, slows the same). Poison arrows.
     * Spawner: bogged x3.
     */
    private static RoomSpec boggedMarsh() {
        return new RoomSpec("bogged_marsh", EnumSet.of(ENTRANCE, EXIT))
                .spawner(new BlockPos(8, 1, 8))
                .decor((level, o) -> {
                    BlockState mud = Blocks.MUD.defaultBlockState();
                    for (int x = 2; x < CELL - 2; x++) {
                        for (int z = 2; z < CELL - 2; z++) {
                            RoomBuilder.set(level, o.offset(x, 0, z), mud);
                        }
                    }
                });
    }

    // ---- 10. Ledge Archers (tier 1, open) ----------------------------------

    /**
     * Skeletons behind iron bars, 3 up. Iron bars contain them; running past is
     * the intended fight. Spawner: skeleton x4.
     */
    private static RoomSpec ledgeArchers() {
        return new RoomSpec("ledge_archers", EnumSet.of(ENTRANCE, EXIT))
                .spawner(new BlockPos(8, 4, 8))
                .decor((level, o) -> {
                    // Ledge at y=3 on the north and south sides.
                    BlockState plat = Blocks.STONE_BRICKS.defaultBlockState();
                    for (int x = 4; x <= 11; x++) {
                        RoomBuilder.set(level, o.offset(x, 3, 4), plat);
                        RoomBuilder.set(level, o.offset(x, 3, 11), plat);
                    }
                    // Iron bars at y=4..5 in front of the ledges.
                    BlockState bars = Blocks.IRON_BARS.defaultBlockState();
                    for (int x = 4; x <= 11; x++) {
                        RoomBuilder.set(level, o.offset(x, 4, 5), bars);
                        RoomBuilder.set(level, o.offset(x, 5, 5), bars);
                        RoomBuilder.set(level, o.offset(x, 4, 10), bars);
                        RoomBuilder.set(level, o.offset(x, 5, 10), bars);
                    }
                });
    }

    // ---- 11. The Raid (tier 3, gated) --------------------------------------

    /**
     * Vindicator x3, evoker x1. Totem of undying is the loot. Spawner config:
     * vindicator (weight 3), evoker (weight 1).
     */
    private static RoomSpec theRaid() {
        return new RoomSpec("the_raid", EnumSet.of(ENTRANCE, EXIT))
                .spawner(new BlockPos(8, 1, 8))
                .decor((level, o) -> {
                    // Dark oak theme: pillars and a coarse dirt floor patch.
                    BlockState pillar = Blocks.DARK_OAK_LOG.defaultBlockState();
                    RoomTemplateGenerator.column(level, o, 4, 1, RoomGeometry.WALL_HEIGHT, 4, pillar);
                    RoomTemplateGenerator.column(level, o, 11, 1, RoomGeometry.WALL_HEIGHT, 4, pillar);
                    RoomTemplateGenerator.column(level, o, 4, 1, RoomGeometry.WALL_HEIGHT, 11, pillar);
                    RoomTemplateGenerator.column(level, o, 11, 1, RoomGeometry.WALL_HEIGHT, 11, pillar);
                });
    }

    // ---- 12. Slime Pit (tier 1, open) --------------------------------------

    /**
     * Slime block floor. The bounce reaches a ledge the room's REWARD is on,
     * not the door (audit 4.6: doors are always at floor level). Spawner:
     * slime x4.
     */
    private static RoomSpec slimePit() {
        return new RoomSpec("slime_pit", EnumSet.of(ENTRANCE, EXIT))
                .spawner(new BlockPos(8, 1, 8))
                .decor((level, o) -> {
                    BlockState slime = Blocks.SLIME_BLOCK.defaultBlockState();
                    for (int x = 3; x <= 12; x++) {
                        for (int z = 3; z <= 12; z++) {
                            RoomBuilder.set(level, o.offset(x, 0, z), slime);
                        }
                    }
                    // Ledge at y=3 on the north side, where the reward sits.
                    BlockState plat = Blocks.STONE_BRICKS.defaultBlockState();
                    for (int x = 5; x <= 10; x++) {
                        RoomBuilder.set(level, o.offset(x, 3, 3), plat);
                        RoomBuilder.set(level, o.offset(x, 2, 3), plat);
                    }
                });
    }

    // ---- 13. Wither Loft (tier 3, gated) -----------------------------------

    /**
     * Wither effect. Spawner: wither skeleton x3. The Innkeeper bag pays off.
     */
    private static RoomSpec witherLoft() {
        return new RoomSpec("wither_loft", EnumSet.of(ENTRANCE, EXIT))
                .spawner(new BlockPos(8, 1, 8))
                .decor((level, o) -> {
                    // Soul sand floor and soul fire lanterns for atmosphere.
                    BlockState soulSand = Blocks.SOUL_SAND.defaultBlockState();
                    for (int x = 3; x <= 12; x++) {
                        for (int z = 3; z <= 12; z++) {
                            RoomBuilder.set(level, o.offset(x, 0, z), soulSand);
                        }
                    }
                    RoomBuilder.set(level, o.offset(4, 1, 4),
                            Blocks.SOUL_LANTERN.defaultBlockState());
                    RoomBuilder.set(level, o.offset(11, 1, 11),
                            Blocks.SOUL_LANTERN.defaultBlockState());
                });
    }

    // ---- 14. Creeper Kennel (tier 2, open) ---------------------------------

    /**
     * Creepers behind a fence gate, none spawn free. Not a fight, a tool. Open
     * the gate and steer one at the soft wall. Audit 2.8: does NOT declare
     * {@code provides: mob} (creepers are not leashable). Spawner: creeper x3.
     */
    private static RoomSpec creeperKennel() {
        return new RoomSpec("creeper_kennel", EnumSet.of(ENTRANCE, EXIT))
                .spawner(new BlockPos(8, 1, 8))
                .decor((level, o) -> {
                    // Enclosure of oak fence around the spawner, with a fence
                    // gate on the entrance side so the player can open it.
                    BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
                    BlockState gate = Blocks.OAK_FENCE_GATE.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.FenceGateBlock.FACING, Direction.EAST);
                    // North and south walls of the enclosure.
                    for (int x = 6; x <= 9; x++) {
                        RoomBuilder.set(level, o.offset(x, 1, 6), fence);
                        RoomBuilder.set(level, o.offset(x, 1, 10), fence);
                    }
                    // West wall with a gate at the centre.
                    RoomBuilder.set(level, o.offset(6, 1, 7), fence);
                    RoomBuilder.set(level, o.offset(6, 1, 8), gate);
                    RoomBuilder.set(level, o.offset(6, 1, 9), fence);
                    // East wall.
                    RoomBuilder.set(level, o.offset(9, 1, 7), fence);
                    RoomBuilder.set(level, o.offset(9, 1, 8), fence);
                    RoomBuilder.set(level, o.offset(9, 1, 9), fence);
                });
    }
}
