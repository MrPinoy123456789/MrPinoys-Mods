package charter.org;

import charter.Fixture;
import charter.engine.Outcome;
import charter.gov.Preset;
import charter.gov.Vote;
import charter.grant.GrantScope;
import charter.grant.Holder;
import charter.grant.Power;
import charter.grant.actions.GrantPower;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.actions.PlaceNexus;
import charter.org.actions.AcceptInvite;
import charter.org.actions.Apply;
import charter.org.actions.CreateSubOrg;
import charter.org.actions.DissolveOrg;
import charter.org.actions.InviteMember;
import charter.org.actions.Rename;
import charter.treasury.Currency;
import charter.treasury.actions.Deposit;

import static charter.Check.check;
import static charter.Check.eq;
import static charter.Fixture.player;

/** An operator dissolves a faction and everything below it. */
public final class DissolveOrgTest {

    static final PlayerId KRIS = player("Kris");
    static final PlayerId GIL = player("Gil");
    static final PlayerId CY = player("Cy");
    static final PlayerId DEE = player("Dee");
    static final String DIM = "minecraft:overworld";

    private DissolveOrgTest() {
    }

    public static void run() {
        theWholeSubtreeGoesAndHistoryKeepsTheName();
        onlyAnOperatorMay();
    }

    static void theWholeSubtreeGoesAndHistoryKeepsTheName() {
        Fixture f = new Fixture();
        f.found("Realm", Preset.LORDSHIP, KRIS, GIL, CY);
        OrgId realm = f.state.orgNamed("Realm").orElseThrow().id;
        f.passDays(4, KRIS, GIL, CY);
        f.admin(new CreateSubOrg(realm, "Guard", Preset.LORDSHIP, GIL));
        OrgId guard = f.state.orgNamed("Guard").orElseThrow().id;
        f.as(KRIS, new Deposit(realm, Currency.DIAMOND, 70));
        check("setup: the realm raises a keep", f.as(KRIS, new PlaceNexus(realm, new ChunkKey(DIM, 10, 10))).done());
        f.as(GIL, new Deposit(guard, Currency.DIAMOND, 70));
        check("setup: the guard raises a keep", f.as(GIL, new PlaceNexus(guard, new ChunkKey(DIM, 90, 90))).done());
        f.admin(new GrantPower(realm, Power.CLAIM, new Holder.OfOrg(guard), GrantScope.ANY, false, 0));
        Outcome open = f.engine.propose(KRIS, new charter.gov.actions.CreateOffice(realm, "Reeve"));
        Vote vote = f.vote(((Outcome.VoteOpened) open).vote());
        f.seen(DEE);
        f.as(DEE, new Apply(DEE, realm));
        f.admin(new InviteMember(realm, player("Eve")));

        Outcome out = f.admin(new DissolveOrg(realm));
        check("1: the operator dissolves it " + out, out.done());
        check("1: the faction is gone from play", f.state.org(realm).isEmpty() && f.state.org(guard).isEmpty());
        eq("1: history still names it", "Realm", f.state.orgIncludingDissolved(realm).orElseThrow().name());
        eq("1: and its sub-faction", "Guard", f.state.orgIncludingDissolved(guard).orElseThrow().name());
        check("1: every member is factionless", f.state.membership(KRIS).isEmpty() && f.state.membership(GIL).isEmpty()
                && f.state.membership(CY).isEmpty());
        check("1: no keep, no land", f.state.coresOf(realm).isEmpty() && f.state.coresOf(guard).isEmpty()
                && f.state.claimsOf(realm).isEmpty() && f.state.claimsOf(guard).isEmpty());
        eq("1: the open vote was closed", Vote.State.FAILED, vote.state());
        check("1: the join request is gone", f.state.joinRequest(realm, DEE).isEmpty());
        check("1: the invite is gone", f.state.invites().isEmpty());
        check("1: the grants ended", f.state.grants().stream().allMatch(charter.grant.Grant::revoked));
        long recorded = f.state.history.entries().stream().filter(e -> e.event() instanceof OrgEvents.Dissolved).count();
        eq("1: one Dissolved line for each", 2L, recorded);
        check("1: the name is free again", f.state.orgNamed("Realm").isEmpty());
    }

    static void onlyAnOperatorMay() {
        Fixture f = new Fixture();
        f.found("Realm", Preset.LORDSHIP, KRIS, GIL);
        OrgId realm = f.state.orgNamed("Realm").orElseThrow().id;
        eq("2: not even the Lord", charter.land.LandReasons.OPERATOR_ONLY.key(), f.as(KRIS, new DissolveOrg(realm)).reasonKey());
        check("2: it still stands", f.state.org(realm).isPresent());
        eq("2: no faction, no dissolving", charter.engine.Reason.NO_SUCH_ORG.key(),
                f.admin(new DissolveOrg(new OrgId(new java.util.UUID(1, 1)))).reasonKey());
    }
}
