package charter.gov;

import charter.Fixture;
import charter.gov.actions.CreateOffice;
import charter.gov.actions.SetSeats;
import charter.id.OrgId;
import charter.id.PlayerId;

import static charter.Check.check;
import static charter.Check.eq;
import static charter.Fixture.player;

/** The server can fix the form of government and the filling of offices: players are refused, operators are not. */
public final class GovernanceLockTest {

    static final PlayerId KRIS = player("Kris");
    static final PlayerId GIL = player("Gil");

    private GovernanceLockTest() {
    }

    public static void run() {
        theFormCanBeLocked();
        officesCanBeLocked();
    }

    static void theFormCanBeLocked() {
        Fixture f = new Fixture();
        OrgId org = f.found("Ashfall", Preset.LORDSHIP, KRIS, GIL);
        Office lord = f.office(org, Preset.LORD);
        check("1: unlocked, the Lord can make a council", f.as(KRIS, new SetSeats(org, lord.id, 2, Office.Deciding.ALONE)).done());
        f.as(KRIS, new SetSeats(org, lord.id, 1, Office.Deciding.ALONE));
        f.knobs.leadersChangeGovernance = false;
        eq("1: locked, the Lord is refused", "governance_locked", f.as(KRIS, new SetSeats(org, lord.id, 2, Office.Deciding.ALONE)).reasonKey());
        eq("1: nor can a Commune be made", "governance_locked", f.as(KRIS, new SetSeats(org, lord.id, 0, Office.Deciding.ALONE)).reasonKey());
        check("1: an operator still can", f.admin(new SetSeats(org, lord.id, 2, Office.Deciding.ALONE)).done());
    }

    static void officesCanBeLocked() {
        Fixture f = new Fixture();
        OrgId org = f.found("Ashfall", Preset.LORDSHIP, KRIS, GIL);
        f.knobs.leadersAppointOffices = false;
        eq("2: locked, a new office is refused", "appointing_locked", f.as(KRIS, new CreateOffice(org, "Steward")).reasonKey());
        check("2: an operator can make one", f.admin(new CreateOffice(org, "Steward")).done());
        f.knobs.leadersAppointOffices = true;
        check("2: unlocked, the Lord can make one", f.as(KRIS, new CreateOffice(org, "Treasurer")).done());
    }
}
