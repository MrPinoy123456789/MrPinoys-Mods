package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/**
 * PD-167: a death rescue spends a life and re-admits the player; it never ends the run for the
 * party, whether the player who died is the owner or a member.
 */
public final class RescueGameTest {

    private static final int SLOT = 9167;

    @GameTest
    public void theOwnersDeathDoesNotEndThePartysRun(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer owner = player(helper);
        ServerPlayer rider = player(helper);
        InstanceRecord record = record(helper, server, owner, rider);
        InstanceRegistry.bySlot.put(SLOT, record);
        InstanceRegistry.usedSlots.add(SLOT);
        InstanceRegistry.byMember.put(owner.getUUID(), record);
        InstanceRegistry.byMember.put(rider.getUUID(), record);
        try {
            Instances.rescue(owner, record);
            helper.assertTrue(InstanceRegistry.bySlot.get(SLOT) == record,
                    "the run still exists after its owner was rescued");
            helper.assertTrue(record.members.containsKey(owner.getUUID()),
                    "the owner is re-admitted to the run");
            helper.assertTrue(record.members.containsKey(rider.getUUID()),
                    "the member was never dropped");
            helper.assertTrue(InstanceRegistry.byMember.get(owner.getUUID()) == record,
                    "the owner is still registered as a member of it");
        } finally {
            InstanceRegistry.bySlot.remove(SLOT);
            InstanceRegistry.usedSlots.remove(SLOT);
            InstanceRegistry.byMember.remove(owner.getUUID());
            InstanceRegistry.byMember.remove(rider.getUUID());
        }
        helper.succeed();
    }

    @GameTest
    public void aMembersDeathDoesNotEndItEither(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer owner = player(helper);
        ServerPlayer rider = player(helper);
        InstanceRecord record = record(helper, server, owner, rider);
        InstanceRegistry.bySlot.put(SLOT, record);
        InstanceRegistry.usedSlots.add(SLOT);
        InstanceRegistry.byMember.put(owner.getUUID(), record);
        InstanceRegistry.byMember.put(rider.getUUID(), record);
        try {
            Instances.rescue(rider, record);
            helper.assertTrue(InstanceRegistry.bySlot.get(SLOT) == record, "the run still exists");
            helper.assertTrue(record.members.containsKey(owner.getUUID())
                            && record.members.containsKey(rider.getUUID()),
                    "both are still in the run");
        } finally {
            InstanceRegistry.bySlot.remove(SLOT);
            InstanceRegistry.usedSlots.remove(SLOT);
            InstanceRegistry.byMember.remove(owner.getUUID());
            InstanceRegistry.byMember.remove(rider.getUUID());
        }
        helper.succeed();
    }

    private static InstanceRecord record(GameTestHelper helper, MinecraftServer server,
                                         ServerPlayer owner, ServerPlayer rider) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        InstanceLayout layout = new InstanceLayout(origin, geometry, origin.offset(3, 1, 3), 0.0f,
                origin.offset(8, 1, 8), geometry.bounds(), 1L, 4, 4, 1, true, Set.of(), 3, origin,
                0, 0, Set.of(), null, Set.of());
        InstanceRecord record = new InstanceRecord(SLOT, origin, server.getTickCount(), layout,
                Set.of(), owner.getUUID(), false);
        record.stagingCellOrigin = origin;
        ReturnPoint point = new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f);
        record.members.put(owner.getUUID(), point);
        record.members.put(rider.getUUID(), point);
        return record;
    }

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper helper) {
        return helper.makeMockServerPlayerInLevel();
    }
}
