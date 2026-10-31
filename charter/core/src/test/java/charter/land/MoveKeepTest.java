package charter.land;

import charter.Fixture;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.actions.MoveKeep;
import charter.org.actions.AppointLeader;
import charter.org.actions.CreateKingdom;

import static charter.Check.check;
import static charter.Check.eq;
import static charter.Fixture.player;

/** An operator moves a keep: the land moves with it, everything else about the kingdom stays. */
public final class MoveKeepTest {

    private MoveKeepTest() {
    }

    public static void run() {
        theKeepMovesAndNothingElseDoes();
        refusals();
    }

    private static ChunkKey ch(int x, int z) {
        return new ChunkKey("minecraft:overworld", x, z);
    }

    private static OrgId kingdom(Fixture f, String name, int x) {
        f.knobs.keepRadiusChunks = 4;
        check("setup: " + name, f.admin(new CreateKingdom(name, ch(x, 0))).done());
        return f.state.orgNamed(name).orElseThrow().id;
    }

    static void theKeepMovesAndNothingElseDoes() {
        Fixture f = new Fixture();
        OrgId org = kingdom(f, "Everdelle", 0);
        PlayerId kri = player("Kri");
        f.seen(kri);
        f.admin(new AppointLeader(org, kri));
        var core = f.state.coresOf(org).stream().filter(Core::isKeep).findFirst().orElseThrow();
        var coreId = core.id;
        check("1: the keep starts at 0,0", core.center.equals(ch(0, 0)));
        eq("1: 81 chunks to begin with", 81, f.state.claimsOf(org).size());
        check("1: an operator moves it", f.admin(new MoveKeep(org, ch(-60, 30))).done());
        var moved = f.state.core(coreId).orElseThrow();
        check("1: the same keep, new place", moved.center.equals(ch(-60, 30)) && moved.isKeep());
        eq("1: still 81 chunks", 81, f.state.claimsOf(org).size());
        check("1: the new centre is a keep core claim",
                f.state.claimAt(ch(-60, 30)).map(c -> c.kind() == ClaimKind.KEEP_CORE && c.core().equals(coreId)).orElse(false));
        check("1: the old ground is free", f.state.claimAt(ch(0, 0)).isEmpty() && f.state.claimAt(ch(4, 4)).isEmpty());
        check("1: the corners of the new plot are held", f.state.claimAt(ch(-64, 26)).isPresent() && f.state.claimAt(ch(-56, 34)).isPresent());
        check("1: the leader is still the leader", f.state.isMember(kri, org));
        check("1: one Chronicle line for the move", f.state.history.entries().stream().anyMatch(e -> e.event() instanceof LandEvents.KeepMoved));
        check("1: and none of a lost keep", f.state.history.entries().stream().noneMatch(e -> e.event() instanceof LandEvents.CoreLost));
    }

    static void refusals() {
        Fixture f = new Fixture();
        OrgId a = kingdom(f, "Azrak", 0);
        kingdom(f, "Aurelia", 40);
        PlayerId nobody = player("Nobody");
        f.seen(nobody);
        eq("2: a player cannot move a keep", "operator_only", f.as(nobody, new MoveKeep(a, ch(-60, 30))).reasonKey());
        eq("2: onto another kingdom's land is refused", "keep_needs_room", f.admin(new MoveKeep(a, ch(40, 0))).reasonKey());
        eq("2: too close to another keep is refused", "keep_gap", f.admin(new MoveKeep(a, ch(31, 0))).reasonKey());
        check("2: nothing moved", f.state.coresOf(a).stream().anyMatch(c -> c.center.equals(ch(0, 0))));
        check("2: a short step within its own old land is fine", f.admin(new MoveKeep(a, ch(-3, 0))).done());
    }
}
