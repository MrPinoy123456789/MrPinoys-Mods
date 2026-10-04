package pocketdungeons;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * What the player built in their safe room, read once each time they leave it
 * (playtest 2026-10-03, A9/A5: "you should be checking the state of my room,
 * which items I made, placed and stashed, each time I leave home").
 *
 * <p>The scan reads the room's saved blob ({@link RoomStore}), which is written
 * from the standing room just before the room is despawned, so it is the room
 * exactly as the player left it. It runs once per exit and does no per-tick
 * work. The result goes three places:
 * <ul>
 *   <li>a {@code room_scan} journal event, for the Lemon agent's {@code context};</li>
 *   <li>the {@code safe_room} field of {@link PlayerContext}, for the remote
 *       harness (the plain {@code room} field there is the dungeon room the
 *       player stands in);</li>
 *   <li>{@link StationTutorial}: a station whose block is already placed counts
 *       as seen, and the next nudge can say what is missing.</li>
 * </ul>
 *
 * <p>Lemon says nothing of her own about a scan (owner decision, 2026-10-03):
 * it only feeds {@code context} and the nudge.
 *
 * <p>The summarizer is pure over the saved NBT, so a headless test can drive it.
 */
final class RoomScan {

    private RoomScan() {}

    /** Vanilla workstations worth naming, besides the mod's own station blocks. */
    static final Set<String> WORKSTATIONS = Set.of(
            "minecraft:crafting_table", "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker",
            "minecraft:grindstone", "minecraft:smithing_table", "minecraft:anvil", "minecraft:chipped_anvil",
            "minecraft:damaged_anvil", "minecraft:stonecutter", "minecraft:loom", "minecraft:cartography_table",
            "minecraft:fletching_table", "minecraft:brewing_stand", "minecraft:enchanting_table");

    /** The most distinct item kinds a summary lists, biggest piles first, so it stays one short line. */
    static final int MAX_ITEM_KINDS = 12;

    /**
     * What a scan found.
     *
     * @param stations   workstation and station blocks placed, by id without the {@code minecraft:} prefix
     * @param containers how many chests, barrels and shulker boxes stand in the room
     * @param items      what those containers hold, by id without the prefix, biggest piles first
     */
    record Summary(Map<String, Integer> stations, int containers, Map<String, Integer> items) {

        boolean has(String blockId) {
            return stations.containsKey(strip(blockId));
        }

        boolean isEmpty() {
            return stations.isEmpty() && containers == 0 && items.isEmpty();
        }
    }

    private static final Map<UUID, Summary> LATEST = new HashMap<>();

    static void register() {
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> LATEST.clear());
    }

    /** The last scan of {@code player}'s room, or null if they have not left it yet this session. */
    static Summary latest(UUID player) {
        return LATEST.get(player);
    }

    /**
     * Reads {@code blob} (a saved room: a palette plus blocks with their block
     * entity data) into a summary. {@code stationIds} are the mod's own station
     * blocks, added to {@link #WORKSTATIONS}.
     */
    static Summary summarize(CompoundTag blob, Set<String> stationIds) {
        if (blob == null) {
            return new Summary(Map.of(), 0, Map.of());
        }
        ListTag palette = blob.getListOrEmpty("palette");
        Map<String, Integer> stations = new TreeMap<>();
        Map<String, Integer> items = new HashMap<>();
        int containers = 0;
        for (Tag entry : blob.getListOrEmpty("blocks")) {
            if (!(entry instanceof CompoundTag block)) {
                continue;
            }
            int state = block.getIntOr("state", -1);
            if (state < 0 || state >= palette.size()) {
                continue;
            }
            String name = palette.getCompoundOrEmpty(state).getStringOr("Name", "");
            if (WORKSTATIONS.contains(name) || stationIds.contains(name)) {
                stations.merge(strip(name), 1, Integer::sum);
            }
            if (isContainer(name)) {
                containers++;
                CompoundTag nbt = block.getCompoundOrEmpty("nbt");
                for (Tag stack : nbt.getListOrEmpty("Items")) {
                    if (stack instanceof CompoundTag item) {
                        String id = item.getStringOr("id", "");
                        if (!id.isEmpty()) {
                            items.merge(strip(id), Math.max(0, item.getIntOr("count", 1)), Integer::sum);
                        }
                    }
                }
            }
        }
        return new Summary(stations, containers, biggest(items));
    }

    private static boolean isContainer(String name) {
        return name.equals("minecraft:chest") || name.equals("minecraft:trapped_chest")
                || name.equals("minecraft:barrel") || name.contains("shulker_box");
    }

    /** The {@link #MAX_ITEM_KINDS} biggest piles, biggest first, ties by name. */
    private static Map<String, Integer> biggest(Map<String, Integer> items) {
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(items.entrySet());
        sorted.sort((a, b) -> a.getValue().equals(b.getValue())
                ? a.getKey().compareTo(b.getKey()) : Integer.compare(b.getValue(), a.getValue()));
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : sorted.subList(0, Math.min(MAX_ITEM_KINDS, sorted.size()))) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** The summary as JSON, for {@code context}. */
    static JsonObject toJson(Summary summary) {
        JsonObject o = new JsonObject();
        JsonObject stations = new JsonObject();
        summary.stations().forEach(stations::addProperty);
        o.add("stations", stations);
        o.addProperty("containers", summary.containers());
        JsonObject items = new JsonObject();
        summary.items().forEach(items::addProperty);
        o.add("items", items);
        return o;
    }

    /**
     * What Lemon's station nudge adds when the room is missing a basic: a
     * sentence to put in front of the generic line, or {@code null} to say the
     * generic line unchanged. Only a missing crafting table is named; every
     * station in {@link StationTutorial} is crafted at one.
     */
    static String tip(Summary summary) {
        if (summary == null || summary.has("minecraft:crafting_table")) {
            return null;
        }
        boolean hasWood = summary.items().keySet().stream().anyMatch(id -> id.endsWith("_planks") || id.endsWith("_log"));
        return hasWood
                ? "There is no crafting table in your room yet, and you have wood put by to make one."
                : "There is no crafting table in your room yet. Wood from the dungeon chests will make one.";
    }

    /**
     * Scans {@code owner}'s room as they leave it, after it was saved. Safe to
     * call with no saved room (nothing happens), and never throws into the door
     * commit: a failed scan is logged and the exit carries on.
     */
    static void onExit(MinecraftServer server, InstanceRecord record, ServerPlayer owner) {
        try {
            CompoundTag blob = RoomStore.load(server, record.owner);
            if (blob == null) {
                return;
            }
            Summary summary = summarize(blob, stationBlockIds());
            LATEST.put(record.owner, summary);
            DungeonLog log = DungeonLog.forServer(server);
            for (StationTutorial.Step step : StationTutorial.Step.values()) {
                if (summary.has(step.blockId())) {
                    StationTutorial.markDone(log, record.owner, step);
                }
            }
            PlaytestJournal.roomScan(owner, record, summary);
        } catch (RuntimeException e) {
            PocketDungeonsMod.LOG.warn("Room scan failed for {}: {}", record.owner, e.toString());
        }
    }

    /** The mod's own station blocks, as namespaced ids. */
    static Set<String> stationBlockIds() {
        Set<String> ids = new java.util.HashSet<>();
        for (StationTutorial.Step step : StationTutorial.Step.values()) {
            ids.add(step.blockId());
        }
        return ids;
    }

    static String strip(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }
}
