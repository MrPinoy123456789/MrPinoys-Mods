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
     * Playtest 2026-10-03-2: a vault one member opened counts as opened for
     * the whole party, so a party of two no longer loots each vault twice.
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
            int marked = VaultShare.shareWithParty(server, helper.getLevel(), vault, owner.getUUID());
            helper.assertValueEqual(marked, 1, "the companion is marked as paid by the owner's vault");
        } finally {
            helper.getLevel().setBlockAndUpdate(vault, Blocks.AIR.defaultBlockState());
            unregister(9973, owner, companion);
        }
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
