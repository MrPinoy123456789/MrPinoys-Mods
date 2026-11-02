package charter.mc.book;

import charter.contract.Custody;
import charter.contract.Justice;
import charter.gov.Standing;
import charter.gov.Vote;
import charter.grant.AuthorityResolver;
import charter.grant.Power;
import charter.grant.ScopeNeed;
import charter.grant.Who;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.id.VoteId;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.justice.JusticePages;
import charter.org.JoinRequest;
import charter.org.Invite;
import charter.siege.WarBook;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Everything that waits for a player, computed once from live state: a vote to cast, a request to join to answer, an
 * invitation to accept, a war on their nation's outpost, a prisoner awaiting sentence. The Home button's count, the Needs
 * you page, and the book's glint and lore all read this, so they can never disagree. It reads state only.
 */
public final class Needs {

    public enum Kind { VOTE, REQUEST, INVITE, WAR, CUSTODY }

    /** One waiting thing: a short label (24 characters at most) and what pressing it opens. */
    public record Need(Kind kind, String label, Consumer<ServerPlayer> open) {
    }

    private Needs() {
    }

    public static List<Need> of(ServerPlayer player) {
        List<Need> out = new ArrayList<>();
        if (!CharterServer.running()) return out;
        CharterRuntime rt = CharterServer.runtime();
        PlayerId me = new PlayerId(player.getUUID());
        long now = rt.clock.now();
        Optional<Standing.View> view = Standing.of(rt.state, rt.engine, me, now);
        if (view.isEmpty()) {
            for (Invite invite : rt.state.invites()) {
                if (!invite.player().equals(me) || rt.state.org(invite.org()).isEmpty()) continue;
                OrgId inviter = invite.org();
                out.add(new Need(Kind.INVITE, Text.get("book.needs.invite", "org", BookDialogs.clip(rt.describe.org(inviter), 18)),
                        p -> NewcomerPages.acceptInvite(p, inviter, NeedsPages::page)));
            }
            return out;
        }
        OrgId org = view.get().org();
        for (VoteId id : view.get().votesWaiting()) {
            Optional<Vote> vote = rt.state.vote(id);
            if (vote.isEmpty()) continue;
            out.add(new Need(Kind.VOTE, Text.get("book.needs.vote", "about", BookDialogs.clip(rt.notices.about(vote.get()), 17)),
                    p -> MemberPages.vote(p, id, null)));
        }
        AuthorityResolver authority = new AuthorityResolver(rt.state, now);
        if (authority.resolve(new Who.AsPlayer(me), org, Power.MANAGE_MEMBERS, ScopeNeed.NONE, false).ok()) {
            for (JoinRequest request : rt.state.joinRequests()) {
                if (!request.org().equals(org)) continue;
                PlayerId applicant = request.player();
                out.add(new Need(Kind.REQUEST, Text.get("book.needs.request", "player", BookDialogs.clip(rt.describe.player(applicant), 9)),
                        p -> LeaderPages.request(p, applicant, true)));
            }
        }
        OrgId nation = rt.state.root(org);
        for (WarBook.Goal goal : rt.state.wars.goals) {
            if (goal.defender.equals(nation)) {
                out.add(new Need(Kind.WAR, Text.get("book.needs.war"), p -> WarPages.page(p, null)));
                break;
            }
        }
        boolean judge = authority.resolve(new Who.AsPlayer(me), org, Power.JUDGE, ScopeNeed.NONE, false).ok();
        if (judge) {
            for (Custody custody : rt.state.contracts.custodies()) {
                if (!rt.state.root(custody.faction()).equals(nation) || !Justice.custodyLive(custody, rt.knobs, now)) continue;
                PlayerId prisoner = custody.prisoner();
                out.add(new Need(Kind.CUSTODY, Text.get("book.needs.custody", "player", BookDialogs.clip(rt.describe.player(prisoner), 10)),
                        p -> JusticePages.sentence(p, prisoner)));
            }
        }
        return out;
    }

    public static int count(ServerPlayer player) {
        return of(player).size();
    }
}
