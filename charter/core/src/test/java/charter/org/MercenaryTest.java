package charter.org;

import charter.Fixture;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.actions.Admit;
import charter.org.actions.Apply;
import charter.org.actions.ChooseMercenaryRace;
import charter.org.actions.CreateKingdom;
import charter.org.actions.InviteMember;
import charter.org.actions.Leave;

import static charter.Check.check;
import static charter.Check.eq;
import static charter.Fixture.player;

/**
 * Race: a mercenary takes one and keeps it; a member gets their kingdom's; a kingdom takes only its own race unless it
 * is mixed; leaving a kingdom does not change a race; admin kingdoms cannot be chosen.
 */
public final class MercenaryTest {

    static final PlayerId RIA = player("Ria");
    static final PlayerId LEO = player("Leo");

    private MercenaryTest() {
    }

    public static void run() {
        aMercenaryTakesARace();
        adminAndMissingKingdomsAreRefused();
        theRaceCannotBeChanged();
        membersCannotChoose();
        joiningGivesTheKingdomsRace();
        aKingdomTakesOnlyItsOwnRace();
        aMixedKingdomTakesAnyRace();
        leavingKeepsTheRace();
        operatorsPlaceAnyone();
    }

    private static OrgId kingdom(Fixture f, String name, int x) {
        f.knobs.keepRadiusChunks = 1;
        check("setup: the operators set " + name, f.admin(new CreateKingdom(name, new ChunkKey("minecraft:overworld", x, 0))).done());
        return f.state.orgNamed(name).orElseThrow().id;
    }

    /** Puts a player into a kingdom through asking and the Lord's welcome, the way the book does it for an operator. */
    private static void enter(Fixture f, PlayerId who, OrgId org) {
        f.seen(who);
        check("setup: " + who + " asks", f.as(who, new Apply(who, org)).done());
        check("setup: " + who + " is admitted", f.admin(new Admit(org, who)).done());
    }

    static void aMercenaryTakesARace() {
        Fixture f = new Fixture();
        OrgId azrak = kingdom(f, "Azrak", 0);
        f.seen(RIA);
        check("1: Ria takes the race", f.as(RIA, new ChooseMercenaryRace(RIA, azrak)).done());
        eq("1: it is Azrak", azrak, f.state.raceOf(RIA).orElseThrow());
        check("1: she belongs to nothing", f.state.membership(RIA).isEmpty());
        eq("1: nobody can choose for someone else", "not_you", f.as(LEO, new ChooseMercenaryRace(RIA, azrak)).reasonKey());
    }

    static void adminAndMissingKingdomsAreRefused() {
        Fixture f = new Fixture();
        OrgId azrak = kingdom(f, "Azrak", 0);
        OrgId elder = kingdom(f, "Hey Dol Hollow", 10);
        f.org(elder).adminKingdom = true;
        f.seen(RIA);
        eq("2: an admin race cannot be taken", "merc_not_a_race", f.as(RIA, new ChooseMercenaryRace(RIA, elder)).reasonKey());
        check("2: an ordinary race can", f.as(RIA, new ChooseMercenaryRace(RIA, azrak)).done());
        eq("2: a missing kingdom is refused", "merc_not_a_race",
                f.as(LEO, new ChooseMercenaryRace(LEO, new OrgId(new java.util.UUID(1, 2)))).reasonKey());
    }

    static void theRaceCannotBeChanged() {
        Fixture f = new Fixture();
        OrgId azrak = kingdom(f, "Azrak", 0);
        OrgId aurelia = kingdom(f, "Aurelia", 10);
        f.seen(RIA);
        f.as(RIA, new ChooseMercenaryRace(RIA, azrak));
        eq("3: changing is refused", "race_chosen", f.as(RIA, new ChooseMercenaryRace(RIA, aurelia)).reasonKey());
        f.passDays(365, RIA);
        eq("3: a year later it is still refused", "race_chosen", f.as(RIA, new ChooseMercenaryRace(RIA, aurelia)).reasonKey());
        eq("3: the same race again is refused too", "race_chosen", f.as(RIA, new ChooseMercenaryRace(RIA, azrak)).reasonKey());
        check("3: an operator may change it", f.admin(new ChooseMercenaryRace(RIA, aurelia)).done());
        eq("3: now Aurelia", aurelia, f.state.raceOf(RIA).orElseThrow());
    }

    static void membersCannotChoose() {
        Fixture f = new Fixture();
        OrgId azrak = kingdom(f, "Azrak", 0);
        OrgId aurelia = kingdom(f, "Aurelia", 10);
        enter(f, LEO, azrak);
        eq("4: a member is refused", "merc_in_org", f.as(LEO, new ChooseMercenaryRace(LEO, aurelia)).reasonKey());
    }

    static void joiningGivesTheKingdomsRace() {
        Fixture f = new Fixture();
        OrgId azrak = kingdom(f, "Azrak", 0);
        enter(f, LEO, azrak);
        eq("5: a member has their kingdom's race", azrak, f.state.raceOf(LEO).orElseThrow());
        eq("5: and it is recorded", azrak, f.state.knownPlayer(LEO).orElseThrow().race);
    }

    static void aKingdomTakesOnlyItsOwnRace() {
        Fixture f = new Fixture();
        OrgId azrak = kingdom(f, "Azrak", 0);
        OrgId aurelia = kingdom(f, "Aurelia", 10);
        f.seen(RIA, LEO);
        f.as(RIA, new ChooseMercenaryRace(RIA, azrak));
        eq("6: a mercenary of another race cannot ask", "race_mismatch", f.as(RIA, new Apply(RIA, aurelia)).reasonKey());
        check("6: she may ask her own kingdom", f.as(RIA, new Apply(RIA, azrak)).done());
        check("6: someone with no race may ask any", f.as(LEO, new Apply(LEO, aurelia)).done());
    }

    static void aMixedKingdomTakesAnyRace() {
        Fixture f = new Fixture();
        OrgId azrak = kingdom(f, "Azrak", 0);
        OrgId aurelia = kingdom(f, "Aurelia", 10);
        f.seen(RIA);
        f.as(RIA, new ChooseMercenaryRace(RIA, azrak));
        f.org(aurelia).mixedRace = true;
        check("7: a mixed kingdom takes her", f.as(RIA, new Apply(RIA, aurelia)).done());
        check("7: and admits her", f.admin(new Admit(aurelia, RIA)).done());
        eq("7: her race is still Azrak", azrak, f.state.raceOf(RIA).orElseThrow());
        f.org(aurelia).mixedRace = false;
        check("7: closing the kingdom does not remove her", f.state.isMember(RIA, aurelia));
    }

    static void leavingKeepsTheRace() {
        Fixture f = new Fixture();
        OrgId azrak = kingdom(f, "Azrak", 0);
        OrgId aurelia = kingdom(f, "Aurelia", 10);
        enter(f, LEO, azrak);
        check("8: Leo leaves", f.as(LEO, new Leave(LEO)).done());
        eq("8: still Azrak", azrak, f.state.raceOf(LEO).orElseThrow());
        eq("8: so another kingdom is closed to him", "race_mismatch", f.as(LEO, new Apply(LEO, aurelia)).reasonKey());
        eq("8: and he cannot choose again", "race_chosen", f.as(LEO, new ChooseMercenaryRace(LEO, aurelia)).reasonKey());
    }

    static void operatorsPlaceAnyone() {
        Fixture f = new Fixture();
        OrgId azrak = kingdom(f, "Azrak", 0);
        OrgId aurelia = kingdom(f, "Aurelia", 10);
        f.seen(RIA);
        f.as(RIA, new ChooseMercenaryRace(RIA, azrak));
        f.as(RIA, new Apply(RIA, azrak));
        f.as(RIA, new Leave(RIA));
        check("9: an operator can still admit across races when asked", f.admin(new InviteMember(aurelia, RIA)).done());
    }
}
