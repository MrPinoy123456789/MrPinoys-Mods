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
 * {@code npcRespawnDays} of real time. Sites that appear for the first time are not delayed.
 */
public final class NpcScheduler {

    private NpcScheduler() {
    }

    /** A site to build now, and whether it answers a respawn timer that has run out. */
    public record Next(NpcBook.Kind kind, boolean fromTimer) {
    }

    /** Sites whose core is gone are removed and their replacement timers start. Returns the sites that fell. */
    public static List<NpcBook.Site> collectFallen(NpcBook book, CharterState state, Knobs knobs, long now) {
        List<NpcBook.Site> fallen = new ArrayList<>();
        for (NpcBook.Site site : List.copyOf(book.sites)) {
            if (state.core(site.core()).isPresent()) continue;
            book.sites.remove(site);
            book.respawns.add(new NpcBook.Respawn(site.kind(), now + Durations.days(knobs.npcRespawnDays)));
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
