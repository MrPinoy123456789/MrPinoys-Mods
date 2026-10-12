package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Haunts (RM T34): The Counted on the Ossuary. The rig is a five cell corridor high above the test arena
 * (entrance, three open cells, terminal) with a wall across the middle of the second cell, a gap at its north
 * edge, and a mock member. The gametest world has no dungeon dimension, so the director and the body tick run
 * through {@link Haunts#registerForTesting}.
 * <ul>
 *   <li>{@code theCountedWakesOnFloorThreeFreezesWhenLookedAtAndGoesWithTheFloor}: pressure wakes the body on
 *       floor 3 out of sight, it holds still while looked at, walks once the member looks away, and the floor
 *       ending removes it.</li>
 *   <li>{@code aStrikeHurtsAndSlowsButNeverKillsAndTheCountedRetreats}: the strike is small, leaves one health,
 *       slows, and the body goes.</li>
 *   <li>{@code hauntedFloorsSayTheLineAndKeepOutOfTheEntranceAndPadRooms}: the title line, the floor ladder and
 *       the excluded rooms.</li>
 * </ul>
 */
public final class HauntGameTest {

    private static final String OSSUARY = "pocketdungeons:ossuary";

    /** The corridor rig: cells 0 to 4 east of {@code origin}, cell 0 the entrance and cell 4 the terminal. */
    private static final class Rig {
        final GameTestHelper helper;
        final ServerLevel level;
        final BlockPos origin;
        final int slot;
        final InstanceRecord record;
        final ServerPlayer player;
        final List<int[]> forced = new ArrayList<>();

        Rig(GameTestHelper helper, int slot, int zShift, String nodeId) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.slot = slot;
            BlockPos base = helper.absolutePos(BlockPos.ZERO);
            this.origin = new BlockPos(base.getX() + 6000, base.getY() + 30, base.getZ() + zShift);
            for (int cx = origin.getX() >> 4; cx <= (origin.getX() + 80) >> 4; cx++) {
                for (int cz = origin.getZ() >> 4; cz <= (origin.getZ() + 16) >> 4; cz++) {
                    level.setChunkForced(cx, cz, true);
                    forced.add(new int[]{cx, cz});
                }
            }
            build();
            @SuppressWarnings("removal")
            ServerPlayer mock = helper.makeMockServerPlayerInLevel();
            this.player = mock;
            player.setGameMode(GameType.SURVIVAL);
            face(18.5, 8.5, -90.0f);
            List<PlanCell> cells = new ArrayList<>();
            for (int x = 0; x < 5; x++) {
                cells.add(new PlanCell(x, 0));
            }
            PlanGeometry geometry = PlanGeometry.of(origin, cells);
            BlockPos terminal = geometry.cellOrigin(new PlanCell(4, 0));
            InstanceLayout layout = new InstanceLayout(origin, geometry, geometry.cellCentre(new PlanCell(0, 0)), 0.0f,
                    terminal.offset(8, 1, 8), geometry.bounds(), 7700L + slot, 4, 5, 1, true, Set.of(), 4, terminal,
                    0, 0, Set.of(), null, Set.of());
            MinecraftServer server = level.getServer();
            this.record = new InstanceRecord(slot, origin, server.getTickCount(), layout, Set.of(),
                    player.getUUID(), false);
            record.interval.dungeonId = OSSUARY;
            record.interval.nodeId = nodeId;
            record.phase = RunSession.Phase.ACTIVE;
            record.members.put(player.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
            Haunts.registerForTesting(level, record);
        }

        /** A stone floor, a wall across the second cell at x 28 with a gap on its north edge, air above. */
        private void build() {
            for (int x = 0; x < 80; x++) {
                for (int z = 0; z < 16; z++) {
                    level.setBlock(origin.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
                    for (int y = 1; y <= 6; y++) {
                        boolean wall = x == 28 && z >= 2;
                        level.setBlock(origin.offset(x, y, z), wall ? Blocks.STONE.defaultBlockState()
                                : Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }

        /** Stands the member at (x, z) in cell terms from the rig origin, facing {@code yaw}. */
        void face(double x, double z, float yaw) {
            player.setGameMode(GameType.SURVIVAL);
            player.teleportTo(level, origin.getX() + x, origin.getY() + 1.0, origin.getZ() + z, Set.of(), yaw, 0.0F,
                    false);
        }

        /** Runs the director until a body stands, up to {@code limit} watch ticks; returns the body or null. */
        Mob wake(int limit) {
            for (int i = 0; i < limit && !Haunts.hasBody(slot); i++) {
                Haunts.tick(level.getServer(), record);
            }
            Entity body = Haunts.hasBody(slot) ? level.getEntity(Haunts.bodyOf(slot)) : null;
            return body instanceof Mob mob ? mob : null;
        }

        void finish() {
            Haunts.forgetForTesting(record);
            for (int[] chunk : forced) {
                level.setChunkForced(chunk[0], chunk[1], false);
            }
        }
    }

    @GameTest(maxTicks = 320)
    public void theCountedWakesOnFloorThreeFreezesWhenLookedAtAndGoesWithTheFloor(GameTestHelper helper) {
        Rig rig = new Rig(helper, 9201, 0, "last_rest");
        // Let the forced chunks settle into entity ticking before a body is added to them.
        helper.runAfterDelay(25, () -> runFreeze(helper, rig));
    }

    private static void runFreeze(GameTestHelper helper, Rig rig) {
        try {
            helper.assertTrue(HauntManifest.current().forDungeon(OSSUARY) != null, "The Counted is loaded for the Ossuary");
            helper.assertTrue(Haunts.stageOf(rig.record) == HauntRules.Stage.STRIKE, "floor 3 is the strike stage");
            helper.assertTrue(!Haunts.hasBody(rig.slot), "nothing walks before the pressure builds");
            Mob body = rig.wake(PocketDungeonsConfig.hauntWakePressure() * 3);
            helper.assertTrue(body != null, "pressure on floor 3 wakes a body");
            helper.assertTrue(body.entityTags().contains(Haunts.TAG), "the body is tagged as a haunt");
            helper.assertTrue("The Counted".equals(body.getCustomName().getString()), "the body wears the name");
            helper.assertTrue(!Haunts.seenByAny(List.of(rig.player), body), "it wakes out of sight");
            BlockPos at = body.blockPosition();
            helper.assertTrue(!Haunts.excluded(rig.record, at), "it does not wake in an excluded room");

            // Put it dead ahead of the member, in the open, and look at it.
            rig.face(18.5, 8.5, -90.0f);
            body.teleportTo(rig.origin.getX() + 24.5, rig.origin.getY() + 1.0, rig.origin.getZ() + 8.5);
            helper.runAfterDelay(4, () -> {
                try {
                    helper.assertTrue(Haunts.seenByAny(List.of(rig.player), body), "the member looks straight at it");
                    Vec3 held = body.position();
                    helper.runAfterDelay(30, () -> {
                        try {
                            helper.assertTrue(body.position().distanceTo(held) < 0.05,
                                    "it does not move while looked at: " + held + " to " + body.position());
                            helper.assertTrue(body.isNoAi(), "it is held, not wandering");
                            // Look away: it comes.
                            rig.face(18.5, 8.5, 90.0f);
                            Vec3 before = body.position();
                            helper.runAfterDelay(16, () -> {
                                try {
                                    helper.assertTrue(!Haunts.seenByAny(List.of(rig.player), body),
                                            "the member looks away");
                                    helper.assertTrue(body.position().distanceTo(before) > 0.8,
                                            "it walks once nobody looks: " + before + " to " + body.position());
                                    // The floor ends: it goes with it.
                                    CapstoneFights.floorEnded(rig.level, rig.record);
                                    helper.assertTrue(body.isRemoved(), "the body is gone when the floor ends");
                                    helper.assertTrue(!Haunts.hasBody(rig.slot), "the director holds no body");
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
    }

    @GameTest(maxTicks = 260)
    public void aStrikeHurtsAndSlowsButNeverKillsAndTheCountedRetreats(GameTestHelper helper) {
        Rig rig = new Rig(helper, 9202, 400, "last_rest");
        helper.runAfterDelay(25, () -> runStrike(helper, rig));
    }

    private static void runStrike(GameTestHelper helper, Rig rig) {
        try {
            Mob body = rig.wake(PocketDungeonsConfig.hauntWakePressure() * 3);
            helper.assertTrue(body != null, "pressure on floor 3 wakes a body");
            // Two health points left: the strike can take one at most. The body starts behind the member.
            rig.player.setGameMode(GameType.SURVIVAL);
            // The mock reports itself creative whatever the mode says; strip its abilities so the strike can land.
            rig.player.getAbilities().instabuild = false;
            rig.player.getAbilities().invulnerable = false;
            rig.player.setHealth(2.0f);
            rig.face(18.5, 8.5, 90.0f);
            body.teleportTo(rig.origin.getX() + 24.5, rig.origin.getY() + 1.0, rig.origin.getZ() + 8.5);
            UUID id = body.getUUID();
            helper.runAfterDelay(120, () -> {
                try {
                    helper.assertTrue(rig.player.isAlive(), "a strike never kills");
                    helper.assertTrue(rig.player.getHealth() >= 1.0f, "a strike leaves at least one health");
                    helper.assertTrue(rig.player.hasEffect(MobEffects.SLOWNESS), "the strike slows; body "
                            + body.position() + " player " + rig.player.position() + " creative " + rig.player.isCreative() + " inv " + rig.player.isInvulnerableTo(rig.level, rig.level.damageSources().generic())
                            + " health " + rig.player.getHealth() + " noAi " + body.isNoAi()
                            + " alive " + body.isAlive() + " hasBody " + Haunts.hasBody(rig.slot)
                            + " seen " + Haunts.seenByAny(java.util.List.of(rig.player), body));
                    helper.assertTrue(!Haunts.hasBody(rig.slot), "the body retreats after a strike");
                    helper.assertTrue(rig.player.getHealth() == 1.0f,
                            "the strike took one of the two health: " + rig.player.getHealth());
                    Entity gone = rig.level.getEntity(id);
                    helper.assertTrue(gone == null || gone.isRemoved(), "the retreated body is not left standing");
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
    }

    @GameTest(maxTicks = 40)
    public void hauntedFloorsSayTheLineAndKeepOutOfTheEntranceAndPadRooms(GameTestHelper helper) {
        Rig rig = new Rig(helper, 9203, 800, "skull_galleries");
        try {
            helper.assertTrue("Something walks here.".equals(Haunts.titleLine(rig.record)),
                    "a haunted floor adds the title line");
            helper.assertTrue(Haunts.stageOf(rig.record) == HauntRules.Stage.NEAR_MISS, "floor 2 is the near miss stage");
            rig.record.interval.nodeId = "charnel_steps";
            helper.assertTrue(Haunts.stageOf(rig.record) == HauntRules.Stage.SIGHTING, "floor 1 is a sighting");
            rig.record.interval.dungeonId = "pocketdungeons:cow_pits";
            helper.assertTrue(Haunts.titleLine(rig.record) == null, "a dungeon without a haunt adds no line");
            helper.assertTrue(Haunts.hauntOf(rig.record) == null, "a dungeon without a haunt has none");
            rig.record.interval.dungeonId = OSSUARY;
            BlockPos cell1 = rig.origin.offset(16 + 8, 1, 8);
            helper.assertTrue(Haunts.excluded(rig.record, rig.origin.offset(8, 1, 8)), "the entrance room is excluded");
            helper.assertTrue(Haunts.excluded(rig.record, rig.origin.offset(64 + 8, 1, 8)), "the pad room is excluded");
            helper.assertTrue(!Haunts.excluded(rig.record, cell1), "an ordinary room is open to it");
            helper.assertTrue(Haunts.excluded(rig.record, rig.origin.offset(-40, 1, 8)), "outside the layout is excluded");
            rig.finish();
            helper.succeed();
        } catch (RuntimeException failure) {
            rig.finish();
            throw failure;
        }
    }
}
