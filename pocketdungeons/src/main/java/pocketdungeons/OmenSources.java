package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SculkSensorPhase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M48: the omen source table (spec 5.2, 5.4).
 *
 * <p>{@link Omen} is deliberately pure arithmetic and says so: "the caller
 * tracks the current floor's omen and hands it to {@code clamp} or {@code add}
 * on every source event". This is that caller. Without it {@code record.omen}
 * stays 0 for the life of every run, every completion lands in the low band,
 * and the {@code pressure} field on a room's metadata is read by nothing.
 *
 * <p>Five sources, all polled rather than hooked, which keeps the mod's
 * one-mixin budget intact:
 *
 * <ul>
 *   <li><strong>Dwell.</strong> Time a player spends in an unsolved cell. A
 *       cleared cell and the staging room are free (spec 5.4).</li>
 *   <li><strong>Sensor pulses.</strong> Sculk sensors going active, counted on
 *       the rising edge. Frame Lock's pots and Pot Room's forty.</li>
 *   <li><strong>Shrieks.</strong> Sculk shriekers, one omen each.</li>
 *   <li><strong>The Ominous Bargain.</strong> Once, when its reward is taken.</li>
 *   <li><strong>Barred Vault.</strong> Once, when its reward is taken.</li>
 * </ul>
 *
 * <p>Cells are armed at stamp time from {@code DungeonRoomMeta.pressure}, the
 * same shape as {@link Locks}: scan the cell once, remember the positions, then
 * poll them. Rooms declaring {@code pressure: "local"} arm nothing here; local
 * pressure is the room's own business (rising lava, a closing bridge) and costs
 * no run-level omen.
 */
final class OmenSources {

    /** One armed cell: what to poll, and what has already been counted. */
    private static final class Armed {
        final List<BlockPos> sensors = new ArrayList<>();
        final List<BlockPos> shriekers = new ArrayList<>();
        /** The spur reward container, for the two one-shot sources. */
        BlockPos spurChest;
        /** Which one-shot this cell owns, or null. */
        String spurKind;
        boolean spurFired;
        /** Rising-edge memory, parallel to the lists above. */
        final Map<BlockPos, Boolean> wasActive = new HashMap<>();
        /** Pulses counted but not yet worth an omen: the formula is one per five. */
        int pendingPulses;
    }

    /** Per-player dwell, reset whenever they change cell. */
    private static final class Dwell {
        BlockPos cell;
        int seconds;
        int awarded;
    }

    private static final Map<BlockPos, Armed> ARMED = new LinkedHashMap<>();
    private static final Map<UUID, Dwell> DWELL = new HashMap<>();

    /** One poll per second. Dwell is measured in 90 second steps; this is ample. */
    private static final int PERIOD = 20;

    private OmenSources() {}

    /** Wires the poll. Call once from {@code onInitialize}. */
    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % PERIOD == 0) {
                tick(server);
            }
        });
    }

    /**
     * Arms the omen sources in the cell at {@code origin} from its room
     * metadata. Only {@code pressure: "omen"} arms anything: a room that says
     * nothing about pressure, or says {@code local}, contributes no run-level
     * omen and is not polled.
     */
    static void arm(ServerLevel level, BlockPos origin, DungeonRoomMeta meta) {
        if (meta == null || !"omen".equals(meta.pressure)) {
            return;
        }
        Armed armed = new Armed();
        for (int x = 0; x < RoomGeometry.CELL; x++) {
            for (int z = 0; z < RoomGeometry.CELL; z++) {
                for (int y = 1; y <= RoomGeometry.CEILING_Y; y++) {
                    BlockPos pos = origin.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.is(Blocks.SCULK_SENSOR) || state.is(Blocks.CALIBRATED_SCULK_SENSOR)) {
                        armed.sensors.add(pos.immutable());
                        armed.wasActive.put(pos.immutable(), sensorActive(state));
                    } else if (state.is(Blocks.SCULK_SHRIEKER)) {
                        armed.shriekers.add(pos.immutable());
                        armed.wasActive.put(pos.immutable(), shrieking(state));
                    } else if (state.is(Blocks.CHEST) && armed.spurChest == null
                            && isSpur(meta.content)) {
                        armed.spurChest = pos.immutable();
                        armed.spurKind = meta.content;
                    }
                }
            }
        }
        if (armed.sensors.isEmpty() && armed.shriekers.isEmpty() && armed.spurChest == null) {
            return;
        }
        ARMED.put(origin.immutable(), armed);
    }

    private static boolean isSpur(String content) {
        return "ominous_bargain".equals(content) || "barred_vault".equals(content);
    }

    /** Drops the sources for one cell. Called from teardown alongside {@link Locks#clear}. */
    static void clear(BlockPos cellOrigin) {
        ARMED.remove(cellOrigin);
    }

    /** Forgets a player's dwell, for a logout or a return to the safe room. */
    static void forget(UUID player) {
        DWELL.remove(player);
    }

    private static void tick(MinecraftServer server) {
        for (InstanceRecord record : new ArrayList<>(InstanceRegistry.bySlot.values())) {
            if (record.lingering || record.layout == null) {
                continue;
            }
            ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
            if (level == null) {
                return;
            }
            pollCells(level, record);
            pollDwell(server, level, record);
        }
    }

    /** Sensors, shriekers and the two spur one-shots, on the rising edge. */
    private static void pollCells(ServerLevel level, InstanceRecord record) {
        for (BlockPos origin : record.layout.geometry().cellOrigins()) {
            Armed armed = ARMED.get(origin);
            if (armed == null) {
                continue;
            }
            int pulses = 0;
            for (BlockPos pos : armed.sensors) {
                boolean now = sensorActive(level.getBlockState(pos));
                if (now && !Boolean.TRUE.equals(armed.wasActive.get(pos))) {
                    pulses++;
                }
                armed.wasActive.put(pos, now);
            }
            if (pulses > 0) {
                armed.pendingPulses += pulses;
                // The formula is +1 omen per 5 pulses, so bank the remainder
                // rather than rounding every poll down to nothing.
                int gained = Omen.sensorContribution(armed.pendingPulses);
                if (gained > 0) {
                    add(record, gained);
                    armed.pendingPulses -= gained * 5;
                }
            }
            for (BlockPos pos : armed.shriekers) {
                boolean now = shrieking(level.getBlockState(pos));
                if (now && !Boolean.TRUE.equals(armed.wasActive.get(pos))) {
                    add(record, Omen.shriekContribution(1));
                }
                armed.wasActive.put(pos, now);
            }
            if (armed.spurChest != null && !armed.spurFired && spurTaken(level, armed.spurChest)) {
                armed.spurFired = true;
                if ("ominous_bargain".equals(armed.spurKind)) {
                    // The bargain replaces the floor's omen, it does not stack
                    // onto it. Omen.bargainOmen's own javadoc is explicit.
                    record.omen = Omen.set(Omen.bargainOmen());
                } else {
                    // Barred Vault is relief: a negative contribution.
                    add(record, Omen.barredVaultContribution());
                }
            }
        }
    }

    /**
     * Dwell, per player, per cell. {@link Omen#dwellContribution} is cumulative
     * for a given dwell time, so only the increase since the last poll is added.
     */
    private static void pollDwell(MinecraftServer server, ServerLevel level, InstanceRecord record) {
        for (UUID id : record.members.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null || player.level() != level) {
                continue;
            }
            BlockPos at = player.blockPosition();
            PlanCell cell = record.layout.geometry().cellAt(at);
            Dwell dwell = DWELL.computeIfAbsent(id, k -> new Dwell());
            if (cell == null) {
                dwell.cell = null;
                dwell.seconds = 0;
                dwell.awarded = 0;
                continue;
            }
            BlockPos origin = record.layout.geometry().cellOrigin(cell);
            if (!origin.equals(dwell.cell)) {
                dwell.cell = origin.immutable();
                dwell.seconds = 0;
                dwell.awarded = 0;
            }
            dwell.seconds += PERIOD / 20;
            boolean staging = origin.equals(record.stagingCellOrigin);
            int total = Omen.dwellContribution(dwell.seconds, unsolved(level, origin), staging);
            if (total > dwell.awarded) {
                add(record, total - dwell.awarded);
                dwell.awarded = total;
            }
        }
    }

    /**
     * Whether the cell's situation is still unsolved, which is what spec 5.4
     * makes dwelling cost. A cell with an armed lock has not been opened, and a
     * cell with an uncleared trial spawner has not been fought.
     */
    private static boolean unsolved(ServerLevel level, BlockPos origin) {
        return Locks.isArmed(origin) || TrialContent.hasActiveSpawner(level, origin);
    }

    /**
     * Adds to the current floor's omen, clamped per floor (spec 5.2).
     * Contributions may be negative: clearing a Barred Vault is relief, not
     * pressure, so this must not filter them out.
     */
    private static void add(InstanceRecord record, int contribution) {
        if (contribution == 0) {
            return;
        }
        record.omen = Omen.clamp(Omen.add(record.omen, contribution));
    }

    private static boolean sensorActive(BlockState state) {
        return state.hasProperty(BlockStateProperties.SCULK_SENSOR_PHASE)
                && state.getValue(BlockStateProperties.SCULK_SENSOR_PHASE) == SculkSensorPhase.ACTIVE;
    }

    private static boolean shrieking(BlockState state) {
        return state.hasProperty(BlockStateProperties.SHRIEKING)
                && state.getValue(BlockStateProperties.SHRIEKING);
    }

    /** A spur pays its omen when its reward leaves the container. */
    private static boolean spurTaken(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof Container container && container.isEmpty();
    }
}
