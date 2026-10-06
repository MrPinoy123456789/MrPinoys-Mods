package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;

/**
 * The one place a keystone comes back to a player, and the only place depletion
 * is decided.
 *
 * <p>Only {@code /dungeon quit} on a floor in progress costs levels
 * ({@code timedOutDepletion}, 2 by default). Disconnecting, dying, running
 * {@code /dungeon exit}, and a server purge all cost nothing. How well a floor
 * went is judged by omen when the interval banks, not here.
 */
final class Keystones {

    /** Why a run ended, for the one purpose of deciding what the key costs. */
    enum Outcome {
        /**
         * The owner quit the floor in progress with {@code /dungeon quit}.
         * Costs {@code timedOutDepletion}, a config key named for the clock
         * that used to charge it and kept so existing files still apply.
         */
        QUIT,
        /** Anything else: completed, left, died, disconnected, purged, restarted. */
        NO_CHANGE;

        int depletion() {
            return switch (this) {
                case QUIT -> PocketDungeonsConfig.timedOutDepletion();
                case NO_CHANGE -> 0;
            };
        }
    }

    private Keystones() {}

    /**
     * Settles a member's keystone as they leave, whatever way they left.
     *
     * <p>A state write, not a delivery. There is no offline path and nothing to
     * park: the level is written against the player's UUID and the remote in their
     * pocket catches up on the watcher's next reconcile, whether that is in one
     * tick or after a restart three days from now. That is the whole reason this
     * moved server-side -- see {@link Keystone}.
     *
     * @param player may be null for a member who is already gone; the write still
     *               happens, only the chat line is skipped
     */
    static void returnTo(MinecraftServer server, UUID member, ServerPlayer player,
                         int level, Set<String> affixes, Outcome outcome) {
        if (level <= 0) {
            return;
        }
        // M4 T4.3: the max across the set, capped at 2x, never the sum and never
        // the product. Two doubling affixes on one key still cost a double, or a
        // level-20 key would shed most of a ladder on one bad night.
        int returned = KeystoneMath.deplete(level, outcome.depletion(),
                AffixMath.depletionMultiplier(affixes, AffixManifest.current().definitions()),
                PocketDungeonsConfig.keystoneMaxLevel());

        // The elective affixes do not survive: they were the price of the extra
        // levels the player already banked when they took the offer, and carrying
        // Big L forward forever would compound one bad night into every night
        // after it. The seeded ones are not stored at all: they follow from the
        // level, so the returned key simply re-derives whatever its new level
        // earns.
        Set<String> none = Set.of();
        DungeonLog.forServer(server).setKeystone(member, returned, none);

        if (player == null) {
            PocketDungeonsMod.LOG.info("Keystone for absent player {} settled at level {} ({})",
                    member, returned, outcome);
            return;
        }

        Keystone.reconcile(player, returned,
                AffixMath.effective(member, returned, none, AffixManifest.current().definitions()));
        if (returned < level) {
            if (outcome == Outcome.QUIT) {
                Chime.keystoneDepleted(player);
            }
            player.sendSystemMessage(Component.literal(
                    "Your compass is depleted: [" + level + "] -> [" + returned + "].")
                    .withStyle(ChatFormatting.RED));
        }
    }

    /**
     * Writes the level an interval's settlement banked for {@code member} and
     * refreshes their remote. The counterpart to {@link #returnTo} for the path
     * that succeeded: one write, and the level is theirs from that instant, with
     * no item to hand over and therefore no way for a full inventory to eat it.
     *
     * <p>{@code level} is already the member's own: {@link RunLifecycle} adds
     * the levels {@link IntervalBanking} settled to each member's own key, so a
     * party member riding along at a lower level climbs from where they stand.
     * No elective affixes are written; what the new level's thresholds seed is
     * derived on every read, so it cannot go stale.
     *
     * @param player may be null for a member who is already gone; the write still
     *               happens, only the refresh and the cue are skipped
     */
    static void grantLevel(MinecraftServer server, UUID member, ServerPlayer player, int level) {
        DungeonLog.forServer(server).setKeystone(member, level, Set.of());
        if (player == null) {
            PocketDungeonsMod.LOG.info("Banked level {} for absent player {}", level, member);
            return;
        }
        Keystone.reconcile(player, level,
                AffixMath.effective(member, level, Set.of(), AffixManifest.current().definitions()));
        Chime.keystoneLevelUp(player);
    }
}
