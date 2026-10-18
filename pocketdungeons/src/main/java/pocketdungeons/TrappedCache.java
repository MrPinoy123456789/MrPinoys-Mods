package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Loaded's trapped cache (affix workshop 2026-10-11, row 4). The affix still places its guaranteed tool
 * chest ({@link RoomContent}); the first time anyone opens it, a small wave rises around the chest. A sound
 * and a burst of smoke go first, about a second before the mobs stand, so the player can back away or set
 * their feet. The chest gives its loot as ever.
 *
 * <p>"Once per chest" needs no stored state: the cache is the chest still holding the Loaded loot table, and
 * vanilla clears the table the moment the chest is opened. A second opening finds no table and does nothing.
 *
 * <p>The ambush never blocks the exit. Mobs stand only on free floor in the chest's own cell, never on the
 * cross of door lanes through the cell's middle (so never in or in front of a doorway), within a few blocks
 * of the chest. Where the room has fewer free spots than the wave asks for, the wave is smaller. Ambush
 * mobs drop nothing ({@link PocketDungeonsMod#OMEN_WAVE_TAG}), so the trap costs the player a fight and pays
 * no extra loot. They are zombies and skeletons; the floor's theme family is a later refinement.
 */
final class TrappedCache {

    /** The loot table the Loaded cache chest carries, when the affix manifest names none. */
    static final String DEFAULT_TABLE = PocketDungeonsMod.MOD_ID + ":affixes/loaded_tools";

    /** Tag on every ambush mob, so tests and tools can find them. */
    static final String TAG = PocketDungeonsMod.MOD_ID + ".trapped_cache";

    /** Ticks between the telegraph and the mobs appearing. */
    static final int DELAY_TICKS = 20;

    /** How far from the chest (in blocks, each axis) an ambush mob may stand. */
    static final int RADIUS = 5;

    static final int MIN_SIZE = 3;
    static final int MAX_SIZE = 8;

    private record Pending(long dueTick, ServerLevel level, List<BlockPos> spots, int slot, int mobLevel, int omen, int act,
                           java.util.UUID target) {}

    private static final List<Pending> PENDING = SessionState.clearedAtStop(new ArrayList<>());

    private TrappedCache() {}

    // ---- pure rules --------------------------------------------------------------------------------------------

    /** The ambush: {@value #MIN_SIZE} mobs for a party of one, one more per extra member, at most {@value #MAX_SIZE}. */
    static int ambushSize(int partySize) {
        int extra = Math.max(1, partySize) - 1;
        return Math.min(MAX_SIZE, MIN_SIZE + extra);
    }

    /**
     * Whether a cell-local column lies on a door lane: the band through the cell's middle, as wide as a
     * doorway plus one block each side, running to every wall. Nothing spawns there, so a doorway and the
     * approach to it stay clear.
     */
    static boolean inLane(int localX, int localZ) {
        int lo = RoomGeometry.DOOR_MIN - 1;
        int hi = RoomGeometry.DOOR_MAX + 1;
        return (localX >= lo && localX <= hi) || (localZ >= lo && localZ <= hi);
    }

    /**
     * Every cell-local column an ambush mob may stand in for a chest at ({@code chestX}, {@code chestZ}):
     * inside the room (not the wall ring), off the door lanes, not the chest itself, and within
     * {@link #RADIUS} of it.
     */
    static List<int[]> candidateColumns(int chestX, int chestZ) {
        List<int[]> out = new ArrayList<>();
        for (int x = 1; x < RoomGeometry.CELL - 1; x++) {
            for (int z = 1; z < RoomGeometry.CELL - 1; z++) {
                if (inLane(x, z) || (x == chestX && z == chestZ)
                        || Math.abs(x - chestX) > RADIUS || Math.abs(z - chestZ) > RADIUS) {
                    continue;
                }
                out.add(new int[] {x, z});
            }
        }
        return out;
    }

    // ---- wiring ------------------------------------------------------------------------------------------------

    static void register() {
        UseBlockCallback.EVENT.register(TrappedCache::onUse);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (PENDING.isEmpty()) {
                return;
            }
            long now = server.getTickCount();
            for (Pending p : new ArrayList<>(PENDING)) {
                if (now >= p.dueTick()) {
                    PENDING.remove(p);
                    release(server, p);
                }
            }
        });
    }

    /** Whether this chest is a Loaded cache that has not been opened: it still holds the Loaded loot table. */
    static boolean isSealedCache(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof RandomizableContainer chest) || chest.getLootTable() == null) {
            return false;
        }
        return chest.getLootTable().identifier().equals(Identifier.parse(cacheTable()));
    }

    private static String cacheTable() {
        AffixDefinition loaded = AffixManifest.current().byId(AffixIds.LOADED);
        return loaded == null || loaded.effects.bonusToolPool == null ? DEFAULT_TABLE : loaded.effects.bonusToolPool;
    }

    static InteractionResult onUse(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || player.isSpectator()
                || !(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }
        BlockPos pos = hit.getBlockPos();
        if (!isSealedCache(serverLevel, pos)) {
            return InteractionResult.PASS;
        }
        // The callback runs before the chest opens, so the table is still there now; vanilla clears it on the
        // open that follows this PASS, which is what makes the ambush fire once.
        InstanceRecord record = Instances.dungeonRecordAt(pos);
        trigger(serverLevel, serverLevel.getServer().getTickCount(), pos, cellOrigin(record, pos),
                record == null ? 1 : Math.max(1, record.members.size()), record, serverPlayer);
        return InteractionResult.PASS;
    }

    /** The floor corner of the cell holding {@code pos}: the plan's, or the 16 block grid when no plan is known. */
    static BlockPos cellOrigin(InstanceRecord record, BlockPos pos) {
        if (record != null && record.layout != null) {
            PlanCell cell = record.layout.geometry().cellAt(pos);
            if (cell != null) {
                return record.layout.geometry().cellOrigin(cell);
            }
        }
        return new BlockPos(pos.getX() & ~15, pos.getY() - 1, pos.getZ() & ~15);
    }

    /**
     * Springs the trap: picks the spots, telegraphs, and queues the mobs {@value #DELAY_TICKS} ticks out.
     * Split from {@link #onUse} so a test can drive it without a client.
     */
    static int trigger(ServerLevel level, long nowTick, BlockPos chest, BlockPos cellOrigin, int partySize,
                       InstanceRecord record, ServerPlayer opener) {
        List<int[]> columns = candidateColumns(chest.getX() - cellOrigin.getX(), chest.getZ() - cellOrigin.getZ());
        Random random = new Random(chest.asLong() ^ nowTick);
        Collections.shuffle(columns, random);
        int want = ambushSize(partySize);
        List<BlockPos> spots = new ArrayList<>();
        for (int[] column : columns) {
            if (spots.size() >= want) {
                break;
            }
            BlockPos spot = new BlockPos(cellOrigin.getX() + column[0], chest.getY(), cellOrigin.getZ() + column[1]);
            if (isFree(level, spot)) {
                spots.add(spot);
            }
        }
        level.playSound(null, chest, SoundEvents.SOUL_ESCAPE.value(), SoundSource.HOSTILE, 1.0f, 0.5f);
        for (BlockPos spot : spots) {
            level.sendParticles(ParticleTypes.SMOKE, spot.getX() + 0.5, spot.getY() + 0.2, spot.getZ() + 0.5,
                    14, 0.25, 0.4, 0.25, 0.02);
        }
        if (opener != null) {
            opener.sendOverlayMessage(Component.literal("Trapped.").withStyle(ChatFormatting.RED));
        }
        int mobLevel = record == null || record.layout == null ? 0
                : record.layout.keystoneLevel() + record.floor.levelBonus;
        int omen = record == null ? 0 : Omen.clamp(record.interval.omen);
        PENDING.add(new Pending(nowTick + DELAY_TICKS, level, spots, record == null ? -1 : record.slot,
                record == null ? -1 : mobLevel, omen, MobScaling.actOf(record), opener == null ? null : opener.getUUID()));
        return spots.size();
    }

    /** Free floor: room for a body, solid ground under it. */
    private static boolean isFree(ServerLevel level, BlockPos spot) {
        return level.getBlockState(spot).isAir() && level.getBlockState(spot.above()).isAir()
                && level.getBlockState(spot.below()).isFaceSturdy(level, spot.below(), Direction.UP);
    }

    private static void release(net.minecraft.server.MinecraftServer server, Pending p) {
        if (p.slot() >= 0) {
            InstanceRecord record = InstanceRegistry.bySlot.get(p.slot());
            if (record == null || record.tearingDown) {
                return;
            }
        }
        ServerPlayer target = p.target() == null ? null : server.getPlayerList().getPlayer(p.target());
        Random random = new Random(p.dueTick());
        for (BlockPos spot : p.spots()) {
            if (!isFree(p.level(), spot)) {
                continue;
            }
            EntityType<?> type = random.nextBoolean() ? EntityTypes.ZOMBIE : EntityTypes.SKELETON;
            if (!(type.create(p.level(), EntitySpawnReason.TRIGGERED) instanceof Mob mob)) {
                continue;
            }
            mob.setPos(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
            mob.addTag(TAG);
            mob.addTag(PocketDungeonsMod.OMEN_WAVE_TAG);
            mob.setPersistenceRequired();
            if (p.mobLevel() >= 0) {
                MobScaling.applyMobScale(mob, p.mobLevel(), p.omen(), p.act());
            }
            if (target != null) {
                mob.setTarget(target);
            }
            p.level().addFreshEntity(mob);
        }
    }
}
