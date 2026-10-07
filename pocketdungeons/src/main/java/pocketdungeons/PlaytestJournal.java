package pocketdungeons;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import pocketdungeons.mixin.ServerCommonPacketListenerAccessor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;

/**
 * The in-game playtest journal: every event in {@code docs/PLAYTEST_EVENTS.md},
 * appended per player per day to
 * {@code <world>/pocketdungeons/playtest/<yyyy-mm-dd>/<uuid>.jsonl} and flushed
 * per line. Local files only.
 *
 * <p>Every entry point swallows its own failures (logged at WARN, never
 * ERROR, so the error event cannot feed on itself): a journal problem never
 * throws into game code. {@code playtestJournal: false} in the config turns
 * the whole thing off.
 *
 * <p>Floor tallies for {@code floor_complete}: {@code blocks_placed} counts the
 * player's successful block placements anywhere in the dungeon dimension
 * while the floor is open (the staging room included), through the same
 * placement hook {@link DungeonTools#recordPlayerPlacement} uses.
 * {@code durability_used} is approximate: the rise in total damage across the
 * player's inventory and armour between the floor's commit (or the first
 * time the player is seen on it) and the completion. A tool that breaks,
 * gets repaired or leaves the inventory in between makes it read low; a
 * damaged item picked up makes it read high.
 */
final class PlaytestJournal {

    private PlaytestJournal() {}

    /** Where the date folders live, under the world save. */
    private static final String DIR = "pocketdungeons/playtest";

    /** How long an enter or leave hint waits for the dimension change it explains. */
    private static final long HINT_TTL_NANOS = 10_000_000_000L;

    private static MinecraftServer server;

    private record Hint(String value, InstanceRecord record, long at) {}

    private static final Map<UUID, Hint> ENTER_HINTS = new HashMap<>();
    private static final Map<UUID, Hint> LEAVE_HINTS = new HashMap<>();

    /** Per-player counters for the floor in progress, reset when the floor changes. */
    private static final class FloorTally {
        long floorStart;
        int rescues;
        int blocksPlaced;
        int nodesMined;
        long damageAtStart;
    }

    private static final Map<UUID, FloorTally> TALLIES = new HashMap<>();

    /** Mod errors from any thread, attributed on the server thread (see {@link #drainErrors}). */
    private static final ConcurrentLinkedQueue<String> PENDING_ERRORS = new ConcurrentLinkedQueue<>();

    /** Wires the journal's own hooks. Call from {@code onInitialize} before {@code Instances.register}. */
    static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            safely("retention", () -> applyRetention(s));
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            server = null;
            TALLIES.clear();
            ENTER_HINTS.clear();
            LEAVE_HINTS.clear();
        });
        ServerTickEvents.END_SERVER_TICK.register(PlaytestJournal::drainErrors);

        // Registered ahead of Instances' own handlers, so the common fields
        // are read while the player is still a member of their instance.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> safely("join", () -> {
            ServerPlayer player = handler.getPlayer();
            record(player, "session_join", Map.of());
            if (inDungeon(player)) {
                record(player, "enter_dungeon", Map.of("via", "rejoin"));
            }
        }));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
            ServerPlayer player = handler.getPlayer();
            String reason = leaveReason(handler);
            s.execute(() -> safely("disconnect", () -> {
                if (inDungeon(player)) {
                    record(player, "leave_dungeon", Map.of("reason", "disconnect"));
                }
                record(player, "session_leave", Map.of("reason", reason));
                TALLIES.remove(player.getUUID());
                ENTER_HINTS.remove(player.getUUID());
                LEAVE_HINTS.remove(player.getUUID());
            }));
        });
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) ->
                safely("level change", () -> {
                    boolean from = origin.dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL);
                    boolean to = destination.dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL);
                    if (to && !from) {
                        Hint hint = consume(ENTER_HINTS, player.getUUID());
                        record(player, "enter_dungeon", Map.of("via", hint == null ? "command" : hint.value()));
                    } else if (from && !to) {
                        Hint hint = consume(LEAVE_HINTS, player.getUUID());
                        record(player.getUUID(), player.getName().getString(), hint == null ? null : hint.record(),
                                "leave_dungeon", Map.of("reason", hint == null ? "other" : hint.value()));
                    }
                }));

        try {
            JournalErrorAppender.install(PENDING_ERRORS);
        } catch (Throwable t) {
            PocketDungeonsMod.LOG.warn("Playtest journal: could not watch the log for errors ({})", t.toString());
        }
    }

    /**
     * {@code quit} for a connection the client closed ({@code disconnect.endOfStream},
     * which is what quitting to the title screen produces), {@code disconnect}
     * for anything else (a timeout, a kick, a network error). Approximate: a
     * client that crashes can also close its end cleanly.
     */
    private static String leaveReason(ServerGamePacketListenerImpl handler) {
        try {
            DisconnectionDetails details = ((ServerCommonPacketListenerAccessor) handler)
                    .pocketdungeons$connection().getDisconnectionDetails();
            if (details != null && details.reason().getContents() instanceof TranslatableContents t
                    && (t.getKey().equals("disconnect.endOfStream") || t.getKey().equals("disconnect.quitting"))) {
                return "quit";
            }
        } catch (RuntimeException e) {
            // Unknown: fall through.
        }
        return "disconnect";
    }

    // ---- hints for enter and leave --------------------------------------------

    /**
     * Says how the next crossing into the dungeon dimension happens
     * ({@code reenter}, {@code rejoin}, {@code visit}, {@code invite}). The first
     * hint wins, so a caller that knows more can set one before a more general
     * path it goes through.
     */
    static void hintEnter(UUID player, String via) {
        hint(ENTER_HINTS, player, via, null);
    }

    /**
     * Says why the next crossing out of the dungeon dimension happens
     * ({@code exit}, {@code checkpoint_exit}, {@code purge}, {@code rescue_eject}).
     * The record is kept so the leave line still carries the instance the
     * player was in, though they are no longer a member by then. The first
     * hint wins.
     */
    static void hintLeave(UUID player, String reason, InstanceRecord record) {
        hint(LEAVE_HINTS, player, reason, record);
    }

    /** Sets a hint unless a fresh one is already waiting; a stale one is replaced. */
    private static void hint(Map<UUID, Hint> hints, UUID player, String value, InstanceRecord record) {
        Hint existing = hints.get(player);
        if (existing != null && System.nanoTime() - existing.at() < HINT_TTL_NANOS) {
            return;
        }
        hints.put(player, new Hint(value, record, System.nanoTime()));
    }

    private static Hint consume(Map<UUID, Hint> hints, UUID player) {
        Hint hint = hints.remove(player);
        return hint != null && System.nanoTime() - hint.at() < HINT_TTL_NANOS ? hint : null;
    }

    // ---- the events -------------------------------------------------------------

    static void bagChosen(ServerPlayer player, String bag) {
        safely("bag_chosen", () -> record(player, "bag_chosen", Map.of("bag", bag)));
    }

    /** A door preview was stamped: written for every member present. */
    static void doorPreview(MinecraftServer s, InstanceRecord record, int step, int level, String theme,
                            Collection<String> affixes) {
        safely("door_preview", () -> {
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("step", step);
            extras.put("level", level);
            extras.put("theme", theme == null ? "" : theme);
            extras.put("affixes", sorted(affixes));
            forMembers(s, record, "door_preview", extras);
        });
    }

    /**
     * A door was committed: written for every member present, and each of
     * them starts a fresh floor tally.
     */
    static void doorCommit(MinecraftServer s, InstanceRecord record, int step, int fuelSpent) {
        safely("door_commit", () -> {
            List<String> rooms = new ArrayList<>();
            for (FloorRooms.Room room : record.floor.rooms.values()) {
                rooms.add(room.id());
            }
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("step", step);
            extras.put("level", record.layout.keystoneLevel());
            extras.put("theme", record.floor.theme == null ? "" : record.floor.theme);
            extras.put("affixes", sorted(record.floor.affixes));
            extras.put("fuel_spent", fuelSpent);
            extras.put("rooms", rooms);
            forMembers(s, record, "door_commit", extras);
            for (UUID member : record.members.keySet()) {
                ServerPlayer player = s.getPlayerList().getPlayer(member);
                if (player != null) {
                    tally(player, record);
                }
            }
        });
    }

    /**
     * Dungeon structure W2: a door was committed inside a dungeon. Written for every
     * member present: {@code dungeon_chosen} on the first door of a trip,
     * {@code edge_taken} (with the step dealt and the scrap cost) on every later one,
     * then {@code node_entered} for the floor now opening. The trip state on
     * {@code record.interval} is already updated.
     */
    static void tripDoor(MinecraftServer s, InstanceRecord record, boolean firstDoor, String fromNode,
                         int step, int cost) {
        safely("trip_door", () -> {
            DungeonDef def = DungeonDefs.current().byId(record.interval.dungeonId);
            DungeonDef.Node node = def == null ? null : def.node(record.interval.nodeId);
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("dungeon", record.interval.dungeonId);
            if (firstDoor) {
                extras.put("act", def == null ? 0 : def.act());
                extras.put("kind", def == null ? "" : def.kind().name().toLowerCase());
                extras.put("entry", record.interval.nodeId);
                extras.put("step", step);
                forMembers(s, record, "dungeon_chosen", extras);
            } else {
                extras.put("from", fromNode);
                extras.put("to", record.interval.nodeId);
                extras.put("step", step);
                extras.put("cost", cost);
                forMembers(s, record, "edge_taken", extras);
            }
            Map<String, Object> entered = new LinkedHashMap<>();
            entered.put("dungeon", record.interval.dungeonId);
            entered.put("node", record.interval.nodeId);
            entered.put("name", node == null ? "" : node.name());
            entered.put("layer", node == null ? 0 : node.layer());
            entered.put("final", node != null && node.isFinal());
            entered.put("step", step);
            entered.put("path_length", record.interval.path.size());
            forMembers(s, record, "node_entered", entered);
        });
    }

    /**
     * Dungeon structure W2: a final floor was cleared. One line per member present;
     * {@code emeralds} and {@code vault_chests} are what the finish paid, {@code first}
     * whether it was this player's first finish of the dungeon, {@code diary} the page
     * id handed over (empty for none).
     */
    static void dungeonFinished(ServerPlayer player, InstanceRecord record, int emeralds, int vaultChests,
                                boolean first, String diary) {
        safely("dungeon_finished", () -> {
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("dungeon", record.interval.dungeonId);
            extras.put("node", record.interval.nodeId);
            extras.put("floors", record.interval.path.size());
            extras.put("emeralds", emeralds);
            extras.put("vault_chests", vaultChests);
            extras.put("first", first);
            extras.put("diary", diary == null ? "" : diary);
            record(player, record, "dungeon_finished", extras);
        });
    }

    /**
     * Dungeon structure W3: a capstone clear opened an act for {@code player}
     * ({@code act} 0 with {@code campaignComplete} for an act 5 capstone).
     */
    static void actUnlocked(ServerPlayer player, InstanceRecord record, String dungeon, int act,
                            boolean campaignComplete) {
        safely("act_unlocked", () -> {
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("dungeon", dungeon);
            extras.put("act", act);
            extras.put("campaign_complete", campaignComplete);
            record(player, record, "act_unlocked", extras);
        });
    }

    /**
     * The watcher's per-member check: the first time any member stands in a
     * plan room of this floor, that member gets a {@code room_entered} line.
     */
    static void roomEntered(ServerPlayer player, InstanceRecord record) {
        safely("room_entered", () -> {
            FloorRooms.Room room = FloorRooms.placedAt(record, player.blockPosition());
            if (room != null && record.floor.enteredRooms.add(room.cell())) {
                record(player, record, "room_entered", Map.of("room", room.id(), "cell", room.cellKey()));
            }
        });
    }

    /**
     * The omen went up: written for every member present. {@code at} is where
     * the rise came from (the dweller, the sensor's cell) or null for a
     * floor-wide source, in which case each member's own room is named.
     */
    static void omenRise(MinecraftServer s, InstanceRecord record, Omen.Source source, int amount,
                         int total, BlockPos at) {
        safely("omen_rise", () -> {
            String sourceName = source == Omen.Source.DEPTH ? "headstart"
                    : source.name().toLowerCase(java.util.Locale.ROOT);
            for (UUID member : record.members.keySet()) {
                ServerPlayer player = s.getPlayerList().getPlayer(member);
                if (player == null) {
                    continue;
                }
                Map<String, Object> extras = new LinkedHashMap<>();
                extras.put("source", sourceName);
                extras.put("amount", amount);
                extras.put("total", total);
                extras.put("room", FloorRooms.roomAt(record, at != null ? at : player.blockPosition()));
                record(player, record, "omen_rise", extras);
            }
        });
    }

    /** A killing blow was turned into a rescue. Call before the rescue moves the player. */
    static void rescue(ServerPlayer player, InstanceRecord record, DamageSource source) {
        safely("rescue", () -> {
            String cause = source.typeHolder().unwrapKey()
                    .map(key -> key.identifier().toString()).orElse("unknown");
            tally(player, record).rescues++;
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("cause", cause);
            extras.put("room", FloorRooms.roomAt(record, player.blockPosition()));
            record(player, record, "rescue", extras);
            hintLeave(player.getUUID(), "rescue_eject", record);
        });
    }

    /** The run ended because a max-omen death failed the whole party. */
    static void runFailed(MinecraftServer server, InstanceRecord record, ServerPlayer player,
                        net.minecraft.world.damagesource.DamageSource source) {
        safely("run_failed", () -> {
            String cause = source.typeHolder().unwrapKey()
                    .map(key -> key.identifier().toString()).orElse("unknown");
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("cause", cause);
            extras.put("floor_omen", Omen.clamp(record.interval.omen));
            extras.put("interval_omen", record.interval.omenSum());
            extras.put("floor", record.interval.floorIndex + 1);
            extras.put("slot", record.slot);
            record(player, record, "run_failed", extras);
        });
    }

    /** One successful block placement in the dungeon dimension, for the floor tally. */
    static void countPlacement(UUID player) {
        safely("placement", () -> {
            MinecraftServer s = server;
            InstanceRecord record = InstanceRegistry.byMember.get(player);
            ServerPlayer online = s == null ? null : s.getPlayerList().getPlayer(player);
            if (record != null && online != null) {
                tally(online, record).blocksPlaced++;
            }
        });
    }

    /** One resource node mined (dungeon structure W4), for the floor tally. */
    static void countNodeMined(UUID player) {
        safely("node_mined", () -> {
            MinecraftServer s = server;
            InstanceRecord record = InstanceRegistry.byMember.get(player);
            ServerPlayer online = s == null ? null : s.getPlayerList().getPlayer(player);
            if (record != null && online != null) {
                tally(online, record).nodesMined++;
            }
        });
    }

    /** This player was credited with the floor. Call after the floor has advanced. */
    static void floorComplete(ServerPlayer player, InstanceRecord record, int spawnersCleared, int spawnersTotal) {
        safely("floor_complete", () -> {
            FloorTally tally = tally(player, record);
            long now = player.level().getServer().overworld().getGameTime();
            int omen = record.interval.floorOmens.isEmpty() ? 0
                    : record.interval.floorOmens.get(record.interval.floorOmens.size() - 1);
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("seconds", record.floor.startedAtTick == 0 ? 0 : (now - record.floor.startedAtTick) / 20);
            extras.put("omen", omen);
            extras.put("spawners_cleared", spawnersCleared);
            extras.put("spawners_total", spawnersTotal);
            extras.put("rescues", tally.rescues);
            extras.put("blocks_placed", tally.blocksPlaced);
            extras.put("nodes_mined", tally.nodesMined);
            extras.put("nodes_total", record.floor.nodesTotal);
            extras.put("durability_used", Math.max(0L, inventoryDamage(player) - tally.damageAtStart));
            extras.put("chests", Math.max(0, record.floor.rewardChests));
            record(player, record, "floor_complete", extras);
        });
    }

    /** A floor's pay to one member (J1): scrap earned, or emeralds when over-level. */
    static void floorPay(ServerPlayer player, InstanceRecord record, int scrap, int emeralds) {
        safely("floor_pay", () -> record(player, record, "floor_pay",
                Map.of("scrap", scrap, "emeralds", emeralds)));
    }

    /** The interval settled for this member. */
    static void bank(ServerPlayer player, InstanceRecord record, String trigger, int floors,
                     IntervalBanking.Settlement settled, int chests, int depthBonus, int keyLevel) {
        safely("bank", () -> {
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("trigger", trigger);
            extras.put("floors", floors);
            extras.put("band", settled.band());
            extras.put("levels_gained", settled.levels());
            extras.put("scrap_left", settled.scrapLeft());
            extras.put("chests", chests);
            extras.put("depth_bonus", depthBonus);
            extras.put("key_level", keyLevel);
            record(player, record, "bank", extras);
        });
    }

    /** A kit top-up was applied (even one that granted nothing). */
    static void kitTopUp(ServerPlayer player, InstanceRecord record, int band, KitTopUp.Plan plan) {
        safely("kit_topup", () -> {
            Map<String, Integer> granted = new LinkedHashMap<>();
            List<String> tools = new ArrayList<>();
            for (KitTopUp.Grant grant : plan.grants()) {
                granted.merge(grant.item(), grant.count(), Integer::sum);
                if (grant.durability()) {
                    tools.add(grant.item());
                }
            }
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("band", band);
            extras.put("granted", granted);
            extras.put("tools_replaced", tools);
            record(player, record, "kit_topup", extras);
        });
    }

    /**
     * Every item the player holds, by store (playtest 2026-10-03-2: the loot
     * could not be analysed afterwards, because the journal kept only an
     * inventory digest). Written at a floor clear, a bank and an exit:
     * {@code pack} is the live inventory (armour and off hand included),
     * {@code run_storage} the run's ender chest, {@code ender_chest} the real
     * one, {@code kept} the dungeon pack waiting for the next entry, and
     * {@code survival_stashed} only counts the stacks of the survival
     * inventory held while the player is inside.
     */
    static void inventorySnapshot(ServerPlayer player, InstanceRecord record, String trigger) {
        safely("inventory_snapshot", () -> {
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("trigger", trigger);
            Inventory inventory = player.getInventory();
            List<ItemStack> pack = new ArrayList<>();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                pack.add(inventory.getItem(i));
            }
            extras.put("pack", describe(pack));
            extras.put("run_storage", describe(DungeonLog.forServer(player.level().getServer())
                    .storageOf(player.getUUID())));
            extras.put("ender_chest", describe(player.getEnderChestInventory().getItems()));
            DungeonLog log = DungeonLog.forServer(player.level().getServer());
            extras.put("kept", describe(log.orphanOf(player.getUUID()).items()));
            extras.put("survival_stashed", (int) log.stashOf(player.getUUID()).backup().stream()
                    .filter(s -> !s.isEmpty()).count());
            record(player, record, "inventory_snapshot", extras);
        });
    }

    /**
     * A list of stacks as {@code items} (id to total count, sorted) and
     * {@code gear} (one entry per damageable piece: id, durability left and
     * max, loot tier, enchantment count, and whether it is kit). Package
     * private for the gametest.
     */
    static Map<String, Object> describe(Collection<ItemStack> stacks) {
        Map<String, Integer> items = new java.util.TreeMap<>();
        List<Map<String, Object>> gear = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            items.merge(id, stack.getCount(), Integer::sum);
            if (stack.isDamageableItem()) {
                Map<String, Object> piece = new LinkedHashMap<>();
                piece.put("item", id);
                piece.put("left", stack.getMaxDamage() - stack.getDamageValue());
                piece.put("max", stack.getMaxDamage());
                int tier = RerollStation.tierOf(stack);
                if (tier > 0) {
                    piece.put("tier", tier);
                }
                int enchants = stack.getEnchantments().size();
                if (enchants > 0) {
                    piece.put("enchants", enchants);
                }
                if (InventorySwap.isBagTagged(stack) && tier <= 0) {
                    piece.put("kit", true);
                }
                gear.add(piece);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("gear", gear);
        return out;
    }

    /** The safe room as the player left it: the stations placed and what the chests hold (playtest 2026-10-03, A9). */
    static void roomScan(ServerPlayer player, InstanceRecord record, RoomScan.Summary summary) {
        safely("room_scan", () -> {
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("stations", summary.stations());
            extras.put("containers", summary.containers());
            extras.put("items", summary.items());
            record(player, record, "room_scan", extras);
        });
    }

    static void quitFloor(ServerPlayer player, int penalty) {
        safely("quit_floor", () -> record(player, "quit_floor", Map.of("penalty", penalty)));
    }

    /** An Ordeal resolved, written for every player in the room: which one, and seconds since it armed. */
    static void ordealResolved(ServerPlayer player, String ordeal, long seconds) {
        safely("ordeal", () -> record(player, "ordeal", Map.of("ordeal", ordeal, "seconds", seconds)));
    }

    /** A diary entry was handed to Lemon: its band and how many she now holds. */
    static void diaryHanded(ServerPlayer player, int band, int count) {
        safely("diary_handed", () -> record(player, "diary_handed", Map.of("band", band, "count", count)));
    }

    /** Gear was locked in at the librarian: which item and the emerald price. */
    static void lockIn(ServerPlayer player, String item, int cost) {
        safely("lock_in", () -> record(player, "lock_in", Map.of("item", item, "cost", cost)));
    }

    /** A Store sale (PD-142): what was bought, what it cost, in which item, and from whom. */
    static void shopPurchase(ServerPlayer player, net.minecraft.world.item.Item item, String name, int price,
                             net.minecraft.world.item.Item currency, String vendor) {
        safely("shop_purchase", () -> record(player, "shop_purchase", Map.of(
                "item", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString(),
                "name", name,
                "price", price,
                "currency", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(currency).toString(),
                "vendor", vendor)));
    }

    /** A dead-end fountain was drunk: which boon it held. */
    static void fountain(ServerPlayer player, String boon) {
        safely("fountain", () -> record(player, "fountain", Map.of("boon", boon)));
    }

    /** Emeralds granted as a dungeon reward (J1): how many and from what. */
    static void emeralds(ServerPlayer player, int amount, String source) {
        safely("emeralds", () -> record(player, "emeralds", Map.of("amount", amount, "source", source)));
    }

    /** One salvage at the bench: what went in and what came out (A3, the surplus sink). */
    static void salvage(ServerPlayer player, Map<String, ?> extras) {
        safely("salvage", () -> record(player, "salvage", extras));
    }

    /**
     * The owner's reconnect grace started, lifted or ran out: written for the
     * owner (online or not) and every member present.
     */
    static void ownerHold(MinecraftServer s, InstanceRecord record, String state) {
        safely("owner_hold", () -> {
            Map<String, Object> extras = Map.of("state", state);
            ServerPlayer owner = s.getPlayerList().getPlayer(record.owner);
            if (owner != null) {
                record(owner.getUUID(), owner.getName().getString(), record, "owner_hold", extras);
            } else {
                String name = s.services().nameToIdCache().get(record.owner).map(p -> p.name()).orElse("");
                record(record.owner, name, record, "owner_hold", extras);
            }
            for (UUID member : record.members.keySet()) {
                ServerPlayer player = s.getPlayerList().getPlayer(member);
                if (player != null && !member.equals(record.owner)) {
                    record(player, record, "owner_hold", extras);
                }
            }
        });
    }

    /** {@code /dungeon report <text>}. */
    static void report(ServerPlayer player, String text) {
        safely("report", () -> {
            List<String> recent = new ArrayList<>();
            for (JsonObject event : recent(player.getUUID(), 10)) {
                recent.add(event.get("ev").getAsString());
            }
            BlockPos pos = player.blockPosition();
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("text", text);
            extras.put("pos", pos.getX() + "," + pos.getY() + "," + pos.getZ());
            extras.put("room", FloorRooms.roomAt(InstanceRegistry.byMember.get(player.getUUID()), pos));
            extras.put("recent", recent);
            record(player, "report", extras);
        });
    }

    /** Something the player said to Lemon, once it has been answered (or not). */
    static void lemonAsk(ServerPlayer player, String text, String answeredBy, int hintTier, long waitSeconds) {
        safely("lemon_ask", () -> {
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("text", text);
            extras.put("room", FloorRooms.roomAt(InstanceRegistry.byMember.get(player.getUUID()),
                    player.blockPosition()));
            extras.put("answered_by", answeredBy);
            extras.put("hint_tier", hintTier);
            extras.put("wait_s", waitSeconds);
            record(player, "lemon_ask", extras);
        });
    }

    /**
     * A party member said {@code text} in chat and {@code player} is in their party
     * (owner decision, 2026-10-03: Lemon takes in what the whole party says, tagged
     * with who said it). Written for the listener, so their {@code context} shows
     * the party's side of the conversation; the speaker's own line is a
     * {@code lemon_ask} or plain chat already.
     */
    static void lemonHeard(ServerPlayer player, String from, String text) {
        safely("lemon_heard", () -> {
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("from", from);
            extras.put("text", text);
            record(player, "lemon_heard", extras);
        });
    }

    /** The player answered a question the interviewer asked through Lemon. */
    static void lemonAnswer(ServerPlayer player, String question, String text) {
        safely("lemon_answer", () -> {
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("question", question);
            extras.put("text", text);
            record(player, "lemon_answer", extras);
        });
    }

    // ---- floor tallies -----------------------------------------------------------

    private static FloorTally tally(ServerPlayer player, InstanceRecord record) {
        FloorTally tally = TALLIES.get(player.getUUID());
        if (tally == null || tally.floorStart != record.floor.startedAtTick) {
            tally = new FloorTally();
            tally.floorStart = record.floor.startedAtTick;
            tally.damageAtStart = inventoryDamage(player);
            TALLIES.put(player.getUUID(), tally);
        }
        return tally;
    }

    /** Total damage on every damageable stack the player carries or wears. */
    private static long inventoryDamage(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        long total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.isDamageableItem()) {
                total += stack.getDamageValue();
            }
        }
        return total;
    }

    // ---- errors ------------------------------------------------------------------

    /** Writes queued errors for every player standing in an instance right now. */
    private static void drainErrors(MinecraftServer s) {
        if (PENDING_ERRORS.isEmpty()) {
            return;
        }
        String message;
        while ((message = PENDING_ERRORS.poll()) != null) {
            String line = message;
            safely("error", () -> {
                for (UUID member : new ArrayList<>(InstanceRegistry.byMember.keySet())) {
                    ServerPlayer player = s.getPlayerList().getPlayer(member);
                    if (player != null) {
                        record(player, "error", Map.of("message", line));
                    }
                }
            });
        }
    }

    // ---- reading back ------------------------------------------------------------

    /**
     * The last {@code n} events for this player, oldest first, from today's
     * file and yesterday's (a session over midnight). Torn lines are skipped.
     */
    static List<JsonObject> recent(UUID player, int n) {
        MinecraftServer s = server;
        if (s == null) {
            return List.of();
        }
        try {
            LocalDate today = LocalDate.now();
            List<String> lines = new ArrayList<>();
            for (LocalDate day : List.of(today.minusDays(1), today)) {
                Path file = root(s).resolve(day.toString()).resolve(player + ".jsonl");
                if (Files.isRegularFile(file)) {
                    lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
                }
            }
            return JournalFormat.tail(lines, n);
        } catch (IOException | RuntimeException e) {
            PocketDungeonsMod.LOG.warn("Playtest journal: could not read back events for {} ({})", player, e.toString());
            return List.of();
        }
    }

    /**
     * The player's latest {@code bank} event from today's file and yesterday's, or
     * {@code null}. {@link #recent} holds only the last few events, so a bank row
     * scrolls out before an operator can answer a question about it (PD-148).
     */
    static JsonObject lastBank(UUID player) {
        MinecraftServer s = server;
        if (s == null) {
            return null;
        }
        try {
            LocalDate today = LocalDate.now();
            for (LocalDate day : List.of(today, today.minusDays(1))) {
                Path file = root(s).resolve(day.toString()).resolve(player + ".jsonl");
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = lines.size() - 1; i >= 0; i--) {
                    JsonObject event = JournalFormat.parse(lines.get(i));
                    if (event != null && event.has("ev") && "bank".equals(event.get("ev").getAsString())) {
                        return event;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            PocketDungeonsMod.LOG.warn("Playtest journal: could not read back the last bank for {} ({})", player, e.toString());
        }
        return null;
    }

    // ---- writing -----------------------------------------------------------------

    static void record(ServerPlayer player, String ev, Map<String, ?> extras) {
        record(player.getUUID(), player.getName().getString(), null, ev, extras);
    }

    /** Writes one line about {@code instance}, whether or not the player is registered as its member. */
    private static void record(ServerPlayer player, InstanceRecord instance, String ev, Map<String, ?> extras) {
        record(player.getUUID(), player.getName().getString(), instance, ev, extras);
    }

    /** Writes {@code ev} for every member of {@code record} who is online. */
    private static void forMembers(MinecraftServer s, InstanceRecord record, String ev, Map<String, ?> extras) {
        for (UUID member : record.members.keySet()) {
            ServerPlayer player = s.getPlayerList().getPlayer(member);
            if (player != null) {
                record(player, record, ev, extras);
            }
        }
    }

    /**
     * Writes one line. {@code instance} overrides the membership lookup for a
     * player who has already been detached (a leave, an absent owner).
     */
    static void record(UUID player, String name, InstanceRecord instance, String ev, Map<String, ?> extras) {
        MinecraftServer s = server;
        if (s == null || !PocketDungeonsConfig.playtestJournal()) {
            return;
        }
        InstanceRecord record = instance != null ? instance : InstanceRegistry.byMember.get(player);
        String line = JournalFormat.line(ev, common(player, name, record), extras);
        write(s, player, line);
    }

    private static JournalFormat.Common common(UUID player, String name, InstanceRecord record) {
        String t = JournalFormat.timestamp(Instant.now());
        if (record == null) {
            return new JournalFormat.Common(t, player.toString(), name, -1, "NONE", -1, "",
                    1 + PartyService.partyCompanions(player).size());
        }
        return new JournalFormat.Common(t, player.toString(), name, record.slot, record.phase.name(),
                floorOf(record), record.floor.theme == null ? "" : record.floor.theme,
                Math.max(1, record.members.size()));
    }

    /**
     * The floor a line is about: the floor in play while it is active (1-based),
     * the floor just cleared between floors, 0 at home before a door.
     */
    static int floorOf(InstanceRecord record) {
        return record.phase == RunSession.Phase.ACTIVE
                ? record.interval.floorIndex + 1 : record.interval.floorIndex;
    }

    private static synchronized void write(MinecraftServer s, UUID player, String line) {
        try {
            Path dir = root(s).resolve(LocalDate.now().toString());
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(player + ".jsonl"), line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException e) {
            PocketDungeonsMod.LOG.warn("Playtest journal: could not write an event for {} ({})", player, e.toString());
        }
    }

    private static Path root(MinecraftServer s) {
        return s.getWorldPath(LevelResource.ROOT).resolve(DIR).normalize();
    }

    /** Deletes date folders past the retention window. */
    private static void applyRetention(MinecraftServer s) throws IOException {
        Path root = root(s);
        if (!Files.isDirectory(root)) {
            return;
        }
        LocalDate today = LocalDate.now();
        List<Path> expired = new ArrayList<>();
        try (DirectoryStream<Path> folders = Files.newDirectoryStream(root)) {
            for (Path folder : folders) {
                if (Files.isDirectory(folder) && JournalFormat.expired(folder.getFileName().toString(), today)) {
                    expired.add(folder);
                }
            }
        }
        for (Path folder : expired) {
            try (Stream<Path> walk = Files.walk(folder)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        if (!expired.isEmpty()) {
            PocketDungeonsMod.LOG.info("Playtest journal: removed {} day folder(s) older than {} days",
                    expired.size(), JournalFormat.RETENTION_DAYS);
        }
    }

    // ---- helpers -----------------------------------------------------------------

    private static boolean inDungeon(ServerPlayer player) {
        return player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL);
    }

    private static List<String> sorted(Collection<String> values) {
        List<String> out = new ArrayList<>(values == null ? List.of() : values);
        out.sort(null);
        return out;
    }

    private interface Body {
        void run() throws Exception;
    }

    private static void safely(String what, Body body) {
        try {
            body.run();
        } catch (Exception | LinkageError e) {
            PocketDungeonsMod.LOG.warn("Playtest journal: {} failed ({})", what, e.toString());
        }
    }
}
