package charter.mapwire;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The bytes of a map message. One custom payload carries one message as a plain byte array; this class is the only
 * place that knows the layout, and both the server and the client mod use it (the client through its copied package).
 *
 * <pre>
 * message   = format:u8 (1)  type:u8 (0 snapshot, 1 delta)  body
 * snapshot  = seq:varlong  count:varint kingdom*  count:varint marker*
 * delta     = fromSeq:varlong  toSeq:varlong  count:varint kingdom*  count:varint uuid*
 *             count:varint marker*  count:varint string*
 * kingdom   = uuid  name:string  colour:u24  race:string  flags:u8 (1 admin, 2 npc, 4 keep)
 *             [keep]  count:varint land*  count:varint point*
 * keep      = dimension  coreX:zigzag  coreZ:zigzag  hasNexus:u8  [x:zigzag y:zigzag z:zigzag]
 * land      = dimension  kind:u8  count:varint run*
 * run       = dz:zigzag (from the last run's z)  dx:zigzag (from the last run's x0)  length-1:varint
 * point     = kind:string  dimension  x:zigzag  y:zigzag  z:zigzag
 * marker    = id:string  kind:string  label:string  dimension  x:zigzag  y:zigzag  z:zigzag
 *             colour:u24  icon:string  ttlMillis:varlong (0 never expires)
 * uuid      = most:i64 least:i64 (big endian)
 * string    = length:varint utf8 bytes
 * dimension = 0:varint string (a new id, which takes the next table slot)  or  slot+1:varint (one seen before in
 *             this message, or in this kingdom when a kingdom is encoded alone)
 * </pre>
 *
 * <p>A reader never trusts a count: every list and string has a ceiling, and anything malformed throws
 * {@link BadMessage}, which a receiver catches and drops.
 */
public final class MapWire {

    /** The layout version. A client that reads a higher one ignores the message. */
    public static final int FORMAT = 1;
    public static final int SNAPSHOT = 0;
    public static final int DELTA = 1;

    /** Ceilings a reader holds a message to. */
    static final int MAX_STRING_BYTES = 1024;
    static final int MAX_KINGDOMS = 4096;
    static final int MAX_LANDS = 4096;
    static final int MAX_RUNS = 1 << 20;
    static final int MAX_POINTS = 4096;
    static final int MAX_MARKERS = 4096;
    /** The largest message a server sends: under the 1 MiB custom payload limit, with room for framing. */
    public static final int MAX_BYTES = 1_000_000;

    private MapWire() {
    }

    /** A map message: a {@link MapModel.Snapshot} or a {@link MapModel.Delta}. */
    public sealed interface Message permits MapModel.Snapshot, MapModel.Delta {
    }

    /** A malformed or unreadable message. */
    public static final class BadMessage extends RuntimeException {
        public BadMessage(String why) {
            super(why);
        }
    }

    // ---- writing ----

    public static byte[] write(Message message) {
        Out out = new Out();
        out.u8(FORMAT);
        if (message instanceof MapModel.Snapshot s) {
            out.u8(SNAPSHOT);
            out.varlong(s.seq());
            out.varint(s.kingdoms().size());
            for (MapModel.Kingdom k : s.kingdoms()) kingdom(out, k);
            out.varint(s.markers().size());
            for (MapModel.Marker m : s.markers()) marker(out, m);
        } else if (message instanceof MapModel.Delta d) {
            out.u8(DELTA);
            out.varlong(d.fromSeq());
            out.varlong(d.toSeq());
            out.varint(d.kingdoms().size());
            for (MapModel.Kingdom k : d.kingdoms()) kingdom(out, k);
            out.varint(d.removedKingdoms().size());
            for (UUID id : d.removedKingdoms()) out.uuid(id);
            out.varint(d.markers().size());
            for (MapModel.Marker m : d.markers()) marker(out, m);
            out.varint(d.removedMarkers().size());
            for (String id : d.removedMarkers()) out.string(id);
        }
        return out.bytes();
    }

    /** One kingdom's bytes alone: what a server compares to tell whether the kingdom changed. */
    public static byte[] kingdomBytes(MapModel.Kingdom kingdom) {
        Out out = new Out();
        kingdom(out, kingdom);
        return out.bytes();
    }

    private static void kingdom(Out out, MapModel.Kingdom k) {
        out.uuid(k.id());
        out.string(k.name());
        out.u24(k.colour());
        out.string(k.race());
        int flags = (k.admin() ? 1 : 0) | (k.npc() ? 2 : 0) | (k.keep() != null ? 4 : 0);
        out.u8(flags);
        if (k.keep() != null) {
            MapModel.Keep keep = k.keep();
            out.dimension(keep.dimension());
            out.zigzag(keep.coreChunkX());
            out.zigzag(keep.coreChunkZ());
            out.u8(keep.hasNexus() ? 1 : 0);
            if (keep.hasNexus()) {
                out.zigzag(keep.nexusX());
                out.zigzag(keep.nexusY());
                out.zigzag(keep.nexusZ());
            }
        }
        out.varint(k.lands().size());
        for (MapModel.Land land : k.lands()) {
            out.dimension(land.dimension());
            out.u8(land.kind().ordinal());
            out.varint(land.runs().size());
            int lastZ = 0;
            int lastX = 0;
            for (MapModel.ChunkRun run : land.runs()) {
                out.zigzag(run.z() - lastZ);
                out.zigzag(run.x0() - lastX);
                out.varint(run.length() - 1);
                lastZ = run.z();
                lastX = run.x0();
            }
        }
        out.varint(k.points().size());
        for (MapModel.Point p : k.points()) {
            out.string(p.kind());
            out.dimension(p.dimension());
            out.zigzag(p.x());
            out.zigzag(p.y());
            out.zigzag(p.z());
        }
    }

    private static void marker(Out out, MapModel.Marker m) {
        out.string(m.id());
        out.string(m.kind());
        out.string(m.label());
        out.dimension(m.dimension());
        out.zigzag(m.x());
        out.zigzag(m.y());
        out.zigzag(m.z());
        out.u24(m.colour());
        out.string(m.icon());
        out.varlong(Math.max(0, m.ttlMillis()));
    }

    // ---- reading ----

    /** Reads one message. Throws {@link BadMessage} for anything malformed, truncated, too large or of a newer format. */
    public static Message read(byte[] bytes) {
        if (bytes == null || bytes.length < 2) throw new BadMessage("too short");
        In in = new In(bytes);
        int format = in.u8();
        if (format != FORMAT) throw new BadMessage("format " + format + " is not " + FORMAT);
        int type = in.u8();
        Message message;
        if (type == SNAPSHOT) {
            long seq = in.varlong();
            List<MapModel.Kingdom> kingdoms = new ArrayList<>();
            int n = in.count(MAX_KINGDOMS);
            for (int i = 0; i < n; i++) kingdoms.add(kingdom(in));
            List<MapModel.Marker> markers = new ArrayList<>();
            int m = in.count(MAX_MARKERS);
            for (int i = 0; i < m; i++) markers.add(marker(in));
            message = new MapModel.Snapshot(seq, kingdoms, markers);
        } else if (type == DELTA) {
            long from = in.varlong();
            long to = in.varlong();
            List<MapModel.Kingdom> kingdoms = new ArrayList<>();
            int n = in.count(MAX_KINGDOMS);
            for (int i = 0; i < n; i++) kingdoms.add(kingdom(in));
            List<UUID> removed = new ArrayList<>();
            int r = in.count(MAX_KINGDOMS);
            for (int i = 0; i < r; i++) removed.add(in.uuid());
            List<MapModel.Marker> markers = new ArrayList<>();
            int m = in.count(MAX_MARKERS);
            for (int i = 0; i < m; i++) markers.add(marker(in));
            List<String> gone = new ArrayList<>();
            int g = in.count(MAX_MARKERS);
            for (int i = 0; i < g; i++) gone.add(in.string());
            message = new MapModel.Delta(from, to, kingdoms, removed, markers, gone);
        } else {
            throw new BadMessage("unknown message type " + type);
        }
        if (in.remaining() != 0) throw new BadMessage(in.remaining() + " trailing byte(s)");
        return message;
    }

    private static MapModel.Kingdom kingdom(In in) {
        UUID id = in.uuid();
        String name = in.string();
        int colour = in.u24();
        String race = in.string();
        int flags = in.u8();
        MapModel.Keep keep = null;
        if ((flags & 4) != 0) {
            String dimension = in.dimension();
            int cx = in.zigzag();
            int cz = in.zigzag();
            boolean hasNexus = in.u8() != 0;
            int nx = 0, ny = 0, nz = 0;
            if (hasNexus) {
                nx = in.zigzag();
                ny = in.zigzag();
                nz = in.zigzag();
            }
            keep = new MapModel.Keep(dimension, cx, cz, hasNexus, nx, ny, nz);
        }
        List<MapModel.Land> lands = new ArrayList<>();
        int nl = in.count(MAX_LANDS);
        for (int i = 0; i < nl; i++) {
            String dimension = in.dimension();
            MapModel.LandKind kind = MapModel.LandKind.of(in.u8());
            int nr = in.count(MAX_RUNS);
            List<MapModel.ChunkRun> runs = new ArrayList<>(Math.min(nr, 1024));
            int lastZ = 0;
            int lastX = 0;
            for (int j = 0; j < nr; j++) {
                int z = lastZ + in.zigzag();
                int x0 = lastX + in.zigzag();
                int length = in.varint() + 1;
                if (length < 1 || length > MAX_RUNS) throw new BadMessage("run length " + length);
                runs.add(new MapModel.ChunkRun(z, x0, length));
                lastZ = z;
                lastX = x0;
            }
            lands.add(new MapModel.Land(dimension, kind, runs));
        }
        List<MapModel.Point> points = new ArrayList<>();
        int np = in.count(MAX_POINTS);
        for (int i = 0; i < np; i++) {
            points.add(new MapModel.Point(in.string(), in.dimension(), in.zigzag(), in.zigzag(), in.zigzag()));
        }
        return new MapModel.Kingdom(id, name, colour, race, (flags & 1) != 0, (flags & 2) != 0, keep, lands, points);
    }

    private static MapModel.Marker marker(In in) {
        String id = in.string();
        String kind = in.string();
        String label = in.string();
        String dimension = in.dimension();
        int x = in.zigzag();
        int y = in.zigzag();
        int z = in.zigzag();
        int colour = in.u24();
        String icon = in.string();
        long ttl = in.varlong();
        if (ttl < 0) throw new BadMessage("negative expiry");
        return new MapModel.Marker(id, kind, label, dimension, x, y, z, colour, icon, ttl);
    }

    // ---- the byte level ----

    /** A growing byte buffer with the few writes the layout needs. */
    static final class Out {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);

        void u8(int v) {
            bytes.write(v & 0xFF);
        }

        void u24(int v) {
            bytes.write((v >>> 16) & 0xFF);
            bytes.write((v >>> 8) & 0xFF);
            bytes.write(v & 0xFF);
        }

        void varint(int v) {
            if (v < 0) throw new IllegalArgumentException("a count is never negative");
            varlong(v);
        }

        void varlong(long v) {
            while ((v & ~0x7FL) != 0) {
                bytes.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            bytes.write((int) v);
        }

        void zigzag(int v) {
            varlong(((long) v << 1) ^ ((long) v >> 63));
        }

        void i64(long v) {
            for (int s = 56; s >= 0; s -= 8) bytes.write((int) (v >>> s) & 0xFF);
        }

        void uuid(UUID id) {
            i64(id.getMostSignificantBits());
            i64(id.getLeastSignificantBits());
        }

        void string(String s) {
            byte[] utf8 = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
            if (utf8.length > MAX_STRING_BYTES) throw new IllegalArgumentException("a string longer than " + MAX_STRING_BYTES + " bytes");
            varint(utf8.length);
            bytes.write(utf8, 0, utf8.length);
        }

        private final java.util.Map<String, Integer> dimensions = new java.util.HashMap<>();

        /** A dimension id: written in full the first time, as its table slot after. */
        void dimension(String id) {
            Integer slot = dimensions.get(id);
            if (slot != null) {
                varint(slot + 1);
                return;
            }
            varint(0);
            string(id);
            dimensions.put(id, dimensions.size());
        }

        byte[] bytes() {
            return bytes.toByteArray();
        }
    }

    /** Reads what {@link Out} wrote, refusing to run past the end. */
    static final class In {
        private final byte[] data;
        private int at;

        In(byte[] data) {
            this.data = data;
        }

        int remaining() {
            return data.length - at;
        }

        int u8() {
            if (at >= data.length) throw new BadMessage("truncated");
            return data[at++] & 0xFF;
        }

        int u24() {
            return (u8() << 16) | (u8() << 8) | u8();
        }

        long varlong() {
            long v = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                int b = u8();
                v |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return v;
            }
            throw new BadMessage("varint too long");
        }

        int varint() {
            long v = varlong();
            if (v < 0 || v > Integer.MAX_VALUE) throw new BadMessage("count out of range");
            return (int) v;
        }

        /** A list length, refused above the ceiling or above what the bytes left could hold. */
        int count(int ceiling) {
            int n = varint();
            if (n > ceiling || n > remaining()) throw new BadMessage("count " + n + " too large");
            return n;
        }

        int zigzag() {
            long raw = varlong();
            long v = (raw >>> 1) ^ -(raw & 1);
            if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) throw new BadMessage("number out of range");
            return (int) v;
        }

        long i64() {
            long v = 0;
            for (int i = 0; i < 8; i++) v = (v << 8) | u8();
            return v;
        }

        UUID uuid() {
            return new UUID(i64(), i64());
        }

        String string() {
            int n = varint();
            if (n > MAX_STRING_BYTES || n > remaining()) throw new BadMessage("string of " + n + " bytes");
            String s = new String(data, at, n, StandardCharsets.UTF_8);
            at += n;
            return s;
        }

        private final List<String> dimensions = new ArrayList<>();

        /** What {@link Out#dimension} wrote. */
        String dimension() {
            int slot = varint();
            if (slot == 0) {
                if (dimensions.size() >= MAX_LANDS) throw new BadMessage("too many dimensions");
                String id = string();
                dimensions.add(id);
                return id;
            }
            if (slot - 1 >= dimensions.size()) throw new BadMessage("unknown dimension slot " + slot);
            return dimensions.get(slot - 1);
        }
    }
}
