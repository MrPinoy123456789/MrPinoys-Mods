package charter.org;

import charter.Fixture;
import charter.gov.Preset;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.actions.ChooseMercenaryRace;
import charter.org.actions.CreateKingdom;
import charter.org.actions.Found;
import charter.org.actions.Leave;

import static charter.Check.check;
import static charter.Check.eq;
import static charter.Fixture.player;

/** Mercenaries: a race with no kingdom, never an admin race, changed only after a wait, ended by joining. */
public final class MercenaryTest {

    static final PlayerId RIA = player("Ria");
    static final PlayerId LEO = player("Leo");

    private MercenaryTest() {
    }

    public static void run() {
        aMercenaryTakesARace();
        adminAndNpcKingdomsAreRefused();
        aChangeWaitsOutTheCooldown();
        membersCannotBeMercenaries();
        joiningAnOrganizationEndsIt();
        noChronicleLineIsMade();
    }

    private static OrgId kingdom(Fixture f, String name, int x) {
        f.knobs.keepRadiusChunks = 1;
        check("setup: the operators set " + name, f.admin(new CreateKingdom(name, new ChunkKey("minecraft:overworld", x, 0))).done());
        return f.state.orgNamed(name).orElseThrow().id;
    }

    static void aMercenaryTakesARace() {
        Fixture f = new Fixture();
        OrgId azrak = kingdom(f, "Azrak", 0);
        f.seen(RIA);
        check("1: Ria takes the race", f.as(RIA, new ChooseMercenaryRace(RIA, azrak)).done());
        eq("1: it is Azrak", azrak, f.state.knownPlayer(RIA).orElseThrow().mercKingdom);
        check("1: she belongs to nothing", f.state.membership(RIA).isEmpty());
        eq("1: a second pick of the same race is refused", "merc_same", f.as(RIA, new ChooseMercenaryRace(RIA, azrak)).reasonKey());
        eq("1: nobody can choose for someone else", "not_you", f.as(LEO, new ChooseMercenaryRace(RIA, azrak)).reasonKey());
    }

    static void adminAndNpcKingdomsAreRefused() {
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

    static void aChangeWaitsOutTheCooldown() {
        Fixture f = new Fixture();
        OrgId azrak = kingdom(f, "Azrak", 0);
        OrgId aurelia = kingdom(f, "Aurelia", 10);
        f.seen(RIA);
        f.as(RIA, new ChooseMercenaryRace(RIA, azrak));
        eq("3: changing at once is refused", "merc_cooldown", f.as(RIA, new ChooseMercenaryRace(RIA, aurelia)).reasonKey());
        f.passDays(f.knobs.mercChangeDays - 1, RIA);
        eq("3: one day short is refused", "merc_cooldown", f.as(RIA, new ChooseMercenaryRace(RIA, aurelia)).reasonKey());
        check("3: an operator may change it at once", f.admin(new ChooseMercenaryRace(RIA, aurelia)).done());
        eq("3: now Aurelia", aurelia, f.state.knownPlayer(RIA).orElseThrow().mercKingdom);
        f.passDays(f.knobs.mercChangeDays + 1, RIA);
        check("3: after the wait she may change", f.as(RIA, new ChooseMercenaryRace(RIA, azrak)).done());
        eq("3: back to Azrak", azrak, f.state.knownPlayer(RIA).orElseThrow().mercKingdom);
    }

    static void membersCannotBeMercenaries() {
        Fixture f = new Fixture();
        OrgId azrak = kingdom(f, "Azrak", 0);
        f.knobs.playerFounding = true;
        f.seen(LEO);
        f.as(LEO, new Found(LEO, "Ashfall", Preset.LORDSHIP));
        eq("4: a member is refused", "merc_in_org", f.as(LEO, new ChooseMercenaryRace(LEO, azrak)).reasonKey());
    }

    static void joiningAnOrganizationEndsIt() {
        Fixture f = new Fixture();
        OrgId azrak = kingdom(f, "Azrak", 0);
        f.seen(RIA);
        f.as(RIA, new ChooseMercenaryRace(RIA, azrak));
        f.state.join(RIA, azrak, f.clock.now());
        check("5: joining clears the mercenary record", f.state.knownPlayer(RIA).orElseThrow().mercKingdom == null);
        check("5: leaving does not bring it back", f.as(RIA, new Leave(RIA)).done()
                && f.state.knownPlayer(RIA).orElseThrow().mercKingdom == null);
    }

    static void noChronicleLineIsMade() {
        Fixture f = new Fixture();
        OrgId azrak = kingdom(f, "Azrak", 0);
        f.seen(RIA);
        f.as(RIA, new ChooseMercenaryRace(RIA, azrak));
        check("6: no Chronicle line is made for it", f.state.history.entries().stream()
                .noneMatch(e -> e.event().getClass().getSimpleName().contains("Mercenary")));
    }
}
