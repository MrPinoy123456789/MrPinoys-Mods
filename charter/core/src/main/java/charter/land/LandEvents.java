package charter.land;

import charter.history.HistoryEvent;
import charter.id.ChunkKey;
import charter.id.CoreId;
import charter.id.OrgId;

/** History events for cores and claims. */
public final class LandEvents {

    private LandEvents() {
    }

    public record NexusPlaced(long at, OrgId org, CoreId core, ChunkKey center) implements HistoryEvent {
    }

    public record CampPlanted(long at, OrgId org, CoreId core, ChunkKey center) implements HistoryEvent {
    }

    public record Claimed(long at, OrgId org, ChunkKey chunk, CoreId core) implements HistoryEvent {
    }

    public record Unclaimed(long at, OrgId org, ChunkKey chunk) implements HistoryEvent {
    }

    /** A keep fell or a camp was destroyed, and its core is gone. */
    public record CoreLost(long at, OrgId org, CoreId core, CoreKind kind, ChunkKey center) implements HistoryEvent {
    }

    /** Claims moved from one core to another after a core was lost or a link changed. */
    public record Reassigned(long at, OrgId org, CoreId from, CoreId to, int count) implements HistoryEvent {
    }

    /** Claims lost their core and went contested for the orphan grace. */
    public record Contested(long at, OrgId org, int count, long graceEndsAtSiege) implements HistoryEvent {
    }

    /** Contested claims were linked to a core again. */
    public record Rescued(long at, OrgId org, int count) implements HistoryEvent {
    }

    /** The grace ended and these claims are no longer claims. */
    public record Lapsed(long at, OrgId org, int count) implements HistoryEvent {
    }
}
