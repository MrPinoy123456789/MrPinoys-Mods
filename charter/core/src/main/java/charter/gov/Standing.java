package charter.gov;

import charter.engine.ActionEngine;
import charter.grant.Authority;
import charter.grant.AuthorityResolver;
import charter.grant.Grant;
import charter.grant.Holder;
import charter.grant.Power;
import charter.grant.ScopeNeed;
import charter.grant.Who;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.id.VoteId;
import charter.org.MemberRecord;
import charter.state.CharterState;
import charter.treasury.Currency;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What one player holds and what they give away, worked out from grants at the
 * moment of reading and never stored (the Charter book's Rights and Freedoms).
 * A Right is a power the player may use alone; a Freedom is a power someone else
 * holds, or the members hold by vote.
 */
public final class Standing {

    public enum Form { COMMUNE, LORDSHIP, COUNCIL }

    public enum HolderKind { YOU, OFFICE, PLAYER, ORG, MEMBERS_BY_VOTE }

    /** The player a newcomer's view is read as: nobody, who holds nothing and cannot vote. */
    private static final PlayerId NOBODY = new PlayerId(new UUID(0, 0));

    /**
     * One reader-facing line: a group, or one power of a group whose powers differ.
     *
     * @param power               null for the whole group
     * @param name                the office, player or organization name; empty otherwise
     * @param holders             for OFFICE: the seated players' names
     * @param together            for OFFICE: the office decides together
     * @param spendLimitDiamonds  -1 when there is none
     */
    public record Line(PowerGroup group, Power power, HolderKind kind, String name, List<String> holders,
                       boolean together, boolean youCanVote, long spendLimitDiamonds) {

        public boolean right() {
            return kind == HolderKind.YOU;
        }

        /** The same holder, vote and limit: lines that read the same way. */
        public boolean sameHolder(Line other) {
            return kind == other.kind && name.equals(other.name) && holders.equals(other.holders)
                    && together == other.together && youCanVote == other.youCanVote
                    && spendLimitDiamonds == other.spendLimitDiamonds;
        }
    }

    public record View(OrgId org, String orgName, Form form, List<String> yourOffices, long memberSince,
                       List<Line> lines, List<VoteId> votesWaiting) {

        public List<Line> rights() {
            return lines.stream().filter(Line::right).toList();
        }

        public List<Line> freedoms() {
            return lines.stream().filter(l -> !l.right()).toList();
        }
    }

    private Standing() {
    }

    /** Empty when the player belongs to no organization. */
    public static Optional<View> of(CharterState state, ActionEngine engine, PlayerId player, long now) {
        Optional<MemberRecord> membership = state.membership(player);
        if (membership.isEmpty()) return Optional.empty();
        OrgId org = membership.get().org();
        List<String> offices = new ArrayList<>();
        for (Office office : state.offices(org)) {
            if (office.holds(player)) offices.add(office.name);
        }
        List<VoteId> waiting = state.votes().stream()
                .filter(v -> v.open() && v.org.equals(org) && v.electorate.contains(player) && !v.ballots().containsKey(player))
                .sorted(Comparator.comparingLong(v -> v.openedAt))
                .map(v -> v.id)
                .toList();
        return Optional.of(new View(org, state.org(org).map(o -> o.name()).orElse(""), form(state, org), offices,
                membership.get().joinedAt(), lines(state, engine, player, org, now), waiting));
    }

    /** The same view as a member of org would read it on joining today (for the Join page). */
    public static View asNewcomer(CharterState state, ActionEngine engine, OrgId org, long now) {
        return new View(org, state.org(org).map(o -> o.name()).orElse(""), form(state, org), List.of(), 0,
                lines(state, engine, NOBODY, org, now), List.of());
    }

    public static Form form(CharterState state, OrgId org) {
        Optional<Office> deciders = state.org(org).map(o -> o.governance.deciders).flatMap(state::office);
        int seats = deciders.map(Office::seats).orElse(0);
        return seats == 0 ? Form.COMMUNE : seats == 1 ? Form.LORDSHIP : Form.COUNCIL;
    }

    // ---- the rules ----

    private static List<Line> lines(CharterState state, ActionEngine engine, PlayerId me, OrgId org, long now) {
        AuthorityResolver resolver = new AuthorityResolver(state, now);
        boolean canVote = engine.eligibleVoters(org, now).contains(me);
        List<Line> out = new ArrayList<>();
        for (PowerGroup group : PowerGroup.values()) {
            List<Line> perPower = new ArrayList<>();
            for (Power power : group.powers) perPower.add(line(state, resolver, me, org, group, power, canVote));
            boolean same = perPower.stream().allMatch(l -> l.sameHolder(perPower.get(0)));
            if (same) {
                Line first = perPower.get(0);
                out.add(new Line(group, null, first.kind(), first.name(), first.holders(), first.together(),
                        first.youCanVote(), first.spendLimitDiamonds()));
            } else {
                out.addAll(perPower);
            }
        }
        return out;
    }

    private static Line line(CharterState state, AuthorityResolver resolver, PlayerId me, OrgId org, PowerGroup group,
                             Power power, boolean canVote) {
        Authority mine = resolver.resolve(new Who.AsPlayer(me), org, power, ScopeNeed.NONE, false);
        if (mine.ok() && !councilOf(state, mine).isPresent()) {
            return describe(state, group, power, HolderKind.YOU, mine.via(), canVote);
        }
        if (mine.ok()) {
            // Reaches them only through a council that decides together: they cannot act alone.
            return describe(state, group, power, null, mine.via(), canVote);
        }
        for (Grant grant : state.grants()) {
            if (!grant.subject.equals(org) || grant.power != power || !resolver.valid(grant)) continue;
            if (grant.recipient instanceof Holder.OfOffice o && state.office(o.office()).map(Office::vacant).orElse(true)) continue;
            return describe(state, group, power, null, grant, canVote);
        }
        return new Line(group, power, HolderKind.MEMBERS_BY_VOTE, "", List.of(), false, canVote, -1);
    }

    private static Optional<Office> councilOf(CharterState state, Authority authority) {
        if (authority.via() == null || !(authority.via().recipient instanceof Holder.OfOffice o)) return Optional.empty();
        return state.office(o.office()).filter(office -> office.deciding() == Office.Deciding.TOGETHER && office.holders().size() >= 2);
    }

    /** A line for a grant; {@code you} forces the kind (YOU), otherwise the kind follows the recipient. */
    private static Line describe(CharterState state, PowerGroup group, Power power, HolderKind you, Grant grant, boolean canVote) {
        long limit = power == Power.SPEND ? grant.scope.limit(Currency.DIAMOND) : Long.MAX_VALUE;
        long shown = limit == Long.MAX_VALUE ? -1 : limit;
        return switch (grant.recipient) {
            case Holder.OfOffice o -> {
                Office office = state.office(o.office()).orElseThrow();
                List<String> names = office.holders().stream().map(s -> name(state, s.player())).toList();
                boolean together = office.deciding() == Office.Deciding.TOGETHER && office.holders().size() >= 2;
                yield new Line(group, power, you != null ? you : HolderKind.OFFICE, office.name, you != null ? List.of() : names,
                        together, canVote, shown);
            }
            case Holder.OfOrg o -> new Line(group, power, you != null ? you : HolderKind.ORG,
                    state.org(o.org()).map(x -> x.name()).orElse(""), List.of(), false, canVote, shown);
            case Holder.OfPlayer p -> new Line(group, power, you != null ? you : HolderKind.PLAYER,
                    you != null ? "" : name(state, p.player()), List.of(), false, canVote, shown);
        };
    }

    private static String name(CharterState state, PlayerId id) {
        return state.knownPlayer(id).map(p -> p.name).filter(n -> !n.isEmpty()).orElse("someone");
    }
}
