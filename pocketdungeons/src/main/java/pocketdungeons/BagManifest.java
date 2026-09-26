package pocketdungeons;

import com.google.gson.JsonParser;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.storage.loot.LootTable;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M70: the reloadable bag definition manifest. Loads
 * {@code data/<namespace>/dungeon_bag/*.json} into
 * {@link BagDefinition}s keyed by namespaced id, validates the set as a
 * whole (built-in coverage, loot table references), and publishes
 * atomically through {@link ContentSnapshot}.
 *
 * <p>Before M70 a bag was a Java enum constant; adding one required a
 * source edit and a recompile. Now a third party ships
 * {@code data/theirpack/dungeon_bag/their_bag.json} and it flows through
 * every site the built-ins do: the picker, the solvability seed, the cube
 * catalyst lookup, and the loot roll. This is the no-compile bag gate.
 *
 * <p>The capability tag vocabulary is closed ({@link SituationTags}); a tag
 * outside it is rejected at load. The loot table reference is validated
 * against the server's reloadable registries, the same way an affix's
 * {@code bonus_tool_pool} is.
 */
final class BagManifest {

    record Entry(String id, BagDefinition def) {}

    private static volatile BagManifest current = new BagManifest(Map.of(), List.of());

    private final Map<String, Entry> byId;
    private final List<BagDefinition> definitions;
    private final List<String> rejections;

    private BagManifest(Map<String, Entry> byId, List<String> rejections) {
        this.byId = Map.copyOf(byId);
        List<BagDefinition> ordered = new ArrayList<>(byId.values().stream()
                .map(Entry::def)
                .toList());
        ordered.sort(BagManifest::byOrderThenId);
        this.definitions = List.copyOf(ordered);
        this.rejections = List.copyOf(rejections);
    }

    private static int byOrderThenId(BagDefinition a, BagDefinition b) {
        int c = Integer.compare(a.order, b.order);
        return c != 0 ? c : a.id.compareTo(b.id);
    }

    /**
     * Parses every {@code dungeon_bag} resource into a manifest without
     * publishing it, the build half of {@link ContentReload}'s atomic reload.
     * Keys by namespaced id so two packs with the same local bag name
     * coexist, validates each definition's tag vocabulary and loot table
     * reference.
     */
    static BagManifest parse(MinecraftServer server) {
        return parse(server, server.getResourceManager());
    }

    /** F1: accepts the incoming ResourceManager from the reload callback. */
    static BagManifest parse(MinecraftServer server, ResourceManager rm) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        List<String> rejections = new ArrayList<>();
        Map<Identifier, Resource> resources = rm.listResources(
                "dungeon_bag", id -> id.getPath().endsWith(".json"));
        List<Map.Entry<Identifier, Resource>> sorted = new ArrayList<>(resources.entrySet());
        sorted.sort(Map.Entry.comparingByKey());
        for (Map.Entry<Identifier, Resource> resource : sorted) {
            String id = JsonPackSupport.resourceId(resource.getKey(), "dungeon_bag");
            try (BufferedReader reader = resource.getValue().openAsReader()) {
                BagDefinition def = BagMeta.fromJson(
                        JsonParser.parseReader(reader).getAsJsonObject(), id);
                validateLootRef(server, def.lootTable, "loot_table", id);
                entries.put(id, new Entry(id, def));
            } catch (Exception exception) {
                String reason = id + " - " + exception.getMessage();
                rejections.add(reason);
                PocketDungeonsMod.LOG.error("Rejected dungeon bag '{}': {}", id, reason, exception);
            }
        }
        return new BagManifest(entries, rejections);
    }

    private static void validateLootRef(MinecraftServer server, String lootId, String field, String bagId) {
        if (lootId == null || lootId.isBlank()) {
            throw new IllegalStateException(bagId + ": loot_table must not be blank");
        }
        Identifier id = Identifier.parse(lootId);
        ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, id);
        // Loot tables are a reloadable, datapack-driven registry held on the
        // server's reloadableRegistries, not on the frozen dynamic registry a
        // registryAccess() lookup exposes (see LootTables.exists). The wrong
        // lookup throws "Missing registry" unconditionally.
        boolean present = server.reloadableRegistries().lookup()
                .lookup(Registries.LOOT_TABLE)
                .map(lookup -> lookup.get(key).isPresent())
                .orElse(false);
        if (!present) {
            throw new IllegalStateException(field + " not found: " + lootId);
        }
    }

    /** Package-private test factory; production code uses {@link #parse}. */
    static BagManifest create(Map<String, Entry> entries, List<String> rejections) {
        return new BagManifest(entries, rejections);
    }

    /** Commits a resolved manifest as the live {@link #current}. */
    static void publish(BagManifest manifest) {
        current = manifest;
        PocketDungeonsMod.LOG.info("Loaded {} dungeon bags ({} rejected)",
                manifest.definitions.size(), manifest.rejections.size());
    }

    static BagManifest current() {
        return current;
    }

    /**
     * Looks up a definition by id, resolving a legacy bare id to the
     * {@code pocketdungeons} namespace the same way the other manifests do.
     */
    BagDefinition byId(String id) {
        if (id == null) {
            return null;
        }
        Entry direct = byId.get(id);
        if (direct != null) {
            return direct.def;
        }
        if (id.indexOf(':') < 0) {
            Entry legacy = byId.get(PocketDungeonsMod.MOD_ID + ":" + id);
            return legacy == null ? null : legacy.def;
        }
        return null;
    }

    /** The definitions in stable picker order. */
    List<BagDefinition> definitions() {
        return definitions;
    }

    List<String> rejections() {
        return rejections;
    }

    /**
     * Whether every built-in bag id is present. The required coverage gate
     * for bags: a pack that drops the {@code pocketdungeons} pack cannot
     * silently remove Mason, the way the room manifest cannot drop entrance
     * and exit. A candidate that fails this gate is not published.
     */
    boolean hasBuiltInCoverage() {
        for (String id : BagIds.BUILT_IN_ORDER) {
            if (!byId.containsKey(id)) {
                return false;
            }
        }
        return true;
    }

    /** The set of ids in this manifest, for the snapshot's coverage gate. */
    Set<String> ids() {
        return byId.keySet();
    }
}
