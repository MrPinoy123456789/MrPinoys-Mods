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
 * <p>Running out of time downgrades the keystone by 2 levels but does not
 * close the dungeon: the player can still finish in overtime, though a late
 * finish banks no door bonus, so the key stays at its depleted level. A
 * completion finished after the clock adds no further penalty since the
 * timeout already applied it. Disconnecting, dying, running
 * {@code /dungeon exit}, and a server purge all cost nothing.
 */
final class Keystones {

    /** Why a run ended, for the one purpose of deciding what the key costs. */
    enum Outcome {
        /** The clock ran out before the run was completed. The harshest way to lose ground. */
        TIMED_OUT,
        /** Completed, but after the clock. Still counts, but banks no door bonus and costs levels. */
        LATE,
        /** Anything else -- completed in time, left, died, disconnected, purged, restarted. */
        NO_CHANGE;

        int depletion() {
            return switch (this) {
                case TIMED_OUT -> PocketDungeonsConfig.timedOutDepletion();
                case LATE -> PocketDungeonsConfig.lateCompletionDepletion();
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
            if (outcome == Outcome.TIMED_OUT || outcome == Outcome.LATE) {
                Chime.keystoneDepleted(player);
            }
            player.sendSystemMessage(Component.literal(
                    "Your keystone is depleted: [" + level + "] -> [" + returned + "].")
                    .withStyle(ChatFormatting.RED));
        }
    }

    /**
     * Writes a chosen door offer and refreshes the player's remote.
     *
     * <p>The counterpart to {@link #returnTo} for the path that succeeded: one
     * write, and the level the player picked is theirs from that instant, with no
     * item to hand over and therefore no way for a full inventory to eat it.
     */
    static void grantOffer(MinecraftServer server, UUID member, ServerPlayer player,
                           Keystone.Offer offer) {
        // Only the elective half is written. What the new level's thresholds hand
        // the player on top is derived on every read, so it cannot go stale and
        // needs no codec field of its own.
        int previousLevel = DungeonLog.forServer(server).get(member).keystoneLevel();
        DungeonLog.forServer(server).setKeystone(member, offer.level(), offer.affixes());
        // M34: a keystone level gain counts toward the owner's Keystone Climber
        // bounty. The owner is the instance owner if the member is in one, or
        // the member themselves otherwise (settling a pending offer outside an
        // instance still counts toward their own bounty).
        int delta = offer.level() - previousLevel;
        if (delta > 0) {
            InstanceRecord record = InstanceRegistry.byMember.get(member);
            UUID owner = record != null ? record.owner : member;
            BountyTracker.progress(server, owner,
                    BountyTracker.Bounty.KEYSTONE_CLIMBER.id, delta);
        }
        if (player == null) {
            PocketDungeonsMod.LOG.info("Offer for absent player {} settled at level {}",
                    member, offer.level());
            return;
        }
        Keystone.reconcile(player, offer.level(),
                AffixMath.effective(member, offer.level(), offer.affixes(),
                        AffixManifest.current().definitions()));
        Chime.keystoneLevelUp(player);
    }
}
