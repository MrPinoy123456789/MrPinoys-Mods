package charter.gametest;

import charter.id.OrgId;
import charter.id.PlayerId;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/** {@code /charter admin dissolve}: the faction, its keep's Nexus block, its land and its members go. */
public final class DissolveGameTest {

    @GameTest(maxTicks = 100, environment = "charter-gametest:founding_alone")
    public void anOperatorDissolvesAFactionAndItsKeep(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Doomed");
            OrgId org = kit.orgOf(lord);
            kit.keep(lord);
            String name = kit.rt.describe.org(org);
            helper.assertTrue(kit.level.getBlockState(kit.nexusPos()).is(Blocks.LODESTONE), "The Nexus is not standing before the dissolve");

            kit.server.getCommands().performPrefixedCommand(kit.server.createCommandSourceStack(), "charter admin dissolve \"" + name + "\"");

            helper.assertTrue(kit.rt.state.org(org).isEmpty(), "The faction still exists");
            helper.assertTrue(kit.rt.state.orgIncludingDissolved(org).map(o -> o.name().equals(name)).orElse(false), "History lost the name");
            helper.assertTrue(kit.rt.state.membership(new PlayerId(lord.getUUID())).isEmpty(), "The Lord still has a faction");
            helper.assertTrue(kit.rt.state.coresOf(org).isEmpty() && kit.rt.state.claimsOf(org).isEmpty(), "The keep or its land remain");
            helper.assertTrue(kit.level.getBlockState(kit.nexusPos()).isAir(), "The Nexus block was not removed");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 40, environment = "charter-gametest:founding_alone")
    public void aMissingFactionIsToldSo(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        int before = charter.mc.CharterServer.runtime().state.orgs().size();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "charter admin dissolve \"No Such Place\"");
        helper.assertTrue(charter.mc.CharterServer.runtime().state.orgs().size() == before, "A missing faction changed something");
        helper.succeed();
    }
}
