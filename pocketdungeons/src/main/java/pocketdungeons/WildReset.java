package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Closes a cleared wild dungeon when its cooldown ends: warns the people inside, sweeps loose items into chests beside
 * the lodestone, moves every member's return point to a safe spot, tears the dungeon down and marks the lodestone ready
 * so the next entry builds a fresh seed. Also tidies the states a restart leaves behind, because wild records live in
 * memory only.
 */
final class WildReset {

    /** The global tick runs every this many server ticks. */
    static final int EVERY = 20;

    private static final java.util.Map<String, Boolean> WARNED = SessionState.clearedAtStop(new java.util.HashMap<>());
    private static int counter;
    private static final java.util.Map<String, ServerLevel> DUNGEONS_FOR_TESTING = new java.util.concurrent.ConcurrentHashMap<>();

    private WildReset() {}

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(WildReset::tick);
    }

    private static void tick(MinecraftServer server) {
        if (!WildMode.active() || ++counter % EVERY != 0) {
            return;
        }
        WildState state = WildStore.state();
        if (state == null) {
            return;
        }
        long now = WildClock.now();
        for (WildState.Lodestone lodestone : new ArrayList<>(state.all())) {
            step(server, lodestone, now);
        }
    }

    /** One lodestone's reset bookkeeping. Package private for the gametest. */
    static void step(MinecraftServer server, WildState.Lodestone lodestone, long now) {
        InstanceRecord record = WildRun.liveRecord(lodestone.id);
        if (lodestone.phase == WildState.Phase.IN_PROGRESS && record == null) {
            // A restart (or a failed build) left the lodestone in progress with nothing behind it.
            lodestone.markReady();
            WildStore.save();
            return;
        }
        if (lodestone.phase != WildState.Phase.CLEARED) {
            WARNED.remove(lodestone.id);
            return;
        }
        if (now >= lodestone.resetAt) {
            reset(server, lodestone, record);
            return;
        }
        if (record != null && now >= lodestone.resetAt - PocketDungeonsConfig.wildResetWarnSeconds()
                && WARNED.putIfAbsent(lodestone.id, Boolean.TRUE) == null) {
            for (ServerPlayer player : WildClear.onlineMembers(server, record)) {
                player.sendSystemMessage(Component.literal(WildText.RESET_SOON).withStyle(ChatFormatting.YELLOW));
                player.sendOverlayMessage(Component.literal(WildText.RESET_SOON).withStyle(ChatFormatting.YELLOW));
            }
        }
    }

    /** Sweeps, ejects everyone to a safe place, tears the dungeon down and marks the lodestone ready. */
    static void reset(MinecraftServer server, WildState.Lodestone lodestone, InstanceRecord record) {
        ServerLevel overworld = levelOf(server, lodestone);
        if (record != null) {
            BlockPos site = new BlockPos(lodestone.x, lodestone.y, lodestone.z);
            if (overworld != null) {
                try {
                    WildSweep.sweep(server, record, overworld, site, lodestone);
                } catch (RuntimeException e) {
                    PocketDungeonsMod.LOG.error("Sweeping wild lodestone {} failed", lodestone.id, e);
                }
                for (UUID member : new ArrayList<>(record.members.keySet())) {
                    ReturnPoint safe = safePoint(overworld, lodestone);
                    if (safe != null) {
                        record.members.put(member, safe);
                    }
                }
            }
            InstanceTeardown.purge(server, record, "wild reset");
        }
        WildLodestones.forgetExits(lodestone.id);
        lodestone.markReady();
        WARNED.remove(lodestone.id);
        WildStore.save();
        PocketDungeonsMod.LOG.info("Wild lodestone {} reset", lodestone.id);
    }

    /** The dungeon dimension, or the level a gametest chose for this lodestone (the gametest server has none). */
    static ServerLevel dungeonLevel(MinecraftServer server, String lodestoneId) {
        ServerLevel chosen = lodestoneId == null ? null : DUNGEONS_FOR_TESTING.get(lodestoneId);
        return chosen != null ? chosen : server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
    }

    /** Test seam, per lodestone so parallel gametests do not clear each other's: null removes it. */
    static void setDungeonForTesting(String lodestoneId, ServerLevel level) {
        if (level == null) {
            DUNGEONS_FOR_TESTING.remove(lodestoneId);
        } else {
            DUNGEONS_FOR_TESTING.put(lodestoneId, level);
        }
    }

    static ServerLevel levelOf(MinecraftServer server, WildState.Lodestone lodestone) {
        for (ServerLevel candidate : server.getAllLevels()) {
            if (WildLodestones.dimensionKey(candidate).equals(lodestone.dimension)) {
                return candidate;
            }
        }
        return null;
    }

    /** A standable spot a few blocks from the lodestone: solid ground, two blocks of air above. */
    static ReturnPoint safePoint(ServerLevel level, WildState.Lodestone lodestone) {
        for (int ring = 2; ring <= 6; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    int x = lodestone.x + dx;
                    int z = lodestone.z + dz;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos feet = new BlockPos(x, y, z);
                    if (level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP) && level.getBlockState(feet).isAir()
                            && level.getBlockState(feet.above()).isAir() && level.getFluidState(feet).isEmpty()) {
                        return new ReturnPoint(level.dimension(), new Vec3(x + 0.5, y, z + 0.5), 0.0f, 0.0f);
                    }
                }
            }
        }
        return null;
    }
}
