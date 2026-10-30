package charter.siege;

import charter.Fixture;
import charter.id.ChunkKey;
import charter.npc.NpcBook;
import charter.npc.NpcPlacement;
import charter.npc.NpcScheduler;
import charter.npc.SpawnNpc;
import charter.time.Durations;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import static charter.Check.check;
import static charter.Check.eq;

/** NPC strongholds, N5: where the raiders build, how many stand, and when a fallen site is replaced. */
public final class NpcPlacementTest {

    static final String DIM = "minecraft:overworld";
    static final ChunkKey SPAWN = new ChunkKey(DIM, 0, 0);

    private NpcPlacementTest() {
    }

    public static void run() {
        aSiteKeepsItsDistances();
        theSameSeedChoosesTheSameSite();
        noRoomMeansNoSite();
        tiersFollowTheDistance();
        theCapsAndTheRespawnDelay();
        aSpawnedFactionHoldsItsSiteAndItsPrize();
    }

    static void aSiteKeepsItsDistances() {
        Fixture f = new Fixture();
        f.knobs.keepGapChunks = 4;
        f.knobs.npcPlayerClearChunks = 8;
        // A rival keep and a player nearby; every chosen site must keep clear of both.
        check("setup: a rival keep", f.admin(new SpawnNpc("Ironhide Raiders", "", NpcBook.Kind.STRONGHOLD, new ChunkKey(DIM, 30, 0), 16, 128)).done());
        ChunkKey player = new ChunkKey(DIM, 0, 20);
        for (long seed = 1; seed <= 40; seed++) {
            Optional<ChunkKey> site = NpcPlacement.choose(new Random(seed), DIM, SPAWN, 10, 60, f.state, f.knobs, List.of(player), c -> true);
            if (site.isEmpty()) continue;
            ChunkKey c = site.get();
            check("1: seed " + seed + " keeps the keep gap", Math.max(Math.abs(c.x() - 30), Math.abs(c.z())) >= f.knobs.keepGapChunks + 3);
            check("1: seed " + seed + " clears the player", Math.max(Math.abs(c.x() - player.x()), Math.abs(c.z() - player.z())) >= f.knobs.npcPlayerClearChunks);
        }
        check("1: a site is found at all", NpcPlacement.choose(new Random(7), DIM, SPAWN, 10, 60, f.state, f.knobs, List.of(), c -> true).isPresent());
    }

    static void theSameSeedChoosesTheSameSite() {
        Fixture f = new Fixture();
        var a = NpcPlacement.choose(new Random(99), DIM, SPAWN, 10, 60, f.state, f.knobs, List.of(), c -> true);
        var b = NpcPlacement.choose(new Random(99), DIM, SPAWN, 10, 60, f.state, f.knobs, List.of(), c -> true);
        eq("2: a fixed seed is repeatable", a, b);
    }

    static void noRoomMeansNoSite() {
        Fixture f = new Fixture();
        check("3: terrain that never suits gives no site", NpcPlacement.choose(new Random(5), DIM, SPAWN, 10, 60, f.state, f.knobs, List.of(), c -> false).isEmpty());
        ChunkKey nowhere = new ChunkKey(DIM, 0, 0);
        check("3: a player standing everywhere near the ring leaves none",
                NpcPlacement.choose(new Random(5), DIM, SPAWN, 1, 3, f.state, f.knobs, List.of(nowhere), c -> true).isEmpty());
    }

    static void tiersFollowTheDistance() {
        Fixture f = new Fixture();
        eq("4: near", 0, NpcPlacement.tier(SPAWN, new ChunkKey(DIM, 10, 5), f.knobs));
        eq("4: middle", 1, NpcPlacement.tier(SPAWN, new ChunkKey(DIM, 60, 5), f.knobs));
        eq("4: far", 2, NpcPlacement.tier(SPAWN, new ChunkKey(DIM, 5, -300), f.knobs));
    }

    static void theCapsAndTheRespawnDelay() {
        Fixture f = new Fixture();
        f.knobs.npcMaxStrongholds = 2;
        f.knobs.npcMaxOutposts = 1;
        NpcBook book = f.state.npc;
        long now = f.clock.now();
        var first = NpcScheduler.next(book, f.knobs, now).orElseThrow();
        eq("5: strongholds come first", NpcBook.Kind.STRONGHOLD, first.kind());
        check("5: and are not delayed", !first.fromTimer());
        for (int i = 0; i < 2; i++) {
            NpcScheduler.built(book, new NpcBook.Site(book.nextId++, NpcBook.Kind.STRONGHOLD, null, null, new ChunkKey(DIM, i * 50, 0), 0, now), false);
        }
        eq("5: outposts fill their own cap", NpcBook.Kind.OUTPOST, NpcScheduler.next(book, f.knobs, now).orElseThrow().kind());
        NpcScheduler.built(book, new NpcBook.Site(book.nextId++, NpcBook.Kind.OUTPOST, null, null, new ChunkKey(DIM, 0, 50), 0, now), false);
        check("5: at the caps nothing more is built", NpcScheduler.next(book, f.knobs, now).isEmpty());

        // A stronghold falls: its core is not in the state, so it is collected and a timer starts.
        var fallen = NpcScheduler.collectFallen(book, f.state, f.knobs, now);
        eq("5: all three sites read as fallen (they have no core)", 3, fallen.size());
        eq("5: three timers wait", 3, book.respawns.size());
        check("5: nothing before the delay", NpcScheduler.next(book, f.knobs, now + Durations.minutes(f.knobs.npcWaveMinutes) - 1).isEmpty());
        var due = NpcScheduler.next(book, f.knobs, now + Durations.minutes(f.knobs.npcWaveMinutes)).orElseThrow();
        check("5: after the delay a replacement is due", due.fromTimer());
    }

    static void aSpawnedFactionHoldsItsSiteAndItsPrize() {
        Fixture f = new Fixture();
        var out = f.admin(new SpawnNpc("The Pillage Village People", "banner", NpcBook.Kind.STRONGHOLD, new ChunkKey(DIM, 25, 25), 16, 128));
        check("6: the raiders raise a stronghold " + out, out.done());
        var org = f.state.orgNamed("The Pillage Village People").orElseThrow();
        check("6: it is a faction of the raiders", org.npc && org.status() == charter.org.OrgStatus.ACTIVE);
        eq("6: the prize in diamonds", 16L, org.ledger.balance(charter.treasury.Currency.DIAMOND));
        eq("6: and in cobblestone", 128L, org.ledger.balance(charter.treasury.Currency.COBBLESTONE));
        check("6: a keep of nine chunks", f.state.coresOf(org.id).size() == 1 && f.state.claimsOf(org.id).size() == 9);
        check("6: its Lord is the raiders' own", f.holder(org.id, charter.gov.Preset.LORD).equals(NpcBook.LORD));

        // Players may hold bigger plots; the raiders' strongholds stay as they were.
        f.knobs.keepRadiusChunks = 4;
        var big = f.admin(new SpawnNpc("The Big Plot Bandits", "", NpcBook.Kind.STRONGHOLD, new ChunkKey(DIM, 60, 60), 1, 1));
        check("6: a stronghold is raised with the big plot setting " + big, big.done());
        var bandits = f.state.orgNamed("The Big Plot Bandits").orElseThrow();
        eq("6: and is still nine chunks", 9, f.state.claimsOf(bandits.id).size());
        check("6: a player keep can stand just past the stronghold and the gap counts both sizes",
                new charter.land.LandGraph(f.state, f.knobs).keepProblemForNew(new ChunkKey(DIM, 60 + 1 + 4 + 1 + f.knobs.keepGapChunks, 60)).isEmpty()
                        && new charter.land.LandGraph(f.state, f.knobs).keepProblemForNew(new ChunkKey(DIM, 60 + 1 + 4 + f.knobs.keepGapChunks, 60)).isPresent());
        eq("6: a stronghold site still needs only the small footprint", true,
                new charter.land.LandGraph(f.state, f.knobs).keepProblemForNew(new ChunkKey(DIM, 60 + 1 + 1 + 1 + f.knobs.keepGapChunks, 60), true).isEmpty());
        f.knobs.keepRadiusChunks = 1;
        eq("6: a player cannot spawn one", charter.land.LandReasons.OPERATOR_ONLY.key(),
                f.as(Fixture.player("Kris"), new SpawnNpc("Evoker Fan Club", "", NpcBook.Kind.STRONGHOLD, new ChunkKey(DIM, 90, 90), 1, 1)).reasonKey());
        var outpost = f.admin(new SpawnNpc("Evoker Fan Club", "", NpcBook.Kind.OUTPOST, new ChunkKey(DIM, 90, 90), 0, 0));
        check("6: an outpost is a raised camp", outpost.done() && f.state.cores().stream().anyMatch(c -> !c.isKeep()
                && c.phase() == charter.land.CampPhase.ACTIVE));
    }
}
