package charter.gametest;

import charter.api.MapMarkers;
import charter.id.OrgId;
import charter.land.Core;
import charter.mapwire.MapModel;
import charter.mapwire.MapState;
import charter.mapwire.MapWire;
import charter.mapwire.Runs;
import charter.mc.land.Fixtures;
import charter.mc.map.MapSync;
import charter.mc.map.MapView;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The kingdom map for Xaero's maps, through the live server: who is sent it (a player whose client listens, never a
 * vanilla client and never a Bedrock player even if the proxy claimed to listen), the snapshot on joining, the deltas
 * that follow a claim, a keep move and a marker, and the knobs.
 *
 * <p>No real client runs in a gametest, so a recorder stands in for Fabric's networking: it says which players
 * "listen" on the map channel and keeps what they were sent. It is installed once for the whole run and every test
 * looks only at its own players and its own kingdom, since other tests change land at the same time. Every test
 * finishes in one tick, so the knobs a test turns are back before any other test runs.
 */
public final class MapSyncGameTest {

    /** Stands in for Fabric's networking. */
    static final class Recorder implements MapSync.Sink {
        final Set<UUID> listening = ConcurrentHashMap.newKeySet();
        final Map<UUID, List<byte[]>> sent = new ConcurrentHashMap<>();

        @Override
        public boolean canReceive(ServerPlayer player) {
            return listening.contains(player.getUUID());
        }

        @Override
        public void send(ServerPlayer player, byte[] bytes) {
            sent.computeIfAbsent(player.getUUID(), id -> new ArrayList<>()).add(bytes);
        }

        List<MapWire.Message> messages(ServerPlayer player) {
            List<MapWire.Message> out = new ArrayList<>();
            for (byte[] b : sent.getOrDefault(player.getUUID(), List.of())) out.add(MapWire.read(b));
            return out;
        }
    }

    static final Recorder RECORDER = new Recorder();

    static {
        MapSync.sink(RECORDER);
    }

    /** A player whose client has the Charter Maps mod, joined and holding the map. */
    private static ServerPlayer modded(LandKit kit) {
        ServerPlayer player = kit.outsider();
        RECORDER.listening.add(player.getUUID());
        MapSync.join(player);
        kit.helper.assertTrue(MapSync.holds(player.getUUID()), "A player whose client listens was not sent the map");
        return player;
    }

    private static void leave(ServerPlayer player) {
        MapSync.forget(player.getUUID());
        RECORDER.listening.remove(player.getUUID());
    }

    /** What the player now knows: every message they were sent, applied in order; fails on a gap. */
    private static MapState known(LandKit kit, ServerPlayer player) {
        MapState state = MapState.EMPTY;
        List<MapWire.Message> messages = RECORDER.messages(player);
        kit.helper.assertTrue(!messages.isEmpty() && messages.get(0) instanceof MapModel.Snapshot,
                "The first message a player gets is not the snapshot");
        for (MapWire.Message m : messages) {
            kit.helper.assertTrue(state.accepts(m), "A delta did not follow on from the one before: " + m);
            state = state.apply(m, 0);
        }
        return state;
    }

    private static List<MapModel.Delta> deltas(ServerPlayer player) {
        List<MapModel.Delta> out = new ArrayList<>();
        for (MapWire.Message m : RECORDER.messages(player)) if (m instanceof MapModel.Delta d) out.add(d);
        return out;
    }

    /** The kingdom as the server would draw it right now. */
    private static MapModel.Kingdom current(LandKit kit, OrgId org) {
        return MapView.build(kit.rt.state, kit.rt.knobs).stream().filter(k -> k.id().equals(org.value())).findFirst().orElse(null);
    }

    private static void sync(LandKit kit) {
        // Tick 0 is always a rebuild tick.
        MapSync.tick(kit.server, kit.rt, 0);
    }

    @GameTest(maxTicks = 100)
    public void onlyAJavaPlayerWhoseClientListensIsSentTheMap(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("MapJoin");
            kit.keep(lord);
            OrgId org = kit.orgOf(lord);
            ServerPlayer vanilla = kit.outsider();
            // A Floodgate player: a UUID whose high half is zero. The recorder even claims it listens, as a proxy might.
            ServerPlayer bedrock = new ServerPlayer(kit.server, kit.level,
                    new GameProfile(new UUID(0L, System.nanoTime()), "MapBedrock"), ClientInformation.createDefault());
            RECORDER.listening.add(bedrock.getUUID());
            ServerPlayer modded = modded(kit);
            try {
                MapSync.join(vanilla);
                MapSync.join(bedrock);
                helper.assertTrue(!MapSync.holds(vanilla.getUUID()) && RECORDER.sent.get(vanilla.getUUID()) == null,
                        "A vanilla client was sent the map");
                helper.assertTrue(!MapSync.holds(bedrock.getUUID()) && RECORDER.sent.get(bedrock.getUUID()) == null,
                        "A Bedrock player was sent the map");
                // Fabric's own check on a client that never registered the channel says no, and does not throw.
                helper.assertTrue(!MapSync.REAL.canReceive(vanilla), "Fabric says a mock client listens on the map channel");

                List<MapWire.Message> got = RECORDER.messages(modded);
                helper.assertTrue(got.size() == 1 && got.get(0) instanceof MapModel.Snapshot, "The modded player did not get one snapshot: " + got);
                MapModel.Kingdom mine = ((MapModel.Snapshot) got.get(0)).kingdoms().stream()
                        .filter(k -> k.id().equals(org.value())).findFirst().orElse(null);
                helper.assertTrue(mine != null, "The snapshot does not hold the kingdom");
                helper.assertTrue(mine.equals(current(kit, org)), "The snapshot's kingdom is not the server's");
                Core keep = kit.rt.state.coresOf(org).stream().filter(Core::isKeep).findFirst().orElseThrow();
                Fixtures.Fixture nexus = Fixtures.of(kit.rt.state).first(keep.id, Fixtures.Kind.NEXUS);
                helper.assertTrue(mine.keep() != null && mine.keep().hasNexus() && mine.keep().nexusX() == nexus.x()
                        && mine.keep().nexusZ() == nexus.z() && mine.keep().coreChunkX() == keep.center.x(),
                        "The keep or its Nexus is wrong in the snapshot: " + mine.keep());
                long keepChunks = mine.lands().stream().filter(l -> l.kind() == MapModel.LandKind.KEEP).mapToLong(l -> Runs.count(l.runs())).sum();
                helper.assertTrue(keepChunks == kit.rt.state.claimsOf(org).size(), "The keep land is not every claim: " + keepChunks);

                // A second join sends nothing more.
                MapSync.join(modded);
                helper.assertTrue(RECORDER.messages(modded).size() == 1, "A second join sent the map again");
            } finally {
                leave(modded);
                RECORDER.listening.remove(bedrock.getUUID());
            }
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void aClaimAndAKeepMoveReachTheMapAsDeltas(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("MapMove");
            OrgId org = kit.orgOf(lord);
            ServerPlayer modded = modded(kit);
            ServerPlayer vanilla = kit.outsider();
            try {
                helper.assertTrue(known(kit, modded).kingdoms().get(org.value()) == null, "A kingdom with no land is on the map");

                kit.keep(lord);
                sync(kit);
                MapModel.Kingdom placed = known(kit, modded).kingdoms().get(org.value());
                helper.assertTrue(placed != null && placed.equals(current(kit, org)), "The new keep did not reach the map: " + placed);
                helper.assertTrue(deltas(modded).stream().anyMatch(d -> d.kingdoms().stream().anyMatch(k -> k.id().equals(org.value()))),
                        "The new keep did not come as a delta");

                int before = deltas(modded).size();
                sync(kit);
                helper.assertTrue(deltas(modded).stream().skip(before).noneMatch(d -> d.kingdoms().stream().anyMatch(k -> k.id().equals(org.value()))),
                        "A rebuild with no change sent the kingdom again");

                String name = kit.rt.state.org(org).orElseThrow().name();
                int newX = kit.nexusPos().getX() + 16;
                kit.server.getCommands().performPrefixedCommand(kit.server.createCommandSourceStack(),
                        "charter admin faction move \"" + name + "\" " + newX + " " + kit.y + " " + kit.nexusPos().getZ());
                sync(kit);
                MapModel.Kingdom moved = known(kit, modded).kingdoms().get(org.value());
                helper.assertTrue(moved != null && moved.keep() != null && moved.keep().coreChunkX() == (newX >> 4)
                        && moved.keep().hasNexus() && moved.keep().nexusX() == newX, "The keep move did not reach the map: " + moved);
                helper.assertTrue(moved.equals(current(kit, org)), "After the move the map differs from the server's");

                // A new tag colour moves no land, but the map follows it.
                Map<String, String> colours = kit.rt.knobs.tagColors;
                kit.rt.knobs.tagColors = new java.util.LinkedHashMap<>(colours);
                kit.rt.knobs.tagColors.put(MapView.colourKey(name), "#123456");
                try {
                    sync(kit);
                    helper.assertTrue(known(kit, modded).kingdoms().get(org.value()).colour() == 0x123456, "A new tag colour did not reach the map");
                } finally {
                    kit.rt.knobs.tagColors = colours;
                }
                helper.assertTrue(RECORDER.sent.get(vanilla.getUUID()) == null, "A vanilla client was sent a delta");
            } finally {
                leave(modded);
            }
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void markersReachTheMapAndLeaveIt(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer modded = modded(kit);
            ServerPlayer vanilla = kit.outsider();
            String id = "gt_drop_" + Long.toString(System.nanoTime(), 36);
            boolean markersWere = kit.rt.knobs.mapSyncMarkers;
            try {
                long now = kit.rt.clock.now();
                helper.assertTrue(MapMarkers.add(new MapMarkers.Marker(id, "supply_drop", "Supply drop", "minecraft:overworld",
                        10, 70, 20, 0xFFAA00, "chest", now + 600_000)), "The marker was refused");
                sync(kit);
                MapState state = known(kit, modded);
                MapState.Placed placed = state.markers().get(id);
                helper.assertTrue(placed != null && placed.marker().label().equals("Supply drop") && placed.marker().colour() == 0xFFAA00
                        && placed.marker().ttlMillis() > 0 && placed.marker().ttlMillis() <= 600_000, "The marker did not reach the map: " + placed);

                // Through the operator's command too.
                String cmdId = id + "_cmd";
                kit.server.getCommands().performPrefixedCommand(kit.server.createCommandSourceStack(),
                        "execute in minecraft:overworld positioned 5 64 -7 run charter admin mapmarker add " + cmdId + " event gold 0 Meet here");
                sync(kit);
                MapState.Placed fromCmd = known(kit, modded).markers().get(cmdId);
                helper.assertTrue(fromCmd != null && fromCmd.marker().label().equals("Meet here") && fromCmd.marker().x() == 5
                        && fromCmd.marker().z() == -7 && fromCmd.expiresAt() == 0, "The command's marker did not reach the map: " + fromCmd);
                kit.server.getCommands().performPrefixedCommand(kit.server.createCommandSourceStack(), "charter admin mapmarker remove " + cmdId);
                sync(kit);
                helper.assertTrue(known(kit, modded).markers().get(cmdId) == null, "A removed marker stayed on the map");

                // An expired marker leaves the map as a removal.
                MapMarkers.add(new MapMarkers.Marker(id, "supply_drop", "Supply drop", "minecraft:overworld", 10, 70, 20, 0xFFAA00, "chest", now - 1));
                sync(kit);
                helper.assertTrue(known(kit, modded).markers().get(id) == null, "An expired marker stayed on the map");

                // With markers off, a new marker is not sent.
                kit.rt.knobs.mapSyncMarkers = false;
                MapMarkers.add(new MapMarkers.Marker(id + "_off", "k", "Hidden", "minecraft:overworld", 0, 0, 0, 0, "", 0));
                sync(kit);
                helper.assertTrue(known(kit, modded).markers().get(id + "_off") == null, "A marker went out with mapSyncMarkers off");
                helper.assertTrue(RECORDER.sent.get(vanilla.getUUID()) == null, "A vanilla client was sent a marker");
            } finally {
                kit.rt.knobs.mapSyncMarkers = markersWere;
                MapMarkers.remove(id);
                MapMarkers.remove(id + "_off");
                MapMarkers.remove(id + "_cmd");
                leave(modded);
            }
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void withTheSyncOffNobodyIsSentTheMap(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            boolean was = kit.rt.knobs.mapSyncEnabled;
            ServerPlayer player = kit.outsider();
            RECORDER.listening.add(player.getUUID());
            try {
                kit.rt.knobs.mapSyncEnabled = false;
                MapSync.join(player);
                helper.assertTrue(!MapSync.holds(player.getUUID()) && RECORDER.sent.get(player.getUUID()) == null,
                        "The map went out with mapSyncEnabled off");
                kit.rt.knobs.mapSyncEnabled = true;
                MapSync.join(player);
                helper.assertTrue(MapSync.holds(player.getUUID()), "With the sync back on the player was not sent the map");
            } finally {
                kit.rt.knobs.mapSyncEnabled = was;
                leave(player);
            }
            helper.succeed();
        }
    }
}
