package charter.gametest;

import charter.gov.Vote;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.cmd.CharterCommands;
import charter.org.OrgStatus;
import charter.treasury.Currency;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * M7's "done" scenario through the typed commands and the page links, as two
 * players would do it: found a commune, invite by a vote, join from the page,
 * deposit at the vault, propose a spend, vote it through from the vote page, and
 * get the diamonds. Links are clicked by running the token the page issued.
 */
public final class VoteCommandGameTest {

    private static void as(ServerPlayer player, String command) {
        MinecraftServer server = player.level().getServer();
        server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
    }

    private static void asOp(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }

    /** Clicks the newest link with this text shown to the player. */
    private static void click(GameTestHelper helper, ServerPlayer player, String textKey) {
        String label = Text.get(textKey);
        String token = CharterCommands.tokens().newest(player.getUUID(), label).orElse(null);
        helper.assertTrue(token != null, "No [" + label + "] link was shown to the player");
        as(player, "charter act " + token);
    }

    private static int diamonds(ServerPlayer player) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(Items.DIAMOND)) n += stack.getCount();
        }
        return n;
    }

    @GameTest(maxTicks = 100)
    public void aSpendIsVotedThroughByCommands(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CharterRuntime rt = CharterServer.runtime();
        ServerPlayer alice = helper.makeMockServerPlayerInLevel();
        ServerPlayer bob = helper.makeMockServerPlayerInLevel();
        PlayerId aliceId = new PlayerId(alice.getUUID());
        PlayerId bobId = new PlayerId(bob.getUUID());
        String name = "Vote " + Long.toString(alice.getUUID().getLeastSignificantBits() & 0xFFFFFF, 36);

        // Found.
        as(alice, "charter found \"" + name + "\" commune");
        OrgId org = rt.state.orgNamed(name).map(o -> o.id).orElse(null);
        helper.assertTrue(org != null, "/charter found did not found " + name);
        helper.assertTrue(rt.state.isMember(aliceId, org), "The founder is not a member");

        // Invite: a lone commune founder may only invite by a vote, and only once eligible.
        asOp(server, "charter admin age " + alice.getUUID() + " 4");
        as(alice, "charter invite " + bob.getUUID());
        helper.assertTrue(rt.state.invite(org, bobId).isEmpty(), "A commune member invited without a vote");
        click(helper, alice, "link.propose");
        helper.assertTrue(rt.state.invite(org, bobId).isPresent(), "The invite vote did not pass and invite Bob");

        // Join from the page.
        as(bob, "charter");
        click(helper, bob, "link.join");
        helper.assertTrue(rt.state.isMember(bobId, org), "Bob did not join from the page link");
        helper.assertTrue(rt.state.org(org).orElseThrow().status() == OrgStatus.ACTIVE, "The second member did not make it active");
        asOp(server, "charter admin age " + bob.getUUID() + " 4");

        // The vault.
        alice.getInventory().add(new ItemStack(Items.DIAMOND, 10));
        as(alice, "charter vault deposit diamond 10");
        helper.assertTrue(rt.state.org(org).orElseThrow().ledger.balance(Currency.DIAMOND) == 10, "The deposit did not reach the treasury");
        helper.assertTrue(diamonds(alice) == 0, "The deposited diamonds were not taken");

        // A spend needs a vote: propose it, then Bob votes yes from his vote page.
        as(alice, "charter vault withdraw diamond 4");
        helper.assertTrue(rt.state.org(org).orElseThrow().ledger.balance(Currency.DIAMOND) == 10, "A commune member spent without a vote");
        click(helper, alice, "link.propose");
        Vote vote = rt.state.votes().stream().filter(v -> v.org.equals(org) && v.open()).findFirst().orElse(null);
        helper.assertTrue(vote != null, "Proposing the spend opened no vote");
        as(bob, "charter vote");
        click(helper, bob, "link.yes");

        helper.assertTrue(vote.state() == Vote.State.EXECUTED, "The spend vote was not carried out: " + vote.state());
        helper.assertTrue(rt.state.org(org).orElseThrow().ledger.balance(Currency.DIAMOND) == 6, "The vote did not move the treasury");
        helper.assertTrue(diamonds(alice) == 4, "The payee did not get the diamonds: " + diamonds(alice));

        // A used link is gone.
        helper.assertTrue(CharterCommands.tokens().newest(bob.getUUID(), Text.get("link.yes")).isEmpty(), "A clicked link was not used up");
        helper.succeed();
    }
}
