package dailyquests;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.List;

import org.slf4j.Logger;

/**
 * The turn-in itself.
 *
 * <p>The one rule that matters here: count everything before removing anything.
 * A turn-in that consumes half the wheat and then fails is worse than no turn-in.
 */
public final class TurnIn {

    private static final Logger LOG = LogUtils.getLogger();

    private final Quests quests;
    private final DailyState state;

    public TurnIn(Quests quests, DailyState state) {
        this.quests = quests;
        this.state = state;
    }

    public Component attempt(ServerPlayer player) {
        String today = DailyState.dayKey(quests.settings().rolloverHourUtc());
        Quests.Quest quest = quests.forDay(today);

        if (quest == null) {
            return error("There's no quest today. Tell an admin the pool is empty.");
        }
        if (state.hasCompleted(player.getUUID(), today)) {
            return error("You've already done today's quest. Come back tomorrow.");
        }

        Item item = lookup(quest.item());
        if (item == null) {
            DailyQuestsMod.LOG.error("Quest names an unknown item: {}", quest.item());
            return error("Today's quest is misconfigured. Tell an admin.");
        }

        Inventory inventory = player.getInventory();
        int held = count(inventory, item);
        if (held < quest.count()) {
            return error("Not yet — you're holding " + held + " of what it wants, and it wants "
                    + quest.count() + ".");
        }

        remove(inventory, item, quest.count());

        int streak = state.complete(player.getUUID(),
                player.getName().getString(), today, quests.settings().graceDays());

        List<Quests.StreakReward> rewards = quests.settings().rewards();
        int diamonds = diamondsForStreak(rewards, streak);
        int maxDay = maxRewardDay(rewards);

        grant(player, Items.DIAMOND, diamonds);
        Chime.questTurnedIn(player);

        player.sendSystemMessage(Component.literal("Correct — it wanted " + quest.answer() + ".")
                .withStyle(ChatFormatting.GREEN));
        player.sendSystemMessage(Component.literal("Streak: " + streak + " day"
                        + (streak == 1 ? "" : "s"))
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal("   +" + diamonds + " diamond"
                                + (diamonds == 1 ? "" : "s"))
                        .withStyle(ChatFormatting.AQUA)));

        if (streak >= maxDay && maxDay > 0) {
            player.sendSystemMessage(Component.literal(
                            "You're at the top of the streak reward. Don't break it.")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        if (isMilestone(quests.settings().milestoneList(), streak)) {
            broadcastStreak(player, streak);
        }
        return null;
    }

    // ---- inventory --------------------------------------------------------

    /**
     * Counts across the whole inventory using the {@code Container} methods, which
     * have been stable far longer than the inventory's own internals.
     */
    private static int count(Inventory inventory, Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Only ever called once the count is known to be sufficient. */
    private static void remove(Inventory inventory, Item item, int wanted) {
        int remaining = wanted;
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !stack.is(item)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            inventory.removeItem(slot, take);
            remaining -= take;
        }
    }

    private static void grant(ServerPlayer player, Item item, int count) {
        int remaining = count;
        while (remaining > 0) {
            int size = Math.min(remaining, item.getDefaultMaxStackSize());
            ItemStack stack = new ItemStack(item, size);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            remaining -= size;
        }
    }

    // ---- streak helpers ---------------------------------------------------

    /** Highest diamonds on the ladder whose day is at or below the streak. */
    public static int diamondsForStreak(List<Quests.StreakReward> rewards, int streak) {
        int best = 0;
        for (Quests.StreakReward reward : rewards) {
            if (reward.day() <= streak && reward.diamonds() > best) {
                best = reward.diamonds();
            }
        }
        return best;
    }

    /** True when a streak should be announced to the server. */
    public static boolean isMilestone(List<Integer> milestones, int streak) {
        if (streak <= 0) {
            return false;
        }
        if (milestones != null && milestones.contains(streak)) {
            return true;
        }
        return streak % 30 == 0;
    }

    /** The largest day in the ladder, or 0 if the ladder is empty. */
    public static int maxRewardDay(List<Quests.StreakReward> rewards) {
        int max = 0;
        for (Quests.StreakReward reward : rewards) {
            if (reward.day() > max) {
                max = reward.day();
            }
        }
        return max;
    }

    private static void broadcastStreak(ServerPlayer player, int streak) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        String name = player.getName().getString();
        String dayLabel = streak == 1 ? "day" : "days";
        server.getPlayerList().broadcastSystemMessage(
                Component.literal(name + " is on a " + streak + "-" + dayLabel + " streak.")
                        .withStyle(ChatFormatting.GOLD),
                false);
        LOG.info("{} reached a {}-day daily quest streak", name, streak);
    }

    /**
     * Resolves a namespaced id to an item. Isolated here because registry lookup is
     * the thing most likely to be renamed between versions — 26.x alone renamed
     * {@code ResourceLocation} to {@code Identifier}. If this stops compiling, this
     * is the only place to change.
     *
     * <p>The item registry is defaulted, so an unknown id returns air rather than
     * null. That is the misconfiguration case, not a valid quest.
     */
    private static Item lookup(String id) {
        try {
            Identifier key = Identifier.parse(id);
            Item item = BuiltInRegistries.ITEM.getValue(key);
            return item == Items.AIR ? null : item;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Component error(String text) {
        return Component.literal(text).withStyle(ChatFormatting.RED);
    }
}