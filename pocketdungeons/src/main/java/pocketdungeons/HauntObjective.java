package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The {@link FloorObjective#HAUNT} floor (lighthouse shape): two beacons stand
 * in the floor's branch cells, each a heavy weighted pressure plate beside an
 * unlit copper bulb. A member standing on a plate for
 * {@code beaconChargeSeconds} without stepping off lights its beacon: the bulb
 * burns, the floor answers, and {@link Haunts#banishFor} drives the hunting
 * keeper off the floor for {@code beaconBanishSeconds}. The pad holds until
 * every beacon the quota asks for is lit.
 *
 * <p>Placement runs under the generic fallback rule: a floor with no cell that
 * can host a beacon arms a fallback state and reads as an ordinary spawner
 * floor everywhere else. A floor that places one beacon is held to a quota of
 * one ({@link FloorObjectiveRules#effectiveQuota}).
 */
final class HauntObjective extends FloorObjectives.ObjectiveState {

    /** Beacons a hunt floor asks the party to light before the pad opens. */
    static final int BEACONS = 2;

    /** The set-block flags every fixture swap in this mod uses (see {@code TrialContent.FLAGS}). */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS
            | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

    /** Blocks from a plate's feet centre within which a standing member charges its beacon. */
    private static final double PLATE_REACH = 0.7;

    /** The copper bulb a beacon carries, unlit until charged (waxed, the way the room kits place it). */
    private static final BlockState BULB_UNLIT = Blocks.COPPER_BULB.waxed().unaffected().defaultBlockState()
            .setValue(CopperBulbBlock.LIT, false);

    /**
     * One beacon: the plate a member stands on and the bulb that lights when the charge fills. Positions are
     * fixtures; {@code chargingSince} is the server tick the current unbroken stand began, or -1.
     */
    static final class Beacon {
        final BlockPos plate;
        final BlockPos bulb;
        long chargingSince = -1;
        boolean lit;

        Beacon(BlockPos plate, BlockPos bulb) {
            this.plate = plate;
            this.bulb = bulb;
        }
    }

    /** The level the floor stands in; captured at arm so the tick needs no dimension lookup. */
    private final ServerLevel level;

    /** The placed beacons, in cell order; each lights once. */
    final List<Beacon> beacons = new ArrayList<>();

    /** The beacons the quota demands: {@link #BEACONS} clamped to what was placed. */
    int quota;

    /** Beacons lit so far. */
    int beaconsLit;

    private HauntObjective(ServerLevel level) {
        super(FloorObjective.HAUNT, false);
        this.level = level;
    }

    /**
     * Places the floor's beacons on a freshly stamped floor: one per cell, branch cells (off the critical
     * path) first, then any other cell that is not the entrance or the terminal. A cell that cannot host a
     * plate and a bulb is skipped; a floor with no beacon at all arms a fallback state.
     */
    static HauntObjective arm(ServerLevel level, InstanceRecord record, DungeonPlan plan) {
        HauntObjective state = new HauntObjective(level);
        if (plan == null || record.layout == null) {
            state.fallback = true;
            return state;
        }
        placeBeacons(level, record, plan, state, level.getRandom());
        state.quota = FloorObjectiveRules.effectiveQuota(BEACONS, state.beacons.size());
        if (state.beacons.isEmpty()) {
            state.fallback = true;
        }
        return state;
    }

    /** The cells a beacon may stand in: branch cells first, then the rest, never the entrance or terminal. */
    private static void placeBeacons(ServerLevel level, InstanceRecord record, DungeonPlan plan,
                                     HauntObjective state, RandomSource random) {
        List<PlanCell> branches = new ArrayList<>();
        List<PlanCell> rest = new ArrayList<>();
        for (PlanCell cell : plan.cells()) {
            if (cell.equals(plan.entrance()) || cell.equals(plan.terminal())) {
                continue;
            }
            (plan.criticalPath() != null && plan.criticalPath().contains(cell) ? rest : branches).add(cell);
        }
        Comparator<PlanCell> byCell = Comparator.comparingInt(PlanCell::x).thenComparingInt(PlanCell::z);
        branches.sort(byCell);
        rest.sort(byCell);
        List<PlanCell> order = new ArrayList<>(branches);
        order.addAll(rest);

        for (PlanCell cell : order) {
            if (state.beacons.size() >= BEACONS) {
                return;
            }
            BlockPos cellOrigin = record.layout.geometry().cellOrigin(cell);
            Set<BlockPos> taken = new HashSet<>();
            taken.add(record.layout.geometry().cellCentre(cell));
            BlockPos plate = MineGuardians.freeSpotInCell(level, cellOrigin, taken, random);
            if (plate == null) {
                continue;
            }
            BlockPos bulb = MineGuardians.freeSpotInCell(level, cellOrigin, taken, random);
            if (bulb == null) {
                continue;
            }
            level.setBlock(plate, Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE.defaultBlockState(), FLAGS);
            level.setBlock(bulb, BULB_UNLIT, FLAGS);
            state.fixtures.add(plate);
            state.fixtures.add(bulb);
            state.beacons.add(new Beacon(plate.immutable(), bulb.immutable()));
        }
    }

    /**
     * One watch tick of the beacon charge: any member standing on an unlit plate fills it; stepping off
     * resets that beacon alone. A filled charge lights the bulb and banishes the keeper.
     */
    @Override
    void tick(MinecraftServer server, InstanceRecord record) {
        if (fallback || beaconsLit >= quota) {
            return;
        }
        long now = server.getTickCount();
        long chargeTicks = PocketDungeonsConfig.beaconChargeSeconds() * 20L;
        for (Beacon beacon : beacons) {
            if (beacon.lit) {
                continue;
            }
            if (someoneOn(server, record, beacon.plate)) {
                if (beacon.chargingSince < 0) {
                    beacon.chargingSince = now;
                } else if (now - beacon.chargingSince >= chargeTicks) {
                    light(record, beacon);
                }
            } else {
                beacon.chargingSince = -1;
            }
        }
    }

    /** Whether any living member is standing on {@code plate} (feet in or beside the block). */
    private boolean someoneOn(MinecraftServer server, InstanceRecord record, BlockPos plate) {
        Vec3 centre = Vec3.atBottomCenterOf(plate);
        for (UUID id : record.members.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null && player.level() == level && player.isAlive() && !player.isSpectator()
                    && (player.blockPosition().equals(plate)
                            || player.position().distanceToSqr(centre) <= PLATE_REACH * PLATE_REACH)) {
                return true;
            }
        }
        return false;
    }

    /** A charged beacon lights: the bulb burns, the count rises and the keeper is driven off for a while. */
    private void light(InstanceRecord record, Beacon beacon) {
        beacon.lit = true;
        beacon.chargingSince = -1;
        beaconsLit++;
        BlockState bulb = level.getBlockState(beacon.bulb);
        if (bulb.hasProperty(CopperBulbBlock.LIT)) {
            level.setBlock(beacon.bulb, bulb.setValue(CopperBulbBlock.LIT, true), FLAGS);
        }
        level.playSound(null, beacon.plate, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 0.9f, 1.2f);
        Haunts.banishFor(record, PocketDungeonsConfig.beaconBanishSeconds());
    }

    @Override
    String padRefusal() {
        if (fallback || beaconsLit >= quota) {
            return null;
        }
        int left = quota - beaconsLit;
        return "The lamp is dark. Light " + left + " more beacon" + (left == 1 ? "." : "s.");
    }

    @Override
    String progressLine() {
        if (fallback) {
            return null;
        }
        return "Beacons " + beaconsLit + "/" + quota;
    }
}
