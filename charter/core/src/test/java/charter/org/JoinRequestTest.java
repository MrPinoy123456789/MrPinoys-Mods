package charter.org;

import charter.Fixture;
import charter.engine.Outcome;
import charter.gov.Preset;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.actions.AcceptInvite;
import charter.org.actions.Admit;
import charter.org.actions.Apply;
import charter.org.actions.InviteMember;
import charter.org.actions.Reject;

import static charter.Check.check;
import static charter.Check.eq;
import static charter.Fixture.player;

/** B3: asking to join, being admitted or turned away, and requests that lapse. */
public final class JoinRequestTest {

    static final PlayerId KRIS = player("Kris");
    static final PlayerId GIL = player("Gil");
    static final PlayerId DEE = player("Dee");

    private JoinRequestTest() {
    }

    public static void run() {
        admitMakesAMember();
        rejectLeavesNothing();
        aRequestLapses();
        onlyThoseWithThePowerAdmit();
        oneRequestAtATime();
        anInviteAcceptedClearsTheRequest();
    }

    private static OrgId world(Fixture f) {
        OrgId org = f.found("Ashfall", Preset.LORDSHIP, KRIS, GIL);
        f.seen(DEE);
        return org;
    }

    static void admitMakesAMember() {
        Fixture f = new Fixture();
        f.seen(KRIS, DEE);
        f.as(KRIS, new charter.org.actions.Found(KRIS, "Ashfall", Preset.LORDSHIP));
        OrgId org = f.state.orgNamed("Ashfall").orElseThrow().id;
        eq("1: forming at first", OrgStatus.FORMING, f.org(org).status());
        check("1: Dee asks", f.as(DEE, new Apply(DEE, org)).done());
        check("1: the request is open", f.state.joinRequest(org, DEE).isPresent());
        check("1: the Lord admits", f.as(KRIS, new Admit(org, DEE)).done());
        check("1: Dee is a member", f.state.isMember(DEE, org));
        check("1: the request is gone", f.state.joinRequest(org, DEE).isEmpty());
        eq("1: a second member activates it", OrgStatus.ACTIVE, f.org(org).status());
    }

    static void rejectLeavesNothing() {
        Fixture f = new Fixture();
        OrgId org = world(f);
        f.as(DEE, new Apply(DEE, org));
        check("2: the Lord rejects", f.as(KRIS, new Reject(org, DEE)).done());
        check("2: no member", !f.state.isMember(DEE, org));
        check("2: no request", f.state.joinRequest(org, DEE).isEmpty());
    }

    static void aRequestLapses() {
        Fixture f = new Fixture();
        OrgId org = world(f);
        f.as(DEE, new Apply(DEE, org));
        f.pass(charter.time.Durations.days(f.knobs.joinRequestDays) + 60_000, KRIS, GIL, DEE);
        check("3: the request lapsed", f.state.joinRequest(org, DEE).isEmpty());
        long lapsed = f.state.history.entries().stream().filter(e -> e.event() instanceof OrgEvents.RequestLapsed).count();
        eq("3: one RequestLapsed", 1L, lapsed);
    }

    static void onlyThoseWithThePowerAdmit() {
        Fixture f = new Fixture();
        OrgId org = world(f);
        f.as(DEE, new Apply(DEE, org));
        Outcome refused = f.as(GIL, new Admit(org, DEE));
        check("4: a plain member cannot admit", refused.refused());
        check("4: the request stands", f.state.joinRequest(org, DEE).isPresent());
    }

    static void oneRequestAtATime() {
        Fixture f = new Fixture();
        OrgId ashfall = world(f);
        PlayerId lee = player("Lee");
        f.found("Brightwater", Preset.LORDSHIP, lee, player("Max"));
        OrgId bright = f.state.orgNamed("Brightwater").orElseThrow().id;
        f.as(DEE, new Apply(DEE, ashfall));
        eq("5: a second ask anywhere is refused", OrgReasons.ALREADY_ASKING.key(), f.as(DEE, new Apply(DEE, bright)).reasonKey());
        eq("5: the same faction too", OrgReasons.ALREADY_ASKING.key(), f.as(DEE, new Apply(DEE, ashfall)).reasonKey());
        eq("5: a member cannot ask", OrgReasons.ALREADY_MEMBER.key(), f.as(GIL, new Apply(GIL, bright)).reasonKey());
    }

    static void anInviteAcceptedClearsTheRequest() {
        Fixture f = new Fixture();
        OrgId org = world(f);
        f.as(DEE, new Apply(DEE, org));
        check("6: invited as well", f.as(KRIS, new InviteMember(org, DEE)).done());
        check("6: accepted", f.as(DEE, new AcceptInvite(DEE, org)).done());
        check("6: the request went with it", f.state.joinRequest(org, DEE).isEmpty());
        eq("6: a later admit is refused", OrgReasons.NO_REQUEST.key(), f.as(KRIS, new Admit(org, DEE)).reasonKey());
    }
}
