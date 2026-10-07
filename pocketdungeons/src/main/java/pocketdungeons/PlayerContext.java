package pocketdungeons;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The context snapshot Lemon and any connected agent work from
 * ({@code docs/LEMON_SPEC.md} section 3): one player's situation as one line of
 * JSON, rebuilt on demand by {@code dungeon admin context <player>}.
 *
 * <p>Split in two: {@link #gather} reads the live server into a plain
 * {@link Snapshot}, and {@link #toJson} turns that into JSON with no server in
 * sight, which is the part {@code PlayerContextTest} covers.
 */
final class PlayerContext {

    private PlayerContext() {}

    /** Journal events carried in {@code recent}. */
    static final int RECENT_EVENTS = 20;

    /** One plan room of the floor, for {@code rooms}. */
    record RoomView(String cell, String room, String role, boolean entered, int spawnersCleared,
                    int spawnersTotal, boolean locked) {}

    /** A damageable tool or weapon: what is left of it and what it started with. */
    record ToolView(String item, int left, int max) {}

    /** Everything the JSON says, as plain values. */
    record Snapshot(String t, String player, String name, String phase, int slot, int floor,
                    String zone, int keystone, int runLevel, int party, String dimension, String room,
                    String roomCell, List<RoomView> rooms, int floorOmen, int intervalOmen, int band, int floorsCounted,
                    int spawnersCleared, int spawnersNeeded, int spawnersTotal,
                    List<ToolView> tools, int blocks, int food, int freeSlots, boolean nearFull,
                    Lemon.View lemon, List<JsonObject> recent,
                    /** The player's latest {@code bank} event, or null if there is none (PD-148). */
                    JsonObject lastBank,
                    /** The last {@link RoomScan} of the player's safe room, or null before their first exit. */
                    JsonObject safeRoom) {}

    /** A pack with this few free slots or fewer is near full. */
    static final int NEAR_FULL_SLOTS = 3;

    /**
     * The snapshot as JSON. Top-level keys, in order: {@code t player name phase
     * slot floor zone keystone run_level party dimension room room_cell rooms omen spawners
     * inventory lemon recent}, then {@code last_bank} once the player has banked (their
     * latest {@code bank} event, which the 20 recent events can scroll past), then
     * {@code safe_room} once the player has left home
     * at least once (the last {@link RoomScan}; the plain {@code room} key is the
     * dungeon room they stand in).
     */
    static JsonObject toJson(Snapshot s) {
        JsonObject o = new JsonObject();
        o.addProperty("t", s.t());
        o.addProperty("player", s.player());
        o.addProperty("name", s.name());
        o.addProperty("phase", s.phase());
        o.addProperty("slot", s.slot());
        o.addProperty("floor", s.floor());
        o.addProperty("zone", s.zone());
        o.addProperty("keystone", s.keystone());
        o.addProperty("run_level", s.runLevel());
        o.addProperty("party", s.party());
        o.addProperty("dimension", s.dimension());
        o.addProperty("room", s.room());
        o.addProperty("room_cell", s.roomCell());

        JsonArray rooms = new JsonArray();
        for (RoomView r : s.rooms()) {
            JsonObject room = new JsonObject();
            room.addProperty("cell", r.cell());
            room.addProperty("room", r.room());
            room.addProperty("role", r.role());
            room.addProperty("entered", r.entered());
            room.addProperty("here", r.cell().equals(s.roomCell()));
            JsonObject spawners = new JsonObject();
            spawners.addProperty("cleared", r.spawnersCleared());
            spawners.addProperty("total", r.spawnersTotal());
            room.add("spawners", spawners);
            room.addProperty("locked", r.locked());
            rooms.add(room);
        }
        o.add("rooms", rooms);

        JsonObject omen = new JsonObject();
        omen.addProperty("floor", s.floorOmen());
        omen.addProperty("interval", s.intervalOmen());
        omen.addProperty("band", s.band());
        omen.addProperty("floors", s.floorsCounted());
        o.add("omen", omen);

        JsonObject gate = new JsonObject();
        gate.addProperty("cleared", s.spawnersCleared());
        gate.addProperty("needed", s.spawnersNeeded());
        gate.addProperty("total", s.spawnersTotal());
        o.add("spawners", gate);

        JsonObject inventory = new JsonObject();
        JsonArray tools = new JsonArray();
        for (ToolView tool : s.tools()) {
            JsonObject t = new JsonObject();
            t.addProperty("item", tool.item());
            t.addProperty("left", tool.left());
            t.addProperty("max", tool.max());
            tools.add(t);
        }
        inventory.add("tools", tools);
        inventory.addProperty("blocks", s.blocks());
        inventory.addProperty("food", s.food());
        inventory.addProperty("free_slots", s.freeSlots());
        inventory.addProperty("near_full", s.nearFull());
        o.add("inventory", inventory);

        JsonObject lemon = new JsonObject();
        Lemon.View view = s.lemon();
        lemon.addProperty("present", view.present());
        lemon.addProperty("mode", view.mode());
        lemon.addProperty("waiting", view.waiting());
        lemon.addProperty("question", view.question());
        lemon.addProperty("pending_questions", view.pendingQuestions());
        lemon.addProperty("quiet", view.quiet());
        o.add("lemon", lemon);

        JsonArray recent = new JsonArray();
        for (JsonObject event : s.recent()) {
            recent.add(event);
        }
        o.add("recent", recent);
        if (s.lastBank() != null) {
            o.add("last_bank", s.lastBank());
        }
        if (s.safeRoom() != null) {
            o.add("safe_room", s.safeRoom());
        }
        return o;
    }

    /**
     * The band the interval stands in so far: the sum over every floor
     * counted (the banked ones and the one in play), with the thresholds
     * scaled to that many floors.
     */
    static int bandSoFar(int intervalOmen, int floorsCounted) {
        return Omen.band(intervalOmen, Math.max(1, floorsCounted));
    }

    // ---- reading the live server ---------------------------------------------------

    static Snapshot gather(MinecraftServer server, ServerPlayer player) {
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        ServerLevel dungeon = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        String t = JournalFormat.timestamp(Instant.now());
        int keystone = DungeonLog.forServer(server).get(player.getUUID()).keystoneLevel();

        List<RoomView> rooms = new ArrayList<>();
        String phase = "NONE";
        int slot = -1;
        int floor = -1;
        String zone = "";
        int runLevel = 0;
        int party = 1 + PartyService.partyCompanions(player.getUUID()).size();
        String room = "";
        String roomCell = "";
        int floorOmen = 0;
        int intervalOmen = 0;
        int floorsCounted = 0;
        int cleared = 0;
        int total = 0;
        if (record != null) {
            phase = record.phase.name();
            slot = record.slot;
            floor = PlaytestJournal.floorOf(record);
            zone = record.floor.theme == null ? "" : record.floor.theme;
            runLevel = record.layout == null ? 0 : record.layout.keystoneLevel();
            party = Math.max(1, record.members.size());
            room = FloorRooms.roomAt(record, player.blockPosition());
            FloorRooms.Room placed = FloorRooms.placedAt(record, player.blockPosition());
            roomCell = placed == null ? "" : placed.cellKey();
            floorOmen = Omen.clamp(record.interval.omen);
            intervalOmen = record.interval.omenSum();
            floorsCounted = record.interval.floorOmens.size()
                    + (record.phase == RunSession.Phase.ACTIVE ? 1 : 0);
            if (dungeon != null && record.layout != null && record.layout.geometry() != null) {
                rooms = roomViews(dungeon, record);
                Set<BlockPos> spawners = TrialContent.activeSpawners(record.layout, dungeon);
                total = spawners.size();
                cleared = TrialContent.countCleared(dungeon, spawners);
            }
        }

        Inventory inventory = player.getInventory();
        List<ToolView> tools = new ArrayList<>();
        int blocks = 0;
        int food = 0;
        int free = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                free++;
                continue;
            }
            if (stack.isDamageableItem()) {
                tools.add(new ToolView(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                        stack.getMaxDamage() - stack.getDamageValue(), stack.getMaxDamage()));
            }
            if (stack.getItem() instanceof BlockItem) {
                blocks += stack.getCount();
            }
            if (stack.has(DataComponents.FOOD)) {
                food += stack.getCount();
            }
        }

        return new Snapshot(t, player.getUUID().toString(), player.getName().getString(), phase, slot, floor,
                zone, keystone, runLevel, party, player.level().dimension().identifier().toString(), room,
                roomCell, rooms, floorOmen, intervalOmen, bandSoFar(intervalOmen, floorsCounted), floorsCounted,
                cleared, DifficultyProfile.spawnersNeeded(total, PocketDungeonsConfig.spawnerClearThreshold()), total,
                tools, blocks, food, free, free <= NEAR_FULL_SLOTS, Lemon.view(player),
                PlaytestJournal.recent(player.getUUID(), RECENT_EVENTS),
                PlaytestJournal.lastBank(player.getUUID()),
                RoomScan.latest(player.getUUID()) == null ? null : RoomScan.toJson(RoomScan.latest(player.getUUID())));
    }

    /** Every plan room of the floor with its entered flag, spawners and lock. */
    private static List<RoomView> roomViews(ServerLevel level, InstanceRecord record) {
        Map<PlanCell, List<BlockPos>> spawnersByCell = new HashMap<>();
        for (BlockPos pos : record.layout.trialSpawners()) {
            PlanCell cell = record.layout.geometry().cellAt(pos);
            if (cell != null) {
                spawnersByCell.computeIfAbsent(cell, c -> new ArrayList<>()).add(pos);
            }
        }
        Set<BlockPos> allActive = TrialContent.activeSpawners(record.layout, level);
        List<RoomView> out = new ArrayList<>();
        for (FloorRooms.Room room : record.floor.rooms.values()) {
            Set<BlockPos> active = new HashSet<>(spawnersByCell.getOrDefault(room.cell(), List.of()));
            active.retainAll(allActive);
            BlockPos origin = record.layout.geometry().cellOrigin(room.cell());
            out.add(new RoomView(room.cellKey(), room.id(), room.role(),
                    record.floor.enteredRooms.contains(room.cell()),
                    TrialContent.countCleared(level, active), active.size(), Locks.isArmed(origin)));
        }
        return out;
    }
}
