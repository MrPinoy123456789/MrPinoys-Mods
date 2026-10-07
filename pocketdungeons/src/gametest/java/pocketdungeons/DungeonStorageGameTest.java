package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Design 2026-10-06-1 item 5: Dungeon Storage is a 27 slot container backed by {@link DungeonLog}.
 * It persists on every change, survives a teardown, takes what an old per-run storage still held,
 * and an omen death closes an open menu before rolling it back.
 */
public final class DungeonStorageGameTest {

    private static final int SLOT = 9981;

    private static InstanceRecord floorRecord(GameTestHelper helper, MinecraftServer server, UUID owner) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        InstanceLayout layout = new InstanceLayout(origin, geometry, origin.offset(3, 1, 3), 0.0f,
                origin.offset(8, 1, 8), geometry.bounds(), 1L, 4, 4, 1, true, Set.of(), 3, origin,
                0, 0, Set.of(), null, Set.of());
        InstanceRecord record = new InstanceRecord(SLOT, origin, server.getTickCount(), layout,
                Set.of(), owner, false);
        record.floor.theme = "pocketdungeons:test_storage";
        // A trip in a dungeon: the floor loop the storage is gated on.
        record.interval.dungeonId = "pocketdungeons:mineshaft";
        record.interval.nodeId = "adit";
        record.members.put(owner, new ReturnPoint(net.minecraft.world.level.Level.OVERWORLD,
                net.minecraft.world.phys.Vec3.ZERO, 0.0f, 0.0f));
        return record;
    }

    private static void register(InstanceRecord record, ServerPlayer player) {
        InstanceRegistry.bySlot.put(SLOT, record);
        InstanceRegistry.usedSlots.add(SLOT);
        InstanceRegistry.byMember.put(player.getUUID(), record);
    }

    private static void unregister(ServerPlayer player) {
        InstanceRegistry.bySlot.remove(SLOT);
        InstanceRegistry.usedSlots.remove(SLOT);
        InstanceRegistry.byMember.remove(player.getUUID());
    }

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        return player;
    }

    @GameTest(maxTicks = 40)
    public void storageIsSavedOnEveryChangeAndSurvivesATeardown(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        MinecraftServer server = player.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        UUID id = player.getUUID();
        InstanceRecord record = floorRecord(helper, server, id);
        register(record, player);
        try {
            helper.assertTrue(RunStorage.onUse(player, RunStorage.stationState(Direction.NORTH), true),
                    "the storage block opens the storage inside a run");
            helper.assertTrue(player.containerMenu instanceof ChestMenu, "a chest menu is open");
            player.containerMenu.getSlot(0).set(new ItemStack(Items.DIAMOND, 5));
            helper.assertTrue(log.storageOf(id).get(0).is(Items.DIAMOND) && log.storageOf(id).get(0).getCount() == 5,
                    "the item reached the log the moment it was placed");
            player.closeContainer();

            // The old per-run storage a live run still held moves in, never into the pack.
            SimpleContainer legacy = new SimpleContainer(RunStorage.SLOTS);
            legacy.setItem(0, new ItemStack(Items.COBBLESTONE, 12));
            record.runStorage.put(id, legacy);

            // A teardown: the room is closed and the storage is where it was.
            record.visitInstance = true;
            ServerLevel level = helper.getLevel();
            InstanceTeardown.levelForTesting = level;
            try {
                InstanceTeardown.purge(server, record, "storage test");
                InstanceTeardown.drainClears(server);
            } finally {
                InstanceTeardown.levelForTesting = null;
            }
            List<ItemStack> stored = log.storageOf(id);
            helper.assertTrue(stored.get(0).is(Items.DIAMOND) && stored.get(0).getCount() == 5,
                    "the stored item survived the teardown in its slot");
            helper.assertTrue(stored.stream().anyMatch(s -> s.is(Items.COBBLESTONE) && s.getCount() == 12),
                    "the old storage's stack moved into the new one");
            helper.assertTrue(log.orphanOf(id).items().stream().noneMatch(s -> s.is(Items.COBBLESTONE)
                            || s.is(Items.DIAMOND)),
                    "none of it went to the dungeon pack");

            // The next open finds it.
            InstanceRecord next = floorRecord(helper, server, id);
            register(next, player);
            helper.assertTrue(RunStorage.onUse(player, RunStorage.stationState(Direction.NORTH), true),
                    "it opens again on the next run");
            helper.assertTrue(player.containerMenu.getSlot(0).getItem().is(Items.DIAMOND),
                    "the next open shows the stored item");
            player.closeContainer();
        } finally {
            player.closeContainer();
            log.setStorage(id, List.of());
            unregister(player);
        }
        helper.succeed();
    }

    /** The saved form keeps every slot where it was, and an old save's retired sidecar still loads. */
    @GameTest(maxTicks = 20)
    public void storageRoundTripsThroughTheLogCodec(GameTestHelper helper) {
        UUID id = UUID.randomUUID();
        List<ItemStack> slots = new ArrayList<>();
        for (int i = 0; i < RunStorage.SLOTS; i++) {
            slots.add(ItemStack.EMPTY);
        }
        slots.set(3, new ItemStack(Items.COBBLESTONE, 64));
        slots.set(20, new ItemStack(Items.TORCH, 7));
        DungeonLog log = new DungeonLog();
        log.setStorage(id, slots);
        slots.set(3, ItemStack.EMPTY);
        helper.assertTrue(log.storageOf(id).get(3).getCount() == 64, "the log keeps its own copy of the stacks");

        com.google.gson.JsonElement json = DungeonLog.CODEC.encodeStart(
                com.mojang.serialization.JsonOps.INSTANCE, log).result().orElseThrow();
        helper.assertTrue(!json.getAsJsonObject().has("storage_returns")
                        || json.getAsJsonObject().getAsJsonArray("storage_returns").isEmpty(),
                "the retired PD-150 sidecar is never written");
        DungeonLog back = DungeonLog.CODEC.decode(com.mojang.serialization.JsonOps.INSTANCE, json)
                .result().orElseThrow().getFirst();
        List<ItemStack> read = back.storageOf(id);
        helper.assertTrue(read.size() == RunStorage.SLOTS, "all 27 slots come back: " + read.size());
        helper.assertTrue(read.get(3).is(Items.COBBLESTONE) && read.get(3).getCount() == 64, "slot 3 is kept");
        helper.assertTrue(read.get(20).is(Items.TORCH) && read.get(20).getCount() == 7, "slot 20 is kept");
        helper.assertTrue(read.get(4).isEmpty(), "an empty slot stays empty, so the layout holds");

        com.google.gson.JsonObject old = new com.google.gson.JsonObject();
        old.add("players", new com.google.gson.JsonArray());
        com.google.gson.JsonArray returns = new com.google.gson.JsonArray();
        com.google.gson.JsonObject one = new com.google.gson.JsonObject();
        one.addProperty("player", id.toString());
        one.addProperty("stacks", 4);
        returns.add(one);
        old.add("storage_returns", returns);
        DungeonLog legacy = DungeonLog.CODEC.decode(com.mojang.serialization.JsonOps.INSTANCE, old)
                .result().orElseThrow().getFirst();
        helper.assertTrue(legacy.storageOf(id).isEmpty(), "an old save loads with an empty storage");

        back.setStorage(id, List.of(ItemStack.EMPTY));
        helper.assertTrue(back.storageOf(id).isEmpty(), "an all-empty storage leaves no record");
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void anOmenDeathClosesTheMenuThenRollsTheStorageBack(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        MinecraftServer server = player.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        UUID id = player.getUUID();
        InstanceRecord record = floorRecord(helper, server, id);
        register(record, player);
        try {
            List<ItemStack> before = new ArrayList<>();
            for (int i = 0; i < RunStorage.SLOTS; i++) {
                before.add(ItemStack.EMPTY);
            }
            before.set(1, new ItemStack(Items.IRON_INGOT, 9));
            log.setStorage(id, before);

            helper.assertTrue(RunStorage.onUse(player, RunStorage.stationState(Direction.NORTH), true), "it opens");
            helper.assertTrue(record.interval.storageSnapshot.containsKey(id), "the first open took the interval snapshot");
            // Unbanked loot stashed during the interval.
            player.containerMenu.getSlot(2).set(new ItemStack(Items.DIAMOND, 7));
            helper.assertTrue(log.storageOf(id).get(2).is(Items.DIAMOND), "the loot is in the storage");

            RunStorage.rollBackToInterval(server, record);
            helper.assertTrue(!(player.containerMenu instanceof ChestMenu), "the open menu was closed first");
            List<ItemStack> after = log.storageOf(id);
            helper.assertTrue(after.get(1).is(Items.IRON_INGOT) && after.get(1).getCount() == 9,
                    "what was stored before the interval is safe");
            helper.assertTrue(after.get(2).isEmpty(), "what was stashed during the interval is gone");
            // A stale menu closing late must not write the old stacks back (no duplication).
            helper.assertTrue(log.storageOf(id).stream().noneMatch(s -> s.is(Items.DIAMOND)),
                    "no diamond survived the rollback");
        } finally {
            player.closeContainer();
            log.setStorage(id, List.of());
            unregister(player);
        }
        helper.succeed();
    }
}
