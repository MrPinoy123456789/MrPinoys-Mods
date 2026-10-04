package pocketdungeons;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/**
 * PD-131 and PD-132 (playtest 2026-10-03-2): a companion in the leader's
 * party could not pick a bag, use the staging room's stations or place
 * blocks in the leader's room, and then later could.
 *
 * <p>Each test stands a one-cell home for an owner, registers the players
 * as members, and drives the real {@link UseBlockCallback} chain, so the
 * result is exactly what a client's right-click would get.
 */
public final class PartyAccessGameTest {

    /**
     * A companion may place blocks in the leader's room and open a station
     * with a block in hand; a lobby-directory guest may open the station but
     * may not place.
     */
    @GameTest(maxTicks = 20)
    public void companionBuildsAndGuestUsesStations(GameTestHelper helper) {
        ServerPlayer owner = stand(helper);
        ServerPlayer companion = stand(helper);
        ServerPlayer guest = stand(helper);
        BlockPos origin = helper.absolutePos(new BlockPos(-6, 0, -6));
        InstanceRecord record = home(helper, owner.level().getServer(), 9971, owner, origin);
        record.members.put(companion.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        record.members.put(guest.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        record.guests.add(guest.getUUID());
        register(record, 9971, owner, companion, guest);
        try {
            BlockPos table = origin.offset(7, 1, 7);
            helper.getLevel().setBlockAndUpdate(table, Blocks.CRAFTING_TABLE.defaultBlockState());
            BlockPos floor = origin.offset(9, 0, 7);

            for (ServerPlayer player : List.of(companion, guest)) {
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE, 8));
            }
            helper.assertTrue(RoomProtection.isPermitted(helper.getLevel(), companion, owner.getUUID()),
                    "a companion shares the leader's room permission");
            helper.assertFalse(RoomProtection.isPermitted(helper.getLevel(), guest, owner.getUUID()),
                    "a directory guest does not");

            helper.assertTrue(use(helper, companion, table, Direction.UP) != InteractionResult.FAIL,
                    "a companion holding blocks opens the crafting table");
            helper.assertTrue(use(helper, guest, table, Direction.UP) != InteractionResult.FAIL,
                    "a guest holding blocks opens the crafting table");
            helper.assertTrue(use(helper, companion, floor, Direction.UP) != InteractionResult.FAIL,
                    "a companion may place a block in the leader's room");
            helper.assertTrue(use(helper, guest, floor, Direction.UP) == InteractionResult.FAIL,
                    "a guest may not place a block in the owner's room");
        } finally {
            unregister(9971, owner, companion, guest);
        }
        helper.succeed();
    }

    /**
     * The bag chest stays for a companion after the leader has a bag, and a
     * click from someone who already carries one is answered rather than
     * falling through to the container denial.
     */
    @GameTest(maxTicks = 20)
    public void bagChestAnswersEveryMember(GameTestHelper helper) {
        ServerPlayer owner = stand(helper);
        ServerPlayer companion = stand(helper);
        MinecraftServer server = owner.level().getServer();
        BlockPos origin = helper.absolutePos(new BlockPos(-6, 0, -6));
        InstanceRecord record = home(helper, server, 9972, owner, origin);
        record.members.put(companion.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        register(record, 9972, owner, companion);
        DungeonLog log = DungeonLog.forServer(server);
        try {
            log.setBag(companion.getUUID(), "");
            log.setBag(owner.getUUID(), "");
            Instances.placeBagChestForParty(helper.getLevel(), server, record);
            BlockPos chest = Instances.bagChestPos(origin);
            helper.assertTrue(helper.getLevel().getBlockState(chest).is(Instances.bagChestBlock()),
                    "the bag chest stands while the party is bagless");

            log.setBag(owner.getUUID(), Bags.ids().get(0));
            helper.assertTrue(use(helper, owner, chest, Direction.UP) == InteractionResult.SUCCESS_SERVER,
                    "a leader who already carries a bag is answered at the chest");
            helper.assertTrue(use(helper, companion, chest, Direction.UP) == InteractionResult.SUCCESS_SERVER,
                    "a bagless companion opens the bag picker");
        } finally {
            log.setBag(owner.getUUID(), "");
            log.setBag(companion.getUUID(), "");
            helper.getLevel().setBlockAndUpdate(Instances.bagChestPos(origin), Blocks.AIR.defaultBlockState());
            unregister(9972, owner, companion);
        }
        helper.succeed();
    }

    /**
     * Playtest 2026-10-03-2 (owner rule): a party opens each vault once, and
     * that opening pays one roll of the vault's table per member. The
     * companion is marked as paid, and the eject list gains a second roll.
     */
    @GameTest(maxTicks = 20)
    public void vaultPaysThePartyOnce(GameTestHelper helper) {
        ServerPlayer owner = stand(helper);
        ServerPlayer companion = stand(helper);
        MinecraftServer server = owner.level().getServer();
        BlockPos origin = helper.absolutePos(new BlockPos(-6, 0, -6));
        InstanceRecord record = home(helper, server, 9973, owner, origin);
        record.members.put(companion.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        register(record, 9973, owner, companion);
        BlockPos vault = helper.absolutePos(new BlockPos(2, 1, 2));
        try {
            helper.getLevel().setBlockAndUpdate(vault, Blocks.VAULT.defaultBlockState());
            var be = (net.minecraft.world.level.block.entity.vault.VaultBlockEntity)
                    helper.getLevel().getBlockEntity(vault);
            var data = (pocketdungeons.mixin.VaultServerDataAccessor) (Object) be.getServerData();
            data.pocketdungeons$setItemsToEject(new java.util.ArrayList<>(List.of(new ItemStack(Items.EMERALD))));
            int extra = PartyRewards.payParty(server, helper.getLevel(), vault, owner.getUUID());
            helper.assertValueEqual(extra, 1, "one extra roll for the one companion");
            helper.assertTrue(data.pocketdungeons$getItemsToEject().size() > 1,
                    "the opening ejects the owner's roll and the companion's, got "
                            + data.pocketdungeons$getItemsToEject().size() + " items");
        } finally {
            helper.getLevel().setBlockAndUpdate(vault, Blocks.AIR.defaultBlockState());
            unregister(9973, owner, companion);
        }
        helper.succeed();
    }

    /**
     * Playtest 2026-10-03-2 (owner rule): a beaten trial spawner ejects one
     * reward for the party, not one per player it saw. A spawner still
     * fighting keeps every player, since that sets its mob count.
     */
    @GameTest(maxTicks = 20)
    public void spawnerPaysOneKeyPerParty(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.getLevel().setBlockAndUpdate(pos, Blocks.TRIAL_SPAWNER.defaultBlockState());
        var spawner = (net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity)
                helper.getLevel().getBlockEntity(pos);
        java.util.Set<java.util.UUID> rewarded = ((pocketdungeons.mixin.TrialSpawnerStateDataAccessor)
                (Object) spawner.getTrialSpawner().getStateData()).pocketdungeons$detectedPlayers();
        rewarded.add(java.util.UUID.randomUUID());
        rewarded.add(java.util.UUID.randomUUID());
        PartyRewards.oneRewardPerSpawner(helper.getLevel(), List.of(pos));
        helper.assertValueEqual(rewarded.size(), 2, "a spawner still fighting keeps both players");
        spawner.setState(helper.getLevel(),
                net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState.WAITING_FOR_REWARD_EJECTION);
        PartyRewards.oneRewardPerSpawner(helper.getLevel(), List.of(pos));
        helper.assertValueEqual(rewarded.size(), 1, "a beaten spawner rewards one player, so ejects one key");
        helper.succeed();
    }

    /**
     * Playtest 2026-10-03-2: the journal's inventory snapshot lists every
     * item by id and every piece of gear with its durability and tier, so
     * the loot a session gave out can be analysed afterwards.
     */
    @GameTest(maxTicks = 20)
    public void inventorySnapshotDescribesItemsAndGear(GameTestHelper helper) {
        ItemStack cap = new ItemStack(Items.LEATHER_HELMET);
        cap.setDamageValue(10);
        net.minecraft.world.item.component.CustomData.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                cap, tag -> {
                    net.minecraft.nbt.CompoundTag mine = new net.minecraft.nbt.CompoundTag();
                    mine.putInt("tier", 1);
                    tag.put(PocketDungeonsMod.MOD_ID, mine);
                });
        java.util.Map<String, Object> out = PlaytestJournal.describe(List.of(
                new ItemStack(Items.BREAD, 3), new ItemStack(Items.BREAD, 2), ItemStack.EMPTY, cap));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Integer> items = (java.util.Map<String, Integer>) out.get("items");
        helper.assertValueEqual(items.get("minecraft:bread"), 5, "bread totals across stacks");
        helper.assertValueEqual(items.get("minecraft:leather_helmet"), 1, "the cap is counted");
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> gear = (List<java.util.Map<String, Object>>) out.get("gear");
        helper.assertValueEqual(gear.size(), 1, "one damageable piece");
        helper.assertValueEqual(gear.get(0).get("left"), cap.getMaxDamage() - 10, "durability left");
        helper.assertValueEqual(gear.get(0).get("tier"), 1, "loot tier");
        helper.succeed();
    }

    private static InteractionResult use(GameTestHelper helper, ServerPlayer player, BlockPos pos, Direction face) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5), face, pos, false);
        return UseBlockCallback.EVENT.invoker().interact(player, helper.getLevel(), InteractionHand.MAIN_HAND, hit);
    }

    /** A one-cell home for {@code owner} with its room at {@code origin}. */
    private static InstanceRecord home(GameTestHelper helper, MinecraftServer server, int slot,
                                       ServerPlayer owner, BlockPos origin) {
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        InstanceLayout layout = new InstanceLayout(origin, geometry, origin.offset(3, 1, 3), 0.0f,
                origin.offset(8, 1, 8), geometry.bounds(), 1L, 4, 4, 1, true, Set.of(), 3, origin,
                0, 0, Set.of(), null, Set.of());
        InstanceRecord record = new InstanceRecord(slot, origin, server.getTickCount(), layout,
                Set.of(), owner.getUUID(), false);
        record.members.put(owner.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        record.roomCellOrigin = origin;
        return record;
    }

    private static void register(InstanceRecord record, int slot, ServerPlayer... members) {
        InstanceRegistry.bySlot.put(slot, record);
        InstanceRegistry.usedSlots.add(slot);
        for (ServerPlayer member : members) {
            InstanceRegistry.byMember.put(member.getUUID(), record);
        }
    }

    private static void unregister(int slot, ServerPlayer... members) {
        InstanceRegistry.bySlot.remove(slot);
        InstanceRegistry.usedSlots.remove(slot);
        for (ServerPlayer member : members) {
            InstanceRegistry.byMember.remove(member.getUUID());
        }
    }

    private static ServerPlayer stand(GameTestHelper helper) {
        @SuppressWarnings("removal")
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        BlockPos where = helper.absolutePos(new BlockPos(1, 2, 1));
        player.teleportTo(helper.getLevel(), where.getX() + 0.5, where.getY(), where.getZ() + 0.5,
                Set.of(), 0.0F, 0.0F, false);
        return player;
    }
}
