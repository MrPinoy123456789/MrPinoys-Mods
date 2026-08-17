package rehome;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Release, then read. Clears HOME and the POI ticket so vanilla can re-claim
 * from wherever the villager is now standing, then polls for that claim to
 * land -- the room check the earlier design drafts tried to build, done by
 * vanilla instead (SPEC.md section 6).
 */
public final class ClaimWatcher {

    private static final List<Pending> pending = new ArrayList<>();

    private ClaimWatcher() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (pending.isEmpty()) {
                return;
            }
            Iterator<Pending> it = pending.iterator();
            while (it.hasNext()) {
                if (poll(server, it.next())) {
                    it.remove();
                }
            }
        });
    }

    /** Clears HOME and the POI ticket, then starts watching for a new claim. */
    public static void release(Villager villager, ServerLevel level, ServerPlayer player) {
        villager.releasePoi(MemoryModuleType.HOME);
        villager.getBrain().eraseMemory(MemoryModuleType.HOME);
        // Toggling stay/resume/stay again before the previous watch resolves
        // used to stack a second Pending on top of the first -- each one
        // eventually timing out and sending its own "doesn't see anywhere to
        // sleep here", which is what the spam actually was. One watch per
        // villager at a time.
        pending.removeIf(p -> p.villagerUuid.equals(villager.getUUID()));
        pending.add(new Pending(villager.getUUID(), player.getUUID(), level.dimension(),
                RehomeConfig.claimWatchTicks()));
    }

    /** Returns true when this watch is finished and should be dropped. */
    private static boolean poll(MinecraftServer server, Pending p) {
        ServerLevel level = server.getLevel(p.dimension);
        if (level == null) {
            return true;
        }
        Entity entity = level.getEntity(p.villagerUuid);
        if (!(entity instanceof Villager villager)) {
            // Dead, or unloaded elsewhere -- nothing to clean up or report.
            return true;
        }

        Optional<GlobalPos> home = villager.getBrain().getMemory(MemoryModuleType.HOME);
        ServerPlayer player = server.getPlayerList().getPlayer(p.ownerUuid);

        if (home.isPresent()) {
            if (player != null) {
                Feedback.settled(villager, level, player);
                checkHeadroom(villager, level, home.get().pos(), player);
                grantOnboarding(server, player);
            }
            return true;
        }

        if (--p.ticksRemaining <= 0) {
            if (player != null) {
                Feedback.notSettled(villager, player);
            }
            return true;
        }
        return false;
    }

    /** First settled villager only -- {@code award} is a no-op once already met. */
    private static void grantOnboarding(MinecraftServer server, ServerPlayer player) {
        AdvancementHolder advancement = server.getAdvancements()
                .get(Identifier.fromNamespaceAndPath("rehome", "villager_settled"));
        if (advancement != null) {
            player.getAdvancements().award(advancement, "code_triggered");
        }
    }

    /** SPEC.md section 7: the one warning. Slabs and trapdoors block headroom too. */
    private static void checkHeadroom(Villager villager, ServerLevel level, BlockPos bed, ServerPlayer player) {
        for (int i = 1; i <= 2; i++) {
            BlockPos above = bed.above(i);
            if (level.getBlockState(above).isCollisionShapeFullBlock(level, above)) {
                Feedback.headroomWarning(level, above, player, villager);
                return;
            }
        }
    }

    private static final class Pending {
        final UUID villagerUuid;
        final UUID ownerUuid;
        final ResourceKey<Level> dimension;
        int ticksRemaining;

        Pending(UUID villagerUuid, UUID ownerUuid, ResourceKey<Level> dimension, int ticksRemaining) {
            this.villagerUuid = villagerUuid;
            this.ownerUuid = ownerUuid;
            this.dimension = dimension;
            this.ticksRemaining = ticksRemaining;
        }
    }
}
