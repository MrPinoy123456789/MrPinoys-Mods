package charter.mc.discord;

import charter.engine.ActionEngine;
import charter.grant.AuthorityResolver;
import charter.grant.Power;
import charter.grant.ScopeNeed;
import charter.grant.Who;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.mc.ActAs;
import charter.mc.Describe;
import charter.mc.Text;
import charter.org.JoinRequest;
import charter.org.MemberRecord;
import charter.org.actions.Admit;
import charter.org.actions.Reject;
import charter.state.CharterState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The join requests a player may answer, and answering them, for surfaces outside
 * the game (Discord). The same rule as the Faction page: a player sees a faction's
 * requests when they may take players in (MANAGE_MEMBERS), which is the Lord of a
 * Lordship and anyone the faction granted that power. Every answer runs through
 * the engine as that player, so it is checked again at the moment it is given.
 * Runs on the server thread.
 */
public final class JoinDesk {

    /** One request, with the names to show. */
    public record Entry(OrgId org, String orgName, PlayerId player, String playerName, long at) {
    }

    /** What answering did: {@code done} when the request is settled, and the line to show. */
    public record Answer(boolean done, String line) {
    }

    private final CharterState state;
    private final ActionEngine engine;
    private final Describe describe;

    public JoinDesk(CharterState state, ActionEngine engine, Describe describe) {
        this.state = state;
        this.engine = engine;
        this.describe = describe;
    }

    public boolean mayAnswer(PlayerId player, OrgId org) {
        return new AuthorityResolver(state, engine.now())
                .resolve(new Who.AsPlayer(player), org, Power.MANAGE_MEMBERS, ScopeNeed.NONE, false).ok();
    }

    /** The open requests this player may answer, oldest first. */
    public List<Entry> answerable(PlayerId player) {
        List<Entry> out = new ArrayList<>();
        for (JoinRequest r : state.joinRequests()) {
            if (!mayAnswer(player, r.org())) continue;
            out.add(new Entry(r.org(), describe.org(r.org()), r.player(), describe.player(r.player()), r.at()));
        }
        out.sort(Comparator.comparingLong(Entry::at));
        return out;
    }

    /** The members of a faction who may answer its requests: who is told when someone asks. */
    public List<PlayerId> answerers(OrgId org) {
        List<PlayerId> out = new ArrayList<>();
        for (MemberRecord m : state.members(org)) {
            if (mayAnswer(m.player(), org)) out.add(m.player());
        }
        return out;
    }

    /** Admits or turns away, as {@code player}. */
    public Answer answer(PlayerId player, OrgId org, PlayerId applicant, boolean admit) {
        String name = describe.player(applicant);
        if (state.joinRequest(org, applicant).isEmpty()) {
            return new Answer(false, Text.get("discord.requests.gone", "player", name));
        }
        String refused = ActAs.run(state, engine, describe, player,
                admit ? new Admit(org, applicant) : new Reject(org, applicant));
        if (refused != null) return new Answer(false, refused);
        return new Answer(true, Text.get(admit ? "book.faction.admitted" : "book.faction.rejected", "player", name));
    }
}
