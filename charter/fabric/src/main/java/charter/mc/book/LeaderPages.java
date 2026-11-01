package charter.mc.book;

import charter.gov.Office;
import charter.gov.Standing;
import charter.grant.AuthorityResolver;
import charter.grant.GrantScope;
import charter.grant.Holder;
import charter.grant.Power;
import charter.grant.ScopeNeed;
import charter.grant.Who;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Describe;
import charter.mc.Text;
import charter.mc.Treasury;
import charter.org.MemberRecord;
import charter.org.actions.InviteMember;
import charter.treasury.actions.Withdraw;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The Leader's Desk: every management command a lord or other officer needs, in one page of the book, for players
 * who cannot type {@code /charter invite}, {@code grant}, {@code vault withdraw} or {@code nexus move}. Each screen
 * takes its choices from dropdowns (names are picked, never typed) and runs the same action the command runs, so a
 * rule lives in one place. Whether a power is really held is still decided by the engine on every press.
 */
public final class LeaderPages {

    private static final int NAME_MAX = 32;
    private static final int AMOUNT_MAX = 9;

    private LeaderPages() {
    }

    private static CharterRuntime rt() {
        return CharterServer.runtime();
    }

    private static PlayerId me(ServerPlayer player) {
        return new PlayerId(player.getUUID());
    }

    private static BookButton go(ServerPlayer player, String label, java.util.function.Consumer<ServerPlayer> page) {
        return BookDialogs.act(player, label, null, page);
    }

    /** True for someone who holds an office, or may take members in: the players this desk is for. */
    public static boolean leads(ServerPlayer player) {
        CharterRuntime rt = rt();
        Optional<Standing.View> view = Standing.of(rt.state, rt.engine, me(player), rt.clock.now());
        if (view.isEmpty()) return false;
        if (!view.get().yourOffices().isEmpty()) return true;
        return new AuthorityResolver(rt.state, rt.clock.now())
                .resolve(new Who.AsPlayer(me(player)), view.get().org(), Power.MANAGE_MEMBERS, ScopeNeed.NONE, false).ok();
    }

    /** The org the player belongs to, or null after sending them to the newcomer home. */
    private static OrgId org(ServerPlayer player) {
        Optional<MemberRecord> member = rt().state.membership(me(player));
        if (member.isEmpty()) {
            NewcomerPages.home(player, null);
            return null;
        }
        return member.get().org();
    }

    // ---- The desk ----

    public static void desk(ServerPlayer player, String notice) {
        OrgId org = org(player);
        if (org == null) return;
        CharterRuntime rt = rt();
        if (!leads(player)) {
            MemberPages.home(player, Text.get("book.leader.not_a_leader"));
            return;
        }
        List<net.minecraft.network.chat.Component> body = new ArrayList<>();
        body.add(BookDialogs.text(Text.get("book.leader.intro")));
        long asking = rt.state.joinRequests().stream().filter(r -> r.org().equals(org)).count();
        List<BookButton> buttons = new ArrayList<>();
        buttons.add(go(player, Text.get(asking > 0 ? "book.leader.requests_n" : "book.leader.requests", "n", asking),
                p -> MemberPages.faction(p, null)));
        buttons.add(go(player, Text.get("book.leader.invite"), p -> invite(p, null)));
        buttons.add(go(player, Text.get("book.leader.grant"), p -> grant(p, null)));
        if (Treasury.available(rt)) buttons.add(go(player, Text.get("book.leader.withdraw"), p -> withdraw(p, null)));
        buttons.add(go(player, Text.get("book.faction.who_decides"), p -> MemberPages.whoDecides(p, null)));
        buttons.add(go(player, Text.get("book.leader.votes"), p -> MemberPages.situation(p, null)));
        buttons.add(go(player, Text.get("book.war.title"), p -> WarPages.page(p, null)));
        buttons.add(go(player, Text.get("book.leader.nexus"), p -> nexus(p, null)));
        BookDialogs.show(player, BookDialogs.list(Text.get("book.leader.title"), BookDialogs.withNotice(notice, body), buttons,
                BookDialogs.back(player, p -> MemberPages.home(p, null))));
    }

    // ---- Invite ----

    public static void invite(ServerPlayer player, String notice) {
        OrgId org = org(player);
        if (org == null) return;
        CharterRuntime rt = rt();
        List<String> ids = new ArrayList<>();
        List<String> shown = new ArrayList<>();
        for (ServerPlayer other : player.level().getServer().getPlayerList().getPlayers()) {
            if (other == player || rt.state.membership(me(other)).isPresent()) continue;
            ids.add(other.getUUID().toString());
            shown.add(clip(other.getPlainTextName()));
        }
        BookButton back = BookDialogs.back(player, p -> desk(p, null));
        List<net.minecraft.network.chat.Component> body = new ArrayList<>();
        if (ids.isEmpty()) {
            body.add(BookDialogs.text(Text.get("book.leader.invite_none")));
            BookDialogs.show(player, BookDialogs.list(Text.get("book.leader.invite"), BookDialogs.withNotice(notice, body), List.of(), back));
            return;
        }
        body.add(BookDialogs.text(Text.get("book.leader.invite_intro")));
        List<BookInput> inputs = List.of(BookDialogs.choice("who", Text.get("book.leader.invite_who"), ids, shown));
        BookButton submit = BookDialogs.submit(player, Text.get("book.leader.invite_send"), null, inputs, form -> {
            ServerPlayer p = form.player();
            Optional<List<String>> values = FormValues.split(form.raw(), 1);
            Optional<PlayerId> target = values.flatMap(v -> parse(v.get(0)));
            if (target.isEmpty()) {
                invite(p, Text.get("book.leader.form_bad"));
                return;
            }
            OrgId now = org(p);
            if (now == null) return;
            String name = rt().describe.player(target.get());
            BookRouter.run(p, new InviteMember(now, target.get()),
                    line -> invite(p, BookResult.of(line, Text.get("invite.done", "player", name))));
        });
        BookDialogs.show(player, BookDialogs.form(Text.get("book.leader.invite"), BookDialogs.withNotice(notice, body), inputs, submit, back));
    }

    // ---- Give a power ----

    public static void grant(ServerPlayer player, String notice) {
        OrgId org = org(player);
        if (org == null) return;
        CharterRuntime rt = rt();
        List<String> powerIds = new ArrayList<>();
        List<String> powerShown = new ArrayList<>();
        for (Power power : Power.values()) {
            // "Changing or ending the charter" names no action that exists, so it is not offered.
            if (power == Power.AMEND || power == Power.DISSOLVE) continue;
            powerIds.add(power.name());
            powerShown.add(Describe.power(power));
        }
        List<String> toIds = new ArrayList<>();
        List<String> toShown = new ArrayList<>();
        for (Office office : rt.state.offices(org)) {
            toIds.add("o:" + office.name);
            toShown.add(clip(office.name));
        }
        for (MemberRecord m : rt.state.members(org)) {
            toIds.add("p:" + m.player().value());
            toShown.add(clip(rt.describe.player(m.player())));
        }
        BookButton back = BookDialogs.back(player, p -> desk(p, null));
        List<net.minecraft.network.chat.Component> body = new ArrayList<>();
        if (toIds.isEmpty()) {
            body.add(BookDialogs.text(Text.get("book.leader.grant_none")));
            BookDialogs.show(player, BookDialogs.list(Text.get("book.leader.grant"), BookDialogs.withNotice(notice, body), List.of(), back));
            return;
        }
        body.add(BookDialogs.text(Text.get("book.leader.grant_intro")));
        List<BookInput> inputs = List.of(
                BookDialogs.choice("power", Text.get("book.leader.grant_power"), powerIds, powerShown),
                BookDialogs.choice("to", Text.get("book.leader.grant_to"), toIds, toShown));
        BookButton submit = BookDialogs.submit(player, Text.get("book.leader.grant_send"), null, inputs, form -> {
            ServerPlayer p = form.player();
            Optional<List<String>> values = FormValues.split(form.raw(), 2);
            OrgId now = org(p);
            if (now == null) return;
            Optional<Power> power = values.flatMap(v -> power(v.get(0)));
            Optional<Holder> to = values.flatMap(v -> holder(now, v.get(1)));
            if (power.isEmpty() || to.isEmpty()) {
                grant(p, Text.get("book.leader.form_bad"));
                return;
            }
            String done = Text.get("grant.done", "to", rt().describe.holder(to.get()), "power", Describe.power(power.get()));
            BookRouter.run(p, new charter.grant.actions.GrantPower(now, power.get(), to.get(), GrantScope.ANY, false, 0),
                    line -> grant(p, BookResult.of(line, done)));
        });
        BookDialogs.show(player, BookDialogs.form(Text.get("book.leader.grant"), BookDialogs.withNotice(notice, body), inputs, submit, back));
    }

    private static Optional<Power> power(String id) {
        try {
            Power power = Power.valueOf(id.toUpperCase(Locale.ROOT));
            return power == Power.AMEND || power == Power.DISSOLVE ? Optional.empty() : Optional.of(power);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<Holder> holder(OrgId org, String id) {
        if (id.startsWith("o:")) return rt().state.officeNamed(org, id.substring(2)).map(o -> new Holder.OfOffice(o.id));
        if (id.startsWith("p:")) {
            return parse(id.substring(2)).filter(p -> rt().state.members(org).stream().anyMatch(m -> m.player().equals(p)))
                    .map(Holder.OfPlayer::new);
        }
        return Optional.empty();
    }

    // ---- The treasury ----

    public static void withdraw(ServerPlayer player, String notice) {
        OrgId org = org(player);
        if (org == null) return;
        CharterRuntime rt = rt();
        BookButton back = BookDialogs.back(player, p -> desk(p, null));
        List<net.minecraft.network.chat.Component> body = new ArrayList<>();
        String balance = Treasury.balance(rt, org);
        body.add(BookDialogs.text(balance == null ? Text.get("book.leader.withdraw_empty")
                : Text.get("book.faction.treasury", "balances", balance)));
        List<BookInput> inputs = List.of(BookDialogs.textInput("amount", Text.get("book.leader.withdraw_amount"), "1", AMOUNT_MAX));
        BookButton submit = BookDialogs.submit(player, Text.get("book.leader.withdraw_send"), null, inputs, form -> {
            ServerPlayer p = form.player();
            var amount = FormValues.split(form.raw(), 1).map(v -> v.get(0)).filter(FormValues::clean)
                    .map(FormValues::integer).filter(java.util.OptionalInt::isPresent).map(java.util.OptionalInt::getAsInt);
            OrgId now = org(p);
            if (now == null) return;
            if (amount.isEmpty() || amount.get() < 1) {
                withdraw(p, Text.get("book.leader.form_bad"));
                return;
            }
            if (!Treasury.available(rt())) {
                withdraw(p, Text.get("vault.no_currency"));
                return;
            }
            int n = amount.get();
            BookRouter.run(p, new Withdraw(now, n, me(p)), line -> withdraw(p, BookResult.of(line, Text.get("vault.withdrawn", "amount", n))));
        });
        BookDialogs.show(player, BookDialogs.form(Text.get("book.leader.withdraw"), BookDialogs.withNotice(notice, body), inputs, submit, back));
    }

    // ---- Move the Nexus ----

    public static void nexus(ServerPlayer player, String notice) {
        if (org(player) == null) return;
        BookButton yes = BookDialogs.act(player, Text.get("book.leader.nexus_yes"), null, p -> {
            String problem = charter.mc.land.NexusMove.move(p);
            desk(p, BookResult.of(problem, Text.get("nexus_move.done")));
        });
        BookButton no = BookDialogs.back(player, p -> desk(p, null));
        BookDialogs.show(player, BookDialogs.confirm(Text.get("book.leader.nexus"),
                BookDialogs.withNotice(notice, List.of(BookDialogs.text(Text.get("book.leader.nexus_intro")))), yes, no));
    }

    // ---- helpers ----

    private static Optional<PlayerId> parse(String uuid) {
        try {
            return Optional.of(new PlayerId(UUID.fromString(uuid)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String clip(String name) {
        return name.length() <= NAME_MAX ? name : name.substring(0, NAME_MAX - 3) + "...";
    }
}
