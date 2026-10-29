package charter.siege;

import charter.engine.Actor;
import charter.engine.Outcome;
import charter.id.PlayerId;
import charter.land.actions.PlantCamp;
import charter.org.actions.Apply;
import charter.org.actions.InviteMember;
import charter.time.Durations;

import static charter.Check.check;
import static charter.Check.eq;
import static charter.Fixture.player;

/** NPC strongholds, N1 and N3: a faction of the raiders cannot be joined, invited to, or make camps. */
public final class NpcTest {

    static final PlayerId NEWCOMER = player("Newcomer");

    private NpcTest() {
    }

    public static void run() {
        theRaidersCannotBeJoinedOrInvitedOrMakeCamps();
        theAbsentRulerTickLeavesThemAlone();
    }

    static void theRaidersCannotBeJoinedOrInvitedOrMakeCamps() {
        SiegeTest.World w = SiegeTest.world();
        w.f.state.org(w.ashfall).orElseThrow().npc = true;
        w.f.seen(NEWCOMER);
        eq("1: nobody can ask to join", charter.org.OrgReasons.NOT_JOINABLE.key(), w.f.as(NEWCOMER, new Apply(NEWCOMER, w.ashfall)).reasonKey());
        eq("1: nobody is invited", charter.org.OrgReasons.NOT_JOINABLE.key(), w.f.admin(new InviteMember(w.ashfall, NEWCOMER)).reasonKey());
        eq("1: they plant no camps", PlantCamp.NPC_NO_CAMPS.key(), w.f.admin(new PlantCamp(w.ashfall, SiegeTest.ch(30, 30))).reasonKey());
    }

    static void theAbsentRulerTickLeavesThemAlone() {
        SiegeTest.World w = SiegeTest.world();
        w.f.state.org(w.ashfall).orElseThrow().npc = true;
        w.f.pass(Durations.days(w.f.knobs.absentLordDays + 3));
        check("2: an NPC ruler is never absent", !w.f.office(w.ashfall, charter.gov.Preset.LORD).vacant());
        check("2: no election opened for the raiders", w.f.state.votes().stream().noneMatch(v -> v.open() && v.kind == charter.gov.Vote.Kind.ELECTION && v.org.equals(w.ashfall)));
        w.f.state.org(w.brine).orElseThrow();
    }
}
