package pocketdungeons;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plan 2026-10-17 part 5: do the spawners a player can reach with no tool always meet the floor's clear threshold?
 *
 * <p>The walk starts at the entrance and crosses a door into a cell only when that cell's room has every
 * {@code requires} met by the bag's seed plus what rooms strictly closer to the entrance provide; rubble doorways
 * count as blocked. The encounter cells reached (a role with the trial encounter operation, or a room that hosts
 * one) are compared with {@code ceil(threshold * total)}. A finding here is a floor where the party could be unable
 * to clear enough spawners without a tool; it is a design question, not something the selector should hide.
 */
final class SpawnerQuota {

    private SpawnerQuota() {}

    /** The reachable and total spawner cells of a plan, and the cells and rooms that were not reached. */
    record Result(int reached, int total, int needed, List<String> blocked) {
        boolean met() {
            return total == 0 || reached >= needed;
        }
    }

    static Result of(DungeonPlan plan, RoomManifest manifest, Set<String> bag, double threshold) {
        Set<PlanCell> encounters = new LinkedHashSet<>();
        for (Map.Entry<PlanCell, String> role : plan.roles().entrySet()) {
            RoomRoleDefinition def = RoleManifest.current().byId(role.getValue());
            if (def != null && def.operation == RoomRoleDefinition.Operation.TRIAL_ENCOUNTER) {
                encounters.add(role.getKey());
            }
        }
        for (Map.Entry<PlanCell, DungeonPlan.PlacedRoom> placed : plan.rooms().entrySet()) {
            RoomManifest.Entry entry = manifest.byName(placed.getValue().name());
            if (RoomSelector.hostsEncounter(placed.getValue().name(), entry == null ? null : entry.meta.content)) {
                encounters.add(placed.getKey());
            }
        }
        Set<PlanCell> reached = new LinkedHashSet<>();
        ArrayDeque<PlanCell> queue = new ArrayDeque<>();
        reached.add(plan.entrance());
        queue.add(plan.entrance());
        while (!queue.isEmpty()) {
            PlanCell at = queue.poll();
            for (PlanEdge edge : plan.doors()) {
                if (plan.rubbleEdges().contains(edge) || !edge.touches(at)) {
                    continue;
                }
                PlanCell next = edge.a().equals(at) ? edge.b() : edge.a();
                if (reached.contains(next) || !toolFree(plan, manifest, bag, next)) {
                    continue;
                }
                reached.add(next);
                queue.add(next);
            }
        }
        int got = 0;
        for (PlanCell cell : encounters) {
            if (reached.contains(cell)) {
                got++;
            }
        }
        int need = (int) Math.ceil(threshold * encounters.size());
        List<String> blocked = new ArrayList<>();
        for (PlanCell cell : encounters) {
            if (!reached.contains(cell)) {
                DungeonPlan.PlacedRoom room = plan.rooms().get(cell);
                blocked.add(cell + "=" + (room == null ? "?" : room.name()));
            }
        }
        return new Result(got, encounters.size(), need, blocked);
    }

    /** Whether the room at {@code cell} can be entered with the bag's seed and what shallower rooms provide. */
    private static boolean toolFree(DungeonPlan plan, RoomManifest manifest, Set<String> bag, PlanCell cell) {
        DungeonPlan.PlacedRoom room = plan.rooms().get(cell);
        RoomManifest.Entry entry = room == null ? null : manifest.byName(room.name());
        if (entry == null || entry.meta.requires.isEmpty()) {
            return true;
        }
        Set<String> available = new LinkedHashSet<>(bag);
        int depth = plan.depths().getOrDefault(cell, 0);
        for (Map.Entry<PlanCell, DungeonPlan.PlacedRoom> other : plan.rooms().entrySet()) {
            if (plan.depths().getOrDefault(other.getKey(), 0) < depth) {
                RoomManifest.Entry provider = manifest.byName(other.getValue().name());
                if (provider != null) {
                    available.addAll(provider.meta.provides);
                }
            }
        }
        return available.containsAll(entry.meta.requires);
    }
}
