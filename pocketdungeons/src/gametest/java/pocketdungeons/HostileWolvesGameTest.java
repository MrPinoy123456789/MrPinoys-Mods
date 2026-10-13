package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/**
 * PD-204: a wolf loose in a Kennels floor hunts the player it can see. The
 * sweep used to ask {@code Instances.inDungeon(pos, "kennels")} against the
 * namespaced id {@code commitDoor} stores ({@code pocketdungeons:kennels}),
 * so the check never matched and every untagged wolf stayed passive. This
 * drives the same per-player sweep a tick does: a registered instance whose
 * interval carries the namespaced id, a player inside it, and wolves beside
 * them. The gametest level stands in for the dungeon dimension because
 * {@link Instances#inDungeon} is a bounds check, not a dimension check.
 */
public final class HostileWolvesGameTest {

    private static final int SLOT = 9983;

    @GameTest(maxTicks = 60)
    public void aKennelsWolfTargetsAPlayer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);

        BlockPos origin = helper.absolutePos(new BlockPos(0, 0, 0));
        for (int x = 4; x <= 12; x++) {
            for (int z = 4; z <= 12; z++) {
                level.setBlock(origin.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }

        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        InstanceLayout layout = new InstanceLayout(origin, geometry, origin.offset(3, 1, 3), 0.0f,
                origin.offset(8, 1, 8), geometry.bounds(), 1L, 4, 4, 1, true, Set.of(), 3, origin,
                0, 0, Set.of(), null, Set.of());
        InstanceRecord record = new InstanceRecord(SLOT, origin, server.getTickCount(), layout,
                Set.of(), player.getUUID(), false);
        record.interval.dungeonId = "pocketdungeons:kennels";
        record.members.put(player.getUUID(),
                new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        InstanceRegistry.bySlot.put(SLOT, record);
        InstanceRegistry.usedSlots.add(SLOT);
        InstanceRegistry.byMember.put(player.getUUID(), record);

        try {
            BlockPos spot = origin.offset(8, 1, 8);
            player.teleportTo(level, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5,
                    Set.of(), 0.0f, 0.0f, false);

            // An untagged wolf in a Kennels floor goes for the player.
            Wolf hunter = new Wolf(EntityTypes.WOLF, level);
            hunter.setPos(spot.getX() + 2.5, spot.getY(), spot.getZ() + 0.5);
            level.addFreshEntity(hunter);
            // A calm-tagged one never does.
            Wolf calm = new Wolf(EntityTypes.WOLF, level);
            HostileWolves.calm(calm);
            calm.setPos(spot.getX() - 2.5, spot.getY(), spot.getZ() + 0.5);
            level.addFreshEntity(calm);

            HostileWolves.enrageAround(level, player);
            // The sweep plants a persistent anger target at the player; the
            // wolf's own brain resolves that into an attack target on its
            // next tick.
            helper.assertTrue(hunter.getPersistentAngerTarget() != null
                            && player.getUUID().equals(hunter.getPersistentAngerTarget().getUUID()),
                    "an untagged wolf in the Kennels gets a persistent anger target at the player"
                            + " (inDungeon=" + Instances.inDungeon(player.blockPosition(), "kennels")
                            + ", anger=" + hunter.getPersistentAngerTarget() + ")");
            helper.assertTrue(calm.getPersistentAngerTarget() == null,
                    "a calm wolf in the Kennels does not");

            // Outside the instance bounds the same wolf is left alone, and a
            // wolf a room marked hostile still hunts anywhere.
            BlockPos outside = origin.offset(26, 1, 8);
            helper.assertTrue(!Instances.inDungeon(outside, "kennels"), "the outside spot is beyond the instance bounds");
            // Where the test structure lands in the world differs from run to run; make sure the chunk 26 blocks
            // away exists before anything is placed or teleported there, or the wolves never join the level.
            level.getChunk(outside.getX() >> 4, outside.getZ() >> 4);
            player.teleportTo(level, outside.getX() + 0.5, outside.getY(), outside.getZ() + 0.5,
                    Set.of(), 0.0f, 0.0f, false);
            level.setBlock(outside.below(), Blocks.STONE.defaultBlockState(), 3);
            Wolf stray = new Wolf(EntityTypes.WOLF, level);
            stray.setPos(outside.getX() + 2.5, outside.getY(), outside.getZ() + 0.5);
            level.addFreshEntity(stray);
            Wolf tagged = new Wolf(EntityTypes.WOLF, level);
            HostileWolves.mark(tagged);
            tagged.setPos(outside.getX() - 2.5, outside.getY(), outside.getZ() + 0.5);
            level.addFreshEntity(tagged);

            helper.assertTrue(level.getEntity(tagged.getUUID()) != null && level.getEntity(stray.getUUID()) != null,
                    "both outside wolves joined the level (chunk loaded: " + level.hasChunkAt(outside) + ")");
            helper.assertTrue(player.blockPosition().distSqr(outside) < 16.0,
                    "the player reached the outside spot: at " + player.blockPosition().toShortString()
                            + ", wanted " + outside.toShortString());
            HostileWolves.enrageAround(level, player);
            helper.assertTrue(stray.getPersistentAngerTarget() == null,
                    "an untagged wolf outside a Kennels floor stays passive");
            helper.assertTrue(tagged.getPersistentAngerTarget() != null
                            && player.getUUID().equals(tagged.getPersistentAngerTarget().getUUID()),
                    "a wolf marked hostile hunts in any dungeon");

            hunter.discard();
            calm.discard();
            stray.discard();
            tagged.discard();
        } finally {
            InstanceRegistry.bySlot.remove(SLOT);
            InstanceRegistry.usedSlots.remove(SLOT);
            InstanceRegistry.byMember.remove(player.getUUID());
        }
        helper.succeed();
    }
}
