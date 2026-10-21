package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.block.Blocks;

/**
 * Owner ask 2026-10-19: a telegraphed area attack marks its tiles and lands on a delay -- a burst
 * that hits only whoever is still inside, and a standing zone whose effect lets go the moment a
 * member steps out. The stand-ins are free-will-less cows: mock players join invulnerable, and the
 * attack's "who is inside" answer needs a body that can actually be hurt.
 */
public final class AoETelegraphGameTest {

    private static final int WARN_TICKS = 20;

    /** A burst telegraph waits out its warning, lands once, and misses whoever stepped out. */
    @GameTest(maxTicks = 80)
    public void aDetonationWaitsItsWarningAndHitsOnlyWhoStaysInside(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = flatFloor(helper, level);
        // spawnWithNoFreeWill takes the position relative to the test structure, not absolute.
        Cow stays = helper.spawnWithNoFreeWill(EntityTypes.COW, new BlockPos(0, 1, 0));
        Cow leaves = helper.spawnWithNoFreeWill(EntityTypes.COW, new BlockPos(0, 1, 0));
        AoETelegraph.clear(origin); // no telegraph may bleed in from a previous test
        AoETelegraph.detonate(level, null, origin, new AoEShapes.Circle(2), WARN_TICKS,
                AoETelegraph.damage(4.0f));
        helper.onEachTick(() -> {
            if (helper.getTick() == WARN_TICKS - 4) {
                helper.assertTrue(stays.getHealth() == stays.getMaxHealth()
                                && leaves.getHealth() == leaves.getMaxHealth(),
                        "the warning has not landed yet");
                // One body reads the mark and steps out before it lands.
                leaves.teleportTo(origin.getX() + 4.5, origin.getY() + 1, origin.getZ() + 0.5);
            }
            if (helper.getTick() < WARN_TICKS + 8) {
                return;
            }
            helper.assertTrue(stays.getHealth() < stays.getMaxHealth(),
                    "the burst lands on whoever is still inside");
            helper.assertTrue(leaves.getHealth() == leaves.getMaxHealth(),
                    "the body that stepped out is safe");
            helper.assertValueEqual(AoETelegraph.liveCount(origin), 0, "a resolved telegraph is spent");
            helper.succeed();
        });
    }

    /** A standing zone freezes whoever is in it and lets go the moment they step out. */
    @GameTest(maxTicks = 140)
    public void aZoneFreezesWhileYouStandInItAndLetsGoWhenYouLeave(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = flatFloor(helper, level);
        Cow body = helper.spawnWithNoFreeWill(EntityTypes.COW, new BlockPos(0, 1, 0));
        AoETelegraph.clear(origin);
        // 5 ticks of warning, 60 ticks live, freeze(40) every 10 ticks: a tick inside keeps the
        // freeze topped up, a tick outside lets vanilla thaw it at two a tick.
        AoETelegraph.zone(level, null, origin, new AoEShapes.Circle(2), 5, 60, 10,
                AoETelegraph.freeze(40));
        boolean[] left = {false};
        helper.onEachTick(() -> {
            // The zone's first pulse lands ~15 ticks after arming (warning + one interval).
            if (helper.getTick() == 20 && !left[0]) {
                helper.assertTrue(body.getTicksFrozen() > 0, "standing in the zone freezes");
                body.teleportTo(origin.getX() + 4.5, origin.getY() + 1, origin.getZ() + 0.5);
                left[0] = true;
            }
            if (helper.getTick() == 55) {
                helper.assertValueEqual(body.getTicksFrozen(), 0, "stepping out lets the freeze go");
            }
            if (helper.getTick() < 75) {
                return;
            }
            helper.assertValueEqual(AoETelegraph.liveCount(origin), 0, "the zone ends on its duration");
            helper.succeed();
        });
    }

    /** A 5x5 stone floor a tile deep, centred on the returned tile, under four blocks of air. */
    private static BlockPos flatFloor(GameTestHelper helper, ServerLevel level) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlock(origin.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 3);
                for (int dy = 1; dy <= 4; dy++) {
                    level.setBlock(origin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        return origin;
    }
}
