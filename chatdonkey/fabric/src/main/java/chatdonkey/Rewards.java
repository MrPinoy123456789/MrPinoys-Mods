package chatdonkey;

import chatdonkey.core.Gift;
import chatdonkey.core.GiftItems;
import chatdonkey.core.GiftTier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/**
 * The suite's giveOrDrop rule: add to the inventory, drop at the player's feet
 * if it will not fit. A reward is never destroyed (DESIGN.md section 4.1).
 */
public final class Rewards {

    private Rewards() {}

    /**
     * Hands over a resolved gift (SPEC.md section 5).
     *
     * <p>The named items use {@code item_name} and {@code lore} components only
     * -- no {@code custom_data}, because nothing here has behavior. A vanilla
     * client renders both without installing anything.
     */
    public static void giveGift(ServerPlayer player, Gift gift, RandomSource random) {
        if (gift.tier() == GiftTier.GRUDGE) {
            // Nothing. The exit line is the gift.
            return;
        }

        // Every tier above grudge gets the base gift: a piece of enchanted
        // nonsense. Tier controls how much nonsense, not how powerful it is.
        giveStack(player, enchantedJunk(player, gift.tier(), random));

        // ...and the better endings then get their extra ON TOP, rather than
        // instead. Earning the golden tier should never mean losing the thing
        // the tier below would have given you.
        switch (gift.tier()) {
            case GRACIOUS -> giveStack(player, apologyCarrot());
            case GOLDEN -> {
                giveStack(player, apologyCarrot());
                giveStack(player, guiltDiamond());
            }
            default -> {
                // STANDARD and SATISFIED get the enchanted item and nothing more.
            }
        }
    }

    /**
     * A cheap, silly item with nonsense enchantments on it (SPEC.md section 5).
     *
     * <p>The enchantment is written straight into the component rather than
     * applied through {@code EnchantmentHelper.enchantItem}, which deliberately
     * bypasses the applicability rules — that is the entire joke. A donkey that
     * hands you a correctly-enchanted fishing rod is a loot table; one that hands
     * you a Bowl of Bane of Arthropods is a character.
     *
     * <p>Nonsense enchantments are still real ones, so a grindstone or the
     * {@code wondrous} disenchanter can lift them off onto a book. The gift is a
     * joke that happens to be worth keeping.
     */
    private static ItemStack enchantedJunk(ServerPlayer player, GiftTier tier,
                                           RandomSource random) {
        Item item = BuiltInRegistries.ITEM.getValue(
                Identifier.parse(GiftItems.random(new java.util.Random(random.nextLong()))));
        ItemStack stack = new ItemStack(item, 1);

        Registry<Enchantment> registry =
                player.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);

        ItemEnchantments.Mutable enchantments =
                new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        int wanted = GiftItems.enchantmentsFor(tier);
        for (int i = 0; i < wanted; i++) {
            registry.getRandom(random).ifPresent(enchantment -> {
                int max = Math.max(1, enchantment.value().getMaxLevel());
                enchantments.set(enchantment, 1 + random.nextInt(max));
            });
        }
        EnchantmentHelper.setEnchantments(stack, enchantments.toImmutable());

        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("A gift. Of sorts.").withStyle(ChatFormatting.GRAY))));
        return stack;
    }

    /** A carrot the donkey is very slightly sorry with. */
    private static ItemStack apologyCarrot() {
        ItemStack stack = new ItemStack(Items.CARROT, 1);
        stack.set(DataComponents.ITEM_NAME,
                Component.literal("Apology Carrot").withStyle(ChatFormatting.YELLOW));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Given under protest.").withStyle(ChatFormatting.GRAY))));
        return stack;
    }

    /** The rare one. The donkey would like it on record that it does not feel bad. */
    private static ItemStack guiltDiamond() {
        ItemStack stack = new ItemStack(Items.DIAMOND, 1);
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("The donkey felt bad. Not really.")
                        .withStyle(ChatFormatting.GRAY))));
        return stack;
    }

    public static void giveCobblestone(ServerPlayer player, int count) {
        give(player, Items.COBBLESTONE, count);
    }

    /** Adds a stack to the inventory, dropping it at the player's feet if it will not fit. */
    static void giveStack(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    private static void give(ServerPlayer player, Item item, int count) {
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
}
