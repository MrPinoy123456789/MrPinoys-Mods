package charter.grant;

import charter.Fixture;
import charter.engine.Reason;
import charter.gov.Office;
import charter.gov.Preset;
import charter.gov.actions.Appoint;
import charter.gov.actions.CreateOffice;
import charter.grant.actions.GrantPower;
import charter.grant.actions.RevokeGrant;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.actions.AcceptInvite;
import charter.org.actions.CreateSubOrg;
import charter.org.actions.InviteMember;
import charter.time.Durations;
import charter.treasury.Currency;
import charter.treasury.actions.Deposit;
import charter.treasury.actions.Withdraw;

import static charter.Check.check;
import static charter.Check.eq;
import static charter.Fixture.player;

/** Acceptance tests C, F (authority half) and G (DESIGN.md §22), and the grant chain rules. */
public final class AuthorityTest {

    static final PlayerId KRIS = player("Kris");
    static final PlayerId BOB = player("Bob");
    static final PlayerId HAL = player("Hal");
    static final PlayerId GIL = player("Gil");
    static final PlayerId CY = player("Cy");

    private AuthorityTest() {
    }

    public static void run() {
        Kingdom k = kingdom();
        testCDelegatedCommand(k);
        siblingGrantsCannotBeRevoked(k);
        testFRevokeEndsTheChain(k);
        testGCannotGiveWhatYouDoNotHold();
        grantsCannotBeWiderThanTheirParent();
        grantsExpire();
        treeRefusesCycles(k);
    }

    /** Ashfall (a Lordship under Kris) with a Guard sub-org led by Bob, and Hal as the Guard's Captain. */
    record Kingdom(Fixture f, OrgId ashfall, OrgId guard, Office captain, Grant toGuard) {
    }

    static Kingdom kingdom() {
        Fixture f = new Fixture();
        OrgId ashfall = f.found("Ashfall", Preset.LORDSHIP, KRIS, BOB, HAL, GIL);
        f.as(GIL, new Deposit(ashfall, Currency.DIAMOND, 100));
        f.as(KRIS, new CreateSubOrg(ashfall, "Royal Guard", Preset.LORDSHIP, BOB));
        OrgId guard = f.state.orgNamed("Royal Guard").orElseThrow().id;

        f.as(KRIS, new GrantPower(ashfall, Power.RAISE_CAMP, new Holder.OfOrg(guard), GrantScope.ANY, true, 0));
        f.as(KRIS, new GrantPower(ashfall, Power.SPEND, new Holder.OfOrg(guard), GrantScope.spendUpTo(0, 10), false, 0));
        Grant toGuard = f.state.grants().stream()
                .filter(g -> g.power == Power.RAISE_CAMP && g.recipient.equals(new Holder.OfOrg(guard)))
                .findFirst().orElseThrow();

        f.as(BOB, new InviteMember(guard, HAL));
        f.as(HAL, new AcceptInvite(HAL, guard));
        f.as(BOB, new CreateOffice(guard, "Captain"));
        Office captain = f.office(guard, "Captain");
        f.as(BOB, new Appoint(guard, captain.id, HAL));
        f.as(BOB, new GrantPower(ashfall, Power.RAISE_CAMP, new Holder.OfOffice(captain.id), GrantScope.ANY, false, 0));
        return new Kingdom(f, ashfall, guard, captain, toGuard);
    }

    static void testCDelegatedCommand(Kingdom k) {
        Fixture f = k.f();
        check("C: the guard sub-org exists under the kingdom", k.ashfall().equals(f.org(k.guard()).parent()));
        check("C: the guard's Lord raises a camp for the kingdom", f.as(BOB, new Fixture.Use(k.ashfall(), Power.RAISE_CAMP)).done());
        check("C: the guard's Captain can too, through the guard", f.as(HAL, new Fixture.Use(k.ashfall(), Power.RAISE_CAMP)).done());
        check("C: the guard withdraws within its limit", f.as(BOB, new Withdraw(k.ashfall(), Currency.DIAMOND, 5, BOB)).done());
        eq("C: the guard cannot withdraw over its limit", Reason.NO_AUTHORITY.key(),
                f.as(BOB, new Withdraw(k.ashfall(), Currency.DIAMOND, 50, BOB)).reasonKey());
        Office lord = f.office(k.ashfall(), Preset.LORD);
        eq("C: the guard cannot appoint the monarch", Reason.RESERVED.key(),
                f.as(BOB, new Appoint(k.ashfall(), lord.id, BOB)).reasonKey());
        eq("C: the treasury paid only the guard's small spend", 95L, f.org(k.ashfall()).ledger.balance(Currency.DIAMOND));
    }

    static void siblingGrantsCannotBeRevoked(Kingdom k) {
        Fixture f = k.f();
        f.as(KRIS, new GrantPower(k.ashfall(), Power.RAISE_CAMP, new Holder.OfPlayer(GIL), GrantScope.ANY, false, 0));
        Grant toGil = f.state.grants().stream()
                .filter(g -> g.recipient.equals(new Holder.OfPlayer(GIL))).findFirst().orElseThrow();
        eq("grant: the guard cannot revoke a grant the Lord gave someone else", RevokeGrant.NOT_YOURS.key(),
                f.as(BOB, new RevokeGrant(k.ashfall(), Power.RAISE_CAMP, toGil.id)).reasonKey());
        check("grant: that grant still works", f.as(GIL, new Fixture.Use(k.ashfall(), Power.RAISE_CAMP)).done());
    }

    static void testFRevokeEndsTheChain(Kingdom k) {
        Fixture f = k.f();
        check("F: the Lord revokes the guard's grant", f.as(KRIS, new RevokeGrant(k.ashfall(), Power.RAISE_CAMP, k.toGuard().id)).done());
        eq("F: the guard's Lord loses the power at once", Reason.NO_AUTHORITY.key(),
                f.as(BOB, new Fixture.Use(k.ashfall(), Power.RAISE_CAMP)).reasonKey());
        eq("F: so does everyone the guard passed it on to", Reason.NO_AUTHORITY.key(),
                f.as(HAL, new Fixture.Use(k.ashfall(), Power.RAISE_CAMP)).reasonKey());
        Grant toCaptain = f.state.grants().stream()
                .filter(g -> g.recipient.equals(new Holder.OfOffice(k.captain().id))).findFirst().orElseThrow();
        check("F: the derived grant is untouched but no longer valid", !toCaptain.revoked()
                && !new AuthorityResolver(f.state, f.clock.now()).valid(toCaptain));
        check("F: the guard keeps its own powers and can still act for itself",
                f.as(BOB, new Fixture.Use(k.guard(), Power.RAISE_CAMP)).done());
        check("F: the Lord keeps the power", f.as(KRIS, new Fixture.Use(k.ashfall(), Power.RAISE_CAMP)).done());
        long used = f.state.history.of(Fixture.Use.Used.class).stream().map(HistoryEvent::at).count();
        check("F: every successful use was recorded", used >= 5);
    }

    static void testGCannotGiveWhatYouDoNotHold() {
        Fixture f = new Fixture();
        OrgId ashfall = f.found("Ashfall", Preset.LORDSHIP, KRIS, GIL);
        OrgId vel = f.found("Vel", Preset.LORDSHIP, CY, BOB);
        f.as(BOB, new Deposit(vel, Currency.DIAMOND, 50));
        eq("G: a kingdom's Lord cannot grant power over another kingdom's treasury", Reason.NO_AUTHORITY.key(),
                f.as(KRIS, new GrantPower(vel, Power.SPEND, new Holder.OfOrg(ashfall), GrantScope.ANY, false, 0)).reasonKey());
        check("G: no grant was made", f.state.grants().stream().noneMatch(g -> g.subject.equals(vel) && g.recipient.equals(new Holder.OfOrg(ashfall))));
        eq("G: and Vel's treasury is untouched", 50L, f.org(vel).ledger.balance(Currency.DIAMOND));
    }

    static void grantsCannotBeWiderThanTheirParent() {
        Fixture f = new Fixture();
        OrgId ashfall = f.found("Ashfall", Preset.LORDSHIP, KRIS, GIL);
        eq("grant: the Lord cannot give a spend limit above his own", GrantPower.TOO_WIDE.key(),
                f.as(KRIS, new GrantPower(ashfall, Power.SPEND, new Holder.OfPlayer(GIL), GrantScope.spendUpTo(0, 1_000), false, 0)).reasonKey());
        eq("grant: a member without the power cannot pass it on", Reason.NEEDS_VOTE.key(),
                f.as(GIL, new GrantPower(ashfall, Power.ARREST, new Holder.OfPlayer(GIL), GrantScope.ANY, false, 0)).reasonKey());
        f.as(KRIS, new GrantPower(ashfall, Power.ARREST, new Holder.OfPlayer(GIL), GrantScope.ANY, false, 0));
        eq("grant: a grant not marked passable cannot be passed on", Reason.NEEDS_VOTE.key(),
                f.as(GIL, new GrantPower(ashfall, Power.ARREST, new Holder.OfPlayer(CY), GrantScope.ANY, false, 0)).reasonKey());
    }

    static void grantsExpire() {
        Fixture f = new Fixture();
        OrgId ashfall = f.found("Ashfall", Preset.LORDSHIP, KRIS, GIL);
        long expires = f.clock.now() + Durations.hours(1);
        f.as(KRIS, new GrantPower(ashfall, Power.ARREST, new Holder.OfPlayer(GIL), GrantScope.ANY, false, expires));
        check("grant: a timed grant works before it expires", f.as(GIL, new Fixture.Use(ashfall, Power.ARREST)).done());
        f.pass(Durations.hours(2), KRIS, GIL);
        eq("grant: and not after", Reason.NEEDS_VOTE.key(), f.as(GIL, new Fixture.Use(ashfall, Power.ARREST)).reasonKey());
    }

    static void treeRefusesCycles(Kingdom k) {
        Fixture f = k.f();
        check("tree: the guard is a descendant of the kingdom", f.state.descendants(k.ashfall()).contains(k.guard()));
        check("tree: a kingdom cannot be moved under its own guard", !f.state.setParent(k.ashfall(), k.guard()));
        check("tree: nothing can be its own parent", !f.state.setParent(k.guard(), k.guard()));
        eq("tree: the guard's tree is the kingdom's", k.ashfall(), f.state.root(k.guard()));
    }
}
