package charter.mc.map;

import charter.config.Knobs;
import charter.id.OrgId;
import charter.land.CampPhase;
import charter.land.Claim;
import charter.land.Core;
import charter.mapwire.MapModel;
import charter.mapwire.Runs;
import charter.mc.land.Fixtures;
import charter.org.Organization;
import charter.state.CharterState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The kingdom map players with the Charter Maps client mod are sent, built from the state. No Minecraft types, so a
 * plain test runs it.
 *
 * <p><b>Hidden information.</b> It carries exactly the land {@link charter.api.CharterAreas} publishes (every claim, as
 * keep, outpost or plain held land, by the root kingdom), plus what anyone can see in the world or in the tag list: the
 * kingdom's name, tag colour and race label, whether it is the spawn kingdom, the keep's core chunk and where its Nexus
 * lodestone stands, and outposts as points. It never carries an outpost's phase (raising or raised), a contested
 * claim's grace, food, members, treasuries or wars. A raiders' stronghold is outline only: no keep, no Nexus, no points.
 * A lifted Nexus has no position until it is set down again; the map never follows its carrier. Everything comes from
 * the state and the saved fixture records; nothing here looks at a block.
 */
public final class MapView {

    /** Kingdom names longer than this are cut on the map. */
    static final int MAX_NAME = 64;
    /** Map colour of the spawn kingdom when {@code tagColors} names none. */
    static final int ADMIN_COLOUR = 0xE0E0E0;
    /** Map colour of the raiders when {@code tagColors} names none. */
    static final int NPC_COLOUR = 0x5A5A5A;
    /** The height an outpost point is given when its banner record is missing. */
    static final int DEFAULT_Y = 64;

    private MapView() {
    }

    /** The {@code tagColors} key of a kingdom: its name in lower case. {@code Tags} uses the same key. */
    public static String colourKey(String kingdomName) {
        return kingdomName.toLowerCase(Locale.ROOT);
    }

    /** A {@code #RRGGBB} or {@code RRGGBB} colour, or -1 when it is not one. */
    public static int parseColour(String hex) {
        if (hex == null) return -1;
        String h = hex.trim();
        if (h.startsWith("#")) h = h.substring(1);
        if (h.length() != 6) return -1;
        try {
            return Integer.parseInt(h, 16);
        } catch (NumberFormatException bad) {
            return -1;
        }
    }

    /**
     * A kingdom's map colour: its {@code tagColors} entry, else light grey for the spawn kingdom, dark grey for the
     * raiders, and otherwise a steady colour made from its name (so it never changes between restarts).
     */
    public static int colourOf(Knobs knobs, String name, boolean admin, boolean npc) {
        int tagged = parseColour(knobs.tagColors.get(colourKey(name)));
        if (tagged >= 0) return tagged;
        if (admin) return ADMIN_COLOUR;
        if (npc) return NPC_COLOUR;
        return colourFromName(name);
    }

    /** A bright, steady colour from a name's hash: hue from the hash, saturation 0.65, brightness 0.9. */
    static int colourFromName(String name) {
        int h = colourKey(name).hashCode();
        return hsb((h & 0x7FFFFFFF) % 360, 0.65, 0.9);
    }

    /** Hue in degrees, saturation and brightness 0 to 1, to 0xRRGGBB. Plain maths, so a server never loads AWT. */
    static int hsb(int hueDegrees, double saturation, double brightness) {
        double c = brightness * saturation;
        double hp = (hueDegrees % 360) / 60.0;
        double x = c * (1 - Math.abs(hp % 2 - 1));
        double r = 0, g = 0, b = 0;
        switch ((int) hp) {
            case 0 -> { r = c; g = x; }
            case 1 -> { r = x; g = c; }
            case 2 -> { g = c; b = x; }
            case 3 -> { g = x; b = c; }
            case 4 -> { r = x; b = c; }
            default -> { r = c; b = x; }
        }
        double m = brightness - c;
        int ri = (int) Math.round((r + m) * 255);
        int gi = (int) Math.round((g + m) * 255);
        int bi = (int) Math.round((b + m) * 255);
        return (ri << 16) | (gi << 8) | bi;
    }

    /** Every kingdom that holds land, sorted by name, then id. */
    public static List<MapModel.Kingdom> build(CharterState state, Knobs knobs) {
        Fixtures fixtures = Fixtures.of(state);
        // Root kingdom, then "dimension kind" (sorted), then the chunks.
        Map<OrgId, TreeMap<String, List<Long>>> land = new LinkedHashMap<>();
        Map<OrgId, Core> keeps = new LinkedHashMap<>();
        Map<OrgId, Map<Object, Core>> outposts = new LinkedHashMap<>();
        for (Claim claim : state.claims()) {
            OrgId root = state.root(claim.owner);
            Core held = claim.core() == null ? null : state.core(claim.core()).orElse(null);
            MapModel.LandKind kind = held == null ? MapModel.LandKind.CLAIM
                    : held.isKeep() ? MapModel.LandKind.KEEP : MapModel.LandKind.OUTPOST;
            String key = claim.chunk.dimension() + " " + kind.ordinal();
            land.computeIfAbsent(root, r -> new TreeMap<>()).computeIfAbsent(key, k -> new ArrayList<>())
                    .add(Runs.pack(claim.chunk.x(), claim.chunk.z()));
            if (held == null) continue;
            if (held.isKeep()) {
                Core known = keeps.get(root);
                if (known == null || held.order < known.order) keeps.put(root, held);
            } else if (held.phase() != CampPhase.PACKED) {
                outposts.computeIfAbsent(root, r -> new LinkedHashMap<>()).put(held.id, held);
            }
        }

        List<MapModel.Kingdom> out = new ArrayList<>();
        for (Map.Entry<OrgId, TreeMap<String, List<Long>>> e : land.entrySet()) {
            OrgId root = e.getKey();
            Organization org = state.org(root).orElse(null);
            String name = clip(org == null ? "?" : org.name());
            boolean admin = org != null && org.adminKingdom;
            boolean npc = org != null && org.npc;
            String race = clip(org == null ? name : org.raceLabel());

            List<MapModel.Land> lands = new ArrayList<>();
            for (Map.Entry<String, List<Long>> l : e.getValue().entrySet()) {
                int space = l.getKey().lastIndexOf(' ');
                String dimension = l.getKey().substring(0, space);
                MapModel.LandKind kind = MapModel.LandKind.values()[Integer.parseInt(l.getKey().substring(space + 1))];
                long[] chunks = l.getValue().stream().mapToLong(Long::longValue).toArray();
                lands.add(new MapModel.Land(dimension, kind, Runs.encode(chunks)));
            }

            MapModel.Keep keep = null;
            List<MapModel.Point> points = new ArrayList<>();
            if (!npc) {
                Core core = keeps.get(root);
                if (core != null) {
                    Fixtures.Fixture nexus = fixtures.first(core.id, Fixtures.Kind.NEXUS);
                    keep = nexus == null
                            ? new MapModel.Keep(core.center.dimension(), core.center.x(), core.center.z(), false, 0, 0, 0)
                            : new MapModel.Keep(core.center.dimension(), core.center.x(), core.center.z(), true,
                            nexus.x(), nexus.y(), nexus.z());
                }
                for (Core camp : outposts.getOrDefault(root, Map.of()).values()) {
                    Fixtures.Fixture banner = fixtures.first(camp.id, Fixtures.Kind.BANNER);
                    if (banner != null) {
                        points.add(new MapModel.Point(MapModel.Point.OUTPOST, banner.dimension(), banner.x(), banner.y(), banner.z()));
                    } else {
                        points.add(new MapModel.Point(MapModel.Point.OUTPOST, camp.center.dimension(),
                                camp.center.x() * 16 + 8, DEFAULT_Y, camp.center.z() * 16 + 8));
                    }
                }
                points.sort(Comparator.comparing(MapModel.Point::dimension).thenComparingInt(MapModel.Point::x)
                        .thenComparingInt(MapModel.Point::z));
            }
            out.add(new MapModel.Kingdom(root.value(), name, colourOf(knobs, name, admin, npc), race, admin, npc, keep,
                    lands, points));
        }
        out.sort(Comparator.comparing(MapModel.Kingdom::name).thenComparing(MapModel.Kingdom::id));
        return out;
    }

    private static String clip(String s) {
        return s.length() <= MAX_NAME ? s : s.substring(0, MAX_NAME);
    }
}
