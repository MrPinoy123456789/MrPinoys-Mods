package pocketdungeons;

import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M70: the reloadable room role definition manifest. Loads
 * {@code data/<namespace>/dungeon_role/*.json} into
 * {@link RoomRoleDefinition}s keyed by namespaced id, validates the set as
 * a whole (built-in coverage, structural-role rejection), and publishes
 * atomically through {@link ContentSnapshot}.
 *
 * <p>Before M70 a population role was a hard-coded string the generator's
 * own {@code switch} understood; adding one required a source edit and a
 * recompile. Now a third party ships
 * {@code data/theirpack/dungeon_role/their_role.json} and it flows through
 * every site the built-ins do: assignment, selection, and dispatch. This
 * is the no-compile role gate.
 *
 * <p>Structural roles ({@code entrance}, {@code exit}) are engine-owned and
 * are not loaded from JSON. A file that declares {@code stage: "structural"}
 * is rejected at load, the same way a pack cannot add a new door direction.
 * The supported operation set is closed
 * ({@link RoomRoleDefinition.Operation}); a definition that declares an
 * operation outside this set is rejected.
 */
final class RoleManifest {

    record Entry(String id, RoomRoleDefinition def) {}

    private static volatile RoleManifest current = new RoleManifest(Map.of(), List.of());

    private final Map<String, Entry> byId;
    private final List<RoomRoleDefinition> definitions;
    private final List<String> rejections;

    private RoleManifest(Map<String, Entry> byId, List<String> rejections) {
        this.byId = Map.copyOf(byId);
        this.definitions = List.copyOf(byId.values().stream().map(Entry::def).toList());
        this.rejections = List.copyOf(rejections);
    }

    /**
     * Parses every {@code dungeon_role} resource into a manifest without
     * publishing it, the build half of {@link ContentReload}'s atomic reload.
     * Keys by namespaced id so two packs with the same local role name
     * coexist, validates each definition's stage and operation.
     */
    static RoleManifest parse(MinecraftServer server) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        List<String> rejections = new ArrayList<>();
        Map<net.minecraft.resources.Identifier, Resource> resources = server.getResourceManager().listResources(
                "dungeon_role", id -> id.getPath().endsWith(".json"));
        List<Map.Entry<net.minecraft.resources.Identifier, Resource>> sorted = new ArrayList<>(resources.entrySet());
        sorted.sort(Map.Entry.comparingByKey());
        for (Map.Entry<net.minecraft.resources.Identifier, Resource> resource : sorted) {
            String id = JsonPackSupport.resourceId(resource.getKey(), "dungeon_role");
            try (BufferedReader reader = resource.getValue().openAsReader()) {
                RoomRoleDefinition def = RoleMeta.fromJson(
                        JsonParser.parseReader(reader).getAsJsonObject(), id);
                entries.put(id, new Entry(id, def));
            } catch (Exception exception) {
                String reason = id + " - " + exception.getMessage();
                rejections.add(reason);
                PocketDungeonsMod.LOG.error("Rejected dungeon role '{}': {}", id, reason, exception);
            }
        }
        return new RoleManifest(entries, rejections);
    }

    /** Commits a resolved manifest as the live {@link #current}. */
    static void publish(RoleManifest manifest) {
        current = manifest;
        PocketDungeonsMod.LOG.info("Loaded {} dungeon roles ({} rejected)",
                manifest.definitions.size(), manifest.rejections.size());
    }

    static RoleManifest current() {
        return current;
    }

    /**
     * Looks up a definition by id, resolving a legacy bare id to the
     * {@code pocketdungeons} namespace the same way the other manifests do.
     * Returns {@code null} for structural roles (entrance, exit), which are
     * engine-owned and not in the manifest.
     */
    RoomRoleDefinition byId(String id) {
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

    /** The population role definitions, in manifest load order. */
    List<RoomRoleDefinition> definitions() {
        return definitions;
    }

    List<String> rejections() {
        return rejections;
    }

    /**
     * Whether every built-in population role id is present. The required
     * coverage gate for roles: a pack that drops the {@code pocketdungeons}
     * pack cannot silently remove encounter, loot, or corridor, the way the
     * room manifest cannot drop entrance and exit. A candidate that fails
     * this gate is not published.
     */
    boolean hasBuiltInCoverage() {
        for (String id : RoleIds.BUILT_IN_POPULATION_ORDER) {
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

    /** Package-private test factory; production code uses {@link #parse}. */
    static RoleManifest create(Map<String, Entry> entries, List<String> rejections) {
        return new RoleManifest(entries, rejections);
    }
}
