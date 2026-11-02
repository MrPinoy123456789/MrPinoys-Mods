package charter.gametest;

import charter.land.CampPhase;
import charter.land.Core;
import charter.mc.land.LandItems;
import charter.mc.land.SpawnRules;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The {@code explosionsInKingdoms} knob: off (the default), no explosion breaks a block on any claim, a keep, an outpost
 * whether raised or still raising (not protected), and the admin kingdom's land, the owner's own TNT included. On, each
 * behaves as before. The wild is the same either way. Every test sets and restores the knob in one tick.
 */
public final class KingdomExplosionsGameTest {

    private static void dirtBox(LandKit kit, int cx) {
        for (int x = cx - 3; x <= cx + 3; x++) {
            for (int z = kit.zc - 3; z <= kit.zc + 3; z++) {
                for (int y = kit.y; y < kit.y + 3; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (charter.mc.land.Protection.fixtureAt(kit.level, p) == null && !kit.level.getBlockState(p).is(net.minecraft.tags.BlockTags.BANNERS)) {
                        kit.level.setBlockAndUpdate(p, Blocks.DIRT.defaultBlockState());
                    }
                }
            }
        }
    }

    private static int dirt(LandKit kit, int cx) {
        int n = 0;
        for (int x = cx - 3; x <= cx + 3; x++) {
            for (int z = kit.zc - 3; z <= kit.zc + 3; z++) {
                for (int y = kit.y; y < kit.y + 3; y++) if (kit.level.getBlockState(new BlockPos(x, y, z)).is(Blocks.DIRT)) n++;
            }
        }
        return n;
    }

    /** Fills a box around cx with dirt, blows it up (lit by owner, or by nobody), and says whether any dirt broke. */
    private static boolean blastBreaks(LandKit kit, int cx, ServerPlayer owner) {
        dirtBox(kit, cx);
        int before = dirt(kit, cx);
        PrimedTnt tnt = owner == null ? null : new PrimedTnt(kit.level, cx + 0.5, kit.y + 1.5, kit.zc + 0.5, owner);
        kit.level.explode(tnt, cx + 0.5, kit.y + 1.5, kit.zc + 0.5, 3.0F, Level.ExplosionInteraction.TNT);
        return dirt(kit, cx) < before;
    }

    private static boolean knob(boolean on) {
        boolean was = charter.mc.CharterServer.runtime().knobs.explosionsInKingdoms;
        charter.mc.CharterServer.runtime().knobs.explosionsInKingdoms = on;
        return was;
    }

    @GameTest(maxTicks = 100)
    public void aKeepIsSafeFromEveryExplosionUnlessTheKnobAllowsThem(GameTestHelper helper) {
        boolean was = knob(false);
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("KeepBoom");
            kit.keep(lord);
            int inside = kit.border + 8;
            int wild = kit.border - 10;
            helper.assertTrue(!blastBreaks(kit, inside, lord), "With the knob off the owner's own TNT broke blocks in the keep");
            helper.assertTrue(!blastBreaks(kit, inside, null), "With the knob off a blast broke blocks in the keep");
            helper.assertTrue(blastBreaks(kit, wild, null), "With the knob off a blast in the wild broke nothing");
            knob(true);
            helper.assertTrue(blastBreaks(kit, inside, lord), "With the knob on the owner's own TNT could not break blocks in their keep");
            helper.assertTrue(!blastBreaks(kit, inside, null), "With the knob on an unowned blast broke a protected keep");
            helper.assertTrue(blastBreaks(kit, wild, null), "With the knob on a blast in the wild broke nothing");
            helper.assertTrue(kit.level.getBlockState(kit.nexusPos()).is(Blocks.LODESTONE), "The nexus did not survive");
            helper.succeed();
        } finally {
            knob(was);
        }
    }

    @GameTest(maxTicks = 100)
    public void anOutpostRaisedOrStillRaisingIsSafeWithTheKnobOff(GameTestHelper helper) {
        boolean was = knob(false);
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("PostBoom");
            var org = kit.orgOf(lord);
            BlockPos spot = kit.pos(-8, 0);
            kit.placeOn(lord, kit.hold(lord, LandItems.WAR_BANNER), spot);
            Core camp = kit.rt.state.coresOf(org).stream().filter(c -> !c.isKeep()).findFirst().orElseThrow();
            helper.assertTrue(camp.phase() == CampPhase.RAISING, "The camp is not raising");
            helper.assertTrue(charter.mc.land.Protection.claimed(kit.level, spot) && !charter.mc.land.Protection.guarded(kit.level, spot),
                    "A raising camp should hold its chunk without protecting it");
            int cx = spot.getX();
            helper.assertTrue(!blastBreaks(kit, cx, null), "With the knob off a blast broke blocks at an unprotected outpost");
            knob(true);
            helper.assertTrue(blastBreaks(kit, cx, null), "With the knob on a raising outpost was spared, which it was not before");

            knob(false);
            var source = kit.server.createCommandSourceStack().withPosition(Vec3.atCenterOf(spot));
            try {
                kit.server.getCommands().getDispatcher().execute("charter admin camp raise-now", source);
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                helper.fail("raise-now failed: " + e.getMessage());
                return;
            }
            helper.assertTrue(camp.phase() == CampPhase.ACTIVE, "The camp did not become active");
            helper.assertTrue(!blastBreaks(kit, cx, lord), "With the knob off the owner's TNT broke blocks at their own outpost");
            knob(true);
            helper.assertTrue(blastBreaks(kit, cx, lord), "With the knob on the owner's TNT could not break blocks at their outpost");
            helper.succeed();
        } finally {
            knob(was);
        }
    }

    @GameTest(maxTicks = 100)
    public void theAdminKingdomIsSafeWithTheKnobOffEvenFromAnOperator(GameTestHelper helper) {
        boolean was = knob(false);
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("SpawnBoom");
            kit.keep(lord);
            kit.rt.state.org(kit.orgOf(lord)).orElseThrow().adminKingdom = true;
            SpawnRules.refresh();
            ServerPlayer op = kit.outsider();
            kit.server.getPlayerList().op(op.nameAndId(), java.util.Optional.of(
                    net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER), java.util.Optional.empty());
            try {
                helper.assertTrue(!blastBreaks(kit, kit.border + 8, null), "With the knob off a blast broke blocks in the admin kingdom");
                helper.assertTrue(!blastBreaks(kit, kit.border + 8, op), "With the knob off an operator's TNT broke blocks in the admin kingdom");
                helper.assertTrue(blastBreaks(kit, kit.border - 10, null), "A blast in the wild broke nothing");
                helper.succeed();
            } finally {
                kit.server.getPlayerList().deop(op.nameAndId());
                kit.rt.state.org(kit.orgOf(lord)).ifPresent(o -> o.adminKingdom = false);
                SpawnRules.refresh();
            }
        } finally {
            knob(was);
        }
    }
}
