package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawner;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The Hold the Plate Ordeal.
 *
 * <ul>
 *   <li>Objective: hold the plate for {@link #HOLD_SECONDS} seconds.</li>
 *   <li>Danger: the plate raises its own waves as the count runs down, on top
 *       of the room's trial spawner.</li>
 *   <li>Resolution: the hold completes and the exit's iron door opens. No
 *       lever: the plate is already this room's action.</li>
 * </ul>
 *
 * <p>PD-67/PD-125: drives Hold the Plate in code. Stand on the plate for
 * {@link #HOLD_SECONDS} seconds and the exit's iron door opens; step off and
 * the count pauses, so the player can dodge ranged attacks and step back on.
 *
 * <p>The room used to carry a hopper clock, a comparator and a repeater meant
 * to open the door, but the diodes faced the wrong way, the plate was wired to
 * nothing and the clock had no lock, so it never worked. A redstone circuit
 * also cannot tell a player from a mob on the plate or show progress. The
 * template now carries only the plate; the door is placed at runtime by
 * {@link LayoutStamper} on the exit side (PD-66), so this handler finds it by
 * scanning the cell when the count completes.
 *
 * <p>{@link Ordeals} runs it: arm at stamp time, a slow tick, dropped on
 * teardown or once the cell is gone.
 *
 * <p>Playtest 2026-09-29 #2: the hold is the fight. Clearing the room's
 * trial spawner used to open the door early, which fixed the dead time of a
 * pre-cleared room by making the plate pointless ("makes standing on it
 * pointless"). Now the plate raises its own waves, keyed to the time left
 * ({@link #WAVES}): one mob the moment someone steps on, more as the count
 * runs down, the biggest waves at the end. They come out of the room's trial
 * spawner through {@link TrialSpawner#spawnMob}, so they are the room's own
 * mob table (equipment, ominous and all) but never join the spawner's tally:
 * the spawner still clears on its own terms, and clearing it first skips
 * nothing. Stepping off pauses the count and no new waves are raised;
 * {@link #ALIVE_CAP} and {@link #SPAWN_CAP} keep a player who steps on and off
 * from farming them.
 */
final class HoldThePlateOrdeal extends Ordeal<HoldThePlateOrdeal.Plate> {

    static final HoldThePlateOrdeal INSTANCE = new HoldThePlateOrdeal();

    /** Seconds a player must stand on the plate without stepping off. */
    static final int HOLD_SECONDS = 30;
    /** Ticks between evaluations. */
    private static final int PERIOD = 10;
    private static final int HOLD_TICKS = HOLD_SECONDS * 20;

    /**
     * The plate's waves: {seconds left when it comes, mobs in it}. The first
     * arrives on the step, and they grow toward the end so the last stretch
     * is the hardest. Each extra player on the plate adds one mob to every
     * wave after the first.
     */
    static final int[][] WAVES = {{30, 1}, {20, 1}, {12, 2}, {5, 2}};
    /** Plate mobs alive at once, beyond which a wave is cut short. */
    static final int ALIVE_CAP = 6;
    /** Plate mobs a room raises over its whole life, across every attempt. */
    static final int SPAWN_CAP = 16;
    /** Tries per mob: a trial spawner's pick of spot can land in a wall. */
    private static final int SPAWN_TRIES = 6;

    /**
     * One armed room: its plate, the cell's trial spawners, how long a player
     * has held it, which of {@link #WAVES} this hold has already raised (a
     * bit each), how many plate mobs the room has raised in all, and the ones
     * still tracked.
     */
    record Plate(ServerLevel level, BlockPos plate, BlockPos cellOrigin, List<BlockPos> spawners,
                 int heldTicks, int wavesFired, int spawned, List<UUID> mobs) {
        Plate withHeld(int ticks) {
            return new Plate(level, plate, cellOrigin, spawners, ticks, wavesFired, spawned, mobs);
        }
    }

    private HoldThePlateOrdeal() {
        super("hold_the_plate", PERIOD, "hold the plate for " + HOLD_SECONDS + " seconds",
                "waves raised by the plate, and the room's spawner", "the hold completes and the exit opens");
    }

    /**
     * Arms the cell at {@code cellOrigin}: finds its stone pressure plate,
     * rotation-agnostic, and notes the cell's trial spawners, which raise the
     * plate's waves. Call after the encounter has turned the template's
     * spawner into a trial spawner.
     */
    @Override
    Plate arm(ServerLevel level, BlockPos cellOrigin) {
        List<BlockPos> spawners = new ArrayList<>();
        BlockPos plate = null;
        for (int x = 0; x < RoomGeometry.CELL; x++) {
            for (int z = 0; z < RoomGeometry.CELL; z++) {
                for (int y = 0; y <= RoomGeometry.CEILING_Y; y++) {
                    BlockPos pos = cellOrigin.offset(x, y, z);
                    if (level.getBlockEntity(pos) instanceof TrialSpawnerBlockEntity) {
                        spawners.add(pos.immutable());
                    }
                }
                BlockPos floor = cellOrigin.offset(x, 1, z);
                if (plate == null && level.getBlockState(floor).is(Blocks.STONE_PRESSURE_PLATE)) {
                    plate = floor.immutable();
                }
            }
        }
        if (plate == null) {
            return null;
        }
        return new Plate(level, plate, cellOrigin.immutable(), List.copyOf(spawners), 0, 0, 0, List.of());
    }

    @Override
    boolean stale(ServerLevel level, Plate plate) {
        return !level.getBlockState(plate.plate()).is(Blocks.STONE_PRESSURE_PLATE);
    }

    @Override
    boolean objectiveMet(Plate plate) {
        return plate.heldTicks() >= HOLD_TICKS;
    }

    @Override
    Plate tickDanger(ServerLevel level, BlockPos cellOrigin, Plate plate) {
        List<ServerPlayer> on = playersOn(plate);
        if (on.isEmpty()) {
            if (plate.heldTicks() > 0) {
                int left = (HOLD_TICKS - plate.heldTicks() + 19) / 20;
                Component line = Component.literal("Hold paused: " + left + "s left. Step back on to finish.")
                        .withStyle(ChatFormatting.GOLD);
                for (ServerPlayer player : Ordeals.playersIn(level, plate.cellOrigin())) {
                    player.sendOverlayMessage(line);
                }
            }
            return plate;
        }
        int held = plate.heldTicks() + PERIOD;
        if (held >= HOLD_TICKS) {
            return plate.withHeld(held); // objectiveMet: Ordeals resolves it
        }
        plate = raiseWaves(plate.withHeld(held), on.size());
        int left = (HOLD_TICKS - held + 19) / 20;
        Component line = Component.literal("Hold the plate: " + left + "s until the door opens")
                .withStyle(ChatFormatting.GOLD);
        for (ServerPlayer player : on) {
            player.sendOverlayMessage(line);
        }
        if (held % 100 == 0) {
            plate.level().playSound(null, plate.plate(), SoundEvents.STONE_PRESSURE_PLATE_CLICK_ON,
                    SoundSource.BLOCKS, 0.6f, 0.8f + 0.4f * held / HOLD_TICKS);
        }
        return plate.withHeld(held);
    }

    /**
     * Raises every wave in {@link #WAVES} whose moment this hold has reached
     * and not yet raised, within {@link #ALIVE_CAP} and {@link #SPAWN_CAP}.
     * A wave counts as raised even if the caps cut it short, so a crowded
     * room does not hold it back to spill out later.
     */
    private static Plate raiseWaves(Plate plate, int playersOn) {
        int ticksLeft = HOLD_TICKS - plate.heldTicks();
        int fired = plate.wavesFired();
        int spawned = plate.spawned();
        List<UUID> mobs = new ArrayList<>(alive(plate));
        for (int i = 0; i < WAVES.length; i++) {
            if ((fired & (1 << i)) != 0 || ticksLeft > WAVES[i][0] * 20) {
                continue;
            }
            fired |= 1 << i;
            int count = WAVES[i][1] + (i == 0 ? 0 : playersOn - 1);
            for (int n = 0; n < count && mobs.size() < ALIVE_CAP && spawned < SPAWN_CAP; n++) {
                UUID mob = spawnOne(plate);
                if (mob != null) {
                    mobs.add(mob);
                    spawned++;
                }
            }
        }
        return new Plate(plate.level(), plate.plate(), plate.cellOrigin(), plate.spawners(),
                plate.heldTicks(), fired, spawned, List.copyOf(mobs));
    }

    /** The plate mobs still alive, by the ids the room tracked. */
    private static List<UUID> alive(Plate plate) {
        List<UUID> out = new ArrayList<>();
        for (UUID id : plate.mobs()) {
            Entity entity = plate.level().getEntity(id);
            if (entity != null && entity.isAlive()) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * One plate mob out of the room's trial spawner, picked and placed the way
     * the spawner places its own. The spawner's pick of spot is random and may
     * be blocked, so a few tries; a room without a working trial spawner gets
     * nothing rather than a mob from nowhere.
     */
    private static UUID spawnOne(Plate plate) {
        ServerLevel level = plate.level();
        for (BlockPos pos : plate.spawners()) {
            if (!(level.getBlockEntity(pos) instanceof TrialSpawnerBlockEntity spawner)) {
                continue;
            }
            TrialSpawner trial = spawner.getTrialSpawner();
            for (int attempt = 0; attempt < SPAWN_TRIES; attempt++) {
                Optional<UUID> id = trial.spawnMob(level, pos);
                if (id.isPresent()) {
                    if (level.getEntity(id.get()) instanceof Mob mob) {
                        mob.setPersistenceRequired();
                    }
                    return id.get();
                }
            }
        }
        return null;
    }

    private static List<ServerPlayer> playersOn(Plate plate) {
        BlockPos p = plate.plate();
        AABB box = new AABB(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 0.5, p.getZ() + 1);
        return plate.level().getPlayers(player -> !player.isSpectator() && player.getBoundingBox().intersects(box));
    }

    /** Opens every closed iron door in the cell (the gate is a pair of leaves). */
    @Override
    String resolve(ServerLevel level, BlockPos cellOrigin, Plate plate) {
        boolean opened = false;
        for (int x = 0; x < RoomGeometry.CELL; x++) {
            for (int z = 0; z < RoomGeometry.CELL; z++) {
                BlockPos pos = plate.cellOrigin().offset(x, 1, z);
                BlockState state = level.getBlockState(pos);
                if (state.is(Blocks.IRON_DOOR) && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                        && !state.getValue(DoorBlock.OPEN)) {
                    ((DoorBlock) state.getBlock()).setOpen(null, level, state, pos, true);
                    opened = true;
                }
            }
        }
        return opened ? "The door grinds open." : "The plate holds. The way on is already open.";
    }
}
