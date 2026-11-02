package charter.mc.book;

import charter.contract.Contract;
import charter.contract.Justice;
import charter.contract.Term;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.gov.Office;
import charter.gov.Standing;
import charter.gov.Vote;
import charter.gov.actions.SetSeats;
import charter.grant.AuthorityResolver;
import charter.grant.Power;
import charter.grant.ScopeNeed;
import charter.grant.Who;
import charter.id.OfficeId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.id.VoteId;
import charter.land.Core;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Describe;
import charter.mc.Text;
import charter.mc.chronicle.ChronicleBooks;
import charter.mc.chronicle.Chronicles;
import charter.mc.cmd.Chat;
import charter.mc.granary.GranaryText;
import charter.org.JoinRequest;
import charter.org.MemberRecord;
import charter.org.Organization;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The pages of the Charter book for a member. Every page is built from live state
 * each time it opens, and every press ends by showing a page again, with the
 * result of the press above it. {@code notice} is that line, or null.
 */
public final class MemberPages {

    private static final int LABEL_MAX = 40;
    private static final int CANDIDATES_MAX = 20;

    private MemberPages() {
    }

    private static CharterRuntime rt() {
        return CharterServer.runtime();
    }

    private static PlayerId me(ServerPlayer player) {
        return new PlayerId(player.getUUID());
    }

    private static Optional<Standing.View> view(ServerPlayer player) {
        CharterRuntime rt = rt();
        return Standing.of(rt.state, rt.engine, me(player), rt.clock.now());
    }

    private static String shorten(String text) {
        return text.length() <= LABEL_MAX ? text : text.substring(0, LABEL_MAX - 3) + "...";
    }

    /** Each item is a plain String or a styled Component, shown as its own paragraph. */
    private static List<net.minecraft.network.chat.Component> lines(String notice, List<?> lines) {
        List<net.minecraft.network.chat.Component> body = new ArrayList<>();
        for (Object line : lines) {
            if (line instanceof net.minecraft.network.chat.Component c) body.add(BookDialogs.text(c));
            else body.add(BookDialogs.text(String.valueOf(line)));
        }
        return BookDialogs.withNotice(notice, body);
    }

    /** The hover that says what a form of rule means; commune and council are the two that need telling apart. */
    static String formTip(Standing.Form form, Office deciders) {
        return switch (form) {
            case COMMUNE -> Text.get("book.tip.form_commune");
            case LORDSHIP -> Text.get("book.tip.form_lordship");
            case COUNCIL -> Text.get(deciders != null && deciders.deciding() == Office.Deciding.TOGETHER
                    ? "book.tip.form_council_together" : "book.tip.form_council_alone");
        };
    }

    private static BookButton go(ServerPlayer player, String label, java.util.function.Consumer<ServerPlayer> page) {
        return BookDialogs.act(player, label, null, page);
    }

    /** A page that needs a faction: a player who has left since the last press goes to the newcomer home. */
    private static boolean member(ServerPlayer player) {
        if (view(player).isPresent()) return true;
        NewcomerPages.home(player, null);
        return false;
    }

    // ---- Home ----

    /** Which of Where I stand and My faction fit on Home; what does not fit waits under More. */
    public record Layout(boolean standOnHome, boolean factionOnHome) {
    }

    /** The layout Home has for this player now (MorePages reads it to show what Home left out). */
    static Layout layout(ServerPlayer player) {
        int fixed = TravelButtons.of(player, true, MemberPages::home).size() + (Needs.count(player) > 0 ? 1 : 0)
                + (LeaderPages.leads(player) ? 1 : 0) + 1;
        return layout(fixed);
    }

    /**
     * The fold rule. {@code fixed} counts what always stays on Home: travel, Needs you, the Leader's Desk and More.
     * My faction moves under More first, then Where I stand, so Home never holds more than 8 buttons and is never paged:
     * five journeys, Needs you, the Desk and More is the most it can hold.
     */
    public static Layout layout(int fixed) {
        if (fixed + 2 <= BookScreen.MAX_BUTTONS) return new Layout(true, true);
        if (fixed + 1 <= BookScreen.MAX_BUTTONS) return new Layout(true, false);
        return new Layout(false, false);
    }

    public static void home(ServerPlayer player, String notice) {
        if (!member(player)) return;
        Standing.View v = view(player).orElseThrow();
        String form = switch (v.form()) {
            case COMMUNE -> Text.get("book.form.commune");
            case LORDSHIP -> Text.get("book.form.lordship");
            case COUNCIL -> {
                Office deciders = deciders(v.org());
                int seats = deciders == null ? 0 : deciders.seats();
                yield Text.get(deciders != null && deciders.deciding() == Office.Deciding.TOGETHER
                        ? "book.form.council_together" : "book.form.council_alone", "count", seats);
            }
        };
        String offices = v.yourOffices().isEmpty() ? Text.get("book.home.no_office") : String.join(", ", v.yourOffices());
        // Travel comes first: it is what players reach for most, and Bedrock players cannot type /home or /keep.
        List<BookButton> buttons = new ArrayList<>(TravelButtons.of(player, true, MemberPages::home));
        int needs = Needs.count(player);
        if (needs > 0) buttons.add(go(player, Text.get("book.button.needs", "n", needs), NeedsPages::open).icon(BookIcons.NEEDS));
        if (LeaderPages.leads(player)) {
            buttons.add(go(player, Text.get("book.button.leader"), p -> LeaderPages.desk(p, null)).icon(BookIcons.LEADER));
        }
        Layout layout = layout(buttons.size() + 1);
        if (layout.standOnHome()) buttons.add(go(player, Text.get("book.button.stand"), p -> StandPages.page(p, null)).icon(BookIcons.STAND));
        if (layout.factionOnHome()) buttons.add(go(player, Text.get("book.button.faction"), p -> faction(p, null)).icon(BookIcons.FACTION));
        buttons.add(go(player, Text.get("book.button.more"), p -> MorePages.page(p, null)).icon(BookIcons.MORE));
        net.minecraft.network.chat.Component line = net.minecraft.network.chat.Component.literal(
                Text.get("book.home.form", "form", form, "offices", offices)).withStyle(net.minecraft.ChatFormatting.GOLD);
        BookDialogs.show(player, BookDialogs.list(Text.get("book.home.title", "org", v.orgName()),
                lines(notice, List.of(line)), buttons, BookDialogs.close()));
    }

    /** "Bargains, Bloodmoons, Ragnarok and Eclipse". */
    static String seasonNames() {
        String[] s = charter.chronicle.Calendar.SEASONS;
        return String.join(", ", java.util.Arrays.copyOf(s, s.length - 1)) + " and " + s[s.length - 1];
    }

    private static Office deciders(OrgId org) {
        CharterRuntime rt = rt();
        return rt.state.org(org).map(o -> o.governance.deciders).flatMap(rt.state::office).orElse(null);
    }

    // ---- The full pages behind Where I stand ----

    public static void rights(ServerPlayer player, String notice) {
        if (!member(player)) return;
        Standing.View v = view(player).orElseThrow();
        List<net.minecraft.network.chat.Component> rights = StandingText.components(
                v.rights().stream().filter(l -> l.group() != charter.gov.PowerGroup.CHARTER).toList(), false);
        List<Object> body = new ArrayList<>();
        body.add(Text.get("book.rights.intro", "org", v.orgName()));
        if (rights.isEmpty()) {
            // Only a commune's members decide by vote; in a lordship or council the ruler does.
            body.add(Text.get(v.form() == Standing.Form.COMMUNE ? "book.rights.none" : "book.rights.none_ruled"));
        } else {
            body.addAll(rights);
        }
        BookDialogs.show(player, BookDialogs.list(Text.get("book.rights.title"), lines(notice, body), List.of(),
                BookDialogs.back(player, p -> StandPages.page(p, null))));
    }

    public static void freedoms(ServerPlayer player, String notice) {
        if (!member(player)) return;
        Standing.View v = view(player).orElseThrow();
        // "Changing or ending the charter" names no action that exists, so it is not shown.
        List<net.minecraft.network.chat.Component> freedoms = StandingText.components(
                v.freedoms().stream().filter(l -> l.group() != charter.gov.PowerGroup.CHARTER).toList(), false);
        List<Object> body = new ArrayList<>();
        body.add(Text.get("book.freedoms.intro", "org", v.orgName()));
        if (freedoms.isEmpty()) body.add(Text.get("book.freedoms.none")); else body.addAll(freedoms);
        rt().state.membership(new charter.id.PlayerId(player.getUUID())).map(m -> rt().state.root(m.org())).flatMap(rt().state::org)
                .ifPresent(top -> body.add(Text.get(top.mixedRace ? "book.freedoms.mixed_open" : "book.freedoms.mixed_closed",
                        "org", top.name(), "race", top.raceLabel())));
        // Members of a Lordship or a council cannot vote the ruler out. While the operators set the kingdoms there is no
        // breaking away either, so the one way out is to leave (under More).
        if (v.form() != Standing.Form.COMMUNE) {
            body.add(Text.get(rt().knobs.playerFounding ? "book.freedoms.no_vote" : "book.freedoms.no_vote_set"));
        }
        BookDialogs.show(player, BookDialogs.list(Text.get("book.freedoms.title"), lines(notice, body), List.of(),
                BookDialogs.back(player, p -> StandPages.page(p, null))));
    }

    /** Leave is never refused for a member, a sole Lord included (Leave.check), so the button always shows. */
    static void leaveConfirm(ServerPlayer player) {
        if (!member(player)) return;
        Standing.View v = view(player).orElseThrow();
        BookButton yes = BookDialogs.act(player, Text.get("book.leave.verb", "org", BookDialogs.clip(v.orgName(), 18)), null, p -> {
            PlayerId me = me(p);
            BookRouter.run(p, new charter.org.actions.Leave(me), line -> {
                if (line != null) MorePages.page(p, line);
                else NewcomerPages.home(p, BookResult.ok(Text.get("leave.done", "org", v.orgName())));
            });
        }).icon(BookIcons.LEAVE);
        BookButton no = BookDialogs.back(player, p -> MorePages.page(p, null));
        BookDialogs.show(player, BookDialogs.confirm(Text.get("book.leave.title", "org", v.orgName()),
                List.of(BookDialogs.text(Text.get("book.leave.confirm", "org", v.orgName())),
                        BookDialogs.text(Text.get("book.leave.cooldown", "days", rt().knobs.foundSecedeCooldownDays))), yes, no));
    }

    // ---- A vote ----

    public static void vote(ServerPlayer player, VoteId id, String notice) {
        if (!member(player)) return;
        CharterRuntime rt = rt();
        Optional<Vote> found = rt.state.vote(id);
        if (found.isEmpty()) {
            NeedsPages.page(player, Text.get("book.vote.gone"));
            return;
        }
        Vote vote = found.get();
        PlayerId me = me(player);
        List<String> body = new ArrayList<>();
        body.add(rt.notices.about(vote));
        body.add(vote.open() ? Text.get("book.vote.closes", "time", Chat.duration(vote.closesAt - rt.clock.now()))
                : Text.get("book.vote.closed", "result", Text.get("vote.state." + vote.state().name().toLowerCase(java.util.Locale.ROOT))));
        Vote.Ballot yours = vote.ballots().get(me);
        if (yours != null) body.add(Text.get("book.vote.yours", "ballot", ballotText(rt, yours)));
        List<BookButton> buttons = new ArrayList<>();
        if (vote.open() && vote.electorate.contains(me)) {
            if (vote.kind == Vote.Kind.ELECTION) {
                int shown = 0;
                for (MemberRecord m : rt.state.members(vote.org)) {
                    if (shown++ >= CANDIDATES_MAX) break;
                    PlayerId candidate = m.player();
                    buttons.add(go(player, Text.get("book.vote.for", "name", rt.describe.player(candidate)),
                            p -> cast(p, id, Vote.Ballot.candidate(candidate))));
                }
            } else {
                buttons.add(go(player, Text.get("book.button.yes"), p -> cast(p, id, Vote.Ballot.YES)));
                buttons.add(go(player, Text.get("book.button.no"), p -> cast(p, id, Vote.Ballot.NO)));
                buttons.add(go(player, Text.get("book.button.abstain"), p -> cast(p, id, Vote.Ballot.ABSTAIN)));
            }
        }
        BookDialogs.show(player, BookDialogs.list(Text.get("book.vote.title"), lines(notice, body), buttons,
                BookDialogs.back(player, p -> NeedsPages.page(p, null))));
    }

    private static String ballotText(CharterRuntime rt, Vote.Ballot ballot) {
        return switch (ballot.choice()) {
            case YES -> Text.get("book.button.yes");
            case NO -> Text.get("book.button.no");
            case ABSTAIN -> Text.get("book.button.abstain");
            case CANDIDATE -> rt.describe.player(ballot.candidate());
        };
    }

    private static void cast(ServerPlayer player, VoteId id, Vote.Ballot ballot) {
        CharterRuntime rt = rt();
        Outcome outcome = rt.engine.cast(me(player), id, ballot);
        if (outcome instanceof Outcome.Refused(Reason reason)) {
            vote(player, id, BookRouter.refusal(reason));
        } else {
            NeedsPages.page(player, BookResult.ok(Text.get("book.vote.cast")));
        }
    }

    // ---- The faction ----

    private static final int MEMBERS_SHOWN = 12;

    /** For the link in a join request notice. */
    public static void openFaction(ServerPlayer player) {
        faction(player, null);
    }

    /** "My faction": a read only summary, with the few things a member may start from here. */
    public static void faction(ServerPlayer player, String notice) {
        if (!member(player)) return;
        CharterRuntime rt = rt();
        PlayerId me = me(player);
        long now = rt.clock.now();
        Standing.View v = view(player).orElseThrow();
        OrgId org = v.org();
        List<String> names = memberNames(org);
        String members = Text.get("book.faction.members", "count", names.size(),
                "list", String.join(", ", names.subList(0, Math.min(MEMBERS_SHOWN, names.size()))));
        if (names.size() > MEMBERS_SHOWN) members += " " + Text.get("book.faction.and_more", "n", names.size() - MEMBERS_SHOWN);

        List<String> seats = new ArrayList<>();
        List<String> empty = rt.state.offices(org).stream().filter(o -> o.vacant() && o.seats() > 0).map(o -> o.name).toList();
        if (!empty.isEmpty()) seats.add(Text.get("book.faction.empty", "offices", String.join(", ", empty)));
        for (Vote running : rt.state.votes()) {
            if (!running.open() || !running.org.equals(org) || running.kind != Vote.Kind.ELECTION) continue;
            seats.add(Text.get(running.restricted() ? "book.faction.election_council" : "book.faction.election_running",
                    "office", rt.describe.office(running.office)));
        }
        boolean voter = rt.engine.eligibleVoters(org, now).contains(me);
        long wait = v.memberSince() + charter.time.Durations.days(rt.knobs.voteEligibilityDays) - now;
        if (!voter && wait > 0) seats.add(Text.get("book.faction.vote_later", "time", Chat.duration(wait)));

        List<String> land = new ArrayList<>();
        Organization organization = rt.state.org(org).orElseThrow();
        String balance = charter.mc.Treasury.balance(rt, org);
        if (balance != null) land.add(Text.get("book.faction.treasury", "balances", balance));
        for (Core core : rt.state.coresOf(org)) {
            if (!core.isKeep()) continue;
            if (!rt.knobs.keepGranary) {
                // Keeps need no food, so there is no granary to report.
                land.add(Text.get("book.faction.keep_plain", "x", core.center.x() * 16 + 8, "z", core.center.z() * 16 + 8));
                continue;
            }
            land.add(Text.get("book.faction.keep", "x", core.center.x() * 16 + 8, "z", core.center.z() * 16 + 8,
                    "granary", GranaryText.timeLine(rt, core)));
            double daysLeft = GranaryText.daysLeft(rt, core);
            int warnAt = java.util.Arrays.stream(rt.knobs.keepWarnDays).max().orElse(0);
            if (daysLeft <= warnAt) land.add(Text.get("book.faction.keep_low", "days", Math.max(0, (int) Math.ceil(daysLeft))));
        }
        if (rt.state.coresOf(org).stream().noneMatch(Core::isKeep) && organization.parent() == null) {
            land.add(Text.get("book.faction.no_keep"));
        }

        List<Object> body = new ArrayList<>();
        body.add(members);
        if (!seats.isEmpty()) body.add(String.join("\n", seats));
        if (!land.isEmpty()) body.add(String.join("\n", land));

        List<BookButton> buttons = new ArrayList<>();
        if (names.size() > MEMBERS_SHOWN) {
            buttons.add(go(player, Text.get("book.faction.all_members", "n", names.size()), p -> members(p, 0)).icon(BookIcons.FACTION));
        }
        if (voter) {
            buttons.add(go(player, Text.get("book.leader.decides"), p -> whoDecides(p, null)).icon(BookIcons.STAND));
            Office deciders = deciders(org);
            if (deciders != null && deciders.seats() > 0 && !deciders.full()
                    && rt.state.votes().stream().noneMatch(x -> x.open() && x.kind == Vote.Kind.ELECTION && deciders.id.equals(x.office))) {
                buttons.add(go(player, Text.get("book.faction.elect"), p -> elect(p, deciders.id)).icon(BookIcons.VOTE));
            }
        }
        if (LeaderPages.leads(player)) {
            buttons.add(go(player, Text.get("book.button.leader"), p -> LeaderPages.desk(p, null)).icon(BookIcons.LEADER));
        }
        buttons.add(go(player, Text.get("book.ranks.title"), p -> RankPages.list(p, null)).icon(BookIcons.RANKS));
        BookDialogs.show(player, BookDialogs.list(v.orgName(), lines(notice, body), buttons,
                BookDialogs.back(player, p -> home(p, null))));
    }

    /** Every member's name, with the offices they hold. */
    private static List<String> memberNames(OrgId org) {
        CharterRuntime rt = rt();
        List<String> out = new ArrayList<>();
        for (MemberRecord m : rt.state.members(org)) {
            List<String> held = rt.state.offices(org).stream().filter(o -> o.holds(m.player())).map(o -> o.name).toList();
            String name = rt.describe.player(m.player());
            out.add(held.isEmpty() ? name : name + " (" + String.join(", ", held) + ")");
        }
        return out;
    }

    /** Every member, {@value #MEMBERS_SHOWN} to a page, rebuilt from live state for each page. */
    public static void members(ServerPlayer player, int index) {
        if (!member(player)) return;
        Standing.View v = view(player).orElseThrow();
        List<String> names = memberNames(v.org());
        int pages = Math.max(1, (names.size() + MEMBERS_SHOWN - 1) / MEMBERS_SHOWN);
        int at = Math.max(0, Math.min(index, pages - 1));
        List<String> shown = names.subList(at * MEMBERS_SHOWN, Math.min(names.size(), (at + 1) * MEMBERS_SHOWN));
        List<Object> body = new ArrayList<>();
        body.add(Text.get("book.page.line", "page", at + 1, "pages", pages));
        body.add(String.join("\n", shown));
        List<BookButton> buttons = new ArrayList<>();
        if (at > 0) buttons.add(go(player, Text.get("book.page.prev"), p -> members(p, at - 1)));
        if (at < pages - 1) buttons.add(go(player, Text.get("book.page.next"), p -> members(p, at + 1)));
        BookDialogs.show(player, BookDialogs.list(Text.get("book.faction.members_title", "org", v.orgName()), lines(null, body), buttons,
                BookDialogs.back(player, p -> faction(p, null))));
    }

    private static void elect(ServerPlayer player, OfficeId office) {
        Outcome outcome = rt().engine.openElection(office, me(player));
        if (outcome instanceof Outcome.Refused(Reason reason)) {
            faction(player, BookRouter.refusal(reason));
        } else if (outcome instanceof Outcome.VoteOpened(VoteId id)) {
            vote(player, id, BookResult.ok(Text.get("book.faction.election_opened")));
        } else {
            faction(player, null);
        }
    }

    // ---- Who decides ----

    /**
     * Who decides. Only the deciders change their own form: in a Commune the members by vote, in a
     * Lordship the Lord, in a council a majority of its seats. A holder of the decision office sees what
     * it may do; everyone else sees one line saying who decides (members of a Lordship or a council
     * have no vote on it; they can break away).
     */
    public static void whoDecides(ServerPlayer player, String notice) {
        if (!member(player)) return;
        Standing.View v = view(player).orElseThrow();
        Office deciders = deciders(v.org());
        if (!rt().knobs.leadersChangeGovernance) {
            // The form is set by the server: say who decides, and offer nothing to change.
            List<String> names = new ArrayList<>();
            if (deciders != null) for (Office.Seat seat : deciders.holders()) names.add(rt().describe.player(seat.player()));
            List<String> fixed = new ArrayList<>();
            fixed.add(Text.get("book.decides.locked"));
            fixed.add(Text.get("book.decides.only", "now", deciders == null ? "" : choiceText(deciders.seats(), deciders.deciding()),
                    "who", names.isEmpty() ? Text.get("book.decides.nobody") : String.join(", ", names)));
            BookDialogs.show(player, BookDialogs.list(Text.get("book.decides.title"), lines(notice, fixed), List.of(),
                    BookDialogs.back(player, MemberPages::decidesParent)));
            return;
        }
        if (deciders != null && deciders.seats() == 0) {
            whoDecidesMembers(player, notice);
            return;
        }
        List<String> body = new ArrayList<>();
        List<BookButton> buttons = new ArrayList<>();
        if (deciders != null && deciders.holds(me(player))) {
            body.add(Text.get("book.decides.own"));
            body.add(Text.get("book.decides.now", "now", choiceText(deciders.seats(), deciders.deciding())));
            buttons.add(go(player, Text.get("book.decides.to_council_alone"), p -> councilSize(p, Office.Deciding.ALONE, null)));
            buttons.add(go(player, Text.get("book.decides.to_council_together"), p -> councilSize(p, Office.Deciding.TOGETHER, null)));
            if (deciders.seats() >= 2) {
                buttons.add(go(player, Text.get("book.decides.to_lordship"), p -> changeForm(p, v.org(), deciders.id, 1, Office.Deciding.ALONE)));
            }
            buttons.add(go(player, Text.get("book.decides.to_commune"), p -> changeForm(p, v.org(), deciders.id, 0, Office.Deciding.ALONE)));
        } else {
            List<String> names = new ArrayList<>();
            if (deciders != null) for (Office.Seat seat : deciders.holders()) names.add(rt().describe.player(seat.player()));
            body.add(Text.get("book.decides.only", "now", deciders == null ? "" : choiceText(deciders.seats(), deciders.deciding()),
                    "who", names.isEmpty() ? Text.get("book.decides.nobody") : String.join(", ", names)));
        }
        BookDialogs.show(player, BookDialogs.list(Text.get("book.decides.title"), lines(notice, body), buttons,
                BookDialogs.back(player, MemberPages::decidesParent)));
    }

    /** Back from Who decides: the Leader's Desk for a leader, My faction for anyone else. */
    private static void decidesParent(ServerPlayer p) {
        if (LeaderPages.leads(p)) LeaderPages.desk(p, null);
        else faction(p, null);
    }

    /** How many seats, for a council the office makes itself: a short list rather than a button per form. */
    private static void councilSize(ServerPlayer player, Office.Deciding how, String notice) {
        if (!member(player)) return;
        Standing.View v = view(player).orElseThrow();
        Office deciders = deciders(v.org());
        if (deciders == null) return;
        List<BookButton> buttons = new ArrayList<>();
        for (int n = 2; n <= 5; n++) {
            if (n == deciders.seats() && how == deciders.deciding()) continue;
            int seats = n;
            buttons.add(go(player, Text.get("book.decides.seats", "n", n), p -> changeForm(p, v.org(), deciders.id, seats, how)));
        }
        List<String> body = List.of(Text.get(how == Office.Deciding.TOGETHER ? "book.decides.size_together" : "book.decides.size_alone"));
        BookDialogs.show(player, BookDialogs.list(Text.get("book.decides.title"), lines(notice, body), buttons,
                BookDialogs.back(player, p -> whoDecides(p, null))));
    }

    /** The office changes its own form: a lone Lord at once, a council by a majority of its seats. */
    private static void changeForm(ServerPlayer player, OrgId org, OfficeId office, int seats, Office.Deciding how) {
        BookRouter.run(player, new SetSeats(org, office, seats, how),
                line -> whoDecides(player, BookResult.of(line, Text.get("book.decides.changed"))));
    }

    /** A Commune only: its members vote on its form. */
    public static void whoDecidesMembers(ServerPlayer player, String notice) {
        if (!member(player)) return;
        Standing.View v = view(player).orElseThrow();
        Office deciders = deciders(v.org());
        List<String> body = new ArrayList<>();
        body.add(Text.get("book.decides.intro"));
        List<BookButton> buttons = new ArrayList<>();
        if (deciders != null && deciders.seats() == 0) {
            for (int seats = 1; seats <= 5; seats++) {
                for (Office.Deciding deciding : Office.Deciding.values()) {
                    if (seats < 2 && deciding == Office.Deciding.TOGETHER) continue;
                    int n = seats;
                    Office.Deciding how = deciding;
                    buttons.add(go(player, choiceText(n, how), p -> {
                        Outcome outcome = rt().engine.propose(me(p), new SetSeats(v.org(), deciders.id, n, how));
                        whoDecidesMembers(p, outcome instanceof Outcome.Refused(Reason reason) ? BookRouter.refusal(reason)
                                : BookResult.ok(Text.get("book.decides.proposed")));
                    }));
                }
            }
        }
        BookDialogs.show(player, BookDialogs.list(Text.get("book.decides.title"), lines(notice, body), buttons,
                BookDialogs.back(player, p -> faction(p, null))));
    }

    private static String choiceText(int seats, Office.Deciding deciding) {
        if (seats == 0) return Text.get("book.decides.everyone");
        if (seats == 1) return Text.get("book.decides.one");
        return Text.get(deciding == Office.Deciding.TOGETHER ? "book.decides.council_together" : "book.decides.council_alone", "n", seats);
    }

    // ---- The Chronicle ----

    public static void chronicle(ServerPlayer player) {
        if (!member(player)) return;
        CharterRuntime rt = rt();
        OrgId org = view(player).orElseThrow().org();
        ChronicleBooks.Volume volume = ChronicleBooks.faction(rt.state, Chronicles.templates(), rt.knobs.chronicleShowAdmin, org, 1);
        BookReading.open(player, Chronicles.book(volume, "org:" + org.value()));
    }

    /** For the Realm Chronicle button on the newcomer pages. */
    static void realmChronicle(ServerPlayer player) {
        CharterRuntime rt = rt();
        ChronicleBooks.Volume volume = ChronicleBooks.realm(rt.state, Chronicles.templates(), rt.knobs.chronicleShowAdmin, 1);
        BookReading.open(player, Chronicles.book(volume, "realm"));
    }
}
