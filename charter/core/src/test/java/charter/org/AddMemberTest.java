package charter.org;

import charter.Fixture;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.actions.AddMember;
import charter.org.actions.CreateKingdom;

import static charter.Check.check;
import static charter.Check.eq;
import static charter.Fixture.player;

/** An operator puts a player into a kingdom without a request: no leader needed, race follows, the rules still hold. */
public final class AddMemberTest {

    private AddMemberTest() {
    }

    public static void run() {
        anOperatorAddsAMember();
        refusals();
    }

    private static OrgId kingdom(Fixture f, String name, int x) {
        f.knobs.keepRadiusChunks = 4;
        check("setup: " + name, f.admin(new CreateKingdom(name, new ChunkKey("minecraft:overworld", x, 0))).done());
        return f.state.orgNamed(name).orElseThrow().id;
    }

    static void anOperatorAddsAMember() {
        Fixture f = new Fixture();
        OrgId org = kingdom(f, "Faeloria", 0);
        PlayerId joker = player("Joker");
        f.seen(joker);
        check("1: nobody leads the kingdom", f.state.members(org).isEmpty());
        check("1: an operator adds them", f.admin(new AddMember(org, joker)).done());
        check("1: they are a member", f.state.isMember(joker, org));
        eq("1: with the kingdom's race", java.util.Optional.of(org), f.state.raceOf(joker));
        check("1: a Chronicle line for the join", f.state.history.entries().stream().anyMatch(e -> e.event() instanceof OrgEvents.Joined));
    }

    static void refusals() {
        Fixture f = new Fixture();
        OrgId a = kingdom(f, "Azrak", 0);
        OrgId b = kingdom(f, "Aurelia", 40);
        PlayerId kri = player("Kri");
        PlayerId other = player("Other");
        f.seen(kri);
        f.seen(other);
        eq("2: a player cannot add a member", "not_you", f.as(other, new AddMember(a, kri)).reasonKey());
        check("2: nothing changed", !f.state.isMember(kri, a));
        check("2: added once", f.admin(new AddMember(a, kri)).done());
        eq("2: twice is refused", "already_member", f.admin(new AddMember(a, kri)).reasonKey());
        eq("2: another kingdom's member is refused", "member_elsewhere", f.admin(new AddMember(b, kri)).reasonKey());
        f.state.org(b).orElseThrow().adminKingdom = true;
        check("2: a closed admin kingdom still takes an operator's add", f.admin(new AddMember(b, other)).done());
    }
}
