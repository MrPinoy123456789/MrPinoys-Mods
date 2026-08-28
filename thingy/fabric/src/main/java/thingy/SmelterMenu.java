package thingy;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Repairable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Smelter's screen: another {@code MenuType.GRINDSTONE} client rendering,
 * this time recycling metal armour and weapons back into nuggets instead of
 * saving enchantments.
 *
 * <p>Material is identified through {@link net.minecraft.core.component.DataComponents#REPAIRABLE}
 * rather than an id prefix: whatever ingot the game already considers valid for
 * repairing the item is the material it melts down into. That is also why
 * diamond and leather gear are refused: diamond is not a metal and leather has
 * no ingot to reduce to, so neither carries a repair item this class recognises.
 *
 * <p>Recovery is deliberately lossy: {@link #RECOVERY_RATE} halves the raw
 * crafting cost, and durability lost is value lost. A full-durability iron
 * chestplate (8 ingots) yields 36 nuggets (4 ingots' worth); one swung down to
 * a sliver of durability yields far less. This is a sink wearing a faucet's
 * clothes; it exists so "too many golden hoes" has an answer that isn't the
 * trash can, not so gearing up and melting down is a profitable loop.
 */
public final class SmelterMenu extends AbstractContainerMenu {

    private static final double RECOVERY_RATE = 0.5;
    private static final int NUGGETS_PER_INGOT = 9;

    /** Vanilla crafting cost in ingots, keyed by the item id's trailing category. */
    private static final Map<String, Integer> CATEGORY_COST = new LinkedHashMap<>();
    static {
        CATEGORY_COST.put("_sword", 1);
        CATEGORY_COST.put("_shovel", 1);
        CATEGORY_COST.put("_hoe", 2);
        CATEGORY_COST.put("_pickaxe", 3);
        CATEGORY_COST.put("_axe", 3);
        CATEGORY_COST.put("_boots", 4);
        CATEGORY_COST.put("_helmet", 5);
        CATEGORY_COST.put("_leggings", 7);
        CATEGORY_COST.put("_chestplate", 8);
    }

    private enum Material {
        IRON(Items.IRON_INGOT, Items.IRON_NUGGET),
        GOLD(Items.GOLD_INGOT, Items.GOLD_NUGGET);

        final Item ingot;
        final Item nugget;

        Material(Item ingot, Item nugget) {
            this.ingot = ingot;
            this.nugget = nugget;
        }
    }

    private final Container inputSlots = new SimpleContainer(2) {
        @Override
        public void setChanged() {
            super.setChanged();
            SmelterMenu.this.slotsChanged(this);
        }
    };
    private final Container resultSlots = new ResultContainer();

    public SmelterMenu(int containerId, Inventory inventory) {
        super(MenuType.GRINDSTONE, containerId);

        this.addSlot(new Slot(inputSlots, 0, 49, 19) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return material(stack) != null && categoryCost(stack) != null;
            }
        });
        this.addSlot(new Slot(inputSlots, 1, 49, 40) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return material(stack) != null && categoryCost(stack) != null;
            }
        });
        this.addSlot(new Slot(resultSlots, 2, 129, 34) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }

            @Override
            public void onTake(Player player, ItemStack carried) {
                inputSlots.setItem(0, ItemStack.EMPTY);
                inputSlots.setItem(1, ItemStack.EMPTY);
                inputSlots.setChanged();

                player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.LAVA_POP, SoundSource.BLOCKS, 0.3f, 1.2f);
                player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.BLOCKS, 0.25f, 1.0f);
            }
        });

        this.addStandardInventorySlots(inventory, 8, 84);
    }

    private static Material material(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        Repairable repairable = stack.get(DataComponents.REPAIRABLE);
        if (repairable == null) {
            return null;
        }
        for (Material candidate : Material.values()) {
            if (repairable.isValidRepairItem(new ItemStack(candidate.ingot))) {
                return candidate;
            }
        }
        return null;
    }

    private static Integer categoryCost(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String path = id.getPath();
        for (Map.Entry<String, Integer> entry : CATEGORY_COST.entrySet()) {
            if (path.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static double durabilityFraction(ItemStack stack) {
        if (!stack.isDamageableItem() || stack.getMaxDamage() <= 0) {
            return 1.0;
        }
        return (double) (stack.getMaxDamage() - stack.getDamageValue()) / stack.getMaxDamage();
    }

    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        if (container == inputSlots) {
            createResult();
        }
    }

    private void createResult() {
        ItemStack first = inputSlots.getItem(0);
        ItemStack second = inputSlots.getItem(1);

        if (first.isEmpty() && second.isEmpty()) {
            resultSlots.setItem(0, ItemStack.EMPTY);
            broadcastChanges();
            return;
        }

        Material firstMaterial = material(first);
        Material secondMaterial = material(second);

        if (!first.isEmpty() && !second.isEmpty() && firstMaterial != secondMaterial) {
            resultSlots.setItem(0, hint("Different metals don't mix.",
                    "Melt one kind at a time."));
            broadcastChanges();
            return;
        }

        Material material = first.isEmpty() ? secondMaterial : firstMaterial;
        if (material == null) {
            resultSlots.setItem(0, hint("Nothing to melt down.",
                    "Needs iron or golden armour or tools."));
            broadcastChanges();
            return;
        }

        double ingotValue = 0.0;
        if (!first.isEmpty()) {
            ingotValue += categoryCost(first) * durabilityFraction(first);
        }
        if (!second.isEmpty()) {
            ingotValue += categoryCost(second) * durabilityFraction(second);
        }

        int nuggets = Math.min(64, (int) Math.floor(ingotValue * NUGGETS_PER_INGOT * RECOVERY_RATE));
        if (nuggets <= 0) {
            resultSlots.setItem(0, hint("Too worn down to save.",
                    "Nothing worth reclaiming."));
            broadcastChanges();
            return;
        }

        ItemStack output = new ItemStack(material.nugget, nuggets);
        output.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Melted down for scrap.")
                        .withStyle(style -> style.withColor(ChatFormatting.DARK_GRAY).withItalic(false)),
                Component.literal("Yield scales with remaining durability.")
                        .withStyle(style -> style.withColor(ChatFormatting.DARK_GRAY).withItalic(false)))));
        resultSlots.setItem(0, output);
        broadcastChanges();
    }

    private ItemStack hint(String title, String detail) {
        ItemStack barrier = new ItemStack(Items.BARRIER);
        barrier.set(DataComponents.ITEM_NAME, Component.literal(title)
                .withStyle(style -> style.withColor(ChatFormatting.GRAY).withItalic(false)));
        barrier.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(detail)
                .withStyle(style -> style.withColor(ChatFormatting.DARK_GRAY).withItalic(false)))));
        return barrier;
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
            ItemStack first = inputSlots.getItem(0);
            ItemStack second = inputSlots.getItem(1);
            if (slotIndex == 2) {
                if (!this.moveItemStackTo(item, 3, 39, true)) {
                    return ItemStack.EMPTY;
                }
                slot.onQuickCraft(item, clicked);
            } else if (slotIndex != 0 && slotIndex != 1) {
                if (!first.isEmpty() && !second.isEmpty()) {
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
