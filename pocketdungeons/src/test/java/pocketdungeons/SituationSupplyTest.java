package pocketdungeons;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M64 (SITUATIONS_SPEC 6.4 and 6.6, audit findings 2.6, 2.7 and 2.17):
 * declared tags versus actual supplies.
 *
 * <p>The solvability pass treats {@code available} as a boolean set of tags:
 * once a tag is in the set it stays in at every deeper distance. That is only
 * sound while two rules hold, and this test is the fixture that catches the
 * cases where they do not:
 * <ul>
 *   <li><strong>Spec 6.4 item-return.</strong> A gate that takes an item must
 *       return it after the gate opens. A gate that eats the item breaks the
 *       boolean model silently: the algorithm says the next room is solvable,
 *       but in play the tool is gone.</li>
 *   <li><strong>Finite bag tools.</strong> A bag item that is consumed in use
 *       (TNT, a lead, a trial key) is not a renewable provider. The boolean
 *       model cannot tell the difference between a renewable source and a
 *       finite one, so a floor that relies on a finite bag tool for two
 *       consecutive gates is solvable on paper and stuck in play.</li>
 * </ul>
 *
 * <p>The mitigations this test pins down:
 * <ul>
 *   <li>A consumable tag (audit 2.6: {@code trial_key}) may appear in a spur's
 *       {@code requires}, where losing it costs an optional reward, and never
 *       in the {@code requires} of a cell on the entrance-to-staging path,
 *       where losing it would cost the route.</li>
 *   <li>A finite bag tool (Sapper's TNT) is not reusable masonry: the catalogue
 *       must carry a renewable {@code blocks} provider or a tool-free fallback
 *       so a spent tool may lose treasure but never the mandatory exit.</li>
 *   <li>The Shepherd's {@code mob} tag is conditional: a lead alone is not a
 *       guaranteed creature. Party size 2 grants {@code mob} unconditionally;
 *       a solo Shepherd's {@code mob} is the over-promise the audit flags.</li>
 * </ul>
 *
 * <p>Pure JDK, like {@link GraphSolvabilityTest}: shapes and manifests are
 * built by hand, no server, no Minecraft world.
 */
public class SituationSupplyTest {

    public static void main(String[] args) {
        // M70: publish synthetic bag and role manifests so BagTags.seed
        // reads the same tags the live server would load.
        publishSyntheticManifests();
        testBagSuppliesAreWhatTheCatalogueClaims();
        testSappersTntIsNotReusableMasonry();
        testShepherdsLeadsAreNotAGuaranteedCreature();
        testBothPlayersGetThroughPlatePair();
        testSpentOptionalToolMayLoseTreasureNeverTheExit();
        testFiniteToolRequiresRenewableProviderOrToolFreeFallback();
        System.out.println("SituationSupplyTest passed");
    }

    // ---- what each bag actually carries ----

    /**
     * The bag tag sets the solvability pass seeds from. These are the declared
     * supplies the rest of this test measures against, so a drift in
     * {@link Bags} surfaces here rather than as a silent change to what a door
     * promises.
     */
    private static void testBagSuppliesAreWhatTheCatalogueClaims() {
        // Pilgrim: bread and nothing else. The strictest seed.
        check(BagTags.seed("pilgrim", 1).isEmpty(), true,
                "solo Pilgrim carries no tags");
        // Sapper: TNT grants blocks, not redstone. Finite, not renewable.
        check(BagTags.seed("sapper", 1).contains(SituationTags.BLOCKS), true,
                "Sapper's TNT grants blocks");
        check(BagTags.seed("sapper", 1).contains(SituationTags.REDSTONE), false,
                "flint and steel powers nothing; Sapper does not claim redstone");
        // Shepherd: leads and bones. The mob tag is the over-promise this test
        // examines below: a lead is not a creature.
        check(BagTags.seed("shepherd", 1).contains(SituationTags.LEAD), true,
                "Shepherd carries lead");
        check(BagTags.seed("shepherd", 1).contains(SituationTags.MOB), true,
                "Shepherd claims mob, conditionally");
        // Plumber: two buckets, water and lava. Both are renewable in the
        // bucket sense: a bucket does not get consumed.
        check(BagTags.seed("plumber", 1).contains(SituationTags.WATER), true,
                "Plumber carries water");
        check(BagTags.seed("plumber", 1).contains(SituationTags.LAVA), true,
                "Plumber carries lava");
    }

    // ---- Sapper's TNT is not reusable masonry ----

    /**
     * The Sapper bag carries {@code blocks} (TNT). TNT is consumed in use: three
     * sticks, three walls, then the bag is empty. The boolean model says
     * {@code blocks} is available at every depth, so two consecutive
     * {@code blocks}-requiring rooms on the spine resolve without a fallback.
     * That is the over-promise: the model says solvable, the player is stuck.
     *
     * <p>This fixture documents the gap. The mitigation, asserted in
     * {@link #testFiniteToolRequiresRenewableProviderOrToolFreeFallback}, is
     * that the catalogue carries a renewable {@code blocks} provider so a floor
     * can place one upstream of the gate, making the gate solvable without
     * spending the bag's TNT at all.
     */
    private static void testSappersTntIsNotReusableMasonry() {
        DungeonShape shape = line(4, "corridor");
        PlanCell first = new PlanCell(1, 0);
        PlanCell second = new PlanCell(2, 0);

        List<RoomManifest.Entry> entries = new ArrayList<>();
        entries.addAll(everyMask("hall", List.of("entrance"), List.of(), List.of()));
        entries.addAll(everyMask("way_out", List.of("exit"), List.of(), List.of()));
        entries.addAll(everyMask("rubble", List.of("corridor"), List.of(),
                List.of(SituationTags.BLOCKS)));
        RoomManifest manifest = RoomManifest.create(entries, List.of());

        Set<String> sapper = BagTags.seed("sapper", 1);
        RoomSelector.Result result = RoomSelector.resolveDetailed(shape, manifest, null, sapper);

        if (result.plan() == null) {
            throw new AssertionError("the Sapper's blocks should resolve both rooms; failed at "
                    + result.failure());
        }
        // The boolean model says both gates are solvable from the bag alone,
        // because blocks is depth 0 and stays available. That is the gap: TNT
        // is finite, and the model cannot see it.
        check(result.fallbackCells().isEmpty(), true,
                "the boolean model says both blocks gates are solvable from the bag; "
                        + "TNT is finite, so this is the over-promise, not a proof");
        // Both cells took the blocks-requiring room, which is exactly what the
        // model gets wrong: it assumes the tool is reusable.
        String atFirst = result.plan().rooms().get(first).name();
        String atSecond = result.plan().rooms().get(second).name();
        check(atFirst.startsWith("rubble"), true,
                "first blocks gate took the rubble room; got " + atFirst);
        check(atSecond.startsWith("rubble"), true,
                "second blocks gate took the rubble room; got " + atSecond
                        + "; the model reused the spent TNT, which is the gap");
    }

    // ---- Shepherd's leads are not a guaranteed creature ----

    /**
     * The Shepherd bag carries {@code lead} and {@code mob}. A lead is not a
     * creature: it is the tool for moving one, and bones are the tool for
     * taming one, but neither spawns a mob. Audit finding 2.7 narrows
     * {@code mob} to "a leashable mob is obtainable at lower BFS depth, or
     * party size is 2 or more". The Shepherd's own {@code mob} tag is the
     * over-promise: a solo Shepherd has the tools but not the animal.
     *
     * <p>What is unconditional is party size: two players stand on the two
     * plates, and {@link BagTags#seed} grants {@code mob} at depth 0 for any
     * party of two or more, regardless of bag. That is the case Plate Pair
     * actually rests on for a solo player who finds no mob upstream: it does
     * not, and the solo Shepherd is the bag that exposes it.
     */
    private static void testShepherdsLeadsAreNotAGuaranteedCreature() {
        // Party size 2 grants mob unconditionally, for any bag.
        check(BagTags.seed("pilgrim", 2).contains(SituationTags.MOB), true,
                "party size 2 grants mob even for Pilgrim");
        check(BagTags.seed("shepherd", 2).contains(SituationTags.MOB), true,
                "party size 2 grants mob for Shepherd too");
        // Solo Shepherd claims mob from the bag. That is the over-promise:
        // a lead and bones do not spawn a mob. The tag is in the set, but the
        // supply behind it is conditional on an upstream leashable mob.
        check(BagTags.seed("shepherd", 1).contains(SituationTags.MOB), true,
                "solo Shepherd claims mob from the bag; the supply is conditional, not guaranteed");

        // A solo Pilgrim has no mob at all, so a mob gate on the spine must
        // fall back. That is the honest case: the model does not pretend. The
        // plate_pair room is the only corridor room, so the solo Pilgrim has no
        // tool-free alternative and the cell is forced into a fallback.
        DungeonShape shape = line(3, "corridor");
        PlanCell gate = new PlanCell(1, 0);
        List<RoomManifest.Entry> entries = new ArrayList<>();
        entries.addAll(everyMask("hall", List.of("entrance"), List.of(), List.of()));
        entries.addAll(everyMask("way_out", List.of("exit"), List.of(), List.of()));
        entries.addAll(everyMask("plate_pair", List.of("corridor"), List.of(),
                List.of(SituationTags.MOB)));
        RoomManifest manifest = RoomManifest.create(entries, List.of());

        RoomSelector.Result pilgrim = RoomSelector.resolveDetailed(shape, manifest, null,
                BagTags.pilgrim());
        check(pilgrim.fallbackCells().contains(gate), true,
                "solo Pilgrim has no mob, so the mob gate must fall back; got "
                        + pilgrim.fallbackCells());

        // A solo Shepherd claims mob from the bag, so the model places the gate.
        // That is the over-promise: the Shepherd has a lead, not a creature.
        RoomSelector.Result shepherd = RoomSelector.resolveDetailed(shape, manifest, null,
                BagTags.seed("shepherd", 1));
        check(shepherd.fallbackCells().contains(gate), false,
                "solo Shepherd claims mob, so the model places the gate; "
                        + "the supply is conditional, so this is the gap, not a proof");
        check(shepherd.plan().rooms().get(gate).name().startsWith("plate_pair"), true,
                "solo Shepherd's mob tag placed Plate Pair; got "
                        + shepherd.plan().rooms().get(gate).name());
    }

    // ---- both players must get through Plate Pair ----

    /**
     * Plate Pair requires {@code mob}. The honest supply is party size 2: two
     * players stand on the two plates. A solo player must find a leashable mob
     * upstream, or the room falls back to a tool-free corridor.
     *
     * <p>This fixture puts a mob gate on the spine and resolves it for a party
     * of one and a party of two. The party of two resolves cleanly; the solo
     * player falls back. That is the contract: the bag never makes Plate Pair
     * free for a solo player, and a second player always does.
     */
    private static void testBothPlayersGetThroughPlatePair() {
        DungeonShape shape = line(3, "corridor");
        PlanCell gate = new PlanCell(1, 0);
        List<RoomManifest.Entry> entries = new ArrayList<>();
        entries.addAll(everyMask("hall", List.of("entrance"), List.of(), List.of()));
        entries.addAll(everyMask("way_out", List.of("exit"), List.of(), List.of()));
        // Plate Pair is the only corridor room: solo has no mob and must fall
        // back; a party of two grants mob and resolves it cleanly.
        entries.addAll(everyMask("plate_pair", List.of("corridor"), List.of(),
                List.of(SituationTags.MOB), DungeonRoomMeta.ACCESS_GATED, 8));
        RoomManifest manifest = RoomManifest.create(entries, List.of());

        // Solo: no mob, gate falls back.
        RoomSelector.Result solo = RoomSelector.resolveDetailed(shape, manifest, null,
                BagTags.seed("pilgrim", 1));
        check(solo.fallbackCells().contains(gate), true,
                "solo Pilgrim has no mob; Plate Pair must fall back");

        // Party of two: mob from party size, gate resolves.
        RoomSelector.Result duo = RoomSelector.resolveDetailed(shape, manifest, null,
                BagTags.seed("pilgrim", 2));
        check(duo.fallbackCells().contains(gate), false,
                "party of two grants mob; Plate Pair must resolve");
        check(duo.plan().rooms().get(gate).name().startsWith("plate_pair"), true,
                "party of two placed Plate Pair; got "
                        + duo.plan().rooms().get(gate).name());
    }

    // ---- a spent optional tool may lose treasure, never the exit ----

    /**
     * Audit finding 2.6: a trial key is spent, and a boolean set cannot say so.
     * The rule is that a consumable may appear in a spur's {@code requires},
     * where losing it costs an optional reward, and never in the
     * {@code requires} of a cell on the entrance-to-staging path, where losing
     * it would cost the route.
     *
     * <p>This fixture puts a key at distance 1 and a room that wants one at
     * distance 2 twice over: once on the spine, once down a spur. The spine
     * cell must refuse it and the spur cell must take it. A spent optional tool
     * may lose treasure (the spur's reward), never the mandatory exit (the
     * spine's route to the staging room).
     */
    private static void testSpentOptionalToolMayLoseTreasureNeverTheExit() {
        PlanCell entrance = new PlanCell(0, 0);
        PlanCell pots = new PlanCell(1, 0);
        PlanCell onPath = new PlanCell(2, 0);
        PlanCell spur = new PlanCell(1, 1);

        Set<PlanCell> cells = new LinkedHashSet<>(List.of(entrance, pots, onPath, spur));
        Set<PlanEdge> edges = new LinkedHashSet<>(List.of(
                new PlanEdge(entrance, pots), new PlanEdge(pots, onPath),
                new PlanEdge(pots, spur)));
        Map<PlanCell, String> roles = new LinkedHashMap<>();
        roles.put(entrance, RoleIds.ENTRANCE);
        roles.put(pots, RoleIds.LOOT);
        roles.put(onPath, RoleIds.ENCOUNTER);
        roles.put(spur, RoleIds.CORRIDOR);

        DungeonShape shape = new DungeonShape(41, cells, edges, entrance, onPath,
                List.of(entrance, pots, onPath), roles);

        List<RoomManifest.Entry> entries = new ArrayList<>();
        entries.addAll(everyMask("hall", List.of("entrance"), List.of(), List.of()));
        entries.addAll(everyMask("pot_room", List.of("loot"),
                List.of(SituationTags.TRIAL_KEY), List.of()));
        entries.addAll(everyMask("plain_fight", List.of("encounter"), List.of(), List.of()));
        // The altar wants a key on the spine: the consumable rule must refuse it.
        entries.addAll(everyMask("the_altar", List.of("encounter"), List.of(),
                List.of(SituationTags.TRIAL_KEY), DungeonRoomMeta.ACCESS_OPEN, 500));
        // The barred vault wants a key down a spur: the consumable rule allows it.
        entries.addAll(everyMask("barred_vault", List.of("corridor"), List.of(),
                List.of(SituationTags.TRIAL_KEY)));
        RoomManifest manifest = RoomManifest.create(entries, List.of());

        RoomSelector.Result result = RoomSelector.resolveDetailed(shape, manifest, null,
                BagTags.pilgrim());

        String atPath = result.plan().rooms().get(onPath).name();
        check(atPath.startsWith("plain_fight"), true,
                "a consumable tag may never gate a cell on the spine; cell " + onPath
                        + " took " + atPath);
        String atSpur = result.plan().rooms().get(spur).name();
        check(atSpur.startsWith("barred_vault"), true,
                "a spur may spend the key; cell " + spur + " should have taken "
                        + "barred_vault, got " + atSpur);
        check(result.fallbackCells().contains(spur), false,
                "the spur's vault is satisfiable and should not have been a fallback; "
                        + "fallbacks were " + result.fallbackCells());
    }

    // ---- finite tool requires renewable provider or tool-free fallback ----

    /**
     * The Sapper's TNT is finite, so the boolean model's claim that
     * {@code blocks} is always available is an over-promise. The mitigation is
     * that the catalogue carries a renewable {@code blocks} provider: a room
     * that {@code provides: [blocks]} without consuming a bag tool. With one
     * upstream, a {@code blocks}-requiring gate is solvable from the room's
     * supply, not the bag's, and the spent TNT is irrelevant.
     *
     * <p>This fixture puts a renewable {@code blocks} provider at distance 1
     * and a {@code blocks}-requiring gate at distance 2, and resolves it for a
     * solo Pilgrim (no bag tool at all). The gate resolves from the room's
     * supply, which is the honest path: the bag's TNT was never the provider.
     */
    private static void testFiniteToolRequiresRenewableProviderOrToolFreeFallback() {
        PlanCell entrance = new PlanCell(0, 0);
        PlanCell provider = new PlanCell(1, 0);
        PlanCell gate = new PlanCell(2, 0);

        Set<PlanCell> cells = new LinkedHashSet<>(List.of(entrance, provider, gate));
        Set<PlanEdge> edges = new LinkedHashSet<>(List.of(
                new PlanEdge(entrance, provider), new PlanEdge(provider, gate)));
        Map<PlanCell, String> roles = new LinkedHashMap<>();
        roles.put(entrance, RoleIds.ENTRANCE);
        roles.put(provider, RoleIds.LOOT);
        roles.put(gate, RoleIds.CORRIDOR);

        DungeonShape shape = new DungeonShape(43, cells, edges, entrance, gate,
                List.of(entrance, provider, gate), roles);

        List<RoomManifest.Entry> entries = new ArrayList<>();
        entries.addAll(everyMask("hall", List.of("entrance"), List.of(), List.of()));
        // A renewable blocks provider: the room hands out blocks without
        // consuming a bag tool. Infested Wall and Gallery are the catalogue
        // examples; this is the abstract fixture.
        entries.addAll(everyMask("quarry", List.of("loot"),
                List.of(SituationTags.BLOCKS), List.of()));
        entries.addAll(everyMask("rubble", List.of("corridor"), List.of(),
                List.of(SituationTags.BLOCKS)));
        entries.addAll(everyMask("passage", List.of("corridor"), List.of(), List.of()));
        RoomManifest manifest = RoomManifest.create(entries, List.of());

        // Solo Pilgrim: no bag tool at all. The gate resolves from the room's
        // renewable supply, which is the honest path.
        RoomSelector.Result result = RoomSelector.resolveDetailed(shape, manifest, null,
                BagTags.pilgrim());
        check(result.fallbackCells().contains(gate), false,
                "a renewable blocks provider upstream must satisfy the gate without "
                        + "any bag tool; fell back at " + result.fallbackCells());
        check(result.plan().rooms().get(provider).name().startsWith("quarry"), true,
                "the renewable provider was placed; got "
                        + result.plan().rooms().get(provider).name());
        check(result.plan().rooms().get(gate).name().startsWith("rubble"), true,
                "the blocks gate took the rubble room from the renewable supply; got "
                        + result.plan().rooms().get(gate).name());

        // The tool-free fallback: with no provider and no bag tool, the
        // selector picks the passage (a room requiring nothing) rather than
        // stranding the player on a blocks gate they cannot open. This is the
        // tool-free slow path the catalogue must offer: a room with empty
        // requires for every role a gated room occupies, so a spent tool costs
        // treasure (the gated room is not placed) but never the exit.
        List<RoomManifest.Entry> noProvider = new ArrayList<>();
        noProvider.addAll(everyMask("hall", List.of("entrance"), List.of(), List.of()));
        noProvider.addAll(everyMask("hoard", List.of("loot"), List.of(), List.of()));
        noProvider.addAll(everyMask("rubble", List.of("corridor"), List.of(),
                List.of(SituationTags.BLOCKS)));
        noProvider.addAll(everyMask("passage", List.of("corridor"), List.of(), List.of()));
        RoomManifest noProviderManifest = RoomManifest.create(noProvider, List.of());

        RoomSelector.Result stranded = RoomSelector.resolveDetailed(shape, noProviderManifest,
                null, BagTags.pilgrim());
        check(stranded.plan() != null, true,
                "no provider and no bag tool: the plan still resolves; the player is "
                        + "never stranded");
        check(stranded.plan().rooms().get(gate).name().startsWith("passage"), true,
                "the tool-free passage was picked over the unsatisfiable rubble room; "
                        + "got " + stranded.plan().rooms().get(gate).name());
    }

    // ---- fixtures ----

    /**
     * A straight corridor of {@code n} cells: entrance, then {@code middleRole}
     * for everything but the last, which is the exit and the terminal.
     */
    private static DungeonShape line(int n, String middleRole) {
        Set<PlanCell> cells = new LinkedHashSet<>();
        List<PlanCell> path = new ArrayList<>();
        Map<PlanCell, String> roles = new LinkedHashMap<>();
        // M70: namespaced role ids, matching the generator.
        String entrance = RoleIds.ENTRANCE;
        String exit = RoleIds.EXIT;
        String middle = RoleIds.resolve(middleRole);
        if (middle == null) {
            middle = middleRole;
        }
        for (int i = 0; i < n; i++) {
            PlanCell cell = new PlanCell(i, 0);
            cells.add(cell);
            path.add(cell);
            roles.put(cell, i == 0 ? entrance : (i == n - 1 ? exit : middle));
        }
        Set<PlanEdge> edges = new LinkedHashSet<>();
        for (int i = 0; i < n - 1; i++) {
            edges.add(new PlanEdge(path.get(i), path.get(i + 1)));
        }
        return new DungeonShape(3, cells, edges, path.get(0), path.get(n - 1), path, roles);
    }

    /**
     * One entry per door mask, so a fixture never fails because the room that
     * carried the tag under test did not fit the cell's walls.
     */
    private static List<RoomManifest.Entry> everyMask(String name, List<String> roles,
                                                      List<String> provides, List<String> requires) {
        return everyMask(name, roles, provides, requires, DungeonRoomMeta.ACCESS_OPEN, 1);
    }

    private static List<RoomManifest.Entry> everyMask(String name, List<String> roles,
                                                      List<String> provides, List<String> requires,
                                                      String access, int weight) {
        List<RoomManifest.Entry> out = new ArrayList<>();
        for (int mask = 1; mask <= 15; mask++) {
            out.add(new RoomManifest.Entry(name + "_" + DoorMask.toLetters(mask),
                    meta(name, roles, provides, requires, access, weight), mask));
        }
        return out;
    }

    private static DungeonRoomMeta meta(String template, List<String> roles, List<String> provides,
                                        List<String> requires, String access, int weight) {
        // M70: qualify bare role names to namespaced ids, matching the
        // generator's namespaced assignment.
        List<String> qualified = new ArrayList<>(roles.size());
        for (String role : roles) {
            String resolved = RoleIds.resolve(role);
            qualified.add(resolved == null ? role : resolved);
        }
        return new DungeonRoomMeta(template, 1, 1, List.copyOf(qualified), weight, 0, -1, null, List.of(), null,
                1, provides, requires, null, access, DungeonRoomMeta.WINDOW_BARS, 1);
    }

    private static void check(Object actual, Object expected, String what) {
        boolean equal = expected == null ? actual == null : expected.equals(actual);
        if (!equal) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }

    /**
     * M70: publishes synthetic bag and role manifests with the built-in
     * definitions and their pre-M70 tag sets, so {@link BagTags#seed}
     * reads the same tags the live server would load from
     * {@code dungeon_bag/*.json}. The headless test has no Minecraft server.
     */
    private static void publishSyntheticManifests() {
        Map<String, BagManifest.Entry> bags = new LinkedHashMap<>();
        Object[][] bagData = {
                {BagIds.MASON, java.util.Set.of(SituationTags.BLOCKS), 0},
                {BagIds.PLUMBER, java.util.Set.of(SituationTags.WATER, SituationTags.LAVA), 1},
                {BagIds.SAPPER, java.util.Set.of(SituationTags.BLOCKS), 2},
                {BagIds.MAGICIAN, java.util.Set.of(SituationTags.PEARL, SituationTags.WIND_CHARGE), 3},
                {BagIds.RANGER, java.util.Set.of(SituationTags.BOW), 4},
                {BagIds.SHEPHERD, java.util.Set.of(SituationTags.LEAD, SituationTags.MOB), 5},
                {BagIds.INNKEEPER, java.util.Set.of(SituationTags.MILK), 6},
                {BagIds.PILGRIM, java.util.Set.of(), 7},
                {BagIds.GUARD, java.util.Set.of(), 8},
        };
        for (Object[] b : bagData) {
            String id = (String) b[0];
            @SuppressWarnings("unchecked")
            Set<String> tags = (Set<String>) b[1];
            int order = (int) b[2];
            BagDefinition def = new BagDefinition(id, id, "", order, List.of(), tags,
                    BagMeta.defaultLootTable(id));
            bags.put(id, new BagManifest.Entry(id, def));
        }
        BagManifest.publish(BagManifest.create(bags, List.of()));

        Map<String, RoleManifest.Entry> roles = new LinkedHashMap<>();
        Object[][] roleData = {
                {RoleIds.ENCOUNTER, 45, RoomRoleDefinition.Operation.TRIAL_ENCOUNTER},
                {RoleIds.LOOT, 25, RoomRoleDefinition.Operation.TOOL_CACHE},
                {RoleIds.CORRIDOR, 30, RoomRoleDefinition.Operation.NONE},
        };
        for (Object[] r : roleData) {
            String id = (String) r[0];
            int weight = (int) r[1];
            RoomRoleDefinition.Operation op = (RoomRoleDefinition.Operation) r[2];
            RoomRoleDefinition def = new RoomRoleDefinition(id, "interior", weight, 0, -1, List.of(), op);
            roles.put(id, new RoleManifest.Entry(id, def));
        }
        RoleManifest.publish(RoleManifest.create(roles, List.of()));
    }
}
