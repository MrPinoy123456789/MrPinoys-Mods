package pocketdungeons;

import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Crew model (decision 2026-10-21, spec 5): the reloadable act reward
 * manifest. Loads {@code data/<namespace>/act_reward/*.json} into
 * {@link ActReward.Act}s keyed by namespaced file id, validates each item
 * reference (crest, disc, decor) against the item registry, and publishes
 * atomically through {@link ContentSnapshot}. Mirrors {@link BagManifest}'s
 * shape: parse collects rejections without throwing, publish swaps the
 * live manifest in one step.
 */
final class ActRewardManifest {

    private static volatile ActRewardManifest current = new ActRewardManifest(Map.of(), List.of());

    private final Map<String, ActReward.Act> byId;
    private final Map<Integer, ActReward.Act> byAct;
    private final List<String> rejections;

    private ActRewardManifest(Map<String, ActReward.Act> byId, List<String> rejections) {
        this.byId = Map.copyOf(byId);
        Map<Integer, ActReward.Act> acts = new LinkedHashMap<>();
        for (ActReward.Act a : byId.values()) {
            acts.put(a.act(), a);
        }
        this.byAct = Map.copyOf(acts);
        this.rejections = List.copyOf(rejections);
    }

    /** The act's data, or null when no pack supplied it. */
    static ActReward.Act byAct(int act) {
        return current.byAct.get(act);
    }

    List<String> rejections() {
        return rejections;
    }

    /**
     * Parses every {@code act_reward} resource into a manifest without
     * publishing it, the build half of {@link ContentReload}'s atomic
     * reload. Each file's item references are validated against the item
     * registry; a file for an act outside 0 to 5 or a second file for the
     * same act is rejected with the id named. Missing acts surface as
     * rejections (a {@link PackValidator} finding), not as a hard gate.
     */
    static ActRewardManifest parse(MinecraftServer server) {
        return parse(server, server.getResourceManager());
    }

    /** F1: accepts the incoming ResourceManager from the reload callback. */
    static ActRewardManifest parse(MinecraftServer server, ResourceManager rm) {
        Map<String, ActReward.Act> entries = new LinkedHashMap<>();
        List<String> rejections = new ArrayList<>();
        Map<Identifier, Resource> resources = rm.listResources(
                "act_reward", id -> id.getPath().endsWith(".json"));
        List<Map.Entry<Identifier, Resource>> sorted = new ArrayList<>(resources.entrySet());
        sorted.sort(Map.Entry.comparingByKey());
        for (Map.Entry<Identifier, Resource> resource : sorted) {
            String id = JsonPackSupport.resourceId(resource.getKey(), "act_reward");
            try (BufferedReader reader = resource.getValue().openAsReader()) {
                ActReward.Act act = ActReward.fromJson(
                        JsonParser.parseReader(reader).getAsJsonObject(), id);
                if (byAct(entries, act.act()) != null) {
                    throw new IllegalStateException(id
                            + ": a file already defines act " + act.act());
                }
                validateItems(act, id);
                entries.put(id, act);
            } catch (Exception exception) {
                String reason = id + " - " + exception.getMessage();
                rejections.add(reason);
                PocketDungeonsMod.LOG.error("Rejected act reward '{}': {}", id, reason, exception);
            }
        }
        for (int act = DungeonDef.PROLOGUE_ACT; act <= DungeonDef.MAX_ACT; act++) {
            if (byAct(entries, act) == null) {
                rejections.add("act_reward - no file for act " + act);
            }
        }
        return new ActRewardManifest(entries, rejections);
    }

    private static ActReward.Act byAct(Map<String, ActReward.Act> entries, int act) {
        for (ActReward.Act a : entries.values()) {
            if (a.act() == act) {
                return a;
            }
        }
        return null;
    }

    /** Every item id the file names must resolve: crest, disc and decor. */
    private static void validateItems(ActReward.Act act, String id) {
        requireItem(act.crest(), id, "crest");
        ActReward.Rewards r = act.rewards();
        if (r.disc() != null) {
            requireItem(r.disc(), id, "disc");
        }
        for (ActReward.DecorItem d : r.decor()) {
            requireItem(d.item(), id, "decor");
        }
        for (String row : act.window()) {
            for (char c : row.toCharArray()) {
                requireItem(ActReward.glassId(c), id, "window");
            }
        }
    }

    private static void requireItem(String itemId, String id, String field) {
        Identifier loc;
        try {
            loc = Identifier.parse(itemId);
        } catch (Exception e) {
            throw new IllegalStateException(id + ": " + field + " bad item id: " + itemId);
        }
        if (BuiltInRegistries.ITEM.getOptional(loc).isEmpty()) {
            throw new IllegalStateException(id + ": " + field + " item not found: " + itemId);
        }
    }

    /** Commits a resolved manifest as the live {@link #current}. */
    static void publish(ActRewardManifest manifest) {
        current = manifest;
        PocketDungeonsMod.LOG.info("Loaded {} act rewards ({} rejected)",
                manifest.byId.size(), manifest.rejections.size());
    }
}
