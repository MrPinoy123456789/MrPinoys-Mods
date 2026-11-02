package charter.gametest;

import charter.mc.Text;
import charter.mc.cmd.Teleports;
import charter.mc.pad.PadBook;
import charter.mc.pad.Pads;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Keep pads: walking onto a registered block takes a player to their keep through the same path as {@code /keep}.
 * Pads are shared by every test running at the same time, so each test removes its own pad when it ends.
 *
 * <p>The trips take several ticks, and the teleport knobs and wait tables are shared, so these tests run in batches of
 * their own (a test environment each): every test in a batch sets the same knobs, and none clears the wait tables,
 * since a fresh mock player has no cooldown anyway.
 */
public final class PadGameTest {

    private static final String PADS = "charter-gametest:pads_alone";
    private static final String PADS_WAIT = "charter-gametest:pads_wait";

    /**
     * Sets the teleport wait and cooldown for one test. The tests of a batch overlap, so the values from before the
     * first of them are kept and put back only when the last one still open closes.
     */
    private static final class Knobs implements AutoCloseable {
        private static int open;
        private static int warmup;
        private static int cooldown;
        final charter.config.Knobs knobs = charter.mc.CharterServer.runtime().knobs;
        private boolean closed;

        Knobs(int warmupSeconds, int cooldownSeconds) {
            if (open++ == 0) {
                warmup = knobs.teleportWarmupSeconds;
                cooldown = knobs.teleportCooldownSeconds;
            }
            knobs.teleportWarmupSeconds = warmupSeconds;
            knobs.teleportCooldownSeconds = cooldownSeconds;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (--open > 0) return;
            knobs.teleportWarmupSeconds = warmup;
            knobs.teleportCooldownSeconds = cooldown;
        }
    }

    /** Steps run one after another, a few ticks apart; whatever happens, the cleanup runs once at the end. */
    private static final class Steps {
        private final GameTestHelper helper;
        private final List<Runnable> steps = new ArrayList<>();
        private final List<Integer> delays = new ArrayList<>();
        private final Runnable cleanup;

        Steps(GameTestHelper helper, Runnable cleanup) {
            this.helper = helper;
            this.cleanup = cleanup;
        }

        Steps then(int delay, Runnable step) {
            delays.add(delay);
            steps.add(step);
            return this;
        }

        void run() {
            next(0);
        }

        private void next(int i) {
            if (i == steps.size()) {
                cleanup.run();
                helper.succeed();
                return;
            }
            helper.runAfterDelay(delays.get(i), () -> {
                try {
                    steps.get(i).run();
                } catch (RuntimeException | Error e) {
                    cleanup.run();
                    throw e;
                }
                next(i + 1);
            });
        }
    }

    /** The floor block a pad is made of, dx east of the border, and the spot that names it. */
    private static BlockPos padBlock(LandKit kit, int dx) {
        return kit.pos(dx, 0).below();
    }

    private static void standOn(ServerPlayer player, BlockPos block) {
        player.teleportTo(block.getX() + 0.5, block.getY() + 1, block.getZ() + 0.5);
    }

    private static boolean atNexus(LandKit kit, ServerPlayer player) {
        BlockPos nexus = kit.nexusPos();
        return Math.abs(player.getX() - (nexus.getX() + 0.5)) < 1 && Math.abs(player.getZ() - (nexus.getZ() + 0.5)) < 1;
    }

    private static boolean onPad(ServerPlayer player, BlockPos block) {
        return Math.abs(player.getX() - (block.getX() + 0.5)) < 1 && Math.abs(player.getZ() - (block.getZ() + 0.5)) < 1;
    }

    @GameTest(maxTicks = 200, environment = PADS)
    public void aMemberWhoStepsOnAPadArrivesAtTheirKeepOnceAndTheCooldownHolds(GameTestHelper helper) {
        LandKit kit = new LandKit(helper);
        Knobs k = new Knobs(0, 30);
        ServerPlayer lord = kit.faction("Pad");
        kit.keep(lord);
        BlockPos pad = padBlock(kit, -8);
        PadBook.Spot spot = Pads.spot(kit.level, pad);
        helper.assertTrue(Pads.add(spot, Teleports.Kind.KEEP), "The pad was not made");
        lord.teleportTo(kit.border - 14, kit.y, kit.zc);
        new Steps(helper, () -> {
            Pads.remove(spot);
            Pads.forget(lord.getUUID());
            k.close();
            kit.close();
        }).then(2, () -> {
            standOn(lord, pad);
        }).then(5, () -> {
            helper.assertTrue(Pads.fired(lord) == 1, "The pad fired " + Pads.fired(lord) + " times, not once");
            helper.assertTrue("".equals(Pads.answer(lord)), "The pad refused the Lord: " + Pads.answer(lord));
            helper.assertTrue(atNexus(kit, lord), "The Lord did not arrive at the Nexus: " + lord.position());
            standOn(lord, pad);
        }).then(5, () -> {
            helper.assertTrue(Pads.fired(lord) == 2, "Stepping back on did not fire the pad again: " + Pads.fired(lord));
            String answer = Pads.answer(lord);
            helper.assertTrue(answer != null && answer.contains("Wait"), "The cooldown did not hold the second trip: " + answer);
            helper.assertTrue(onPad(lord, pad), "The Lord left the pad during the cooldown: " + lord.position());
        }).then(15, () -> {
            helper.assertTrue(Pads.fired(lord) == 2, "Standing on the pad fired it again: " + Pads.fired(lord));
            helper.assertTrue(onPad(lord, pad), "The Lord left the pad: " + lord.position());
        }).run();
    }

    @GameTest(maxTicks = 200, environment = PADS_WAIT)
    public void aPadWaitsForAPlayerToHoldStillThenTakesThem(GameTestHelper helper) {
        LandKit kit = new LandKit(helper);
        Knobs k = new Knobs(1, 0);
        ServerPlayer lord = kit.faction("PadWait");
        kit.keep(lord);
        BlockPos pad = padBlock(kit, -10);
        PadBook.Spot spot = Pads.spot(kit.level, pad);
        Pads.add(spot, Teleports.Kind.KEEP);
        lord.teleportTo(kit.border - 16, kit.y, kit.zc);
        new Steps(helper, () -> {
            Pads.remove(spot);
            Pads.forget(lord.getUUID());
            k.close();
            kit.close();
        }).then(2, () -> {
            standOn(lord, pad);
        }).then(5, () -> {
            helper.assertTrue(Pads.fired(lord) == 1, "The pad did not fire once: " + Pads.fired(lord));
            helper.assertTrue(Teleports.waiting(lord), "The pad did not start the usual wait");
            helper.assertTrue(onPad(lord, pad), "The player left before the wait ended");
        }).then(30, () -> {
            helper.assertTrue(!Teleports.waiting(lord), "The wait never ended");
            helper.assertTrue(atNexus(kit, lord), "The player did not arrive after the wait: " + lord.position());
            helper.assertTrue(Pads.fired(lord) == 1, "The pad fired again during the wait: " + Pads.fired(lord));
        }).run();
    }

    @GameTest(maxTicks = 200, environment = PADS)
    public void aPlayerWithNoKingdomIsToldSoAndStaysPut(GameTestHelper helper) {
        LandKit kit = new LandKit(helper);
        Knobs k = new Knobs(0, 30);
        kit.rt.knobs.requireAffiliation = true;
        ServerPlayer drifter = kit.outsider();
        BlockPos pad = padBlock(kit, -12);
        PadBook.Spot spot = Pads.spot(kit.level, pad);
        Pads.add(spot, Teleports.Kind.KEEP);
        drifter.teleportTo(kit.border - 18, kit.y, kit.zc);
        new Steps(helper, () -> {
            Pads.remove(spot);
            Pads.forget(drifter.getUUID());
            k.close();
            kit.close();
        }).then(2, () -> standOn(drifter, pad)).then(5, () -> {
            helper.assertTrue(Pads.fired(drifter) == 1, "The pad did not answer the drifter once: " + Pads.fired(drifter));
            helper.assertTrue(Text.get("teleport.no_org").equals(Pads.answer(drifter)),
                    "The drifter was not told they belong to no organization: " + Pads.answer(drifter));
            helper.assertTrue(onPad(drifter, pad), "The drifter was moved: " + drifter.position());
        }).run();
    }

    @GameTest(maxTicks = 200, environment = PADS)
    public void aRiderTriggersAPadWithoutDismounting(GameTestHelper helper) {
        LandKit kit = new LandKit(helper);
        Knobs k = new Knobs(0, 30);
        ServerPlayer lord = kit.faction("PadRide");
        kit.keep(lord);
        BlockPos pad = padBlock(kit, -6);
        PadBook.Spot spot = Pads.spot(kit.level, pad);
        Pads.add(spot, Teleports.Kind.KEEP);
        lord.teleportTo(kit.border - 18, kit.y, kit.zc + 4);
        Pig pig = net.minecraft.world.entity.EntityTypes.PIG.create(kit.level, EntitySpawnReason.COMMAND);
        helper.assertTrue(pig != null, "No pig");
        pig.setNoAi(true);
        pig.snapTo(kit.border - 18 + 0.5, kit.y, kit.zc + 4.5);
        kit.level.addFreshEntity(pig);
        helper.assertTrue(lord.startRiding(pig, true, false), "The Lord could not mount the pig");
        new Steps(helper, () -> {
            Pads.remove(spot);
            Pads.forget(lord.getUUID());
            lord.stopRiding();
            pig.discard();
            k.close();
            kit.close();
        }).then(3, () -> {
            pig.teleportTo(pad.getX() + 0.5, pad.getY() + 1, pad.getZ() + 0.5);
        }).then(8, () -> {
            helper.assertTrue(Pads.fired(lord) == 1, "A rider's pad did not fire: " + Pads.fired(lord) + ", pig at " + pig.position());
            helper.assertTrue("".equals(Pads.answer(lord)), "The rider was refused: " + Pads.answer(lord));
            helper.assertTrue(atNexus(kit, lord), "The rider did not arrive at the Nexus: " + lord.position());
            helper.assertTrue(!lord.isPassenger() && pig.position().distanceTo(Vec3.atBottomCenterOf(pad.above())) < 1.5,
                    "The mount did not stay behind on the pad: riding " + lord.isPassenger() + ", pig at " + pig.position());
        }).run();
    }

    @GameTest(maxTicks = 100)
    public void operatorsAddListRemoveAndClearPadsAndOthersCannot(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer op = kit.outsider();
            ServerPlayer plain = kit.outsider();
            kit.server.getPlayerList().op(op.nameAndId(), java.util.Optional.of(
                    net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER), java.util.Optional.empty());
            List<PadBook.Pad> before = Pads.book().all();
            try {
                BlockPos block = padBlock(kit, -20);
                PadBook.Spot spot = Pads.spot(kit.level, block);
                standOn(op, block);
                standOn(plain, block);
                run(kit, plain, "charter admin pad add keep");
                helper.assertTrue(Pads.book().at(spot) == null, "A player who is not an operator made a pad");

                run(kit, op, "charter admin pad add keep");
                PadBook.Pad made = Pads.book().at(spot);
                helper.assertTrue(made != null && made.kind() == Teleports.Kind.KEEP, "The operator's pad was not made under their feet");
                int size = Pads.book().size();
                run(kit, op, "charter admin pad add keep");
                helper.assertTrue(Pads.book().size() == size, "A second pad was made on the same block");
                run(kit, op, "charter admin pad add moon");
                helper.assertTrue(Pads.book().size() == size, "A pad of an unknown kind was made");
                run(kit, op, "charter admin pad list");

                run(kit, plain, "charter admin pad remove");
                run(kit, plain, "charter admin pad clear");
                helper.assertTrue(Pads.book().at(spot) != null, "A player who is not an operator removed or cleared pads");

                run(kit, op, "charter admin pad remove");
                helper.assertTrue(Pads.book().at(spot) == null, "remove did not take the pad under the operator");

                // Clear removes every pad, so the other tests' pads are put back in this same tick.
                run(kit, op, "charter admin pad add keep");
                run(kit, op, "charter admin pad clear");
                boolean cleared = Pads.book().isEmpty();
                for (PadBook.Pad pad : before) Pads.add(pad.spot(), pad.kind());
                helper.assertTrue(cleared, "clear left pads behind");
                helper.assertTrue(Pads.book().at(spot) == null, "The cleared pad came back");
            } finally {
                for (PadBook.Pad pad : before) Pads.add(pad.spot(), pad.kind());
                kit.server.getPlayerList().deop(op.nameAndId());
            }
            helper.succeed();
        }
    }

    private static void run(LandKit kit, ServerPlayer player, String command) {
        kit.server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
    }
}
