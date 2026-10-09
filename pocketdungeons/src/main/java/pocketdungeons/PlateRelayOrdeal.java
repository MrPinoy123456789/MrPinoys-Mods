package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawner;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * The Plate Relay (the room id stays {@code hold_the_plate}), which replaced Hold the Plate after
 * the 2026-10-07-2 verdict that standing on one plate was "kind of lame".
 *
 * <ul>
 *   <li>Objective: charge {@link #needed} plates. Four stone plates sit in the room's corners and
 *       exactly one is lit (a sea lantern under it, particles over it). Stand on the lit plate
 *       for {@link #CHARGE_SECONDS} seconds and it is charged; the light jumps to another corner.</li>
 *   <li>Danger: every charge raises a wave out of the room's own trial spawner, and a light left
 *       uncharged for {@link #LIVE_SECONDS} seconds moves on by itself and raises a mob, so
 *       dawdling is punished by more fighting rather than by a fail state. Whack a mole: the
 *       party chases the light across the room while the room's mobs chase the party.</li>
 *   <li>Resolution: the last charge opens the exit's iron door.</li>
 * </ul>
 *
 * <p>Costs no items and needs nothing from the player's pack, in line with the haul model's
 * scarcity: only time, positioning and the room's own mob table are spent. More players raise
 * the charges needed ({@link #needed}) so a party cannot split and finish at solo speed, and
 * the waves grow with the party. The template carries a single centre plate from the old
 * design; {@link #arm} clears it and places the four corner plates (repair at stamp, like
 * the old toll rooms), and a plate a player breaks is put back on the next tick.
 *
 * <p>Waves reuse the old design's machinery: they come out of the room's trial spawner through
 * {@link TrialSpawner#spawnMob}, never join its tally, and are held by {@link #ALIVE_CAP} and
 * {@link #SPAWN_CAP}.
 */
final class PlateRelayOrdeal extends Ordeal<PlateRelayOrdeal.Relay> {

    static final PlateRelayOrdeal INSTANCE = new PlateRelayOrdeal();

    /** Seconds a player stands on the lit plate to charge it. */
    static final int CHARGE_SECONDS = 3;
    /** Seconds the light waits, uncharged and unstood on, before it moves on and costs a mob. */
    static final int LIVE_SECONDS = 15;
    /** Charges a solo player needs. */
    static final int BASE_CHARGES = 5;
    /** The most charges any party needs. */
    static final int MAX_CHARGES = 8;
    /** Ticks between evaluations. */
    private static final int PERIOD = 10;
    private static final int CHARGE_TICKS = CHARGE_SECONDS * 20;
    private static final int LIVE_TICKS = LIVE_SECONDS * 20;
    /** Mobs alive at once, beyond which a wave is cut short. */
    static final int ALIVE_CAP = 6;
    /** Mobs a room raises over its whole life. */
    static final int SPAWN_CAP = 16;
    private static final int SPAWN_TRIES = 6;

    /** Corner plates, as offsets from the cell origin at y=1; symmetric, so rotation never matters. */
    static final int[][] PLATE_SPOTS = {{3, 3}, {12, 3}, {12, 12}, {3, 12}};

    /**
     * One armed room: its plates, the cell's trial spawners, which plate is lit, how long it has
     * been charged and how long it has waited, charges done, charges needed, mobs raised in all,
     * and the ones still tracked.
     */
    record Relay(ServerLevel level, BlockPos cellOrigin, List<BlockPos> plates, List<BlockPos> spawners,
                 int live, int chargeTicks, int waitTicks, int charges, int needed, int spawned,
                 List<UUID> mobs) {
        Relay with(int live, int chargeTicks, int waitTicks, int charges, int needed, int spawned,
                   List<UUID> mobs) {
            return new Relay(level, cellOrigin, plates, spawners, live, chargeTicks, waitTicks, charges,
                    needed, spawned, mobs);
        }
    }

    private PlateRelayOrdeal() {
        super("hold_the_plate", PERIOD, "charge the lit plate in each corner, " + BASE_CHARGES + " or more times",
                "waves raised by every charge and by a light left waiting, from the room's spawner",
                "the last charge opens the exit");
    }

    // ---- pure rules, tested without a world --------------------------------------

    /** Charges a party of {@code players} needs: one more per extra player, capped. */
    static int needed(int players) {
        return Math.min(MAX_CHARGES, BASE_CHARGES + Math.max(0, players - 1));
    }

    /** The plate the light jumps to: never the one it leaves, picked from the cell and the count. */
    static int nextLive(int live, int plateCount, long cellKey, int charges) {
        Random rng = new Random(cellKey * 31L + charges * 17L + live);
        return (live + 1 + rng.nextInt(plateCount - 1)) % plateCount;
    }

    /** Mobs one charge raises: one, plus one for every two extra players in the room, and a second from the third charge on. */
    static int waveSize(int playersInRoom, int chargesDone) {
        return 1 + Math.max(0, playersInRoom - 1) / 2 + (chargesDone >= 3 ? 1 : 0);
    }

    // ---- the Ordeal ----------------------------------------------------------------

    @Override
    Relay arm(ServerLevel level, BlockPos cellOrigin) {
        List<BlockPos> spawners = new ArrayList<>();
        for (int x = 0; x < RoomGeometry.CELL; x++) {
            for (int z = 0; z < RoomGeometry.CELL; z++) {
                for (int y = 0; y <= RoomGeometry.CEILING_Y; y++) {
                    BlockPos pos = cellOrigin.offset(x, y, z);
                    if (level.getBlockEntity(pos) instanceof TrialSpawnerBlockEntity) {
                        spawners.add(pos.immutable());
                    }
                    if (y == 1 && level.getBlockState(pos).is(Blocks.STONE_PRESSURE_PLATE)) {
                        RoomBuilder.set(level, pos, RoomBuilder.AIR); // the old centre plate
                    }
                }
            }
        }
        List<BlockPos> plates = new ArrayList<>();
        for (int[] spot : PLATE_SPOTS) {
            plates.add(cellOrigin.offset(spot[0], 1, spot[1]).immutable());
        }
        Relay relay = new Relay(level, cellOrigin.immutable(), List.copyOf(plates), List.copyOf(spawners),
                0, 0, 0, 0, BASE_CHARGES, 0, List.of());
        paint(relay);
        return relay;
    }

    @Override
    boolean stale(ServerLevel level, Relay relay) {
        // The cell is gone when its floor is: plates are re-placed if broken, so they cannot say.
        return level.getBlockState(relay.cellOrigin().offset(8, 0, 8)).isAir();
    }

    @Override
    boolean objectiveMet(Relay relay) {
        return relay.charges() >= relay.needed();
    }

    @Override
    Relay tickDanger(ServerLevel level, BlockPos cellOrigin, Relay relay) {
        List<ServerPlayer> inRoom = Ordeals.playersIn(level, relay.cellOrigin());
        int needed = Math.max(relay.needed(), needed(inRoom.size()));
        paint(relay);

        BlockPos lit = relay.plates().get(relay.live());
        level.sendParticles(ParticleTypes.END_ROD, lit.getX() + 0.5, lit.getY() + 0.4, lit.getZ() + 0.5,
                3, 0.25, 0.3, 0.25, 0.01);

        boolean standing = playerOn(level, lit);
        int charge = relay.chargeTicks();
        int wait = relay.waitTicks();
        int charges = relay.charges();
        int live = relay.live();
        int spawned = relay.spawned();
        List<UUID> mobs = new ArrayList<>(alive(relay));

        if (standing) {
            charge += PERIOD;
            wait = 0;
            if (charge >= CHARGE_TICKS) {
                charges++;
                charge = 0;
                Raised raised = raise(relay, mobs, spawned, waveSize(inRoom.size(), charges));
                spawned = raised.spawned();
                level.playSound(null, lit, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 0.8f, 1.0f + 0.1f * charges);
                if (charges < needed) {
                    live = nextLive(live, relay.plates().size(), relay.cellOrigin().asLong(), charges);
                    tell(inRoom, charges + " of " + needed + " charged. The light moves.", ChatFormatting.AQUA);
                }
            } else {
                tell(inRoom, "Charging: " + ((CHARGE_TICKS - charge + 19) / 20) + "s (" + charges + " of "
                        + needed + ")", ChatFormatting.GOLD);
            }
        } else {
            wait += PERIOD;
            if (wait >= LIVE_TICKS) {
                wait = 0;
                charge = 0;
                live = nextLive(live, relay.plates().size(), relay.cellOrigin().asLong(), charges + 7);
                spawned = raise(relay, mobs, spawned, 1).spawned();
                level.playSound(null, lit, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 0.8f, 0.8f);
                tell(inRoom, "The light moves on, and something answers it.", ChatFormatting.RED);
            } else if (wait % 100 == 0 || wait == PERIOD) {
                tell(inRoom, "Stand on the lit plate: " + charges + " of " + needed + " charged.",
                        ChatFormatting.GOLD);
            }
        }
        return relay.with(live, charge, wait, charges, needed, spawned, List.copyOf(mobs));
    }

    /** Opens every closed iron door in the cell (the gate is a pair of leaves) and lights every plate. */
    @Override
    String resolve(ServerLevel level, BlockPos cellOrigin, Relay relay) {
        for (BlockPos plate : relay.plates()) {
            RoomBuilder.set(level, plate.below(), Blocks.SEA_LANTERN.defaultBlockState());
        }
        boolean opened = false;
        for (int x = 0; x < RoomGeometry.CELL; x++) {
            for (int z = 0; z < RoomGeometry.CELL; z++) {
                BlockPos pos = relay.cellOrigin().offset(x, 1, z);
                BlockState state = level.getBlockState(pos);
                if (state.is(Blocks.IRON_DOOR) && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                        && !state.getValue(DoorBlock.OPEN)) {
                    ((DoorBlock) state.getBlock()).setOpen(null, level, state, pos, true);
                    opened = true;
                }
            }
        }
        return opened ? "The last plate is lit. The door grinds open." : "The plates are lit. The way on is already open.";
    }

    // ---- helpers -------------------------------------------------------------------

    /** Keeps the room as the state says: every plate in place, the lit one on a sea lantern, the rest on blackstone. */
    private static void paint(Relay relay) {
        ServerLevel level = relay.level();
        for (int i = 0; i < relay.plates().size(); i++) {
            BlockPos plate = relay.plates().get(i);
            if (!level.getBlockState(plate).is(Blocks.STONE_PRESSURE_PLATE)) {
                RoomBuilder.set(level, plate, Blocks.STONE_PRESSURE_PLATE.defaultBlockState());
            }
            BlockState under = i == relay.live() ? Blocks.SEA_LANTERN.defaultBlockState()
                    : Blocks.BLACKSTONE.defaultBlockState();
            if (!level.getBlockState(plate.below()).is(under.getBlock())) {
                RoomBuilder.set(level, plate.below(), under);
            }
        }
    }

    private static boolean playerOn(ServerLevel level, BlockPos plate) {
        AABB box = new AABB(plate.getX(), plate.getY(), plate.getZ(), plate.getX() + 1, plate.getY() + 0.5,
                plate.getZ() + 1);
        return !level.getPlayers(p -> !p.isSpectator() && p.getBoundingBox().intersects(box)).isEmpty();
    }

    private static void tell(List<ServerPlayer> players, String text, ChatFormatting colour) {
        Component line = Component.literal(text).withStyle(colour);
        for (ServerPlayer player : players) {
            player.sendOverlayMessage(line);
        }
    }

    private record Raised(int spawned) {}

    /** Raises up to {@code count} mobs from the room's spawner within the caps; {@code mobs} gains their ids. */
    private static Raised raise(Relay relay, List<UUID> mobs, int spawned, int count) {
        for (int n = 0; n < count; n++) {
            if (mobs.size() >= ALIVE_CAP || spawned >= SPAWN_CAP) {
                break;
            }
            UUID mob = spawnOne(relay);
            if (mob != null) {
                mobs.add(mob);
                spawned++;
            }
        }
        return new Raised(spawned);
    }

    private static List<UUID> alive(Relay relay) {
        List<UUID> out = new ArrayList<>();
        for (UUID id : relay.mobs()) {
            Entity entity = relay.level().getEntity(id);
            if (entity != null && entity.isAlive()) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * One mob out of the room's trial spawner, picked and placed the way the spawner places its
     * own. The spawner's pick of spot is random and may be blocked, so a few tries; a room without
     * a working trial spawner gets nothing rather than a mob from nowhere.
     */
    private static UUID spawnOne(Relay relay) {
        ServerLevel level = relay.level();
        for (BlockPos pos : relay.spawners()) {
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
}
