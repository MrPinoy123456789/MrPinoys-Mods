package charter.gametest;

import charter.land.Core;
import charter.mc.land.Fixtures;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/** /charter nexus move: the lodestone moves inside the keep and nothing else does. */
public final class NexusMoveGameTest {

    private static void as(ServerPlayer player, String command) {
        player.level().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
    }

    @GameTest(maxTicks = 100)
    public void theLordMovesTheLodestoneInsideTheKeep(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Nex");
            var org = kit.orgOf(lord);
            kit.keep(lord);
            Core core = kit.rt.state.coresOf(org).get(0);
            var centre = core.center;
            long claims = kit.rt.state.claimsOf(org).size();
            BlockPos old = kit.nexusPos();
            helper.assertTrue(kit.level.getBlockState(old).is(Blocks.LODESTONE), "The lodestone was not placed");

            BlockPos to = new BlockPos(kit.nexusPos().getX() + 16, kit.y, kit.nexusPos().getZ());
            lord.teleportTo(to.getX(), to.getY(), to.getZ());
            as(lord, "charter nexus move");

            var nexus = Fixtures.of(kit.rt.state).first(core.id, Fixtures.Kind.NEXUS);
            helper.assertTrue(nexus != null && nexus.x() == to.getX() && nexus.z() == to.getZ(), "The lodestone record did not move");
            helper.assertTrue(kit.level.getBlockState(new BlockPos(nexus.x(), nexus.y(), nexus.z())).is(Blocks.LODESTONE), "No lodestone stands at the new spot");
            helper.assertTrue(kit.level.getBlockState(old).isAir(), "The old lodestone block is still there");
            helper.assertTrue(kit.rt.state.core(core.id).orElseThrow().center.equals(centre), "The keep moved with the lodestone");
            helper.assertTrue(kit.rt.state.claimsOf(org).size() == claims, "The keep's land changed");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void outsideTheKeepOrWithoutAKingdomItIsRefused(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Nex");
            var org = kit.orgOf(lord);
            kit.keep(lord);
            Core core = kit.rt.state.coresOf(org).get(0);
            BlockPos old = kit.nexusPos();

            lord.teleportTo(kit.border - 4, kit.y, kit.zc);
            as(lord, "charter nexus move");
            ServerPlayer stranger = kit.outsider();
            stranger.teleportTo(old.getX() + 2, old.getY(), old.getZ());
            as(stranger, "charter nexus move");

            var nexus = Fixtures.of(kit.rt.state).first(core.id, Fixtures.Kind.NEXUS);
            helper.assertTrue(nexus != null && nexus.x() == old.getX() && nexus.z() == old.getZ(), "A refused move changed the lodestone record");
            helper.assertTrue(kit.level.getBlockState(old).is(Blocks.LODESTONE), "A refused move took the lodestone");
            helper.succeed();
        }
    }
}
