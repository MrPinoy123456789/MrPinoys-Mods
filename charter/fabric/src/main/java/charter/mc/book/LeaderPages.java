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
import charter.mc.justice.JusticePages;
import charter.org.MemberRecord;
import charter.org.actions.Admit;
import charter.org.actions.InviteMember;
import charter.org.actions.Reject;
import charter.treasury.actions.Withdraw;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The Leader's Desk: every management command a lord or other officer needs, in pages of the book, for players who
 * cannot type {@code /charter invite}, {@code grant}, {@code vault withdraw} or {@code nexus move}. Names are picked from
 * buttons (or a dropdown when there are many), numbers from presets or a slider; nothing is typed. Each page runs the
 * same action the command runs, so a rule lives in one place: the desk only decides what to show, and whether a power
 * is really held is decided by the engine on every press.
 */
public final class LeaderPages {

    private static final int PRESETS_MAX = 100_000;
    private static final long[] PRESETS = {1, 10, 64};

    /** The powers a leader can give, grouped as the pages show them. Every power but AMEND and DISSOLVE is in exactly one. */
    public record Group(String key, List<Power> powers) {
    }

    public static final List<Group> GROUPS = List.of(
            new Group("members", List.of(Power.MANAGE_MEMBERS, Power.APPOINT, Power.CREATE_SUBORG, Power.MANAGE_RANKS)),
            new Group("money", List.of(Power.SPEND, Power.GRANARY_DEPOSIT, Power.GRANARY_WITHDRAW)),
            new Group("land_war", List.of(Power.PLACE_CORE, Power.CLAIM, Power.RAISE_CAMP, Power.GARRISON, Power.DECLARE_WAR)),
            new Group("justice", List.of(Power.ARREST, Power.JUDGE, Power.PARDON)),
            new Group("treaties", List.of(Power.NEGOTIATE)));

    private LeaderPages() {
    }

    private static CharterRuntime rt() {
        return CharterServer.runtime();
    }

    private static PlayerId me(ServerPlayer player) {
        return new PlayerId(player.getUUID());
    }

    private static BookButton go(ServerPlayer player, String label, Consumer<ServerPlayer> page) {
        return BookDialogs.act(player, label, null, page);
    }

    /** True for someone who holds an office, or may take members in: the players this desk is for. */
    public static boolean leads(ServerPlayer player) {
        CharterRuntime rt = rt();
        Optional<Standing.View> view = Standing.of(rt.state, rt.engine, me(player), rt.clock.now());
        if (view.isEmpty()) return false;
        if (!view.get().yourOffices().isEmpty()) return true;
        return holds(player, view.get().org(), Power.MANAGE_MEMBERS) || RankPages.manages(player);
    }

    /** Whether the player holds the power now. Only decides what is shown: the engine decides what is allowed. */
    static boolean holds(ServerPlayer player, OrgId org, Power power) {
        CharterRuntime rt = rt();
        return new AuthorityResolver(rt.state, rt.clock.now())
                .resolve(new Who.AsPlayer(me(player)), org, power, ScopeNeed.NONE, false).ok();
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
        long now = rt.clock.now();
        boolean admit = holds(player, org, Power.MANAGE_MEMBERS);
        List<BookButton> buttons = new ArrayList<>();
        List<PlayerId> asking = askers(org);
        if (admit && !asking.isEmpty()) {
            buttons.add(go(player, Text.get("book.leader.requests_n", "n", asking.size()), p -> {
                List<PlayerId> now2 = askers(org);
                if (now2.size() == 1) request(p, now2.get(0), false);
                else requests(p, null);
            }).icon(BookIcons.JOIN));
        }
        if (admit) buttons.add(go(player, Text.get("book.leader.invite"), p -> invite(p, null)).icon(BookIcons.JOIN));
        buttons.add(go(player, Text.get("book.leader.grant"), p -> grant(p, null)).icon(BookIcons.LEADER));
        if (RankPages.manages(player)) buttons.add(go(player, Text.get("book.ranks.title"), p -> RankPages.ranks(p, null)).icon(BookIcons.RANKS));
        if (Treasury.available(rt) && holds(player, org, Power.SPEND)) {
            buttons.add(go(player, Text.get("book.leader.treasury"), p -> treasury(p, null)).icon(BookIcons.TREASURY));
        }
        // Discipline citizens is a Lord's: moderation of their citizens, with Prisoners and justice inside it. Anyone else who
        // judges keeps a Justice button of their own, so neither costs an extra press.
        boolean judges = holds(player, org, Power.ARREST) || holds(player, org, Power.JUDGE) || holds(player, org, Power.PARDON);
        if (charter.moderation.LordRules.isLord(rt.state, me(player))) {
            buttons.add(go(player, Text.get("book.order.title"), p -> DisciplinePages.page(p, null)).icon(BookIcons.JUSTICE));
        } else if (judges) {
            buttons.add(go(player, Text.get("book.leader.justice"), p -> JusticePages.home(p, null)).icon(BookIcons.JUSTICE));
        }
        BookButton decides = null;
        if (rt.engine.eligibleVoters(org, now).contains(me(player))) {
            decides = go(player, Text.get("book.leader.decides"), p -> MemberPages.whoDecides(p, null)).icon(BookIcons.STAND);
            buttons.add(decides);
        }
        if (holds(player, org, Power.DECLARE_WAR)) {
            buttons.add(go(player, Text.get("book.war.title"), p -> WarPages.page(p, null)).icon(BookIcons.WAR));
        }
        if (hasKeep(org) && holds(player, org, Power.PLACE_CORE)) {
            buttons.add(go(player, Text.get("book.leader.nexus"), p -> nexus(p, null)).icon(BookIcons.KEEP));
        }
        // Fold rule: with every button a Lord can have, Who decides goes (it is on My faction too), so the desk never pages.
        if (buttons.size() > BookScreen.MAX_BUTTONS && decides != null) buttons.remove(decides);
        BookDialogs.show(player, BookDialogs.list(Text.get("book.leader.title"),
                BookDialogs.withNotice(notice, List.of(BookDialogs.text(Text.get("book.leader.intro")))), buttons,
                BookDialogs.back(player, p -> MemberPages.home(p, null))));
    }

    private static boolean hasKeep(OrgId org) {
        CharterRuntime rt = rt();
        return rt.state.coresOf(rt.state.root(org)).stream().anyMatch(c -> c.isKeep());
    }

    private static List<PlayerId> askers(OrgId org) {
        List<PlayerId> out = new ArrayList<>();
        for (var request : rt().state.joinRequests()) if (request.org().equals(org)) out.add(request.player());
        return out;
    }

    // ---- Requests to join ----

    public static void requests(ServerPlayer player, String notice) {
        OrgId org = org(player);
        if (org == null) return;
        List<BookButton> buttons = new ArrayList<>();
        for (PlayerId applicant : askers(org)) {
            buttons.add(go(player, BookDialogs.clip(rt().describe.player(applicant), BookScreen.MAX_LABEL), p -> request(p, applicant, false))
                    .icon(BookIcons.JOIN));
        }
        List<Component> body = new ArrayList<>();
        body.add(BookDialogs.text(Text.get(buttons.isEmpty() ? "book.leader.requests_none" : "book.leader.requests_intro")));
        BookDialogs.show(player, BookDialogs.list(Text.get("book.leader.requests"), BookDialogs.withNotice(notice, body), buttons,
                BookDialogs.back(player, p -> desk(p, null))));
    }

    /**
     * One applicant: Admit, or Turn away (which asks first). A list, not a two button question, because the exit of a
     * question is what B runs, and B must never admit or turn anyone away.
     */
    public static void request(ServerPlayer player, PlayerId applicant, boolean fromNeeds) {
        OrgId org = org(player);
        if (org == null) return;
        CharterRuntime rt = rt();
        BiConsumer<ServerPlayer, String> after = fromNeeds ? NeedsPages::page : LeaderPages::requests;
        if (rt.state.joinRequest(org, applicant).isEmpty()) {
            after.accept(player, Text.get("book.leader.request_gone"));
            return;
        }
        String name = rt.describe.player(applicant);
        String shown = BookDialogs.clip(name, 14);
        BookButton admit = go(player, Text.get("book.button.admit", "player", shown), p -> BookRouter.run(p, new Admit(org, applicant),
                line -> after.accept(p, BookResult.of(line, Text.get("book.faction.admitted", "player", name))))).icon(BookIcons.YES);
        BookButton turn = go(player, Text.get("book.button.reject", "player", shown), p -> confirmReject(p, applicant, fromNeeds)).icon(BookIcons.NO);
        BookDialogs.show(player, BookDialogs.list(Text.get("book.leader.request_title"),
                List.of(BookDialogs.text(Text.get("book.faction.asks", "player", name))), List.of(admit, turn),
                BookDialogs.back(player, p -> after.accept(p, null))));
    }

    private static void confirmReject(ServerPlayer player, PlayerId applicant, boolean fromNeeds) {
        OrgId org = org(player);
        if (org == null) return;
        String name = rt().describe.player(applicant);
        BiConsumer<ServerPlayer, String> after = fromNeeds ? NeedsPages::page : LeaderPages::requests;
        BookButton yes = go(player, Text.get("book.button.reject", "player", BookDialogs.clip(name, 14)), p -> BookRouter.run(p,
                new Reject(org, applicant), line -> after.accept(p, BookResult.of(line, Text.get("book.faction.rejected", "player", name)))));
        BookButton no = BookDialogs.back(player, p -> request(p, applicant, fromNeeds));
        BookDialogs.show(player, BookDialogs.confirm(Text.get("book.reject.title"),
                List.of(BookDialogs.text(Text.get("book.reject.confirm", "player", name))), yes, no));
    }

    // ---- Invite ----

    public static void invite(ServerPlayer player, String notice) {
        OrgId org = org(player);
        if (org == null) return;
        CharterRuntime rt = rt();
        List<ServerPlayer> free = new ArrayList<>();
        for (ServerPlayer other : player.level().getServer().getPlayerList().getPlayers()) {
            if (other != player && rt.state.membership(me(other)).isEmpty()) free.add(other);
        }
        BookButton back = BookDialogs.back(player, p -> desk(p, null));
        List<Component> body = new ArrayList<>();
        if (free.isEmpty()) {
            body.add(BookDialogs.text(Text.get("book.leader.invite_none")));
            BookDialogs.show(player, BookDialogs.list(Text.get("book.leader.invite"), BookDialogs.withNotice(notice, body), List.of(), back));
            return;
        }
        body.add(BookDialogs.text(Text.get("book.leader.invite_intro")));
        if (free.size() <= BookScreen.MAX_BUTTONS) {
            List<BookButton> buttons = new ArrayList<>();
            for (ServerPlayer other : free) {
                PlayerId target = me(other);
                String name = other.getPlainTextName();
                buttons.add(go(player, Text.get("book.leader.invite_one", "player", BookDialogs.clip(name, 17)), p -> sendInvite(p, target, name))
                        .icon(BookIcons.JOIN));
            }
            BookDialogs.show(player, BookDialogs.list(Text.get("book.leader.invite"), BookDialogs.withNotice(notice, body), buttons, back));
            return;
        }
        List<String> ids = new ArrayList<>();
        List<String> shown = new ArrayList<>();
        for (ServerPlayer other : free) {
            ids.add(other.getUUID().toString());
            shown.add(BookDialogs.clip(other.getPlainTextName(), 32));
        }
        List<BookInput> inputs = List.of(BookDialogs.choice("who", Text.get("book.leader.invite_who"), ids, shown));
        BookButton submit = BookDialogs.submit(player, Text.get("book.leader.invite_send"), null, inputs, form -> {
            ServerPlayer p = form.player();
            Optional<PlayerId> target = FormValues.split(form.raw(), 1).flatMap(v -> parse(v.get(0)));
            if (target.isEmpty()) {
                invite(p, Text.get("book.leader.form_bad"));
                return;
            }
            sendInvite(p, target.get(), rt().describe.player(target.get()));
        });
        BookDialogs.show(player, BookDialogs.form(Text.get("book.leader.invite"), BookDialogs.withNotice(notice, body), inputs, submit, back));
    }

    private static void sendInvite(ServerPlayer p, PlayerId target, String name) {
        OrgId now = org(p);
        if (now == null) return;
        BookRouter.run(p, new InviteMember(now, target), line -> invite(p, BookResult.of(line, Text.get("invite.done", "player", name))));
    }

    // ---- Give a power: group, then power, then who ----

    public static void grant(ServerPlayer player, String notice) {
        if (org(player) == null) return;
        List<BookButton> buttons = new ArrayList<>();
        for (Group group : GROUPS) {
            buttons.add(go(player, Text.get("book.grant.group." + group.key()), p -> grantPowers(p, group, null)).icon(BookIcons.LEADER));
        }
        BookDialogs.show(player, BookDialogs.list(Text.get("book.leader.grant"),
                BookDialogs.withNotice(notice, List.of(BookDialogs.text(Text.get("book.leader.grant_intro")))), buttons,
                BookDialogs.back(player, p -> desk(p, null))));
    }

    private static void grantPowers(ServerPlayer player, Group group, String notice) {
        if (org(player) == null) return;
        List<BookButton> buttons = new ArrayList<>();
        for (Power power : group.powers()) {
            buttons.add(go(player, Text.get("book.grant.power." + power.name().toLowerCase(Locale.ROOT)), p -> grantTo(p, power, null)));
        }
        BookDialogs.show(player, BookDialogs.list(Text.get("book.grant.group." + group.key()),
                BookDialogs.withNotice(notice, List.of(BookDialogs.text(Text.get("book.grant.pick_power")))), buttons,
                BookDialogs.back(player, p -> grant(p, null))));
    }

    private static void grantTo(ServerPlayer player, Power power, String notice) {
        OrgId org = org(player);
        if (org == null) return;
        CharterRuntime rt = rt();
        List<String> ids = new ArrayList<>();
        List<String> shown = new ArrayList<>();
        for (Office office : rt.state.offices(org)) {
            ids.add("o:" + office.name);
            shown.add(BookDialogs.clip(office.name, BookScreen.MAX_LABEL));
        }
        for (MemberRecord m : rt.state.members(org)) {
            ids.add("p:" + m.player().value());
            shown.add(BookDialogs.clip(rt.describe.player(m.player()), BookScreen.MAX_LABEL));
        }
        BookButton back = BookDialogs.back(player, p -> grantPowers(p, groupOf(power), null));
        String title = Text.get("book.grant.power." + power.name().toLowerCase(Locale.ROOT));
        List<Component> body = List.of(BookDialogs.text(Text.get(ids.isEmpty() ? "book.leader.grant_none" : "book.grant.pick_who")));
        if (ids.isEmpty()) {
            BookDialogs.show(player, BookDialogs.list(title, BookDialogs.withNotice(notice, body), List.of(), back));
            return;
        }
        if (ids.size() <= BookScreen.MAX_BUTTONS) {
            List<BookButton> buttons = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                String id = ids.get(i);
                buttons.add(go(player, shown.get(i), p -> giveTo(p, power, id)));
            }
            BookDialogs.show(player, BookDialogs.list(title, BookDialogs.withNotice(notice, body), buttons, back));
            return;
        }
        List<BookInput> inputs = List.of(BookDialogs.choice("to", Text.get("book.leader.grant_to"), ids, shown));
        BookButton submit = BookDialogs.submit(player, Text.get("book.leader.grant_send"), null, inputs, form -> {
            Optional<String> id = FormValues.split(form.raw(), 1).map(v -> v.get(0));
            if (id.isEmpty()) {
                grantTo(form.player(), power, Text.get("book.leader.form_bad"));
                return;
            }
            giveTo(form.player(), power, id.get());
        });
        BookDialogs.show(player, BookDialogs.form(title, BookDialogs.withNotice(notice, body), inputs, submit, back));
    }

    private static void giveTo(ServerPlayer p, Power power, String id) {
        OrgId now = org(p);
        if (now == null) return;
        Optional<Holder> to = holder(now, id);
        if (to.isEmpty()) {
            grantTo(p, power, Text.get("book.leader.form_bad"));
            return;
        }
        String done = Text.get("grant.done", "to", rt().describe.holder(to.get()), "power", Describe.power(power));
        BookRouter.run(p, new charter.grant.actions.GrantPower(now, power, to.get(), GrantScope.ANY, false, 0),
                line -> grantPowers(p, groupOf(power), BookResult.of(line, done)));
    }

    static Group groupOf(Power power) {
        for (Group group : GROUPS) if (group.powers().contains(power)) return group;
        throw new IllegalArgumentException("no grant group for " + power);
    }

    private static Optional<Holder> holder(OrgId org, String id) {
        if (id.startsWith("o:")) return rt().state.officeNamed(org, id.substring(2)).map(o -> new Holder.OfOffice(o.id));
        if (id.startsWith("p:")) {
            return parse(id.substring(2)).filter(p -> rt().state.members(org).stream().anyMatch(m -> m.player().equals(p)))
                    .map(Holder.OfPlayer::new);
        }
        return Optional.empty();
    }

    // ---- The treasury: presets are one press, a slider handles any other amount ----

    public static void treasury(ServerPlayer player, String notice) {
        OrgId org = org(player);
        if (org == null) return;
        CharterRuntime rt = rt();
        BookButton back = BookDialogs.back(player, p -> desk(p, null));
        List<Component> body = new ArrayList<>();
        if (!Treasury.available(rt)) {
            body.add(BookDialogs.text(Text.get("vault.no_currency")));
            BookDialogs.show(player, BookDialogs.list(Text.get("book.leader.treasury"), BookDialogs.withNotice(notice, body), List.of(), back));
            return;
        }
        long balance = rt.state.payment.balance(org.toString());
        body.add(BookDialogs.text(balance <= 0 ? Text.get("book.leader.withdraw_empty")
                : Text.get("book.faction.treasury", "balances", Treasury.balance(rt, org))));
        List<BookButton> buttons = new ArrayList<>();
        for (long marks : PRESETS) {
            if (marks > balance) continue;
            buttons.add(go(player, Text.get("book.leader.take", "n", marks), p -> take(p, (int) marks)).icon(BookIcons.TREASURY));
        }
        if (balance >= 2) buttons.add(go(player, Text.get("book.leader.take_other"), p -> takeSlider(p, null)).icon(BookIcons.TREASURY));
        BookDialogs.show(player, BookDialogs.list(Text.get("book.leader.treasury"), BookDialogs.withNotice(notice, body), buttons, back));
    }

    private static void take(ServerPlayer p, int marks) {
        OrgId now = org(p);
        if (now == null) return;
        if (!Treasury.available(rt())) {
            treasury(p, Text.get("vault.no_currency"));
            return;
        }
        BookRouter.run(p, new Withdraw(now, marks, me(p)),
                line -> treasury(p, BookResult.of(line, Text.get("vault.withdrawn", "amount", marks))));
    }

    private static void takeSlider(ServerPlayer player, String notice) {
        OrgId org = org(player);
        if (org == null) return;
        long balance = rt().state.payment.balance(org.toString());
        BookButton back = BookDialogs.back(player, p -> treasury(p, null));
        if (balance < 2) {
            treasury(player, null);
            return;
        }
        int max = (int) Math.min(balance, PRESETS_MAX);
        List<BookInput> inputs = List.of(new BookInput.Slider("amount", Text.get("book.leader.withdraw_amount"), 1, max, 1, Math.min(10, max)));
        BookButton submit = BookDialogs.submit(player, Text.get("book.leader.withdraw_send"), null, inputs, form -> {
            var amount = FormValues.split(form.raw(), 1).map(v -> v.get(0)).filter(FormValues::clean)
                    .map(FormValues::integer).filter(java.util.OptionalInt::isPresent).map(java.util.OptionalInt::getAsInt);
            if (amount.isEmpty() || amount.get() < 1) {
                takeSlider(form.player(), Text.get("book.leader.form_bad"));
                return;
            }
            take(form.player(), amount.get());
        });
        BookDialogs.show(player, BookDialogs.form(Text.get("book.leader.take_other"),
                BookDialogs.withNotice(notice, List.of(BookDialogs.text(Text.get("book.faction.treasury", "balances", Treasury.balance(rt(), org))))),
                inputs, submit, back));
    }

    // ---- Move the Nexus ----

    public static void nexus(ServerPlayer player, String notice) {
        OrgId org = org(player);
        if (org == null) return;
        BookButton yes = BookDialogs.act(player, Text.get("book.leader.nexus_yes"), null, p -> {
            String problem = charter.mc.land.NexusMove.move(p);
            desk(p, BookResult.of(problem, Text.get("nexus_move.done")));
        });
        BookButton no = BookDialogs.back(player, p -> desk(p, null));
        List<Component> body = List.of(BookDialogs.text(Text.get("book.leader.nexus_intro")),
                BookDialogs.text(Text.get(insideKeep(player, org) ? "book.leader.nexus_inside" : "book.leader.nexus_outside")));
        BookDialogs.show(player, BookDialogs.confirm(Text.get("book.leader.nexus"), BookDialogs.withNotice(notice, body), yes, no));
    }

    /** Whether the player stands on the chunks of their nation's keep right now. */
    static boolean insideKeep(ServerPlayer player, OrgId org) {
        CharterRuntime rt = rt();
        var keep = rt.state.coresOf(rt.state.root(org)).stream().filter(c -> c.isKeep()).findFirst();
        if (keep.isEmpty() || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)) return false;
        var claim = rt.state.claimAt(charter.mc.land.Placement.chunkOf(level, player.blockPosition()));
        return claim.isPresent() && claim.get().core().equals(keep.get().id);
    }

    // ---- helpers ----

    private static Optional<PlayerId> parse(String uuid) {
        try {
            return Optional.of(new PlayerId(UUID.fromString(uuid)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
