package pocketdungeons;

import com.google.gson.JsonParser;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reloadable dungeon theme manifest across every datapack namespace. */
final class ThemeManifest {

    record Entry(String id, DungeonThemeMeta meta) {}

    private static volatile ThemeManifest current = new ThemeManifest(Map.of(), List.of());

    private final Map<String, Entry> byId;
    private final List<String> rejections;

    private ThemeManifest(Map<String, Entry> byId, List<String> rejections) {
        this.byId = Map.copyOf(byId);
        this.rejections = List.copyOf(rejections);
    }

    /**
     * M68: the reload listener this used to register, plus the chained
     * {@link AdventureGraphs#load} and {@link Diaries#load} calls, are now
     * owned by {@link ContentReload}, which builds all five surfaces into one
     * {@link ContentSnapshot} and publishes them atomically. Kept as a no op so
     * any caller that still reaches for it does not double register a listener.
     */
    static void register() {
        // ContentReload.register() owns the reload listener now.
    }

    static ThemeManifest load(MinecraftServer server) {
        ThemeManifest loaded = parse(server);
        current = loaded;
        PocketDungeonsMod.LOG.info("Loaded {} dungeon themes ({} rejected)",
                loaded.byId.size(), loaded.rejections.size());
        return loaded;
    }

    /**
     * M68: parses every {@code dungeon_theme} resource into a manifest without
     * publishing it, the build half of {@link ContentReload}'s atomic reload.
     * Keys by namespaced id ({@code namespace:path}) so two packs with the same
     * local theme name coexist, validates the processor list as before, and
     * additionally validates the M68 {@code normal_spawner} / {@code ominous_
     * spawner} references against the trial spawner config registry up front:
     * trap 6 means a misspelt spawner config id does not throw at the block
     * entity, so the manifest is the right place to catch it.
     */
    static ThemeManifest parse(MinecraftServer server) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        List<String> rejections = new ArrayList<>();
        Map<Identifier, Resource> resources = server.getResourceManager().listResources(
                "dungeon_theme", id -> id.getPath().endsWith(".json"));
        List<Map.Entry<Identifier, Resource>> sorted = new ArrayList<>(resources.entrySet());
        sorted.sort(Map.Entry.comparingByKey());
        for (Map.Entry<Identifier, Resource> resource : sorted) {
            String id = JsonPackSupport.resourceId(resource.getKey(), "dungeon_theme");
            try (BufferedReader reader = resource.getValue().openAsReader()) {
                DungeonThemeMeta meta = DungeonThemeMeta.fromJson(
                        JsonParser.parseReader(reader).getAsJsonObject(), id);
                Identifier processors = Identifier.parse(meta.processors);
                if (server.registryAccess().lookupOrThrow(Registries.PROCESSOR_LIST)
                        .getValue(processors) == null) {
                    throw new IllegalStateException("processor list not found: " + meta.processors);
                }
                validateSpawnerConfig(server, meta.normalSpawner, "normal_spawner");
                validateSpawnerConfig(server, meta.ominousSpawner, "ominous_spawner");
                entries.put(id, new Entry(id, meta));
            } catch (Exception exception) {
                String reason = resource.getKey() + " - " + exception.getMessage();
                rejections.add(reason);
                PocketDungeonsMod.LOG.error("Rejected dungeon theme '{}': {}", id, reason, exception);
            }
        }
        return new ThemeManifest(entries, rejections);
    }

    /**
     * M68: rejects a theme whose namespaced spawner config reference does not
     * resolve in the trial spawner config registry. The legacy
     * {@code spawner_prefix} field is composed with the tier at stamp time and
     * validated lazily (a missing config falls back to default), so it stays
     * unchecked here; the namespaced {@code normal_spawner} / {@code ominous_
     * spawner} fields are explicit ids, so a typo is caught at load with the
     * theme named.
     */
    private static void validateSpawnerConfig(MinecraftServer server, String configId, String field) {
        if (configId == null || configId.isBlank()) {
            return;
        }
        Identifier id = Identifier.parse(configId);
        if (server.registryAccess().lookupOrThrow(Registries.TRIAL_SPAWNER_CONFIG).getValue(id) == null) {
            throw new IllegalStateException(field + " not found: " + configId);
        }
    }

    /**
     * M68: commits a resolved theme manifest as the live {@link #current}, the
     * publish half of {@link ContentReload}'s atomic build then commit.
     */
    static void publish(ThemeManifest themes) {
        current = themes;
        PocketDungeonsMod.LOG.info("Loaded {} dungeon themes ({} rejected)",
                themes.byId.size(), themes.rejections.size());
    }

    static ThemeManifest current() {
        return current;
    }

    /**
     * M68: looks up a theme by namespaced id, resolving a legacy bare id to the
     * {@code pocketdungeons} namespace the same way {@link RoomManifest#byName}
     * does, so existing call sites that pass a bare theme id keep working and a
     * bare id that is not a {@code pocketdungeons} built in returns null rather
     * than last file wins.
     */
    Entry byId(String id) {
        if (id == null) {
            return null;
        }
        Entry direct = byId.get(id);
        if (direct != null) {
            return direct;
        }
        if (id.indexOf(':') < 0) {
            return byId.get(PocketDungeonsMod.MOD_ID + ":" + id);
        }
        return null;
    }

    List<Entry> themes() {
        return List.copyOf(byId.values());
    }

    List<String> rejections() {
        return rejections;
    }

}
