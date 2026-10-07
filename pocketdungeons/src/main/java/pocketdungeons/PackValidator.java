package pocketdungeons;

import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.loot.LootTable;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * M72: pack validation and release-safe author export.
 *
 * <p>The first surface a non-developer author meets. It does three jobs the
 * bundled {@link DatapackExporter} does not:
 * <ul>
 *   <li><strong>Validate.</strong> Build a candidate {@link ContentSnapshot}
 *       from the live server and report every actionable finding: parse
 *       rejections, required coverage and (mask, role) pairs, reachable
 *       adventure nodes, Cube recipe eligibility and guarantees, missing loot
 *       and spawner references, and a door/return-path plan check with a
 *       reproducible seed. Each finding names a file, a field, a cause, and
 *       (for plan-level findings) a seed the operator can replay with
 *       {@code /dungeon admin plan <seed>}.</li>
 *   <li><strong>Starter pack.</strong> Export a small namespaced starter under
 *       a fresh destination, so an author edits one example of each content
 *       type instead of overriding every built-in the mod bundles.</li>
 *   <li><strong>Author workspace.</strong> Release-safe export of the rooms an
 *       author captured with {@code /dungeon admin buildroom} and
 *       {@code saveroom}, into a distributable pack at a fresh, confirmed
 *       destination.</li>
 * </ul>
 *
 * <p>Both exports refuse to overwrite an existing destination by default. A
 * replacement requires an explicit, destination-specific {@code confirm} and
 * backs the existing destination up first, so an author never clobbers an
 * operator's live datapack by accident. The existing bulk
 * {@link DatapackExporter#export} stays available unchanged for operators.
 *
 * <h2>No source-tree workflow</h2>
 *
 * <p>Everything here runs against a release jar: the starter is bundled as a
 * resource tree under {@code /pack_starter} (outside {@code data/}, so the game
 * never loads it as live content), and the author workspace is the production
 * saveroom datapack. No source-tree-only path is advertised as pack tooling.
 */
final class PackValidator {

    /** The bundled starter pack resource root, outside {@code data/} on purpose. */
    private static final String STARTER_ROOT = "/pack_starter";
    private static final String STARTER_DESCRIPTION = "Pocket Dungeons starter pack";
    /** The production saveroom datapack {@link RoomTemplateGenerator} writes to. */
    private static final String SAVEROOM_PACK = "pocketdungeons_rooms";

    /** No reproducible seed for a static (non-plan) finding. */
    static final long NO_SEED = Long.MIN_VALUE;
    /** The first seed the plan check tries; findings that fail carry their seed. */
    static final long FIRST_SEED = 0L;
    /** How many seeds the plan check tries before declaring the door/return path sound. */
    static final int SEED_TRIES = 16;

    private PackValidator() {}

    /**
     * One actionable finding: the content id or path (file), the field or
     * surface at fault, the cause, and a reproducible seed for plan-level
     * findings ({@link #NO_SEED} for static checks).
     */
    record Finding(String file, String field, String cause, long seed) {
        Finding(String file, String field, String cause) {
            this(file, field, cause, NO_SEED);
        }

        boolean hasSeed() {
            return seed != NO_SEED;
        }
    }

    // ---- Validation --------------------------------------------------------

    /**
     * Builds a candidate snapshot and runs every PackValidator check,
     * sweeping the first {@link #SEED_TRIES} seeds for the plan check.
     */
    static List<Finding> validate(MinecraftServer server) {
        return validate(server, null);
    }

    /**
     * Builds a candidate snapshot and runs every check. When
     * {@code singleSeed} is non-null the plan check runs only that seed (for
     * reproducing a specific failure); otherwise it sweeps
     * {@link #FIRST_SEED} .. {@code FIRST_SEED + SEED_TRIES - 1}.
     */
    static List<Finding> validate(MinecraftServer server, Long singleSeed) {
        ContentSnapshot snapshot = ContentSnapshot.build(server);
        List<Finding> findings = new ArrayList<>();

        addParseFindings(snapshot, findings);
        addCoverageFindings(snapshot, findings);
        addAdventureReachabilityFindings(snapshot, findings);
        List<String> themeIds = new ArrayList<>();
        for (ThemeManifest.Entry theme : snapshot.themes().themes()) {
            themeIds.add(theme.id());
        }
        findings.addAll(dungeonFindings(snapshot.dungeons().all(), themeIds,
                affix -> snapshot.affixes().byId(affix) != null,
                room -> snapshot.rooms().byName(room) != null));
        java.util.Map<String, DungeonRoomMeta> roomMetas = new java.util.TreeMap<>();
        for (RoomManifest.Entry room : snapshot.rooms().rooms()) {
            roomMetas.put(room.name, room.meta);
        }
        Set<String> dungeonIds = new HashSet<>();
        for (DungeonDef def : snapshot.dungeons().all()) {
            dungeonIds.add(def.id());
        }
        findings.addAll(roomMetaFindings(roomMetas, dungeonIds, PackValidator::vanillaBlockExists));
        findings.addAll(zoneRuleFindings(snapshot.themes().themes(), snapshot.adventure().graph(),
                PocketDungeonsConfig.keystoneMaxLevel()));
        addRecipeFindings(snapshot, findings);
        addMissingLootFindings(server, snapshot, findings);
        findings.addAll(kitBaselineFindings(snapshot.bags().definitions(),
                bag -> rolledCounts(server, bag), PackValidator::damageableOrNull));
        addPlanFindings(server, snapshot, singleSeed, findings);

        return findings;
    }

    /**
     * Parse rejections from every surface. Each rejection already names its
     * file and cause; split the two apart so the report's file/field/cause
     * shape stays uniform.
     */
    private static void addParseFindings(ContentSnapshot snapshot, List<Finding> findings) {
        for (String rejection : snapshot.allRejections()) {
            int dash = rejection.indexOf(" - ");
            if (dash > 0) {
                findings.add(new Finding(rejection.substring(0, dash), "parse",
                        rejection.substring(dash + 3)));
            } else {
                findings.add(new Finding(rejection, "parse", "rejected at load"));
            }
        }
    }

    /**
     * Required coverage (entrance/exit and every built-in manifest gate) plus
     * the (mask, role) pairs the planner can ask for, the same check
     * {@code /dungeon admin coverage} runs.
     */
    private static void addCoverageFindings(ContentSnapshot snapshot, List<Finding> findings) {
        for (String error : snapshot.errors()) {
            findings.add(new Finding("coverage", "required", error));
        }
        RoomManifest rooms = snapshot.rooms();
        for (int mask = 1; mask < 16; mask++) {
            boolean singleDoor = Integer.bitCount(mask) == 1;
            for (String role : List.of(RoleIds.ENCOUNTER, RoleIds.LOOT, RoleIds.CORRIDOR,
                    RoleIds.ENTRANCE, RoleIds.EXIT)) {
                if (!singleDoor && (role.equals(RoleIds.ENTRANCE) || role.equals(RoleIds.EXIT))) {
                    continue;
                }
                if (rooms.queryAnyRotation(mask, role).isEmpty()) {
                    findings.add(new Finding("coverage",
                            DoorMask.toLetters(mask) + " / " + role,
                            "no room satisfies this (mask, role) pair"));
                }
            }
        }
    }

    /**
     * Reports adventure nodes no entry theme can reach (a dead branch no door
     * ever offers), and themes that have a dungeon_theme file but no adventure
     * node (never offered as a door choice).
     */
    private static void addAdventureReachabilityFindings(ContentSnapshot snapshot,
                                                          List<Finding> findings) {
        AdventureGraph graph = snapshot.adventure().graph();
        Set<String> reachable = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>(graph.entryThemes());
        reachable.addAll(graph.entryThemes());
        while (!queue.isEmpty()) {
            AdventureGraph.Node node = graph.node(queue.poll());
            if (node == null) {
                continue;
            }
            for (AdventureGraph.Transition t : node.next()) {
                if (reachable.add(t.theme())) {
                    queue.add(t.theme());
                }
            }
        }
        for (String theme : graph.nodeThemes()) {
            if (!reachable.contains(theme)) {
                findings.add(new Finding(theme, "adventure",
                        "node is not reachable from any entry theme; no door ever offers it"));
            }
        }
        Set<String> nodeThemes = new HashSet<>(graph.nodeThemes());
        for (ThemeManifest.Entry theme : snapshot.themes().themes()) {
            if (!nodeThemes.contains(theme.id())) {
                findings.add(new Finding(theme.id(), "adventure",
                        "theme has no adventure node; never offered as a door choice"));
            }
        }
    }

    /**
     * Dungeon structure findings (data/pocketdungeons/dungeon). Structural rule
     * breaks (3 to 6 layers, at most 3 edges out, acyclic, layer n to n+1, one
     * entry on layer 1, a free edge out of every non-final node, main theme on
     * entry and final nodes, borrowed themes from the same or an earlier act,
     * one final node on a capstone, reachability) are the same rules the loader
     * rejects a dungeon for, run here over the given dungeons so a pack author
     * sees them with the dungeon id and node named. On top of that: a node
     * theme, signature affix or room bias entry that does not exist, and a theme no dungeon uses
     * as its main theme (never offered once dungeons drive the doors).
     */
    static List<Finding> dungeonFindings(java.util.Collection<DungeonDef> dungeons, List<String> themeIds,
                                         java.util.function.Predicate<String> affixExists,
                                         java.util.function.Predicate<String> roomExists) {
        List<Finding> findings = new ArrayList<>();
        java.util.Map<String, Integer> actOfTheme = DungeonDefs.actOfTheme(dungeons);
        Set<String> themes = new HashSet<>(themeIds);
        for (DungeonDef def : dungeons) {
            for (String problem : def.problems(theme -> actOfTheme.getOrDefault(theme, 0))) {
                findings.add(new Finding(def.id(), "dungeon", problem));
            }
            for (String block : def.nodePalette()) {
                if (DungeonDef.isStructuralBlock(block)) {
                    findings.add(new Finding(def.id(), "nodePalette",
                            "structural block " + block + " would make walls mineable; a palette holds resource blocks only"));
                }
                if (DungeonDef.isCopperOre(block)) {
                    findings.add(new Finding(def.id(), "nodePalette",
                            "copper ore " + block + " is out of the loot and the palettes (K2.7, K2.8)"));
                }
            }
            if (!themes.contains(def.mainTheme())) {
                findings.add(new Finding(def.id(), "mainTheme", "theme not found: " + def.mainTheme()));
            }
            for (DungeonDef.Node node : def.nodes()) {
                if (node.overridesTheme() && !themes.contains(node.theme())) {
                    findings.add(new Finding(def.id(), "nodes." + node.id() + ".theme",
                            "theme not found: " + node.theme()));
                }
                for (String room : node.roomBias()) {
                    if (!roomExists.test(room)) {
                        findings.add(new Finding(def.id(), "nodes." + node.id() + ".roomBias",
                                "room not found: " + room));
                    }
                }
                if (!node.signatureAffix().isEmpty() && !affixExists.test(node.signatureAffix())) {
                    findings.add(new Finding(def.id(), "nodes." + node.id() + ".signatureAffix",
                            "affix not found: " + node.signatureAffix()));
                }
            }
        }
        for (String theme : themeIds) {
            if (!actOfTheme.containsKey(theme)) {
                findings.add(new Finding(theme, "dungeon",
                        "theme is not the main theme of any dungeon"));
            }
        }
        return findings;
    }

    /**
     * Dungeon structure W4 room metadata findings (design section 7): a room that is
     * {@code dark} and {@code requiresLight} (the darkness pass never darkens it, so
     * the two disagree), a node whose block is not a vanilla block (the pack is
     * server side only: no custom blocks), a {@code graphRole} word the planner does
     * not know, and a {@code dungeons} or {@code borrowableBy} entry no dungeon
     * answers to. {@code blockExists} answers for a full block id; {@code dungeonIds}
     * are the loaded dungeons' namespaced ids.
     */
    static List<Finding> roomMetaFindings(java.util.Map<String, DungeonRoomMeta> rooms,
                                          Set<String> dungeonIds,
                                          java.util.function.Predicate<String> blockExists) {
        List<Finding> findings = new ArrayList<>();
        for (java.util.Map.Entry<String, DungeonRoomMeta> entry : rooms.entrySet()) {
            String room = entry.getKey();
            DungeonRoomMeta meta = entry.getValue();
            if (DungeonRoomMeta.LIGHT_DARK.equals(meta.light) && meta.requiresLight) {
                findings.add(new Finding(room, "light",
                        "warning: light is dark but requiresLight is true; the room is never stamped dark"));
            }
            for (DungeonRoomMeta.NodeSpec node : meta.nodes) {
                if (!node.block().startsWith("minecraft:")) {
                    findings.add(new Finding(room, "nodes",
                            "node block is not a vanilla block: " + node.block()));
                } else if (!blockExists.test(node.block())) {
                    findings.add(new Finding(room, "nodes", "node block does not exist: " + node.block()));
                } else if (DungeonDef.isCopperOre(node.block())) {
                    findings.add(new Finding(room, "nodes",
                            "copper ore is out of the loot and the palettes (K2.7, K2.8)"));
                }
            }
            for (String role : meta.graphRole) {
                if (!DungeonRoomMeta.GRAPH_ROLES.contains(role)) {
                    findings.add(new Finding(room, "graphRole", "unknown graph role: " + role
                            + " (entry, any, side_reward, final or capstone)"));
                }
            }
            for (String id : meta.dungeons) {
                if (!dungeonIds.contains(DungeonDef.qualify(id))) {
                    findings.add(new Finding(room, "dungeons", "dungeon not found: " + id));
                }
            }
            for (String id : meta.borrowableBy) {
                if (!dungeonIds.contains(DungeonDef.qualify(id))) {
                    findings.add(new Finding(room, "borrowableBy", "dungeon not found: " + id));
                }
            }
        }
        return findings;
    }

    private static boolean vanillaBlockExists(String id) {
        net.minecraft.resources.Identifier parsed = net.minecraft.resources.Identifier.tryParse(id);
        return parsed != null && net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(parsed);
    }

    /**
     * Cube recipe eligibility: every weighted and guaranteed room reference
     * must resolve, and a guarantee group with no resolvable room can never
     * fire.
     */
    private static void addRecipeFindings(ContentSnapshot snapshot, List<Finding> findings) {
        RoomManifest rooms = snapshot.rooms();
        for (CubeRecipeDefinition def : snapshot.recipes().definitions()) {
            for (String name : def.effects.weightedRooms) {
                if (rooms.byName(name) == null) {
                    findings.add(new Finding(def.id, "weighted_rooms",
                            "room not found: " + name));
                }
            }
            for (RecipeEffects.GuaranteedRoom group : def.effects.guaranteedRooms) {
                boolean any = false;
                for (String name : group.names()) {
                    if (rooms.byName(name) != null) {
                        any = true;
                    } else {
                        findings.add(new Finding(def.id, "guaranteed_rooms",
                                "room not found: " + name));
                    }
                }
                if (!any && !group.names().isEmpty()) {
                    findings.add(new Finding(def.id, "guaranteed_rooms",
                            "group can never fire: no named room exists (min_tier=" + group.minTier() + ")"));
                }
            }
        }
    }

    /**
     * Zone rules the parser accepts but an author probably did not mean. A
     * malformed {@code rules} block (an unknown field, a floor kind not built
     * yet, a value out of range) already rejects its theme and surfaces as a
     * parse finding; these are the ones that load and then misbehave:
     * <ul>
     *   <li>an {@code unlock_level} above {@code keystoneMaxLevel}: no key
     *       ever reaches it, so no door ever deals the zone;</li>
     *   <li>{@code capstone: boss} on a theme whose adventure node is not a
     *       boss node: the Warden spawns and gates the pad, but beating it
     *       does not reset the descent the way a boss node does;</li>
     *   <li>{@code capstone: none} on a boss node: the descent resets with no
     *       boss to beat;</li>
     *   <li>every entry theme locked above level 1: a new player is dealt
     *       them anyway, since the door pick never deals nothing.</li>
     * </ul>
     */
    static List<Finding> zoneRuleFindings(List<ThemeManifest.Entry> themes, AdventureGraph graph,
                                          int keystoneMaxLevel) {
        List<Finding> findings = new ArrayList<>();
        for (ThemeManifest.Entry theme : themes) {
            ZoneRules rules = theme.meta().rules;
            if (rules == null) {
                continue;
            }
            if (rules.unlockLevel() > keystoneMaxLevel) {
                findings.add(new Finding(theme.id(), "rules.unlock_level",
                        "unlock level " + rules.unlockLevel() + " is above keystoneMaxLevel "
                                + keystoneMaxLevel + "; no door ever deals this zone"));
            }
            AdventureGraph.Node node = graph.node(theme.id());
            boolean bossNode = node != null && node.kind() == AdventureGraph.Kind.BOSS;
            if (rules.capstone() == ZoneRules.Capstone.BOSS && !bossNode) {
                findings.add(new Finding(theme.id(), "rules.capstone",
                        "capstone is boss but the adventure node is not a boss node; the boss gates the "
                                + "pad but beating it does not reset the descent"));
            }
            if (rules.capstone() == ZoneRules.Capstone.NONE && bossNode) {
                findings.add(new Finding(theme.id(), "rules.capstone",
                        "capstone is none on a boss node; the descent resets with no boss to beat"));
            }
        }
        List<String> entries = graph.entryThemes();
        if (!entries.isEmpty() && entries.stream().allMatch(id -> unlockLevelOf(themes, id) > 1)) {
            findings.add(new Finding("adventure", "rules.unlock_level",
                    "every entry theme is locked above level 1; a new player is dealt them anyway"));
        }
        return findings;
    }

    private static int unlockLevelOf(List<ThemeManifest.Entry> themes, String id) {
        for (ThemeManifest.Entry theme : themes) {
            if (theme.id().equals(id)) {
                return theme.meta().rules == null ? 1 : theme.meta().rules.unlockLevel();
            }
        }
        return 1;
    }

    /**
     * Missing loot and spawners. The manifests already reject namespaced
     * spawner configs, bag loot tables and affix loot pools at load (they
     * surface as parse findings); the one gap is a theme's namespaced
     * {@code loot_table}, which {@link ThemeManifest} does not validate, so a
     * typo there fails at run time instead of load time. Catch it here.
     */
    private static void addMissingLootFindings(MinecraftServer server, ContentSnapshot snapshot,
                                               List<Finding> findings) {
        for (ThemeManifest.Entry theme : snapshot.themes().themes()) {
            String lootTable = theme.meta().lootTable;
            if (lootTable == null || lootTable.isBlank()) {
                continue;
            }
            if (!lootTableExists(server, lootTable)) {
                findings.add(new Finding(theme.id(), "loot_table",
                        "loot table not found: " + lootTable));
            }
        }
    }

    /**
     * Kit baselines that load but would misbehave at a safe visit's top-up
     * ({@link KitTopUp}): an item or empty that does not resolve, a tool not
     * marked as a durability line (it would be refilled by count instead of
     * replaced when missing), a durability mark on an item with none, and a
     * line asking for more than one roll of the bag's kit table gives, which
     * would let the top-up hand out more than the kit ever did.
     *
     * @param rolled     one roll of a bag's kit table as counts per item id,
     *                   or {@code null} when the table is missing (reported by
     *                   the loot checks already)
     * @param damageable whether an item id has durability, or {@code null}
     *                   when it is not a registered item
     */
    static List<Finding> kitBaselineFindings(List<BagDefinition> bags,
                                             java.util.function.Function<BagDefinition, Map<String, Integer>> rolled,
                                             java.util.function.Function<String, Boolean> damageable) {
        List<Finding> findings = new ArrayList<>();
        for (BagDefinition bag : bags) {
            if (bag.kitBaseline.isEmpty()) {
                continue;
            }
            Map<String, Integer> roll = rolled.apply(bag);
            for (BagDefinition.KitItem line : bag.kitBaseline) {
                Boolean hasDurability = damageable.apply(line.item());
                if (hasDurability == null) {
                    findings.add(new Finding(bag.id, "kit_baseline",
                            line.item() + " is not a registered item"));
                    continue;
                }
                if (line.durability() && !hasDurability) {
                    findings.add(new Finding(bag.id, "kit_baseline",
                            line.item() + " is marked durability but has no durability"));
                } else if (!line.durability() && hasDurability) {
                    findings.add(new Finding(bag.id, "kit_baseline", line.item()
                            + " has durability; mark it \"durability\": true so it is replaced when"
                            + " missing and never refilled by count"));
                }
                if (line.emptiesInto() != null && damageable.apply(line.emptiesInto()) == null) {
                    findings.add(new Finding(bag.id, "kit_baseline", line.item()
                            + " empties into " + line.emptiesInto() + ", which is not a registered item"));
                }
                int given = roll == null ? line.count() : roll.getOrDefault(line.item(), 0);
                if (given < line.count()) {
                    findings.add(new Finding(bag.id, "kit_baseline", line.item() + " asks for "
                            + line.count() + " but one roll of " + bag.lootTable + " gives " + given
                            + "; the top-up would hand out more than the kit"));
                }
            }
        }
        return findings;
    }

    /** Whether a registered item has durability, or {@code null} if the id names no item. */
    private static Boolean damageableOrNull(String id) {
        Identifier parsed = Identifier.tryParse(id);
        net.minecraft.world.item.Item item = parsed == null ? null
                : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(parsed).orElse(null);
        return item == null ? null : new net.minecraft.world.item.ItemStack(item).isDamageableItem();
    }

    /** One roll of a bag's kit table, as counts per item id, or {@code null} if it cannot be rolled. */
    private static Map<String, Integer> rolledCounts(MinecraftServer server, BagDefinition bag) {
        List<net.minecraft.world.item.ItemStack> rolled = Bags.rollKit(server.overworld(), bag);
        if (rolled == null) {
            return null;
        }
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (net.minecraft.world.item.ItemStack stack : rolled) {
            if (!stack.isEmpty()) {
                counts.merge(KitTopUp.itemId(stack), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Door and return-path validation with a reproducible seed. Generates a
     * shape, validates it (the return path is the BFS from the entrance
     * reaching every cell), and resolves a room for every cell. Reports the
     * first failing seed; a clean sweep reports nothing.
     */
    private static void addPlanFindings(MinecraftServer server, ContentSnapshot snapshot,
                                        Long singleSeed, List<Finding> findings) {
        List<RoomRoleDefinition> roles = snapshot.roles().definitions();
        int min = PocketDungeonsConfig.pathLengthMin();
        int max = PocketDungeonsConfig.pathLengthMax();
        double branch = PocketDungeonsConfig.branchProbability();
        double loop = PocketDungeonsConfig.loopProbability();
        RoomManifest rooms = snapshot.rooms();

        if (singleSeed != null) {
            Finding f = checkOneSeed(singleSeed, min, max, branch, loop, roles, rooms);
            if (f != null) {
                findings.add(f);
            }
            return;
        }
        // The production planner retries many seeds; if any seed in our sample
        // produces a sound plan, the plan is sound. Only report when every seed
        // fails, and report the first failing seed for reproducibility.
        Finding firstFailure = null;
        for (int i = 0; i < SEED_TRIES; i++) {
            long seed = FIRST_SEED + i;
            Finding f = checkOneSeed(seed, min, max, branch, loop, roles, rooms);
            if (f == null) {
                return;
            }
            if (firstFailure == null) {
                firstFailure = f;
            }
        }
        if (firstFailure != null) {
            findings.add(firstFailure);
        }
    }

    private static Finding checkOneSeed(long seed, int min, int max, double branch, double loop,
                                        List<RoomRoleDefinition> roles, RoomManifest rooms) {
        DungeonShape shape = LayoutGraphGenerator.generate(seed, min, max, branch, loop, roles);
        if (shape == null) {
            return new Finding("plan", "shape",
                    "seed " + seed + " exhausted the shape backtracking budget", seed);
        }
        List<String> problems = LayoutGraphGenerator.validate(shape);
        if (!problems.isEmpty()) {
            return new Finding("plan", "shape",
                    "seed " + seed + ": " + String.join("; ", problems), seed);
        }
        RoomSelector.Result result = RoomSelector.resolveDetailed(shape, rooms);
        if (result.plan() == null) {
            RoomSelector.Failure failure = result.failure();
            String detail = failure == null ? "no plan" : failure.role();
            return new Finding("plan", "rooms",
                    "seed " + seed + " failed to resolve: " + detail, seed);
        }
        return null;
    }

    // ---- Export: starter pack ---------------------------------------------

    /**
     * Exports the bundled starter pack to {@code <world>/datapacks/<packName>}
     * with every file rewritten to use {@code namespace} instead of
     * {@code starter}. The author gets a pack under their own namespace with no
     * manual file editing. Refuses to overwrite an existing destination; pass
     * {@code confirm} to back the existing destination up and replace it.
     */
    static int exportStarter(CommandSourceStack source, String packName,
                            String namespace, boolean confirm) {
        String safePack = sanitizePackName(packName);
        String safeNs = sanitizeNamespace(namespace);
        Path dest = packDest(source, safePack);
        if (!checkReplace(source, dest, confirm)) {
            return 0;
        }
        try {
            URL root = PackValidator.class.getResource(STARTER_ROOT);
            if (root == null) {
                source.sendFailure(Component.literal(
                        "Starter pack not found in the jar at " + STARTER_ROOT));
                return 0;
            }
            copyStarterTree(root, dest, safeNs);
            writePackMeta(dest, STARTER_DESCRIPTION + " (" + safeNs + ")");
            source.sendSuccess(() -> Component.literal(
                    "Exported starter pack to " + relativize(source, dest)
                            + " under namespace " + safeNs
                            + ". Run /reload, then /dungeon admin validate."), false);
            return 1;
        } catch (Exception e) {
            PocketDungeonsMod.LOG.error("Failed to export starter pack to {}", dest, e);
            source.sendFailure(Component.literal("Starter export failed: " + e));
            return 0;
        }
    }

    // ---- Export: author workspace (buildroom/saveroom) --------------------

    /**
     * Release-safe export of the rooms an author captured with
     * {@code /dungeon admin buildroom} and {@code saveroom}. Copies the
     * production saveroom datapack to {@code <world>/datapacks/<name>} as a
     * distributable pack, with the same refuse/confirm/backup semantics as the
     * starter. The live saveroom datapack is left untouched.
     */
    static int exportAuthorWorkspace(CommandSourceStack source, String name, boolean confirm) {
        String safe = sanitizePackName(name);
        Path dest = packDest(source, safe);
        if (dest == null) {
            return 0;
        }
        Path sourcePack = source.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("datapacks").resolve(SAVEROOM_PACK);
        if (!Files.isDirectory(sourcePack)) {
            source.sendFailure(Component.literal(
                    "No author workspace found at " + SAVEROOM_PACK
                            + ". Capture a room first with /dungeon admin buildroom"
                            + " and /dungeon admin saveroom <name>."));
            return 0;
        }
        if (!checkReplace(source, dest, confirm)) {
            return 0;
        }
        try {
            copyDirectory(sourcePack, dest);
            writePackMeta(dest, "Pocket Dungeons author workspace");
            source.sendSuccess(() -> Component.literal(
                    "Exported author workspace to " + relativize(source, dest)
                            + ". Distribute this datapack as-is."), false);
            return 1;
        } catch (Exception e) {
            PocketDungeonsMod.LOG.error("Failed to export author workspace to {}", dest, e);
            source.sendFailure(Component.literal("Workspace export failed: " + e));
            return 0;
        }
    }

    // ---- Shared export helpers --------------------------------------------

    private static Path packDest(CommandSourceStack source, String name) {
        return source.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("datapacks").resolve(name);
    }

    /** The safe-replace verdict for a destination, independent of IO. */
    enum ReplaceDecision { PROCEED, REFUSE }

    /**
     * Pure decision: a fresh destination proceeds; an existing one refuses
     * unless the caller passed an explicit, destination-specific confirm.
     * Exposed for the headless test so the refuse/confirm rule is checked
     * without a live filesystem.
     */
    static ReplaceDecision decideReplace(boolean destinationExists, boolean confirm) {
        if (!destinationExists) {
            return ReplaceDecision.PROCEED;
        }
        return confirm ? ReplaceDecision.PROCEED : ReplaceDecision.REFUSE;
    }

    /**
     * Refuses to overwrite an existing destination unless {@code confirm} is
     * set, in which case the existing destination is moved to a timestamped
     * backup directory outside {@code datapacks/} (so Minecraft never loads
     * it as a live pack) first. Returns true if the caller may proceed.
     */
    private static boolean checkReplace(CommandSourceStack source, Path dest, boolean confirm) {
        boolean exists = Files.exists(dest);
        ReplaceDecision decision = decideReplace(exists, confirm);
        if (decision == ReplaceDecision.REFUSE) {
            String relative = relativize(source, dest);
            source.sendFailure(Component.literal(
                    "Destination " + relative + " already exists. Re-run with 'confirm'"
                            + " to back it up and replace it."));
            return false;
        }
        if (!exists) {
            return true;
        }
        try {
            Path backup = backupPath(source, dest);
            Files.move(dest, backup);
            source.sendSuccess(() -> Component.literal(
                    "Backed up existing " + relativize(source, dest)
                            + " to " + relativize(source, backup)), false);
            return true;
        } catch (IOException e) {
            PocketDungeonsMod.LOG.error("Could not back up {}", dest, e);
            source.sendFailure(Component.literal(
                    "Could not back up existing destination: " + e));
            return false;
        }
    }

    /**
     * A backup path outside {@code datapacks/}, so Minecraft never scans it as
     * a live datapack. Backups go to {@code <world>/pocketdungeons_backups/}.
     */
    private static Path backupPath(CommandSourceStack source, Path dest) {
        Path worldRoot = source.getServer().getWorldPath(LevelResource.ROOT);
        Path backupDir = worldRoot.resolve("pocketdungeons_backups");
        try {
            Files.createDirectories(backupDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create backup directory", e);
        }
        return backupDir.resolve(dest.getFileName() + ".backup-" + System.currentTimeMillis());
    }

    /**
     * The pack.mcmeta this build writes, using the game's current data pack
     * format ({@link SharedConstants#DATA_PACK_FORMAT_MAJOR}), so a release
     * jar for 26.2 writes a 26.2 pack.mcmeta.
     */
    static String packMetaJson(int packFormat, String description) {
        return String.format("""
                {
                  "pack": {
                    "pack_format": %d,
                    "description": "%s"
                  }
                }
                """, packFormat, description);
    }

    /** The data pack format this build writes into pack.mcmeta. */
    static int packFormat() {
        return SharedConstants.DATA_PACK_FORMAT_MAJOR;
    }

    private static void writePackMeta(Path target, String description) throws IOException {
        try (Writer w = Files.newBufferedWriter(target.resolve("pack.mcmeta"),
                StandardCharsets.UTF_8)) {
            w.write(packMetaJson(packFormat(), description));
        }
    }

    /** {@code [a-z0-9_]} only, so a command argument can never escape the datapacks directory. */
    private static String sanitizePackName(String raw) {
        String cleaned = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return cleaned.isEmpty() ? "pack" : cleaned;
    }

    /** Same rule as {@link #sanitizePackName}, for the content namespace. */
    private static String sanitizeNamespace(String raw) {
        String cleaned = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return cleaned.isEmpty() ? "mypack" : cleaned;
    }

    private static String relativize(CommandSourceStack source, Path path) {
        Path root = source.getServer().getWorldPath(LevelResource.ROOT);
        return root.relativize(path).toString().replace('\\', '/');
    }

    /**
     * Copies a bundled resource tree (the starter pack) to {@code target},
     * handling both the {@code file:} protocol (development, resources on
     * disk) and the {@code jar:} protocol (a packaged release jar), the same
     * two paths {@link DatapackExporter} handles.
     */
    private static void copyResourceTree(URL resource, Path target)
            throws IOException, URISyntaxException {
        if ("file".equals(resource.getProtocol())) {
            copyDirectory(Paths.get(resource.toURI()), target);
        } else if ("jar".equals(resource.getProtocol())) {
            String uriString = resource.toURI().toString();
            String fsUriString = uriString.substring(0, uriString.indexOf("!/") + 2);
            URI fsUri = URI.create(fsUriString);
            FileSystem fs;
            boolean created;
            try {
                fs = FileSystems.getFileSystem(fsUri);
                created = false;
            } catch (FileSystemNotFoundException e) {
                fs = FileSystems.newFileSystem(fsUri, Map.of());
                created = true;
            }
            try {
                copyDirectory(fs.getPath(STARTER_ROOT), target);
            } finally {
                if (created) {
                    fs.close();
                }
            }
        } else {
            throw new IllegalStateException(
                    "Unsupported resource protocol: " + resource.getProtocol());
        }
    }

    /**
     * Copies the bundled starter tree to {@code target}, rewriting the
     * {@code starter} namespace to {@code namespace} in both file paths
     * ({@code data/starter/} becomes {@code data/<namespace>/}) and file
     * contents ({@code "starter:"} becomes {@code "<namespace>:"}). The README
     * is copied verbatim; only {@code .json} content files get the content
     * rewrite. This lets an author export a pack under their own namespace
     * with no manual file editing.
     */
    private static void copyStarterTree(URL resource, Path target, String namespace)
            throws IOException, URISyntaxException {
        Path srcDir;
        if ("file".equals(resource.getProtocol())) {
            srcDir = Paths.get(resource.toURI());
        } else if ("jar".equals(resource.getProtocol())) {
            String uriString = resource.toURI().toString();
            String fsUriString = uriString.substring(0, uriString.indexOf("!/") + 2);
            URI fsUri = URI.create(fsUriString);
            FileSystem fs;
            boolean created;
            try {
                fs = FileSystems.getFileSystem(fsUri);
                created = false;
            } catch (FileSystemNotFoundException e) {
                fs = FileSystems.newFileSystem(fsUri, Map.of());
                created = true;
            }
            try {
                srcDir = fs.getPath(STARTER_ROOT);
                copyStarterDirectory(srcDir, target, namespace);
            } finally {
                if (created) {
                    fs.close();
                }
            }
            return;
        } else {
            throw new IllegalStateException(
                    "Unsupported resource protocol: " + resource.getProtocol());
        }
        copyStarterDirectory(srcDir, target, namespace);
    }

    private static void copyStarterDirectory(Path src, Path dst, String namespace)
            throws IOException {
        try (Stream<Path> paths = Files.walk(src)) {
            paths.forEach(path -> {
                try {
                    Path relative = src.relativize(path);
                    String relativeStr = relative.toString().replace('\\', '/');
                    // Rewrite data/starter/ to data/<namespace>/ in paths.
                    String outRelative = relativeStr.replaceFirst(
                            "^data/starter/", "data/" + namespace + "/");
                    Path out = dst.resolve(outRelative);
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(out);
                    } else {
                        Files.createDirectories(out.getParent());
                        if (relativeStr.endsWith(".json")) {
                            // Rewrite "starter:" to "<namespace>:" in content.
                            String content = Files.readString(path, StandardCharsets.UTF_8);
                            String rewritten = content.replace(
                                    "\"starter:", "\"" + namespace + ":");
                            Files.writeString(out, rewritten, StandardCharsets.UTF_8);
                        } else {
                            Files.copy(path, out, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to copy " + path, e);
                }
            });
        }
    }

    private static void copyDirectory(Path src, Path dst) throws IOException {
        try (Stream<Path> paths = Files.walk(src)) {
            paths.forEach(path -> {
                try {
                    Path relative = src.relativize(path);
                    Path out = dst.resolve(relative.toString());
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(out);
                    } else {
                        Files.createDirectories(out.getParent());
                        Files.copy(path, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to copy " + path, e);
                }
            });
        }
    }

    private static boolean lootTableExists(MinecraftServer server, String lootId) {
        Identifier id = Identifier.parse(lootId);
        ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, id);
        return server.reloadableRegistries().lookup()
                .lookup(Registries.LOOT_TABLE)
                .map(lookup -> lookup.get(key).isPresent())
                .orElse(false);
    }
}
