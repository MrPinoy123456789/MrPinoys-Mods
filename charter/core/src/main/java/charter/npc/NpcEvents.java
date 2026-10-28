package charter.npc;

import charter.history.HistoryEvent;
import charter.id.ChunkKey;
import charter.id.OrgId;

/** History events of the raiders' sites. A fall and a razing are told by the usual siege events. */
public final class NpcEvents {

    private NpcEvents() {
    }

    /** A stronghold or an outpost was raised. */
    public record Raised(long at, OrgId org, NpcBook.Kind kind, ChunkKey center) implements HistoryEvent {
    }

    /** A site that fell has been replaced elsewhere. */
    public record Replaced(long at, OrgId org, NpcBook.Kind kind, ChunkKey center) implements HistoryEvent {
    }
}
