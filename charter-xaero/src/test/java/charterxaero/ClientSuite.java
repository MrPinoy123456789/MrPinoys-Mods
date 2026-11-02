package charterxaero;

import charterxaero.model.Borders;
import charterxaero.model.Colours;
import charterxaero.model.KingdomIndex;
import charterxaero.model.MapStore;
import charterxaero.model.Tooltips;
import charterxaero.model.Versions;
import charterxaero.wire.MapModel;
import charterxaero.wire.MapState;
import charterxaero.wire.MapWire;
import charterxaero.wire.Runs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Plain Java tests of Charter Maps: no game, no Xaero. Everything the Xaero binding draws comes from what is tested
 * here; the binding itself only forwards. Exits non-zero on any failure so {@code check} fails.
 */
public final class ClientSuite {

    private static final String OW = "minecraft:overworld";
    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        wire();
        colours();
        borders();
        index();
        regionHashes();
        markerChunks();
        tooltips();
        versions();
        store();

        System.out.println("charter-xaero: " + passed + " passed, " + failures.size() + " failed");
        for (String f : failures) System.out.println("  FAIL " + f);
        if (!failures.isEmpty()) System.exit(1);
    }

    static void check(String description, boolean condition) {
        if (condition) passed++;
        else failures.add(description);
    }

    // ---- helpers ----

    static MapModel.Kingdom square(UUID id, String name, int colour, int x0, int z0, int side, boolean keep) {
        List<Long> chunks = new ArrayList<>();
        for (int x = x0; x < x0 + side; x++) for (int z = z0; z < z0 + side; z++) chunks.add(Runs.pack(x, z));
        MapModel.Land land = new MapModel.Land(OW, keep ? MapModel.LandKind.KEEP : MapModel.LandKind.CLAIM,
                Runs.encode(chunks.stream().mapToLong(Long::longValue).toArray()));
        MapModel.Keep k = keep ? new MapModel.Keep(OW, x0 + side / 2, z0 + side / 2, true, (x0 + side / 2) * 16 + 8, 70, (z0 + side / 2) * 16 + 8) : null;
        return new MapModel.Kingdom(id, name, colour, name + "folk", false, false, k, List.of(land), List.of());
    }

    static MapState stateOf(List<MapModel.Kingdom> ks, List<MapModel.Marker> ms) {
        return MapState.EMPTY.apply(new MapModel.Snapshot(1, ks, ms), 1_000);
    }

    // ---- the wire, as this mod's copy reads it ----

    static void wire() {
        MapModel.Kingdom a = square(new UUID(1, 1), "Azrak", 0xFFD700, -10, -10, 9, true);
        MapModel.Marker m = new MapModel.Marker("drop", "supply_drop", "Supply drop", OW, 1, 64, -1, 0xFFAA00, "", 60_000);
        MapModel.Snapshot s = new MapModel.Snapshot(3, List.of(a), List.of(m));
        check("wire: the copied codec reads back what it wrote", s.equals(MapWire.read(MapWire.write(s))));
        check("wire: a 9 by 9 keep is nine runs", a.lands().get(0).runs().size() == 9);
        boolean refused;
        try {
            MapWire.read(new byte[]{1, 0, 5});
            refused = false;
        } catch (MapWire.BadMessage bad) {
            refused = true;
        }
        check("wire: a broken message is refused as a bad message", refused);
    }

    // ---- colours ----

    static void colours() {
        check("colours: Xaero's layout is blue, green, red, alpha", Colours.toXaero(0xFF6A1A, 0x48) == 0x1A6AFF48);
        check("colours: and back", Colours.rgbOfXaero(Colours.toXaero(0x89CFF0, 0x10)) == 0x89CFF0
                && Colours.alphaOfXaero(Colours.toXaero(0x89CFF0, 0x10)) == 0x10);
        check("colours: the border is darker than the fill", Colours.border(0xFFFFFF) == 0xBFBFBF && Colours.border(0x000000) == 0);
        check("colours: mix halfway", Colours.mix(0x000000, 0xFEFEFE, 0.5) == 0x7F7F7F);

        int[] into = new int[5];
        Colours.chunkColours(0xFFD700, false, Borders.NORTH | Borders.WEST, into);
        int fill = Colours.toXaero(0xFFD700, Colours.FILL_ALPHA);
        int edge = Colours.toXaero(Colours.border(0xFFD700), Colours.BORDER_ALPHA);
        check("colours: a corner chunk has its north and west edges as border", into[0] == fill && into[1] == edge
                && into[2] == fill && into[3] == fill && into[4] == edge);
        Colours.chunkColours(0xFFD700, true, 0, into);
        check("colours: the keep's core is filled more strongly", Colours.alphaOfXaero(into[0]) == Colours.CORE_ALPHA
                && Colours.CORE_ALPHA > Colours.FILL_ALPHA && Colours.BORDER_ALPHA > Colours.CORE_ALPHA);
        Colours.markerColours(0xFFAA00, into);
        check("colours: a marker chunk is solid with a rim", Colours.alphaOfXaero(into[0]) == Colours.MARKER_ALPHA
                && into[1] == into[2] && into[2] == into[3] && into[3] == into[4] && into[1] != into[0]);

        // Xaero's waypoint colours (black, dark blue, ..., gold, ..., red, ..., pink).
        int[] palette = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA, 0x555555, 0x5555FF,
                0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
        check("colours: Azrak's gold is nearest gold or yellow", Colours.nearest(0xFFD700, palette) == 6 || Colours.nearest(0xFFD700, palette) == 14);
        check("colours: a supply drop's orange is nearest gold", Colours.nearest(0xFFAA00, palette) == 6);
        check("colours: Faeloria's purple is nearest purple", Colours.nearest(0xA020F0, palette) == 5 || Colours.nearest(0xA020F0, palette) == 13);
        check("colours: Azurius' baby blue is nearest aqua or white", Colours.nearest(0x89CFF0, palette) == 11 || Colours.nearest(0x89CFF0, palette) == 15);
        check("colours: an exact colour is itself", Colours.nearest(0x55FF55, palette) == 10);
    }

    // ---- borders ----

    static void borders() {
        // A 3 by 3 kingdom 0 at (0..2, 0..2), kingdom 1 just east at (3, 1).
        Borders.Owner owner = (x, z) -> {
            if (x >= 0 && x <= 2 && z >= 0 && z <= 2) return 0;
            if (x == 3 && z == 1) return 1;
            return -1;
        };
        check("borders: the middle chunk has none", Borders.sides(owner, 1, 1, 0) == 0);
        check("borders: the north west corner has north and west", Borders.sides(owner, 0, 0, 0) == (Borders.NORTH | Borders.WEST));
        check("borders: a neighbouring kingdom is a border too", (Borders.sides(owner, 2, 1, 0) & Borders.EAST) != 0);
        check("borders: a lone chunk is bordered all round", Borders.sides(owner, 3, 1, 1) == (Borders.NORTH | Borders.EAST | Borders.SOUTH | Borders.WEST));
    }

    // ---- the index ----

    static void index() {
        MapModel.Kingdom a = square(new UUID(1, 1), "Azrak", 0xFFD700, 0, 0, 9, true);
        MapModel.Kingdom b = square(new UUID(2, 2), "Aurelia", 0xFF6A1A, 9, 0, 3, false);
        KingdomIndex i = KingdomIndex.of(stateOf(List.of(a, b), List.of()), false, 0);
        check("index: counts every chunk", i.chunkCount() == 81 + 9);
        check("index: who holds a chunk", i.kingdomAt(OW, 4, 4).name().equals("Azrak") && i.kingdomAt(OW, 10, 1).name().equals("Aurelia")
                && i.kingdomAt(OW, 50, 50) == null && i.kingdomAt("minecraft:the_nether", 4, 4) == null);
        check("index: the keep's core chunk is marked", i.at(OW, 4, 4).core() && !i.at(OW, 3, 4).core());
        check("index: the land kind is kept", i.at(OW, 4, 4).kind() == MapModel.LandKind.KEEP && i.at(OW, 10, 1).kind() == MapModel.LandKind.CLAIM);
        check("index: the inside has no border", i.sides(OW, 4, 4) == 0);
        check("index: the edge against another kingdom is a border", (i.sides(OW, 8, 1) & Borders.EAST) != 0
                && (i.sides(OW, 9, 1) & Borders.WEST) != 0);
        check("index: nobody's chunk has no sides", i.sides(OW, 40, 40) == 0);
        check("index: regions with land are drawn", i.regionDrawn(OW, 0, 0) && !i.regionDrawn(OW, 5, 5));
        check("index: an empty index draws nothing", !KingdomIndex.EMPTY.drawn(OW, 0, 0) && KingdomIndex.EMPTY.regionHash(OW, 0, 0) == 0);
    }

    static void regionHashes() {
        // A kingdom ending on the last column of region 0 (chunk x 31) changes the drawing of region 1 too: its border
        // depends on the neighbour across the region line.
        MapModel.Kingdom edge = square(new UUID(3, 3), "Edge", 0x123456, 29, 5, 3, false);
        KingdomIndex before = KingdomIndex.of(stateOf(List.of(), List.of(new MapModel.Marker("x", "k", "", "minecraft:the_end", 0, 0, 0, 0, "", 0))), false, 0);
        KingdomIndex after = KingdomIndex.of(stateOf(List.of(edge), List.of()), false, 0);
        Map<String, Set<Long>> changed = KingdomIndex.changedRegions(before, after);
        check("regions: land on a region's edge changes it and the next one", changed.getOrDefault(OW, Set.of())
                .containsAll(Set.of(Runs.pack(0, 0), Runs.pack(1, 0))));
        check("regions: and nothing far away", !changed.getOrDefault(OW, Set.of()).contains(Runs.pack(3, 3)));
        check("regions: the hash of a region with land is not 0", after.regionHash(OW, 0, 0) != 0);

        MapModel.Kingdom recoloured = square(new UUID(3, 3), "Edge", 0x654321, 29, 5, 3, false);
        KingdomIndex again = KingdomIndex.of(stateOf(List.of(recoloured), List.of()), false, 0);
        check("regions: a new colour changes the hash", again.regionHash(OW, 0, 0) != after.regionHash(OW, 0, 0));
        MapModel.Kingdom renamed = new MapModel.Kingdom(edge.id(), "Renamed", edge.colour(), edge.race(), false, false, null, edge.lands(), List.of());
        check("regions: a rename alone redraws nothing (names are read live)",
                KingdomIndex.changedRegions(after, KingdomIndex.of(stateOf(List.of(renamed), List.of()), false, 0)).isEmpty());

        MapModel.Kingdom far = square(new UUID(4, 4), "Far", 0x00FF00, 500, 500, 2, false);
        Map<String, Set<Long>> farChange = KingdomIndex.changedRegions(after, KingdomIndex.of(stateOf(List.of(edge, far), List.of()), false, 0));
        check("regions: adding far land redraws only its regions", farChange.getOrDefault(OW, Set.of()).equals(Set.of(Runs.pack(15, 15))));
        check("regions: the same map twice changes nothing", KingdomIndex.changedRegions(after, KingdomIndex.of(stateOf(List.of(edge), List.of()), false, 0)).isEmpty());
        // Negative coordinates fall in negative regions.
        MapModel.Kingdom west = square(new UUID(5, 5), "West", 0x0000FF, -40, -1, 2, false);
        KingdomIndex w = KingdomIndex.of(stateOf(List.of(west), List.of()), false, 0);
        check("regions: negative chunks are in negative regions", w.regionDrawn(OW, -2, -1) && w.regionDrawn(OW, -2, 0));
    }

    static void markerChunks() {
        MapModel.Marker drop = new MapModel.Marker("drop", "supply_drop", "Supply drop", OW, -1, 70, 33, 0xFFAA00, "", 0);
        MapState s = stateOf(List.of(), List.of(drop));
        KingdomIndex without = KingdomIndex.of(s, false, 0);
        KingdomIndex with = KingdomIndex.of(s, true, 0);
        check("markers: not drawn as chunks when the Minimap shows them", !without.drawn(OW, -1, 2));
        check("markers: drawn on their chunk otherwise", with.markerAt(OW, -1, 2) == drop && with.drawn(OW, -1, 2) && with.regionDrawn(OW, -1, 0));
        MapModel.Marker soon = new MapModel.Marker("soon", "k", "Soon", OW, 0, 0, 0, 0xFF0000, "", 500);
        MapState timed = MapState.EMPTY.apply(new MapModel.Snapshot(1, List.of(), List.of(soon)), 1_000);
        check("markers: an expired marker is not drawn", KingdomIndex.of(timed, true, 1_499).drawn(OW, 0, 0)
                && !KingdomIndex.of(timed, true, 1_500).drawn(OW, 0, 0));
    }

    // ---- words ----

    static void tooltips() {
        MapModel.Kingdom azrak = new MapModel.Kingdom(new UUID(1, 1), "Azrak", 0xFFD700, "Dwarf", false, false, null, List.of(), List.of());
        check("tooltips: the short line is the name", Tooltips.subtle(azrak).equals("Azrak"));
        check("tooltips: keep land", Tooltips.blunt(azrak, new KingdomIndex.Cell(0, MapModel.LandKind.KEEP, false)).equals("Keep of Azrak (Dwarf)"));
        check("tooltips: the keep's heart", Tooltips.blunt(azrak, new KingdomIndex.Cell(0, MapModel.LandKind.KEEP, true)).equals("Heart of the keep of Azrak (Dwarf)"));
        check("tooltips: an outpost", Tooltips.blunt(azrak, new KingdomIndex.Cell(0, MapModel.LandKind.OUTPOST, false)).equals("Outpost of Azrak (Dwarf)"));
        check("tooltips: held land", Tooltips.blunt(azrak, new KingdomIndex.Cell(0, MapModel.LandKind.CLAIM, false)).equals("Held by Azrak (Dwarf)"));
        MapModel.Kingdom spawn = new MapModel.Kingdom(new UUID(2, 2), "Hey Dol Hollow", 0xE0E0E0, "Hey Dol Hollow", true, false, null, List.of(), List.of());
        check("tooltips: the spawn", Tooltips.blunt(spawn, new KingdomIndex.Cell(0, MapModel.LandKind.KEEP, false)).equals("Spawn: Hey Dol Hollow"));
        MapModel.Kingdom same = new MapModel.Kingdom(new UUID(3, 3), "Solo", 1, "Solo", false, false, null, List.of(), List.of());
        check("tooltips: a race named like its kingdom is not repeated", Tooltips.blunt(same, new KingdomIndex.Cell(0, MapModel.LandKind.CLAIM, false)).equals("Held by Solo"));
        check("tooltips: initials", Tooltips.initials("Supply drop").equals("SD") && Tooltips.initials("camp").equals("C")
                && Tooltips.initials("  ").equals("?"));
        check("tooltips: a marker with no label shows its kind", Tooltips.marker(new MapModel.Marker("i", "event", "", OW, 0, 0, 0, 0, "", 0)).equals("event"));
        for (String s : new String[]{Tooltips.blunt(azrak, new KingdomIndex.Cell(0, MapModel.LandKind.KEEP, true)),
                Tooltips.blunt(spawn, new KingdomIndex.Cell(0, MapModel.LandKind.KEEP, false))}) {
            check("tooltips: no dashes in " + s, !s.contains("—") && !s.contains("--"));
        }
    }

    static void versions() {
        check("versions: same", Versions.atLeast("1.47.0", "1.47.0"));
        check("versions: newer patch", Versions.atLeast("1.47.2", "1.47.0") && Versions.atLeast("26.6.2", "26.6.0"));
        check("versions: older", !Versions.atLeast("1.46.4", "1.47.0") && !Versions.atLeast("26.5.3", "26.6.0"));
        check("versions: suffixes are ignored", Versions.atLeast("26.6.2+fabric", "26.6.0") && Versions.atLeast("1.47.0-beta", "1.47"));
        check("versions: numbers, not text", Versions.atLeast("1.100.0", "1.47.0"));
        check("versions: nonsense is never enough", !Versions.atLeast("abc", "1.0") && !Versions.atLeast(null, "1.0"));
    }

    // ---- the store ----

    static void store() {
        MapStore store = new MapStore();
        List<Map<String, Set<Long>>> redraws = new ArrayList<>();
        store.listen((before, after, state) -> redraws.add(KingdomIndex.changedRegions(before, after)));
        MapModel.Kingdom a = square(new UUID(1, 1), "Azrak", 0xFFD700, 0, 0, 3, true);
        byte[] snap = MapWire.write(new MapModel.Snapshot(5, List.of(a), List.of(new MapModel.Marker("m", "k", "M", OW, 0, 0, 0, 1, "", 1_000))));
        check("store: a snapshot applies", store.receive(snap, 10_000) == MapStore.Outcome.APPLIED && store.index().kingdomAt(OW, 1, 1) != null);
        check("store: and asks for the redraw of its region", redraws.size() == 1 && redraws.get(0).get(OW).contains(Runs.pack(0, 0)));
        check("store: the same snapshot again changes nothing", store.receive(snap, 10_000) == MapStore.Outcome.UNCHANGED && redraws.size() == 1);
        check("store: garbage is dropped and the map stays", store.receive(new byte[]{9, 9, 9}, 10_000) == MapStore.Outcome.BAD
                && store.index().kingdomAt(OW, 1, 1) != null && !store.lastError().isEmpty());
        byte[] stray = MapWire.write(new MapModel.Delta(99, 100, List.of(), List.of(a.id()), List.of(), List.of()));
        check("store: a delta out of order is dropped", store.receive(stray, 10_000) == MapStore.Outcome.OUT_OF_ORDER
                && store.index().kingdomAt(OW, 1, 1) != null);
        byte[] removal = MapWire.write(new MapModel.Delta(5, 6, List.of(), List.of(a.id()), List.of(), List.of()));
        check("store: a following delta applies", store.receive(removal, 10_000) == MapStore.Outcome.APPLIED
                && store.index().kingdomAt(OW, 1, 1) == null && store.state().seq() == 6);
        store.prune(10_999);
        check("store: a marker shows until its time", store.state().markers().size() == 1);
        store.prune(11_000);
        check("store: and is pruned at it", store.state().markers().isEmpty());
        store.clear();
        check("store: clear forgets everything", store.state().seq() == -1 && store.index() == KingdomIndex.EMPTY);
    }
}
