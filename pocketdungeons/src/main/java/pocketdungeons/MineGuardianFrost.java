package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Act 2 Endless Mine's third guardian (roadmap T42), a miniature of the Frostworks' Big Freeze.
 * The Frostbite is a stray (small kin of Jack Frost, the renamed elite; never a Warden) who brings the
 * cold with her: a few seconds after she appears, every member inside the cell gains freeze ticks the
 * way {@link FrostFinale} counts them, and the lit braziers she stands among are the only warmth. Her
 * wind tell shortens as hardness climbs ({@link MineGuardianRules#tellTicks}) but never under two
 * seconds, and harder guardians field more strays ({@link MineGuardianRules#straysFor}).
 *
 * <p>The cold is {@link FrostFinale#coldTick} itself, fed a {@link FrostFinale.Run} built for the
 * terminal cell: the braziers are lit campfires placed at spawn (and taken back when the cell ends),
 * and the meter's health floor rule applies unchanged, so the cold can bite but never kill. When every
 * stray is dead the cold lifts at once. Per floor state lives in {@link #SETS}, keyed by the terminal
 * cell origin.
 */
final class MineGuardianFrost implements MineGuardians.Guardian {

    /** Her name: a small Jack Frost for a small freeze, and never a Warden name. */
    static final String NAME = "The Frostbite";

    /** Strays stand this far from the cell centre. */
    private static final double RING = 3.0;

    /** One floor's cold and the braziers it lent. */
    private static final class Set {
        final ServerLevel level;
        final BlockPos terminal;
        final FrostFinale.Run cold = new FrostFinale.Run();
        final List<BlockPos> braziers = new ArrayList<>();
        final List<UUID> strays = new ArrayList<>();

        Set(ServerLevel level, BlockPos terminal) {
            this.level = level;
            this.terminal = terminal;
        }
    }

    private static final Map<BlockPos, Set> SETS = SessionState.clearedAtStop(new HashMap<>());
    private static boolean registered;

    // ---- the spawn -----------------------------------------------------------------------------------

    @Override
    public void spawn(MineGuardians.Spawn spawn) {
        ServerLevel level = spawn.level();
        Set set = new Set(level, spawn.terminal());
        int strays = MineGuardianRules.capped(MineGuardianRules.straysFor(spawn.hardness()),
                PocketDungeonsConfig.mineGuardianMobCap());
        for (int i = 0; i < strays; i++) {
            double angle = 2.0 * Math.PI * i / strays;
            BlockPos at = spawn.centre().offset((int) Math.round(Math.cos(angle) * RING), 0,
                    (int) Math.round(Math.sin(angle) * RING));
            Mob stray = EntityTypes.STRAY.spawn(level, at, EntitySpawnReason.TRIGGERED);
            if (stray == null) {
                continue;
            }
            MineGuardians.tag(stray);
            MineGuardians.scale(stray, spawn.floorLevel(), spawn.hardness());
            if (set.strays.isEmpty()) {
                stray.setCustomName(Component.literal(NAME).withStyle(ChatFormatting.AQUA));
                stray.setCustomNameVisible(true);
            }
            set.strays.add(stray.getUUID());
        }
        if (set.strays.isEmpty()) {
            return;
        }

        java.util.Set<BlockPos> taken = new java.util.HashSet<>();
        taken.add(spawn.centre());
        for (int i = 0; i < MineGuardianRules.BRAZIERS; i++) {
            BlockPos at = MineGuardians.freeSpotInCell(level, spawn.terminal(), taken, level.getRandom());
            if (at == null) {
                continue;
            }
            level.setBlock(at, Blocks.CAMPFIRE.defaultBlockState(), 3);
            set.braziers.add(at);
        }
        set.cold.room.add(CellGeometry.cellBounds(spawn.terminal()));
        set.cold.braziers.addAll(set.braziers);
        long now = level.getServer().getTickCount();
        set.cold.coldAt = now + MineGuardianRules.tellTicks(FrostFinaleRules.TELL_TICKS, spawn.hardness());
        SETS.put(spawn.terminal(), set);
        for (ServerPlayer member : membersInCell(set)) {
            level.playSound(null, member.blockPosition(), SoundEvents.WIND_CHARGE_THROW,
                    SoundSource.AMBIENT, 1.5f, 0.5f);
            member.sendOverlayMessage(Component.literal("The cold is coming. Find a fire.")
                    .withStyle(ChatFormatting.AQUA));
        }
    }

    // ---- the tick: the cold moves every game tick ------------------------------------------------------

    /**
     * Wires the shared tick. It runs every game tick, not a poll: vanilla thaws two freeze ticks a
     * tick, so a slower write would saw tooth, the same reason {@link FrostFinale} has its own tick.
     * Called once from {@link MineGuardiansAct2#registerAll()}.
     */
    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!SETS.isEmpty()) {
                pollAll(server.getTickCount());
            }
        });
    }

    /** Test seam: runs one tick of the cold at {@code now}. */
    static void tickForTest(long now) {
        pollAll(now);
    }

    private static void pollAll(long now) {
        for (Map.Entry<BlockPos, Set> entry : new ArrayList<>(SETS.entrySet())) {
            Set set = entry.getValue();
            if (!strayAlive(set)) {
                endSet(entry.getKey(), set);
                continue;
            }
            FrostFinale.coldTick(set.level, set.cold, set.level.players(), now);
        }
    }

    private static boolean strayAlive(Set set) {
        for (UUID id : set.strays) {
            Entity entity = set.level.getEntity(id);
            if (entity instanceof Mob mob && mob.isAlive()) {
                return true;
            }
        }
        return false;
    }

    /** The cold lifts: the tracked members thaw and the set is spent. */
    private static void endSet(BlockPos cellOrigin, Set set) {
        SETS.remove(cellOrigin);
        thaw(set);
    }

    private static void thaw(Set set) {
        for (ServerPlayer member : set.level.players()) {
            member.setTicksFrozen(0);
        }
        for (UUID id : set.cold.meter.keySet()) {
            ServerPlayer member = set.level.getServer().getPlayerList().getPlayer(id);
            if (member != null) {
                member.setTicksFrozen(0);
            }
        }
        set.cold.meter.clear();
        set.cold.warned.clear();
        set.cold.coldAt = -1;
    }

    private static List<ServerPlayer> membersInCell(Set set) {
        AABB box = set.cold.room.get(0);
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer player : set.level.players()) {
            if (!player.isSpectator() && box.contains(player.getX(), player.getY(), player.getZ())) {
                out.add(player);
            }
        }
        return out;
    }

    // ---- cleanup and seams ----------------------------------------------------------------------------

    /**
     * The floor's cell is ending: the cold lifts and the braziers go back to air where they still stand.
     * A stray still fighting is an ordinary live mob, the teardown's to sweep.
     */
    static void clear(BlockPos cellOrigin) {
        Set set = SETS.remove(cellOrigin);
        if (set == null) {
            return;
        }
        thaw(set);
        for (BlockPos at : set.braziers) {
            if (set.level.getBlockState(at).is(Blocks.CAMPFIRE)) {
                set.level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    /** The braziers stood in the cell, for tests. */
    static int braziersOf(BlockPos cellOrigin) {
        Set set = SETS.get(cellOrigin);
        return set == null ? 0 : set.braziers.size();
    }

    /** The tick the cold begins, for tests; -1 once lifted or when there is none. */
    static long coldAtOf(BlockPos cellOrigin) {
        Set set = SETS.get(cellOrigin);
        return set == null ? -1 : set.cold.coldAt;
    }
}
