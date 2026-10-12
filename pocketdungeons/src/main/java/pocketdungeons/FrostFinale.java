package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Frostworks finale (roadmap T33, boss workshop 2026-10-11 row 11): The Big Freeze is cold. A few seconds
 * after the wave stands (a wind sound and an action bar line first), every member inside the room gains vanilla
 * freeze ticks ({@code Entity.setTicksFrozen}: the blue overlay, the slowdown, then freeze damage) at the
 * {@code frostColdTicksPerSecond} knob. A member within {@code frostBrazierRadius} blocks of a lit campfire (a
 * brazier, stamped into the room on a plinth) thaws fast. The party moves between fire and cover while the Frost
 * Warden shoots. The decisions are in {@link FrostFinaleRules}; this class only does them to the world.
 *
 * <p>The cold is never lethal: vanilla freeze damage is 1 health every 2 seconds, and a member at 2 health or
 * under is held below the freeze line ({@link FrostFinaleRules#ceilingFor}). The Warden's own arrows already
 * slow (a stray's arrow carries slowness), so there is no extra effect.
 *
 * <p>Braziers are found by scanning the room for lit campfires when the wave stands, never by authored
 * coordinates (a stamped room rotates). A room with none, or a knob of 0, gets no cold at all rather than a cold
 * with no relief. A brazier a player puts out or breaks stops warming; one a player places later never counts.
 *
 * <p>Why a tick listener of its own: vanilla takes 2 freeze ticks off every tick a member is out of powder
 * snow, so a value written once per watch interval (20 ticks) would saw-tooth by 40. The script keeps a decimal
 * meter per member and moves it every server tick while the cold runs. The listener is registered the first time
 * a cold starts and does nothing when no run is active.
 *
 * <p>What the world holds, and where it is undone: nothing is placed (the braziers belong to the room); the only
 * change is the members' freeze ticks, which go to 0 when the finale is won ({@link #onWon}), when the floor
 * ends or is torn down ({@link #onFloorEnded}), or the moment the finale is found over.
 */
final class FrostFinale implements FinaleScript {

    /** Wind, once as the tell starts and once as the cold begins. */
    private static final float WIND_VOLUME = 1.5f;
    private static final float WIND_PITCH = 0.5f;

    /** Set once, when the first cold starts, so a pure test that builds the script never loads Fabric events. */
    private static boolean tickHooked;

    /** One run's state (package-visible so the gametest can read the braziers and the meters). */
    static final class Run {
        /** The tick the cold starts, or -1 while there is no cold (no braziers, or a knob of 0). */
        long coldAt = -1;
        boolean windSecond;
        final List<AABB> room = new ArrayList<>();
        final List<BlockPos> braziers = new ArrayList<>();
        /** Per member: the freeze meter, a decimal so a rate under one tick per tick still moves. */
        final Map<UUID, Double> meter = new HashMap<>();
        final Set<UUID> warned = new HashSet<>();
    }

    /** A running cold: the slot's record and level (the listener needs both) and its state. */
    private record Active(InstanceRecord record, ServerLevel level, Run run) {}

    /** Live colds by run slot; emptied at server stop and whenever a cold is released. */
    private static final Map<Integer, Active> ACTIVE = SessionState.clearedAtStop(new HashMap<>());

    @Override
    public void onWaveSpawned(FinaleContext ctx) {
        Run run = ctx.scriptState(Run.class, Run::new);
        run.room.clear();
        run.room.addAll(roomBoxes(ctx.record()));
        run.braziers.clear();
        run.braziers.addAll(scanBraziers(ctx.level(), run.room));
        int rate = PocketDungeonsConfig.frostColdTicksPerSecond();
        if (!FrostFinaleRules.coldApplies(run.braziers.size(), rate)) {
            run.coldAt = -1;
            PocketDungeonsMod.LOG.warn("Frost finale (slot {}): {} lit braziers found and {} freeze ticks per "
                    + "second, so there is no cold this run", ctx.record().slot, run.braziers.size(), rate);
            return;
        }
        run.coldAt = ctx.tickCount() + FrostFinaleRules.TELL_TICKS;
        run.windSecond = false;
        if (!tickHooked) {
            tickHooked = true;
            ServerTickEvents.END_SERVER_TICK.register(FrostFinale::tickAll);
        }
        ACTIVE.put(ctx.record().slot, new Active(ctx.record(), ctx.level(), run));
        wind(ctx.level(), ctx.members());
        overlay(ctx.members(), "The cold is coming. Find a fire.");
    }

    @Override
    public void onWon(FinaleContext ctx) {
        release(ctx.server(), ctx.record().slot, ctx.members());
    }

    @Override
    public void onFloorEnded(FinaleContext ctx) {
        release(ctx.server(), ctx.record().slot, ctx.members());
    }

    // ---- the cold ----------------------------------------------------------------------------------

    /** Every server tick: each live cold advances for the members online in the dungeon. */
    private static void tickAll(MinecraftServer server) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        for (Active active : new ArrayList<>(ACTIVE.values())) {
            InstanceRecord record = active.record();
            FinaleWave.State state = FinaleWave.stateOf(record);
            List<ServerPlayer> members = CapstoneFights.onlineMembers(server, record);
            if (state == null || state.done || record.phase != RunSession.Phase.ACTIVE
                    || !record.floor.completed.isEmpty()) {
                release(server, record.slot, members);
                continue;
            }
            coldTick(active.level(), active.run(), members, server.getTickCount());
        }
    }

    /**
     * One tick of the cold for {@code members}. Before the tell is over nothing happens. After it, a member in
     * the room moves along {@link FrostFinaleRules#next}: up by the knob's rate in the cold, down fast within
     * the radius of a lit brazier. A member who leaves the room is left to vanilla, which thaws them.
     */
    static void coldTick(ServerLevel level, Run run, List<ServerPlayer> members, long now) {
        if (!FrostFinaleRules.coldStarted(now, run.coldAt)) {
            return;
        }
        if (!run.windSecond) {
            run.windSecond = true;
            wind(level, members);
        }
        double rise = FrostFinaleRules.risePerTick(PocketDungeonsConfig.frostColdTicksPerSecond());
        double radius = PocketDungeonsConfig.frostBrazierRadius();
        List<BlockPos> lit = litBraziers(level, run.braziers);
        for (ServerPlayer member : members) {
            UUID id = member.getUUID();
            if (!member.isAlive() || member.isSpectator() || !inRoom(run.room, member)) {
                run.meter.remove(id);
                run.warned.remove(id);
                continue;
            }
            double meter = Math.max(run.meter.getOrDefault(id, 0.0), member.getTicksFrozen());
            boolean warm = warmAt(lit, member, radius);
            double health = member.getHealth();
            double next = FrostFinaleRules.next(meter, warm, rise, health);
            run.meter.put(id, next);
            int write = FrostFinaleRules.ticksToWrite(next, health);
            if (write != member.getTicksFrozen()) {
                member.setTicksFrozen(write);
            }
            if (FrostFinaleRules.warnDue(next, run.warned.contains(id))) {
                run.warned.add(id);
                member.sendOverlayMessage(Component.literal("Freezing. Stand by a fire.")
                        .withStyle(ChatFormatting.AQUA));
            } else if (FrostFinaleRules.warnReset(next)) {
                run.warned.remove(id);
            }
        }
    }

    /**
     * Ends the cold for this slot at once: drops it from the live list and thaws every member who was in it.
     * Safe to call twice and for a slot with no cold.
     */
    static void release(MinecraftServer server, int slot, List<ServerPlayer> members) {
        Active active = ACTIVE.remove(slot);
        if (active == null) {
            return;
        }
        Set<UUID> thawed = new HashSet<>();
        for (ServerPlayer member : members) {
            member.setTicksFrozen(0);
            thawed.add(member.getUUID());
        }
        for (UUID id : active.run().meter.keySet()) {
            ServerPlayer member = thawed.contains(id) ? null : server.getPlayerList().getPlayer(id);
            if (member != null) {
                member.setTicksFrozen(0);
            }
        }
        active.run().meter.clear();
        active.run().warned.clear();
        active.run().coldAt = -1;
    }

    /** Whether a cold is running for this slot (the gametest reads it). */
    static boolean coldRunning(int slot) {
        return ACTIVE.containsKey(slot);
    }

    // ---- the room and the braziers -----------------------------------------------------------------

    /**
     * The cold's boxes: one per cell the floor placed {@code big_freeze} in. A record whose floor has no room
     * map (an old save, a gametest) falls back to every cell of the layout.
     */
    static List<AABB> roomBoxes(InstanceRecord record) {
        List<AABB> out = new ArrayList<>();
        if (record == null || record.layout == null || record.layout.geometry() == null) {
            return out;
        }
        PlanGeometry geometry = record.layout.geometry();
        for (Map.Entry<PlanCell, FloorRooms.Room> entry : record.floor.rooms.entrySet()) {
            if (FrostFinaleRules.isBigFreezeRoom(entry.getValue().id())) {
                out.add(cellBox(geometry, entry.getKey()));
            }
        }
        if (out.isEmpty()) {
            for (PlanCell cell : geometry.cells()) {
                out.add(cellBox(geometry, cell));
            }
        }
        return out;
    }

    private static AABB cellBox(PlanGeometry geometry, PlanCell cell) {
        BlockPos origin = geometry.cellOrigin(cell);
        return new AABB(origin.getX(), origin.getY(), origin.getZ(), origin.getX() + RoomGeometry.CELL,
                origin.getY() + RoomGeometry.CEILING_Y + 1, origin.getZ() + RoomGeometry.CELL);
    }

    /**
     * Every lit campfire (and soul campfire) inside the boxes, up to {@link FrostFinaleRules#MAX_BRAZIERS}.
     * Reads loaded chunks only. One pass at the start of the cold, never per tick.
     */
    static List<BlockPos> scanBraziers(ServerLevel level, List<AABB> boxes) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (AABB box : boxes) {
            for (int x = (int) Math.floor(box.minX); x < box.maxX; x++) {
                for (int z = (int) Math.floor(box.minZ); z < box.maxZ; z++) {
                    for (int y = (int) Math.floor(box.minY); y < box.maxY; y++) {
                        pos.set(x, y, z);
                        if (level.hasChunkAt(pos) && isLitBrazier(level.getBlockState(pos))) {
                            out.add(pos.immutable());
                            if (out.size() >= FrostFinaleRules.MAX_BRAZIERS) {
                                return out;
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    /** A campfire or soul campfire that is burning. */
    static boolean isLitBrazier(BlockState state) {
        return state.getBlock() instanceof CampfireBlock && state.getValue(CampfireBlock.LIT);
    }

    /** The braziers of the scan that are still burning now (a player may have put one out or broken it). */
    private static List<BlockPos> litBraziers(ServerLevel level, List<BlockPos> braziers) {
        List<BlockPos> lit = new ArrayList<>(braziers.size());
        for (BlockPos pos : braziers) {
            if (level.hasChunkAt(pos) && isLitBrazier(level.getBlockState(pos))) {
                lit.add(pos);
            }
        }
        return lit;
    }

    private static boolean inRoom(List<AABB> room, ServerPlayer member) {
        for (AABB box : room) {
            if (box.contains(member.getX(), member.getY(), member.getZ())) {
                return true;
            }
        }
        return false;
    }

    /** Whether the member stands within {@code radius} of any of these burning braziers. */
    private static boolean warmAt(List<BlockPos> lit, ServerPlayer member, double radius) {
        for (BlockPos pos : lit) {
            if (FrostFinaleRules.withinRadius(member.getX() - (pos.getX() + 0.5), member.getY() - (pos.getY() + 0.5),
                    member.getZ() - (pos.getZ() + 0.5), radius)) {
                return true;
            }
        }
        return false;
    }

    // ---- the tell ----------------------------------------------------------------------------------

    private static void wind(ServerLevel level, List<ServerPlayer> members) {
        for (ServerPlayer member : members) {
            level.playSound(null, member.blockPosition(), SoundEvents.WIND_CHARGE_THROW, SoundSource.AMBIENT,
                    WIND_VOLUME, WIND_PITCH);
        }
    }

    private static void overlay(List<ServerPlayer> members, String text) {
        for (ServerPlayer member : members) {
            member.sendOverlayMessage(Component.literal(text).withStyle(ChatFormatting.AQUA));
        }
    }
}
