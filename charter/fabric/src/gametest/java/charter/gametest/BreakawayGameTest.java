package charter.gametest;

import charter.engine.Actor;
import charter.gov.Preset;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.mc.Text;
import charter.mc.book.BookDialogs;
import charter.mc.book.BreakawayPages;
import charter.mc.book.MemberPages;
import charter.mc.book.NexusItems;
import charter.mc.cmd.CharterCommands;
import charter.org.actions.AcceptInvite;
import charter.org.actions.CreateSubOrg;
import charter.org.actions.InviteMember;
import charter.org.actions.Secede;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Breaking away from the book: a group leaves a flat faction, a sub-faction puts secession to a vote (R4, R5). */
public final class BreakawayGameTest {

    private static PlayerId id(ServerPlayer p) {
        return new PlayerId(p.getUUID());
    }

    private static ServerPlayer member(LandKit kit, OrgId org) {
        ServerPlayer p = kit.outsider();
        kit.rt.state.touch(id(p), p.getPlainTextName(), kit.rt.clock.now());
        kit.rt.engine.run(new Actor.Admin("gametest"), new InviteMember(org, id(p)));
        kit.rt.engine.run(new Actor.Player(id(p)), new AcceptInvite(id(p), org));
        kit.server.getCommands().performPrefixedCommand(kit.server.createCommandSourceStack(), "charter admin age " + p.getUUID() + " 4");
        return p;
    }

    private static void press(GameTestHelper helper, ServerPlayer player, String label) {
        var tokens = CharterCommands.tokens();
        String token = tokens.newest(player.getUUID(), label).orElse(null);
        helper.assertTrue(token != null, "No [" + label + "] button was shown to the player");
        tokens.take(token, player.getUUID(), System.currentTimeMillis()).orElseThrow().accept(player);
    }

    @GameTest(maxTicks = 200)
    public void aGroupBreaksAwayAndFoundsAKeep(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Old");
            OrgId old = kit.orgOf(lord);
            ServerPlayer gil = member(kit, old);
            ServerPlayer cy = member(kit, old);

            // The button on the Freedoms page, then the page, then the confirm.
            MemberPages.freedoms(gil, null);
            press(helper, gil, Text.get("book.breakaway.button"));
            helper.assertTrue(BookDialogs.LAST.get(gil.getUUID()).common().title().getString().equals(Text.get("book.breakaway.title")),
                    "The button did not open the Break away page");
            press(helper, gil, Text.get("book.breakaway.start"));
            press(helper, gil, Text.get("book.button.yes"));
            helper.assertTrue(kit.rt.state.groupLeaving(old).isPresent(), "Starting left no group");
            BreakawayPages.page(cy, null);
            press(helper, cy, Text.get("book.breakaway.join"));
            press(helper, cy, Text.get("book.button.yes"));
            helper.assertTrue(kit.rt.state.groupLeaving(old).orElseThrow().members().size() == 2, "The second member did not join the group");

            String name = "Brk" + Long.toString(System.nanoTime() & 0xFFFFFL, 36);
            gil.getInventory().setItem(20, new ItemStack(Items.DIAMOND, 64));
            kit.placeOn(gil, NexusItems.founding(name, ""), kit.nexusPos());
            var fresh = kit.rt.state.orgNamed(name).orElse(null);
            helper.assertTrue(fresh != null, "Placing the Nexus founded no faction named " + name);
            helper.assertTrue(kit.rt.state.isMember(id(gil), fresh.id) && kit.rt.state.isMember(id(cy), fresh.id), "The group did not move into the new faction");
            helper.assertTrue(kit.rt.state.isMember(id(lord), old), "The Lord left with them");
            helper.assertTrue(kit.rt.state.coresOf(fresh.id).size() == 1, "The new faction has no keep");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void leaveIsAButtonOnFreedomsWithAConfirm(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Exit");
            OrgId org = kit.orgOf(lord);
            ServerPlayer gil = member(kit, org);

            MemberPages.freedoms(gil, null);
            press(helper, gil, Text.get("book.breakaway.button"));
            MemberPages.freedoms(gil, null);
            press(helper, gil, Text.get("book.leave.button"));
            helper.assertTrue(kit.rt.state.isMember(id(gil), org), "Pressing Leave left at once, with no confirm");
            String shown = BookDialogs.LAST.get(gil.getUUID()).common().title().getString();
            helper.assertTrue(shown.equals(Text.get("book.leave.title", "org", kit.rt.describe.org(org))), "The Leave button did not open the confirm: " + shown);
            press(helper, gil, Text.get("book.leave.yes"));
            helper.assertTrue(kit.rt.state.membership(id(gil)).isEmpty(), "Confirming did not leave the faction");

            // A sole Lord is not refused: Leave works for them too.
            MemberPages.freedoms(lord, null);
            press(helper, lord, Text.get("book.leave.button"));
            press(helper, lord, Text.get("book.leave.yes"));
            helper.assertTrue(kit.rt.state.membership(id(lord)).isEmpty(), "The Lord could not leave");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void aSubFactionPutsSecessionToAVote(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Crown");
            OrgId realm = kit.orgOf(lord);
            ServerPlayer gil = member(kit, realm);
            String sub = "Sub" + Long.toString(System.nanoTime() & 0xFFFFFL, 36);
            var made = kit.rt.engine.run(new Actor.Admin("gametest"), new CreateSubOrg(realm, sub, Preset.LORDSHIP, id(gil)));
            helper.assertTrue(made.done(), "The sub-faction was not made: " + made);
            OrgId guard = kit.rt.state.orgNamed(sub).orElseThrow().id;

            BreakawayPages.page(gil, null);
            press(helper, gil, Text.get("book.breakaway.put_to_vote"));
            press(helper, gil, Text.get("book.button.yes"));
            boolean voted = kit.rt.state.votes().stream().anyMatch(v -> v.org.equals(guard) && v.motion instanceof Secede);
            helper.assertTrue(voted, "Confirming put no secession to a vote");
            // The lone member of the sub-faction is all the electorate, so it has passed; with no keep it now waits for a Nexus.
            helper.assertTrue(kit.rt.state.pendingSecession(guard).isPresent(), "The passed vote left no pending break away");
            helper.succeed();
        }
    }
}
