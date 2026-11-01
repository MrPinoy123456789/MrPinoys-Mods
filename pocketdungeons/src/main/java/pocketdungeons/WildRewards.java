package pocketdungeons;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * What a wild clear pays besides the dungeon's own chests, through one seam (owner, 2026-10-31): the item bonus of the
 * offering goes in the copper chest, and a currency sink gets one call with everything it needs to pay a player
 * currency and a kingdom currency. Nothing registers a sink yet, so nothing is paid until the currency bridge does.
 */
final class WildRewards {

    /** One clear, as the currency side sees it. */
    record Payout(WildState.Lodestone lodestone, WildDungeons.Offering offering, int level,
                  List<ServerPlayer> players) {}

    /** Receives every clear. Called once, on the server thread, after the chests are placed. */
    interface Sink {
        void paid(Payout payout);
    }

    private static Sink sink;

    private WildRewards() {}

    /** Installs the currency sink; {@code null} removes it. */
    static void setSink(Sink replacement) {
        sink = replacement;
    }

    /** The offering's bonus items that rolled, as stacks for the copper chest. Unknown ids are skipped. */
    static List<ItemStack> rollBonus(WildDungeons.Offering offering, RandomSource random) {
        List<ItemStack> out = new ArrayList<>();
        if (offering == null) {
            return out;
        }
        for (WildDungeons.Bonus bonus : offering.bonusRewards()) {
            if (random.nextDouble() >= bonus.chance()) {
                continue;
            }
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(bonus.item()));
            if (item == null || item == Items.AIR) {
                PocketDungeonsMod.LOG.warn("wild bonus reward {} on {} is not an item", bonus.item(), offering.id());
                continue;
            }
            out.add(new ItemStack(item, Math.max(1, bonus.count())));
        }
        return out;
    }

    /** Hands the clear to the sink, if any. A failing sink never blocks the clear. */
    static void pay(Payout payout) {
        if (sink == null) {
            return;
        }
        try {
            sink.paid(payout);
        } catch (RuntimeException e) {
            PocketDungeonsMod.LOG.error("The wild reward sink failed for lodestone {}", payout.lodestone().id, e);
        }
    }
}
