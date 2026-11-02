package charter.gametest;

import charter.gov.Office;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.mc.ActAs;
import charter.mc.Text;
import charter.mc.book.AdminPages;
import charter.mc.book.BookButton;
import charter.mc.book.BookDialogs;
import charter.mc.book.BookInput;
import charter.mc.book.BookScreen;
import charter.mc.book.LeaderPages;
import charter.mc.book.MemberPages;
import charter.mc.book.NewcomerPages;
import charter.mc.book.StandPages;
import charter.mc.cmd.CharterCommands;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/** Two Lords: the operator commands and menu, and what players read in the book. */
public final class TwoLordsGameTest {

    private static PlayerId id(ServerPlayer p) {
        return new PlayerId(p.getUUID());
    }

    private static void op(LandKit kit, String command) {
        kit.server.getCommands().performPrefixedCommand(kit.server.createCommandSourceStack(), command);
    }

    private static Office lordOffice(LandKit kit, OrgId org) {
        return kit.rt.state.office(kit.rt.state.org(org).orElseThrow().governance.deciders).orElseThrow();
    }

    /** A kit faction with a second Lord named through the real command. */
    private static ServerPlayer second(LandKit kit, OrgId org) {
        ServerPlayer bob = kit.outsider();
        kit.rt.state.touch(id(bob), bob.getPlainTextName(), kit.rt.clock.now());
        op(kit, "charter admin faction coleader " + quote(kit.rt.describe.org(org)) + " " + bob.getUUID());
        return bob;
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    @GameTest(maxTicks = 200)
    public void theCommandsGiveAndTakeASecondLord(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer ann = kit.faction("Pair");
            OrgId org = kit.orgOf(ann);
            Office lord = lordOffice(kit, org);
            ServerPlayer bob = second(kit, org);
            helper.assertTrue(kit.rt.state.isMember(id(bob), org), "coleader did not bring the player into the kingdom");
            helper.assertTrue(lord.holds(id(ann)) && lord.holds(id(bob)) && lord.seats() == 2 && lord.deciding() == Office.Deciding.ALONE,
                    "coleader did not make two Lords");
            ServerPlayer cy = kit.outsider();
            kit.rt.state.touch(id(cy), cy.getPlainTextName(), kit.rt.clock.now());
            op(kit, "charter admin faction coleader " + quote(kit.rt.describe.org(org)) + " " + cy.getUUID());
            helper.assertTrue(!lord.holds(id(cy)) && lord.holders().size() == 2, "A third Lord was seated");

            op(kit, "charter admin faction unlord " + quote(kit.rt.describe.org(org)) + " " + bob.getUUID());
            helper.assertTrue(!lord.holds(id(bob)) && lord.holds(id(ann)) && lord.seats() == 1, "unlord did not leave one Lord on one seat");
            helper.assertTrue(kit.rt.state.isMember(id(bob), org), "unlord took the player out of the kingdom");

            second(kit, org);
            op(kit, "charter admin faction leader " + quote(kit.rt.describe.org(org)) + " " + cy.getUUID());
            helper.assertTrue(lord.holders().size() == 1 && lord.holds(id(cy)) && lord.seats() == 1, "leader did not leave exactly one Lord");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void theBookSaysTwoLordsNotACouncil(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer ann = kit.faction("Twain");
            OrgId org = kit.orgOf(ann);
            ServerPlayer bob = second(kit, org);
            String twoLords = Text.get("book.form.co_lordship");

            MemberPages.home(bob, null);
            String home = String.join("\n", BookDialogs.LAST.get(bob.getUUID()).bodyText());
            helper.assertTrue(home.contains(twoLords) && !home.toLowerCase().contains("council"), "Home does not say two Lords: " + home);
            helper.assertTrue(LeaderPages.leads(bob) && LeaderPages.leads(ann), "Both Lords should have the Leader's Desk");

            ServerPlayer gil = kit.outsider();
            kit.rt.engine.run(new charter.engine.Actor.Admin("gametest"), new charter.org.actions.AddMember(org, id(gil)));
            MemberPages.whoDecides(gil, null);
            String decides = String.join("\n", BookDialogs.LAST.get(gil.getUUID()).bodyText());
            String names = kit.rt.describe.player(id(ann)) + Text.get("book.and") + kit.rt.describe.player(id(bob));
            helper.assertTrue(decides.contains(Text.get("book.decides.two_lords", "names", names)), "Who decides does not name two Lords: " + decides);

            StandPages.page(gil, null);
            String stand = String.join("\n", BookDialogs.LAST.get(gil.getUUID()).bodyText());
            helper.assertTrue(stand.contains(Text.get("book.or")) && stand.contains("as Lord") && !stand.toLowerCase().contains("council"),
                    "Where I stand does not read the Lords as two: " + stand);

            ServerPlayer newcomer = kit.outsider();
            NewcomerPages.faction(newcomer, org, null);
            String page = String.join("\n", BookDialogs.LAST.get(newcomer.getUUID()).bodyText());
            helper.assertTrue(page.contains(twoLords), "The kingdom page does not say two Lords: " + page);
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void oneLordsAttemptToRemoveTheOtherNamesWhoMustAgree(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer ann = kit.faction("Equal");
            OrgId org = kit.orgOf(ann);
            ServerPlayer bob = second(kit, org);
            Office lord = lordOffice(kit, org);
            ActAs.Ran ran = ActAs.runDetailed(kit.rt.state, kit.rt.engine, kit.rt.describe, id(ann),
                    new charter.gov.actions.Unseat(org, lord.id, id(bob)));
            helper.assertTrue(ran.proposed(), "Unseating the other Lord was not put to them: " + ran.line());
            helper.assertTrue(ran.line().equals(Text.get("book.seats.proposed_lord", "other", kit.rt.describe.player(id(bob)))),
                    "The book does not say who must agree: " + ran.line());
            helper.assertTrue(lord.holds(id(bob)), "One Lord removed the other alone");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void theOperatorMenuNamesAndRemovesLords(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer ann = kit.faction("Crowns");
            OrgId org = kit.orgOf(ann);
            second(kit, org);
            String name = kit.rt.describe.org(org);
            ServerPlayer op = kit.outsider();
            kit.server.getPlayerList().op(op.nameAndId(), java.util.Optional.of(
                    net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER), java.util.Optional.empty());
            AdminPages.open(op);
            press(helper, op, Text.get("admin.menu.button.kingdoms"));
            press(helper, op, name);
            BookScreen page = BookDialogs.LAST.get(op.getUUID());
            helper.assertTrue(page.buttons().stream().map(BookButton::label).toList().contains(Text.get("admin.kingdom.button.coleader")),
                    "The kingdom page has no second Lord button");
            press(helper, op, Text.get("admin.kingdom.button.unlord"));
            BookScreen form = BookDialogs.LAST.get(op.getUUID());
            helper.assertTrue(form.kind() == BookScreen.Kind.FORM && form.inputs().get(0) instanceof BookInput.Choice c && c.ids().size() == 2,
                    "Remove a Lord does not offer the two Lords: " + form.inputs());
            helper.succeed();
        }
    }

    private static void press(GameTestHelper helper, ServerPlayer player, String label) {
        var tokens = CharterCommands.tokens();
        String token = tokens.newest(player.getUUID(), label).orElse(null);
        helper.assertTrue(token != null, "No [" + label + "] button was shown");
        tokens.take(token, player.getUUID(), System.currentTimeMillis()).orElseThrow().accept(player);
    }
}
