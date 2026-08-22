package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * The one place a keystone comes back to a player, and the only place depletion
 * is decided.
 *
 * <p>U5's shipped notes are what forces this shape: {@code ExitReason} has
 * <em>two</em> constants, not five -- {@code rescue}, {@code purge} and
 * {@code dropMember} eject directly and never route through {@code Instances.exit}.
 * Hanging depletion off {@code ExitReason} would silently skip death and
 * disconnect, which are two of the four rows in U7 Stage 4's own table. So there
 * is one method here and {@code Instances} calls it from all four sites.
 *
 * <table>
 *   <caption>What each way out costs</caption>
 *   <tr><td>exit pad, in time</td><td>the offer they chose, or {@code +1} if they walked past all three</td></tr>
 *   <tr><td>exit pad, over time</td><td>{@code overtimeDepletion} (default 0: unchanged)</td></tr>
 *   <tr><td>death inside</td><td>{@code depletionOnDeath} (default 2)</td></tr>
 *   <tr><td>{@code /dungeon exit}</td><td>{@code depletionOnExit} (default 1)</td></tr>
 *   <tr><td>disconnect inside</td><td>{@code depletionOnDisconnect} (default 3)</td></tr>
 *   <tr><td>server purge or crash</td><td><strong>unchanged</strong> -- never punish a player for the server</td></tr>
 * </table>
 */
final class Keystones {

    /** Why a run ended, for the one purpose of deciding what the key costs. */
    enum Outcome {
        COMPLETED_IN_TIME,
        COMPLETED_OVER_TIME,
        DEATH,
        COMMAND,
        DISCONNECT,
        /** A purge, a shutdown, or anything else that is the server's fault. */
        SERVER;

        int depletion() {
            return switch (this) {
                case COMPLETED_IN_TIME, SERVER -> 0;
                case COMPLETED_OVER_TIME -> PocketDungeonsConfig.overtimeDepletion();
                case DEATH -> PocketDungeonsConfig.depletionOnDeath();
                case COMMAND -> PocketDungeonsConfig.depletionOnExit();
                case DISCONNECT -> PocketDungeonsConfig.depletionOnDisconnect();
            };
        }
    }

    private Keystones() {}

    /**
     * Hands a keystone back to a member who is leaving.
     *
     * <p>{@code alreadyGranted} is the escape hatch for the one path that has
     * already paid: a player who opened one of the three choice vaults was granted
     * that keystone from Java the moment vanilla recorded the claim, and must not
     * also receive a consolation one on the way out.
     *
     * @param player  may be null -- a disconnecting member is not there to be
     *                handed anything, and the level is parked on
     *                {@link DungeonLog} for their next login instead
     */
    static void returnTo(MinecraftServer server, UUID member, ServerPlayer player,
                         int level, Keystone.Affix affix, Outcome outcome,
                         boolean alreadyGranted) {
        if (level <= 0 || alreadyGranted) {
            return;
        }
        int returned = KeystoneMath.deplete(level, outcome.depletion(),
                affix == Keystone.Affix.FRAGILE, PocketDungeonsConfig.keystoneMaxLevel());

        // The affix does not survive a failure: it was the price of the extra
        // levels the player already banked when they took the offer, and carrying
        // Fragile forward forever would compound one bad night into every night
        // after it.
        ItemStack stack = Keystone.mint(returned);

        if (player == null) {
            DungeonLog.forServer(server).setPendingKeystone(member, returned);
            PocketDungeonsMod.LOG.info("Parked a level {} keystone for offline player {} ({})",
                    returned, member, outcome);
            return;
        }

        Payout.deliver(player, stack);
        if (returned < level) {
            player.sendSystemMessage(Component.literal(
                    "Your keystone is depleted: [" + level + "] -> [" + returned + "].")
                    .withStyle(ChatFormatting.RED));
        } else {
            player.sendSystemMessage(Component.literal(
                    "Your keystone comes back at [" + returned + "].")
                    .withStyle(ChatFormatting.AQUA));
        }
    }

    /**
     * Grants any keystone that was parked while this player was offline.
     *
     * <p>Deliberately additive: if they somehow already hold one, they get both.
     * Two keystones is not a bug worth writing code to prevent; a lost one is.
     */
    static void drainPending(MinecraftServer server, ServerPlayer player) {
        DungeonLog log = DungeonLog.forServer(server);
        int level = log.takePendingKeystone(player.getUUID());
        if (level <= 0) {
            return;
        }
        Payout.deliver(player, Keystone.mint(level));
        player.sendSystemMessage(Component.literal(
                "Your keystone found its way back to you: [" + level + "].")
                .withStyle(ChatFormatting.AQUA));
    }
}
