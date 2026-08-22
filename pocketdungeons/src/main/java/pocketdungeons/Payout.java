package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * What a completed run pays.
 *
 * <p>The one ordering rule that matters here: the grant happens <em>after</em>
 * the player has been teleported out, at their return point. An item that will
 * not fit in a full inventory is dropped at the player's feet, and feet still
 * standing on the exit pad are inside a dungeon that teardown is already
 * clearing -- which would destroy the reward silently.
 *
 * <p>There is no Java dependency on any economy mod. {@code payoutCommand} is
 * the integration layer: an operator who would rather route rewards through
 * cobbleeconomy's own command surface points it at one.
 */
final class Payout {

    private static final ConfiguredItem ITEM = new ConfiguredItem("payoutItem",
            PocketDungeonsConfig::payoutItem,
            "completed runs will pay no item. payoutCommand, if set, still runs.");

    private Payout() {}

    /**
     * Pays one member for one completed run.
     *
     * @return how many items were granted, for the completion message
     */
    static int grant(ServerPlayer player, InstanceLayout layout, int streak) {
        int count = PayoutMath.count(
                PocketDungeonsConfig.payoutBaseCount(),
                PocketDungeonsConfig.payoutPerTier(),
                layout.lootTier(),
                streak,
                PocketDungeonsConfig.streakBonusPercent(),
                PocketDungeonsConfig.streakBonusCapPercent());

        int granted = 0;
        Item item = ITEM.get();
        if (item != null && count > 0) {
            granted = count;
            giveOrDrop(player, item, count);
        }

        runPayoutCommand(player, granted);
        return granted;
    }

    /** The item a payout is made of, or null if {@code payoutItem} does not resolve. */
    static Item item() {
        return ITEM.get();
    }

    /**
     * Give what fits, drop the rest at the player's feet rather than voiding it.
     *
     * <p>The test is {@code stack.isEmpty()} afterwards, <em>not</em> the boolean
     * {@code Inventory.add} returns. Its bytecode loops {@code addResource} until
     * the stack is empty or it stops making progress, then answers "did I move
     * <em>any</em> of this" -- so a payout that half fits returns {@code true} and
     * leaves the remainder sitting in the stack we were given. The suite's usual
     * {@code if (!add(stack))} idiom silently destroys that remainder, and a
     * nearly-full inventory at the end of a run is exactly when it happens.
     *
     * <p>The stack split above is for a {@code payoutItem} that does not stack to
     * 64; the default emerald payout is well inside one stack.
     */
    private static void giveOrDrop(ServerPlayer player, Item item, int count) {
        int remaining = count;
        int max = Math.max(1, new ItemStack(item).getMaxStackSize());
        while (remaining > 0) {
            int size = Math.min(max, remaining);
            remaining -= size;
            ItemStack stack = new ItemStack(item, size);
            player.getInventory().add(stack);
            if (!stack.isEmpty()) {
                int leftover = stack.getCount();
                player.drop(stack, false);
                PocketDungeonsMod.LOG.info("{}'s payout did not fit; {} x{} dropped at their feet",
                        player.getName().getString(), item, leftover);
            }
        }
    }

    /**
     * Runs the optional operator command, from the server console's own source so
     * it does not depend on the player's permission level. Output is deliberately
     * not suppressed: a payoutCommand that is broken should be visible to whoever
     * configured it.
     */
    private static void runPayoutCommand(ServerPlayer player, int amount) {
        String template = PocketDungeonsConfig.payoutCommand();
        if (template == null || template.isBlank()) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        String command = template
                .replace("%player%", player.getName().getString())
                .replace("%amount%", Integer.toString(amount));
        try {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
        } catch (Exception e) {
            PocketDungeonsMod.LOG.error("payoutCommand '{}' failed for {}", command,
                    player.getName().getString(), e);
        }
    }

    /**
     * The completion line. The run number and the streak are the whole retention
     * mechanic, so they go in front of a player who never types
     * {@code /dungeon log}.
     */
    static void announce(ServerPlayer player, DungeonLog.Entry entry, int granted) {
        StringBuilder text = new StringBuilder("You escape with the loot. Run #")
                .append(entry.runsCompleted())
                .append(" - streak ").append(entry.streak());
        if (granted > 0) {
            text.append(" - ").append(granted).append(' ')
                    .append(itemName(granted));
        }
        player.sendSystemMessage(Component.literal(text.toString())
                .withStyle(ChatFormatting.GOLD));
    }

    /** Plain-text item name, pluralised the crude way English mostly allows. */
    private static String itemName(int count) {
        Item item = ITEM.get();
        String name = item == null
                ? "reward" : new ItemStack(item).getHoverName().getString().toLowerCase();
        return count == 1 || name.endsWith("s") ? name : name + "s";
    }
}
