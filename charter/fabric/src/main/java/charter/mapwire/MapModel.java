package charter.mapwire;

import java.util.List;
import java.util.UUID;

/**
 * The kingdom map as it travels from the server to the Charter Maps client mod. Plain records, JDK only: this package
 * is copied into the client mod (renamed to {@code charterxaero.wire}) by its build, so it must import nothing but
 * {@code java.*} and other classes of this package.
 *
 * <p>Everything here is already public on the server: held land is in {@code areas.json}, a keep's Nexus is a block
 * anyone can walk to, and markers are what an operator chose to show.
 */
public final class MapModel {

    private MapModel() {
    }

    /** What a piece of held land is, as {@code charter.api.Area.Kind} says. */
    public enum LandKind {
        KEEP, OUTPOST, CLAIM;

        static LandKind of(int ordinal) {
            LandKind[] all = values();
            if (ordinal < 0 || ordinal >= all.length) throw new MapWire.BadMessage("unknown land kind " + ordinal);
            return all[ordinal];
        }
    }

    /**
     * One row run of chunks: chunk row {@code z}, from chunk {@code x0} eastwards, {@code length} chunks long
     * (at least 1).
     */
    public record ChunkRun(int z, int x0, int length) {
        public ChunkRun {
            if (length < 1) throw new IllegalArgumentException("a run holds at least one chunk");
        }

        /** The last chunk x of the run. */
        public int x1() {
            return x0 + length - 1;
        }
    }

    /** The chunks of one kind of land a kingdom holds in one dimension, as row runs sorted by z then x. */
    public record Land(String dimension, LandKind kind, List<ChunkRun> runs) {
        public Land {
            runs = List.copyOf(runs);
        }
    }

    /**
     * A kingdom's keep: its core chunk, and the block its Nexus lodestone stands on when it stands (a lifted Nexus has
     * no position).
     */
    public record Keep(String dimension, int coreChunkX, int coreChunkZ, boolean hasNexus, int nexusX, int nexusY, int nexusZ) {
    }

    /** A point of a kingdom on the map; today only {@link #OUTPOST}, the outpost's banner chunk. */
    public record Point(String kind, String dimension, int x, int y, int z) {
        public static final String OUTPOST = "outpost";
    }

    /**
     * One kingdom: the top organization that holds land.
     *
     * @param id     the organization's id, stable across renames and restarts
     * @param name   its display name
     * @param colour its map colour, 0xRRGGBB
     * @param race   the race label players see (the kingdom's name when it has none)
     * @param admin  the admin kingdom (the spawn town)
     * @param npc    a raiders' stronghold: outline only, no keep and no points
     * @param keep   the keep, or null when it has none (or for raiders)
     * @param lands  held land by dimension and kind
     * @param points outposts as points
     */
    public record Kingdom(UUID id, String name, int colour, String race, boolean admin, boolean npc, Keep keep,
                          List<Land> lands, List<Point> points) {
        public Kingdom {
            lands = List.copyOf(lands);
            points = List.copyOf(points);
        }
    }

    /**
     * A marker an operator or another mod put on the map (a supply drop, an event).
     *
     * @param ttlMillis milliseconds the marker has left when it is sent, 0 for no expiry. A receiver adds it to its own
     *                  clock, so the two clocks never need to agree.
     * @param colour    0xRRGGBB
     * @param icon      an icon name the client may know, or empty
     */
    public record Marker(String id, String kind, String label, String dimension, int x, int y, int z, int colour,
                         String icon, long ttlMillis) {
    }

    /** Everything, sent when a player joins. */
    public record Snapshot(long seq, List<Kingdom> kingdoms, List<Marker> markers) implements MapWire.Message {
        public Snapshot {
            kingdoms = List.copyOf(kingdoms);
            markers = List.copyOf(markers);
        }
    }

    /**
     * What changed since {@code fromSeq}: whole kingdoms replaced or removed, markers added, replaced or removed. A
     * kingdom is a few hundred bytes, so a changed kingdom is simply sent again.
     */
    public record Delta(long fromSeq, long toSeq, List<Kingdom> kingdoms, List<UUID> removedKingdoms,
                        List<Marker> markers, List<String> removedMarkers) implements MapWire.Message {
        public Delta {
            kingdoms = List.copyOf(kingdoms);
            removedKingdoms = List.copyOf(removedKingdoms);
            markers = List.copyOf(markers);
            removedMarkers = List.copyOf(removedMarkers);
        }

        public boolean isEmpty() {
            return kingdoms.isEmpty() && removedKingdoms.isEmpty() && markers.isEmpty() && removedMarkers.isEmpty();
        }
    }
}
