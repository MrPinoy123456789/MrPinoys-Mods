package thingy;

import it.unimi.dsi.fastutil.objects.Object2IntMap.Entry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.List;

/**
 * The Disenchanter's screen: a {@code MenuType.GRINDSTONE} client rendering
 * dressed up with different rules server-side. See {@code SPEC.md} in the
 * standalone disenchanter design doc for the reasoning; this is that design,
 * shipped as a wondrous item instead of its own mod.
 *
 * <p>Slot 0: the enchanted item ("the victim"). Slot 1: a blank book ("the
 * vessel"). Slot 2: a virtual preview of the enchanted book, never insertable,
 * and only pickable when the transaction is actually legal, which is where
 * {@link #mayPickup} does the real gatekeeping rather than a click handler.
 *
 * <p>Cost reuses the anvil's own charge path, {@link Player#onEnchantmentPerformed},
 * so a creative player pays nothing exactly the way an anvil costs nothing for them,
 * and the number on the tooltip is never a lie the deduction disagrees with.
 */
public final class DisenchantMenu extends AbstractContainerMenu {

    private final Container inputSlots = new SimpleContainer(2) {
        @Override
        public void setChanged() {
            super.setChanged();
            DisenchantMenu.this.slotsChanged(this);
        }
    };
    private final Container resultSlots = new ResultContainer();
    private final ServerPlayer player;

    /** Recomputed on every {@link #createResult()}; read by {@link #mayPickup} and the take handler. */
    private int cost = 0;
    private boolean valid = false;

    public DisenchantMenu(int containerId, Inventory inventory, ServerPlayer player) {
        super(MenuType.GRINDSTONE, containerId);
        this.player = player;

        this.addSlot(new Slot(inputSlots, 0, 49, 19) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return !stack.is(Items.ENCHANTED_BOOK) && EnchantmentHelper.hasAnyEnchantments(stack);
            }
        });
        this.addSlot(new Slot(inputSlots, 1, 49, 40) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.is(Items.BOOK);
            }
        });
        this.addSlot(new Slot(resultSlots, 2, 129, 34) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }

            @Override
            public boolean mayPickup(Player takingPlayer) {
                return DisenchantMenu.this.valid
                        && (DisenchantMenu.this.player.getAbilities().instabuild
                                || DisenchantMenu.this.player.experienceLevel >= DisenchantMenu.this.cost);
            }

            @Override
            public void onTake(Player takingPlayer, ItemStack carried) {
                complete(carried);
            }
        });

        this.addStandardInventorySlots(inventory, 8, 84);
    }

    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        if (container == inputSlots) {
            createResult();
        }
    }

    private void createResult() {
        ItemStack victim = inputSlots.getItem(0);
        ItemStack vessel = inputSlots.getItem(1);
        valid = false;
        cost = 0;

        if (victim.isEmpty() || vessel.isEmpty()) {
            resultSlots.setItem(0, ItemStack.EMPTY);
            broadcastChanges();
            return;
        }

        ItemEnchantments source = EnchantmentHelper.getEnchantmentsForCrafting(victim);
        ItemEnchantments.Mutable kept = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        int computedCost = 0;
        boolean hadCurse = false;

        for (Entry<Holder<Enchantment>> entry : source.entrySet()) {
            Holder<Enchantment> enchantment = entry.getKey();
            int level = entry.getIntValue();
            if (enchantment.is(EnchantmentTags.CURSE)) {
                hadCurse = true;
                continue;
            }
            kept.set(enchantment, level);
            computedCost += enchantment.value().getAnvilCost() * level;
        }

        ItemEnchantments finalEnchantments = kept.toImmutable();
        if (finalEnchantments.isEmpty()) {
            resultSlots.setItem(0, hint(hadCurse));
            broadcastChanges();
            return;
        }

        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        EnchantmentHelper.setEnchantments(book, finalEnchantments);

        boolean canAfford = player.getAbilities().instabuild || player.experienceLevel >= computedCost;
        List<Component> lore = new ArrayList<>();
        lore.add(Component.literal("Cost: " + computedCost + " level" + (computedCost == 1 ? "" : "s"))
                .withStyle(style -> style.withColor(canAfford ? ChatFormatting.GREEN : ChatFormatting.RED)
                        .withItalic(false)));
        if (!canAfford) {
            lore.add(Component.literal("You have " + player.experienceLevel)
                    .withStyle(style -> style.withColor(ChatFormatting.RED).withItalic(false)));
        }
        lore.add(Component.literal(victimName(victim) + " is destroyed.")
                .withStyle(style -> style.withColor(ChatFormatting.DARK_RED).withItalic(false)));
        if (hadCurse) {
            lore.add(Component.literal("Curses will not transfer.")
                    .withStyle(style -> style.withColor(ChatFormatting.GRAY).withItalic(false)));
        }
        book.set(DataComponents.LORE, new ItemLore(lore));

        cost = computedCost;
        valid = true;
        resultSlots.setItem(0, book);
        broadcastChanges();
    }

    private String victimName(ItemStack victim) {
        return victim.getHoverName().getString();
    }

    private ItemStack hint(boolean curseOnly) {
        ItemStack barrier = new ItemStack(Items.BARRIER);
        barrier.set(DataComponents.ITEM_NAME, Component.literal(
                curseOnly ? "Nothing to save" : "Nothing enchanted here")
                .withStyle(style -> style.withColor(ChatFormatting.GRAY).withItalic(false)));
        barrier.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(
                curseOnly ? "Curses cannot be saved; only buried."
                        : "Needs an enchanted item and a book.")
                .withStyle(style -> style.withColor(ChatFormatting.DARK_GRAY).withItalic(false)))));
        return barrier;
    }

    /** Runs at the moment the (already-validated) preview book leaves the result slot. */
    private void complete(ItemStack producedBook) {
        if (!player.getAbilities().instabuild) {
            player.onEnchantmentPerformed(producedBook, cost);
        }
        inputSlots.setItem(0, ItemStack.EMPTY);
        inputSlots.getItem(1).shrink(1);
        inputSlots.setChanged();

        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 0.3f, 1.0f);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 0.25f, 1.1f);

        cost = 0;
        valid = false;
    }

    @Override
    public void removed(Player exitingPlayer) {
        super.removed(exitingPlayer);
        this.clearContainer(exitingPlayer, this.inputSlots);
    }

    @Override
    public boolean stillValid(Player checkedPlayer) {
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player movingPlayer, int slotIndex) {
        ItemStack clicked = ItemStack.EMPTY;
        Slot slot = this.slots.get(slotIndex);
        if (slot != null && slot.hasItem()) {
            ItemStack item = slot.getItem();
            clicked = item.copy();
            ItemStack victim = inputSlots.getItem(0);
            ItemStack vessel = inputSlots.getItem(1);
            if (slotIndex == 2) {
                if (!this.moveItemStackTo(item, 3, 39, true)) {
                    return ItemStack.EMPTY;
                }
                slot.onQuickCraft(item, clicked);
            } else if (slotIndex != 0 && slotIndex != 1) {
                if (!victim.isEmpty() && !vessel.isEmpty()) {
                    if (slotIndex >= 3 && slotIndex < 30) {
                        if (!this.moveItemStackTo(item, 30, 39, false)) {
                            return ItemStack.EMPTY;
                        }
                    } else if (slotIndex >= 30 && slotIndex < 39 && !this.moveItemStackTo(item, 3, 30, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (!this.moveItemStackTo(item, 0, 2, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(item, 3, 39, false)) {
                return ItemStack.EMPTY;
            }

            if (item.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }

            if (item.getCount() == clicked.getCount()) {
                return ItemStack.EMPTY;
            }

            slot.onTake(movingPlayer, item);
        }
        return clicked;
    }
}
