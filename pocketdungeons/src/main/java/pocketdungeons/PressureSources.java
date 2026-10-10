package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
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
 * J3 (plan 2026-10-06-2): the floor's pressure triggers, reworked off omen.
 * Death is the only thing that raises omen; everything else here answers in
 * the dungeon's own coin: a lootless wave after the player, darkness on a
 * shriek, a harder floor for the Ominous Bargain.
 *
 * <p>Five triggers, all polled rather than hooked, which keeps the mod's
 * one-mixin budget intact:
 *
 * <ul>
 *   <li><strong>Dwell.</strong> Time a player spends in an unsolved cell: a
 *       wave per 90 seconds past the first 60. A cleared cell and the
 *       staging room are free (spec 5.4).</li>
 *   <li><strong>Sensor pulses.</strong> Sculk sensors going active, counted
 *       on the rising edge: a wave per five pulses, or per pulse on an
 *       Ancient City floor ({@link SculkOmen}), where the final floor's
 *       fourth pulse wakes the Warden.</li>
 *   <li><strong>Shrieks.</strong> Sculk shriekers: darkness, then a wave.</li>
 *   <li><strong>The Ominous Bargain.</strong> Once, when its reward is taken:
 *       +2 level for the rest of the floor.</li>
 *   <li><strong>Barred Vault.</strong> Once, when its reward is taken: relief,
 *       a life back.</li>
 * </ul>
 *
 * <p>Cells are armed at stamp time from {@code DungeonRoomMeta.pressure}, the
 * same shape as {@link Locks}: scan the cell once, remember the positions, then
 * poll them. Rooms declaring {@code pressure: "local"} arm nothing here; local
 * pressure is the room's own business (rising lava, a closing bridge) and costs
 * nothing run-level.
 */
final class PressureSources {

    /** One armed cell: what to poll, and what has already been counted. */
    private static final class Armed {
        final List<BlockPos> sensors = new ArrayList<>();
        final List<BlockPos> shriekers = new ArrayList<>();
        /** The spur reward container, for the two one-shot triggers. */
        BlockPos spurChest;
        /** Which one-shot this cell owns, or null. */
        String spurKind;
        boolean spurFired;
        /** Rising-edge memory, parallel to the lists above. */
        final Map<BlockPos, Boolean> wasActive = new HashMap<>();
        /** The Heard meter: sensor pulses since the room last answered ({@link SculkOmen#heardAfter}). */
        int heard;
        /** Whether the room has ever answered (a full meter or a shriek), which forfeits the unheard pay. */
        boolean everHeard;
        /** Whether the room held a spawner when it was armed, and whether it has paid for being crossed unheard. */
        boolean hadSpawner;
        boolean unheardPaid;
        /** The Barred Vault's vault block, for the one-shot relief when it pays out. */
        BlockPos vault;
        boolean vaultFired;
        /** Ancient City: every sculk block was armed and every pulse answers. */
        boolean ancientCity;
    }

    /** Per-player dwell, reset whenever they change cell. */
    private static final class Dwell {
        BlockPos cell;
        int seconds;
        int wavesPaid;
    }

    private static final Map<BlockPos, Armed> ARMED = new LinkedHashMap<>();
    private static final Map<UUID, Dwell> DWELL = new HashMap<>();

    /** One poll per second. Dwell is measured in 90 second steps; this is ample. */
    private static final int PERIOD = 20;

    /** The Ominous Bargain's price: +2 floor level for the rest of the floor (J3). */
    static final int BARGAIN_LEVELS = 2;

    private PressureSources() {}

    /** Wires the poll. Call once from {@code onInitialize}. */
    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % PERIOD == 0) {
                tick(server);
            }
        });
    }

    /**
     * Arms the pressure triggers in the cell at {@code origin} from its room
     * metadata. Only {@code pressure: "omen"} arms anything: a room that says
     * nothing about pressure, or says {@code local}, triggers nothing and is
     * not polled.
     */
    static void arm(ServerLevel level, BlockPos origin, DungeonRoomMeta meta) {
        arm(level, origin, meta, null);
    }

    /**
     * {@link #arm(ServerLevel, BlockPos, DungeonRoomMeta)} for a floor of
     * {@code themeId}. On an Ancient City floor ({@link SculkOmen#armsAllSculk}) every sculk sensor
     * and shrieker in the cell is armed whatever the room's {@code pressure} says, and every
     * sensor pulse answers. Spur chests stay owned by rooms that declare {@code pressure: "omen"}.
     */
    static void arm(ServerLevel level, BlockPos origin, DungeonRoomMeta meta, String themeId) {
        arm(level, origin, meta, themeId, false);
    }

    /** {@link #arm(ServerLevel, BlockPos, DungeonRoomMeta, String)} on a floor that is, or is not, ominous. */
    static void arm(ServerLevel level, BlockPos origin, DungeonRoomMeta meta, String themeId, boolean ominousFloor) {
        boolean ancient = SculkOmen.armsAllSculk(themeId);
        boolean pressure = meta != null && "omen".equals(meta.pressure);
        if (meta == null) {
            return;
        }
        // The Barred Vault is a real vault block (design pass 2026-10-09, Q6): it opens with the floor's trial key.
        if ("barred_vault".equals(meta.content)) {
            SpurVault.apply(level, origin, ominousFloor);
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
                    } else if (state.is(Blocks.VAULT) && "barred_vault".equals(meta.content)) {
                        armed.vault = pos.immutable();
                    }
                }
            }
        }
        // Design pass 2026-10-09 (Q3): any room that holds a sculk sensor or a shrieker listens, whether or not its
        // metadata asks for pressure; the other triggers still need the room to ask for them.
        boolean sculk = !armed.sensors.isEmpty() || !armed.shriekers.isEmpty();
        if (!sculk && armed.spurChest == null && armed.vault == null) {
            return;
        }
        armed.hadSpawner = TrialContent.hasActiveSpawner(level, origin);
        ARMED.put(origin.immutable(), armed);
    }

    private static boolean isSpur(String content) {
        return "ominous_bargain".equals(content) || "barred_vault".equals(content);
    }

    /** Drops the triggers for one cell. Called from teardown alongside {@link Locks#clear}. */
    static void clear(BlockPos cellOrigin) {
        ARMED.remove(cellOrigin);
    }

    /**
     * Forgets a player's dwell and their count of Silenced consumable uses, for a logout or a return to
     * the safe room. PD-199: the use count used to outlive the run it was counted in.
     */
    static void forget(UUID player) {
        DWELL.remove(player);
        SILENCED_USES.remove(player);
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
            Whelp.tick(server, level, record);
            if (server.getTickCount() % 6 == 0) {
                HallRoom.leverGlow(level, record);
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
            // While a whelp is out the room is not listening: its meter cannot rise and nothing answers.
            boolean hushed = Whelp.active(level, record);
            int pulses = 0;
            for (BlockPos pos : armed.sensors) {
                boolean now = sensorActive(level.getBlockState(pos));
                if (now && !Boolean.TRUE.equals(armed.wasActive.get(pos))) {
                    pulses++;
                }
                armed.wasActive.put(pos, now);
            }
            if (pulses > 0 && !hushed) {
                // Sculk hears you (design pass 2026-10-09, Q3): each pulse fills the room's meter; a full meter
                // is an answer, and the meter starts again.
                armed.heard = SculkOmen.heardAfter(armed.heard, pulses);
                if (SculkOmen.answers(armed.heard, SculkOmen.heardMax(armed.ancientCity))) {
                    armed.heard = 0;
                    // The shrieker screams for the sound and the look (vanilla's own sensor relay may already have
                    // made it), but the answer is the meter filling, never the shriek.
                    screamViaShrieker(level, record, armed, origin);
                    armed.everHeard = true;
                    record.floor.sculkAnswers++;
                    darken(level, record, armed.sensors.isEmpty() ? origin : armed.sensors.get(0));
                    answer(server, level, record, Omen.Source.SENSOR, origin);
                    announceHeard(server, record);
                }
            }
            for (BlockPos pos : armed.shriekers) {
                boolean now = shrieking(level.getBlockState(pos));
                if (now && !hushed && armed.sensors.isEmpty() && !Boolean.TRUE.equals(armed.wasActive.get(pos))) {
                    // In a room with no sensors a shriek is the room answering at once. With sensors it is only the
                    // shrieker voicing what its sensors heard (vanilla relays a sensor to a shrieker within 8 blocks),
                    // so it does not answer for them.
                    armed.heard = 0;
                    armed.everHeard = true;
                    record.floor.sculkAnswers++;
                    darken(level, record, pos);
                    answer(server, level, record, Omen.Source.SHRIEK, pos);
                    announceHeard(server, record);
                }
                armed.wasActive.put(pos, now);
            }
            if ((!armed.sensors.isEmpty() || !armed.shriekers.isEmpty())
                    && SculkOmen.unheardPays(armed.hadSpawner, !TrialContent.hasActiveSpawner(level, origin),
                    armed.everHeard, armed.unheardPaid)) {
                armed.unheardPaid = true;
                payUnheard(server, level, record, origin);
            }
            if (armed.vault != null && !armed.vaultFired && vaultPaid(level, armed.vault)) {
                armed.vaultFired = true;
                // The Barred Vault is relief: a life back, never below zero.
                relieve(server, record, 1);
                PlaytestJournal.hazard(server, record, Omen.Source.VAULT, armed.vault);
            }
            if (armed.spurChest != null && !armed.spurFired && spurTaken(level, armed.spurChest)) {
                armed.spurFired = true;
                if ("ominous_bargain".equals(armed.spurKind)) {
                    // J3: the bargain is a harder floor, +2 level for the
                    // rest of it, not omen.
                    record.floor.levelBonus += BARGAIN_LEVELS;
                    PlaytestJournal.hazard(server, record, Omen.Source.BARGAIN, armed.spurChest);
                    OmenBar.cue(server, record, Omen.Source.BARGAIN);
                }
            }
        }
    }

    /**
     * Makes the room's shrieker nearest the party scream when the Heard meter fills (design pass 2026-10-09,
     * Q3: sensors and shriekers should read as one system). Vanilla sensors and shriekers are separate
     * listeners that never talk to each other, so this is the link: the shrieker plays its own shriek and
     * animation, and the shriek is then answered like any other.
     *
     * @return whether a shrieker screamed
     */
    private static boolean screamViaShrieker(ServerLevel level, InstanceRecord record, Armed armed, BlockPos origin) {
        if (armed.shriekers.isEmpty()) {
            return false;
        }
        net.minecraft.world.phys.AABB room = CellGeometry.cellBounds(origin);
        ServerPlayer hearer = null;
        for (UUID member : record.members.keySet()) {
            ServerPlayer player = serverOf(level).getPlayerList().getPlayer(member);
            if (player != null && player.level() == level && room.contains(player.position())) {
                hearer = player;
                break;
            }
        }
        if (hearer == null) {
            return false;
        }
        BlockPos nearest = armed.shriekers.get(0);
        for (BlockPos pos : armed.shriekers) {
            if (pos.distSqr(hearer.blockPosition()) < nearest.distSqr(hearer.blockPosition())) {
                nearest = pos;
            }
        }
        return shriekAt(level, nearest, hearer);
    }

    /** Makes the shrieker at {@code pos} shriek at {@code player}, as it would for their own footsteps; whether it did. */
    static boolean shriekAt(ServerLevel level, BlockPos pos, ServerPlayer player) {
        if (!(level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.SculkShriekerBlockEntity shrieker)) {
            return false;
        }
        shrieker.tryShriek(level, player);
        return shrieking(level.getBlockState(pos));
    }

    /** The room answered: a title so the player sees it, since the meter is only a number until it fills. */
    private static void announceHeard(MinecraftServer server, InstanceRecord record) {
        for (UUID member : record.members.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player != null) {
                StaggeredTitle.show(server, member, net.minecraft.network.chat.Component.literal("Heard")
                        .withStyle(net.minecraft.ChatFormatting.DARK_AQUA),
                        List.of("The sculk heard you."), net.minecraft.ChatFormatting.GRAY);
            }
        }
    }

    /** A sculk room with a spawner was cleared without the sculk ever answering: scrap into each haul in the room. */
    private static void payUnheard(MinecraftServer server, ServerLevel level, InstanceRecord record, BlockPos origin) {
        int scrap = PocketDungeonsConfig.sculkUnheardScrap();
        if (scrap <= 0) {
            return;
        }
        net.minecraft.world.phys.AABB room = CellGeometry.cellBounds(origin);
        DungeonLog log = DungeonLog.forServer(server);
        for (UUID member : record.members.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player != null && room.contains(player.position())) {
                log.addHaul(member, scrap);
                player.sendOverlayMessage(net.minecraft.network.chat.Component.literal("Unheard. +" + scrap + " scrap")
                        .withStyle(net.minecraft.ChatFormatting.AQUA));
                Keystone.showCompass(player, log.get(member), true);
            }
        }
    }

    /**
     * The Heard meter of the sculk room {@code at} stands in, as {@code {heard, max}}, or {@code null} when the
     * room has no sensor or the position is not in an armed room. For the sidebar.
     */
    static int[] heardAt(InstanceRecord record, BlockPos at) {
        if (record == null || record.layout == null) {
            return null;
        }
        PlanCell cell = record.layout.geometry().cellAt(at);
        if (cell == null) {
            return null;
        }
        Armed armed = ARMED.get(record.layout.geometry().cellOrigin(cell));
        if (armed == null || armed.sensors.isEmpty()) {
            return null;
        }
        return new int[]{armed.heard, SculkOmen.heardMax(armed.ancientCity)};
    }

    /** Whether the vault at {@code pos} has been opened with its key: it is unlocking or ejecting. */
    static boolean vaultPaid(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(Blocks.VAULT)) {
            return false;
        }
        net.minecraft.world.level.block.entity.vault.VaultState phase =
                state.getValue(net.minecraft.world.level.block.VaultBlock.STATE);
        return phase == net.minecraft.world.level.block.entity.vault.VaultState.UNLOCKING
                || phase == net.minecraft.world.level.block.entity.vault.VaultState.EJECTING;
    }

    /** Vanilla's shrieker answer: darkness on every member close enough to have been heard. */
    private static void darken(ServerLevel level, InstanceRecord record, BlockPos shrieker) {
        for (UUID member : record.members.keySet()) {
            ServerPlayer player = serverOf(level).getPlayerList().getPlayer(member);
            if (player != null && player.level() == level
                    && player.blockPosition().distSqr(shrieker) <= 40 * 40) {
                player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 260));
            }
        }
    }

    private static MinecraftServer serverOf(ServerLevel level) {
        return level.getServer();
    }

    /**
     * Dwell, per player, per cell. {@link Omen#dwellWaves} is cumulative for a
     * given dwell time, so only the increase since the last poll answers.
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
                dwell.wavesPaid = 0;
                continue;
            }
            BlockPos origin = record.layout.geometry().cellOrigin(cell);
            if (!origin.equals(dwell.cell)) {
                dwell.cell = origin.immutable();
                dwell.seconds = 0;
                dwell.wavesPaid = 0;
            }
            dwell.seconds += PERIOD / 20;
            boolean staging = origin.equals(record.stagingCellOrigin);
            int owed = Omen.dwellWaves(dwell.seconds, unsolved(level, origin), staging);
            while (owed > dwell.wavesPaid) {
                trigger(server, record, Omen.Source.DWELL, at);
                dwell.wavesPaid++;
            }
        }
    }

    /**
     * Whether the cell's situation is still unsolved, which is what spec 5.4
     * makes dwelling answer. A cell with an armed lock has not been opened, and a
     * cell with an uncleared trial spawner has not been fought.
     */
    private static boolean unsolved(ServerLevel level, BlockPos origin) {
        return Locks.isArmed(origin) || TrialContent.hasActiveSpawner(level, origin);
    }

    /** Consumables used so far by each Silenced player; every {@code silencedConsumablesPerOmen}-th spawns a wave. */
    private static final Map<UUID, Integer> SILENCED_USES = new HashMap<>();

    /**
     * A Silenced member used a consumable (playtest 2026-10-02-1: Silence no
     * longer blocks consumables; using one is heard). Every
     * {@code silencedConsumablesPerOmen}-th use sends a wave (J3).
     */
    static void silencedUse(MinecraftServer server, InstanceRecord record, UUID player) {
        int per = PocketDungeonsConfig.silencedConsumablesPerOmen();
        int uses = SILENCED_USES.merge(player, 1, Integer::sum);
        if (uses >= per) {
            SILENCED_USES.put(player, 0);
            trigger(server, record, Omen.Source.SILENCE, null);
        }
    }

    /**
     * A trigger answered: journal it, send the lootless wave, play the cue.
     * The wave's size follows the lives already lost, so a shaken trip is
     * punished harder (danger still scales with omen in
     * {@code Instances#applyMobScale}).
     */
    private static void trigger(MinecraftServer server, InstanceRecord record, Omen.Source source,
                                BlockPos at) {
        PlaytestJournal.hazard(server, record, source, at);
        Instances.spawnOmenWave(server, record, Math.max(1, Omen.clamp(record.interval.omen) + 1));
        OmenBar.cue(server, record, source);
    }

    /** A sculk room answers: a Warden whelp goes after the nearest member in it, in place of an omen wave. */
    private static void answer(MinecraftServer server, ServerLevel level, InstanceRecord record, Omen.Source source,
                               BlockPos at) {
        PlaytestJournal.hazard(server, record, source, at);
        ServerPlayer hearer = null;
        for (UUID member : record.members.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player != null && player.level() == level && player.isAlive()
                    && (hearer == null || player.blockPosition().distSqr(at) < hearer.blockPosition().distSqr(at))) {
                hearer = player;
            }
        }
        if (hearer != null) {
            Whelp.spawn(level, record, hearer);
        }
        OmenBar.cue(server, record, source);
    }

    /** Takes {@code amount} omen off the trip: a life back, never below zero (the fountain, a Barred Vault). */
    static void relieve(MinecraftServer server, InstanceRecord record, int amount) {
        record.interval.omen = Math.max(0, Omen.clamp(record.interval.omen) - Math.abs(amount));
        OmenBar.sync(server, record);
    }

    private static boolean sensorActive(BlockState state) {
        return state.hasProperty(BlockStateProperties.SCULK_SENSOR_PHASE)
                && state.getValue(BlockStateProperties.SCULK_SENSOR_PHASE) == SculkSensorPhase.ACTIVE;
    }

    private static boolean shrieking(BlockState state) {
        return state.hasProperty(BlockStateProperties.SHRIEKING)
                && state.getValue(BlockStateProperties.SHRIEKING);
    }

    /** A spur pays its price when its reward leaves the container. */
    static boolean spurTaken(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof Container container && container.isEmpty();
    }
}
