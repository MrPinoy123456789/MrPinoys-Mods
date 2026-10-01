package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * An Ordeal whose danger is a classic mob spawner and whose resolution is a
 * baked lever with its lamp. Two rooms use it:
 *
 * <ul>
 *   <li>Thicket: reach the spawner through the cobweb lattice while cave
 *       spiders come for you.</li>
 *   <li>Ice Run: hop the floating ice up to the lever platform in the middle
 *       while strays on the floor shoot you off; or pillar up to it.</li>
 * </ul>
 *
 * <p>Both rooms put the lever in the middle and read the same after the
 * layout turns them round, so their lever is baked into the template rather
 * than placed at the exit. Playtest 2026-09-29-3: the
 * player expected a counterplay for the thicket's spawner; the lever is it,
 * and replaced the torch-on-a-face check tried first.
 */
final class SpawnerOrdeal extends Ordeal<SpawnerOrdeal.Room> {

    static final SpawnerOrdeal THICKET = new SpawnerOrdeal("thicket", EntityTypes.CAVE_SPIDER,
            "reach the spawner through the webs", "cobwebs and cave spiders");
    static final SpawnerOrdeal ICE_RUN = new SpawnerOrdeal("ice_run", EntityTypes.STRAY,
            "climb the floating ice to the lever platform", "strays shooting you off the ice");

    /** A spawner room is quiet until someone pulls the lever; the tick only watches for that. */
    private static final int PERIOD = 20;

    private final EntityType<?> mob;

    record Room(List<BlockPos> spawners, BlockPos lever) {}

    private SpawnerOrdeal(String id, EntityType<?> mob, String objective, String danger) {
        super(id, PERIOD, objective, danger, "the lever on the spawner shuts it off");
        this.mob = mob;
    }

    /** Sets the room's spawners to its mob (PD-68) and finds the lever beside them. */
    @Override
    Room arm(ServerLevel level, BlockPos cellOrigin) {
        List<BlockPos> spawners = ClassicSpawners.configure(level, cellOrigin, mob);
        BlockPos lever = Ordeals.findLever(level, cellOrigin);
        if (lever == null) {
            // A template baked before the lever existed: the spawner still
            // works, there is just no way to end it until the room is regenerated.
            return null;
        }
        return new Room(List.copyOf(spawners), lever);
    }

    /** The lever is protected, so it only goes when the cell does. */
    @Override
    boolean stale(ServerLevel level, Room room) {
        return !level.getBlockState(room.lever()).is(Blocks.LEVER);
    }

    @Override
    BlockPos lever(Room room) {
        return room.lever();
    }

    @Override
    String resolve(ServerLevel level, BlockPos cellOrigin, Room room) {
        for (BlockPos pos : room.spawners()) {
            ClassicSpawners.douse(level, pos, mob);
            level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.8f, 1.0f);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, pos.getX() + 0.5, pos.getY() + 0.7,
                    pos.getZ() + 0.5, 12, 0.3, 0.3, 0.3, 0.01);
        }
        return "The spawner goes dark.";
    }
}
