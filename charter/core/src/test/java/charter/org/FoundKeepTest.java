package charter.org;

import charter.Fixture;
import charter.engine.Outcome;
import charter.gov.Preset;
import charter.id.ChunkKey;
import charter.id.PlayerId;
import charter.org.actions.Found;
import charter.org.actions.FoundKeep;
import charter.treasury.Currency;

import java.util.ArrayList;
import java.util.List;

import static charter.Check.check;
import static charter.Check.eq;
import static charter.Fixture.player;

/** B2: founding a faction and its keep in one act. */
public final class FoundKeepTest {

    static final PlayerId KRIS = player("Kris");
    static final PlayerId GIL = player("Gil");
    static final String DIM = "minecraft:overworld";
    static final ChunkKey HERE = new ChunkKey(DIM, 0, 0);

    private FoundKeepTest() {
    }

    public static void run() {
        foundingPaysForTheKeep();
        changeStaysInTheTreasury();
        everyRefusalChangesNothing();
        eventsComeInOrder();
    }

    static void foundingPaysForTheKeep() {
        Fixture f = new Fixture();
        f.seen(KRIS);
        Outcome done = f.as(KRIS, new FoundKeep(KRIS, "Ashfall", "{banner}", HERE, 64));
        check("1: founded", done.done());
        var org = f.state.orgNamed("Ashfall").orElseThrow();
        eq("1: forming", OrgStatus.FORMING, org.status());
        eq("1: the founder is the Lord", KRIS, f.holder(org.id, Preset.LORD));
        eq("1: nine claims", 9L, f.state.claimsOf(org.id).size() + 0L);
        eq("1: nothing left in the treasury", 0L, org.ledger.balance(Currency.DIAMOND));
        eq("1: the banner is kept", "{banner}", org.people.banner);
        eq("1: one keep", 1, f.state.coresOf(org.id).size());
    }

    static void changeStaysInTheTreasury() {
        Fixture f = new Fixture();
        f.seen(KRIS);
        f.as(KRIS, new FoundKeep(KRIS, "Ashfall", "", HERE, 70));
        eq("2: the change", 6L, f.state.orgNamed("Ashfall").orElseThrow().ledger.balance(Currency.DIAMOND));
    }

    private static void refused(String what, Fixture f, PlayerId who, FoundKeep action) {
        int orgs = f.state.allOrgs().size();
        int claims = f.state.claims().size();
        int cores = f.state.cores().size();
        boolean member = f.state.membership(who).isPresent();
        Outcome out = f.as(who, action);
        check("3: " + what + " is refused", out.refused());
        eq("3: " + what + " leaves the organizations", orgs, f.state.allOrgs().size());
        eq("3: " + what + " leaves the claims", claims, f.state.claims().size());
        eq("3: " + what + " leaves the cores", cores, f.state.cores().size());
        eq("3: " + what + " leaves the membership", member, f.state.membership(who).isPresent());
    }

    static void everyRefusalChangesNothing() {
        Fixture f = new Fixture();
        f.seen(KRIS, GIL);
        f.as(GIL, new FoundKeep(GIL, "Taken", "", new ChunkKey(DIM, 100, 100), 64));
        refused("a taken name", f, KRIS, new FoundKeep(KRIS, "Taken", "", new ChunkKey(DIM, 0, 0), 64));
        refused("a two letter name", f, KRIS, new FoundKeep(KRIS, "Ab", "", new ChunkKey(DIM, 0, 0), 64));
        refused("a keep too near another", f, KRIS, new FoundKeep(KRIS, "Near", "", new ChunkKey(DIM, 101, 100), 64));
        refused("a footprint already claimed", f, KRIS, new FoundKeep(KRIS, "Claimed", "", new ChunkKey(DIM, 100, 101), 64));
        refused("too few diamonds", f, KRIS, new FoundKeep(KRIS, "Poor", "", new ChunkKey(DIM, 0, 0), 63));
        refused("already a member", f, GIL, new FoundKeep(GIL, "Second", "", new ChunkKey(DIM, 0, 0), 64));

        Fixture g = new Fixture();
        g.seen(KRIS);
        g.as(KRIS, new Found(KRIS, "Early", Preset.LORDSHIP));
        g.as(KRIS, new charter.org.actions.Leave(KRIS));
        g.passDays(2, KRIS);
        refused("founding two days after the last", g, KRIS, new FoundKeep(KRIS, "Again", "", new ChunkKey(DIM, 0, 0), 64));
    }

    static void eventsComeInOrder() {
        Fixture f = new Fixture();
        f.seen(KRIS);
        int before = f.state.history.entries().size();
        f.as(KRIS, new FoundKeep(KRIS, "Ashfall", "", HERE, 64));
        List<String> names = new ArrayList<>();
        for (var e : f.state.history.entries().subList(before, f.state.history.entries().size())) {
            names.add(e.event().getClass().getSimpleName());
        }
        int founded = names.indexOf("Founded");
        int deposited = names.indexOf("Deposited");
        int placed = names.indexOf("NexusPlaced");
        check("4: Founded, the office, Deposited, NexusPlaced in that order " + names,
                founded == 0 && names.indexOf("OfficeCreated") > founded && deposited > names.indexOf("OfficeFilled") && placed > deposited);
    }
}
