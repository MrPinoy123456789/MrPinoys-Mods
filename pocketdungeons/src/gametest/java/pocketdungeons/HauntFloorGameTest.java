package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The haunt floor objective (act 3-4 floor types) and its HUNT haunt, The Keeper: two beacons stand in the
 * floor's branch cells, a member standing on a plate lights its beacon after {@code beaconChargeSeconds},
 * every lit beacon banishes the keeper for {@code beaconBanishSeconds}, and the pad opens when the quota is
 * lit. The keeper itself wakes a grace after the floor opens, stalks the party whether seen or not, and
 * cannot be killed or stunlocked: a hit staggers it, and a lost body means it comes back.
 *
 * <p>No lighthouse dungeon JSON exists yet, so the record is built by hand, its objective is pinned through
 * {@link FloorObjectives#registerForTesting}, and the hunt definition through {@link Haunts#hauntForTesting}
 * (the {@link ClaimFloorGameTest} way: the pieces are driven directly rather than a real descent, which the
 * gametest world cannot stand up). The record stays out of the live slot registry, so the watch loop never
 * touches it.
 *
 * <p>A keeper strikes on every floor: the sighting and near-miss rungs of the stage ladder are the
 * stalker's, and a toothless keeper gives a beacon floor no reason to burn. The approach windows stand the
 * member in the entrance cell, which a keeper never acts in, so the movement and stagger asserts cannot be
 * interrupted by a reach; the strike itself gets its own beat at the end.
 */
public final class HauntFloorGameTest {

    private static final int SLOT = 9985;
    private static final int BANISH_SLOT = 9986;
    private static final int FALLBACK_SLOT = 9987;
    private static final String TEST_DUNGEON = "pocketdungeons:test_haunt";

    /** Entrance, mid (critical path), terminal, and one branch cell off the path. */
    private static final PlanCell ENTRANCE = new PlanCell(0, 0);
    private static final PlanCell MID = new PlanCell(1, 0);
    private static final PlanCell BRANCH = new PlanCell(1, 1);
    private static final PlanCell TERMINAL = new PlanCell(2, 0);

    /** The Keeper definition the test pins through the haunt seam (the shipped file walks lighthouse_below). */
    private static final HauntDefinition KEEPER = new HauntDefinition("pocketdungeons:the_keeper",
            "The Keeper", Set.of(TEST_DUNGEON), 0, HauntDefinition.Behaviour.HUNT,
            "minecraft:drowned", "minecraft:sculk_soul", "minecraft:entity.drowned.ambient",
            "It only stops while the lamps burn.", "");

    /**
     * The rig: four cells on a stone floor high above the test arena (entrance, mid, terminal and a branch
     * off the path), with the chunks forced so the keeper's body really ticks. {@code record.interval.path}
     * stays empty, so {@link Haunts#floorNumber} reads one: a sighting-stage floor, which proves the keeper
     * strikes on every floor, not just the deep ones.
     */
    private static final class Rig {
        final GameTestHelper helper;
        final ServerLevel level;
        final BlockPos origin;
        final int slot;
        final PlanGeometry geometry;
        final InstanceRecord record;
        final ServerPlayer player;
        final List<int[]> forced = new ArrayList<>();

        Rig(GameTestHelper helper, int slot, int zShift, List<PlanCell> cells,
                PlanCell entrance, PlanCell terminal) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.slot = slot;
            BlockPos base = helper.absolutePos(BlockPos.ZERO);
            this.origin = new BlockPos(base.getX() + 6500, base.getY() + 30, base.getZ() + zShift);
            for (int cx = origin.getX() >> 4; cx <= (origin.getX() + 64) >> 4; cx++) {
                for (int cz = origin.getZ() >> 4; cz <= (origin.getZ() + 48) >> 4; cz++) {
                    level.setChunkForced(cx, cz, true);
                    forced.add(new int[]{cx, cz});
                }
            }
            this.geometry = PlanGeometry.of(origin, cells);
            prepareCells();
            @SuppressWarnings("removal")
            ServerPlayer mock = helper.makeMockServerPlayerInLevel();
            this.player = mock;
            player.setGameMode(GameType.SURVIVAL);
            standAt(geometry.cellCentre(MID));
            InstanceLayout layout = new InstanceLayout(origin, geometry, geometry.cellCentre(entrance),
                    0.0f, geometry.cellCentre(terminal), geometry.bounds(), 9100L + slot,
                    cells.size() - 1, cells.size(), 1, true, Set.of(), 3, geometry.cellOrigin(terminal),
                    0, 0, Set.of(), null, Set.of());
            this.record = new InstanceRecord(slot, origin, level.getServer().getTickCount(),
                    layout, Set.of(), player.getUUID(), false);
            record.interval.dungeonId = TEST_DUNGEON;
            record.interval.nodeId = "keeper_floor";
            record.phase = RunSession.Phase.ACTIVE;
            record.members.put(player.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
            Haunts.registerForTesting(level, record);
            Haunts.hauntForTesting(TEST_DUNGEON, KEEPER);
            FloorObjectives.registerForTesting(record, FloorObjective.HAUNT);
        }

        /** A stone floor under every cell and air above, so the keeper and the beacons can stand. */
        private void prepareCells() {
            for (BlockPos cellOrigin : geometry.cellOrigins()) {
                for (int x = 0; x < RoomGeometry.CELL; x++) {
                    for (int z = 0; z < RoomGeometry.CELL; z++) {
                        level.setBlock(cellOrigin.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
                        for (int y = 1; y <= 6; y++) {
                            level.setBlock(cellOrigin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                        }
                    }
                }
            }
        }

        /** The cell a plan position sits in, or {@code null}; used to say which cell a beacon went to. */
        PlanCell cellOf(BlockPos pos) {
            for (PlanCell cell : geometry.cells()) {
                if (CellGeometry.insideAnyCell(pos, List.of(geometry.cellOrigin(cell)))) {
                    return cell;
                }
            }
            return null;
        }

        /** Stands the member with its feet inside {@code pos}'s block (a plate, a cell centre). */
        void standAt(BlockPos pos) {
            player.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, Set.of(),
                    0.0f, 0.0f, false);
        }

        /** Ages the floor far enough that the hunt grace has already run. */
        void graceElapsed() {
            MinecraftServer server = level.getServer();
            record.floor.startedAtTick = server.overworld().getGameTime()
                    - PocketDungeonsConfig.hauntHuntGraceSeconds() * 20L - 1L;
        }

        /** The live keeper body, or {@code null}. */
        Mob body() {
            Entity body = Haunts.hasBody(slot) ? level.getEntity(Haunts.bodyOf(slot)) : null;
            return body instanceof Mob mob ? mob : null;
        }

        void finish() {
            Mob body = body();
            if (body != null) {
                body.discard();
            }
            Haunts.forgetForTesting(record);
            FloorObjectives.forgetForTesting(record);
            for (int[] chunk : forced) {
                level.setChunkForced(chunk[0], chunk[1], false);
            }
        }
    }

    /**
     * The whole keeper beat: beacons arm in the branch and mid cells, the pad counts them down, the keeper
     * wakes only after the grace, it advances whether the member looks or not, a hit staggers instead of
     * hurting it, a second hit inside the immunity window does not, and a lost body means it comes back.
     */
    @GameTest(maxTicks = 400)
    public void theKeeperStalksAndTheBeaconsHoldThePad(GameTestHelper helper) {
        Rig rig = new Rig(helper, SLOT, 0, List.of(ENTRANCE, MID, BRANCH, TERMINAL), ENTRANCE, TERMINAL);
        helper.runAfterDelay(25, () -> runStalk(helper, rig));
    }

    private static void runStalk(GameTestHelper helper, Rig rig) {
        try {
            MinecraftServer server = rig.level.getServer();
            FloorObjectives.arm(rig.level, rig.record, plan(List.of(ENTRANCE, MID, BRANCH, TERMINAL)), null);
            helper.assertTrue(rig.record.floor.objective instanceof HauntObjective,
                    "a haunt floor arms a haunt state");
            HauntObjective state = (HauntObjective) rig.record.floor.objective;
            helper.assertTrue(!state.fallback, "the beacons placed on this floor");

            // 1. Two beacons in two different cells, the branch first, never entrance or terminal.
            helper.assertValueEqual(state.beacons.size(), 2, "two beacons were placed");
            helper.assertValueEqual(state.quota, 2, "the quota is both beacons");
            helper.assertTrue(!state.beacons.get(0).plate.equals(state.beacons.get(1).plate),
                    "the beacons stand apart");
            helper.assertTrue(!rig.cellOf(state.beacons.get(0).plate)
                            .equals(rig.cellOf(state.beacons.get(1).plate)),
                    "each beacon is in its own cell");
            helper.assertValueEqual(rig.cellOf(state.beacons.get(0).plate), BRANCH,
                    "the branch cell takes the first beacon");
            for (HauntObjective.Beacon beacon : state.beacons) {
                helper.assertTrue(rig.level.getBlockState(beacon.plate)
                                .is(Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE),
                        "a beacon plate at " + beacon.plate.toShortString());
                helper.assertTrue(rig.level.getBlockState(beacon.bulb).getBlock()
                                instanceof CopperBulbBlock
                                && !rig.level.getBlockState(beacon.bulb).getValue(CopperBulbBlock.LIT),
                        "a beacon bulb is unlit at " + beacon.bulb.toShortString());
                helper.assertTrue(FloorObjectives.isFixture(beacon.plate), "a plate is a fixture");
                helper.assertTrue(FloorObjectives.isFixture(beacon.bulb), "a bulb is a fixture");
                PlanCell cell = rig.cellOf(beacon.plate);
                helper.assertTrue(cell != null && !cell.equals(ENTRANCE) && !cell.equals(TERMINAL),
                        "no beacon in the entrance or the pad room");
            }

            // 2. The pad holds on the dark lamp line; the bar counts the beacons.
            helper.assertValueEqual(FloorObjectives.padRefusal(rig.record),
                    "The lamp is dark. Stand on 2 more beacon plates.", "the pad wants both beacons");
            helper.assertValueEqual(FloorObjectives.objectiveLine(rig.record), "Beacons 0/2",
                    "the bar reads the beacon count");

            // 3. The keeper sleeps through the grace and wakes once it has run.
            rig.record.floor.startedAtTick = server.overworld().getGameTime();
            Haunts.tick(server, rig.record);
            helper.assertTrue(!Haunts.hasBody(rig.slot), "the keeper does not wake inside the grace");
            rig.graceElapsed();
            Haunts.tick(server, rig.record);
            Mob body = rig.body();
            helper.assertTrue(body != null, "the keeper wakes once the grace has run");
            helper.assertTrue(body.getType() == EntityTypes.DROWNED, "the keeper wears its drowned body");
            helper.assertTrue(body.entityTags().contains(Haunts.TAG), "the keeper is tagged as a haunt");
            helper.assertTrue("The Keeper".equals(body.getCustomName().getString()),
                    "the keeper wears its name");

            // 4. Looked at or not, it comes on. Put it east of the member, who stares at it (yaw -90 is +x).
            // The member stands in the entrance cell: a keeper never acts on a member there, so a reach can
            // only freeze it and the movement and stagger asserts cannot be interrupted by a strike.
            body.teleportTo(rig.origin.getX() + 24.5, rig.origin.getY() + 1.0, rig.origin.getZ() + 8.5);
            rig.player.teleportTo(rig.level, rig.origin.getX() + 8.5, rig.origin.getY() + 1.0,
                    rig.origin.getZ() + 8.5, Set.of(), -90.0f, 0.0f, false);
            helper.runAfterDelay(4, () -> {
                try {
                    helper.assertTrue(Haunts.seenByAny(List.of(rig.player), body),
                            "the member looks straight at the keeper");
                    double seen = body.position().distanceTo(rig.player.position());
                    helper.runAfterDelay(20, () -> {
                        try {
                            helper.assertTrue(body.position().distanceTo(rig.player.position()) < seen - 0.4,
                                    "the keeper advances while seen: " + seen + " to "
                                            + body.position().distanceTo(rig.player.position()));
                            // 5. A hit never lands: it staggers, then the immunity window forbids a re-stagger.
                            // Park it east in the pad cell first, out of the member's reach: a keeper frozen
                            // at the entrance line would keep its noAi after the stagger and read the same.
                            body.teleportTo(rig.origin.getX() + 40.5, rig.origin.getY() + 1.0,
                                    rig.origin.getZ() + 8.5);
                            float health = body.getHealth();
                            body.hurtServer(rig.level, rig.level.damageSources().generic(), 6.0f);
                            helper.assertValueEqual(body.getHealth(), health,
                                    "a hit on the keeper is cancelled");
                            helper.assertTrue(body.isNoAi(), "a hit staggers the keeper");
                            helper.runAfterDelay(PocketDungeonsConfig.hauntStunSeconds() * 20L + 8L, () -> {
                                try {
                                    helper.assertTrue(!body.isNoAi(), "the keeper walks once the stagger ends");
                                    body.hurtServer(rig.level, rig.level.damageSources().generic(), 6.0f);
                                    helper.assertValueEqual(body.getHealth(), health,
                                            "the second hit is still cancelled");
                                    helper.assertTrue(!body.isNoAi(),
                                            "the immunity window forbids a second stagger");
                                    // 6. Looked away (yaw 90 is -x), it still comes; and a lost body is
                                    // never the end of it. Park the keeper east again so it has room to walk.
                                    body.teleportTo(rig.origin.getX() + 38.5, rig.origin.getY() + 1.0,
                                            rig.origin.getZ() + 8.5);
                                    rig.player.teleportTo(rig.level, rig.origin.getX() + 8.5,
                                            rig.origin.getY() + 1.0, rig.origin.getZ() + 8.5,
                                            Set.of(), 90.0f, 0.0f, false);
                                    double unseen = body.position().distanceTo(rig.player.position());
                                    helper.runAfterDelay(20, () -> {
                                        try {
                                            helper.assertTrue(!Haunts.seenByAny(List.of(rig.player), body),
                                                    "the member has looked away");
                                            helper.assertTrue(
                                                    body.position().distanceTo(rig.player.position())
                                                            < unseen - 0.4,
                                                    "the keeper advances unseen too");
                                            body.discard();
                                            Haunts.tick(server, rig.record);
                                            helper.assertTrue(!Haunts.hasBody(rig.slot),
                                                    "a lost body leaves the state waiting");
                                            // Back to an open cell: a keeper only wakes for a member it
                                            // may reach, and the strike beat needs one.
                                            rig.standAt(rig.geometry.cellCentre(MID));
                                            Haunts.banishFor(rig.record, 0);
                                            Haunts.tick(server, rig.record);
                                            helper.assertTrue(Haunts.hasBody(rig.slot),
                                                    "the keeper is never spent: it comes back");

                                            // 7. The keeper is the floor's threat: on an open cell it
                                            // reaches the member and strikes. A strike hurts and slows
                                            // but can never kill, and the body draws back after it.
                                            // Check once, past the tell: a per-tick poll here mass
                                            // schedules into runAtTickTimeMap mid-iteration and can
                                            // re-run this same lambda, so the wait is a single delay.
                                            Mob returned = rig.body();
                                            helper.assertTrue(returned != null,
                                                    "the returning keeper is out");
                                            // The mock reports itself creative whatever the mode says;
                                            // strip its abilities so the strike can land.
                                            rig.player.getAbilities().instabuild = false;
                                            rig.player.getAbilities().invulnerable = false;
                                            float before = rig.player.getHealth();
                                            returned.teleportTo(rig.player.getX() + 4.0,
                                                    rig.origin.getY() + 1.0, rig.player.getZ());
                                            helper.runAfterDelay(
                                                    PocketDungeonsConfig.hauntTellSeconds() * 20L + 80L,
                                                    () -> {
                                                        try {
                                                            helper.assertTrue(returned.isRemoved(),
                                                                    "the keeper reached the member and struck");
                                                            helper.assertTrue(
                                                                    rig.player.getHealth() < before,
                                                                    "a strike hurts: " + before + " to "
                                                                            + rig.player.getHealth());
                                                            helper.assertTrue(
                                                                    rig.player.getHealth() >= 1.0f,
                                                                    "a strike can never kill");
                                                            helper.assertTrue(
                                                                    rig.player.hasEffect(
                                                                            MobEffects.SLOWNESS),
                                                                    "a strike slows");
                                                            rig.finish();
                                                            helper.succeed();
                                                        } catch (RuntimeException failure) {
                                                            rig.finish();
                                                            throw failure;
                                                        }
                                                    });
                                        } catch (RuntimeException failure) {
                                            rig.finish();
                                            throw failure;
                                        }
                                    });
                                } catch (RuntimeException failure) {
                                    rig.finish();
                                    throw failure;
                                }
                            });
                        } catch (RuntimeException failure) {
                            rig.finish();
                            throw failure;
                        }
                    });
                } catch (RuntimeException failure) {
                    rig.finish();
                    throw failure;
                }
            });
        } catch (RuntimeException failure) {
            rig.finish();
            throw failure;
        }
    }

    /**
     * Standing on a plate charges its beacon: the bulb lights, the count rises, the keeper is driven off,
     * and the last beacon opens the pad.
     */
    @GameTest(maxTicks = 300)
    public void lightingTheBeaconsBanishesTheKeeperAndOpensThePad(GameTestHelper helper) {
        Rig rig = new Rig(helper, BANISH_SLOT, 300, List.of(ENTRANCE, MID, BRANCH, TERMINAL),
                ENTRANCE, TERMINAL);
        helper.runAfterDelay(25, () -> runBeacons(helper, rig));
    }

    private static void runBeacons(GameTestHelper helper, Rig rig) {
        try {
            MinecraftServer server = rig.level.getServer();
            FloorObjectives.arm(rig.level, rig.record, plan(List.of(ENTRANCE, MID, BRANCH, TERMINAL)), null);
            HauntObjective state = (HauntObjective) rig.record.floor.objective;
            helper.assertTrue(state != null && !state.fallback && state.beacons.size() == 2,
                    "two beacons armed");

            // The keeper is already out, but parked east past the terminal cell on a laid strip, far
            // enough away that it cannot reach the member charging the first plate.
            for (int x = 48; x < 64; x++) {
                for (int z = 0; z < 16; z++) {
                    rig.level.setBlock(rig.origin.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
                    for (int y = 1; y <= 6; y++) {
                        rig.level.setBlock(rig.origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
            rig.graceElapsed();
            Haunts.tick(server, rig.record);
            Mob body = rig.body();
            helper.assertTrue(body != null, "the keeper is out for the banish");
            body.teleportTo(rig.origin.getX() + 58.5, rig.origin.getY() + 1.0, rig.origin.getZ() + 8.5);
            // A cancelled hit parks it for the charge window: a keeper free to walk could reach the
            // member and strike before the beacon lights, which is not what the banish assert proves.
            body.hurtServer(rig.level, rig.level.damageSources().generic(), 6.0f);
            helper.assertTrue(body.isNoAi(), "the hit parks the keeper for the charge");

            // 1. Stand on the first plate: the charge fills, the bulb lights, the keeper goes.
            rig.standAt(state.beacons.get(0).plate);
            FloorObjectives.tick(server, rig.record);
            helper.assertTrue(state.beacons.get(0).chargingSince >= 0, "the charge has started");
            helper.runAfterDelay(PocketDungeonsConfig.beaconChargeSeconds() * 20L + 4L, () -> {
                try {
                    FloorObjectives.tick(server, rig.record);
                    helper.assertTrue(state.beacons.get(0).lit, "the charged beacon is lit");
                    helper.assertTrue(rig.level.getBlockState(state.beacons.get(0).bulb)
                                    .getValue(CopperBulbBlock.LIT), "the bulb burns");
                    helper.assertValueEqual(state.beaconsLit, 1, "one beacon is lit");
                    helper.assertTrue(body.isRemoved(), "the lit beacon drove the keeper off");
                    helper.assertTrue(!Haunts.hasBody(rig.slot), "the director holds no body");
                    helper.assertValueEqual(FloorObjectives.padRefusal(rig.record),
                            "The lamp is dark. Stand on 1 more beacon plate.", "one beacon to go");
                    helper.assertValueEqual(FloorObjectives.objectiveLine(rig.record), "Beacons 1/2",
                            "the bar reads one lit");

                    // 2. The second plate charges the same way; then the pad opens.
                    rig.standAt(state.beacons.get(1).plate);
                    FloorObjectives.tick(server, rig.record);
                    helper.runAfterDelay(PocketDungeonsConfig.beaconChargeSeconds() * 20L + 4L, () -> {
                        try {
                            FloorObjectives.tick(server, rig.record);
                            helper.assertTrue(state.beacons.get(1).lit, "the second beacon is lit");
                            helper.assertValueEqual(state.beaconsLit, 2, "both beacons are lit");
                            helper.assertTrue(FloorObjectives.padRefusal(rig.record) == null,
                                    "the pad is open");
                            helper.assertValueEqual(FloorObjectives.objectiveLine(rig.record),
                                    "Beacons 2/2", "the bar reads full");
                            rig.finish();
                            helper.succeed();
                        } catch (RuntimeException failure) {
                            rig.finish();
                            throw failure;
                        }
                    });
                } catch (RuntimeException failure) {
                    rig.finish();
                    throw failure;
                }
            });
        } catch (RuntimeException failure) {
            rig.finish();
            throw failure;
        }
    }

    /**
     * A floor with room for one beacon is held to a quota of one; a floor with room for none falls back to
     * an ordinary spawner floor.
     */
    @GameTest(maxTicks = 60)
    public void aFloorWithNoBeaconRoomFallsBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<PlanCell> cells = List.of(ENTRANCE, MID, TERMINAL);
        PlanGeometry geometry = PlanGeometry.of(helper.absolutePos(BlockPos.ZERO), cells);
        for (BlockPos cellOrigin : geometry.cellOrigins()) {
            for (int x = 0; x < RoomGeometry.CELL; x++) {
                for (int z = 0; z < RoomGeometry.CELL; z++) {
                    level.setBlock(cellOrigin.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
                }
            }
        }
        InstanceLayout layout = new InstanceLayout(helper.absolutePos(BlockPos.ZERO), geometry,
                geometry.cellCentre(ENTRANCE), 0.0f, geometry.cellCentre(TERMINAL), geometry.bounds(),
                9200L, cells.size() - 1, cells.size(), 1, true, Set.of(), 3, geometry.cellOrigin(TERMINAL),
                0, 0, Set.of(), null, Set.of());
        InstanceRecord record = new InstanceRecord(FALLBACK_SLOT, helper.absolutePos(BlockPos.ZERO),
                level.getServer().getTickCount(), layout, Set.of(), UUID.randomUUID(), false);
        FloorObjectives.registerForTesting(record, FloorObjective.HAUNT);
        try {
            FloorObjectives.arm(level, record, plan(cells), null);
            HauntObjective state = (HauntObjective) record.floor.objective;
            helper.assertTrue(state != null && !state.fallback, "the one open cell still arms a beacon");
            helper.assertValueEqual(state.beacons.size(), 1, "only the mid cell could host a beacon");
            helper.assertValueEqual(state.quota, 1, "the quota drops to what was placed");
            helper.assertValueEqual(FloorObjectives.padRefusal(record),
                    "The lamp is dark. Stand on 1 more beacon plate.", "a short floor wants one beacon");
            helper.assertValueEqual(FloorObjectives.objectiveLine(record), "Beacons 0/1",
                    "the bar reads the reduced quota");

            // Fill the mid cell solid at beacon height: nowhere left for even a plate.
            for (int x = 0; x < RoomGeometry.CELL; x++) {
                for (int z = 0; z < RoomGeometry.CELL; z++) {
                    for (int y = 1; y <= 2; y++) {
                        level.setBlock(geometry.cellOrigin(MID).offset(x, y, z),
                                Blocks.STONE.defaultBlockState(), 3);
                    }
                }
            }
            InstanceRecord second = new InstanceRecord(FALLBACK_SLOT + 10, helper.absolutePos(BlockPos.ZERO),
                    level.getServer().getTickCount(), layout, Set.of(), UUID.randomUUID(), false);
            FloorObjectives.registerForTesting(second, FloorObjective.HAUNT);
            try {
                FloorObjectives.arm(level, second, plan(cells), null);
                HauntObjective empty = (HauntObjective) second.floor.objective;
                helper.assertTrue(empty != null && empty.fallback, "no beacon spot means the fallback floor");
                helper.assertTrue(empty.beacons.isEmpty() && empty.fixtures.isEmpty(),
                        "a fallback floor places nothing");
                helper.assertTrue(FloorObjectives.padRefusal(second) == null,
                        "a fallback floor never refuses the pad");
                helper.assertTrue(FloorObjectives.isFallback(second), "the record reads fallback");
                helper.succeed();
            } finally {
                FloorObjectives.forgetForTesting(second);
            }
        } finally {
            FloorObjectives.forgetForTesting(record);
        }
    }

    private static DungeonPlan plan(List<PlanCell> cells) {
        return new DungeonPlan(1L, Set.copyOf(cells), Map.of(), Map.of(), Map.of(), Set.of(),
                ENTRANCE, TERMINAL, List.of(ENTRANCE, MID, TERMINAL), null);
    }
}
