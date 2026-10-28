package charter.npc;

import charter.id.ChunkKey;
import charter.id.CoreId;
import charter.id.OrgId;

import java.util.ArrayList;
import java.util.List;

/**
 * The raiders' sites that stand now and the replacements still waiting (NPC strongholds, N5). Saved with the
 * state, so a restart keeps both the sites and the respawn timers.
 */
public final class NpcBook {

    /** The Lord of every faction of the raiders: nobody a player can meet. */
    public static final charter.id.PlayerId LORD = new charter.id.PlayerId(new java.util.UUID(0x4E50434C4F5244L, 1));

    public enum Kind { STRONGHOLD, OUTPOST }

    /** A site: a stronghold (a keep) or an outpost (a camp), held by its own NPC faction. */
    public record Site(long id, Kind kind, OrgId org, CoreId core, ChunkKey chunk, int tier, long raisedAt) {
    }

    /** A site fell; another of its kind appears elsewhere at {@code due} (wall time). */
    public record Respawn(Kind kind, long due) {
    }

    public final List<Site> sites = new ArrayList<>();
    public final List<Respawn> respawns = new ArrayList<>();
    public long nextId = 1;

    public int standing(Kind kind) {
        return (int) sites.stream().filter(s -> s.kind() == kind).count();
    }

    public int waiting(Kind kind) {
        return (int) respawns.stream().filter(r -> r.kind() == kind).count();
    }
}
