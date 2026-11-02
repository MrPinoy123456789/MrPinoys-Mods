package charter.mc.book;

import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.land.LandItems;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/**
 * The Admin Book: the operators' counterpart of the Charter of Rights and Freedoms. Using it opens the operators' menu
 * ({@link AdminPages}), where every press checks again that the player is still an operator. It belongs only in an
 * operator's hands: anyone else holding one has it taken away on the next pass (a deopped operator, a stolen book), it
 * vanishes on death instead of dropping, and an operator keeps one copy. Operators are given one when they join or
 * respawn while {@code opsGetAdminBook} is on, never into hotbar slot 9, where the Charter book goes.
 */
public final class AdminBook {

    /** The value of the {@code charter} custom data key. */
    public static final String KIND = "adminbook";

    private AdminBook() {
    }

    public static ItemStack create() {
        ItemStack stack = new ItemStack(Items.PAPER);
        CompoundTag tag = new CompoundTag();
        tag.putString(LandItems.KEY, KIND);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.ITEM_MODEL, Identifier.withDefaultNamespace("knowledge_book"));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(Text.get("admin.book.name"))
                .withStyle(style -> style.withColor(ChatFormatting.RED).withItalic(false)));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                (Component) Component.literal(Text.get("admin.book.lore")).withStyle(ChatFormatting.GRAY))));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        stack.set(DataComponents.MAX_STACK_SIZE, 1);
        return stack;
    }

    public static boolean isBook(ItemStack stack) {
        return KIND.equals(LandItems.kindOf(stack));
    }

    /** An operator, by the same test the menu uses on every press. */
    public static boolean allowed(ServerPlayer player) {
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(player.createCommandSourceStack());
    }

    /** Main hand only. An operator gets the menu; anyone else loses the book. Public so a gametest can call it. */
    public static InteractionResult use(ServerPlayer player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || !isBook(player.getItemInHand(hand))) return InteractionResult.PASS;
        if (!allowed(player)) {
            player.setItemInHand(hand, ItemStack.EMPTY);
            player.sendSystemMessage(Component.literal(Text.get("admin.book.taken")).withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }
        if (!CharterServer.running()) {
            player.sendSystemMessage(Component.literal(Text.get("book.not_running")));
            return InteractionResult.SUCCESS_SERVER;
        }
        AdminPages.open(player);
        return InteractionResult.SUCCESS_SERVER;
    }

    /** Gives an Admin Book: the first free hotbar slot but the last, then anywhere, then at the feet. */
    public static void give(ServerPlayer player) {
        ItemStack book = create();
        Inventory inventory = player.getInventory();
        for (int i = 0; i < Inventory.SELECTION_SIZE - 1; i++) {
            if (inventory.getItem(i).isEmpty()) {
                inventory.setItem(i, book);
                return;
            }
        }
        BookMod.giveOrDrop(player, book);
    }

    /** On join and respawn: an operator who carries none gets one, when the knob is on. */
    public static void ensure(ServerPlayer player) {
        if (!CharterServer.running() || !CharterServer.runtime().knobs.opsGetAdminBook || !allowed(player)) return;
        if (count(player) == 0) give(player);
    }

    public static int count(ServerPlayer player) {
        return stacks(player).size();
    }

    /**
     * Every Admin Book stack the player carries, each once: the inventory, then any worn or held stack the inventory
     * does not already hold (the main hand is an inventory slot too, so a stack is compared by identity).
     */
    static List<ItemStack> stacks(ServerPlayer player) {
        List<ItemStack> out = new java.util.ArrayList<>();
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (isBook(inventory.getItem(i))) out.add(inventory.getItem(i));
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack worn = player.getItemBySlot(slot);
            if (isBook(worn) && out.stream().noneMatch(seen -> seen == worn)) out.add(worn);
        }
        return out;
    }

    /**
     * Once a second for everyone: a player who is not an operator loses every Admin Book; an operator keeps the first.
     * Returns how many were removed.
     */
    public static int police(ServerPlayer player) {
        boolean keepOne = allowed(player);
        List<ItemStack> books = stacks(player);
        int removed = 0;
        for (int n = keepOne ? 1 : 0; n < books.size(); n++) {
            // Emptying the stack removes it wherever it sits: an inventory slot, the off hand or armour.
            books.get(n).setCount(0);
            removed++;
        }
        if (player.containerMenu != null && isBook(player.containerMenu.getCarried()) && (!keepOne || !books.isEmpty())) {
            player.containerMenu.setCarried(ItemStack.EMPTY);
            removed++;
        }
        if (removed > 0) {
            player.containerMenu.broadcastChanges();
            if (!keepOne) player.sendSystemMessage(Component.literal(Text.get("admin.book.taken")).withStyle(ChatFormatting.RED));
        }
        return removed;
    }

    /** Before death loot drops: the book never lies on the ground for someone else to take. */
    public static void vanish(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (isBook(inventory.getItem(i))) inventory.setItem(i, ItemStack.EMPTY);
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (isBook(player.getItemBySlot(slot))) player.setItemSlot(slot, ItemStack.EMPTY);
        }
    }

    static void register() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
            return use(serverPlayer, hand);
        });
    }
}
