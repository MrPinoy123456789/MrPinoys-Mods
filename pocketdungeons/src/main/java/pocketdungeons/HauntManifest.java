package pocketdungeons;

import com.google.gson.JsonParser;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reloadable haunt definition manifest: loads {@code data/<namespace>/dungeon_haunt/*.json} into
 * {@link HauntDefinition}s keyed by namespaced id and publishes atomically through {@link ContentSnapshot}, the
 * way {@link AffixManifest} does. A definition that names an unknown mob, particle or sound, or a behaviour
 * outside the closed set, is rejected with its file named; the rest still load. An empty manifest is valid:
 * a pack with no haunts simply has none.
 */
final class HauntManifest {

    private static volatile HauntManifest current = new HauntManifest(Map.of(), List.of());

    private final Map<String, HauntDefinition> byId;
    private final List<String> rejections;

    private HauntManifest(Map<String, HauntDefinition> byId, List<String> rejections) {
        this.byId = Map.copyOf(byId);
        this.rejections = List.copyOf(rejections);
    }

    static HauntManifest parse(MinecraftServer server, ResourceManager rm) {
        Map<String, HauntDefinition> entries = new LinkedHashMap<>();
        List<String> rejections = new ArrayList<>();
        Map<Identifier, Resource> resources = rm.listResources(
                "dungeon_haunt", id -> id.getPath().endsWith(".json"));
        List<Map.Entry<Identifier, Resource>> sorted = new ArrayList<>(resources.entrySet());
        sorted.sort(Map.Entry.comparingByKey());
        for (Map.Entry<Identifier, Resource> resource : sorted) {
            String id = JsonPackSupport.resourceId(resource.getKey(), "dungeon_haunt");
            try (BufferedReader reader = resource.getValue().openAsReader()) {
                HauntDefinition def = HauntMeta.fromJson(JsonParser.parseReader(reader).getAsJsonObject(), id);
                validateRegistries(def);
                entries.put(id, def);
            } catch (Exception exception) {
                String reason = id + " - " + exception.getMessage();
                rejections.add(reason);
                PocketDungeonsMod.LOG.error("Rejected dungeon haunt '{}': {}", id, reason, exception);
            }
        }
        return new HauntManifest(entries, rejections);
    }

    /** The registry half of validation: the body, the particle and the sound must exist. */
    private static void validateRegistries(HauntDefinition def) {
        if (BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(def.body())).isEmpty()) {
            throw new IllegalStateException("body is not a mob type: " + def.body());
        }
        var particle = BuiltInRegistries.PARTICLE_TYPE.getOptional(Identifier.parse(def.tellParticle()));
        if (particle.isEmpty() || !(particle.get() instanceof SimpleParticleType)) {
            throw new IllegalStateException("tell_particle is not a simple particle: " + def.tellParticle());
        }
        if (BuiltInRegistries.SOUND_EVENT.getOptional(Identifier.parse(def.tellSound())).isEmpty()) {
            throw new IllegalStateException("tell_sound is not a sound: " + def.tellSound());
        }
    }

    static void publish(HauntManifest manifest) {
        current = manifest;
        PocketDungeonsMod.LOG.info("Loaded {} dungeon haunts ({} rejected)",
                manifest.byId.size(), manifest.rejections.size());
    }

    static HauntManifest current() {
        return current;
    }

    /** The haunt a dungeon carries, or {@code null}. The first by id when a pack gives one dungeon several. */
    HauntDefinition forDungeon(String dungeonId) {
        if (dungeonId == null || dungeonId.isEmpty()) {
            return null;
        }
        return byId.values().stream()
                .filter(def -> def.dungeons().contains(JsonPackSupport.qualify(dungeonId)))
                .min(Comparator.comparing(HauntDefinition::id))
                .orElse(null);
    }

    List<HauntDefinition> definitions() {
        return List.copyOf(byId.values());
    }

    List<String> rejections() {
        return rejections;
    }
}
