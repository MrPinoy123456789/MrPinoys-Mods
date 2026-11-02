package charter.mc;

import charter.api.Area;
import charter.api.CharterAreas;
import charter.api.MapMarkers;
import charter.config.Knobs;
import charter.id.ChunkKey;
import charter.land.CampPhase;
import charter.land.Claim;
import charter.land.ClaimKind;
import charter.land.Core;
import charter.land.CoreKind;
import charter.mapwire.MapModel;
import charter.mapwire.MapState;
import charter.mapwire.MapWire;
import charter.mapwire.Runs;
import charter.mc.land.Fixtures;
import charter.mc.map.MapLedger;
import charter.mc.map.MapView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static charter.mc.FabricSuite.check;

/**
 * The kingdom map for Xaero's maps, without a server: row runs, the wire codec and its defences, the receiver's state,
 * the server's ledger of deltas, and the map built from a real state (what it shows and what it must not).
 */
final class MapSyncTest {

    private static final String OW = "minecraft:overworld";

    private MapSyncTest() {
    }

    static void run() {
        runs();
        codecRoundTrip();
        codecRefusesBadBytes();
        receiverState();
        ledger();
        viewOfAState();
        markersApi();
    }

    // ---- row runs ----

    static void runs() {
        List<Long> keep = new ArrayList<>();
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) keep.add(Runs.pack(100 + x, -200 + z));
        long[] keepChunks = keep.stream().mapToLong(Long::longValue).toArray();
        List<MapModel.ChunkRun> runs = Runs.encode(keepChunks);
        check("map runs: a 9 by 9 keep is nine runs of nine", runs.size() == 9 && runs.stream().allMatch(r -> r.length() == 9));
        check("map runs: sorted by z then x", runs.get(0).z() == -204 && runs.get(8).z() == -196 && runs.get(0).x0() == 96);
        check("map runs: expand gives back the same set", sameSet(keepChunks, Runs.expand(runs)));

        long[] gappy = {Runs.pack(0, 0), Runs.pack(1, 0), Runs.pack(3, 0), Runs.pack(3, 0), Runs.pack(-1, 5), Runs.pack(2, 0)};
        List<MapModel.ChunkRun> g = Runs.encode(gappy);
        check("map runs: touching chunks join, a gap splits, duplicates count once",
                g.size() == 2 && g.get(0).equals(new MapModel.ChunkRun(0, 0, 4)) && g.get(1).equals(new MapModel.ChunkRun(5, -1, 1)));
        check("map runs: count", Runs.count(g) == 5);
        check("map runs: nothing encodes to nothing", Runs.encode(new long[0]).isEmpty());
        check("map runs: pack keeps negative coordinates", Runs.x(Runs.pack(-30_000_000, 29_999_999)) == -30_000_000
                && Runs.z(Runs.pack(-30_000_000, 29_999_999)) == 29_999_999);
    }

    private static boolean sameSet(long[] a, long[] b) {
        Set<Long> sa = new HashSet<>();
        for (long v : a) sa.add(v);
        Set<Long> sb = new HashSet<>();
        for (long v : b) sb.add(v);
        return sa.equals(sb) && b.length == sb.size();
    }

    // ---- the codec ----

    static MapModel.Kingdom sampleKingdom(UUID id, String name, int colour) {
        List<Long> chunks = new ArrayList<>();
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) chunks.add(Runs.pack(-63 + x, -110 + z));
        List<MapModel.Land> lands = List.of(
                new MapModel.Land(OW, MapModel.LandKind.KEEP, Runs.encode(chunks.stream().mapToLong(Long::longValue).toArray())),
                new MapModel.Land(OW, MapModel.LandKind.OUTPOST, Runs.encode(new long[]{Runs.pack(-40, -110), Runs.pack(-39, -110)})),
                new MapModel.Land("minecraft:the_nether", MapModel.LandKind.CLAIM, Runs.encode(new long[]{Runs.pack(5, 5)})));
        MapModel.Keep keep = new MapModel.Keep(OW, -63, -110, true, -1000, 71, -1752);
        return new MapModel.Kingdom(id, name, colour, "Dwarf", false, false, keep, lands,
                List.of(new MapModel.Point(MapModel.Point.OUTPOST, OW, -632, 64, -1752)));
    }

    static void codecRoundTrip() {
        MapModel.Kingdom azrak = sampleKingdom(new UUID(1, 2), "Azrak", 0xFFD700);
        MapModel.Kingdom spawn = new MapModel.Kingdom(new UUID(3, 4), "Hey Dol Hollow", 0xE0E0E0, "Hey Dol Hollow", true, false,
                new MapModel.Keep(OW, 0, 0, false, 0, 0, 0),
                List.of(new MapModel.Land(OW, MapModel.LandKind.KEEP, Runs.encode(new long[]{Runs.pack(0, 0)}))), List.of());
        MapModel.Kingdom raiders = new MapModel.Kingdom(new UUID(5, 6), "Raiders", 0x5A5A5A, "Raiders", false, true, null,
                List.of(new MapModel.Land(OW, MapModel.LandKind.KEEP, Runs.encode(new long[]{Runs.pack(900, 900)}))), List.of());
        MapModel.Marker drop = new MapModel.Marker("drop_1", "supply_drop", "Supply drop é", OW, 120, -5, -340, 0xFFAA00, "chest", 900_000);
        MapModel.Snapshot snap = new MapModel.Snapshot(7, List.of(azrak, spawn, raiders), List.of(drop));
        byte[] bytes = MapWire.write(snap);
        check("map wire: a snapshot reads back equal", snap.equals(MapWire.read(bytes)));
        int azrakBytes = MapWire.kingdomBytes(azrak).length;
        check("map wire: a 9 by 9 keep kingdom with an outpost and land in two dimensions is " + azrakBytes + " bytes, under 160",
                azrakBytes < 160);

        MapModel.Delta delta = new MapModel.Delta(7, 8, List.of(azrak), List.of(new UUID(9, 9)), List.of(drop), List.of("old"));
        check("map wire: a delta reads back equal", delta.equals(MapWire.read(MapWire.write(delta))));
        MapModel.Delta empty = new MapModel.Delta(8, 9, List.of(), List.of(), List.of(), List.of());
        check("map wire: an empty delta reads back equal", empty.equals(MapWire.read(MapWire.write(empty))) && empty.isEmpty());

        // A large, ragged kingdom: a disc of radius 12 chunks, about 450 chunks, stays a few hundred bytes.
        List<Long> disc = new ArrayList<>();
        for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) if (x * x + z * z <= 144) disc.add(Runs.pack(x, z));
        MapModel.Kingdom big = new MapModel.Kingdom(new UUID(7, 7), "Big", 0x123456, "Big", false, false, null,
                List.of(new MapModel.Land(OW, MapModel.LandKind.CLAIM, Runs.encode(disc.stream().mapToLong(Long::longValue).toArray()))), List.of());
        int size = MapWire.kingdomBytes(big).length;
        check("map wire: a kingdom of " + disc.size() + " chunks is " + size + " bytes, under 200", disc.size() > 400 && size < 200);
        check("map wire: extreme coordinates survive", roundTripsAt(-29_999_984 >> 4, 29_999_984 >> 4));
    }

    private static boolean roundTripsAt(int cx, int cz) {
        MapModel.Kingdom k = new MapModel.Kingdom(new UUID(-1, -1), "Edge", 0xFFFFFF, "Edge", false, false,
                new MapModel.Keep(OW, cx, cz, true, cx * 16, -64, cz * 16),
                List.of(new MapModel.Land(OW, MapModel.LandKind.KEEP, Runs.encode(new long[]{Runs.pack(cx, cz), Runs.pack(-cx, -cz)}))),
                List.of(new MapModel.Point("outpost", OW, Integer.MIN_VALUE, Integer.MAX_VALUE, 0)));
        MapModel.Snapshot s = new MapModel.Snapshot(Long.MAX_VALUE, List.of(k), List.of());
        return s.equals(MapWire.read(MapWire.write(s)));
    }

    static void codecRefusesBadBytes() {
        byte[] good = MapWire.write(new MapModel.Snapshot(1, List.of(sampleKingdom(new UUID(1, 1), "A", 1)), List.of()));
        check("map wire: nothing is refused", refused(new byte[0]) && refused(null));
        check("map wire: every truncation is refused", allTruncationsRefused(good));
        byte[] newer = good.clone();
        newer[0] = (byte) (MapWire.FORMAT + 1);
        check("map wire: a newer format is refused", refused(newer));
        byte[] trailing = Arrays.copyOf(good, good.length + 1);
        check("map wire: trailing bytes are refused", refused(trailing));
        byte[] unknownType = good.clone();
        unknownType[1] = 9;
        check("map wire: an unknown type is refused", refused(unknownType));
        // A count of two billion kingdoms in a ten byte message must not allocate anything.
        byte[] huge = {1, 0, 0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07, 0, 0};
        check("map wire: an impossible count is refused", refused(huge));
        // Random noise only ever fails as a bad message, never as anything else.
        Random random = new Random(42);
        boolean onlyBad = true;
        for (int i = 0; i < 2000 && onlyBad; i++) {
            byte[] noise = new byte[2 + random.nextInt(64)];
            random.nextBytes(noise);
            noise[0] = 1;
            noise[1] = (byte) random.nextInt(2);
            try {
                MapWire.read(noise);
            } catch (MapWire.BadMessage expected) {
                // fine
            } catch (RuntimeException other) {
                onlyBad = false;
            }
        }
        check("map wire: random bytes fail only as a bad message", onlyBad);
        // Flipping any single byte of a good message never throws anything but a bad message.
        boolean flips = true;
        for (int i = 0; i < good.length && flips; i++) {
            byte[] bent = good.clone();
            bent[i] ^= (byte) 0xA5;
            try {
                MapWire.read(bent);
            } catch (MapWire.BadMessage expected) {
                // fine
            } catch (RuntimeException other) {
                flips = false;
            }
        }
        check("map wire: a bent byte fails only as a bad message", flips);
    }

    private static boolean allTruncationsRefused(byte[] good) {
        for (int n = 0; n < good.length; n++) {
            if (!refused(Arrays.copyOf(good, n))) return false;
        }
        return true;
    }

    private static boolean refused(byte[] bytes) {
        try {
            MapWire.read(bytes);
            return false;
        } catch (MapWire.BadMessage expected) {
            return true;
        }
    }

    // ---- the receiver's state ----

    static void receiverState() {
        MapModel.Kingdom a = sampleKingdom(new UUID(1, 1), "A", 1);
        MapModel.Kingdom b = sampleKingdom(new UUID(2, 2), "B", 2);
        MapModel.Marker forever = new MapModel.Marker("f", "k", "Forever", OW, 0, 0, 0, 0, "", 0);
        MapModel.Marker soon = new MapModel.Marker("s", "k", "Soon", OW, 0, 0, 0, 0, "", 5_000);
        long clientNow = 50_000;
        MapState s = MapState.EMPTY.apply(new MapModel.Snapshot(4, List.of(a, b), List.of(forever, soon)), clientNow);
        check("map state: a snapshot sets everything", s.seq() == 4 && s.kingdoms().size() == 2 && s.markers().size() == 2);
        check("map state: time left becomes the receiver's own clock", s.markers().get("s").expiresAt() == 55_000
                && s.markers().get("f").expiresAt() == 0);

        MapModel.Kingdom a2 = sampleKingdom(new UUID(1, 1), "A renamed", 3);
        MapModel.Delta d = new MapModel.Delta(4, 5, List.of(a2), List.of(new UUID(2, 2)), List.of(), List.of("f"));
        check("map state: a following delta is accepted", s.accepts(d));
        MapState s2 = s.apply(d, clientNow);
        check("map state: a delta replaces a kingdom, removes one and removes a marker", s2.seq() == 5
                && s2.kingdoms().size() == 1 && s2.kingdoms().get(new UUID(1, 1)).name().equals("A renamed")
                && s2.markers().keySet().equals(Set.of("s")));
        check("map state: the old state is untouched", s.kingdoms().size() == 2 && s.markers().size() == 2);
        MapModel.Delta stray = new MapModel.Delta(9, 10, List.of(b), List.of(), List.of(), List.of());
        check("map state: a delta that does not follow is ignored", !s2.accepts(stray) && s2.apply(stray, clientNow) == s2);
        check("map state: a snapshot always applies", s2.accepts(new MapModel.Snapshot(1, List.of(), List.of())));

        check("map state: a marker still shows before its time", s2.liveMarkers(54_999).size() == 1 && s2.prune(54_999) == s2);
        check("map state: and is gone at its time", s2.liveMarkers(55_000).isEmpty() && s2.prune(55_000).markers().isEmpty());
    }

    // ---- the server's ledger ----

    static MapMarkers.Marker serverMarker(String id, long expiresAt) {
        return new MapMarkers.Marker(id, "supply_drop", "Drop " + id, OW, 1, 2, 3, 0xFFAA00, "", expiresAt);
    }

    static void ledger() {
        MapLedger ledger = new MapLedger();
        MapModel.Kingdom a = sampleKingdom(new UUID(1, 1), "A", 1);
        MapModel.Kingdom b = sampleKingdom(new UUID(2, 2), "B", 2);
        long now = 1_000_000;
        MapModel.Delta first = ledger.advance(List.of(a, b), List.of(serverMarker("m", now + 60_000)), now);
        check("map ledger: the first advance reports everything", first != null && first.kingdoms().size() == 2
                && first.markers().size() == 1 && first.fromSeq() == 0 && first.toSeq() == 1 && ledger.seq() == 1);
        check("map ledger: a marker's time left is sent, not its clock time", first.markers().get(0).ttlMillis() == 60_000);

        check("map ledger: nothing changed sends nothing, even as markers run down",
                ledger.advance(List.of(a, b), List.of(serverMarker("m", now + 60_000)), now + 30_000) == null && ledger.seq() == 1);

        MapModel.Kingdom bRecoloured = sampleKingdom(new UUID(2, 2), "B", 0x00FF00);
        MapModel.Delta recolour = ledger.advance(List.of(a, bRecoloured), List.of(serverMarker("m", now + 60_000)), now);
        check("map ledger: a new colour sends that kingdom alone", recolour != null && recolour.kingdoms().equals(List.of(bRecoloured))
                && recolour.removedKingdoms().isEmpty() && recolour.markers().isEmpty() && recolour.fromSeq() == 1 && recolour.toSeq() == 2);

        MapModel.Delta gone = ledger.advance(List.of(a), List.of(), now);
        check("map ledger: a kingdom with no land and a removed marker go as removals", gone != null
                && gone.removedKingdoms().equals(List.of(new UUID(2, 2))) && gone.removedMarkers().equals(List.of("m"))
                && gone.kingdoms().isEmpty());

        ledger.advance(List.of(a), List.of(serverMarker("x", now + 10)), now);
        MapModel.Delta expired = ledger.advance(List.of(a), List.of(serverMarker("x", now + 10)), now + 10);
        check("map ledger: an expired marker goes as a removal", expired != null && expired.removedMarkers().equals(List.of("x")));

        // A receiver that took the snapshot and every delta since holds exactly the current map.
        MapLedger l2 = new MapLedger();
        l2.advance(List.of(a, b), List.of(), now);
        MapState client = MapState.EMPTY.apply(l2.snapshot(List.of(a, b), List.of(serverMarker("p", 0)), now), 0);
        MapModel.Delta next = l2.advance(List.of(a, bRecoloured), List.of(serverMarker("p", 0)), now);
        client = client.apply(next, 0);
        MapState expected = MapState.EMPTY.apply(new MapModel.Snapshot(l2.seq(), List.of(a, bRecoloured),
                List.of(new MapModel.Marker("p", "supply_drop", "Drop p", OW, 1, 2, 3, 0xFFAA00, "", 0))), 0);
        check("map ledger: snapshot plus deltas equals the current map", client.equals(expected));
    }

    // ---- the map built from a state ----

    static void viewOfAState() {
        FullState s = FullState.build();
        Fixtures.of(s.state).add(new Fixtures.Fixture(Fixtures.Kind.NEXUS, OW, 8, 70, 8, s.keep.id));
        s.knobs.tagColors = new java.util.LinkedHashMap<>(Map.of(MapView.colourKey(s.state.org(s.oakvale).orElseThrow().name()), "#89CFF0"));
        List<MapModel.Kingdom> view = MapView.build(s.state, s.knobs);

        // The hidden information yardstick: exactly the chunks the public land map holds, no more and no fewer.
        CharterAreas.publish(s.state);
        Set<String> published = new HashSet<>();
        for (Area area : CharterAreas.areas()) {
            for (Area.Chunk c : area.chunks()) published.add(area.dimension() + " " + c.x() + " " + c.z() + " " + area.kind());
        }
        Set<String> sent = new HashSet<>();
        for (MapModel.Kingdom k : view) {
            for (MapModel.Land l : k.lands()) {
                for (long c : Runs.expand(l.runs())) sent.add(l.dimension() + " " + Runs.x(c) + " " + Runs.z(c) + " " + l.kind());
            }
        }
        check("map view: exactly the chunks and kinds the public land map holds", published.equals(sent) && !sent.isEmpty());
        CharterAreas.clear();

        MapModel.Kingdom oak = view.stream().filter(k -> k.id().equals(s.oakvale.value())).findFirst().orElse(null);
        check("map view: the keep's kingdom is listed by its id and name", oak != null && oak.name().equals(s.state.org(s.oakvale).orElseThrow().name()));
        check("map view: its colour is its tag colour", oak != null && oak.colour() == 0x89CFF0);
        check("map view: its keep and Nexus are there", oak != null && oak.keep() != null && oak.keep().coreChunkX() == 0
                && oak.keep().hasNexus() && oak.keep().nexusY() == 70);
        check("map view: a contested claim is plain held land, with no grace sent", oak != null && oak.lands().stream()
                .anyMatch(l -> l.kind() == MapModel.LandKind.CLAIM && Runs.count(l.runs()) == 1));

        MapModel.Kingdom iron = view.stream().filter(k -> k.id().equals(s.ironhold.value())).findFirst().orElse(null);
        check("map view: a raising outpost is a point and outpost land, with no phase", iron != null && iron.keep() == null
                && iron.points().size() == 1 && iron.points().get(0).x() == 6 * 16 + 8
                && iron.lands().size() == 1 && iron.lands().get(0).kind() == MapModel.LandKind.OUTPOST);
        check("map view: a kingdom without a tag colour still gets a steady one", iron != null
                && iron.colour() == MapView.build(s.state, s.knobs).stream().filter(k -> k.id().equals(s.ironhold.value())).findFirst().orElseThrow().colour());

        // A lifted Nexus: its record is gone, so the keep has no Nexus position (it never follows its carrier).
        Fixtures.of(s.state).removeCore(s.keep.id);
        MapModel.Kingdom lifted = MapView.build(s.state, s.knobs).stream().filter(k -> k.id().equals(s.oakvale.value())).findFirst().orElseThrow();
        check("map view: a lifted Nexus has no position", lifted.keep() != null && !lifted.keep().hasNexus());

        // The spawn kingdom is flagged; the raiders are outline only.
        var oakOrg = s.state.org(s.oakvale).orElseThrow();
        oakOrg.adminKingdom = true;
        s.knobs.tagColors = new java.util.LinkedHashMap<>();
        MapModel.Kingdom spawn = MapView.build(s.state, s.knobs).stream().filter(k -> k.id().equals(s.oakvale.value())).findFirst().orElseThrow();
        check("map view: the admin kingdom is flagged and light grey without a tag colour", spawn.admin() && spawn.colour() == 0xE0E0E0);
        oakOrg.adminKingdom = false;
        oakOrg.npc = true;
        MapModel.Kingdom raiders = MapView.build(s.state, s.knobs).stream().filter(k -> k.id().equals(s.oakvale.value())).findFirst().orElseThrow();
        check("map view: raiders are outline only", raiders.npc() && raiders.keep() == null && raiders.points().isEmpty()
                && !raiders.lands().isEmpty());
        oakOrg.npc = false;

        // A packed outpost is no point (its land, if any, still shows as the land map has it).
        s.camp.setPhase(CampPhase.PACKED, 0);
        MapModel.Kingdom packed = MapView.build(s.state, s.knobs).stream().filter(k -> k.id().equals(s.ironhold.value())).findFirst().orElseThrow();
        check("map view: a packed outpost is no point", packed.points().isEmpty());

        // Sub-faction land is drawn as its kingdom's.
        Core subKeep = new Core(s.state.ids.core(), CoreKind.CAMP, s.guard, new ChunkKey(OW, 20, 20), s.state.takeCoreOrder(), 0, CampPhase.ACTIVE, 0);
        s.state.addCore(subKeep);
        s.state.addClaim(new Claim(s.state.ids.claim(), subKeep.center, s.guard, ClaimKind.CAMP, subKeep.id, 0, 0));
        List<MapModel.Kingdom> withSub = MapView.build(s.state, s.knobs);
        check("map view: a sub-faction's land is its kingdom's", withSub.stream().noneMatch(k -> k.id().equals(s.guard.value()))
                && withSub.stream().filter(k -> k.id().equals(s.ironhold.value())).findFirst().orElseThrow().points().size() == 1);

        Knobs plain = new Knobs();
        check("map view: tag colours parse with or without #", MapView.parseColour("#FF6A1A") == 0xFF6A1A
                && MapView.parseColour("ff6a1a") == 0xFF6A1A && MapView.parseColour("nope") == -1 && MapView.parseColour(null) == -1);
        check("map view: the shipped colours are the kingdoms'", MapView.colourOf(plain, "Azrak", false, false) == 0xFFD700
                && MapView.colourOf(plain, "Aurelia", false, false) == 0xFF6A1A && MapView.colourOf(plain, "Everdelle", false, false) == 0xFF69B4
                && MapView.colourOf(plain, "Azurius", false, false) == 0x89CFF0 && MapView.colourOf(plain, "Faeloria", false, false) == 0xA020F0);
        check("map view: the colour key is the tag key", MapView.colourKey("Azrak").equals("azrak"));
    }

    // ---- the public marker API ----

    static void markersApi() {
        MapMarkers.clear();
        long v0 = MapMarkers.version();
        check("map markers: add", MapMarkers.add(serverMarker("a", 0)) && MapMarkers.version() > v0 && MapMarkers.list().size() == 1);
        check("map markers: the same id replaces", MapMarkers.add(serverMarker("a", 5)) && MapMarkers.list().size() == 1
                && MapMarkers.get("a").orElseThrow().expiresAt() == 5);
        check("map markers: a blank id is refused", !MapMarkers.add(serverMarker(" ", 0)));
        check("map markers: a long label is refused", !MapMarkers.add(new MapMarkers.Marker("b", "k", "x".repeat(129), OW, 0, 0, 0, 0, "", 0)));
        check("map markers: colour is kept to RGB", new MapMarkers.Marker("c", null, null, OW, 0, 0, 0, 0xFF123456, null, 0).colour() == 0x123456);
        check("map markers: prune drops the expired", MapMarkers.prune(5) == 1 && MapMarkers.list().isEmpty());
        for (int i = 0; i < MapMarkers.MAX_MARKERS; i++) MapMarkers.add(serverMarker("n" + i, 0));
        check("map markers: no more than the ceiling", !MapMarkers.add(serverMarker("one_more", 0))
                && MapMarkers.add(serverMarker("n0", 0)));
        check("map markers: remove", MapMarkers.remove("n0") && !MapMarkers.remove("n0"));
        MapMarkers.clear();
        check("map markers: clear", MapMarkers.list().isEmpty());
    }
}
