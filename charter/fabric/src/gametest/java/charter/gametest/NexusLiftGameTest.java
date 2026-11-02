package charter.gametest;

import charter.engine.Actor;
import charter.id.PlayerId;
import charter.land.Core;
import charter.mc.cmd.Teleports;
import charter.mc.land.Fixtures;
import charter.mc.land.NexusLift;
import charter.mc.terminal.TerminalBook;
import charter.mc.terminal.Terminals;
import charter.org.actions.AddMember;
import charter.siege.WarBook;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * Moving the lodestone by hand: the Lord mines the Nexus and carries it as a bound item, sets it down inside the keep
 * (and {@code /keep} follows it), is refused outside, at war and when not authorised, and the lodestone goes back where
 * it stood when the carrier dies or logs out. Each test ends its own lift before it closes; none calls
 * {@link NexusLift#restoreAll}, which would end lifts of other tests running beside it.
 */
public final class NexusLiftGameTest {

    /** The first lifted Nexus in the player's inventory and its slot, or null. */
    private static int liftedSlot(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (NexusLift.isLiftedItem(player.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    private static int liftedCount(ServerPlayer player) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (NexusLift.isLiftedItem(player.getInventory().getItem(i))) n++;
        }
        return n;
    }

    private static Fixtures.Fixture nexus(LandKit kit, Core core) {
        return Fixtures.of(kit.rt.state).first(core.id, Fixtures.Kind.NEXUS);
    }

    private static Core keepOf(LandKit kit, ServerPlayer lord) {
        return kit.rt.state.coresOf(kit.orgOf(lord)).stream().filter(Core::isKeep).findFirst().orElseThrow();
    }

    /** Mines the Nexus as this player and checks it came up as the bound item. */
    private static void lift(LandKit kit, ServerPlayer lord, Core core, BlockPos at) {
        lord.gameMode.destroyBlock(at);
        kit.helper.assertTrue(NexusLift.lifted(core.id), "Mining the Nexus did not lift it");
        kit.helper.assertTrue(kit.level.getBlockState(at).isAir(), "The lodestone block is still standing after the lift");
        kit.helper.assertTrue(nexus(kit, core) == null, "The Nexus record stayed while the lodestone is carried");
        kit.helper.assertTrue(liftedCount(lord) == 1, "The Lord does not carry exactly one lifted Nexus: " + liftedCount(lord));
        ItemStack item = lord.getInventory().getItem(liftedSlot(lord));
        kit.helper.assertTrue(item.is(net.minecraft.world.item.Items.LODESTONE) && item.getMaxStackSize() == 1,
                "The lifted Nexus is not a lodestone that stacks to one");
    }

    private static void assertStandsAt(LandKit kit, Core core, BlockPos at, String why) {
        Fixtures.Fixture f = nexus(kit, core);
        kit.helper.assertTrue(f != null && f.x() == at.getX() && f.y() == at.getY() && f.z() == at.getZ(),
                why + ": the Nexus record is " + f + ", not at " + at);
        kit.helper.assertTrue(kit.level.getBlockState(at).is(Blocks.LODESTONE), why + ": no lodestone stands at " + at);
        kit.helper.assertTrue(!NexusLift.lifted(core.id), why + ": the lift did not end");
    }

    @GameTest(maxTicks = 100)
    public void theLordLiftsTheLodestoneAndSetsItDownInsideTheKeep(GameTestHelper helper) {
        var knobs = charter.mc.CharterServer.runtime().knobs;
        int warmup = knobs.teleportWarmupSeconds;
        int cooldown = knobs.teleportCooldownSeconds;
        try (LandKit kit = new LandKit(helper)) {
            knobs.teleportWarmupSeconds = 0;
            knobs.teleportCooldownSeconds = 0;
            ServerPlayer lord = kit.faction("Lift");
            kit.keep(lord);
            Core core = keepOf(kit, lord);
            BlockPos old = kit.nexusPos();
            List<TerminalBook.Terminal> lecterns = Terminals.book().ofCore(core.id.value().toString());
            try {
                lift(kit, lord, core, old);
                ItemStack item = lord.getInventory().getItem(liftedSlot(lord));

                // Soulbound: no chest slot takes it and a drop goes back into the inventory.
                helper.assertTrue(!new Slot(new SimpleContainer(9), 0, 0, 0).mayPlace(item), "A chest slot took the lifted Nexus");
                int slot = liftedSlot(lord);
                ItemStack taken = lord.getInventory().removeItem(slot, 1);
                helper.assertTrue(lord.drop(taken, false, Prediction.SERVER_ONLY) == null, "The lifted Nexus was dropped");
                helper.assertTrue(liftedCount(lord) == 1, "A refused drop did not put the lifted Nexus back");

                // Nobody else may carry it: a copy in another player's inventory is swept.
                ServerPlayer stranger = kit.outsider();
                stranger.getInventory().setItem(0, lord.getInventory().getItem(liftedSlot(lord)).copy());
                NexusLift.check(kit.server);
                helper.assertTrue(liftedCount(stranger) == 0, "Another player kept a copy of the lifted Nexus");
                helper.assertTrue(NexusLift.lifted(core.id) && liftedCount(lord) == 1, "Sweeping the copy ended the real lift");

                // While it is up, /keep goes to the middle of the keep's core chunk instead of refusing.
                lord.teleportTo(kit.border - 4, kit.y, kit.zc);
                Teleports.reset();
                String trip = Teleports.start(lord, Teleports.Kind.KEEP);
                helper.assertTrue(trip == null, "/keep was refused while the lodestone is carried: " + trip);
                int mx = core.center.x() * 16 + 8;
                int mz = core.center.z() * 16 + 8;
                helper.assertTrue(Math.abs(lord.getX() - (mx + 0.5)) < 1 && Math.abs(lord.getZ() - (mz + 0.5)) < 1,
                        "/keep did not arrive at the middle of the core chunk: " + lord.position());

                // Outside the keep it is refused and kept.
                BlockPos outside = kit.pos(-4, 0);
                InteractionResult refused = kit.placeOn(lord, lord.getInventory().getItem(liftedSlot(lord)), outside);
                helper.assertTrue(refused != InteractionResult.SUCCESS && !kit.level.getBlockState(outside).is(Blocks.LODESTONE),
                        "The lifted Nexus was set down outside the keep: " + refused);
                helper.assertTrue(NexusLift.lifted(core.id) && liftedCount(lord) == 1, "A refused placement took the lifted Nexus");

                // Inside the keep, a chunk east of where it stood, it stands again and the record follows.
                BlockPos to = old.offset(16, 0, 0);
                kit.placeOn(lord, lord.getInventory().getItem(liftedSlot(lord)), to);
                assertStandsAt(kit, core, to, "Setting it down inside the keep");
                helper.assertTrue(liftedCount(lord) == 0, "The Lord still carries a lifted Nexus after setting it down");
                helper.assertTrue(kit.rt.state.core(core.id).orElseThrow().center.equals(core.center), "The keep moved with the lodestone");
                helper.assertTrue(Terminals.book().ofCore(core.id.value().toString()).equals(lecterns), "The keep's lectern moved with the lodestone");

                // /keep now arrives at the new spot.
                lord.teleportTo(kit.border - 4, kit.y, kit.zc);
                Teleports.reset();
                helper.assertTrue(Teleports.start(lord, Teleports.Kind.KEEP) == null, "/keep was refused after the lodestone moved");
                helper.assertTrue(Math.abs(lord.getX() - (to.getX() + 0.5)) < 1 && Math.abs(lord.getZ() - (to.getZ() + 0.5)) < 1,
                        "/keep did not arrive at the moved lodestone: " + lord.position());
            } finally {
                NexusLift.cancel(kit.server, core.id);
                Teleports.reset();
                knobs.teleportWarmupSeconds = warmup;
                knobs.teleportCooldownSeconds = cooldown;
            }
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void nobodyButTheLordMayLiftIt(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("LiftNo");
            kit.keep(lord);
            Core core = keepOf(kit, lord);
            BlockPos at = kit.nexusPos();
            ServerPlayer member = kit.outsider();
            helper.assertTrue(kit.rt.engine.run(new Actor.Admin("gametest"), new AddMember(kit.orgOf(lord), new PlayerId(member.getUUID()))).done(),
                    "The member did not join");
            ServerPlayer stranger = kit.outsider();
            ServerPlayer rival = kit.faction("LiftNoR");
            try {
                for (ServerPlayer who : List.of(member, stranger, rival)) {
                    who.teleportTo(at.getX() + 2, at.getY(), at.getZ());
                    who.gameMode.destroyBlock(at);
                    assertStandsAt(kit, core, at, "A player who may not lift it mined it");
                    helper.assertTrue(liftedCount(who) == 0, "A player who may not lift it got the lifted Nexus");
                }
            } finally {
                NexusLift.cancel(kit.server, core.id);
            }
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void itCannotBeLiftedAtWarAndAWarPutsItBack(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("LiftWar");
            kit.keep(lord);
            Core core = keepOf(kit, lord);
            BlockPos at = kit.nexusPos();
            ServerPlayer rival = kit.faction("LiftWarR");
            WarBook.Goal goal = new WarBook.Goal();
            goal.id = -1;
            goal.attacker = kit.orgOf(rival);
            goal.defender = kit.orgOf(lord);
            goal.outpost = core.id;
            goal.opensAt = Long.MAX_VALUE;
            goal.expiresAt = Long.MAX_VALUE;
            WarBook wars = kit.rt.state.wars;
            try {
                wars.goals.add(goal);
                lord.gameMode.destroyBlock(at);
                assertStandsAt(kit, core, at, "Lifting it during a war");
                helper.assertTrue(liftedCount(lord) == 0, "The Lord got the lifted Nexus during a war");
                wars.goals.remove(goal);

                lift(kit, lord, core, at);
                wars.goals.add(goal);
                NexusLift.check(kit.server);
                assertStandsAt(kit, core, at, "A war beginning while it is carried");
                helper.assertTrue(liftedCount(lord) == 0, "The Lord kept the lifted Nexus after the war put it back");
            } finally {
                wars.goals.remove(goal);
                NexusLift.cancel(kit.server, core.id);
            }
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void deathOrLoggingOutPutsItBack(GameTestHelper helper) {
        var knobs = charter.mc.CharterServer.runtime().knobs;
        int grace = knobs.nexusLiftLogoutSeconds;
        try (LandKit kit = new LandKit(helper)) {
            knobs.nexusLiftLogoutSeconds = 0;
            ServerPlayer lord = kit.faction("LiftDie");
            kit.keep(lord);
            Core core = keepOf(kit, lord);
            BlockPos at = kit.nexusPos();
            try {
                lift(kit, lord, core, at);
                ServerLivingEntityEvents.ALLOW_DEATH.invoker().allowDeath(lord, lord.damageSources().generic(), 1000f);
                assertStandsAt(kit, core, at, "The carrier dying");
                helper.assertTrue(liftedCount(lord) == 0, "The dead carrier kept the lifted Nexus");

                lift(kit, lord, core, at);
                // Logging out: the carrier leaves the player list, and the next check puts the lodestone back.
                kit.server.getPlayerList().remove(lord);
                NexusLift.check(kit.server);
                assertStandsAt(kit, core, at, "The carrier logging out");
                // The item left in their saved inventory is swept when they come back.
                helper.assertTrue(NexusLift.carriedBy(lord.getUUID()) == null, "The lift still names the carrier who logged out");
            } finally {
                NexusLift.cancel(kit.server, core.id);
                knobs.nexusLiftLogoutSeconds = grace;
            }
            helper.succeed();
        }
    }
}
