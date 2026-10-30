package charter.siege;

import charter.id.ChunkKey;
import charter.id.CoreId;
import charter.id.OrgId;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Wars, as the goals nations have declared (protected keeps, O1). The one goal is to capture an outpost. Times are
 * siege time. Saved with the state; an old save has none.
 */
public final class WarBook {

    /** A declared goal. Nothing happens to the outpost until the notice has run out ({@code opensAt}). */
    public static final class Goal {
        public long id;
        public OrgId attacker;
        public OrgId defender;
        public CoreId outpost;
        /** The attackers' outpost the war is fought from, touching {@link #outpost}; null in a war declared before this was kept. */
        public CoreId from;
        public long declaredAt;
        public long opensAt;
        public long expiresAt;
        /** True from the moment capture may begin. */
        public boolean opened;
        /** Siege milliseconds the attackers have held the outpost's chunk with no defender in it. */
        public long progress;
    }

    /** No goal between these two nations, either way, until the siege clock passes {@code untilSiege}. */
    public record Truce(OrgId a, OrgId b, long untilSiege) {
    }

    /** Only {@code winner} may plant an outpost in this chunk until the siege clock passes {@code untilSiege}. */
    public record Window(ChunkKey chunk, OrgId winner, long untilSiege) {
    }

    public final List<Goal> goals = new ArrayList<>();
    public final List<Truce> truces = new ArrayList<>();
    public final List<Window> windows = new ArrayList<>();
    public long nextId = 1;

    public Optional<Goal> against(CoreId outpost) {
        return goals.stream().filter(g -> g.outpost.equals(outpost)).findFirst();
    }

    /** True while a goal that names this outpost is open for capture: its claims are not protected. */
    public boolean opened(CoreId outpost) {
        return goals.stream().anyMatch(g -> g.opened && g.outpost.equals(outpost));
    }

    public long openBy(OrgId attacker) {
        return goals.stream().filter(g -> g.attacker.equals(attacker)).count();
    }

    public boolean truce(OrgId x, OrgId y, long siegeNow) {
        return truces.stream().anyMatch(t -> siegeNow < t.untilSiege()
                && ((t.a().equals(x) && t.b().equals(y)) || (t.a().equals(y) && t.b().equals(x))));
    }

    /** The nation holding this chunk's window, if one is open. */
    public Optional<OrgId> windowHolder(ChunkKey chunk, long siegeNow) {
        return windows.stream().filter(w -> w.chunk().equals(chunk) && siegeNow < w.untilSiege()).map(Window::winner).findFirst();
    }
}
