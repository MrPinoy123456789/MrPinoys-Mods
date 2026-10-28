package charter.gametest;

import charter.engine.Actor;
import charter.engine.Outcome;
import charter.gov.Office;
import charter.gov.Preset;
import charter.gov.Vote;
import charter.gov.actions.Appoint;
import charter.gov.actions.SetSeats;
import charter.id.OfficeId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.id.VoteId;
import charter.mc.CharterRuntime;
import charter.mc.Text;
import charter.mc.book.BookDialogs;
import charter.mc.book.MemberPages;
import charter.mc.cmd.CharterCommands;
import charter.org.actions.AcceptInvite;
import charter.org.actions.Apply;
import charter.org.actions.Found;
import charter.org.actions.InviteMember;
import charter.org.actions.Rename;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/** The pages of the Charter book for a member (implementation plan, B5). */
public final class MemberPagesGameTest {

    private static PlayerId id(ServerPlayer p) {
        return new PlayerId(p.getUUID());
    }

    private static void asOp(LandKit kit, String command) {
        kit.server.getCommands().performPrefixedCommand(kit.server.createCommandSourceStack(), command);
    }

    /** A new member of the founder's faction, aged enough to vote. */
    private static ServerPlayer member(LandKit kit, OrgId org) {
        ServerPlayer p = kit.outsider();
        kit.rt.state.touch(id(p), p.getPlainTextName(), kit.rt.clock.now());
        kit.rt.engine.run(new Actor.Admin("gametest"), new InviteMember(org, id(p)));
        kit.rt.engine.run(new Actor.Player(id(p)), new AcceptInvite(id(p), org));
        asOp(kit, "charter admin age " + p.getUUID() + " 4");
        return p;
    }

    private static ServerPlayer applicant(LandKit kit, OrgId org) {
        ServerPlayer p = kit.outsider();
        kit.rt.state.touch(id(p), p.getPlainTextName(), kit.rt.clock.now());
        Outcome asked = kit.rt.engine.run(new Actor.Player(id(p)), new Apply(id(p), org));
        kit.helper.assertTrue(asked.done(), "The applicant could not ask: " + asked);
        return p;
    }

    private static void press(GameTestHelper helper, ServerPlayer player, String label) {
        var tokens = CharterCommands.tokens();
        String token = tokens.newest(player.getUUID(), label).orElse(null);
        helper.assertTrue(token != null, "No [" + label + "] button was shown to the player");
        tokens.take(token, player.getUUID(), System.currentTimeMillis()).orElseThrow().accept(player);
    }

    private static List<String> body(ServerPlayer player) {
        Dialog dialog = BookDialogs.LAST.get(player.getUUID());
        List<String> out = new ArrayList<>();
        for (var b : dialog.common().body()) {
            if (b instanceof PlainMessage m) out.add(m.contents().getString());
        }
        return out;
    }

    private static void hasButton(GameTestHelper helper, ServerPlayer player, String page) {
        Dialog dialog = BookDialogs.LAST.get(player.getUUID());
        helper.assertTrue(dialog != null, "The " + page + " page sent no dialog");
        helper.assertTrue(!BookDialogs.buttons(dialog).isEmpty(), "The " + page + " page has no button");
    }

    private static void everyPage(GameTestHelper helper, ServerPlayer player, VoteId vote) {
        String[] pages = {"home", "rights", "freedoms", "situation", "faction", "who decides", "vote"};
        for (String page : pages) {
            BookDialogs.LAST.remove(player.getUUID());
            switch (page) {
                case "home" -> MemberPages.home(player, null);
                case "rights" -> MemberPages.rights(player, null);
                case "freedoms" -> MemberPages.freedoms(player, null);
                case "situation" -> MemberPages.situation(player, null);
                case "faction" -> MemberPages.faction(player, null);
                case "who decides" -> MemberPages.whoDecides(player, null);
                default -> {
                    if (vote == null) continue;
                    MemberPages.vote(player, vote, null);
                }
            }
            hasButton(helper, player, page);
        }
        MemberPages.chronicle(player);
    }

    @GameTest(maxTicks = 200)
    public void everyPageWorksFullAndEmpty(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lone = kit.faction("Lone");
            everyPage(helper, lone, null);

            ServerPlayer lord = kit.faction("Full");
            OrgId org = kit.orgOf(lord);
            asOp(kit, "charter admin age " + lord.getUUID() + " 4");
            ServerPlayer gil = member(kit, org);
            applicant(kit, org);
            kit.keep(lord);
            Outcome opened = kit.rt.engine.propose(id(lord), new Rename(org, "Renamed" + System.nanoTime() % 100000));
            VoteId vote = opened instanceof Outcome.VoteOpened v ? v.vote() : null;
            helper.assertTrue(vote != null && kit.rt.state.vote(vote).orElseThrow().open(), "The setup vote is not open");
            everyPage(helper, lord, vote);
            everyPage(helper, gil, vote);
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void aLordshipAndACommuneReadDifferently(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Crown");
            ServerPlayer gil = member(kit, kit.orgOf(lord));
            MemberPages.freedoms(gil, null);
            List<String> lordship = body(gil);
            helper.assertTrue(lordship.size() <= 3, "A Lordship member's Freedoms run to " + lordship.size() + " lines: " + lordship);

            ServerPlayer founder = kit.outsider();
            kit.rt.state.touch(id(founder), founder.getPlainTextName(), kit.rt.clock.now());
            Outcome found = kit.rt.engine.run(new Actor.Player(id(founder)),
                    new Found(id(founder), "Commune" + System.nanoTime() % 1000000, Preset.COMMUNE));
            helper.assertTrue(found.done(), "The Commune was not founded: " + found);
            MemberPages.freedoms(founder, null);
            List<String> commune = body(founder);
            helper.assertTrue(!lordship.equals(commune), "A Lordship and a Commune member read the same Freedoms");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void aNewMemberIsToldWhenTheyCanVote(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Wait");
            OrgId org = kit.orgOf(lord);
            // The lone founder votes at once, so is told nothing.
            MemberPages.faction(lord, null);
            helper.assertTrue(body(lord).stream().noneMatch(l -> l.startsWith("You can vote on changes")), "A lone founder was told to wait");
            ServerPlayer young = kit.outsider();
            kit.rt.state.touch(id(young), young.getPlainTextName(), kit.rt.clock.now());
            kit.rt.engine.run(new Actor.Admin("gametest"), new InviteMember(org, id(young)));
            kit.rt.engine.run(new Actor.Player(id(young)), new AcceptInvite(id(young), org));
            MemberPages.faction(young, null);
            helper.assertTrue(body(young).stream().anyMatch(l -> l.startsWith("You can vote on changes in")),
                    "A new member was not told when they can vote: " + body(young));
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void aShrunkCouncilSeesItsElectionOnTheFactionPage(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Shrink");
            OrgId org = kit.orgOf(lord);
            asOp(kit, "charter admin age " + lord.getUUID() + " 4");
            ServerPlayer second = member(kit, org);
            OfficeId deciders = kit.rt.state.org(org).orElseThrow().governance.deciders;
            var admin = new Actor.Admin("gametest");
            kit.rt.engine.run(admin, new SetSeats(org, deciders, 2, Office.Deciding.ALONE));
            kit.rt.engine.run(admin, new Appoint(org, deciders, id(second)));
            kit.rt.engine.run(admin, new SetSeats(org, deciders, 1, Office.Deciding.ALONE));
            helper.assertTrue(kit.rt.state.office(deciders).orElseThrow().vacant(), "The shrunk council kept a holder");
            Vote election = kit.rt.state.votes().stream().filter(v -> v.open() && deciders.equals(v.office)).findFirst().orElse(null);
            helper.assertTrue(election != null && election.restricted() && election.electorate.size() == 2,
                    "No election among the two former holders opened");
            MemberPages.faction(second, null);
            helper.assertTrue(body(second).stream().anyMatch(l -> l.contains("open among the council")),
                    "The faction page does not say the council is choosing: " + body(second));
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void aDeciderChangesTheFormFromTheBook(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Form");
            OrgId org = kit.orgOf(lord);
            OfficeId deciders = kit.rt.state.org(org).orElseThrow().governance.deciders;
            MemberPages.whoDecides(lord, null);
            press(helper, lord, Text.get("book.decides.to_council_together"));
            press(helper, lord, Text.get("book.decides.seats", "n", 3));
            var office = kit.rt.state.office(deciders).orElseThrow();
            helper.assertTrue(office.seats() == 3 && office.deciding() == Office.Deciding.TOGETHER && office.holds(id(lord)),
                    "The Lord did not make a council of three deciding together: " + office.seats());

            ServerPlayer plain = member(kit, org);
            MemberPages.whoDecides(plain, null);
            helper.assertTrue(BookDialogs.buttons(BookDialogs.LAST.get(plain.getUUID())).stream()
                    .noneMatch(b -> b.button().label().getString().equals(Text.get("book.decides.to_lordship"))),
                    "A member who is not a decider was offered the deciders' changes");
            helper.assertTrue(CharterCommands.tokens().newest(plain.getUUID(), Text.get("book.decides.everyone")).isPresent(),
                    "A member who is not a decider was not offered the members' list");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void castingYesFromTheVotePage(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Ballot");
            OrgId org = kit.orgOf(lord);
            asOp(kit, "charter admin age " + lord.getUUID() + " 4");
            ServerPlayer gil = member(kit, org);
            Outcome opened = kit.rt.engine.propose(id(lord), new Rename(org, "Ballot" + System.nanoTime() % 100000));
            VoteId vote = ((Outcome.VoteOpened) opened).vote();
            MemberPages.vote(gil, vote, null);
            press(helper, gil, Text.get("book.button.yes"));
            Vote.Ballot ballot = kit.rt.state.vote(vote).orElseThrow().ballots().get(id(gil));
            helper.assertTrue(ballot != null && ballot.choice() == Vote.Choice.YES, "Gil's Yes was not recorded: " + ballot);
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void admitAndRejectFromTheFactionPage(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Gate");
            OrgId org = kit.orgOf(lord);
            ServerPlayer first = applicant(kit, org);
            String name = kit.rt.describe.player(id(first));
            MemberPages.faction(lord, null);
            press(helper, lord, Text.get("book.button.admit", "player", name));
            helper.assertTrue(kit.rt.state.isMember(id(first), org), "The applicant was not admitted");

            ServerPlayer second = applicant(kit, org);
            MemberPages.faction(lord, null);
            press(helper, lord, Text.get("book.button.reject", "player", name));
            helper.assertTrue(kit.rt.state.joinRequest(org, id(second)).isPresent(), "Reject acted before the confirm screen");
            helper.assertTrue(BookDialogs.LAST.get(lord.getUUID()) instanceof net.minecraft.server.dialog.ConfirmationDialog,
                    "Reject showed no confirm screen");
            press(helper, lord, Text.get("book.button.yes"));
            helper.assertTrue(kit.rt.state.joinRequest(org, id(second)).isEmpty(), "The confirmed Reject left the request");
            helper.assertTrue(!kit.rt.state.isMember(id(second), org), "The rejected player became a member");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void whoDecidesAndTheElection(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Choice");
            OrgId org = kit.orgOf(lord);
            asOp(kit, "charter admin age " + lord.getUUID() + " 4");
            MemberPages.whoDecidesMembers(lord, null);
            press(helper, lord, Text.get("book.decides.everyone"));
            boolean proposed = kit.rt.state.votes().stream().anyMatch(v -> v.org.equals(org)
                    && v.motion instanceof SetSeats s && s.seats() == 0);
            helper.assertTrue(proposed, "Everyone, by vote did not put a SetSeats of 0 to a vote");

            ServerPlayer other = kit.faction("Seat");
            OrgId orgTwo = kit.orgOf(other);
            asOp(kit, "charter admin age " + other.getUUID() + " 4");
            // Someone to elect: a seated holder cannot be named again. Mock players share a name,
            // so the newest "Vote for" button is the last member listed, the newcomer.
            ServerPlayer candidate = member(kit, orgTwo);
            OfficeId deciders = kit.rt.state.org(orgTwo).orElseThrow().governance.deciders;
            kit.rt.engine.run(new Actor.Admin("gametest"), new SetSeats(orgTwo, deciders, 2, Office.Deciding.ALONE));
            MemberPages.faction(other, null);
            press(helper, other, Text.get("book.faction.elect"));
            press(helper, other, Text.get("book.vote.for", "name", kit.rt.describe.player(id(candidate))));
            boolean cast = kit.rt.state.votes().stream().anyMatch(v -> v.kind == Vote.Kind.ELECTION && deciders.equals(v.office)
                    && v.ballots().containsKey(id(other)) && v.ballots().get(id(other)).choice() == Vote.Choice.CANDIDATE
                    && id(candidate).equals(v.ballots().get(id(other)).candidate()));
            helper.assertTrue(cast, "The election page button cast no candidate ballot");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void aCouncilAdmitsThroughASeatVote(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Council");
            OrgId org = kit.orgOf(lord);
            asOp(kit, "charter admin age " + lord.getUUID() + " 4");
            ServerPlayer second = member(kit, org);
            OfficeId deciders = kit.rt.state.org(org).orElseThrow().governance.deciders;
            var admin = new Actor.Admin("gametest");
            kit.rt.engine.run(admin, new SetSeats(org, deciders, 2, Office.Deciding.TOGETHER));
            kit.rt.engine.run(admin, new Appoint(org, deciders, id(second)));
            ServerPlayer applicant = applicant(kit, org);

            MemberPages.faction(lord, null);
            press(helper, lord, Text.get("book.button.admit", "player", kit.rt.describe.player(id(applicant))));
            helper.assertTrue(!kit.rt.state.isMember(id(applicant), org), "A council member admitted alone");
            Vote seats = kit.rt.state.votes().stream().filter(v -> v.open() && deciders.equals(v.seatsOf)).findFirst().orElse(null);
            helper.assertTrue(seats != null, "No vote among the seats was opened");
            kit.rt.engine.cast(id(second), seats.id, Vote.Ballot.YES);
            helper.assertTrue(kit.rt.state.isMember(id(applicant), org), "The second Yes did not admit the applicant");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void theChronicleTouchesNoInventory(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Annals");
            var before = lord.getInventory().getSelectedItem().copy();
            int slot = lord.getInventory().getSelectedSlot();
            MemberPages.chronicle(lord);
            helper.assertTrue(lord.getInventory().getSelectedSlot() == slot, "The Chronicle changed the selected slot");
            helper.assertTrue(net.minecraft.world.item.ItemStack.matches(before, lord.getInventory().getSelectedItem()),
                    "The Chronicle changed the item in the selected slot");
            helper.succeed();
        }
    }
}
