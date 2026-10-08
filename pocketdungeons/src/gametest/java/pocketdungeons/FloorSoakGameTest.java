package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The floor soak: plans every floor of every Act 1 and Act 2 dungeon over many seeds, the way a real
 * door preview does (the dungeon's node as the floor context, its theme, its room bias, the pilgrim
 * bag tags, the configured path lengths and attempt budget), and checks what must hold for a floor to
 * be finishable. A finding here is a floor a player could be stuck on.
 *
 * <p>Hard failures (the test fails and names the seed):
 * <ul>
 *   <li>a floor cannot be planned at all;</li>
 *   <li>a cell is not reachable from the entrance;</li>
 *   <li>a room that holds a required spawner can only be reached through a rubble plug;</li>
 *   <li>a toll room (a trial key toll) is the floor's only spawner room, so no key can ever pay it;</li>
 *   <li>more than one rubble plug, or a plug on the route to the exit;</li>
 *   <li>a room that is only meant for final floors turns up elsewhere.</li>
 * </ul>
 * Soft findings (logged and written to {@code floor-soak-report.md} in the run directory, never
 * failing): floors that fit fewer node rooms than the dungeon promises, final floors that drew no
 * final room, floors with no spawner, and rooms bound to the dungeon that never rolled.
 *
 * <p>Seeds per floor default to 60; set the environment variable {@code PD_SOAK_SEEDS} for a longer
 * soak. It plans only (there is no dungeon dimension here); stamping is covered by
 * {@code dungeonLoadTest}.
 */
public final class FloorSoakGameTest {

    private static final int DEFAULT_SEEDS = 60;

    // Act 1
    @GameTest(maxTicks = 100) public void mineshaft(GameTestHelper h) { soak(h, "mineshaft"); }
    @GameTest(maxTicks = 100) public void ossuary(GameTestHelper h) { soak(h, "ossuary"); }
    @GameTest(maxTicks = 100) public void infestation(GameTestHelper h) { soak(h, "infestation"); }
    @GameTest(maxTicks = 100) public void rootworks(GameTestHelper h) { soak(h, "rootworks"); }
    @GameTest(maxTicks = 100) public void endlessMine(GameTestHelper h) { soak(h, "endless_mine"); }
    @GameTest(maxTicks = 100) public void spawnerDungeon(GameTestHelper h) { soak(h, "spawner_dungeon"); }
    // Act 2
    @GameTest(maxTicks = 100) public void copperWorks(GameTestHelper h) { soak(h, "copper_works"); }
    @GameTest(maxTicks = 100) public void deepslate(GameTestHelper h) { soak(h, "deepslate"); }
    @GameTest(maxTicks = 100) public void frostworks(GameTestHelper h) { soak(h, "frostworks"); }
    @GameTest(maxTicks = 100) public void cowPits(GameTestHelper h) { soak(h, "cow_pits"); }
    @GameTest(maxTicks = 100) public void ancientCity(GameTestHelper h) { soak(h, "ancient_city"); }

    private static int seeds() {
        String value = System.getenv("PD_SOAK_SEEDS");
        if (value != null) {
            try {
                return Math.max(1, Integer.parseInt(value.trim()));
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return DEFAULT_SEEDS;
    }

    private static void soak(GameTestHelper helper, String id) {
        DungeonDef def = DungeonDefs.current().byId("pocketdungeons:" + id);
        helper.assertTrue(def != null, "dungeon " + id + " is loaded");
        int seedsPerFloor = seeds();
        List<String> hard = new ArrayList<>();
        List<String> soft = new ArrayList<>();
        Map<String, Integer> placed = new TreeMap<>();
        Set<String> bound = new TreeSet<>();
        int plans = 0;
        int resourceShortPlans = 0;
        int noSpawnerPlans = 0;
        int finalFloors = 0;
        int finalFloorsWithoutFinalRoom = 0;
        int finalRoomsAvailable = 0;

        boolean endless = def.kind() == DungeonDef.Kind.ENDLESS;
        int nodeIndex = 0;
        for (DungeonDef.Node node : def.nodes()) {
            nodeIndex++;
            String effectiveTheme = node.overridesTheme() ? node.theme() : def.mainTheme();
            ThemeManifest.Entry theme = ThemeManifest.current().byId(effectiveTheme);
            String roomTheme = theme == null ? null : theme.meta().roomTheme;
            boolean[] sideVariants = hasSideEdgeInto(def, node) ? new boolean[] {false, true} : new boolean[] {false};
            for (boolean side : sideVariants) {
                RoomEligibility.Floor floor = endless ? null : new RoomEligibility.Floor(
                        def.id(), def.mainTheme(), roomTheme, def.act(), def.kind() == DungeonDef.Kind.CAPSTONE,
                        effectiveTheme, borrowedFrom(def, node), node.layer() == 1, node.isFinal(), side,
                        def.minNodeRooms(node));
                if (floor != null) {
                    for (RoomManifest.Entry entry : RoomManifest.current().rooms()) {
                        if (RoomEligibility.eligible(RoomEligibility.RoomTags.of(entry.meta), floor)
                                && RoomEligibility.boundTo(RoomEligibility.RoomTags.of(entry.meta), floor)) {
                            bound.add(entry.name.replace("pocketdungeons:", ""));
                        }
                    }
                }
                boolean hasFinalRoomPool = floor != null && node.isFinal() && RoomManifest.current().rooms().stream()
                        .anyMatch(e -> e.meta.graphRole.contains("final")
                                && RoomEligibility.eligible(RoomEligibility.RoomTags.of(e.meta), floor)
                                && RoomEligibility.boundTo(RoomEligibility.RoomTags.of(e.meta), floor));
                if (hasFinalRoomPool) {
                    finalRoomsAvailable++;
                }
                for (int s = 0; s < seedsPerFloor; s++) {
                    long seed = 100_000L * nodeIndex + 1_000L * (side ? 1 : 0) + s * 7919L + 13;
                    String where = id + "/" + node.id() + (side ? "(side)" : "") + " seed " + seed;
                    LayoutPlanner.Outcome outcome = PlaytestBias.withNodeBias(node.roomBias(), () -> LayoutPlanner.plan(
                            seed, RoomManifest.current(), PocketDungeonsConfig.planAttemptBudget(),
                            PocketDungeonsConfig.pathLengthMin(), PocketDungeonsConfig.pathLengthMax(),
                            PocketDungeonsConfig.branchProbability(), PocketDungeonsConfig.loopProbability(),
                            PocketDungeonsConfig.maxGridSpan(), roomTheme, null, BagTags.pilgrim(1), null,
                            node.layer() == 1 ? PocketDungeonsConfig.firstFloorMaxEncounters() : 0, floor));
                    plans++;
                    DungeonPlan plan = outcome.plan();
                    if (plan == null) {
                        hard.add(where + ": cannot be planned (" + outcome.failureReason() + ")");
                        continue;
                    }
                    check(plan, floor, def, node, where, hard, soft, placed);
                    int needed = def.minNodeRooms(node);
                    if (needed > 0 && countNodeRooms(plan) < needed) {
                        resourceShortPlans++;
                    }
                    int spawnerRooms = spawnerCells(plan).size();
                    if (spawnerRooms == 0) {
                        noSpawnerPlans++;
                    }
                    if (node.isFinal() && hasFinalRoomPool) {
                        finalFloors++;
                        boolean got = plan.rooms().values().stream().anyMatch(r -> {
                            RoomManifest.Entry e = RoomManifest.current().byName(r.name());
                            return e != null && e.meta.graphRole.contains("final");
                        });
                        if (!got) {
                            finalFloorsWithoutFinalRoom++;
                        }
                    }
                }
            }
        }

        Set<String> neverRolled = new TreeSet<>();
        for (String room : bound) {
            if (!placed.containsKey(room)) {
                neverRolled.add(room);
            }
        }
        StringBuilder report = new StringBuilder();
        report.append("## ").append(id).append(" (act ").append(def.act()).append(")\n\n");
        report.append("- floors planned: ").append(plans).append(" (").append(seedsPerFloor).append(" seeds per floor)\n");
        report.append("- hard findings: ").append(hard.size()).append('\n');
        report.append("- floors short of the promised node rooms: ").append(resourceShortPlans).append('\n');
        report.append("- floors with no spawner room: ").append(noSpawnerPlans).append('\n');
        if (finalRoomsAvailable > 0) {
            report.append("- final floors that drew no final room: ").append(finalFloorsWithoutFinalRoom)
                    .append(" of ").append(finalFloors).append('\n');
        }
        report.append("- bound rooms that never rolled (").append(neverRolled.size()).append(" of ").append(bound.size())
                .append("): ").append(neverRolled.isEmpty() ? "none" : String.join(", ", neverRolled)).append('\n');
        for (String line : hard) {
            report.append("  - HARD: ").append(line).append('\n');
        }
        for (String line : soft) {
            report.append("  - note: ").append(line).append('\n');
        }
        report.append('\n');
        write(report.toString());
        PocketDungeonsMod.LOG.info("Floor soak {}: {} plans, {} hard, {} short of nodes, {} without a spawner, {} never rolled",
                id, plans, hard.size(), resourceShortPlans, noSpawnerPlans, neverRolled.size());
        if (!hard.isEmpty()) {
            helper.fail(hard.size() + " hard finding(s) in " + id + "; first: " + hard.get(0));
            return;
        }
        helper.succeed();
    }

    // ---- checks on one plan ----------------------------------------------------------------

    private static void check(DungeonPlan plan, RoomEligibility.Floor floor, DungeonDef def, DungeonDef.Node node,
                              String where, List<String> hard, List<String> soft, Map<String, Integer> placed) {
        for (DungeonPlan.PlacedRoom room : plan.rooms().values()) {
            placed.merge(room.name().replace("pocketdungeons:", ""), 1, Integer::sum);
            RoomManifest.Entry entry = RoomManifest.current().byName(room.name());
            if (entry != null && floor != null && !node.isFinal() && entry.meta.graphRole.contains("final")) {
                hard.add(where + ": final-only room " + room.name() + " on a non-final floor");
            }
        }
        if (plan.rubbleEdges().size() > 1) {
            hard.add(where + ": " + plan.rubbleEdges().size() + " rubble plugs on one floor");
        }
        // Reachability from the entrance over every door, then over doors that are not rubble plugs.
        Set<PlanCell> all = reach(plan, plan.entrance(), false);
        if (!all.containsAll(plan.cells())) {
            hard.add(where + ": " + (plan.cells().size() - all.size()) + " cell(s) are not reachable from the entrance");
        }
        Set<PlanCell> open = reach(plan, plan.entrance(), true);
        // Rubble only ever gates a spur or a shortcut: the exit must stay reachable without crossing a plug.
        if (!plan.rubbleEdges().isEmpty() && !open.contains(plan.terminal())) {
            hard.add(where + ": the exit can only be reached through rubble");
        }
        Set<PlanCell> spawnerCells = spawnerCells(plan);
        for (PlanCell cell : spawnerCells) {
            if (!open.contains(cell)) {
                hard.add(where + ": the spawner room at " + cell + " ("
                        + plan.rooms().get(cell).name() + ") can only be reached through rubble");
            }
        }
        // A trial key toll needs a key, and keys come out of spawners: a toll room must not be the only one.
        Set<PlanCell> tolls = new HashSet<>();
        for (Map.Entry<PlanCell, DungeonPlan.PlacedRoom> e : plan.rooms().entrySet()) {
            String name = e.getValue().name().replace("pocketdungeons:", "");
            if (name.equals("barred_vault")) {
                tolls.add(e.getKey());
            }
        }
        if (!tolls.isEmpty()) {
            Set<PlanCell> keySources = new HashSet<>(spawnerCells);
            keySources.removeAll(tolls);
            if (keySources.isEmpty()) {
                hard.add(where + ": the Barred Vault is the floor's only spawner room, so no trial key can ever pay its toll");
            }
        }
    }

    /** Cells that hold a required spawner: an encounter role, or a room whose handler places a trial spawner. */
    private static Set<PlanCell> spawnerCells(DungeonPlan plan) {
        Set<PlanCell> cells = new HashSet<>();
        for (Map.Entry<PlanCell, String> role : plan.roles().entrySet()) {
            RoomRoleDefinition def = RoleManifest.current().byId(role.getValue());
            if (def != null && def.operation == RoomRoleDefinition.Operation.TRIAL_ENCOUNTER) {
                cells.add(role.getKey());
            }
        }
        for (Map.Entry<PlanCell, DungeonPlan.PlacedRoom> e : plan.rooms().entrySet()) {
            RoomManifest.Entry entry = RoomManifest.current().byName(e.getValue().name());
            if (RoomSelector.hostsEncounter(e.getValue().name().replace("pocketdungeons:", ""),
                    entry == null ? null : entry.meta.content)) {
                cells.add(e.getKey());
            }
        }
        return cells;
    }

    private static Set<PlanCell> reach(DungeonPlan plan, PlanCell start, boolean avoidRubble) {
        Set<PlanCell> seen = new HashSet<>();
        ArrayDeque<PlanCell> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            PlanCell at = queue.poll();
            for (PlanEdge door : plan.doors()) {
                if (!door.touches(at) || (avoidRubble && plan.rubbleEdges().contains(door))) {
                    continue;
                }
                PlanCell next = door.other(at);
                if (seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        return seen;
    }

    private static int countNodeRooms(DungeonPlan plan) {
        int count = 0;
        for (DungeonPlan.PlacedRoom room : plan.rooms().values()) {
            RoomManifest.Entry entry = RoomManifest.current().byName(room.name());
            if (entry != null && !entry.meta.nodes.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasSideEdgeInto(DungeonDef def, DungeonDef.Node node) {
        for (DungeonDef.Edge edge : def.edges()) {
            if (edge.to().equals(node.id()) && edge.cost() > 0) {
                return true;
            }
        }
        return false;
    }

    private static String borrowedFrom(DungeonDef def, DungeonDef.Node node) {
        if (!node.overridesTheme() || DungeonDef.qualify(node.theme()).equals(DungeonDef.qualify(def.mainTheme()))) {
            return "";
        }
        for (DungeonDef other : DungeonDefs.current().all()) {
            if (DungeonDef.qualify(other.mainTheme()).equals(DungeonDef.qualify(node.theme()))) {
                return other.id();
            }
        }
        return "";
    }

    private static void write(String text) {
        Path file = Paths.get("floor-soak-report.md");
        try {
            Files.writeString(file, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            PocketDungeonsMod.LOG.warn("Could not write {}: {}", file.toAbsolutePath(), e.toString());
        }
    }
}
