package charter.npc;

import charter.config.Knobs;
import charter.state.CharterState;
import charter.time.Durations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What the raiders do next (N5), as pure bookkeeping on the {@link NpcBook}. {@code npcMaxStrongholds} and
 * {@code npcMaxOutposts} bound what stands at once; a site that fell is replaced elsewhere after
 * {@code npcWaveMinutes} of real time. Sites that appear for the first time are not delayed.
 */
public final class NpcScheduler {

    private NpcScheduler() {
    }

    /** A site to build now, and whether it answers a respawn timer that has run out. */
    public record Next(NpcBook.Kind kind, boolean fromTimer) {
    }

    /** What one pass decides for a site that stands. */
    public record Step(int spawn, boolean fell, boolean expired) {
    }

    /** The site's live state, made on first use (an old save has none): full supply for its tier, a wave due now. */
    public static NpcBook.Live live(NpcBook book, NpcBook.Site site, Knobs knobs) {
        return book.live.computeIfAbsent(site.id(), id -> {
            NpcBook.Live live = new NpcBook.Live();
            boolean stronghold = site.kind() == NpcBook.Kind.STRONGHOLD;
            live.supply = tier(stronghold ? knobs.npcSupplyStronghold : knobs.npcSupplyOutpost, site.tier());
            live.pool = tier(stronghold ? knobs.npcPoolByTier : knobs.npcOutpostPool, site.tier());
            live.nextWaveAt = 0;
            live.lastFoughtAt = site.raisedAt();
            return live;
        });
    }

    private static int tier(int[] values, int tier) {
        return values[Math.max(0, Math.min(values.length - 1, tier))];
    }

    /**
     * One pass over a standing site (protected keeps, N). {@code alive} is how many of its raiders stand now and
     * {@code died} how many were lost since the last pass. A site falls when its supply is spent and none is left;
     * it expires when nobody has fought it for {@code npcExpireDays}; otherwise a wave tops its raiders up to the
     * pool, each costing one supply, once {@code npcWaveMinutes} have passed.
     */
    public static Step step(NpcBook.Live live, int alive, int died, Knobs knobs, long now) {
        if (died > 0) live.lastFoughtAt = now;
        if (live.supply <= 0 && alive == 0) return new Step(0, true, false);
        if (now - live.lastFoughtAt >= Durations.days(knobs.npcExpireDays)) return new Step(0, false, true);
        return new Step(wave(live, alive, knobs, now), false, false);
    }

    /** The raiders to spawn now (0 if the next wave is not due), and the supply they cost. */
    public static int wave(NpcBook.Live live, int alive, Knobs knobs, long now) {
        if (now < live.nextWaveAt) return 0;
        live.nextWaveAt = now + Durations.minutes(knobs.npcWaveMinutes);
        int n = Math.min(Math.max(0, live.pool - alive), live.supply);
        live.supply -= n;
        return n;
    }

    /** A site that fell or expired leaves the book; another of its kind is awaited after one wave interval. */
    public static void retire(NpcBook book, NpcBook.Site site, Knobs knobs, long now) {
        book.sites.remove(site);
        book.live.remove(site.id());
        book.respawns.add(new NpcBook.Respawn(site.kind(), now + Durations.minutes(knobs.npcWaveMinutes)));
    }

    /** Sites whose core is gone are removed and their replacement timers start. Returns the sites that fell. */
    public static List<NpcBook.Site> collectFallen(NpcBook book, CharterState state, Knobs knobs, long now) {
        List<NpcBook.Site> fallen = new ArrayList<>();
        for (NpcBook.Site site : List.copyOf(book.sites)) {
            if (state.core(site.core()).isPresent()) continue;
            book.sites.remove(site);
            book.respawns.add(new NpcBook.Respawn(site.kind(), now + Durations.minutes(knobs.npcWaveMinutes)));
            fallen.add(site);
        }
        return fallen;
    }

    /** The next site to build, a due replacement first, else one a cap still leaves room for. Strongholds come before outposts. */
    public static Optional<Next> next(NpcBook book, Knobs knobs, long now) {
        for (NpcBook.Kind kind : NpcBook.Kind.values()) {
            boolean due = book.respawns.stream().anyMatch(r -> r.kind() == kind && r.due() <= now);
            if (due && book.standing(kind) < cap(kind, knobs)) return Optional.of(new Next(kind, true));
        }
        for (NpcBook.Kind kind : NpcBook.Kind.values()) {
            if (book.standing(kind) + book.waiting(kind) < cap(kind, knobs)) return Optional.of(new Next(kind, false));
        }
        return Optional.empty();
    }

    /** Records that a site was built: the timer it answered, if any, is spent. */
    public static void built(NpcBook book, NpcBook.Site site, boolean fromTimer) {
        book.sites.add(site);
        if (fromTimer) {
            book.respawns.stream().filter(r -> r.kind() == site.kind()).findFirst().ifPresent(book.respawns::remove);
        }
    }

    public static int cap(NpcBook.Kind kind, Knobs knobs) {
        return kind == NpcBook.Kind.STRONGHOLD ? knobs.npcMaxStrongholds : knobs.npcMaxOutposts;
    }
}
