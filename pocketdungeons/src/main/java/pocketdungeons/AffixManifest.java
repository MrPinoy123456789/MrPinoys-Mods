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
 * M69: the reloadable affix definition manifest. Loads
 * {@code data/<namespace>/dungeon_affix/*.json} into
 * {@link AffixDefinition}s keyed by namespaced id, validates the set as a
 * whole (incompatibility symmetry, built-in coverage, loot table references),
 * and publishes atomically through {@link ContentSnapshot}.
 *
 * <p>Before M69 an affix was a Java enum constant; adding one required a
 * source edit and a recompile. Now a third party ships
 * {@code data/theirpack/dungeon_affix/their_affix.json} and it flows through
 * every site the built-ins do: seeding, naming, depletion, the keystone
 * label, the screen line, and the operation dispatch. This is the first
 * no-compile affix gate.
 *
 * <p>The supported operation set is closed
 * ({@link AffixEffects}); a definition that declares an operation outside
 * that set is silently ignored by the parser, and a definition that bends
 * nothing is rejected outright. Genuinely new operations still require
 * reviewed engine work.
 */
final class AffixManifest {

    record Entry(String id, AffixDefinition def) {}

    private static volatile AffixManifest current = new AffixManifest(Map.of(), List.of());

    private final Map<String, Entry> byId;
    private final List<AffixDefinition> definitions;
    private final List<String> rejections;

    private AffixManifest(Map<String, Entry> byId, List<String> rejections) {
        this.byId = Map.copyOf(byId);
        List<AffixDefinition> ordered = new ArrayList<>(byId.values().stream()
                .map(Entry::def)
                .toList());
        ordered.sort(AffixManifest::byOrderThenId);
        this.definitions = List.copyOf(ordered);
        this.rejections = List.copyOf(rejections);
    }

    private static int byOrderThenId(AffixDefinition a, AffixDefinition b) {
        int c = Integer.compare(a.order, b.order);
        return c != 0 ? c : a.id.compareTo(b.id);
    }

    /**
     * Parses every {@code dungeon_affix} resource into a manifest without
     * publishing it, the build half of {@link ContentReload}'s atomic reload.
     * Keys by namespaced id so two packs with the same local affix name
     * coexist, validates each definition's bounds, then runs the cross
     * definition checks (incompatibility symmetry, loot table references).
     */
    static AffixManifest parse(MinecraftServer server) {
        return parse(server, server.getResourceManager());
    }

    /** F1: accepts the incoming ResourceManager from the reload callback. */
    static AffixManifest parse(MinecraftServer server, ResourceManager rm) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        List<String> rejections = new ArrayList<>();
        Map<Identifier, Resource> resources = rm.listResources(
                "dungeon_affix", id -> id.getPath().endsWith(".json"));
        List<Map.Entry<Identifier, Resource>> sorted = new ArrayList<>(resources.entrySet());
        sorted.sort(Map.Entry.comparingByKey());
        for (Map.Entry<Identifier, Resource> resource : sorted) {
            String id = JsonPackSupport.resourceId(resource.getKey(), "dungeon_affix");
            try (BufferedReader reader = resource.getValue().openAsReader()) {
                AffixDefinition def = AffixMeta.fromJson(
                        JsonParser.parseReader(reader).getAsJsonObject(), id);
                validateLootRef(server, def.effects.bonusToolPool, "bonus_tool_pool", id);
                validateLootRef(server, def.effects.decorPool, "decor_pool", id);
                entries.put(id, new Entry(id, def));
            } catch (Exception exception) {
                String reason = id + " - " + exception.getMessage();
                rejections.add(reason);
                PocketDungeonsMod.LOG.error("Rejected dungeon affix '{}': {}", id, reason, exception);
            }
        }
        validateIncompatibilitySymmetry(entries, rejections);
        return new AffixManifest(entries, rejections);
    }

    /**
     * Rejects a one-sided incompatibility: if A lists B but B does not list A,
     * the pair is inconsistent and the manifest reports it. A symmetric pair
     * is allowed; an asymmetric one is a data authoring bug.
     */
    private static void validateIncompatibilitySymmetry(Map<String, Entry> entries,
                                                        List<String> rejections) {
        for (Entry a : entries.values()) {
            for (String b : a.def.incompatible) {
                Entry other = entries.get(b);
                if (other == null) {
                    rejections.add(a.id + ": incompatible with unknown affix " + b);
                    continue;
                }
                if (!other.def.incompatible.contains(a.id)) {
                    rejections.add(a.id + ": incompatible with " + b
                            + " but " + b + " does not list " + a.id);
                }
            }
        }
    }

    private static void validateLootRef(MinecraftServer server, String lootId, String field, String affixId) {
        if (lootId == null || lootId.isBlank()) {
            return;
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

    /** Commits a resolved manifest as the live {@link #current}. */
    static void publish(AffixManifest manifest) {
        current = manifest;
        PocketDungeonsMod.LOG.info("Loaded {} dungeon affixes ({} rejected)",
                manifest.definitions.size(), manifest.rejections.size());
    }

    static AffixManifest current() {
        return current;
    }

    /**
     * Looks up a definition by id, resolving a legacy bare id to the
     * {@code pocketdungeons} namespace the same way the other manifests do.
     */
    AffixDefinition byId(String id) {
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

    /** The definitions in stable render/seed order. */
    List<AffixDefinition> definitions() {
        return definitions;
    }

    List<String> rejections() {
        return rejections;
    }

    /**
     * Whether every built-in affix id is present. The required coverage gate
     * for affixes: a pack that drops the {@code pocketdungeons} pack cannot
     * silently remove Ominous, the way the room manifest cannot drop entrance
     * and exit. A candidate that fails this gate is not published.
     */
    boolean hasBuiltInCoverage() {
        for (String id : AffixIds.BUILT_IN_ORDER) {
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
