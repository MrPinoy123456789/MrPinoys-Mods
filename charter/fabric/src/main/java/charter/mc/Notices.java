package charter.mc;

import charter.gov.GovEvents;
import charter.gov.Vote;
import charter.history.History;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.MemberRecord;
import charter.org.OrgEvents;
import charter.siege.SiegeEvents;
import charter.state.CharterState;
import charter.treasury.TreasuryEvents;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Tells players what happened to them: at once if they are online, else kept in
 * {@link Mail} and read out when they join. Also turns every treasury payout into
 * items owed to the player, so a withdrawal a vote passes while they are away is
 * handed over when they come back.
 *
 * <p>Only events a player would want to hear about are told; the Chronicle and the
 * faction page carry the rest.
 */
public final class Notices {

    /** How the notices reach players. The server's player list in game; a fake in tests. */
    public interface Messenger {
        boolean online(PlayerId player);

        void send(PlayerId player, String text);

        /** Tells a member who may take players in that someone asked to join; in game with a link to the Faction page. */
        default void askedToJoin(PlayerId to, String text) {
            send(to, text);
        }

        /** Hands over whatever {@link Mail} says is owed, if the player is online. */
        void deliver(PlayerId player);

        /** A siege alert (PLAN.md §7.2): by default a plain message; in game also a title and a quiet chime. */
        default void alert(PlayerId player, String text) {
            send(player, text);
        }
    }

    private final CharterState state;
    private final Mail mail;
    private final Describe describe;
    private final Messenger messenger;

    public Notices(CharterState state, Mail mail, Describe describe, Messenger messenger) {
        this.state = state;
        this.mail = mail;
        this.describe = describe;
        this.messenger = messenger;
    }

    /** History listener. */
    public void onEntry(History.Entry entry) {
        HistoryEvent event = entry.event();
        switch (event) {
            case OrgEvents.Invited e -> tell(e.at(), Set.of(e.player()), Text.get("notice.invited", "org", describe.org(e.org())));
            case OrgEvents.Joined e -> tell(e.at(), membersExcept(e.org(), e.player()),
                    Text.get("notice.joined", "player", describe.player(e.player()), "org", describe.org(e.org())));
            case OrgEvents.Asked e -> {
                String text = Text.get("notice.asked", "player", describe.player(e.player()), "org", describe.org(e.org()));
                var resolver = new charter.grant.AuthorityResolver(state, e.at());
                for (PlayerId member : members(e.org())) {
                    boolean admits = resolver.resolve(new charter.grant.Who.AsPlayer(member), e.org(),
                            charter.grant.Power.MANAGE_MEMBERS, charter.grant.ScopeNeed.NONE, false).ok();
                    if (!admits) continue;
                    if (messenger.online(member)) messenger.askedToJoin(member, text);
                    else mail.post(member, e.at(), text);
                }
            }
            case OrgEvents.Admitted e -> tell(e.at(), Set.of(e.player()), Text.get("notice.admitted", "org", describe.org(e.org())));
            case OrgEvents.Rejected e -> tell(e.at(), Set.of(e.player()), Text.get("notice.rejected", "org", describe.org(e.org())));
            case OrgEvents.Expelled e -> tell(e.at(), Set.of(e.player()), Text.get("notice.expelled", "org", describe.org(e.org())));
            case OrgEvents.Activated e -> tell(e.at(), members(e.org()), Text.get("notice.activated", "org", describe.org(e.org())));
            case GovEvents.VoteOpened e -> {
                Set<PlayerId> who = state.vote(e.vote()).map(v -> (Set<PlayerId>) new LinkedHashSet<>(v.electorate)).orElse(members(e.org()));
                who.remove(e.proposer());
                tell(e.at(), who, Text.get("notice.vote_opened", "org", describe.org(e.org()), "about", about(e.vote())));
            }
            case GovEvents.VotePassed e -> tell(e.at(), members(e.org()),
                    Text.get("notice.vote_passed", "org", describe.org(e.org()), "about", about(e.vote())));
            case GovEvents.VoteFailed e -> tell(e.at(), members(e.org()),
                    Text.get("notice.vote_failed", "org", describe.org(e.org()), "about", about(e.vote()), "why", e.why()));
            case GovEvents.VoteRefusedAtExecution e -> tell(e.at(), members(e.org()),
                    Text.get("notice.vote_refused", "org", describe.org(e.org()), "about", about(e.vote())));
            case GovEvents.OfficeFilled e -> tell(e.at(), Set.of(e.holder()),
                    Text.get("notice.office_filled", "office", describe.office(e.office()), "org", describe.org(e.org())));
            case GovEvents.OfficeVacated e -> {
                if (e.formerHolder() != null) tell(e.at(), Set.of(e.formerHolder()),
                        Text.get("notice.office_vacated", "office", describe.office(e.office()), "org", describe.org(e.org())));
            }
            case TreasuryEvents.Withdrawn e -> {
                if (e.to() == null) return;
                mail.owe(e.to(), e.currency().itemId, (int) Math.min(Integer.MAX_VALUE, e.amount()));
                tell(e.at(), Set.of(e.to()), Text.get("notice.paid", "org", describe.org(e.org()),
                        "amount", e.amount(), "currency", Describe.currency(e.currency(), e.amount())));
                messenger.deliver(e.to());
            }
            // ---- keep upkeep: to the whole faction, subordinates included, as alerts ----
            case SiegeEvents.KeepRunningLow e -> alert(e.at(), faction(e.owner()), Text.get("notice.keep_low",
                    "site", describe.core(e.keep()), "days", e.daysLeft()));
            case SiegeEvents.KeepLapsed e -> alert(e.at(), faction(e.owner()), Text.get("notice.keep_lapsed",
                    "site", describe.core(e.keep())));
            case charter.land.LandEvents.Contested e -> alert(e.at(), faction(e.org()), Text.get("notice.contested", "count", e.count()));
            case charter.land.LandEvents.Lapsed e -> alert(e.at(), faction(e.org()), Text.get("notice.lapsed", "count", e.count()));
            // ---- justice (M12): to the prisoner, and to the judge or officer who acted ----
            case charter.contract.ContractEvents.Arrested e -> tell(e.at(), Set.of(e.prisoner()), Text.get("notice.arrested",
                    "officer", describe.player(e.officer()), "org", describe.org(e.faction())));
            case charter.contract.ContractEvents.CustodyLapsed e -> tell(e.at(), Set.of(e.prisoner()), Text.get("notice.custody_lapsed"));
            case charter.contract.ContractEvents.Imposed e -> {
                if (e.basis() != charter.contract.Basis.CUSTODY || !e.subject().isPlayer()) return;
                String terms = charter.mc.justice.JusticeText.terms(e.terms());
                tell(e.at(), Set.of(e.subject().player()), Text.get("notice.sentenced", "org", describe.org(e.imposer().org()), "terms", terms));
                PlayerId judge = judge(entry);
                if (judge != null) tell(e.at(), Set.of(judge), Text.get("notice.sentence_done",
                        "player", describe.player(e.subject().player()), "terms", terms));
            }
            case charter.contract.ContractEvents.Fined e -> tell(e.at(), Set.of(e.prisoner()), Text.get("notice.fined",
                    "org", describe.org(e.to()), "asked", e.asked(), "paid", e.paid()));
            case charter.contract.ContractEvents.Escaped e -> tell(e.at(), faction(e.faction()), Text.get("notice.escaped",
                    "player", describe.player(e.prisoner())));
            case charter.contract.ContractEvents.Released e -> tell(e.at(), Set.of(e.prisoner()), Text.get("notice.released", "org", describe.org(e.by())));
            case charter.contract.ContractEvents.Pardoned e -> tell(e.at(), Set.of(e.prisoner()), Text.get("notice.pardoned", "org", describe.org(e.by())));
            case charter.contract.ContractEvents.PrisonersFreed e -> {
                for (PlayerId freed : e.prisoners()) tell(e.at(), Set.of(freed), Text.get("notice.freed", "site", describe.core(e.keep())));
            }
            case charter.contract.ContractEvents.Fulfilled e -> state.contracts.get(e.contract()).ifPresent(c -> {
                if (c.basis == charter.contract.Basis.CUSTODY && c.subject.isPlayer()) {
                    tell(e.at(), Set.of(c.subject.player()), Text.get("notice.served"));
                }
            });
            default -> {
            }
        }
    }

    /** The player who ran the action behind an entry, or null when it was a vote, an operator or the rules. */
    private static PlayerId judge(History.Entry entry) {
        try {
            return new PlayerId(java.util.UUID.fromString(entry.actor().startsWith("player:") ? entry.actor().substring(7) : entry.actor()));
        } catch (IllegalArgumentException notAPlayer) {
            return null;
        }
    }

    private String about(charter.id.VoteId id) {
        return state.vote(id).map(this::about).orElse(Text.get("motion.unknown"));
    }

    public String about(Vote vote) {
        if (vote.kind == Vote.Kind.ELECTION) return Text.get("motion.election", "office", describe.office(vote.office));
        return describe.action(vote.motion);
    }

    private Set<PlayerId> members(OrgId org) {
        Set<PlayerId> out = new LinkedHashSet<>();
        if (org == null) return out;
        for (MemberRecord m : state.members(org)) out.add(m.player());
        return out;
    }

    /** Every member of the faction an organization belongs to: its top organization and all below it. */
    private Set<PlayerId> faction(OrgId org) {
        Set<PlayerId> out = new LinkedHashSet<>();
        if (org == null || state.org(org).isEmpty()) return out;
        OrgId root = state.root(org);
        out.addAll(members(root));
        for (OrgId below : state.descendants(root)) out.addAll(members(below));
        return out;
    }

    private void alert(long at, Set<PlayerId> players, String text) {
        for (PlayerId player : List.copyOf(players)) {
            if (player == null) continue;
            if (messenger.online(player)) messenger.alert(player, text);
            else mail.post(player, at, text);
        }
    }

    private Set<PlayerId> membersExcept(OrgId org, PlayerId except) {
        Set<PlayerId> out = members(org);
        out.remove(except);
        return out;
    }

    private void tell(long at, Set<PlayerId> players, String text) {
        for (PlayerId player : List.copyOf(players)) {
            if (player == null) continue;
            if (messenger.online(player)) messenger.send(player, text);
            else mail.post(player, at, text);
        }
    }
}
