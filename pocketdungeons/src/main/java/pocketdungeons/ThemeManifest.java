package pocketdungeons;

import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reloadable dungeon theme manifest across every datapack namespace. */
final class ThemeManifest {

    record Entry(String id, DungeonThemeMeta meta) {}

    private static volatile ThemeManifest current = new ThemeManifest(Map.of(), List.of());
    private static volatile MinecraftServer server;

    private final Map<String, Entry> byId;
    private final List<String> rejections;

    private ThemeManifest(Map<String, Entry> byId, List<String> rejections) {
        this.byId = Map.copyOf(byId);
        this.rejections = List.copyOf(rejections);
    }

    static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(value -> server = value);
        ServerLifecycleEvents.SERVER_STOPPING.register(value -> server = null);
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public Identifier getFabricId() {
                        return Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "theme_manifest");
                    }

                    @Override
                    public void onResourceManagerReload(ResourceManager manager) {
                        MinecraftServer value = server;
                        if (value != null) {
                            load(value);
                            AdventureGraphs.load(value);
                        }
                    }
                });
    }

    static ThemeManifest load(MinecraftServer server) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        List<String> rejections = new ArrayList<>();
        Map<Identifier, Resource> resources = server.getResourceManager().listResources(
                "dungeon_theme", id -> id.getPath().endsWith(".json"));
        List<Map.Entry<Identifier, Resource>> sorted = new ArrayList<>(resources.entrySet());
        sorted.sort(Map.Entry.comparingByKey());
        for (Map.Entry<Identifier, Resource> resource : sorted) {
            String id = baseName(resource.getKey());
            try (BufferedReader reader = resource.getValue().openAsReader()) {
                DungeonThemeMeta meta = DungeonThemeMeta.fromJson(
                        JsonParser.parseReader(reader).getAsJsonObject());
                Identifier processors = Identifier.parse(meta.processors);
                if (server.registryAccess().lookupOrThrow(Registries.PROCESSOR_LIST)
                        .getValue(processors) == null) {
                    throw new IllegalStateException("processor list not found: " + meta.processors);
                }
                entries.put(id, new Entry(id, meta));
            } catch (Exception exception) {
                String reason = resource.getKey() + " - " + exception.getMessage();
                rejections.add(reason);
                PocketDungeonsMod.LOG.error("Rejected dungeon theme '{}': {}", id, reason, exception);
            }
        }
        ThemeManifest loaded = new ThemeManifest(entries, rejections);
        current = loaded;
        PocketDungeonsMod.LOG.info("Loaded {} dungeon themes ({} rejected)",
                entries.size(), rejections.size());
        return loaded;
    }

    static ThemeManifest current() {
        return current;
    }

    Entry byId(String id) {
        return id == null ? null : byId.get(id);
    }

    List<Entry> themes() {
        return List.copyOf(byId.values());
    }

    List<String> discoverableIds() {
        return byId.values().stream().filter(entry -> entry.meta.discoverable)
                .map(Entry::id).sorted().toList();
    }

    List<String> rejections() {
        return rejections;
    }

    private static String baseName(Identifier location) {
        String path = location.getPath();
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        return name.substring(0, name.length() - 5);
    }
}
