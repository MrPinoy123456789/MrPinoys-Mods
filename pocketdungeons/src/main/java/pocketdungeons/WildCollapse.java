package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * The end of a wild lodestone's life (plan 4.6 and 4.7): warns the people inside as the lifespan runs out, and at the
 * end sweeps the dungeon's loose items into chests beside the old lodestone, closes the dungeon, turns the entrance to
 * rubble and retires the lodestone. The chests stay as plain world blocks and their positions stay on the retired list.
 * The top up loop fills the gap.
 */
final class WildCollapse {

    /** The global tick runs every this many server ticks. */
    static final int EVERY = 20;

    private static final Map<String, Integer> WARNED = SessionState.clearedAtStop(new HashMap<>());
    private static int counter;

    private WildCollapse() {}

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(WildCollapse::tick);
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

    /** One lodestone's lifespan bookkeeping. Package private for the gametest. */
    static void step(MinecraftServer server, WildState.Lodestone lodestone, long now) {
        if (lodestone.expired(now)) {
            collapse(server, lodestone);
            return;
        }
        int due = WildLifespan.warningDue(now, lodestone.expiresAt, PocketDungeonsConfig.wildCollapseWarnHours());
        if (due == 0 || WARNED.getOrDefault(lodestone.id, 0) >= due) {
            return;
        }
        WARNED.put(lodestone.id, due);
        InstanceRecord record = WildRun.liveRecord(lodestone.id);
        if (record == null) {
            return;
        }
        String text = due == 2 ? WildText.COLLAPSE_NOW : WildText.COLLAPSE_SOON;
        for (ServerPlayer player : WildClear.onlineMembers(server, record)) {
            player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.RED));
            player.sendOverlayMessage(Component.literal(text).withStyle(ChatFormatting.RED));
        }
    }

    /** Closes the dungeon, rubbles the entrance and retires the lodestone. */
    static void collapse(MinecraftServer server, WildState.Lodestone lodestone) {
        WildState state = WildStore.state();
        if (state == null) {
            return;
        }
        InstanceRecord record = WildRun.liveRecord(lodestone.id);
        if (record != null) {
            WildReset.reset(server, lodestone, record);
        }
        ServerLevel level = WildReset.levelOf(server, lodestone);
        if (level != null) {
            BlockPos pos = new BlockPos(lodestone.x, lodestone.y, lodestone.z);
            level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            WildLodestones.rubble(level, pos);
        }
        WildLodestones.forgetExits(lodestone.id);
        state.retire(lodestone.id);
        WARNED.remove(lodestone.id);
        WildStore.save();
        PocketDungeonsMod.LOG.info("Wild lodestone {} collapsed; {} lost and found chest(s) remain", lodestone.id,
                lodestone.lostAndFound.size());
    }
}
