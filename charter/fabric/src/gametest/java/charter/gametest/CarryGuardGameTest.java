package charter.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.equine.Horse;

/**
 * A carried mount (physics off, as a carry mod leaves it) throws its rider within a tick, and a carried player is taken
 * off their own mount; a normal mount and rider are left alone.
 */
public final class CarryGuardGameTest {

    @GameTest(maxTicks = 100)
    public void aRiderOfACarriedMountIsSetDown(GameTestHelper helper) {
        LandKit kit = new LandKit(helper);
        ServerPlayer rider = kit.outsider();
        ServerPlayer carried = kit.outsider();
        Horse horse = EntityTypes.HORSE.create(kit.level, EntitySpawnReason.COMMAND);
        Horse second = EntityTypes.HORSE.create(kit.level, EntitySpawnReason.COMMAND);
        Runnable cleanup = () -> {
            kit.rt.knobs.carryGuard = true;
            rider.stopRiding();
            carried.stopRiding();
            if (horse != null) horse.discard();
            if (second != null) second.discard();
            kit.close();
        };
        try {
            helper.assertTrue(horse != null && second != null, "No horse");
            horse.setNoAi(true);
            horse.snapTo(kit.border - 18 + 0.5, kit.y, kit.zc + 4.5);
            kit.level.addFreshEntity(horse);
            second.setNoAi(true);
            second.snapTo(kit.border - 18 + 0.5, kit.y, kit.zc + 8.5);
            kit.level.addFreshEntity(second);
            helper.assertTrue(rider.startRiding(horse, true, false), "The rider could not mount the horse");
            helper.assertTrue(carried.startRiding(second, true, false), "The second player could not mount a horse");
        } catch (RuntimeException | AssertionError e) {
            cleanup.run();
            throw e;
        }
        helper.runAfterDelay(3, () -> {
            try {
                helper.assertTrue(rider.getVehicle() == horse, "A rider was set down off a horse nobody carries");
                helper.assertTrue(carried.getVehicle() == second, "A player nobody carries was set down");
                // Picked up: the carry mod switches physics and gravity off.
                horse.noPhysics = true;
                horse.setNoGravity(true);
                carried.noPhysics = true;
            } catch (RuntimeException | AssertionError e) {
                cleanup.run();
                throw e;
            }
            helper.runAfterDelay(2, () -> {
                try {
                    helper.assertTrue(!rider.isPassenger(), "The rider stayed on a carried horse");
                    helper.assertTrue(!carried.isPassenger(), "A carried player stayed on their horse");
                    carried.noPhysics = false;
                    kit.rt.knobs.carryGuard = false;
                    helper.assertTrue(rider.startRiding(horse, true, false), "The rider could not mount again");
                } catch (RuntimeException | AssertionError e) {
                    cleanup.run();
                    throw e;
                }
                helper.runAfterDelay(2, () -> {
                    try {
                        helper.assertTrue(rider.getVehicle() == horse, "With carryGuard off the rider was still set down");
                        helper.succeed();
                    } finally {
                        cleanup.run();
                    }
                });
            });
        });
    }
}
