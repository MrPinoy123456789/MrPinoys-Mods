package charter.gametest;

import charter.engine.Actor;
import charter.engine.Outcome;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.id.VoteId;
import charter.mc.Text;
import charter.mc.book.BookButton;
import charter.mc.book.BookDialogs;
import charter.mc.book.BookScreen;
import charter.mc.book.CharterBook;
import charter.mc.book.LeaderPages;
import charter.mc.book.MemberPages;
import charter.mc.book.MorePages;
import charter.mc.book.NeedsPages;
import charter.mc.book.NewcomerPages;
import charter.mc.book.StandPages;
import charter.mc.cmd.CharterCommands;
import charter.mc.cmd.Teleports;
import charter.mc.justice.JusticePages;
import charter.org.actions.AcceptInvite;
import charter.org.actions.Apply;
import charter.org.actions.InviteMember;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.ChatFormatting;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

/**
 * The Bedrock first layout (plan, Phase 4): Home order, the fold rule, every player screen held to the screen rules,
 * leader gating, and the press budget measured from the book in hand.
 */
public final class BookLayoutGameTest {

    private static PlayerId id(ServerPlayer p) {
        return new PlayerId(p.getUUID());
    }

    private static void asOp(LandKit kit, String command) {
        kit.server.getCommands().performPrefixedCommand(kit.server.createCommandSourceStack(), command);
    }

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

    private static VoteId openVote(LandKit kit, ServerPlayer lord, OrgId org) {
        asOp(kit, "charter admin age " + lord.getUUID() + " 4");
        Outcome opened = kit.rt.engine.propose(id(lord), new charter.gov.actions.CreateOffice(org, "Reeve" + System.nanoTime() % 100000));
        kit.helper.assertTrue(opened instanceof Outcome.VoteOpened, "No vote opened: " + opened);
        return ((Outcome.VoteOpened) opened).vote();
    }

    /** Presses a button by its label, as the client would, and counts it. */
    private static final class Presses {
        final GameTestHelper helper;
        final ServerPlayer player;
        int count;

        Presses(GameTestHelper helper, ServerPlayer player) {
            this.helper = helper;
            this.player = player;
        }

        void book() {
            player.setItemInHand(InteractionHand.MAIN_HAND, CharterBook.create());
            CharterBook.use(player, InteractionHand.MAIN_HAND);
            count++;
        }

        void press(String label) {
            var tokens = CharterCommands.tokens();
            String token = tokens.newest(player.getUUID(), label).orElse(null);
            helper.assertTrue(token != null, "No [" + label + "] button on " + shown().title() + ": " + labels());
            tokens.take(token, player.getUUID(), System.currentTimeMillis()).orElseThrow().accept(player);
            count++;
        }

        BookScreen shown() {
            return BookDialogs.LAST.get(player.getUUID());
        }

        List<String> labels() {
            return shown().buttons().stream().map(BookButton::label).toList();
        }
    }

    private static List<String> labels(ServerPlayer p) {
        return BookDialogs.LAST.get(p.getUUID()).buttons().stream().map(BookButton::label).toList();
    }

    private static boolean hovers(Component c) {
        return c.visit((style, text) -> style.getHoverEvent() != null ? Optional.of(true) : Optional.<Boolean>empty(), Style.EMPTY).isPresent();
    }

    /** The screen last shown obeys every rule: limits, no hover, no command typed in text. */
    private static void obeys(GameTestHelper helper, ServerPlayer player, String what) {
        BookScreen screen = BookDialogs.LAST.get(player.getUUID());
        helper.assertTrue(screen != null, what + " showed nothing");
        helper.assertTrue(screen.violations().isEmpty(), what + " (" + screen.title() + ") breaks the screen rules: " + screen.violations());
        for (Component line : screen.body()) {
            helper.assertTrue(!hovers(line), what + " has a hover in its body: " + line.getString());
            helper.assertTrue(!line.getString().contains("/charter"), what + " tells the player to type a command: " + line.getString());
        }
        for (BookButton b : screen.allButtons()) {
            helper.assertTrue(!b.label().contains("/"), what + " has a command in a label: " + b.label());
        }
    }

    private static void show(GameTestHelper helper, ServerPlayer player, String what, Runnable open) {
        BookDialogs.LAST.remove(player.getUUID());
        open.run();
        obeys(helper, player, what);
    }

    @GameTest(maxTicks = 20)
    public void theFoldRuleKeepsHomeAtEight(GameTestHelper helper) {
        helper.assertTrue(MemberPages.layout(6).standOnHome() && MemberPages.layout(6).factionOnHome(), "Six fixed buttons left no room for both");
        helper.assertTrue(MemberPages.layout(7).standOnHome() && !MemberPages.layout(7).factionOnHome(), "At seven, My faction should move under More");
        helper.assertTrue(!MemberPages.layout(8).standOnHome() && !MemberPages.layout(8).factionOnHome(), "At eight, both should move under More");
        for (int fixed = 1; fixed <= 8; fixed++) {
            MemberPages.Layout l = MemberPages.layout(fixed);
            int total = fixed + (l.standOnHome() ? 1 : 0) + (l.factionOnHome() ? 1 : 0);
            helper.assertTrue(total <= BookScreen.MAX_BUTTONS, "Home would hold " + total + " with " + fixed + " fixed");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 20)
    public void theGrantGroupsHoldEveryGrantablePowerOnce(GameTestHelper helper) {
        for (charter.grant.Power power : charter.grant.Power.values()) {
            boolean grantable = power != charter.grant.Power.AMEND && power != charter.grant.Power.DISSOLVE;
            long held = LeaderPages.GROUPS.stream().flatMap(g -> g.powers().stream()).filter(power::equals).count();
            helper.assertTrue(held == (grantable ? 1 : 0), power + " is in the grant groups " + held + " times");
            helper.assertTrue(Text.get("book.grant.power." + power.name().toLowerCase(java.util.Locale.ROOT)).length() <= BookScreen.MAX_LABEL
                    || !grantable, power + " has a label over the limit");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 200)
    public void homePutsTravelFirstThenNeedsThenTheDesk(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Order");
            OrgId org = kit.orgOf(lord);
            kit.keep(lord);
            applicant(kit, org);
            MemberPages.home(lord, null);
            List<String> labels = labels(lord);
            int travel = 0;
            while (travel < labels.size() && labels.get(travel).startsWith("Go ")) travel++;
            helper.assertTrue(travel >= 3 && labels.get(0).startsWith(Text.get("book.travel.home")), "Travel is not first: " + labels);
            helper.assertTrue(labels.contains(Text.get("book.travel.keep")), "A lord with a keep has no Go to keep: " + labels);
            helper.assertTrue(labels.get(travel).equals(Text.get("book.button.needs", "n", 1)), "Needs you is not right after travel: " + labels);
            helper.assertTrue(labels.get(travel + 1).equals(Text.get("book.button.leader")), "The Desk is not after Needs you: " + labels);
            helper.assertTrue(labels.get(labels.size() - 1).equals(Text.get("book.button.more")), "More is not last: " + labels);
            helper.assertTrue(labels.size() <= BookScreen.MAX_BUTTONS, "Home holds " + labels.size());
            helper.assertTrue(BookDialogs.LAST.get(lord.getUUID()).exit().label().equals(Text.get("book.button.close")), "Home's exit is not Close");
            obeys(helper, lord, "the member Home");

            ServerPlayer alone = kit.outsider();
            NewcomerPages.home(alone, null);
            List<String> theirs = labels(alone);
            helper.assertTrue(theirs.get(0).startsWith(Text.get("book.travel.home")) && !theirs.contains(Text.get("book.travel.keep")),
                    "A newcomer's travel is wrong: " + theirs);
            helper.assertTrue(theirs.get(theirs.size() - 1).equals(Text.get("book.button.more")), "More is not last for a newcomer: " + theirs);
            obeys(helper, alone, "the newcomer Home");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 300)
    public void everyPlayerScreenObeysTheRules(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Rules");
            OrgId org = kit.orgOf(lord);
            kit.keep(lord);
            ServerPlayer gil = member(kit, org);
            ServerPlayer asker = applicant(kit, org);
            openVote(kit, lord, org);
            for (ServerPlayer p : List.of(lord, gil)) {
                show(helper, p, "Home", () -> MemberPages.home(p, null));
                show(helper, p, "Needs you", () -> NeedsPages.page(p, null));
                show(helper, p, "Where I stand", () -> StandPages.page(p, null));
                show(helper, p, "My faction", () -> MemberPages.faction(p, null));
                show(helper, p, "Members", () -> MemberPages.members(p, 0));
                show(helper, p, "More", () -> MorePages.page(p, null));
                show(helper, p, "Who decides", () -> MemberPages.whoDecides(p, null));
                show(helper, p, "War", () -> charter.mc.book.WarPages.page(p, null));
                show(helper, p, "Glossary", () -> MorePages.glossary(p, MorePages::page));
            }
            show(helper, lord, "the Desk", () -> LeaderPages.desk(lord, null));
            show(helper, lord, "Requests", () -> LeaderPages.requests(lord, null));
            show(helper, lord, "a request", () -> LeaderPages.request(lord, id(asker), false));
            show(helper, lord, "Invite", () -> LeaderPages.invite(lord, null));
            show(helper, lord, "Treasury", () -> LeaderPages.treasury(lord, null));
            show(helper, lord, "Move the Nexus", () -> LeaderPages.nexus(lord, null));
            show(helper, lord, "Justice", () -> JusticePages.home(lord, null));
            show(helper, lord, "Give a power", () -> LeaderPages.grant(lord, null));
            for (LeaderPages.Group group : LeaderPages.GROUPS) {
                LeaderPages.grant(lord, null);
                Presses presses = new Presses(helper, lord);
                presses.press(Text.get("book.grant.group." + group.key()));
                obeys(helper, lord, "the " + group.key() + " powers");
                presses.press(Text.get("book.grant.power." + group.powers().get(0).name().toLowerCase(java.util.Locale.ROOT)));
                obeys(helper, lord, "who gets " + group.powers().get(0));
            }

            ServerPlayer alone = kit.outsider();
            kit.rt.engine.run(new Actor.Admin("gametest"), new InviteMember(org, id(alone)));
            show(helper, alone, "the newcomer Home", () -> NewcomerPages.home(alone, null));
            show(helper, alone, "Choose a kingdom", () -> NewcomerPages.join(alone, null));
            show(helper, alone, "a kingdom", () -> NewcomerPages.faction(alone, org, null));
            show(helper, alone, "Mercenary", () -> NewcomerPages.mercenary(alone, null));
            show(helper, alone, "the newcomer More", () -> NewcomerPages.more(alone, null));
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void onlyLeadersSeeTheDesk(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Gate");
            ServerPlayer gil = member(kit, kit.orgOf(lord));
            MemberPages.home(gil, null);
            helper.assertTrue(!labels(gil).contains(Text.get("book.button.leader")), "A plain member was shown the Leader's Desk");
            LeaderPages.desk(gil, null);
            BookScreen landed = BookDialogs.LAST.get(gil.getUUID());
            helper.assertTrue(landed.bodyText().get(0).equals(Text.get("book.leader.not_a_leader")), "The desk opened for a plain member");
            helper.assertTrue(TextColor.fromLegacyFormat(ChatFormatting.RED).equals(landed.body().get(0).getStyle().getColor()), "The refusal was not red");
            LeaderPages.desk(lord, null);
            helper.assertTrue(labels(lord).contains(Text.get("book.leader.invite")), "The Lord's desk has no Invite: " + labels(lord));
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void becomingAMercenaryAsksFirstAndBackChangesNothing(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Merc");
            OrgId org = kit.orgOf(lord);
            ServerPlayer alone = kit.outsider();
            kit.rt.state.touch(id(alone), alone.getPlainTextName(), kit.rt.clock.now());
            String race = kit.rt.state.org(org).orElseThrow().raceLabel();
            Presses presses = new Presses(helper, alone);
            NewcomerPages.mercConfirm(alone, org);
            helper.assertTrue(presses.shown().kind() == BookScreen.Kind.CONFIRM, "Choosing a race did not ask first");
            obeys(helper, alone, "the mercenary confirm");
            presses.press(Text.get("book.button.back"));
            helper.assertTrue(kit.rt.state.raceOf(id(alone)).isEmpty(), "Back chose a race");
            NewcomerPages.mercConfirm(alone, org);
            presses.press(Text.get("book.merc.verb", "race", BookDialogs.clip(race, 14)));
            helper.assertTrue(kit.rt.state.raceOf(id(alone)).filter(org::equals).isPresent(), "The verb did not make a mercenary of that race");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void aPowerIsGivenAndAPlayerInvitedWithoutTyping(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Hand");
            OrgId org = kit.orgOf(lord);
            member(kit, org);
            Presses presses = new Presses(helper, lord);
            LeaderPages.grant(lord, null);
            presses.press(Text.get("book.grant.group.members"));
            presses.press(Text.get("book.grant.power.manage_members"));
            BookScreen who = presses.shown();
            helper.assertTrue(who.kind() == BookScreen.Kind.LIST && !who.buttons().isEmpty(), "Who gets the power is not a list of buttons");
            presses.press(who.buttons().get(who.buttons().size() - 1).label());
            BookScreen landed = presses.shown();
            helper.assertTrue(TextColor.fromLegacyFormat(ChatFormatting.GREEN).equals(landed.body().get(0).getStyle().getColor()),
                    "Giving the power was not done: " + landed.bodyText());

            ServerPlayer free = kit.outsider();
            kit.rt.state.touch(id(free), free.getPlainTextName(), kit.rt.clock.now());
            LeaderPages.invite(lord, null);
            BookScreen invite = presses.shown();
            if (invite.kind() == BookScreen.Kind.FORM) {
                // More than eight are online: a dropdown, answered the way the client answers it.
                var forms = CharterCommands.forms();
                String token = forms.newest(lord.getUUID(), invite.submit().label()).orElseThrow();
                forms.take(token, lord.getUUID(), System.currentTimeMillis()).orElseThrow()
                        .accept(new CharterCommands.Form(lord, "=" + free.getUUID()));
            } else {
                String label = invite.buttons().stream().map(BookButton::label).filter(l -> l.startsWith("Invite ")).findFirst().orElseThrow();
                presses.press(label);
            }
            helper.assertTrue(kit.rt.state.invites().stream().anyMatch(i -> i.org().equals(org)), "Inviting left no invitation");
            helper.succeed();
        }
    }

    // ---- the press budget, measured from the book in hand ----

    @GameTest(maxTicks = 200)
    public void goToYourKeepTakesTwoPresses(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Trip");
            kit.keep(lord);
            Vec3 before = lord.position();
            Presses presses = new Presses(helper, lord);
            presses.book();
            presses.press(Text.get("book.travel.keep"));
            helper.assertTrue(Teleports.waiting(lord) || lord.position().distanceToSqr(before) > 1, "Go to keep started no journey");
            helper.assertTrue(presses.count == 2, "Go to keep took " + presses.count + " presses");
            Teleports.reset();
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void aWaitingVoteTakesThreePresses(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Poll");
            OrgId org = kit.orgOf(lord);
            ServerPlayer gil = member(kit, org);
            VoteId vote = openVote(kit, lord, org);
            Presses presses = new Presses(helper, gil);
            presses.book();
            presses.press(Text.get("book.button.needs", "n", 1));
            presses.press(Text.get("book.button.yes"));
            helper.assertTrue(kit.rt.state.vote(vote).orElseThrow().ballots().containsKey(id(gil)), "The vote was not cast");
            helper.assertTrue(presses.count == 3, "Voting took " + presses.count + " presses");
            BookScreen landed = presses.shown();
            helper.assertTrue(landed.title().equals(Text.get("book.needs.title")), "Casting did not land on Needs you: " + landed.title());
            helper.assertTrue(TextColor.fromLegacyFormat(ChatFormatting.GREEN).equals(landed.body().get(0).getStyle().getColor()),
                    "The cast vote was not reported in green");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void anInvitationTakesTwoPresses(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Hall");
            OrgId org = kit.orgOf(lord);
            ServerPlayer alone = kit.outsider();
            kit.rt.state.touch(id(alone), alone.getPlainTextName(), kit.rt.clock.now());
            kit.rt.engine.run(new Actor.Admin("gametest"), new InviteMember(org, id(alone)));
            Presses presses = new Presses(helper, alone);
            presses.book();
            presses.press(Text.get("book.needs.invite", "org", BookDialogs.clip(kit.rt.describe.org(org), 18)));
            helper.assertTrue(kit.rt.state.isMember(id(alone), org), "Accepting did not join");
            helper.assertTrue(presses.count == 2, "Accepting took " + presses.count + " presses");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void admittingTakesFourPressesFromTheDesk(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Door");
            OrgId org = kit.orgOf(lord);
            ServerPlayer asker = applicant(kit, org);
            Presses presses = new Presses(helper, lord);
            presses.book();
            presses.press(Text.get("book.button.leader"));
            presses.press(Text.get("book.leader.requests_n", "n", 1));
            presses.press(Text.get("book.button.admit", "player", BookDialogs.clip(kit.rt.describe.player(id(asker)), 14)));
            helper.assertTrue(kit.rt.state.isMember(id(asker), org), "The applicant was not admitted");
            helper.assertTrue(presses.count == 4, "Admitting took " + presses.count + " presses");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void admittingTakesThreePressesFromNeedsYou(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Gate");
            OrgId org = kit.orgOf(lord);
            ServerPlayer asker = applicant(kit, org);
            Presses presses = new Presses(helper, lord);
            presses.book();
            presses.press(Text.get("book.button.needs", "n", 1));
            presses.press(Text.get("book.button.admit", "player", BookDialogs.clip(kit.rt.describe.player(id(asker)), 14)));
            helper.assertTrue(kit.rt.state.isMember(id(asker), org), "The applicant was not admitted");
            helper.assertTrue(presses.count == 3, "Admitting took " + presses.count + " presses");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void takingMarksTakesFourPresses(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Coin");
            OrgId org = kit.orgOf(lord);
            long before = kit.rt.state.payment.balance(org.toString());
            helper.assertTrue(before >= 1, "The test treasury is empty");
            Presses presses = new Presses(helper, lord);
            presses.book();
            presses.press(Text.get("book.button.leader"));
            presses.press(Text.get("book.leader.treasury"));
            presses.press(Text.get("book.leader.take", "n", 1));
            // The payout itself runs through the currency mod's commands, absent here, so the page's answer is what is checked.
            BookScreen landed = presses.shown();
            helper.assertTrue(landed.title().equals(Text.get("book.leader.treasury")), "Take 1 did not land on the treasury: " + landed.title());
            helper.assertTrue(landed.bodyText().get(0).equals(Text.get("vault.withdrawn", "amount", 1))
                    && TextColor.fromLegacyFormat(ChatFormatting.GREEN).equals(landed.body().get(0).getStyle().getColor()),
                    "Take 1 was not reported as done: " + landed.bodyText() + " (balance " + before + ")");
            helper.assertTrue(presses.count == 4, "Taking Marks took " + presses.count + " presses");
            helper.succeed();
        }
    }
}
