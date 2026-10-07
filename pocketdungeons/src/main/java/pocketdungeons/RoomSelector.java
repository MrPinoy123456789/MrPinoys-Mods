package pocketdungeons;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Resolves a {@link DungeonShape} into a {@link DungeonPlan} by querying the
 * room manifest for a concrete template/rotation that satisfies each cell's
 * required door mask and role.
 *
 * <p>Selection is a <strong>seeded weighted pick</strong>, not the first
 * alphabetical match. With one room per shape, a whole dungeon gets built out of
 * the same handful of templates -- which is the difference between a procedural
 * layout and a layout that looks procedural. The seed is derived from the plan,
 * so a seed still reproduces a dungeon exactly.
 */
final class RoomSelector {

    private static final int MAX_ROOMS = 50;

    private RoomSelector() {}

    /**
     * Attempts to resolve every cell in the shape. Returns {@link Optional#empty}
     * if any cell has no room/rotation that satisfies its required mask and role.
     */
    static Optional<DungeonPlan> resolve(DungeonShape shape, RoomManifest manifest) {
        return resolve(shape, manifest, null);
    }

    /**
     * As {@link #resolve(DungeonShape, RoomManifest)}, filtered to a requested
     * theme. A {@code null} theme matches every room; a non-null theme only
     * matches rooms that either omit the {@code theme} field or include it.
     */
    static Optional<DungeonPlan> resolve(DungeonShape shape, RoomManifest manifest, String theme) {
        Result result = resolveDetailed(shape, manifest, theme);
        return Optional.ofNullable(result.plan());
    }

    /**
     * Detailed variant that carries failure information when resolution cannot
     * find a match for a cell.
     */
    static Result resolveDetailed(DungeonShape shape, RoomManifest manifest) {
        return resolveDetailed(shape, manifest, null);
    }

    static Result resolveDetailed(DungeonShape shape, RoomManifest manifest, String theme) {
        return resolveDetailed(shape, manifest, theme, BagTags.pilgrim());
    }

    /**
     * M47 (SITUATIONS_SPEC 6.6): resolution with the root-distance solvability
     * pass. E (D25) split the proof in two: the entrance-to-staging spine is
     * seeded from the Pilgrim's bag plus the party's own {@code mob}, while a
     * spur cell may take a room whose {@code requires} names a tool nobody
     * carries, because a bonus room that stays shut is a sign, not a broken
     * plan.
     *
     * <p>Cells are processed in BFS order rather than grid order, and a spine
     * cell at root distance n may only take a room whose {@code requires} is a
     * subset of the tags reachable at a distance strictly below n: the Pilgrim
     * seed, plus the {@code provides} of every shallower cell. That makes the
     * solution to a gate reachable before the gate on the main path, which is
     * the whole point of 6.6 over 6.2's single walk.
     *
     * <p><strong>What the model assumes.</strong> {@code available} is a boolean
     * set of tags and never a count. That is only sound while spec 6.4's
     * item-return rule holds: a gate that takes an item must give it back, so a
     * capability, once reached, stays reached at every deeper distance. The
     * catalogue audit found four templates that break the rule (findings 2.2,
     * 2.3, 2.4 and 2.5); those are template bugs and the model cannot see them.
     * The one case handled here is the tag that is consumable by construction:
     * see {@link #CONSUMABLE_TAGS}.
     *
     * <p>The three-argument form seeds the Pilgrim case (empty bag, party of
     * one), which is the strictest seed and therefore the safe default for a
     * caller with no bag to name.
     *
     * @param bagTags the depth-0 tag set, from {@link BagTags#seed}
     */
    static Result resolveDetailed(DungeonShape shape, RoomManifest manifest, String theme,
                                  Set<String> bagTags) {
        return resolveDetailed(shape, manifest, theme, bagTags, null);
    }

    /**
     * M66: the full form with recipe effects. The recipe plan carries
     * guarantees (infested, Deep Dark, Store), weightings (flooded/chasm),
     * and the path length bonus. {@code null} means no recipe effects.
     */
    static Result resolveDetailed(DungeonShape shape, RoomManifest manifest, String theme,
                                  Set<String> bagTags, RunRecipePlan recipePlan) {
        return resolveDetailed(shape, manifest, theme, bagTags, recipePlan, null);
    }

    /**
     * Dungeon structure W5: the full form with the floor's dungeon context. A non-null
     * {@code floor} makes room eligibility read the W4 metadata ({@link RoomEligibility}); a null
     * one is exactly the legacy theme-only behaviour.
     */
    static Result resolveDetailed(DungeonShape shape, RoomManifest manifest, String theme,
                                  Set<String> bagTags, RunRecipePlan recipePlan,
                                  RoomEligibility.Floor floor) {
        Map<PlanCell, Integer> depths = depths(shape);
        List<PlanCell> order = bfsOrder(shape.cells(), depths);

        int maxTier = recipePlan == null ? Integer.MAX_VALUE : KeystoneMath.lootTier(recipePlan.offerLevel);
        Set<String> weighted = recipePlan == null ? Set.of() : Set.copyOf(recipePlan.weightedRooms);
        Pass pass = new Pass(shape, manifest, theme, depths, order,
                bagTags == null ? Set.of() : bagTags, weighted, maxTier, floor);
        Failure failure = pass.prepare();
        if (failure != null) {
            return new Result(null, failure);
        }
        pass.run();

        Map<PlanCell, DungeonPlan.PlacedRoom> placed = pass.placed();
        Set<PlanCell> fallbacks = pass.fallbacks();
        PlanCell anomalyCell = rollAnomaly(shape, theme, placed, depths, maxTier);
        String anomalyName = anomalyCell == null ? null : placed.get(anomalyCell).name();

        // M66: apply recipe guarantees after the main pass and anomaly roll.
        // Each guarantee forces a specific room onto an eligible cell. The
        // guarantee is part of the solvability proof, not a post-plan
        // replacement: the forced room's requires must be satisfied by the
        // available tags at that cell's depth.
        if (recipePlan != null) {
            applyRecipeGuarantees(shape, manifest, theme, placed, depths, bagTags, recipePlan, floor);
        }
        boolean resourceShort = !ensureResourceRooms(shape, manifest, theme, placed, depths, bagTags, floor);
        // PD-93: a guarantee may have forced a themed room onto the anomaly
        // cell. That cell is no longer an anomaly, and calling it one would
        // send the stamper to the anomaly manifest for a room it does not hold.
        if (anomalyCell != null && !anomalyName.equals(placed.get(anomalyCell).name())) {
            anomalyCell = null;
        }
        orientGatedRooms(shape, manifest, placed, depths);

        Set<PlanCell> multiStory = new HashSet<>();
        for (Map.Entry<PlanCell, DungeonPlan.PlacedRoom> e : placed.entrySet()) {
            RoomManifest.Entry entry = manifest.byName(e.getValue().name());
            if (entry != null && entry.meta.spanY > 1 && !e.getKey().equals(anomalyCell)) {
                multiStory.add(e.getKey());
            }
        }
        // E (D25): rubble only ever plugs a door off the entrance-to-staging
        // spine, and a two-story cell's lower story is always a bonus (the
        // way on never runs through it, so it can always seal).
        List<PlanCell> spine = shortestPath(shape, shape.entrance(), shape.terminal());
        Set<PlanEdge> spineEdges = new HashSet<>();
        for (int i = 1; i < spine.size(); i++) {
            spineEdges.add(new PlanEdge(spine.get(i - 1), spine.get(i)));
        }
        // A cell hosting a trial spawner is required: the floor cannot finish
        // without clearing it, so rubble may never cut one off.
        Set<PlanCell> encounterCells = new HashSet<>();
        for (Map.Entry<PlanCell, String> role : shape.roles().entrySet()) {
            RoomRoleDefinition def = RoleManifest.current().byId(role.getValue());
            if (def != null && def.operation == RoomRoleDefinition.Operation.TRIAL_ENCOUNTER) {
                encounterCells.add(role.getKey());
            }
        }
        Set<PlanEdge> rubble = pickRubbleEdges(shape.seed(), shape.openEdges(), shape.entrance(),
                spineEdges, encounterCells);
        Set<PlanCell> sealed = pickSealedCells(multiStory);

        DungeonPlan plan = new DungeonPlan(
                shape.seed(),
                Set.copyOf(shape.cells()),
                Map.copyOf(shape.roles()),
                Map.copyOf(depths),
                Map.copyOf(placed),
                Set.copyOf(shape.openEdges()),
                shape.entrance(),
                shape.terminal(),
                List.copyOf(shape.criticalPath()),
                anomalyCell,
                rubble,
                sealed);
        return new Result(plan, null, Set.copyOf(fallbacks), pass.backtrackSteps(), resourceShort);
    }

    /**
     * PD-133 (playtest 2026-10-03-2): turns every gated room so its entry wall
     * faces the cell the player arrives from.
     *
     * <p>A gated room is a straight pass-through (west entry, east gate), so
     * its mask fits a straight cell at two rotations 180 degrees apart, and
     * every placement route (the main pass, the anomaly roll, recipe
     * guarantees) kept whichever one it rolled. Half the time that put the
     * iron door on the near side: the party walked out of the entrance hall
     * into a closed door they could not open from outside. The mask is the
     * same at both rotations, so swapping the rotation here changes nothing
     * the solvability pass proved.
     *
     * <p>The approach is the critical path predecessor for a spine cell and
     * the shallowest open neighbour otherwise. A room whose mask has no
     * rotation that both fits the cell and faces the approach is left alone
     * and logged, since that is a content error (a gated room authored with
     * a corner or tee mask) rather than a roll to fix here.
     */
    static void orientGatedRooms(DungeonShape shape, RoomManifest manifest,
                                 Map<PlanCell, DungeonPlan.PlacedRoom> placed,
                                 Map<PlanCell, Integer> depths) {
        for (Map.Entry<PlanCell, DungeonPlan.PlacedRoom> e : placed.entrySet()) {
            PlanCell cell = e.getKey();
            DungeonPlan.PlacedRoom room = e.getValue();
            RoomManifest.Entry entry = manifest.byName(room.name());
            if (entry == null || !DungeonRoomMeta.ACCESS_GATED.equals(entry.meta.access)
                    || (entry.maskAtRotation0 & DungeonRoomMeta.GATED_ENTRY_AT_ROTATION_0) == 0) {
                continue;
            }
            PlanCell from = approach(shape, cell, depths);
            DoorMask.Direction dir = from == null ? null : cell.directionTo(from);
            if (dir == null) {
                continue;
            }
            int want = DoorMask.fromEdges(java.util.EnumSet.of(dir));
            int mask = entry.maskAtRotation(room.rotation());
            if (DoorMask.rotateClockwise(DungeonRoomMeta.GATED_ENTRY_AT_ROTATION_0, room.rotation()) == want) {
                continue;
            }
            int chosen = -1;
            for (int r = 0; r < 4; r++) {
                if (entry.maskAtRotation(r) == mask
                        && DoorMask.rotateClockwise(DungeonRoomMeta.GATED_ENTRY_AT_ROTATION_0, r) == want) {
                    chosen = r;
                    break;
                }
            }
            if (chosen < 0) {
                PocketDungeonsMod.LOG.warn("Gated room {} at {} cannot face its approach from {}: mask {}",
                        room.name(), cell, DoorMask.toLetters(want), DoorMask.toLetters(mask));
                continue;
            }
            e.setValue(new DungeonPlan.PlacedRoom(room.name(), chosen));
        }
    }

    /**
     * PD-133: the cell a player reaches {@code cell} from. On the critical path
     * that is the previous path cell; elsewhere it is the open neighbour with
     * the smallest depth, ties broken by grid position so a plan is
     * reproducible. Null for the entrance or an unconnected cell.
     */
    static PlanCell approach(DungeonShape shape, PlanCell cell, Map<PlanCell, Integer> depths) {
        List<PlanCell> path = shape.criticalPath();
        int index = path.indexOf(cell);
        if (index > 0) {
            return path.get(index - 1);
        }
        PlanCell best = null;
        int bestDepth = Integer.MAX_VALUE;
        for (PlanEdge edge : shape.openEdges()) {
            if (!edge.touches(cell)) {
                continue;
            }
            PlanCell other = edge.other(cell);
            int d = depths.getOrDefault(other, Integer.MAX_VALUE);
            if (best == null || d < bestDepth
                    || (d == bestDepth && (other.x() < best.x()
                        || (other.x() == best.x() && other.z() < best.z())))) {
                best = other;
                bestDepth = d;
            }
        }
        if (best != null && bestDepth >= depths.getOrDefault(cell, 0)) {
            return null;
        }
        return best;
    }

    /** The chance a plan with an eligible door gets one rubble doorway. */
    static final double RUBBLE_CHANCE = 0.35;

    /**
     * The doors to plug with rubble ({@link ConnectorType#RUBBLE}): at most one,
     * rolled off the plan seed. A door is eligible when it does not touch the
     * entrance (the stamper leaves the entrance's doors alone) and is not on
     * the entrance-to-staging spine (E, D25): a plug only ever gates a spur,
     * so the way on never asks for the blast. Nor may a plug cut off a
     * required cell ({@code requiredCells}, the trial spawner rooms): rubble
     * gates optional rooms and shortcuts only. One per plan, so the bonus
     * stays rare.
     */
    static Set<PlanEdge> pickRubbleEdges(long seed, Set<PlanEdge> doors, PlanCell entrance,
                                         Set<PlanEdge> spineEdges, Set<PlanCell> requiredCells) {
        List<PlanEdge> eligible = new ArrayList<>();
        for (PlanEdge edge : doors) {
            if (edge.touches(entrance) || spineEdges.contains(edge)
                    || cutsOffRequired(doors, edge, entrance, requiredCells)) {
                continue;
            }
            eligible.add(edge);
        }
        if (eligible.isEmpty()) {
            return Set.of();
        }
        eligible.sort(Comparator.comparingInt(PlanEdge::hashCode)
                .thenComparing(PlanEdge::toString));
        // SplitMix64's finaliser first: java.util.Random's first draw barely
        // moves between nearby seeds (the same reason AffixMath.seed mixes).
        long mixed = (seed ^ 0x52554242L) * 0x9E3779B97F4A7C15L;
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        Random rng = new Random(mixed ^ (mixed >>> 31));
        if (rng.nextDouble() >= RUBBLE_CHANCE) {
            return Set.of();
        }
        return Set.of(eligible.get(rng.nextInt(eligible.size())));
    }

    /** Whether plugging {@code plugged} leaves a required cell unreachable from the entrance. */
    private static boolean cutsOffRequired(Set<PlanEdge> doors, PlanEdge plugged, PlanCell entrance,
                                           Set<PlanCell> requiredCells) {
        Set<PlanCell> reached = new HashSet<>();
        java.util.ArrayDeque<PlanCell> queue = new java.util.ArrayDeque<>();
        reached.add(entrance);
        queue.add(entrance);
        while (!queue.isEmpty()) {
            PlanCell cell = queue.poll();
            for (PlanEdge door : doors) {
                if (door.equals(plugged) || !door.touches(cell)) {
                    continue;
                }
                PlanCell next = door.other(cell);
                if (reached.add(next)) {
                    queue.add(next);
                }
            }
        }
        for (PlanCell required : requiredCells) {
            if (!reached.contains(required)) {
                return true;
            }
        }
        return false;
    }

    /**
     * E (D25): a two-story room's lower story is a reward pocket, never the
     * way on (the way through the cell is on the upper story, where the
     * doorway doors are). So every multi-story cell seals its lower story,
     * and {@link RubbleOrdeal#FLOOR} arms each seal at stamp time. The blast
     * that opens it is a bonus spend, not a path requirement, so there is no
     * cap.
     */
    static Set<PlanCell> pickSealedCells(Set<PlanCell> multiStory) {
        return Set.copyOf(multiStory);
    }

    /**
     * M35: rarely swaps one non-entrance, non-terminal critical-path cell's
     * room for one out of {@link RoomManifest#currentAnomaly()} satisfying the
     * same mask and role, mutating {@code placed} in place. Gated on the run
     * having an adventure-graph node for its theme, the same gate the Pocket2
     * door uses -- an unthemed run never hosts an anomaly. Rolled off the plan
     * seed rather than {@code rng}, which the main selection loop above has
     * already consumed by an amount that depends on how many cells the shape
     * has, and a roll whose outcome depends on room count would be a strange
     * kind of not-quite-reproducible.
     *
     * @return the swapped cell, or {@code null} if the gate was closed, the
     *         roll failed, no eligible cell existed, or no anomaly room
     *         matched the chosen cell's mask and role
     */
    private static PlanCell rollAnomaly(DungeonShape shape, String theme,
                                        Map<PlanCell, DungeonPlan.PlacedRoom> placed,
                                        Map<PlanCell, Integer> depths, int maxTier) {
        if (theme == null || AdventureGraphs.current().graph().node(theme) == null) {
            return null;
        }
        Random anomalyRng = new Random(shape.seed() * 47 + 91);
        if (anomalyRng.nextDouble() >= PocketDungeonsConfig.anomalyRoomChance()) {
            return null;
        }

        List<PlanCell> eligible = new ArrayList<>();
        for (PlanCell cell : shape.criticalPath()) {
            if (!cell.equals(shape.entrance()) && !cell.equals(shape.terminal())) {
                eligible.add(cell);
            }
        }
        if (eligible.isEmpty()) {
            return null;
        }

        PlanCell candidate = eligible.get(anomalyRng.nextInt(eligible.size()));
        int mask = requiredMask(shape, candidate);
        String role = shape.roles().get(candidate);
        List<RoomManifest.Match> matches = RoomManifest.currentAnomaly().queryAnyRotation(mask, role);
        if (matches.isEmpty()) {
            return null;
        }
        matches = new ArrayList<>(matches);
        matches.sort(Comparator.comparing((RoomManifest.Match m) -> m.entry().name)
                .thenComparingInt(RoomManifest.Match::rotation));

        String descriptor = "anomaly " + candidate + " " + role + " mask " + DoorMask.toLetters(mask)
                + (theme == null ? "" : " theme " + theme);
        RoomManifest.Match pick = pick(matches, depths.getOrDefault(candidate, 0), new HashMap<>(),
                anomalyRng, maxTier, descriptor);
        placed.put(candidate, new DungeonPlan.PlacedRoom(pick.entry().name, pick.rotation()));
        return candidate;
    }

    /**
     * M66: applies recipe guarantees after the main selection pass. Each
     * guarantee forces a specific room onto an eligible cell, replacing
     * whatever room the pass assigned there. The forced room's
     * {@code requires} must be satisfied by the available tags at that
     * cell's depth; if no eligible cell can host the guaranteed room, the
     * guarantee is silently skipped (the plan is still valid, just without
     * the guarantee). This is rare: the room library has coverage-floor
     * shapes for every mask.
     *
     * <p>Guarantees applied, in order:
     * <ol>
     *   <li>Infested: force {@code infested_wall} or {@code creeper_kennel}
     *       onto a non-entrance, non-terminal cell.</li>
     *   <li>Deep Dark: force {@code deep_dark_landing} onto a non-entrance,
     *       non-terminal cell (tier 3 only; RunRecipePlan already refused
     *       below tier 3).</li>
     *   <li>Store: force {@code the_store} onto a non-entrance, non-terminal
     *       cell, preferring spur cells (branches off the critical path).</li>
     * </ol>
     *
     * <p>Flooded/Chasm weighting is applied during the main selection pass
     * via the {@link Pass} constructor, not here.
     */
    private static void applyRecipeGuarantees(DungeonShape shape, RoomManifest manifest,
                                              String theme,
                                              Map<PlanCell, DungeonPlan.PlacedRoom> placed,
                                              Map<PlanCell, Integer> depths,
                                              Set<String> bagTags,
                                              RunRecipePlan recipePlan,
                                              RoomEligibility.Floor floor) {
        // M71: guaranteed rooms are data-driven. Each group carries its
        // alternative room names; the tier gate was already checked at
        // resolve time, so every group here is tier-eligible for this offer.
        for (RecipeEffects.GuaranteedRoom g : recipePlan.guaranteedRooms) {
            // M68: room names are now namespaced ids. Qualify the target names so
            // a legacy bare target ("infested_wall") matches the namespaced room
            // name ("pocketdungeons:infested_wall") the manifest now carries.
            Set<String> targetSet = new java.util.HashSet<>();
            for (String target : g.names()) {
                targetSet.add(JsonPackSupport.qualify(target));
            }
            forceRoom(shape, manifest, theme, placed, depths, bagTags,
                    match -> targetSet.contains(match.entry().name), floor);
        }
    }

    /**
     * PD-149 (playtest 2026-10-05-1), as data (D12 revised 2026-10-06): a floor
     * whose dungeon asks for {@code minNodeRooms} node-bearing rooms must place
     * that many, so a floor of nothing but generic halls pays nothing at all
     * (Mineshaft floors rolled `nodes_total` 0 twice in one trip). After the
     * pass and the recipe guarantees, a floor short of its number gets
     * resource-bearing rooms forced onto eligible cells, the same way a recipe
     * guarantee lands. A room is resource-bearing when it declares
     * {@code nodes} or, for a dungeon with no node room at all, is bound to the
     * floor's dungeon by its metadata (a Cow Pits pen pays cows, not ore).
     * No eligible host leaves the plan untouched: the floor is dull, never broken.
     *
     * <p>Returns whether the floor met its number. False tells
     * {@link LayoutPlanner#plan} to try another layout: the reopened PD-149
     * (playtest 2026-10-06-1) was a floor of corners, tees and dead ends, and
     * every Mineshaft ore room is a two-door straight, so no cell could take one.
     */
    private static boolean ensureResourceRooms(DungeonShape shape, RoomManifest manifest, String theme,
                                            Map<PlanCell, DungeonPlan.PlacedRoom> placed,
                                            Map<PlanCell, Integer> depths,
                                            Set<String> bagTags,
                                            RoomEligibility.Floor floor) {
        int wanted = floor == null ? 0 : floor.minNodeRooms();
        while (wanted > 0 && payingRooms(manifest, placed, floor) < wanted) {
            // First choice: a room that stamps nodes itself. Second, when the dungeon has
            // no node room at all: any room of the dungeon's own, so the floor at least
            // reads and pays as the place it claims to be.
            if (forceRoom(shape, manifest, theme, placed, depths, bagTags,
                    match -> !match.entry().meta.nodes.isEmpty(), floor)) {
                continue;
            }
            if (!hasNodeRoom(manifest, floor) && forceRoom(shape, manifest, theme, placed, depths, bagTags,
                    match -> RoomEligibility.boundTo(
                            RoomEligibility.RoomTags.of(match.entry().meta), floor), floor)) {
                continue;
            }
            // PD-149: every node room may need a door shape this layout lacks (the
            // Mineshaft's ore rooms are all two-door straights, and the live floor was
            // corners, tees and dead ends). Say so, so the planner tries another layout.
            return false;
        }
        return true;
    }

    /**
     * How many placed rooms pay as a resource floor: rooms that stamp nodes, or,
     * for a dungeon that has no node rooms, its own rooms.
     */
    private static int payingRooms(RoomManifest manifest, Map<PlanCell, DungeonPlan.PlacedRoom> placed,
                                   RoomEligibility.Floor floor) {
        int nodes = 0;
        int bound = 0;
        for (DungeonPlan.PlacedRoom room : placed.values()) {
            RoomManifest.Entry entry = manifest.byName(room.name());
            if (entry == null) {
                continue;
            }
            if (!entry.meta.nodes.isEmpty()) {
                nodes++;
            } else if (RoomEligibility.boundTo(RoomEligibility.RoomTags.of(entry.meta), floor)) {
                bound++;
            }
        }
        return nodes > 0 || hasNodeRoom(manifest, floor) ? nodes : bound;
    }

    /** Whether the manifest holds a node room bound to the floor's dungeon. */
    private static boolean hasNodeRoom(RoomManifest manifest, RoomEligibility.Floor floor) {
        for (RoomManifest.Entry entry : manifest.rooms()) {
            if (!entry.meta.nodes.isEmpty()
                    && RoomEligibility.boundTo(RoomEligibility.RoomTags.of(entry.meta), floor)) {
                return true;
            }
        }
        return false;
    }

    /**
     * M66: forces a matching room onto an eligible cell. Finds a
     * non-entrance, non-terminal cell whose mask and role match a wanted
     * room at some rotation, and whose {@code requires} is satisfied
     * by the available tags at that depth. Replaces the cell's assigned room
     * with the matching room. If no eligible cell exists, does nothing.
     *
     * @return whether a room was forced
     */
    private static boolean forceRoom(DungeonShape shape, RoomManifest manifest, String theme,
                                  Map<PlanCell, DungeonPlan.PlacedRoom> placed,
                                  Map<PlanCell, Integer> depths,
                                  Set<String> bagTags,
                                  java.util.function.Predicate<RoomManifest.Match> wanted,
                                  RoomEligibility.Floor floor) {
        for (PlanCell cell : shape.cells()) {
            if (cell.equals(shape.entrance()) || cell.equals(shape.terminal())) {
                continue;
            }
            int mask = requiredMask(shape, cell);
            String role = shape.roles().get(cell);
            if (role == null) {
                continue;
            }
            List<RoomManifest.Match> matches = query(manifest, mask, role, theme, floor);
            for (RoomManifest.Match match : matches) {
                if (!wanted.test(match)) {
                    continue;
                }
                // Check requires: the forced room's requires must be a subset
                // of the available tags at this cell's depth.
                int depth = depths.getOrDefault(cell, 0);
                Set<String> available = new java.util.LinkedHashSet<>(bagTags);
                for (PlanCell other : shape.cells()) {
                    if (depths.getOrDefault(other, 0) < depth) {
                        DungeonPlan.PlacedRoom otherRoom = placed.get(other);
                        if (otherRoom != null) {
                            RoomManifest.Entry entry = manifest.byName(otherRoom.name());
                            if (entry != null) {
                                available.addAll(entry.meta.provides);
                            }
                        }
                    }
                }
                if (!available.containsAll(match.entry().meta.requires)) {
                    continue;
                }
                // Eligible: force this room onto this cell.
                DungeonPlan.PlacedRoom original = placed.get(cell);
                placed.put(cell, new DungeonPlan.PlacedRoom(match.entry().name, match.rotation()));
                // F8: revalidate that the replacement did not strip a
                // provides tag that a deeper cell's requires depends on.
                // forceRoom already checked the forced room's own requires,
                // but replacing a provider can break solvability for cells
                // at greater depth. If it does, revert and keep searching.
                if (!deeperCellsSatisfied(shape, manifest, placed, depths, bagTags, depth)) {
                    placed.put(cell, original);
                    continue;
                }
                return true;
            }
        }
        // No eligible cell found. The guarantee is silently skipped; the plan
        // is still valid. This is rare and logged by the caller if needed.
        return false;
    }

    /**
     * F8: verifies that every cell at a depth greater than
     * {@code forcedDepth} still has its {@code requires} satisfied by the
     * available tags, given the current {@code placed} map. Used after a
     * forced room replacement to ensure the replacement did not remove a
     * {@code provides} tag that a deeper cell depends on.
     */
    private static boolean deeperCellsSatisfied(DungeonShape shape, RoomManifest manifest,
                                                Map<PlanCell, DungeonPlan.PlacedRoom> placed,
                                                Map<PlanCell, Integer> depths,
                                                Set<String> bagTags, int forcedDepth) {
        for (PlanCell cell : shape.cells()) {
            int depth = depths.getOrDefault(cell, 0);
            if (depth <= forcedDepth) {
                continue;
            }
            DungeonPlan.PlacedRoom room = placed.get(cell);
            if (room == null) {
                continue;
            }
            RoomManifest.Entry entry = manifest.byName(room.name());
            if (entry == null) {
                continue;
            }
            Set<String> available = new java.util.LinkedHashSet<>(bagTags);
            for (PlanCell other : shape.cells()) {
                if (depths.getOrDefault(other, 0) < depth) {
                    DungeonPlan.PlacedRoom otherRoom = placed.get(other);
                    if (otherRoom != null) {
                        RoomManifest.Entry otherEntry = manifest.byName(otherRoom.name());
                        if (otherEntry != null) {
                            available.addAll(otherEntry.meta.provides);
                        }
                    }
                }
            }
            if (!available.containsAll(entry.meta.requires)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates a resolved plan. Returns an empty list when the plan is acceptable.
     */
    static List<String> validate(DungeonPlan plan, int maxGridSpan) {
        if (maxGridSpan < 1) {
            throw new IllegalArgumentException("maxGridSpan must be positive");
        }
        List<String> problems = new ArrayList<>();
        // A plan's cell set is guaranteed non-empty by construction: PlanGeometry
        // throws on an empty set, and the graph generator's own minPathLength
        // (config-validated >= 2) guarantees at least an entrance and a
        // terminal cell. A minimum-room check here would be unreachable.
        int count = plan.cells().size();
        if (count > MAX_ROOMS) {
            problems.add("room count " + count + " above maximum " + MAX_ROOMS);
        }
        Set<PlanCell> reachable = reachableCells(plan);
        if (!reachable.contains(plan.terminal())) {
            problems.add("terminal not reachable from entrance");
        }

        // Span budget. A sprawling layout costs a force-load ticket per cell and a
        // teardown proportional to its footprint, so cap it here, where a rejection
        // costs one retry, rather than discovering it as a stutter in live play.
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (PlanCell cell : plan.cells()) {
            minX = Math.min(minX, cell.x());
            maxX = Math.max(maxX, cell.x());
            minZ = Math.min(minZ, cell.z());
            maxZ = Math.max(maxZ, cell.z());
        }
        int spanX = maxX - minX + 1;
        int spanZ = maxZ - minZ + 1;
        if (spanX > maxGridSpan || spanZ > maxGridSpan) {
            problems.add("grid span " + spanX + "x" + spanZ
                    + " exceeds the maximum of " + maxGridSpan);
        }
        return problems;
    }

    /**
     * Picks one match, preferring those whose {@code minDepth} and
     * {@code maxPerDungeon} are satisfied, weighted by the room's declared weight.
     *
     * <p><strong>Both filters are preferences, not constraints.</strong> If
     * applying them empties a non-empty match list they are dropped and the pick
     * runs over everything. Failing a whole plan because the one legal room for a
     * cell had already been used twice would reintroduce M3's failure mode for no
     * player-visible benefit. Because every coverage-floor room ships unlimited
     * and at depth 0, this relaxation almost never fires -- and if it starts
     * firing often, that is a signal the floor has a hole, which
     * {@code /dungeon admin coverage} names directly.
     */
    private static RoomManifest.Match pick(List<RoomManifest.Match> matches, int depth,
                                           Map<String, Integer> used, Random rng,
                                           int maxTier, String descriptor) {
        List<RoomManifest.Match> eligible = preferred(matches, depth, used, maxTier, descriptor);

        // A rotationally symmetric room yields several matches for the same entry
        // and so gets an extra roll. That is harmless, and picking a random
        // rotation for a symmetric room is desirable anyway.
        int total = 0;
        for (RoomManifest.Match match : eligible) {
            total += biasedWeight(match);
        }
        int roll = rng.nextInt(total);
        for (RoomManifest.Match match : eligible) {
            roll -= biasedWeight(match);
            if (roll < 0) {
                return match;
            }
        }
        return eligible.get(eligible.size() - 1);
    }

    /**
     * The manifest query for a cell, with the floor's room eligibility applied (dungeon structure
     * W5). With no floor this is the legacy theme query. With a floor the theme filter is replaced
     * by {@link RoomEligibility}, which still lets every room that matched the legacy theme match.
     */
    static List<RoomManifest.Match> query(RoomManifest manifest, int mask, String role, String theme,
                                          RoomEligibility.Floor floor) {
        if (floor == null) {
            return manifest.queryAnyRotation(mask, role, theme);
        }
        List<RoomManifest.Match> out = new ArrayList<>();
        for (RoomManifest.Match match : manifest.queryAnyRotation(mask, role, null)) {
            if (RoomEligibility.eligible(RoomEligibility.RoomTags.of(match.entry().meta), floor)) {
                out.add(match);
            }
        }
        // W7a: the final floor of a capstone dungeon puts its boss room in the terminal cell.
        return RoomEligibility.narrowToCapstone(out,
                match -> RoomEligibility.RoomTags.of(match.entry().meta), floor, RoleIds.EXIT.equals(role));
    }

    /** A match's declared weight times any playtest bias on its room ({@link PlaytestBias}). */
    private static int biasedWeight(RoomManifest.Match match) {
        return Math.max(1, match.entry().meta.weight) * PlaytestBias.of(match.entry().name);
    }

    /**
     * The tier, depth and repeat preferences {@link #pick} applies, as a fresh
     * mutable list. Extracted from {@code pick} so M47's pass can draw from the
     * same distribution more than once without re-deriving it, and it relaxes
     * to the whole list the same way and for the same reason.
     *
     * <p>PD-122: a room whose {@code tier} is higher than the floor's allowed
     * maximum is filtered out first. If that leaves nothing, the tier filter
     * is dropped and a warning is logged so a coverage hole shows up in the
     * server log instead of silently failing the plan.
     */
    private static List<RoomManifest.Match> preferred(List<RoomManifest.Match> matches, int depth,
                                                      Map<String, Integer> used, int maxTier,
                                                      String descriptor) {
        List<RoomManifest.Match> tierEligible = new ArrayList<>(matches.size());
        for (RoomManifest.Match match : matches) {
            if (match.entry().meta.tier <= maxTier) {
                tierEligible.add(match);
            }
        }
        if (tierEligible.isEmpty() && !matches.isEmpty()) {
            PocketDungeonsMod.LOG.warn("Room tier gap for {}: no rooms at tier <= {}; falling back",
                    descriptor, maxTier);
            tierEligible = new ArrayList<>(matches);
        }
        List<RoomManifest.Match> eligible = new ArrayList<>(tierEligible.size());
        for (RoomManifest.Match match : tierEligible) {
            DungeonRoomMeta meta = match.entry().meta;
            if (meta.minDepth > depth) {
                continue;
            }
            if (meta.maxPerDungeon >= 0
                    && used.getOrDefault(match.entry().name, 0) >= meta.maxPerDungeon) {
                continue;
            }
            eligible.add(match);
        }
        return eligible.isEmpty() ? tierEligible : eligible;
    }

    // ---- M47: the root-distance solvability pass (SITUATIONS_SPEC 6.6) ----

    /**
     * Tags a room hands over once rather than for good.
     *
     * <p>Audit finding 2.6: a trial key leaves the room in the player's
     * inventory and is spent at the first vault or altar that takes it, so
     * {@code provides: trial_key} is true exactly once and the boolean
     * {@code available} set cannot say so. The audit's rule, enforced in
     * {@link #solvable}: a consumable may appear in {@code provides}, and in a
     * spur's {@code requires} where losing it costs an optional reward, but
     * never in the {@code requires} of a cell on the entrance-to-staging path,
     * where losing it would cost the route.
     *
     * <p>This belongs next to the vocabulary in {@link SituationTags} and moves
     * there the next time that file is opened for another reason; M47 does not
     * own it.
     */
    static final Set<String> CONSUMABLE_TAGS = Set.of(SituationTags.TRIAL_KEY);

    /** The branch id of every cell on the entrance-to-staging path. */
    private static final int SPINE_BRANCH = -1;

    /**
     * Safety cap on the backtracking search, mirroring the constant
     * {@code LayoutGraphGenerator.generateCriticalPath} caps its own
     * backtracker with (spec 6.6 step 5 names that one). It is private over
     * there and M47 does not own that file, so the value is repeated here
     * rather than shared; whoever next opens both should hoist it.
     *
     * <p>For twenty cells with five to ten candidates each it is never
     * approached: the measured worst case over the Pilgrim sweep is three
     * figures below it.
     */
    private static final int MAX_BACKTRACK_STEPS = 50000;

    /**
     * One run of the 6.6 pass over one shape.
     *
     * <p>State lives in an object rather than in locals because backtracking
     * needs to undo, and undoing a cumulative {@code available} set built by
     * addition is the kind of thing that works for a week. Everything derived
     * is instead recomputed from the assignment prefix, which for twenty cells
     * costs nothing and cannot drift: the tags a cell may draw on, how many
     * times a room has been used already, and which branches have spent their
     * one gate are all functions of the cells resolved before it.
     */
    private static final class Pass {

        private final DungeonShape shape;
        private final RoomManifest manifest;
        private final String theme;
        private final Map<PlanCell, Integer> depths;
        private final List<PlanCell> order;
        private final Set<String> bagTags;
        /** E (D25): the spine's seed. Bag tool tags never reach the spine's
         *  solvability proof; only the party's own {@code mob} does. */
        private final Set<String> spineSeed;
        private final Set<String> weightedRooms;
        private final int maxTier;
        private final RoomEligibility.Floor floor;

        private Set<PlanCell> onSpine = Set.of();
        private Map<PlanCell, Integer> branches = Map.of();
        private PlanCell gateCell;

        /** Every mask-and-role match per cell, sorted, before any tag filtering. */
        private final List<List<RoomManifest.Match>> matches = new ArrayList<>();

        private RoomManifest.Match[] assignments;
        private CellState[] states;

        /** Cells whose {@code requires} filter could not be satisfied at all. */
        private final Set<PlanCell> forced = new LinkedHashSet<>();

        private int steps;

        /** Deepest index handed an empty candidate pool during the round. */
        private int deepestEmpty = -1;

        Pass(DungeonShape shape, RoomManifest manifest, String theme,
             Map<PlanCell, Integer> depths, List<PlanCell> order, Set<String> bagTags,
             Set<String> weightedRooms, int maxTier, RoomEligibility.Floor floor) {
            this.floor = floor;
            this.shape = shape;
            this.manifest = manifest;
            this.theme = theme;
            this.depths = depths;
            this.order = order;
            this.bagTags = bagTags;
            this.spineSeed = bagTags != null && bagTags.contains(SituationTags.MOB)
                    ? Set.of(SituationTags.MOB) : Set.of();
            this.weightedRooms = weightedRooms == null ? Set.of() : Set.copyOf(weightedRooms);
            this.maxTier = maxTier;
        }

        /**
         * Resolves each cell's mask and role once. Returns the legible failure
         * the pre-M47 selector returned, unchanged: a cell with no role, or a
         * mask and role the manifest has no room for at any rotation. Neither
         * is a solvability problem and neither is worth backtracking over.
         */
        Failure prepare() {
            List<PlanCell> spine = shortestPath(shape, shape.entrance(), shape.terminal());
            onSpine = new HashSet<>(spine);
            branches = branchGroups(shape, onSpine);
            gateCell = designateGateCell(shape, spine, manifest, theme, maxTier, floor);

            for (PlanCell cell : order) {
                int mask = requiredMask(shape, cell);
                String role = shape.roles().get(cell);
                if (role == null) {
                    return new Failure(cell, mask, "missing role");
                }
                // M70: a role the manifest does not recognise is a named
                // validation error, not a silent fallthrough to generic room
                // behaviour. Structural roles (entrance, exit) are
                // engine-owned and not in the role manifest, so they are
                // accepted here; every population role must be loaded.
                if (!isStructuralRole(role) && RoleManifest.current().byId(role) == null) {
                    return new Failure(cell, mask, "unknown role: " + role);
                }
                List<RoomManifest.Match> found = query(manifest, mask, role, theme, floor);
                if (found.isEmpty()) {
                    return new Failure(cell, mask, role);
                }
                // Stable order in, so the weighted draw is reproducible
                // regardless of the order the manifest holds its rooms in.
                matches.add(sortedMatches(found));
            }
            assignments = new RoomManifest.Match[order.size()];
            states = new CellState[order.size()];
            return null;
        }

        /**
         * Runs the search, widening the forced-fallback set until it succeeds.
         *
         * <p>A round is a full backtracking search. If it exhausts, the cell
         * that blocked it deepest is added to {@link #forced}, where it takes a
         * role-only room with no {@code requires} at all (6.6 step 6), and the
         * search runs again. Each round forces at least one more cell and a
         * forced cell always has a candidate, so the loop terminates; in
         * practice round one succeeds and this is one iteration.
         */
        void run() {
            forceImpossibleCells();
            for (int round = 0; round <= order.size(); round++) {
                if (search()) {
                    return;
                }
                PlanCell blocker = blockingCell();
                if (blocker == null || !forced.add(blocker)) {
                    // Nothing new to give up on. Give up on everything: a floor
                    // of plain corridors is dull, and a floor that fails to
                    // resolve is a run the player does not get.
                    forced.addAll(order);
                }
            }
            throw new IllegalStateException("solvability pass did not terminate for seed "
                    + shape.seed());
        }

        /**
         * Marks, before any search runs, the cells no assignment could ever
         * satisfy.
         *
         * <p>Take the most generous view possible of a cell at depth n: assume
         * every shallower cell picked whichever of its candidates helped most,
         * so {@code available} is the bag plus the {@code provides} of every
         * candidate of every cell at depth below n. If nothing fits the cell
         * even then, nothing will, and searching for it is a search with no
         * answer at the bottom of it.
         *
         * <p>This is the difference between a fast pass and a slow one. Without
         * it a single impossible cell (a floor whose only encounter room wants
         * redstone, on a floor with no redstone above it) makes the backtracker
         * re-try every combination of every shallower cell before giving up,
         * which is what burns the step cap. With it, that cell is a fallback
         * immediately and the search only ever runs where an answer might be.
         */
        private void forceImpossibleCells() {
            for (int i = 0; i < order.size(); i++) {
                PlanCell cell = order.get(i);
                int depth = depths.getOrDefault(cell, 0);
                Set<String> optimistic = new LinkedHashSet<>(
                        onSpine.contains(cell) ? spineSeed : bagTags);
                for (int j = 0; j < i; j++) {
                    if (depths.getOrDefault(order.get(j), 0) >= depth) {
                        break;
                    }
                    for (RoomManifest.Match match : matches.get(j)) {
                        optimistic.addAll(match.entry().meta.provides);
                    }
                }
                // Optimistic on the placement rules too: a cell rejected here is
                // rejected on its requires alone, never on where it happens to sit.
                if (solvable(matches.get(i), optimistic, onSpine.contains(cell), false, false, maxTier)
                        .isEmpty()) {
                    forced.add(cell);
                }
            }
        }

        /**
         * One backtracking search over the whole BFS order.
         *
         * @return whether every cell ended up with a room
         */
        private boolean search() {
            java.util.Arrays.fill(assignments, null);
            java.util.Arrays.fill(states, null);
            deepestEmpty = -1;
            int index = 0;
            while (index < order.size()) {
                if (states[index] == null) {
                    states[index] = new CellState(index);
                }
                RoomManifest.Match pick = states[index].next();
                if (pick != null) {
                    assignments[index] = pick;
                    index++;
                    continue;
                }
                // Nothing left here. Hand the cell back and take an earlier
                // cell's next candidate, which is what makes this better than
                // greedy: a shallow pick that stranded a deeper gate is undone
                // rather than paid for with a boring room.
                int target = retryFrom(index);
                for (int i = index; i > target && i >= 0; i--) {
                    states[i] = null;
                    assignments[i] = null;
                }
                index = target;
                if (index < 0 || ++steps > MAX_BACKTRACK_STEPS) {
                    return false;
                }
                assignments[index] = null;
            }
            return true;
        }

        /**
         * Which cell to go back to when {@code index} has run out of rooms.
         *
         * <p>The previous cell, except when this cell never had a candidate in
         * the first place. That case is a {@code requires} conflict, and only a
         * cell that could have provided one of the tags this cell wants, at a
         * <em>smaller</em> root distance, can resolve it. Anything else is a
         * cell whose choice cannot reach this one: a sibling at the same depth
         * is invisible to it by 6.6's same-depth rule, and a room providing
         * nothing it wants changes nothing.
         *
         * <p>Walking back one cell at a time through those instead is what
         * turns a shallow search into an exponential one. Measured on a
         * deliberately mean catalogue, jumping straight to the cell that could
         * actually help takes the pass from tens of milliseconds a floor, with
         * the step cap being hit, to a fraction of one.
         *
         * @return the index to retry, or {@code -1} when nothing upstream can
         *         help and the search is over
         */
        private int retryFrom(int index) {
            if (!states[index].startedEmpty) {
                return index - 1;
            }
            Set<String> wanted = new LinkedHashSet<>();
            for (RoomManifest.Match match : matches.get(index)) {
                wanted.addAll(match.entry().meta.requires);
            }
            int depth = depths.getOrDefault(order.get(index), 0);
            for (int j = index - 1; j >= 0; j--) {
                if (depths.getOrDefault(order.get(j), 0) >= depth) {
                    continue;
                }
                for (RoomManifest.Match match : matches.get(j)) {
                    if (!java.util.Collections.disjoint(match.entry().meta.provides, wanted)) {
                        return j;
                    }
                }
            }
            return -1;
        }

        /**
         * The cell to give up on after a search exhausted: the deepest one that
         * was ever handed an empty candidate pool, which is the cell the floor
         * could not solve. If that one is already forced, any cell that is not
         * will do, and the round after that forces the lot.
         */
        private PlanCell blockingCell() {
            if (deepestEmpty >= 0 && !forced.contains(order.get(deepestEmpty))) {
                return order.get(deepestEmpty);
            }
            for (int i = order.size() - 1; i >= 0; i--) {
                if (!forced.contains(order.get(i))) {
                    return order.get(i);
                }
            }
            return null;
        }

        /**
         * 6.6 step 3: the bag's tags, plus the {@code provides} of every cell at
         * a root distance strictly below this one. The order is depth-sorted, so
         * that is a prefix scan that stops at the first cell of this cell's own
         * depth: a same-depth neighbour's provides are deliberately not visible,
         * because the player is not guaranteed to have visited it first.
         */
        private Set<String> availableFor(int index) {
            Set<String> available = new LinkedHashSet<>(
                    onSpine.contains(order.get(index)) ? spineSeed : bagTags);
            int depth = depths.getOrDefault(order.get(index), 0);
            for (int i = 0; i < index; i++) {
                if (depths.getOrDefault(order.get(i), 0) >= depth) {
                    break;
                }
                if (assignments[i] != null) {
                    available.addAll(assignments[i].entry().meta.provides);
                }
            }
            return available;
        }

        Map<PlanCell, DungeonPlan.PlacedRoom> placed() {
            Map<PlanCell, DungeonPlan.PlacedRoom> out = new LinkedHashMap<>();
            for (int i = 0; i < order.size(); i++) {
                RoomManifest.Match match = assignments[i];
                out.put(order.get(i), new DungeonPlan.PlacedRoom(match.entry().name, match.rotation()));
            }
            return out;
        }

        Set<PlanCell> fallbacks() {
            return Set.copyOf(forced);
        }

        int backtrackSteps() {
            return steps;
        }

        /**
         * One cell's remaining candidates, drawn from without replacement.
         *
         * <p>The first draw is the ordinary weighted pick the selector has
         * always made. Later draws only happen when the cells after this one
         * could not be solved, and they walk the same weighted distribution
         * with the already-tried rooms removed.
         */
        private final class CellState {

            private final List<RoomManifest.Match> remaining;
            private final Random rng;

            /**
             * Whether this cell had nothing to choose from the moment it was
             * reached, which means a {@code requires} conflict rather than a
             * cell that tried everything it had. {@link #retryFrom} needs the
             * difference.
             */
            private final boolean startedEmpty;

            CellState(int index) {
                PlanCell cell = order.get(index);
                List<RoomManifest.Match> all = matches.get(index);
                List<RoomManifest.Match> pool;
                if (forced.contains(cell)) {
                    pool = roleOnly(all);
                } else {
                    int branch = branches.getOrDefault(cell, SPINE_BRANCH);
                    pool = solvable(all, availableFor(index), onSpine.contains(cell),
                            gatedBefore(index).contains(branch), cell.equals(gateCell), maxTier);
                }
                this.startedEmpty = pool.isEmpty();
                if (pool.isEmpty()) {
                    deepestEmpty = Math.max(deepestEmpty, index);
                }
                String role = shape.roles().get(cell);
                String descriptor = cell + " " + (role == null ? "no-role" : role)
                        + " mask " + DoorMask.toLetters(requiredMask(shape, cell))
                        + (theme == null ? "" : " theme " + theme);
                this.remaining = preferred(pool, depths.getOrDefault(cell, 0), usedBefore(index),
                        maxTier, descriptor);
                // Seeded per cell rather than off one shared stream, so a cell
                // re-entered after an upstream change draws the same order it
                // would have drawn the first time.
                this.rng = new Random(shape.seed() * 31 + 17 + 1013L * index);
            }

            RoomManifest.Match next() {
                if (remaining.isEmpty()) {
                    return null;
                }
                int total = 0;
                for (RoomManifest.Match match : remaining) {
                    total += weightOf(match);
                }
                int roll = rng.nextInt(total);
                for (int i = 0; i < remaining.size(); i++) {
                    roll -= weightOf(remaining.get(i));
                    if (roll < 0) {
                        return remaining.remove(i);
                    }
                }
                return remaining.remove(remaining.size() - 1);
            }

            /**
             * M71: the effective weight of a match, with a recipe's
             * weighted rooms boosted. A room named in the recipe plan's
             * {@code weightedRooms} set draws three times its declared
             * weight, so a bias is a noticeable nudge rather than a
             * guarantee. The boost is applied here rather than in the
             * room's own {@code weight} field so a reload that removes
             * the recipe restores the room's original distribution without
             * a manifest republish.
             */
            private int weightOf(RoomManifest.Match match) {
                int base = biasedWeight(match)
                        * RoomEligibility.weightFactor(RoomEligibility.RoomTags.of(match.entry().meta), floor);
                if (!weightedRooms.isEmpty()
                        && weightedRooms.contains(match.entry().name)) {
                    return base * 3;
                }
                return base;
            }
        }

        /** How many times each room is already placed at cells before this one. */
        private Map<String, Integer> usedBefore(int index) {
            Map<String, Integer> used = new HashMap<>();
            for (int i = 0; i < index; i++) {
                if (assignments[i] != null) {
                    used.merge(assignments[i].entry().name, 1, Integer::sum);
                }
            }
            return used;
        }

        /** Which branches have already spent their one gated cell (spec 6.1). */
        private Set<Integer> gatedBefore(int index) {
            Set<Integer> gated = new HashSet<>();
            for (int i = 0; i < index; i++) {
                if (assignments[i] != null
                        && DungeonRoomMeta.ACCESS_GATED.equals(assignments[i].entry().meta.access)) {
                    gated.add(branches.getOrDefault(order.get(i), SPINE_BRANCH));
                }
            }
            return gated;
        }
    }

    /** Cells in BFS order: shallowest first, then by grid position for stability. */
    private static List<PlanCell> bfsOrder(Set<PlanCell> cells, Map<PlanCell, Integer> depths) {
        List<PlanCell> order = new ArrayList<>(cells);
        order.sort(Comparator.comparingInt((PlanCell c) -> depths.getOrDefault(c, 0))
                .thenComparingInt(PlanCell::x)
                .thenComparingInt(PlanCell::z));
        return order;
    }

    /** Neighbour lists over the open edges, each sorted so a walk is reproducible. */
    private static Map<PlanCell, List<PlanCell>> adjacency(Set<PlanEdge> edges) {
        Map<PlanCell, List<PlanCell>> adjacency = new HashMap<>();
        for (PlanEdge edge : edges) {
            adjacency.computeIfAbsent(edge.a(), k -> new ArrayList<>()).add(edge.b());
            adjacency.computeIfAbsent(edge.b(), k -> new ArrayList<>()).add(edge.a());
        }
        Comparator<PlanCell> byPosition =
                Comparator.comparingInt(PlanCell::x).thenComparingInt(PlanCell::z);
        for (List<PlanCell> neighbors : adjacency.values()) {
            neighbors.sort(byPosition);
        }
        return adjacency;
    }

    /**
     * The shortest entrance-to-staging-room route, which is the only route the
     * player is guaranteed to take (spec 6.5) and the one the {@code access}
     * rule and the consumable rule are written against. Returns a list from
     * {@code from} to {@code to} inclusive, or just {@code from} if the two are
     * not connected, which shape validation would already have rejected.
     */
    private static List<PlanCell> shortestPath(DungeonShape shape, PlanCell from, PlanCell to) {
        Map<PlanCell, List<PlanCell>> adjacency = adjacency(shape.openEdges());
        Map<PlanCell, PlanCell> parents = new HashMap<>();
        Set<PlanCell> seen = new HashSet<>();
        Deque<PlanCell> queue = new ArrayDeque<>();
        seen.add(from);
        queue.add(from);
        while (!queue.isEmpty()) {
            PlanCell current = queue.poll();
            if (current.equals(to)) {
                break;
            }
            for (PlanCell neighbor : adjacency.getOrDefault(current, List.of())) {
                if (seen.add(neighbor)) {
                    parents.put(neighbor, current);
                    queue.add(neighbor);
                }
            }
        }
        if (!seen.contains(to)) {
            return List.of(from);
        }
        List<PlanCell> path = new ArrayList<>();
        for (PlanCell cell = to; cell != null; cell = parents.get(cell)) {
            path.add(cell);
            if (cell.equals(from)) {
                break;
            }
        }
        java.util.Collections.reverse(path);
        return path;
    }

    /**
     * One id per branch, for spec 6.1's "at most one gated cell per branch".
     * Every cell on the spine shares {@link #SPINE_BRANCH}; every other cell
     * takes the id of its connected component once the spine is removed, which
     * is exactly "the cells you reach by leaving the main route at one point".
     */
    private static Map<PlanCell, Integer> branchGroups(DungeonShape shape, Set<PlanCell> onSpine) {
        Map<PlanCell, List<PlanCell>> adjacency = adjacency(shape.openEdges());
        Map<PlanCell, Integer> groups = new HashMap<>();
        for (PlanCell cell : onSpine) {
            groups.put(cell, SPINE_BRANCH);
        }
        int next = 0;
        List<PlanCell> ordered = new ArrayList<>(shape.cells());
        ordered.sort(Comparator.comparingInt(PlanCell::x).thenComparingInt(PlanCell::z));
        for (PlanCell start : ordered) {
            if (groups.containsKey(start)) {
                continue;
            }
            int id = next++;
            Deque<PlanCell> queue = new ArrayDeque<>();
            groups.put(start, id);
            queue.add(start);
            while (!queue.isEmpty()) {
                PlanCell current = queue.poll();
                for (PlanCell neighbor : adjacency.getOrDefault(current, List.of())) {
                    if (!groups.containsKey(neighbor)) {
                        groups.put(neighbor, id);
                        queue.add(neighbor);
                    }
                }
            }
        }
        return groups;
    }

    /**
     * The spine cell that should carry the floor's guaranteed gate (spec 6.1:
     * "at least one gated cell on the critical path between entrance and
     * staging room"). Entrance and terminal are excluded: one is the way in and
     * the other is the reward. Audit finding 2.16 is why this only ever looks
     * at spine cells, since a dead-end spur's gate is one the player can walk
     * past without ever meeting.
     *
     * <p>Rolled off its own {@link Random} rather than the selection rng, which
     * has not been consumed yet at this point and whose consumption would then
     * depend on how many cells the shape has.
     *
     * @return the chosen cell, or {@code null} when no spine cell has a gated
     *         room to offer, which is every floor built from a catalogue with
     *         no gated rooms in it
     */
    private static PlanCell designateGateCell(DungeonShape shape, List<PlanCell> spine,
                                              RoomManifest manifest, String theme, int maxTier,
                                              RoomEligibility.Floor floor) {
        List<PlanCell> eligible = new ArrayList<>();
        for (PlanCell cell : spine) {
            if (cell.equals(shape.entrance()) || cell.equals(shape.terminal())) {
                continue;
            }
            String role = shape.roles().get(cell);
            if (role == null) {
                continue;
            }
            for (RoomManifest.Match match : query(manifest, requiredMask(shape, cell), role, theme, floor)) {
                // PD-139: a gate above the floor's tier is no gate to offer;
                // designating it forced a tier 2 or 3 room onto a keystone 1
                // floor and logged a "Room tier gap" every plan.
                if (DungeonRoomMeta.ACCESS_GATED.equals(match.entry().meta.access)
                        && match.entry().meta.tier <= maxTier) {
                    eligible.add(cell);
                    break;
                }
            }
        }
        if (eligible.isEmpty()) {
            return null;
        }
        return eligible.get(new Random(shape.seed() * 53 + 7).nextInt(eligible.size()));
    }

    /**
     * The candidates a cell may legally take: 6.6's subset filter, plus the two
     * placement rules that ride along with it.
     *
     * <p>The gate preference is a preference and not a constraint, for the same
     * reason {@link #pick}'s depth and repeat filters are: a floor that fails to
     * resolve because the one room that could have carried its gate was also the
     * one room that fit the mask is worse than a floor with no gate on it.
     *
     * @param onSpine      whether this cell is on the entrance-to-staging path
     * @param branchGated  whether this cell's branch already has its one gate
     * @param isGateCell   whether this cell is the floor's designated gate
     */
    private static List<RoomManifest.Match> solvable(List<RoomManifest.Match> matches,
                                                     Set<String> available, boolean onSpine,
                                                     boolean branchGated, boolean isGateCell, int maxTier) {
        List<RoomManifest.Match> pool = withTags(matches, available, onSpine, branchGated);
        if (pool.isEmpty() && branchGated) {
            // The one-gate-per-branch rule emptied the pool on its own. It is a
            // spacing rule, and spacing is not worth a cell falling back to a
            // room that asks for nothing when a solvable room was sitting
            // right there, so it yields. On any catalogue with an open room per
            // role this never fires.
            pool = withTags(matches, available, onSpine, false);
        }
        if (!isGateCell) {
            return pool;
        }
        List<RoomManifest.Match> gated = new ArrayList<>();
        for (RoomManifest.Match match : pool) {
            if (DungeonRoomMeta.ACCESS_GATED.equals(match.entry().meta.access)
                    && match.entry().meta.tier <= maxTier) {
                gated.add(match);
            }
        }
        return gated.isEmpty() ? pool : gated;
    }

    /** 6.6's subset filter plus the consumable and one-gate-per-branch rules. */
    private static List<RoomManifest.Match> withTags(List<RoomManifest.Match> matches,
                                                     Set<String> available, boolean onSpine,
                                                     boolean branchGated) {
        List<RoomManifest.Match> pool = new ArrayList<>(matches.size());
        for (RoomManifest.Match match : matches) {
            DungeonRoomMeta meta = match.entry().meta;
            // E (D25): off the spine a room may ask for a tool the party does
            // not carry; those are the bonus rooms. Only the spine must prove
            // solvable with the Pilgrim seed and upstream provides.
            if (onSpine && !available.containsAll(meta.requires)) {
                continue;
            }
            if (onSpine && !java.util.Collections.disjoint(meta.requires, CONSUMABLE_TAGS)) {
                continue;
            }
            if (branchGated && DungeonRoomMeta.ACCESS_GATED.equals(meta.access)) {
                continue;
            }
            pool.add(match);
        }
        return pool;
    }

    /**
     * 6.6 step 6's last resort: the rooms that ask for nothing. A plain corridor
     * or encounter is always solvable, so a bag can make a floor duller and
     * never impossible. If even that is empty the whole match list comes back,
     * because failing the plan here would reintroduce M3's failure mode.
     */
    private static List<RoomManifest.Match> roleOnly(List<RoomManifest.Match> matches) {
        List<RoomManifest.Match> pool = new ArrayList<>(matches.size());
        for (RoomManifest.Match match : matches) {
            if (match.entry().meta.requires.isEmpty()) {
                pool.add(match);
            }
        }
        return pool.isEmpty() ? matches : pool;
    }

    private static List<RoomManifest.Match> sortedMatches(List<RoomManifest.Match> matches) {
        List<RoomManifest.Match> sorted = new ArrayList<>(matches);
        sorted.sort(Comparator.comparing((RoomManifest.Match m) -> m.entry().name)
                .thenComparingInt(RoomManifest.Match::rotation));
        return sorted;
    }

    /**
     * Hop count from the entrance, over open edges.
     *
     * <p>M45 already computes this once, in the same walk the generator's
     * reachability check uses, and hands it over on the shape. This reads it
     * back rather than walking again; the walk below is kept for a shape
     * assembled by hand without distances, and for the cell an unfinished shape
     * leaves out of the map, which is defaulted rather than left null for the
     * stamper to trip over.
     */
    private static Map<PlanCell, Integer> depths(DungeonShape shape) {
        Map<PlanCell, Integer> known = shape.rootDistances();
        if (known != null && known.keySet().containsAll(shape.cells())) {
            return new HashMap<>(known);
        }
        Map<PlanCell, List<PlanCell>> adjacency = adjacency(shape.openEdges());

        Map<PlanCell, Integer> depths = new HashMap<>();
        Deque<PlanCell> queue = new ArrayDeque<>();
        depths.put(shape.entrance(), 0);
        queue.add(shape.entrance());
        while (!queue.isEmpty()) {
            PlanCell current = queue.poll();
            int next = depths.get(current) + 1;
            for (PlanCell neighbor : adjacency.getOrDefault(current, List.of())) {
                if (depths.putIfAbsent(neighbor, next) == null) {
                    queue.add(neighbor);
                }
            }
        }
        // An unreachable cell would already have failed shape validation. Default
        // it rather than leave a null in the map for the stamper to trip over.
        for (PlanCell cell : shape.cells()) {
            depths.putIfAbsent(cell, 0);
        }
        return depths;
    }

    private static int requiredMask(DungeonShape shape, PlanCell cell) {
        Set<DoorMask.Direction> dirs = new LinkedHashSet<>();
        for (PlanEdge edge : shape.openEdges()) {
            if (!edge.touches(cell)) {
                continue;
            }
            PlanCell other = edge.other(cell);
            DoorMask.Direction dir = cell.directionTo(other);
            if (dir != null) {
                dirs.add(dir);
            }
        }
        return DoorMask.fromEdges(dirs);
    }

    /**
     * M70: whether a role id is one of the engine-owned structural roles
     * (entrance, exit). Structural roles are not loaded from the role
     * manifest, so the selector's unknown-role check accepts them here.
     */
    private static boolean isStructuralRole(String role) {
        return RoleIds.ENTRANCE.equals(role) || RoleIds.EXIT.equals(role);
    }

    private static Set<PlanCell> reachableCells(DungeonPlan plan) {
        Set<PlanCell> reachable = new HashSet<>();
        reachable.add(plan.entrance());

        boolean changed;
        do {
            changed = false;
            for (PlanCell cell : List.copyOf(reachable)) {
                for (PlanEdge edge : plan.doors()) {
                    if (!edge.touches(cell)) {
                        continue;
                    }
                    if (reachable.add(edge.other(cell))) {
                        changed = true;
                    }
                }
            }
        } while (changed);
        return reachable;
    }

    /**
     * @param fallbackCells  M47: cells the solvability pass could not satisfy
     *                       and filled with a role-only, empty-{@code requires}
     *                       room instead. Empty on a clean resolve. A run is
     *                       never unsolvable because of a bag (6.6 step 6); it
     *                       is only less interesting, and this set says by how
     *                       much.
     * @param backtrackSteps how many times the pass gave a cell back and took
     *                       the previous cell's next candidate. Zero means the
     *                       first greedy assignment was already satisfying.
     * @param resourceShort  PD-149: a floor whose layout cannot place the
     *                       {@code minNodeRooms} node rooms its dungeon asks for. The
     *                       planner tries another layout before settling for it.
     */
    record Result(DungeonPlan plan, Failure failure, Set<PlanCell> fallbackCells,
                  int backtrackSteps, boolean resourceShort) {

        Result(DungeonPlan plan, Failure failure) {
            this(plan, failure, Set.of(), 0, false);
        }

        Result(DungeonPlan plan, Failure failure, Set<PlanCell> fallbackCells, int backtrackSteps) {
            this(plan, failure, fallbackCells, backtrackSteps, false);
        }
    }

    record Failure(PlanCell cell, int mask, String role) {}
}
