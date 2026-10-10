package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The Warden whelp walks at its target and reaches melee range (on the first live build it stood still: a
 * Warden made without a dig cooldown digs in at once). A villager stands in for the player, because the
 * gametest mock player is creative and no mob targets a creative player.
 */
public final class WhelpGameTest {

    @GameTest(maxTicks = 400)
    public void aWhelpWalksAtItsTarget(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = -2; x <= 10; x++) {
            for (int z = -2; z <= 10; z++) {
                level.setBlock(helper.absolutePos(new BlockPos(x, 0, z)), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        Villager target = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
        target.setNoAi(true);
        Vec3 targetAt = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(6, 1, 6)));
        target.setPos(targetAt.x, targetAt.y, targetAt.z);
        level.addFreshEntity(target);
        Warden whelp = Whelp.create(level, Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(1, 1, 1))), target);
        helper.assertTrue(whelp != null, "a whelp is made");
        double[] closest = {whelp.distanceTo(target)};
        for (int step = 1; step <= 56; step++) {
            final int t = step * 5;
            helper.runAfterDelay(t, () -> {
                closest[0] = Math.min(closest[0], whelp.distanceTo(target));
                if (t % 20 == 0) {
                    Whelp.point(whelp, target);
                }
            });
        }
        helper.runAfterDelay(300, () -> {
            double reached = closest[0];
            whelp.discard();
            target.discard();
            if (reached > 3.0) {
                helper.fail("The whelp never got within melee range; closest was " + reached);
                return;
            }
            helper.succeed();
        });
    }
}
