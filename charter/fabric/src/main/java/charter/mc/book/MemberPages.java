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
import charter.org.actions.Admit;
import charter.org.actions.Reject;
import charter.treasury.Currency;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.body.DialogBody;
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

    private static List<DialogBody> lines(String notice, List<String> lines) {
        List<DialogBody> body = new ArrayList<>();
        for (String line : lines) body.add(BookDialogs.text(line));
        return BookDialogs.withNotice(notice, body);
    }

    private static ActionButton go(ServerPlayer player, String label, java.util.function.Consumer<ServerPlayer> page) {
        return BookDialogs.act(player, label, null, page);
    }

    /** A page that needs a faction: a player who has left since the last press goes to the newcomer home. */
    private static boolean member(ServerPlayer player) {
        if (view(player).isPresent()) return true;
        NewcomerPages.home(player, null);
        return false;
    }

    // ---- Home ----

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
        List<ActionButton> buttons = List.of(
                go(player, Text.get("book.button.rights"), p -> rights(p, null)),
                go(player, Text.get("book.button.freedoms"), p -> freedoms(p, null)),
                go(player, Text.get("book.button.situation"), p -> situation(p, null)),
                go(player, v.orgName(), p -> faction(p, null)),
                go(player, Text.get("book.button.chronicle"), p -> chronicle(p)));
        BookDialogs.show(player, BookDialogs.list(Text.get("book.home.title", "org", v.orgName()),
                lines(notice, List.of(Text.get("book.home.form", "form", form, "offices", offices))),
                buttons, BookDialogs.close()));
    }

    private static Office deciders(OrgId org) {
        CharterRuntime rt = rt();
        return rt.state.org(org).map(o -> o.governance.deciders).flatMap(rt.state::office).orElse(null);
    }

    // ---- Rights and Freedoms ----

    public static void rights(ServerPlayer player, String notice) {
        if (!member(player)) return;
        Standing.View v = view(player).orElseThrow();
        List<String> rights = StandingText.joined(v.rights());
        List<String> body = new ArrayList<>();
        body.add(Text.get("book.rights.intro", "org", v.orgName()));
        if (rights.isEmpty()) body.add(Text.get("book.rights.none")); else body.addAll(rights);
        BookDialogs.show(player, BookDialogs.list(Text.get("book.rights.title"), lines(notice, body), List.of(),
                BookDialogs.back(player, p -> home(p, null))));
    }

    public static void freedoms(ServerPlayer player, String notice) {
        if (!member(player)) return;
        Standing.View v = view(player).orElseThrow();
        List<String> freedoms = StandingText.joined(v.freedoms());
        List<String> body = new ArrayList<>();
        body.add(Text.get("book.freedoms.intro", "org", v.orgName()));
        if (freedoms.isEmpty()) body.add(Text.get("book.freedoms.none")); else body.addAll(freedoms);
        BookDialogs.show(player, BookDialogs.list(Text.get("book.freedoms.title"), lines(notice, body), List.of(),
                BookDialogs.back(player, p -> home(p, null))));
    }

    // ---- Your situation ----

    public static void situation(ServerPlayer player, String notice) {
        if (!member(player)) return;
        CharterRuntime rt = rt();
        PlayerId me = me(player);
        long now = rt.clock.now();
        Standing.View v = view(player).orElseThrow();
        List<String> body = new ArrayList<>();
        body.add(Text.get("book.situation.member", "org", v.orgName(), "time", Chat.duration(now - v.memberSince())));
        Optional<Contract> held = Justice.holding(rt.state, rt.knobs, me, now);
        if (held.isPresent()) {
            Contract c = held.get();
            Term term = c.term(Term.Confinement.class) != null ? c.term(Term.Confinement.class) : c.term(Term.Tether.class);
            long left = term == null ? 0 : Justice.until(c, term, rt.knobs) - now;
            body.add(Text.get("book.situation.held", "time", Chat.duration(left)));
        }
        for (Organization org : rt.state.orgs()) {
            if (org.isTopLevel() && org.exists() && Justice.banished(rt.state, rt.knobs, me, org.id, now)) {
                body.add(Text.get("book.situation.banished", "org", org.name()));
            }
        }
        long respawn = Justice.respawnDelayLeft(rt.state, rt.knobs, me, now);
        if (respawn > 0) body.add(Text.get("book.situation.respawn", "time", Chat.duration(respawn)));
        Map<String, Integer> owed = rt.mail.owedTo(me);
        if (!owed.isEmpty()) {
            List<String> things = new ArrayList<>();
            owed.forEach((item, n) -> things.add(n + " " + item.substring(item.indexOf(':') + 1).replace('_', ' ')));
            body.add(Text.get("book.situation.owed", "list", String.join(", ", things)));
        }
        List<ActionButton> buttons = new ArrayList<>();
        for (VoteId id : v.votesWaiting()) {
            Optional<Vote> vote = rt.state.vote(id);
            if (vote.isEmpty()) continue;
            buttons.add(go(player, shorten(rt.notices.about(vote.get())), p -> vote(p, id, null)));
        }
        BookDialogs.show(player, BookDialogs.list(Text.get("book.situation.title"), lines(notice, body), buttons,
                BookDialogs.back(player, p -> home(p, null))));
    }

    // ---- A vote ----

    public static void vote(ServerPlayer player, VoteId id, String notice) {
        if (!member(player)) return;
        CharterRuntime rt = rt();
        Optional<Vote> found = rt.state.vote(id);
        if (found.isEmpty()) {
            situation(player, Text.get("book.vote.gone"));
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
        List<ActionButton> buttons = new ArrayList<>();
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
                BookDialogs.back(player, p -> situation(p, null))));
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
            situation(player, Text.get("book.vote.cast"));
        }
    }

    // ---- The faction ----

    /** For the link in a join request notice. */
    public static void openFaction(ServerPlayer player) {
        faction(player, null);
    }

    public static void faction(ServerPlayer player, String notice) {
        if (!member(player)) return;
        CharterRuntime rt = rt();
        PlayerId me = me(player);
        long now = rt.clock.now();
        Standing.View v = view(player).orElseThrow();
        OrgId org = v.org();
        Describe d = rt.describe;
        List<String> body = new ArrayList<>();

        List<String> members = new ArrayList<>();
        for (MemberRecord m : rt.state.members(org)) {
            List<String> held = rt.state.offices(org).stream().filter(o -> o.holds(m.player())).map(o -> o.name).toList();
            members.add(held.isEmpty() ? d.player(m.player()) : d.player(m.player()) + " (" + String.join(", ", held) + ")");
        }
        body.add(Text.get("book.faction.members", "count", members.size(), "list", String.join(", ", members)));
        List<String> empty = rt.state.offices(org).stream().filter(o -> o.vacant() && o.seats() > 0).map(o -> o.name).toList();
        if (!empty.isEmpty()) body.add(Text.get("book.faction.empty", "offices", String.join(", ", empty)));
        for (Vote running : rt.state.votes()) {
            if (!running.open() || !running.org.equals(org) || running.kind != Vote.Kind.ELECTION) continue;
            body.add(Text.get(running.restricted() ? "book.faction.election_council" : "book.faction.election_running",
                    "office", rt.describe.office(running.office)));
        }
        List<String> balances = new ArrayList<>();
        Organization organization = rt.state.org(org).orElseThrow();
        for (Currency c : Currency.values()) {
            long n = organization.ledger.balance(c);
            balances.add(Text.get("vault.amount", "amount", n, "currency", Describe.currency(c, n)));
        }
        body.add(Text.get("book.faction.treasury", "balances", String.join(", ", balances)));
        for (Core core : rt.state.coresOf(org)) {
            if (!core.isKeep()) continue;
            body.add(Text.get("book.faction.keep", "x", core.center.x() * 16 + 8, "z", core.center.z() * 16 + 8,
                    "granary", GranaryText.timeLine(rt, core)));
        }

        List<ActionButton> buttons = new ArrayList<>();
        for (Vote vote : rt.state.votes()) {
            if (!vote.open() || !vote.org.equals(org)) continue;
            VoteId id = vote.id;
            buttons.add(go(player, shorten(rt.notices.about(vote)), p -> vote(p, id, null)));
        }
        boolean admits = new AuthorityResolver(rt.state, now)
                .resolve(new Who.AsPlayer(me), org, Power.MANAGE_MEMBERS, ScopeNeed.NONE, false).ok();
        if (admits) {
            for (JoinRequest request : rt.state.joinRequests()) {
                if (!request.org().equals(org)) continue;
                PlayerId applicant = request.player();
                String name = d.player(applicant);
                body.add(Text.get("book.faction.asks", "player", name));
                buttons.add(go(player, Text.get("book.button.admit", "player", name), p -> BookRouter.run(p, new Admit(org, applicant),
                        line -> faction(p, line != null ? line : Text.get("book.faction.admitted", "player", name)))));
                buttons.add(go(player, Text.get("book.button.reject", "player", name), p -> confirmReject(p, org, applicant)));
            }
        }
        for (Currency c : Currency.values()) {
            int carried = charter.mc.cmd.CharterCommands.carried(player, c);
            if (carried <= 0) continue;
            buttons.add(go(player, Text.get("book.button.pay", "amount", carried, "currency", Describe.currency(c, carried)), p -> {
                int paid = charter.mc.cmd.CharterCommands.depositAll(p, c);
                faction(p, paid > 0 ? Text.get("book.faction.paid", "amount", paid, "currency", Describe.currency(c, paid))
                        : Text.get("book.faction.not_paid"));
            }));
        }
        if (rt.engine.eligibleVoters(org, now).contains(me)) {
            buttons.add(go(player, Text.get("book.faction.who_decides"), p -> whoDecides(p, null)));
            Office deciders = deciders(org);
            if (deciders != null && deciders.seats() > 0 && !deciders.full()
                    && rt.state.votes().stream().noneMatch(x -> x.open() && x.kind == Vote.Kind.ELECTION && deciders.id.equals(x.office))) {
                buttons.add(go(player, Text.get("book.faction.elect"), p -> elect(p, deciders.id)));
            }
        }
        boolean voter = rt.engine.eligibleVoters(org, now).contains(me);
        long wait = v.memberSince() + charter.time.Durations.days(rt.knobs.voteEligibilityDays) - now;
        if (!voter && wait > 0) body.add(Text.get("book.faction.vote_later", "time", Chat.duration(wait)));
        BookDialogs.show(player, BookDialogs.list(v.orgName(), lines(notice, body), buttons,
                BookDialogs.back(player, p -> home(p, null))));
    }

    private static void confirmReject(ServerPlayer player, OrgId org, PlayerId applicant) {
        String name = rt().describe.player(applicant);
        ActionButton yes = BookDialogs.act(player, Text.get("book.button.yes"), null, p -> BookRouter.run(p,
                new Reject(org, applicant), line -> faction(p, line != null ? line : Text.get("book.faction.rejected", "player", name))));
        ActionButton no = BookDialogs.back(player, p -> faction(p, null));
        BookDialogs.show(player, BookDialogs.confirm(Text.get("book.reject.title"),
                List.of(BookDialogs.text(Text.get("book.reject.confirm", "player", name))), yes, no));
    }

    private static void elect(ServerPlayer player, OfficeId office) {
        Outcome outcome = rt().engine.openElection(office, me(player));
        if (outcome instanceof Outcome.Refused(Reason reason)) {
            faction(player, BookRouter.refusal(reason));
        } else if (outcome instanceof Outcome.VoteOpened(VoteId id)) {
            vote(player, id, Text.get("book.faction.election_opened"));
        } else {
            faction(player, null);
        }
    }

    // ---- Who decides ----

    /**
     * Who decides. A holder of the decision makers office sees the changes the office may make
     * itself (to a council, or back to a lordship); everyone, holders included, may put any form to
     * a vote of the members.
     */
    public static void whoDecides(ServerPlayer player, String notice) {
        if (!member(player)) return;
        Standing.View v = view(player).orElseThrow();
        Office deciders = deciders(v.org());
        if (deciders == null || !deciders.holds(me(player)) || deciders.seats() == 0) {
            whoDecidesMembers(player, notice);
            return;
        }
        List<String> body = new ArrayList<>();
        body.add(Text.get("book.decides.own"));
        body.add(Text.get("book.decides.now", "now", choiceText(deciders.seats(), deciders.deciding())));
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(go(player, Text.get("book.decides.to_council_alone"), p -> councilSize(p, Office.Deciding.ALONE, null)));
        buttons.add(go(player, Text.get("book.decides.to_council_together"), p -> councilSize(p, Office.Deciding.TOGETHER, null)));
        if (deciders.seats() >= 2) {
            buttons.add(go(player, Text.get("book.decides.to_lordship"), p -> changeForm(p, v.org(), deciders.id, 1, Office.Deciding.ALONE)));
        }
        buttons.add(go(player, Text.get("book.decides.to_members"), p -> whoDecidesMembers(p, null)));
        BookDialogs.show(player, BookDialogs.list(Text.get("book.decides.title"), lines(notice, body), buttons,
                BookDialogs.back(player, p -> faction(p, null))));
    }

    /** How many seats, for a council the office makes itself: a short list rather than a button per form. */
    private static void councilSize(ServerPlayer player, Office.Deciding how, String notice) {
        if (!member(player)) return;
        Standing.View v = view(player).orElseThrow();
        Office deciders = deciders(v.org());
        if (deciders == null) return;
        List<ActionButton> buttons = new ArrayList<>();
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
                line -> whoDecides(player, line != null ? line : Text.get("book.decides.changed")));
    }

    /** Any form, put to a vote of the members. */
    public static void whoDecidesMembers(ServerPlayer player, String notice) {
        if (!member(player)) return;
        CharterRuntime rt = rt();
        Standing.View v = view(player).orElseThrow();
        Office deciders = deciders(v.org());
        List<String> body = new ArrayList<>();
        body.add(Text.get("book.decides.intro"));
        if (deciders != null) body.add(Text.get("book.decides.now", "now", choiceText(deciders.seats(), deciders.deciding())));
        List<ActionButton> buttons = new ArrayList<>();
        if (deciders != null) {
            for (int seats = 0; seats <= 5; seats++) {
                for (Office.Deciding deciding : Office.Deciding.values()) {
                    if (seats < 2 && deciding == Office.Deciding.TOGETHER) continue;
                    if (seats == deciders.seats() && deciding == deciders.deciding()) continue;
                    int n = seats;
                    Office.Deciding how = deciding;
                    buttons.add(go(player, choiceText(n, how), p -> {
                        Outcome outcome = rt().engine.propose(me(p), new SetSeats(v.org(), deciders.id, n, how));
                        whoDecidesMembers(p, outcome instanceof Outcome.Refused(Reason reason) ? BookRouter.refusal(reason)
                                : Text.get("book.decides.proposed"));
                    }));
                }
            }
        }
        boolean own = deciders != null && deciders.holds(me(player)) && deciders.seats() > 0;
        BookDialogs.show(player, BookDialogs.list(Text.get("book.decides.title"), lines(notice, body), buttons,
                BookDialogs.back(player, p -> {
                    if (own) whoDecides(p, null); else faction(p, null);
                })));
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
