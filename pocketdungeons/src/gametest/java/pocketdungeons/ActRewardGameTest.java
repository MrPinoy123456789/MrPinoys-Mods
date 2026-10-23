package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;

import java.util.Set;
import java.util.UUID;

/**
 * Spec 6.5: when an act completes on the leader's save, every member present
 * gets the rewards and the title exactly once; a second completion pays
 * nothing; a player whose own save already shows the act complete is back
 * paid once on join.
 */
public final class ActRewardGameTest {

    /** Both members collect act 1's rewards; a repeat call pays nobody twice. */
    @GameTest(maxTicks = 20)
    public void bothMembersCollectActRewards(GameTestHelper helper) {
        ServerPlayer owner = standInALoadedChunk(helper);
        ServerPlayer guest = standInALoadedChunk(helper);
        MinecraftServer server = owner.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        helper.assertTrue(ActRewardManifest.byAct(1) != null, "the act_reward data is loaded");
        int slot = 9951;
        InstanceRecord record = new InstanceRecord(slot, helper.absolutePos(BlockPos.ZERO),
                server.getTickCount(), null, Set.of(), owner.getUUID(), false);
        Vec3 ret = new Vec3(0.5, 70.0, 0.5);
        ReturnPoint outside = new ReturnPoint(Level.OVERWORLD, ret, 0.0f, 0.0f);
        record.members.put(owner.getUUID(), outside);
        record.members.put(guest.getUUID(), outside);
        InstanceRegistry.bySlot.put(slot, record);
        InstanceRegistry.usedSlots.add(slot);
        try {
            int[] before = {itemCount(owner), itemCount(guest)};
            ActRewards.actCompleted(server, record, owner, 1);
            for (ServerPlayer p : new ServerPlayer[]{owner, guest}) {
                UUID id = p.getUUID();
                helper.assertTrue(log.actRewardsClaimedOf(id).contains(1),
                        p.getName().getString() + " claimed act 1");
                helper.assertTrue(log.titlesEarnedOf(id).contains(1),
                        p.getName().getString() + " earned the act 1 title");
                helper.assertTrue(log.get(id).unlockedShells().contains("old_mine"),
                        p.getName().getString() + " unlocked the Old Mine shell");
                PlayerTeam team = server.getScoreboard().getPlayersTeam(p.getScoreboardName());
                helper.assertTrue(team != null && team.getName().equals("pd_title_1"),
                        p.getName().getString() + " sits on the pd_title_1 team");
            }
            helper.assertTrue(itemCount(owner) > before[0], "the owner was paid items");
            helper.assertTrue(itemCount(guest) > before[1], "the guest was paid items");

            // A second completion pays nothing.
            int[] after = {itemCount(owner), itemCount(guest)};
            ActRewards.actCompleted(server, record, owner, 1);
            helper.assertValueEqual(itemCount(owner), after[0], "the owner is not paid twice");
            helper.assertValueEqual(itemCount(guest), after[1], "the guest is not paid twice");
        } finally {
            InstanceRegistry.bySlot.remove(slot);
            InstanceRegistry.usedSlots.remove(slot);
            InstanceRegistry.byMember.remove(owner.getUUID());
            InstanceRegistry.byMember.remove(guest.getUUID());
            server.getPlayerList().remove(owner);
            server.getPlayerList().remove(guest);
        }
        helper.succeed();
    }

    /** A player whose own save already shows act 1 complete is back paid once on join. */
    @GameTest(maxTicks = 20)
    public void aPlayerIsBackPaidOnce(GameTestHelper helper) {
        ServerPlayer player = standInALoadedChunk(helper);
        MinecraftServer server = player.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        UUID id = player.getUUID();
        log.unlockAct(id, 2);   // act 1 finished before this feature existed
        try {
            int before = itemCount(player);
            ActRewards.backPay(server, player);
            helper.assertTrue(log.actRewardsClaimedOf(id).contains(1),
                    "act 1 is back paid on join");
            helper.assertTrue(log.titlesEarnedOf(id).contains(1),
                    "the act 1 title is back paid on join");
            helper.assertTrue(itemCount(player) > before, "back pay delivers items");
            int after = itemCount(player);
            ActRewards.backPay(server, player);
            helper.assertValueEqual(itemCount(player), after, "back pay runs once");
        } finally {
            server.getPlayerList().remove(player);
        }
        helper.succeed();
    }

    private static int itemCount(ServerPlayer player) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                n += stack.getCount();
            }
        }
        return n;
    }

    private static ServerPlayer standInALoadedChunk(GameTestHelper helper) {
        @SuppressWarnings("removal")
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        BlockPos where = helper.absolutePos(new BlockPos(1, 2, 1));
        player.teleportTo(helper.getLevel(), where.getX() + 0.5, where.getY(), where.getZ() + 0.5,
                Set.of(), 0.0F, 0.0F, false);
        return player;
    }
}
