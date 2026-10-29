package charter.gametest;

import charter.land.Core;
import charter.mc.cmd.Teleports;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/**
 * /keep, /outpost and the waits (protected keeps, K3). The cooldown and the wait are knobs, set here for each test and
 * put back after; the shared wait tables are cleared first, since tests run side by side.
 */
public final class TeleportGameTest {

    private static final class Knobs implements AutoCloseable {
        final charter.config.Knobs knobs = charter.mc.CharterServer.runtime().knobs;
        final int warmup = knobs.teleportWarmupSeconds;
        final int cooldown = knobs.teleportCooldownSeconds;

        Knobs(int warmupSeconds, int cooldownSeconds) {
            knobs.teleportWarmupSeconds = warmupSeconds;
            knobs.teleportCooldownSeconds = cooldownSeconds;
        }

        @Override
        public void close() {
            knobs.teleportWarmupSeconds = warmup;
            knobs.teleportCooldownSeconds = cooldown;
        }
    }

    @GameTest(maxTicks = 100)
    public void keepTakesAMemberToTheNexusAndTheCooldownHoldsTheNextTrip(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper); Knobs k = new Knobs(0, 30)) {
            ServerPlayer lord = kit.faction("Travel");
            kit.keep(lord);
            ServerPlayer stranger = kit.outsider();
            helper.assertTrue(Teleports.start(stranger, Teleports.Kind.KEEP) != null, "A player with no nation was taken to a keep");

            lord.teleportTo(kit.border - 4, kit.y, kit.zc);
            Teleports.reset();
            helper.assertTrue(Teleports.start(lord, Teleports.Kind.KEEP) == null, "The Lord was refused their own keep");
            BlockPos nexus = kit.nexusPos();
            helper.assertTrue(Math.abs(lord.getX() - (nexus.getX() + 0.5)) < 1 && Math.abs(lord.getZ() - (nexus.getZ() + 0.5)) < 1,
                    "The Lord did not arrive at the Nexus: " + lord.position());
            String again = Teleports.start(lord, Teleports.Kind.KEEP);
            helper.assertTrue(again != null && again.contains("Wait"), "A second trip at once was not held by the cooldown: " + again);
            Teleports.reset();
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void outpostCostsFoodAndIsRefusedWhenTheSupplyIsEmpty(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper); Knobs k = new Knobs(0, 0)) {
            ServerPlayer lord = kit.faction("Post");
            helper.assertTrue(Teleports.start(lord, Teleports.Kind.OUTPOST) != null, "A player with no outpost was taken to one");
            kit.placeOn(lord, kit.hold(lord, charter.mc.land.LandItems.WAR_BANNER), kit.pos(-8, 0));
            Core camp = kit.rt.state.coresOf(kit.orgOf(lord)).stream().filter(c -> !c.isKeep()).findFirst().orElseThrow();
            helper.assertTrue(Teleports.start(lord, Teleports.Kind.OUTPOST) != null, "A camp still raising was a destination");
            camp.setPhase(charter.land.CampPhase.ACTIVE, 0);
            helper.assertTrue(camp.deposit > 0, "The outpost holds no deposit");
            String empty = Teleports.start(lord, Teleports.Kind.OUTPOST);
            helper.assertTrue(empty != null && empty.contains("no food"), "An outpost with no food was a destination: " + empty);

            kit.rt.state.siege.store(camp.id).provisions.add("minecraft:bread", 2);
            lord.teleportTo(kit.border - 4, kit.y, kit.zc + 6);
            helper.assertTrue(Teleports.start(lord, Teleports.Kind.OUTPOST) == null, "The Lord was refused their outpost");
            helper.assertTrue(kit.rt.state.siege.store(camp.id).provisions.count("minecraft:bread") == 1, "The trip did not cost one food");
            BlockPos banner = kit.pos(-8, 0);
            helper.assertTrue(Math.abs(lord.getX() - (banner.getX() + 0.5)) < 1.5, "The Lord did not arrive at the banner: " + lord.position());
            Teleports.reset();
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void movingOrBeingHurtCancelsTheWait(GameTestHelper helper) {
        LandKit kit = new LandKit(helper);
        Knobs k = new Knobs(1, 0);
        ServerPlayer lord = kit.faction("Wait");
        kit.keep(lord);
        lord.teleportTo(kit.border - 4, kit.y, kit.zc);
        Teleports.reset();
        helper.assertTrue(Teleports.start(lord, Teleports.Kind.KEEP) == null, "The trip did not start");
        helper.assertTrue(Teleports.waiting(lord), "The player is not waiting");
        lord.teleportTo(kit.border - 4, kit.y, kit.zc + 3);
        helper.runAfterDelay(3, () -> {
            try {
                helper.assertTrue(!Teleports.waiting(lord), "Moving did not cancel the wait");
                helper.assertTrue(Math.abs(lord.getX() - (kit.nexusPos().getX() + 0.5)) > 5, "A player who moved was taken anyway");
                helper.assertTrue(Teleports.start(lord, Teleports.Kind.KEEP) == null, "The second try did not start");
                lord.setHealth(lord.getHealth() - 1.0F);
                helper.runAfterDelay(3, () -> {
                    try {
                        helper.assertTrue(!Teleports.waiting(lord), "Being hurt did not cancel the wait");
                        helper.succeed();
                    } finally {
                        Teleports.reset();
                        k.close();
                        kit.close();
                    }
                });
            } catch (RuntimeException | Error e) {
                Teleports.reset();
                k.close();
                kit.close();
                throw e;
            }
        });
    }
}
