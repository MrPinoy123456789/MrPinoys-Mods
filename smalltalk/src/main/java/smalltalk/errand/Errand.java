package smalltalk.errand;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * SPEC.md section 10: a temporary brain override, structurally identical to
 * Rehome's follow behaviour. Not implemented in the core release -- defined
 * now so visits, deliveries, and "come see this" are a filled-in slot later
 * rather than a redesign.
 *
 * <p>Rules for any errand, once built: never leave the resident stranded
 * (timeout always returns them home), never override during a raid or at
 * night, and never let an errand outlive a server restart -- abandon on load.
 */
public interface Errand {

    BlockPos target();

    int timeoutTicks();

    void onArrive(Villager villager, ServerLevel level);

    void onAbandon(Villager villager, AbandonReason reason);

    enum AbandonReason {
        TIMED_OUT,
        RAID,
        NIGHTFALL,
        SERVER_RESTART
    }
}
