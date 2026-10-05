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
 * on every source event". This is that caller. Without it {@code record.interval.omen}
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
        /** Pulses counted but not yet worth an omen: the formula is one per five ({@link SculkOmen}). */
        int pendingPulses;
        /** Ancient City: every sculk block was armed and a sensor pulse is worth a whole omen. */
        boolean ancientCity;
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
        arm(level, origin, meta, null);
    }

    /**
     * Dungeon structure W7a: {@link #arm(ServerLevel, BlockPos, DungeonRoomMeta)} for a floor of
     * {@code themeId}. On an Ancient City floor ({@link SculkOmen#armsAllSculk}) every sculk sensor
     * and shrieker in the cell is armed whatever the room's {@code pressure} says, and a sensor pulse
     * is worth a whole omen. Spur chests stay owned by rooms that declare {@code pressure: "omen"}.
     */
    static void arm(ServerLevel level, BlockPos origin, DungeonRoomMeta meta, String themeId) {
        boolean ancient = SculkOmen.armsAllSculk(themeId);
        boolean pressure = meta != null && "omen".equals(meta.pressure);
        if (meta == null || (!pressure && !ancient)) {
            return;
        }
        Armed armed = new Armed();
        armed.ancientCity = ancient;
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
                    } else if (pressure && state.is(Blocks.CHEST) && armed.spurChest == null
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
            if (record.layout == null) {
                continue;
            }
            ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
            if (level == null) {
                return;
            }
            pollCells(server, level, record);
            pollDwell(server, level, record);
        }
    }

    /** Sensors, shriekers and the two spur one-shots, on the rising edge. */
    private static void pollCells(MinecraftServer server, ServerLevel level, InstanceRecord record) {
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
                int gained = SculkOmen.omenFromPulses(armed.pendingPulses, armed.ancientCity);
                if (gained > 0) {
                    add(server, record, gained, Omen.Source.SENSOR, origin);
                    armed.pendingPulses = SculkOmen.remainingPulses(armed.pendingPulses, gained, armed.ancientCity);
                }
            }
            for (BlockPos pos : armed.shriekers) {
                boolean now = shrieking(level.getBlockState(pos));
                if (now && !Boolean.TRUE.equals(armed.wasActive.get(pos))) {
                    add(server, record, Omen.shriekContribution(1), Omen.Source.SHRIEK, pos);
                }
                armed.wasActive.put(pos, now);
            }
            if (armed.spurChest != null && !armed.spurFired && spurTaken(level, armed.spurChest)) {
                armed.spurFired = true;
                if ("ominous_bargain".equals(armed.spurKind)) {
                    // The bargain replaces the floor's omen, it does not stack
                    // onto it. Omen.bargainOmen's own javadoc is explicit.
                    int before = record.interval.omen;
                    record.interval.omen = Omen.set(Omen.bargainOmen());
                    if (record.interval.omen > before) {
                        OmenBar.omenRose(server, record, Omen.Source.BARGAIN, record.interval.omen,
                                record.interval.omen - before, armed.spurChest);
                    }
                } else {
                    // Barred Vault is relief: a negative contribution.
                    add(server, record, Omen.barredVaultContribution(), null, armed.spurChest);
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
                add(server, record, total - dwell.awarded, Omen.Source.DWELL, at);
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
     * pressure, so this must not filter them out. A rise is scaled by the
     * zone's {@link ZoneRules#omenScale} and announced on the omen bar as
     * coming from {@code source}; relief ({@code source} null) is unscaled
     * and silent, and so is a rise the per-floor clamp swallowed.
     */
    /** Consumables used so far by each Silenced player; {@code silencedConsumablesPerOmen} of them raise the omen once. */
    private static final Map<UUID, Integer> SILENCED_USES = new HashMap<>();

    /**
     * A Silenced member used a consumable (playtest 2026-10-02-1: Silence no
     * longer blocks consumables; using one costs omen). Every
     * {@code silencedConsumablesPerOmen}-th use raises the omen by one.
     */
    static void silencedUse(MinecraftServer server, InstanceRecord record, UUID player) {
        int per = PocketDungeonsConfig.silencedConsumablesPerOmen();
        int uses = SILENCED_USES.merge(player, 1, Integer::sum);
        if (uses >= per) {
            SILENCED_USES.put(player, 0);
            add(server, record, 1, Omen.Source.SILENCE, null);
        }
    }

    /** Takes the given omen off the floor, silently (the fountain cleanse). */
    static void relieve(MinecraftServer server, InstanceRecord record, int amount) {
        add(server, record, -Math.abs(amount), null, null);
        OmenBar.sync(server, record);
    }

    private static void add(MinecraftServer server, InstanceRecord record, int contribution,
                            Omen.Source source, BlockPos at) {
        if (source != null) {
            contribution = ZoneRules.of(record).scaleOmen(contribution);
        }
        if (contribution == 0) {
            return;
        }
        int before = record.interval.omen;
        record.interval.omen = Omen.clamp(Omen.add(record.interval.omen, contribution));
        if (source != null && record.interval.omen > before) {
            OmenBar.omenRose(server, record, source, record.interval.omen, record.interval.omen - before, at);
        }
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
    static boolean spurTaken(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof Container container && container.isEmpty();
    }
}
