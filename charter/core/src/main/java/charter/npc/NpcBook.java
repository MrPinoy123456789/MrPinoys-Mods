package charter.npc;

import charter.id.ChunkKey;
import charter.id.CoreId;
import charter.id.OrgId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    /**
     * What a site has left and what it changed (protected keeps, N): its supply of raiders, how many may stand at
     * once, when the next wave is due, when anyone last fought it, the raiders it has out, and the blocks it
     * replaced when it was raised. Kept apart from the immutable {@link Site}; saved with the book.
     */
    public static final class Live {
        public int supply;
        public int pool;
        public long nextWaveAt;
        public long lastFoughtAt;
        public int spawned;
        public boolean adopted;
        public boolean hasHome;
        public int homeX;
        public int homeY;
        public int homeZ;
        public List<UUID> raiders = new ArrayList<>();
        /** Null for a site raised before snapshots existed: its ground is then cleared to the surface. */
        public Snapshot snapshot;
    }

    /** The blocks a site replaced: the distinct block states, and for each changed position the index of its old state. */
    public static final class Snapshot {
        public List<String> states = new ArrayList<>();
        public List<int[]> blocks = new ArrayList<>();
    }

    /**
     * A site that fell and is coming down (protected keeps, N): its blocks go from the top layer to the ground, then the
     * ground it replaced is put back and its loot spills. Saved with the book so a restart in the middle carries on.
     */
    public static final class Crumble {
        public String dimension;
        /** Positions still to remove, lowest layer first, so the next layer is always at the end. */
        public List<int[]> pending = new ArrayList<>();
        /** Put back as the last step. */
        public Snapshot restore = new Snapshot();
        public int homeX;
        public int homeY;
        public int homeZ;
        /** A quarter share of the treasury, spilled when the ground is back. */
        public long diamonds;
        public long cobblestone;
    }

    public final List<Crumble> crumbles = new ArrayList<>();
    public final List<Site> sites = new ArrayList<>();
    public final Map<Long, Live> live = new HashMap<>();
    public final List<Respawn> respawns = new ArrayList<>();
    public long nextId = 1;

    public int standing(Kind kind) {
        return (int) sites.stream().filter(s -> s.kind() == kind).count();
    }

    public int waiting(Kind kind) {
        return (int) respawns.stream().filter(r -> r.kind() == kind).count();
    }
}
