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

        /** Hands over whatever {@link Mail} says is owed, if the player is online. */
        void deliver(PlayerId player);
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
            case SiegeEvents.SiegeBegan e -> tell(e.at(), members(e.defender()),
                    Text.get("notice.besieged", "site", describe.core(e.keep())));
            case SiegeEvents.KeepFell e -> tell(e.at(), members(e.owner()),
                    Text.get("notice.keep_fell", "site", describe.core(e.keep()), "org", describe.org(e.besieger())));
            case SiegeEvents.SiegeLifted e -> state.core(e.keep()).ifPresent(keep ->
                    tell(e.at(), members(keep.owner), Text.get("notice.lifted", "site", describe.core(e.keep()))));
            default -> {
            }
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
