package charter.gametest;

import charter.engine.Actor;
import charter.grant.GrantScope;
import charter.grant.Holder;
import charter.grant.Power;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.book.BookButton;
import charter.mc.book.BookDialogs;
import charter.mc.book.BookScreen;
import charter.mc.book.CharterBook;
import charter.mc.book.DisciplinePages;
import charter.mc.book.LeaderPages;
import charter.mc.cmd.CharterCommands;
import charter.mc.moderation.LordActions;
import charter.moderation.LordRules;
import charter.org.actions.AddMember;
import charter.time.Durations;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.ChatFormatting;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

import java.util.List;
import java.util.Optional;

/** The Lord's Discipline pages: who sees them, every step pressed, the same rules as the typed commands, and the presses counted. */
public final class DisciplineGameTest {

    private static PlayerId id(ServerPlayer p) {
        return new PlayerId(p.getUUID());
    }

    private static ServerPlayer citizen(LandKit kit, OrgId org) {
        ServerPlayer p = kit.outsider();
        kit.rt.state.touch(id(p), p.getPlainTextName(), kit.rt.clock.now());
        kit.rt.engine.run(new Actor.Admin("gametest"), new AddMember(org, id(p)));
        return p;
    }

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

        void submit(String label, String raw) {
            var forms = CharterCommands.forms();
            String token = forms.newest(player.getUUID(), label).orElse(null);
            helper.assertTrue(token != null, "No [" + label + "] submit on " + shown().title());
            forms.take(token, player.getUUID(), System.currentTimeMillis()).orElseThrow().accept(new CharterCommands.Form(player, raw));
            count++;
        }

        BookScreen shown() {
            return BookDialogs.LAST.get(player.getUUID());
        }

        List<String> labels() {
            return shown().buttons().stream().map(BookButton::label).toList();
        }
    }

    private static boolean green(Component line) {
        return TextColor.fromLegacyFormat(ChatFormatting.GREEN).equals(line.getStyle().getColor());
    }

    private static boolean red(Component line) {
        return TextColor.fromLegacyFormat(ChatFormatting.RED).equals(line.getStyle().getColor());
    }

    /** The screen shown obeys the screen rules and never tells the player to type a command. */
    private static void obeys(GameTestHelper helper, ServerPlayer player, String what) {
        BookScreen screen = BookDialogs.LAST.get(player.getUUID());
        helper.assertTrue(screen.violations().isEmpty(), what + " breaks the screen rules: " + screen.violations());
        for (Component line : screen.body()) {
            helper.assertTrue(!line.getString().contains("/charter"), what + " tells the player to type a command: " + line.getString());
            boolean hover = line.visit((style, text) -> style.getHoverEvent() != null ? Optional.of(true) : Optional.<Boolean>empty(), Style.EMPTY).isPresent();
            helper.assertTrue(!hover, what + " has a hover: " + line.getString());
        }
    }

    @GameTest(maxTicks = 200)
    public void onlyALordSeesDisciplineAndAJudgeKeepsJustice(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer ann = kit.faction("Order");
            OrgId org = kit.orgOf(ann);
            LeaderPages.desk(ann, null);
            List<String> labels = BookDialogs.LAST.get(ann.getUUID()).buttons().stream().map(BookButton::label).toList();
            helper.assertTrue(labels.contains(Text.get("book.order.title")), "The Lord's desk has no Discipline citizens: " + labels);
            helper.assertTrue(labels.size() <= BookScreen.MAX_BUTTONS, "The desk holds " + labels.size());
            obeys(helper, ann, "the Lord's desk");

            ServerPlayer gil = citizen(kit, org);
            var admin = new Actor.Admin("gametest");
            for (Power power : List.of(Power.MANAGE_MEMBERS, Power.JUDGE)) {
                kit.rt.engine.run(admin, new charter.grant.actions.GrantPower(org, power, new Holder.OfPlayer(id(gil)), GrantScope.ANY, false, 0));
            }
            LeaderPages.desk(gil, null);
            List<String> theirs = BookDialogs.LAST.get(gil.getUUID()).buttons().stream().map(BookButton::label).toList();
            helper.assertTrue(!theirs.contains(Text.get("book.order.title")), "A judge who is no Lord saw Discipline citizens: " + theirs);
            helper.assertTrue(theirs.contains(Text.get("book.leader.justice")), "A judge who is no Lord lost Justice: " + theirs);
            DisciplinePages.page(gil, null);
            BookScreen landed = BookDialogs.LAST.get(gil.getUUID());
            helper.assertTrue(landed.title().equals(Text.get("book.leader.title")) && red(landed.body().get(0)),
                    "Discipline opened for someone who is no Lord: " + landed.title());
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void aLordMutesACitizenInSevenPressesAndUnmutesInFive(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer ann = kit.faction("Hush");
            OrgId org = kit.orgOf(ann);
            ServerPlayer bob = citizen(kit, org);
            String name = kit.rt.describe.player(id(bob));
            Presses presses = new Presses(helper, ann);
            presses.book();
            presses.press(Text.get("book.button.leader"));
            presses.press(Text.get("book.order.title"));
            obeys(helper, ann, "Discipline");
            presses.press(BookDialogs.clip(name, BookScreen.MAX_LABEL));
            obeys(helper, ann, "the citizen");
            presses.press(Text.get("book.order.mute"));
            obeys(helper, ann, "how long");
            presses.press(Text.get("book.order.minutes", "n", 10));
            obeys(helper, ann, "why");
            presses.press(Text.get("book.order.reason.1"));
            long now = kit.rt.clock.now();
            long until = kit.rt.state.player(id(bob)).mutedUntil;
            helper.assertTrue(until > now && until <= now + Durations.minutes(10) + 1000, "The citizen was not muted for ten minutes");
            helper.assertTrue(presses.count == 7, "Muting took " + presses.count + " presses");
            BookScreen landed = presses.shown();
            helper.assertTrue(landed.title().equals(Text.get("book.order.page_title")) && green(landed.body().get(0)),
                    "Muting did not land on Discipline with a green line: " + landed.bodyText());
            helper.assertTrue(String.join("\n", landed.bodyText()).contains(name), "The held list does not name the citizen: " + landed.bodyText());

            Presses lift = new Presses(helper, ann);
            lift.book();
            lift.press(Text.get("book.button.leader"));
            lift.press(Text.get("book.order.title"));
            lift.press(BookDialogs.clip(name, 13) + " " + Text.get("book.order.tag_muted"));
            lift.press(Text.get("book.order.unmute"));
            helper.assertTrue(kit.rt.state.player(id(bob)).mutedUntil <= kit.rt.clock.now(), "Unmute did not lift the mute");
            helper.assertTrue(lift.count == 5, "Unmuting took " + lift.count + " presses");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void aWrittenReasonIsCheckedAndABarKeepsTheMember(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer ann = kit.faction("Gate");
            OrgId org = kit.orgOf(ann);
            ServerPlayer bob = citizen(kit, org);
            Presses presses = new Presses(helper, ann);
            DisciplinePages.citizen(ann, bob.getUUID(), null);
            presses.press(Text.get("book.order.suspend"));
            presses.press(Text.get("book.time.hour"));
            presses.press(Text.get("book.order.reason_other"));
            obeys(helper, ann, "the written reason");
            presses.submit(Text.get("book.order.reason_send"), "=no");
            helper.assertTrue(kit.rt.state.player(id(bob)).suspendedUntil == 0, "A two letter reason was taken");
            helper.assertTrue(red(presses.shown().body().get(0)), "A short reason was not refused in red");
            presses.submit(Text.get("book.order.reason_send"), "=broke the farm rules");
            helper.assertTrue(kit.rt.state.player(id(bob)).suspendedUntil > kit.rt.clock.now(), "The written reason did not bar them");
            helper.assertTrue(kit.rt.state.isMember(id(bob), org), "A barred citizen lost their membership");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void operatorsAndLordsAreNotOfferedAndTheTimesKeepToTheCap(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer ann = kit.faction("Reach");
            OrgId org = kit.orgOf(ann);
            ServerPlayer staff = citizen(kit, org);
            kit.server.getPlayerList().op(staff.nameAndId(), Optional.of(
                    net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
            try {
                DisciplinePages.page(ann, null);
                BookScreen page = BookDialogs.LAST.get(ann.getUUID());
                helper.assertTrue(page.buttons().isEmpty() || page.kind() != BookScreen.Kind.LIST || page.buttons().stream()
                        .noneMatch(b -> b.label().startsWith(BookDialogs.clip(kit.rt.describe.player(id(staff)), 13))),
                        "An operator was offered to a Lord: " + page.buttons().stream().map(BookButton::label).toList());
                LordActions.Result result = LordActions.run(ann, LordActions.Kind.MUTE, staff.getUUID(), Durations.minutes(5), "rude words");
                helper.assertTrue(!result.ok(), "The shared path muted an operator");
            } finally {
                kit.server.getPlayerList().deop(staff.nameAndId());
            }

            var knobs = CharterServer.runtime().knobs;
            int was = knobs.lordMuteMaxMinutes;
            knobs.lordMuteMaxMinutes = 20;
            try {
                List<Long> lengths = DisciplinePages.lengths(LordActions.Kind.MUTE);
                helper.assertTrue(lengths.equals(List.of(Durations.minutes(5), Durations.minutes(10), Durations.minutes(20))),
                        "Mute lengths under a 20 minute cap: " + lengths);
                helper.assertTrue(DisciplinePages.lengths(LordActions.Kind.SUSPEND).stream().allMatch(l -> l <= LordRules.suspendCap(knobs)),
                        "A bar preset passes the cap");
            } finally {
                knobs.lordMuteMaxMinutes = was;
            }
            helper.succeed();
        }
    }
}
