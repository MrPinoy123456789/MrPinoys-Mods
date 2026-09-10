package pocketdungeons;

import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M71: the reloadable Cube recipe definition manifest. Loads
 * {@code data/<namespace>/cube_recipe/*.json} into
 * {@link CubeRecipeDefinition}s keyed by namespaced id, validates the set
 * as a whole (built-in coverage, catalyst item references, duplicate
 * catalyst declarations), and publishes atomically through
 * {@link ContentSnapshot}.
 *
 * <p>Before M71 a Cube recipe was a Java enum constant ({@link CubeRecipe});
 * adding one required a source edit and a recompile. Now a third party
 * ships {@code data/theirpack/cube_recipe/their_recipe.json} and it flows
 * through every site the built-ins do: the Cube station's match path, the
 * keystone tag write, and the resolver's effect accumulation. This is the
 * no-compile recipe gate.
 *
 * <p>The supported effect set is closed ({@link RecipeEffects}); a
 * definition that declares an effect outside it is rejected at load. A
 * definition that bends nothing is rejected outright. Genuinely new
 * operations still require reviewed engine work.
 *
 * <h2>Ambiguous matches</h2>
 *
 * <p>Two definitions whose catalyst predicates can match the same off-hand
 * stack is an ambiguity the match path refuses at use time rather than
 * silently picking one. The manifest catches the static case at load:
 * two definitions naming the same catalyst item, or the same catalyst
 * tag. The dynamic case (an item that is in a tag another recipe names)
 * is caught by the match path, which collects every matching definition
 * and refuses if more than one matches.
 */
final class CubeRecipeManifest {

    record Entry(String id, CubeRecipeDefinition def) {}

    private static volatile CubeRecipeManifest current = new CubeRecipeManifest(Map.of(), List.of());

    private final Map<String, Entry> byId;
    private final List<CubeRecipeDefinition> definitions;
    private final List<String> rejections;

    private CubeRecipeManifest(Map<String, Entry> byId, List<String> rejections) {
        this.byId = Map.copyOf(byId);
        List<CubeRecipeDefinition> ordered = new ArrayList<>(byId.values().stream()
                .map(Entry::def)
                .toList());
        ordered.sort(CubeRecipeManifest::byPriorityThenId);
        this.definitions = List.copyOf(ordered);
        this.rejections = List.copyOf(rejections);
    }

    private static int byPriorityThenId(CubeRecipeDefinition a, CubeRecipeDefinition b) {
        int c = Integer.compare(a.priority, b.priority);
        return c != 0 ? c : a.id.compareTo(b.id);
    }

    /**
     * Parses every {@code cube_recipe} resource into a manifest without
     * publishing it, the build half of {@link ContentReload}'s atomic
     * reload. Keys by namespaced id so two packs with the same local
     * recipe name coexist, validates each definition's catalyst reference,
     * then runs the cross-definition duplicate-catalyst check.
     */
    static CubeRecipeManifest parse(MinecraftServer server) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        List<String> rejections = new ArrayList<>();
        Map<Identifier, Resource> resources = server.getResourceManager().listResources(
                "cube_recipe", id -> id.getPath().endsWith(".json"));
        List<Map.Entry<Identifier, Resource>> sorted = new ArrayList<>(resources.entrySet());
        sorted.sort(Map.Entry.comparingByKey());
        for (Map.Entry<Identifier, Resource> resource : sorted) {
            String id = JsonPackSupport.resourceId(resource.getKey(), "cube_recipe");
            try (BufferedReader reader = resource.getValue().openAsReader()) {
                CubeRecipeDefinition def = CubeRecipeMeta.fromJson(
                        JsonParser.parseReader(reader).getAsJsonObject(), id);
                validateCatalyst(server, def, id);
                entries.put(id, new Entry(id, def));
            } catch (Exception exception) {
                String reason = id + " - " + exception.getMessage();
                rejections.add(reason);
                PocketDungeonsMod.LOG.error("Rejected cube recipe '{}': {}", id, reason, exception);
            }
        }
        validateNoDuplicateCatalyst(entries, rejections);
        return new CubeRecipeManifest(entries, rejections);
    }

    private static void validateCatalyst(MinecraftServer server, CubeRecipeDefinition def, String id) {
        if (def.catalystItem != null) {
            Identifier itemId = Identifier.parse(def.catalystItem);
            if (BuiltInRegistries.ITEM.getOptional(itemId).isEmpty()) {
                throw new IllegalStateException("catalyst item not found: " + def.catalystItem);
            }
        }
        if (def.catalystTag != null) {
            // Validate the tag id parses as a namespaced id. The tag's
            // membership is resolved at match time against the live tag
            // registry, the same way the wool check in the old enum did.
            // Strip the leading '#' if present, since the JSON form uses
            // the tag prefix but Identifier does not.
            String tagId = def.catalystTag;
            if (tagId.startsWith("#")) {
                tagId = tagId.substring(1);
            }
            Identifier.parse(tagId);
        }
    }

    /**
     * Rejects two definitions that name the same catalyst item or the same
     * catalyst tag. The dynamic overlap (an item in a tag another recipe
     * names) is caught by the match path at use time; this catches the
     * static case at load so a duplicate declaration is reported before a
     * player ever holds the catalyst.
     */
    private static void validateNoDuplicateCatalyst(Map<String, Entry> entries,
                                                    List<String> rejections) {
        Map<String, String> seenItem = new LinkedHashMap<>();
        Map<String, String> seenTag = new LinkedHashMap<>();
        for (Entry e : entries.values()) {
            CubeRecipeDefinition def = e.def;
            if (def.catalystItem != null) {
                String prior = seenItem.put(def.catalystItem, e.id);
                if (prior != null) {
                    rejections.add(e.id + ": duplicate catalyst item " + def.catalystItem
                            + " (also declared by " + prior + ")");
                }
            }
            if (def.catalystTag != null) {
                String prior = seenTag.put(def.catalystTag, e.id);
                if (prior != null) {
                    rejections.add(e.id + ": duplicate catalyst tag " + def.catalystTag
                            + " (also declared by " + prior + ")");
                }
            }
        }
    }

    /** Package-private test factory; production code uses {@link #parse}. */
    static CubeRecipeManifest create(Map<String, Entry> entries, List<String> rejections) {
        return new CubeRecipeManifest(entries, rejections);
    }

    /** Commits a resolved manifest as the live {@link #current}. */
    static void publish(CubeRecipeManifest manifest) {
        current = manifest;
        PocketDungeonsMod.LOG.info("Loaded {} cube recipes ({} rejected)",
                manifest.definitions.size(), manifest.rejections.size());
    }

    static CubeRecipeManifest current() {
        return current;
    }

    /**
     * Looks up a definition by id, resolving a legacy bare id to the
     * {@code pocketdungeons} namespace the same way the other manifests do.
     */
    CubeRecipeDefinition byId(String id) {
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

    /** The definitions in stable match order (priority then id). */
    List<CubeRecipeDefinition> definitions() {
        return definitions;
    }

    List<String> rejections() {
        return rejections;
    }

    /**
     * Whether every built-in recipe id is present. The required coverage
     * gate for recipes: a pack that drops the {@code pocketdungeons} pack
     * cannot silently remove Ominous or Store, the way the room manifest
     * cannot drop entrance and exit. A candidate that fails this gate is
     * not published.
     */
    boolean hasBuiltInCoverage() {
        for (String id : RecipeIds.BUILT_IN_ORDER) {
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
