package fortune.mc;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fortune Machine blocks, as Charter's terminals: any block an operator registers. Right-click opens the machine; a
 * few end rod sparks rise over each one (the particle Geyser already shows for Charter's pads). No floating label:
 * display entities break Geyser. Saved in the world's {@code fortune/machines.json} on every change.
 */
final class Machines {

    private static final int SPARK_EVERY = 40;
    private static final double SPARK_RANGE = 24;

    private final FortuneService service;
    private final FortuneStore store;

    Machines(FortuneService service, FortuneStore store) {
        this.service = service;
        this.store = store;
    }

    static String dimension(Level level) {
        return level.dimension().identifier().toString();
    }

    boolean isMachine(Level level, BlockPos pos) {
        return find(level, pos).isPresent();
    }

    Optional<FortuneStore.MachineSpot> find(Level level, BlockPos pos) {
        String dim = dimension(level);
        for (FortuneStore.MachineSpot spot : store.machines()) {
            if (spot.at(dim, pos.getX(), pos.getY(), pos.getZ())) return Optional.of(spot);
        }
        return Optional.empty();
    }

    /** False when that block is already a machine. */
    boolean add(Level level, BlockPos pos) {
        if (isMachine(level, pos)) return false;
        store.machines().add(new FortuneStore.MachineSpot(dimension(level), pos.getX(), pos.getY(), pos.getZ()));
        store.saveMachines();
        return true;
    }

    boolean remove(Level level, BlockPos pos) {
        Optional<FortuneStore.MachineSpot> spot = find(level, pos);
        if (spot.isEmpty()) return false;
        store.machines().remove(spot.get());
        store.saveMachines();
        return true;
    }

    List<FortuneStore.MachineSpot> all() {
        return new ArrayList<>(store.machines());
    }

    void tick(MinecraftServer server) {
        if (server.getTickCount() % SPARK_EVERY != 0 || store.machines().isEmpty()) return;
        for (FortuneStore.MachineSpot spot : store.machines()) {
            Identifier dim = Identifier.tryParse(spot.dimension);
            if (dim == null) continue;
            for (ServerLevel level : server.getAllLevels()) {
                if (!level.dimension().identifier().equals(dim)) continue;
                double x = spot.x + 0.5;
                double y = spot.y + 1.2;
                double z = spot.z + 0.5;
                boolean watched = false;
                for (ServerPlayer p : level.players()) {
                    if (p.distanceToSqr(x, y, z) < SPARK_RANGE * SPARK_RANGE) {
                        watched = true;
                        break;
                    }
                }
                if (watched) level.sendParticles(ParticleTypes.END_ROD, x, y, z, 4, 0.25, 0.2, 0.25, 0.01);
            }
        }
    }
}
