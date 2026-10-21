package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Crew model (decision 2026-10-21): a run moves the party leader's campaign only. A finish records
 * on the leader's save, guests keep their own campaign untouched, and every member earns each
 * dungeon's first finish bonus once through {@link DungeonLog#claimFinishBonus}.
 */
public final class CrewProgressGameTest {

    private static final String MINESHAFT = "pocketdungeons:mineshaft";
    private static final String SPAWNER = "pocketdungeons:spawner_dungeon";

    /** A two member finish writes the dungeon to the leader's save only; each member is paid the bonus once. */
    @GameTest(maxTicks = 20)
    @SuppressWarnings("removal")
    public void aFinishMovesOnlyTheLeadersCampaign(GameTestHelper helper) {
        ServerPlayer leader = standInALoadedChunk(helper);
        ServerPlayer guest = helper.makeMockServerPlayerInLevel();
        guest.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        MinecraftServer server = leader.level().getServer();
        ServerLevel level = helper.getLevel();
        DungeonLog log = DungeonLog.forServer(server);
        UUID leaderId = leader.getUUID();
        UUID guestId = guest.getUUID();
        DungeonDef def = DungeonDefs.current().byId(MINESHAFT);

        InstanceRecord record = floorRecord(helper, server, 9911, leader, MINESHAFT);
        record.members.put(guestId, new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        register(record, 9911, leader, guest);
        try {
            RunLifecycle.finishDungeon(server, level, record, def, record.origin,
                    DoorMask.Direction.NORTH, null);
            helper.assertTrue(log.get(leaderId).dungeonsFinished().contains(MINESHAFT),
                    "the leader's save records the finish");
            helper.assertTrue(!log.get(guestId).dungeonsFinished().contains(MINESHAFT),
                    "the guest's own campaign does not move");
            helper.assertTrue(log.finishBonusClaimedOf(leaderId).contains(MINESHAFT),
                    "the leader claims the first finish bonus");
            helper.assertTrue(log.finishBonusClaimedOf(guestId).contains(MINESHAFT),
                    "the guest claims the first finish bonus too");
            helper.assertValueEqual(emeralds(guest), PocketDungeonsConfig.finishEmeralds(),
                    "the guest's first finish pays the full rate");
            helper.assertValueEqual(emeralds(leader), PocketDungeonsConfig.finishEmeralds(),
                    "the leader's first finish pays the full rate");

            RunLifecycle.finishDungeon(server, level, record, def, record.origin,
                    DoorMask.Direction.NORTH, null);
            int repeat = PocketDungeonsConfig.finishEmeralds()
                    * PocketDungeonsConfig.repeatFinishEmeraldPercent() / 100;
            helper.assertValueEqual(emeralds(guest), PocketDungeonsConfig.finishEmeralds() + repeat,
                    "the guest's repeat finish pays the repeat rate, not the first bonus again");
            helper.assertValueEqual(emeralds(leader), PocketDungeonsConfig.finishEmeralds() + repeat,
                    "the leader's repeat finish pays the repeat rate");
        } finally {
            unregister(9911, leader, guest);
        }
        helper.succeed();
    }

    /** An act completing on the leader's save leaves the guest's acts where they were. */
    @GameTest(maxTicks = 20)
    @SuppressWarnings("removal")
    public void anActOpensOnTheLeadersSaveOnly(GameTestHelper helper) {
        ServerPlayer leader = standInALoadedChunk(helper);
        ServerPlayer guest = helper.makeMockServerPlayerInLevel();
        guest.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        MinecraftServer server = leader.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        UUID leaderId = leader.getUUID();
        UUID guestId = guest.getUUID();

        InstanceRecord record = floorRecord(helper, server, 9912, leader, SPAWNER);
        record.members.put(guestId, new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        register(record, 9912, leader, guest);
        try {
            log.addDungeonFinished(leaderId, SPAWNER);
            DungeonProgress.onProgress(server, record, leader, true, true);
            helper.assertTrue(log.get(leaderId).campaign().actsUnlocked().contains(2),
                    "finishing the Act 1 capstone opens Act 2 on the leader's save");
            helper.assertTrue(!log.get(guestId).campaign().actsUnlocked().contains(2),
                    "the guest's acts do not move");
            helper.assertTrue(!log.get(guestId).dungeonsFinished().contains(SPAWNER),
                    "the guest's finished dungeons do not move");
        } finally {
            unregister(9912, leader, guest);
        }
        helper.succeed();
    }

    // ---- scaffolding ---------------------------------------------------------------------------

    private static int emeralds(ServerPlayer player) {
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(Items.EMERALD)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static InstanceRecord floorRecord(GameTestHelper helper, MinecraftServer server, int slot,
                                              ServerPlayer owner, String dungeonId) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        InstanceLayout layout = new InstanceLayout(origin, geometry, origin.offset(3, 1, 3), 0.0f,
                origin.offset(8, 1, 8), geometry.bounds(), 1L, 4, 4, 1, true, Set.of(), 3, origin,
                0, 0, Set.of(), null, Set.of());
        InstanceRecord record = new InstanceRecord(slot, origin, server.getTickCount(), layout,
                Set.of(), owner.getUUID(), false);
        record.floor.theme = "pocketdungeons:test_crew_progress";
        record.members.put(owner.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        record.phase = RunSession.Phase.FLOOR_CLEARED;
        record.floor.completed.add(owner.getUUID());
        record.interval.dungeonId = dungeonId;
        record.interval.floorIndex = 1;
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
