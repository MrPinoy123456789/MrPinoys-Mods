package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Playtest 2026-10-04-1 fix batch. Each scenario stamps a room the way the
 * layout stamper does (template, then {@link RoomContent#apply}) and reads the
 * blocks back.
 */
public final class PlaytestFixGameTest {

    private static final int CELL = RoomGeometry.CELL;

    /** Stamps {@code room} at {@code q} quarter turns on its own scratch cell and runs its content. */
    private static BlockPos stampRoom(ServerLevel level, String room, int q, int slot, long seed) {
        RoomManifest.Entry entry = RoomManifest.current().byName(room);
        BlockPos origin = new BlockPos(24576 + q * 2 * CELL, 120, 24576 + slot * 2 * CELL);
        level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, true);
        java.util.List<BlockPos> spawns = TemplateStamper.place(level, level.getServer().getStructureManager(),
                origin, Identifier.parse(entry.meta.template), q, seed);
        RoomContent.apply(level, origin, RoleIds.CORRIDOR, 1, DifficultyProfile.of(3, 1), spawns, seed,
                Set.of(), null, null, null, false, false, entry.meta.content);
        return origin;
    }

    private static void release(ServerLevel level, BlockPos origin) {
        Locks.clear(origin);
        level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, false);
    }

    /**
     * PD-144: sorting_floor arrived solved (a filter hopper pre-loaded with
     * sticks fed the return chest at stamp), with water spilling past the
     * channel. At every approach rotation it must hold one closed iron door
     * set, an empty return chest and hopper, and water that touches nothing
     * but water, fences, walls and the hopper.
     */
    @GameTest(maxTicks = 200)
    public void sortingFloorIsAClosedGateWithConfinedWater(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<String> failures = new ArrayList<>();
        for (int q = 0; q < 4; q++) {
            BlockPos origin = stampRoom(level, "sorting_floor", q, 0, 1L);
            try {
                int doors = 0;
                int open = 0;
                int water = 0;
                for (int x = 0; x < CELL; x++) {
                    for (int z = 0; z < CELL; z++) {
                        for (int y = 1; y <= RoomGeometry.CEILING_Y; y++) {
                            BlockPos pos = origin.offset(x, y, z);
                            BlockState state = level.getBlockState(pos);
                            if (state.is(Blocks.IRON_DOOR)
                                    && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                                doors++;
                                if (state.getValue(DoorBlock.OPEN)) {
                                    open++;
                                }
                            }
                            BlockEntity be = level.getBlockEntity(pos);
                            if (be instanceof Container container && !container.isEmpty()
                                    && !state.is(Blocks.DECORATED_POT)) {
                                failures.add("q" + q + " " + state.getBlock() + " at " + x + "," + y + "," + z
                                        + " holds items at stamp");
                            }
                            if (y == 1 && state.is(Blocks.WATER)) {
                                water++;
                                for (BlockPos side : new BlockPos[]{pos.north(), pos.south(), pos.east(), pos.west()}) {
                                    BlockState next = level.getBlockState(side);
                                    if (next.isAir()) {
                                        failures.add("q" + q + " water at " + x + "," + z
                                                + " is open to air at " + (side.getX() - origin.getX()) + ","
                                                + (side.getZ() - origin.getZ()));
                                    }
                                }
                            }
                        }
                    }
                }
                if (doors != 2) {
                    failures.add("q" + q + " has " + doors + " iron door blocks, wanted one pair (2)");
                }
                if (open != 0) {
                    failures.add("q" + q + " has " + open + " open iron door blocks at stamp");
                }
                if (water == 0) {
                    failures.add("q" + q + " has no water");
                }
            } finally {
                release(level, origin);
            }
        }
        helper.assertTrue(failures.isEmpty(), "sorting_floor: " + failures);
        helper.succeed();
    }

    /**
     * PD-144: the connector pass stacked an iron door set on the edge of a
     * gated room that already stands its own, which is how the player met two
     * sets, one open and one shut.
     */
    @GameTest(maxTicks = 50)
    public void anIronDoorConnectorYieldsToAGatedRoom(GameTestHelper helper) {
        RoomManifest.Entry gated = RoomManifest.current().byName("sorting_floor");
        RoomManifest.Entry plain = null;
        for (RoomManifest.Entry entry : RoomManifest.current().rooms()) {
            if (!DungeonRoomMeta.ACCESS_GATED.equals(entry.meta.access)) {
                plain = entry;
                break;
            }
        }
        helper.assertTrue(gated != null && plain != null, "the manifest has a gated and an open room");
        helper.assertValueEqual(ConnectorStamper.effectiveType(ConnectorType.IRON_DOOR, gated, plain),
                ConnectorType.DOOR_WIDE, "iron door beside a gated room");
        helper.assertValueEqual(ConnectorStamper.effectiveType(ConnectorType.IRON_DOOR, plain, gated),
                ConnectorType.DOOR_WIDE, "iron door beside a gated room, other side");
        helper.assertValueEqual(ConnectorStamper.effectiveType(ConnectorType.IRON_DOOR, plain, plain),
                ConnectorType.IRON_DOOR, "iron door between open rooms");
        helper.assertValueEqual(ConnectorStamper.effectiveType(ConnectorType.ARCH, gated, plain),
                ConnectorType.ARCH, "other connectors are untouched");
        helper.succeed();
    }

    // ---- PD-143: the Altar -------------------------------------------------

    private static net.minecraft.world.phys.AABB cellBox(BlockPos origin) {
        return new net.minecraft.world.phys.AABB(origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + CELL, origin.getY() + 8, origin.getZ() + CELL);
    }

    private static int keysIn(ServerLevel level, BlockPos origin) {
        int keys = 0;
        for (net.minecraft.world.entity.item.ItemEntity item : level.getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class, cellBox(origin))) {
            if (item.getItem().is(net.minecraft.world.item.Items.TRIAL_KEY)) {
                keys += item.getItem().getCount();
            }
        }
        return keys;
    }

    private static BlockPos find(ServerLevel level, BlockPos origin, net.minecraft.world.level.block.Block block, int y) {
        for (int x = 0; x < CELL; x++) {
            for (int z = 0; z < CELL; z++) {
                if (level.getBlockState(origin.offset(x, y, z)).is(block)) {
                    return origin.offset(x, y, z);
                }
            }
        }
        return null;
    }

    /**
     * PD-143: the Altar's comparator, repeater and dust stand on something, the
     * dropper starts empty, and the trial key comes out only for a food
     * offering, once.
     */
    @GameTest(maxTicks = 300)
    public void theAltarPaysOneKeyForAFoodOfferingOnly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int q = 0; q < 4; q++) {
            BlockPos chunkAt = new BlockPos(24576 + q * 2 * CELL, 120, 24576 + 1 * 2 * CELL);
            level.setChunkForced(chunkAt.getX() >> 4, chunkAt.getZ() >> 4, true);
        }
        // Entities (the key) need the chunks fully loaded before the stamp.
        helper.runAfterDelay(20, () -> altarBody(helper, level));
    }

    private void altarBody(GameTestHelper helper, ServerLevel level) {
        List<String> failures = new ArrayList<>();
        BlockPos[] origins = new BlockPos[4];
        for (int q = 0; q < 4; q++) {
            // The test world persists between runs: clear keys a past run left on the floor.
            for (net.minecraft.world.entity.item.ItemEntity old : level.getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class,
                    cellBox(new BlockPos(24576 + q * 2 * CELL, 120, 24576 + 1 * 2 * CELL)))) {
                old.discard();
            }
            origins[q] = stampRoom(level, "the_altar", q, 1, 1L);
            for (int x = 0; x < CELL; x++) {
                for (int z = 0; z < CELL; z++) {
                    BlockPos pos = origins[q].offset(x, 2, z);
                    BlockState state = level.getBlockState(pos);
                    if ((state.is(Blocks.COMPARATOR) || state.is(Blocks.REPEATER) || state.is(Blocks.REDSTONE_WIRE))
                            && !state.canSurvive(level, pos)) {
                        failures.add("q" + q + " " + state.getBlock() + " at " + x + "," + z + " has no support");
                    }
                }
            }
            BlockPos dropper = find(level, origins[q], Blocks.DROPPER, 2);
            if (!(level.getBlockEntity(dropper) instanceof Container container) || !container.isEmpty()) {
                failures.add("q" + q + " dropper is loaded at stamp");
            }
        }
        helper.assertTrue(failures.isEmpty(), "the_altar: " + failures);
        // q0 bread, q1 a stick, q2 nothing, q3 bread.
        offer(level, origins[0], new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD));
        offer(level, origins[1], new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK));
        offer(level, origins[3], new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD));
        helper.runAfterDelay(35, () -> {
            int[] want = {1, 0, 0, 1};
            for (int q = 0; q < 4; q++) {
                int keys = keysIn(level, origins[q]);
                if (keys != want[q]) {
                    failures.add("q" + q + " has " + keys + " trial keys, wanted " + want[q]);
                }
            }
            // A second offering at an altar that has paid pays nothing more.
            offer(level, origins[0], new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD));
            helper.runAfterDelay(35, () -> {
                if (keysIn(level, origins[0]) != 1) {
                    failures.add("q0 paid again for a second offering");
                }
                for (BlockPos origin : origins) {
                    AltarOffering.clear(origin);
                    for (net.minecraft.world.entity.item.ItemEntity item : level.getEntitiesOfClass(
                            net.minecraft.world.entity.item.ItemEntity.class, cellBox(origin))) {
                        item.discard();
                    }
                    release(level, origin);
                }
                helper.assertTrue(failures.isEmpty(), "the_altar: " + failures);
                helper.succeed();
            });
        });
    }

    private static void offer(ServerLevel level, BlockPos origin, net.minecraft.world.item.ItemStack stack) {
        BlockPos hopper = find(level, origin, Blocks.HOPPER, 2);
        if (level.getBlockEntity(hopper) instanceof Container container) {
            container.setItem(0, stack);
        }
    }

    // ---- PD-140, PD-141: one-door loot rooms and the Store ------------------

    /**
     * PD-140: a one-door loot room can be walked into. The spur door is on the
     * north wall at rotation 0, so at q quarter turns it is on the wall q
     * steps clockwise from north; the doorway lane (both columns, three
     * blocks in, two tall) must be clear. The Store built its counter and a
     * row of barrels along the north wall at stamp time, in world
     * coordinates, which walled the doorway of every room whose door was
     * there.
     */
    @GameTest(maxTicks = 200)
    public void oneDoorLootRoomsAreOpenAtTheirDoor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<String> failures = new ArrayList<>();
        String[] rooms = {"the_store", "the_altar", "barred_vault", "ominous_bargain"};
        for (int r = 0; r < rooms.length; r++) {
            for (int q = 0; q < 4; q++) {
                BlockPos origin = stampRoom(level, rooms[r], q, 2 + r, 1L);
                try {
                    for (int column = 7; column <= 8; column++) {
                        for (int depth = 0; depth <= 3; depth++) {
                            int along = depth;
                            int across = column;
                            int x;
                            int z;
                            switch (q) {
                                case 0 -> { x = across; z = along; }
                                case 1 -> { x = CELL - 1 - along; z = across; }
                                case 2 -> { x = across; z = CELL - 1 - along; }
                                default -> { x = along; z = across; }
                            }
                            for (int y = 1; y <= 2; y++) {
                                BlockPos pos = origin.offset(x, y, z);
                                BlockState state = level.getBlockState(pos);
                                if (!state.getCollisionShape(level, pos).isEmpty()
                                        && !state.is(Blocks.IRON_DOOR)) {
                                    failures.add(rooms[r] + " q" + q + " door lane blocked by "
                                            + state.getBlock() + " at depth " + depth + " column " + column);
                                }
                            }
                        }
                    }
                } finally {
                    release(level, origin);
                }
            }
        }
        helper.assertTrue(failures.isEmpty(), "one-door loot rooms: " + failures);
        helper.succeed();
    }

    /**
     * PD-141: the vendor stays in the shop. A free villager wandered into the
     * next room; the shopkeeper now stands where it was spawned.
     */
    @GameTest(maxTicks = 200)
    public void theStoreVendorStaysPut(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos chunkAt = new BlockPos(24576 + 2 * 2 * CELL, 120, 24576 + 6 * 2 * CELL);
        level.setChunkForced(chunkAt.getX() >> 4, chunkAt.getZ() >> 4, true);
        // Entities need the chunk fully loaded before they can be added.
        helper.runAfterDelay(20, () -> {
            // The test world persists between runs: clear vendors a past run left behind.
            for (net.minecraft.world.entity.npc.villager.Villager old : level.getEntitiesOfClass(
                    net.minecraft.world.entity.npc.villager.Villager.class, cellBox(chunkAt))) {
                old.discard();
            }
            BlockPos origin = stampRoom(level, "the_store", 2, 6, 1L);
            java.util.List<net.minecraft.world.entity.npc.villager.Villager> vendors = level.getEntitiesOfClass(
                    net.minecraft.world.entity.npc.villager.Villager.class, cellBox(origin),
                    v -> v.entityTags().contains(StoreNPC.STORE_TAG));
            helper.assertTrue(vendors.size() == 1, "one vendor stands in the store, found " + vendors.size());
            net.minecraft.world.entity.npc.villager.Villager vendor = vendors.get(0);
            net.minecraft.world.phys.Vec3 spawned = vendor.position();
            helper.assertTrue(vendor.isNoAi(), "the vendor has no wander AI");
            helper.runAfterDelay(60, () -> {
                double moved = vendor.position().distanceTo(spawned);
                vendor.discard();
                release(level, origin);
                helper.assertTrue(moved < 0.5, "the vendor moved " + moved + " blocks");
                helper.succeed();
            });
        });
    }

    // ---- PD-145: kennel_crossing on a dark floor ----------------------------

    /**
     * PD-145: on an ender_archive floor the kennel's grass pen spawned no
     * wolves. A trial spawner runs the mob's own placement rules, and a wolf
     * needs grass underfoot and light above 8. For every theme, once the
     * light engine has settled, a wolf must be allowed to spawn on at least
     * three spots around the spawner.
     */
    @GameTest(maxTicks = 300)
    public void theKennelPenSpawnsWolvesOnEveryTheme(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<ThemeManifest.Entry> themes = ThemeManifest.current().themes();
        BlockPos[] origins = new BlockPos[themes.size()];
        BlockPos[] anchors = new BlockPos[themes.size()];
        for (int i = 0; i < themes.size(); i++) {
            BlockPos origin = new BlockPos(24576, 120, 24576 + (40 + i) * 2 * CELL);
            origins[i] = origin;
            level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, true);
            String processors = themes.get(i).meta().processors;
            java.util.List<BlockPos> spawns = TemplateStamper.place(level,
                    level.getServer().getStructureManager(), origin,
                    Identifier.parse("pocketdungeons:rooms/kennel_crossing"), 0, 1L,
                    processors == null ? null : Identifier.parse(processors));
            anchors[i] = Situations.apply(level, origin, "encounter", 1, DifficultyProfile.of(5, 1), spawns,
                    1L, Set.of(), "", themes.get(i).id(), false, "kennel_crossing");
        }
        helper.runAfterDelay(150, () -> {
            List<String> failures = new ArrayList<>();
            net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(1L);
            for (int i = 0; i < themes.size(); i++) {
                int spots = 0;
                int darkest = 15;
                if (anchors[i] == null) {
                    failures.add(themes.get(i).id() + " placed no spawner");
                    continue;
                }
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos pos = anchors[i].offset(dx, 0, dz);
                        if (!level.getBlockState(pos).isAir()) {
                            continue;
                        }
                        darkest = Math.min(darkest, level.getRawBrightness(pos, 0));
                        if (net.minecraft.world.entity.SpawnPlacements.checkSpawnRules(
                                net.minecraft.world.entity.EntityTypes.WOLF, level,
                                net.minecraft.world.entity.EntitySpawnReason.TRIAL_SPAWNER, pos, random)) {
                            spots++;
                        }
                    }
                }
                if (spots < 3) {
                    failures.add(themes.get(i).id() + " has " + spots + " wolf spots (darkest light " + darkest + ")");
                }
            }
            for (BlockPos origin : origins) {
                release(level, origin);
            }
            helper.assertTrue(failures.isEmpty(), "kennel pens: " + failures);
            helper.succeed();
        });
    }

    // ---- PD-146: gear above the durability cap ------------------------------

    /**
     * PD-146: a reward handed over directly (the Store's sale, the gamble, a
     * salvage grant) never passed the item-entity or loot-table caps, so a
     * bought iron sword kept its vanilla 250 and an iron helmet 165. Every
     * stack {@link Payout#deliver} hands over is capped.
     */
    @GameTest(maxTicks = 50)
    public void handedOverGearIsDurabilityCapped(GameTestHelper helper) {
        net.minecraft.server.level.ServerPlayer player = helper.makeMockServerPlayerInLevel();
        net.minecraft.world.item.Item[] gear = {
                net.minecraft.world.item.Items.IRON_SWORD, net.minecraft.world.item.Items.IRON_HELMET,
                net.minecraft.world.item.Items.DIAMOND_CHESTPLATE, net.minecraft.world.item.Items.BOW,
                net.minecraft.world.item.Items.STONE_PICKAXE};
        for (net.minecraft.world.item.Item item : gear) {
            Payout.deliver(player, new net.minecraft.world.item.ItemStack(item));
        }
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack stack = player.getInventory().getItem(i);
            int cap = stack.isEmpty() ? -1 : DungeonTools.durabilityCap(stack.getItem());
            if (cap > 0 && stack.getMaxDamage() > cap) {
                failures.add(stack.getItem() + " has " + stack.getMaxDamage() + ", cap " + cap);
            }
        }
        int seen = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (!player.getInventory().getItem(i).isEmpty()) {
                seen++;
            }
        }
        helper.assertTrue(seen >= gear.length, "the player holds the delivered gear (" + seen + ")");
        helper.assertTrue(failures.isEmpty(), "uncapped gear: " + failures);
        helper.succeed();
    }

    // ---- PD-147: collapsing_bridge -------------------------------------------

    /** A template-local (x, z) after {@code q} clockwise quarter turns, as TemplateStamper turns a room. */
    private static BlockPos turned(BlockPos origin, int x, int y, int z, int q) {
        int m = CELL - 1;
        return switch (q) {
            case 1 -> origin.offset(m - z, y, x);
            case 2 -> origin.offset(m - x, y, m - z);
            case 3 -> origin.offset(z, y, m - x);
            default -> origin.offset(x, y, z);
        };
    }

    private static List<BlockPos> bridgePlanks(BlockPos origin, int q) {
        List<BlockPos> planks = new ArrayList<>();
        for (int x : new int[]{3, 6, 9, 12}) {
            for (int z : new int[]{7, 8}) {
                planks.add(turned(origin, x, 1, z, q));
            }
        }
        return planks;
    }

    /**
     * PD-147: the bridge is whole when the party finds it, stays whole, and
     * cannot burn. It stood in the lava room as oak, which lava lights; and
     * its unpowered pistons retract on any neighbour update, which dropped
     * planks before anyone stood on them.
     */
    @GameTest(maxTicks = 300)
    public void theCollapsingBridgeStandsAndDoesNotBurn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos[] origins = new BlockPos[4];
        for (int q = 0; q < 4; q++) {
            origins[q] = new BlockPos(24576 + q * 2 * CELL, 120, 24576 + 30 * 2 * CELL);
            level.setChunkForced(origins[q].getX() >> 4, origins[q].getZ() >> 4, true);
            TemplateStamper.place(level, level.getServer().getStructureManager(), origins[q],
                    Identifier.parse("pocketdungeons:rooms/collapsing_bridge"), q, 1L);
            Ordeals.arm(CollapsingBridgeOrdeal.INSTANCE, level, origins[q]);
        }
        List<String> failures = new ArrayList<>();
        Runnable check = () -> {
            for (int q = 0; q < 4; q++) {
                for (BlockPos pos : bridgePlanks(origins[q], q)) {
                    BlockState state = level.getBlockState(pos);
                    if (!state.is(net.minecraft.tags.BlockTags.PLANKS)) {
                        failures.add("q" + q + " plank missing at " + (pos.getX() - origins[q].getX()) + ","
                                + (pos.getZ() - origins[q].getZ()) + " (" + state.getBlock() + ")");
                    } else if (state.ignitedByLava()) {
                        failures.add("q" + q + " " + state.getBlock() + " is flammable");
                    }
                }
            }
        };
        helper.runAfterDelay(20, () -> {
            check.run();
            // Every block around the bridge is updated, as lava, a placed block or a
            // neighbour's tick would.
            for (BlockPos origin : origins) {
                for (int x = 0; x < CELL; x++) {
                    for (int z = 0; z < CELL; z++) {
                        for (int y = 0; y <= 2; y++) {
                            BlockPos pos = origin.offset(x, y, z);
                            level.updateNeighborsAt(pos, level.getBlockState(pos).getBlock());
                        }
                    }
                }
            }
            helper.runAfterDelay(40, () -> {
                check.run();
                for (BlockPos origin : origins) {
                    Ordeals.clear(origin);
                    release(level, origin);
                }
                helper.assertTrue(failures.isEmpty(), "collapsing_bridge: " + failures);
                helper.succeed();
            });
        });
    }

    // ---- PD-142: vendor name, vanilla purchase, journal ---------------------

    /**
     * PD-142: the vendor is named for what he buys. A frostworks merchant takes
     * bones and rotten flesh like three other floors' merchants, so he is the
     * Bone Collector, not a Frost Peddler.
     */
    @GameTest(maxTicks = 20)
    public void aVendorIsNamedForWhatHeBuys(GameTestHelper helper) {
        for (String theme : new String[]{"ossuary", "deepslate", "endless_mine", "frostworks", "basalt_foundry",
                "copper_works", "ender_archive", "infestation", "rootworks"}) {
            MerchantThemes.Merchant merchant = MerchantThemes.forTheme(theme);
            helper.assertValueEqual(merchant.title(), merchant.currencies().get(0).buyer(),
                    "the title of the " + theme + " merchant");
        }
        helper.assertValueEqual(MerchantThemes.forTheme("frostworks").title(), "Bone Collector", "frostworks vendor");
        helper.assertValueEqual(MerchantThemes.forTheme("prismarine").title(), "Wandering Merchant",
                "a vendor who takes only emeralds");
        helper.succeed();
    }

    /**
     * PD-159: a purchase hands over the plain item (no listing name or lore, so
     * a bought log stacks with any other log), journals it, and stops at sold
     * out.
     */
    @GameTest(maxTicks = 100)
    public void aStorePurchaseIsDeliveredCleanAndJournaled(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));
        StoreNPC.spawn(level, origin, 3L, "frostworks");
        java.util.List<net.minecraft.world.entity.npc.villager.Villager> found = level.getEntitiesOfClass(
                net.minecraft.world.entity.npc.villager.Villager.class,
                new net.minecraft.world.phys.AABB(origin).inflate(CELL),
                v -> v.entityTags().contains(StoreNPC.STORE_TAG));
        helper.assertTrue(!found.isEmpty(), "a store villager stands in the test area");
        net.minecraft.world.entity.npc.villager.Villager villager = found.get(0);
        net.minecraft.server.level.ServerPlayer player = helper.makeMockServerPlayerInLevel();

        player.getInventory().clearContent();
        // The seeded stock and prices vary (up to 31 units at 20 apiece), so hold plenty of every currency.
        net.minecraft.world.item.Item[] coins = {net.minecraft.world.item.Items.BONE,
                net.minecraft.world.item.Items.ROTTEN_FLESH, net.minecraft.world.item.Items.EMERALD};
        int[] slots = {10, 10, 4};
        int next = 0;
        for (int c = 0; c < coins.length; c++) {
            for (int n = 0; n < slots[c]; n++) {
                player.getInventory().setItem(next++, new net.minecraft.world.item.ItemStack(coins[c], 64));
            }
        }
        String tag = villager.entityTags().stream().filter(t -> t.startsWith("pocketdungeons_store_inv:"))
                .findFirst().orElseThrow();
        int stock = Integer.parseInt(tag.substring("pocketdungeons_store_inv:".length()).split(";")[0].split(",", 5)[3]);
        for (int i = 0; i < stock; i++) {
            StoreNPC.Sale got = StoreNPC.buy(player, villager, 0);
            helper.assertTrue(got == StoreNPC.Sale.BOUGHT, "buy " + i + " gave " + got + " from " + tag);
        }
        helper.assertTrue(StoreNPC.buy(player, villager, 0) == StoreNPC.Sale.SOLD_OUT, "a line bought out is sold out");
        boolean clean = false;
        for (net.minecraft.world.item.ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty() && !stack.is(net.minecraft.world.item.Items.BONE)
                    && !stack.is(net.minecraft.world.item.Items.ROTTEN_FLESH)
                    && !stack.is(net.minecraft.world.item.Items.EMERALD)) {
                clean = stack.getOrDefault(net.minecraft.core.component.DataComponents.LORE,
                        net.minecraft.world.item.component.ItemLore.EMPTY).lines().isEmpty()
                        && !stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
                helper.assertTrue(clean, "a bought stack is the plain item: " + stack);
            }
        }
        helper.assertTrue(clean, "the purchases reached the pack");

        boolean journaled = false;
        for (com.google.gson.JsonObject line : PlaytestJournal.recent(player.getUUID(), 50)) {
            if (line.has("ev") && "shop_purchase".equals(line.get("ev").getAsString())) {
                journaled = "Bone Collector".equals(line.get("vendor").getAsString())
                        && line.get("price").getAsInt() > 0;
            }
        }
        helper.assertTrue(journaled, "a shop_purchase line names the vendor and the price");

        villager.discard();
        helper.succeed();
    }
}
