package pocketdungeons;

import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Reloadable loader for {@code data/pocketdungeons/dungeon/*.json}, the
 * dungeon structure data type (see {@link DungeonDef}). Mirrors
 * {@link AdventureGraphs}: a {@code volatile current} holder, sorted resource
 * entries, one rejection per malformed dungeon rather than one failure for the
 * whole pack, and a parse then publish split so {@link ContentSnapshot} can
 * validate before {@link ContentReload} commits.
 *
 * <p>Wave W1 only loads and validates; nothing reads the dungeons for
 * gameplay yet, so loading changes no behaviour.
 */
final class DungeonDefs {

    static final String FOLDER = "dungeon";

    private static volatile DungeonDefs current = new DungeonDefs(Map.of(), List.of());

    private final Map<String, DungeonDef> byId;
    private final List<String> rejections;

    private DungeonDefs(Map<String, DungeonDef> byId, List<String> rejections) {
        this.byId = Map.copyOf(byId);
        this.rejections = List.copyOf(rejections);
    }

    /** Parses every dungeon resource without publishing, the build half of the atomic reload. */
    static DungeonDefs parse(MinecraftServer server, ResourceManager rm, Predicate<String> themeExists) {
        Map<String, DungeonDef> parsed = new LinkedHashMap<>();
        List<String> rejections = new ArrayList<>();
        Map<Identifier, Resource> resources = rm.listResources(FOLDER, id -> id.getPath().endsWith(".json"));
        List<Map.Entry<Identifier, Resource>> sorted = new ArrayList<>(resources.entrySet());
        sorted.sort(Map.Entry.comparingByKey());
        for (Map.Entry<Identifier, Resource> resource : sorted) {
            String id = JsonPackSupport.resourceId(resource.getKey(), FOLDER);
            try (BufferedReader reader = resource.getValue().openAsReader()) {
                parsed.put(id, DungeonDef.fromJson(id, JsonParser.parseReader(reader).getAsJsonObject()));
            } catch (Exception exception) {
                String reason = resource.getKey() + " - " + exception.getMessage();
                rejections.add(reason);
                PocketDungeonsMod.LOG.error("Rejected dungeon '{}': {}", id, reason, exception);
            }
        }
        return validate(parsed, themeExists, rejections);
    }

    /**
     * The pure half of loading: runs every structural and cross dungeon rule
     * over already parsed dungeons, rejecting each dungeon that breaks any, and
     * keeping the rest. Rejections are appended to {@code rejections} in the
     * "id - reason" shape the pack validator splits. {@code themeExists} says
     * whether a qualified theme id is a loaded theme.
     */
    static DungeonDefs validate(Map<String, DungeonDef> parsed, Predicate<String> themeExists,
                                List<String> rejections) {
        Map<String, Integer> actOfTheme = actOfTheme(parsed.values());
        Map<String, DungeonDef> kept = new LinkedHashMap<>();
        for (DungeonDef def : parsed.values()) {
            List<String> problems = new ArrayList<>();
            if (!themeExists.test(def.mainTheme())) {
                problems.add("main theme not found: " + def.mainTheme());
            }
            problems.addAll(def.problems(theme -> actOfTheme.getOrDefault(theme, 0)));
            if (problems.isEmpty()) {
                kept.put(def.id(), def);
            } else {
                for (String problem : problems) {
                    rejections.add(def.id() + " - " + problem);
                }
            }
        }
        return new DungeonDefs(kept, rejections);
    }

    /** Main theme to the earliest act of a dungeon that uses it as its main theme. */
    static Map<String, Integer> actOfTheme(Collection<DungeonDef> defs) {
        Map<String, Integer> out = new HashMap<>();
        for (DungeonDef def : defs) {
            out.merge(def.mainTheme(), def.act(), Math::min);
        }
        return out;
    }

    /** Commits a resolved set as the live {@link #current}, the publish half of the atomic reload. */
    static void publish(DungeonDefs dungeons) {
        current = dungeons;
        PocketDungeonsMod.LOG.info("Loaded {} dungeon(s) ({} rejected)",
                dungeons.byId.size(), dungeons.rejections.size());
    }

    static DungeonDefs current() {
        return current;
    }

    DungeonDef byId(String id) {
        if (id == null) {
            return null;
        }
        DungeonDef direct = byId.get(id);
        if (direct != null) {
            return direct;
        }
        return id.indexOf(':') < 0 ? byId.get(DungeonDef.DEFAULT_NAMESPACE + ":" + id) : null;
    }

    /** Every loaded dungeon, sorted by id. */
    List<DungeonDef> all() {
        List<DungeonDef> out = new ArrayList<>(byId.values());
        out.sort(Comparator.comparing(DungeonDef::id));
        return out;
    }

    int size() {
        return byId.size();
    }

    List<String> rejections() {
        return rejections;
    }
}
