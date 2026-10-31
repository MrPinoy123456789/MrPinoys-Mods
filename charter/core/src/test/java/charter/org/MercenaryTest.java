package charter.org;

import charter.Fixture;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.actions.Admit;
import charter.org.actions.Apply;
import charter.org.actions.ChooseMercenaryRace;
import charter.org.actions.CreateKingdom;
import charter.org.actions.Leave;
import charter.org.actions.SetRace;

import static charter.Check.check;
import static charter.Check.eq;
import static charter.Fixture.player;

/**
 * Race: a mercenary takes one; joining a kingdom that is not mixed makes you of its race; a mixed kingdom lets you keep
 * yours; leaving does not change a race; admin kingdoms cannot be chosen.
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
        joiningAnotherKingdomChangesTheRace();
        aMixedKingdomTakesAnyRace();
        leavingKeepsTheRace();
        operatorsSetTheRace();
        operatorsCanSetAndRemoveARace();
        aFormerMemberCanBeAMercenaryOfTheirRace();
        aRaceHasANameOfItsOwn();
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
        check("3: the race she has is always allowed", f.as(RIA, new ChooseMercenaryRace(RIA, azrak)).done());
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

    static void joiningAnotherKingdomChangesTheRace() {
        Fixture f = new Fixture();
        OrgId azrak = kingdom(f, "Azrak", 0);
        OrgId aurelia = kingdom(f, "Aurelia", 10);
        f.seen(RIA);
        f.as(RIA, new ChooseMercenaryRace(RIA, azrak));
        enter(f, RIA, aurelia);
        eq("6: joining another kingdom makes her of its race", aurelia, f.state.raceOf(RIA).orElseThrow());
        eq("6: and it is recorded", aurelia, f.state.knownPlayer(RIA).orElseThrow().race);
        eq("6: she cannot choose again", "merc_in_org", f.as(RIA, new ChooseMercenaryRace(RIA, azrak)).reasonKey());
    }

    static void aMixedKingdomTakesAnyRace() {
        Fixture f = new Fixture();
        OrgId azrak = kingdom(f, "Azrak", 0);
        OrgId aurelia = kingdom(f, "Aurelia", 10);
        f.seen(RIA);
        f.as(RIA, new ChooseMercenaryRace(RIA, azrak));
        f.org(aurelia).mixedRace = true;
        enter(f, RIA, aurelia);
        check("7: she is a member", f.state.isMember(RIA, aurelia));
        eq("7: her race is still Azrak", azrak, f.state.raceOf(RIA).orElseThrow());
        f.org(aurelia).mixedRace = false;
        check("7: closing the kingdom does not remove her", f.state.isMember(RIA, aurelia));
        eq("7: nor change her race", azrak, f.state.raceOf(RIA).orElseThrow());
    }

    static void leavingKeepsTheRace() {
        Fixture f = new Fixture();
        OrgId azrak = kingdom(f, "Azrak", 0);
        OrgId aurelia = kingdom(f, "Aurelia", 10);
        enter(f, LEO, azrak);
        check("8: Leo leaves", f.as(LEO, new Leave(LEO)).done());
        eq("8: still Azrak", azrak, f.state.raceOf(LEO).orElseThrow());
        eq("8: and he cannot choose again", "race_chosen", f.as(LEO, new ChooseMercenaryRace(LEO, aurelia)).reasonKey());
        enter(f, LEO, aurelia);
        eq("8: joining Aurelia makes him of Aurelia", aurelia, f.state.raceOf(LEO).orElseThrow());
    }

    static void operatorsSetTheRace() {
        Fixture f = new Fixture();
        OrgId azrak = kingdom(f, "Azrak", 0);
        OrgId aurelia = kingdom(f, "Aurelia", 10);
        f.seen(RIA);
        f.as(RIA, new ChooseMercenaryRace(RIA, azrak));
        check("9: an operator may change a race", f.admin(new ChooseMercenaryRace(RIA, aurelia)).done());
        eq("9: now Aurelia", aurelia, f.state.raceOf(RIA).orElseThrow());
    }

    static void operatorsCanSetAndRemoveARace() {
        Fixture f = new Fixture();
        OrgId azrak = kingdom(f, "Azrak", 0);
        OrgId aurelia = kingdom(f, "Aurelia", 10);
        OrgId elder = kingdom(f, "Hey Dol Hollow", 20);
        f.org(elder).adminKingdom = true;
        enter(f, LEO, azrak);
        eq("10: a player cannot set a race", "not_you", f.as(LEO, new SetRace(LEO, aurelia)).reasonKey());
        check("10: an operator sets a member's race", f.admin(new SetRace(LEO, aurelia)).done());
        eq("10: now Aurelia", aurelia, f.state.raceOf(LEO).orElseThrow());
        check("10: he is still in Azrak", f.state.isMember(LEO, azrak));
        check("10: an operator may give an admin race", f.admin(new SetRace(LEO, elder)).done());
        eq("10: a missing kingdom is refused", "merc_not_a_race",
                f.admin(new SetRace(LEO, new OrgId(new java.util.UUID(3, 4)))).reasonKey());
        check("10: an operator removes the race", f.admin(new SetRace(LEO, null)).done());
        check("10: he has none, though he belongs to Azrak", f.state.raceOf(LEO).isEmpty());
        check("10: leaving does not give it back", f.as(LEO, new Leave(LEO)).done() && f.state.raceOf(LEO).isEmpty());
        check("10: now he may choose one", f.as(LEO, new ChooseMercenaryRace(LEO, azrak)).done());
        eq("10: Azrak", azrak, f.state.raceOf(LEO).orElseThrow());
        f.admin(new SetRace(LEO, null));
        enter(f, LEO, aurelia);
        eq("10: joining a kingdom gives a raceless player its race", aurelia, f.state.raceOf(LEO).orElseThrow());
    }

    static void aFormerMemberCanBeAMercenaryOfTheirRace() {
        Fixture f = new Fixture();
        OrgId azrak = kingdom(f, "Azrak", 0);
        OrgId aurelia = kingdom(f, "Aurelia", 10);
        enter(f, LEO, azrak);
        f.as(LEO, new Leave(LEO));
        check("11: he may be a mercenary of the race he has", f.as(LEO, new ChooseMercenaryRace(LEO, azrak)).done());
        eq("11: but not another", "race_chosen", f.as(LEO, new ChooseMercenaryRace(LEO, aurelia)).reasonKey());
        f.org(azrak).adminKingdom = true;
        check("11: even when the race is an admin race", f.as(LEO, new ChooseMercenaryRace(LEO, azrak)).done());
    }

    static void aRaceHasANameOfItsOwn() {
        Fixture f = new Fixture();
        OrgId azrak = kingdom(f, "Azrak", 0);
        eq("12: without a race name it is the kingdom's name", "Azrak", f.state.raceLabel(azrak));
        f.org(azrak).raceName = "Dwarf";
        eq("12: with one it is the race's name", "Dwarf", f.state.raceLabel(azrak));
    }
}
