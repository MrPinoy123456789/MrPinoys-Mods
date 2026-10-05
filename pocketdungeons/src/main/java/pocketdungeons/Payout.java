package pocketdungeons;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * The one remaining reward hook this mod hands an operator: an optional command
 * run once per completing member.
 *
 * <p>U8 deletes the item grant outright -- the reward room's chests are the
 * payout now, stamped with real loot tables at stamp time (see {@code Instances}
 * and {@code TrialContent}). {@code payoutCommand} survives because it is the
 * only remaining way an operator routes a reward through {@code cobbleeconomy}'s
 * own command surface without this mod taking a Java dependency on it -- the
 * suite's whole coupling model (cross-cutting section 6: never a hard reference).
 */
final class Payout {

    private Payout() {}

    /**
     * Hands one already-built stack over, giving what fits and dropping the rest
     * at the player's feet rather than voiding it.
     *
     * <p>The test is {@code stack.isEmpty()} afterwards, <em>not</em> the boolean
     * {@code Inventory.add} returns. Its bytecode loops {@code addResource} until
     * the stack is empty or it stops making progress, then answers "did I move
     * <em>any</em> of this" -- so a stack that half fits returns {@code true} and
     * leaves the remainder sitting in the stack we were given. The idiom
     * {@code if (!add(stack))} silently destroys that remainder, and a nearly-full
     * inventory at the end of a run is exactly when it happens.
     */
    static void deliver(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        // PD-146: a reward handed over directly never passed the item-entity
        // or loot-table caps (a bought iron sword kept its vanilla 250).
        DungeonTools.capInPlace(stack);
        player.getInventory().add(stack);
        if (!stack.isEmpty()) {
            int leftover = stack.getCount();
            player.drop(stack, false);
            PocketDungeonsMod.LOG.info("{} could not hold their reward; {} x{} dropped at their feet",
                    player.getName().getString(), stack.getItem(), leftover);
        }
    }

    /**
     * Runs the optional operator command, from the server console's own source so
     * it does not depend on the player's permission level. Output is deliberately
     * not suppressed: a payoutCommand that is broken should be visible to whoever
     * configured it.
     *
     * <p>{@code %amount%} is gone -- there is no item count any more, and a chest
     * count is not a currency amount, so the old name would be a lie.
     *
     * @param level  the keystone level the run was finished at
     * @param chests how many of the reward room's three chests were earned
     */
    static void runPayoutCommand(ServerPlayer player, int level, int chests) {
        String template = PocketDungeonsConfig.payoutCommand();
        if (template == null || template.isBlank()) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        String command = substitute(template, player.getName().getString(), level, chests);
        try {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
        } catch (Exception e) {
            PocketDungeonsMod.LOG.error("payoutCommand '{}' failed for {}", command,
                    player.getName().getString(), e);
        }
    }

    /**
     * (M44.4) The template-fill step of {@link #runPayoutCommand}, split out
     * as pure string substitution so it is testable without a
     * {@code ServerPlayer} or {@code MinecraftServer}. Every placeholder can
     * appear more than once or not at all; {@code String.replace} handles
     * both without help.
     */
    static String substitute(String template, String playerName, int level, int chests) {
        return template
                .replace("%player%", playerName)
                .replace("%level%", Integer.toString(level))
                .replace("%chests%", Integer.toString(chests));
    }
}
