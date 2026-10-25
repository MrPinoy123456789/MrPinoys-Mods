package charter.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.properties.RailShape;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M0 spikes (PLAN.md §11, risks R2 and R12). Throwaway proofs that the three
 * things the supply line is built on work in 26.2 before anything is built on
 * them: the protection Mixin targets exist, a minecart crossing a chunk border on
 * real rails is seen by a scan every 5 ticks, and a player's chunk change is
 * seen by a per-tick scan of the player list.
 */
public final class SpikeGameTest {

    /** Every class a protection Mixin targets. Matches charter-gametest.mixins.json. */
    private static final List<String> MIXIN_TARGETS = List.of(
            "net.minecraft.world.item.BlockItem",
            "net.minecraft.world.level.ServerExplosion",
            "net.minecraft.world.level.block.piston.PistonBaseBlock",
            "net.minecraft.world.level.block.FireBlock",
            "net.minecraft.world.level.material.LavaFluid",
            "net.minecraft.world.level.material.FlowingFluid",
            "net.minecraft.world.level.block.DispenserBlock",
            "net.minecraft.world.level.block.entity.HopperBlockEntity",
            "net.minecraft.world.entity.vehicle.VehicleEntity",
            "net.minecraft.world.level.block.FarmlandBlock");

    /**
     * Loading each target class applies its spike Mixin; with defaultRequire 1 a
     * missing method would fail the load. The marker proves the Mixin applied.
     */
    @GameTest
    public void mixinTargetsResolve(GameTestHelper helper) {
        for (String name : MIXIN_TARGETS) {
            Class<?> target;
            try {
                target = Class.forName(name, true, SpikeGameTest.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                helper.fail("Mixin target class is missing in 26.2: " + name);
                return;
            }
            helper.assertTrue(SpikeMarker.class.isAssignableFrom(target), "Spike Mixin did not apply to " + name);
        }
        helper.succeed();
    }

    /**
     * A chest minecart pushed along rails that cross a chunk border is seen to
     * change chunk by a scan that only looks every 5 ticks, the cart scan rate in
     * PLAN.md §4.2.
     */
    @GameTest(maxTicks = 200)
    public void cartCrossingSeenByScan(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));
        int border = nearestBorder(origin.getX());
        int y = origin.getY();
        int z = origin.getZ() + 1;
        // The test can land anywhere; a chunk outside the test area may not tick entities, and a
        // cart there never moves. Force-load the chunks along the line for the length of the test.
        int chunkZ = Math.floorDiv(z, 16);
        for (int cx = Math.floorDiv(border - 4, 16); cx <= Math.floorDiv(border + 4, 16); cx++) {
            level.setChunkForced(cx, chunkZ, true);
        }
        for (int x = border - 4; x <= border + 4; x++) {
            level.setBlockAndUpdate(new BlockPos(x, y - 1, z), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(x, y, z),
                    Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST));
        }

        MinecartChest cart = EntityTypes.CHEST_MINECART.create(level, EntitySpawnReason.COMMAND);
        if (cart == null) {
            helper.fail("Could not create a chest minecart");
            return;
        }
        cart.snapTo(border - 3 + 0.5, y + 0.1, z + 0.5);
        level.addFreshEntity(cart);
        cart.setDeltaMovement(0.4, 0, 0);

        ChunkPos start = new ChunkPos(Math.floorDiv(border - 3, 16), Math.floorDiv(z, 16));
        boolean[] seen = {false};
        long[] ticks = {0};
        helper.onEachTick(() -> {
            ticks[0]++;
            if (ticks[0] % 5 == 0 && !cart.chunkPosition().equals(start)) {
                seen[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(seen[0], "The 5 tick scan never saw the cart change chunk");
            for (int cx = Math.floorDiv(border - 4, 16); cx <= Math.floorDiv(border + 4, 16); cx++) {
                level.setChunkForced(cx, chunkZ, false);
            }
        });
    }

    /** The chunk border (a multiple of 16) nearest to x, so the test's rails stay close to its own area. */
    static int nearestBorder(int x) {
        int below = Math.floorDiv(x, 16) * 16;
        return x - below < 8 ? below : below + 16;
    }

    /**
     * A mock server player is in the server's player list, and walking it across
     * a chunk border is seen by the per-tick comparison the crossing detector will
     * run over every online player (PLAN.md §4.2, §5.2).
     */
    @GameTest(maxTicks = 200)
    public void playerChunkChangeSeen(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(helper.getLevel().getServer().getPlayerList().getPlayers().contains(player),
                "A mock server player is not in the player list, so the detector cannot see it");

        BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));
        int border = nearestBorder(origin.getX());
        double[] x = {border - 3 + 0.5};
        player.teleportTo(x[0], origin.getY(), origin.getZ() + 0.5);

        Map<UUID, ChunkPos> last = new HashMap<>();
        boolean[] seen = {false};
        helper.onEachTick(() -> {
            x[0] += 0.5;
            player.teleportTo(x[0], origin.getY(), origin.getZ() + 0.5);
            for (ServerPlayer online : helper.getLevel().getServer().getPlayerList().getPlayers()) {
                ChunkPos now = online.chunkPosition();
                ChunkPos before = last.put(online.getUUID(), now);
                if (online == player && before != null && !before.equals(now)) {
                    seen[0] = true;
                }
            }
        });
        helper.succeedWhen(() -> helper.assertTrue(seen[0], "The per-tick scan never saw the player change chunk"));
    }
}
