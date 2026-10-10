package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import java.util.Set;

/**
 * The Plate Relay: arming swaps the template's centre plate for four corner plates with one lit,
 * a player standing on the lit plate charges it and the light moves, a plate a player breaks is
 * put back, and enough charges meet the objective.
 */
public final class PlateRelayGameTest {

    @GameTest(maxTicks = 40)
    public void armingSwapsTheCentrePlateForFourCornerPlates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        flatCell(level, o);
        level.setBlock(o.offset(8, 1, 8), Blocks.STONE_PRESSURE_PLATE.defaultBlockState(), 3);

        PlateRelayOrdeal.Relay relay = PlateRelayOrdeal.INSTANCE.arm(level, o);
        helper.assertTrue(relay != null, "the room arms");
        helper.assertTrue(level.getBlockState(o.offset(8, 1, 8)).isAir(), "the old centre plate is gone");
        for (int i = 0; i < relay.plates().size(); i++) {
            BlockPos plate = relay.plates().get(i);
            helper.assertTrue(level.getBlockState(plate).is(Blocks.STONE_PRESSURE_PLATE), "plate " + i + " stands");
            helper.assertTrue(level.getBlockState(plate.below()).is(i == relay.live()
                    ? Blocks.SEA_LANTERN : Blocks.BLACKSTONE), "only the lit plate has a lantern under it");
        }

        // A plate a player breaks comes back on the next tick.
        BlockPos broken = relay.plates().get(2);
        level.setBlock(broken, Blocks.AIR.defaultBlockState(), 3);
        PlateRelayOrdeal.INSTANCE.tickDanger(level, o, relay);
        helper.assertTrue(level.getBlockState(broken).is(Blocks.STONE_PRESSURE_PLATE), "a broken plate is put back");
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void standingOnTheLitPlateChargesItAndTheLightMoves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        flatCell(level, o);
        PlateRelayOrdeal.Relay relay = PlateRelayOrdeal.INSTANCE.arm(level, o);
        @SuppressWarnings("removal")
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);

        int charged = 0;
        for (int step = 0; step < 100 && !PlateRelayOrdeal.INSTANCE.objectiveMet(relay); step++) {
            BlockPos lit = relay.plates().get(relay.live());
            player.teleportTo(level, lit.getX() + 0.5, lit.getY(), lit.getZ() + 0.5, Set.of(), 0.0F, 0.0F, false);
            int before = relay.live();
            int chargesBefore = relay.charges();
            relay = PlateRelayOrdeal.INSTANCE.tickDanger(level, o, relay);
            if (relay.charges() > chargesBefore) {
                charged++;
                if (relay.charges() < relay.needed()) {
                    helper.assertTrue(relay.live() != before, "after a charge the light jumps to another plate");
                }
            }
        }
        helper.assertValueEqual(charged, PlateRelayOrdeal.BASE_CHARGES, "a solo player charges the base number of plates");
        helper.assertTrue(PlateRelayOrdeal.INSTANCE.objectiveMet(relay), "and the objective is met");
        helper.succeed();
    }

    private static void flatCell(ServerLevel level, BlockPos o) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                level.setBlock(o.offset(x, 0, z), Blocks.STONE_BRICKS.defaultBlockState(), 3);
                for (int y = 1; y <= 4; y++) {
                    level.setBlock(o.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }
}
