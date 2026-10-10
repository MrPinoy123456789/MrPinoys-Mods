package pocketdungeons;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/**
 * The Room Editor Kit: a single item issued to the operator when they enter
 * a build room. Right-clicking it opens an enderchest-style GUI stocked with
 * special gameplay blocks, tools, and editor functions.
 *
 * <p>The kit is a palette, not a chest. Special blocks restock on every open.
 * Tools restock only if the slot is empty, to prevent duplicate accumulation.
 *
 * <p>See {@code docs/reference/ROOM_AUTHORING_SPEC.md} section 4.1-4.2.
 */
public final class RoomEditorKit {

    /** The NBT key on the kit item that identifies it as a Room Editor Kit. */
    static final String KIT_KEY = "pd_room_editor_kit";

    /** The NBT key on kit-provided items that marks them as editor-authored. */
    public static final String AUTHORED_KEY = "pd_authored";

    /** The NBT key on the inspector tool that identifies it. */
    static final String INSPECTOR_KEY = "pd_inspector";

    /** The NBT key on the annotation tool that identifies it. */
    static final String ANNOTATION_KEY = "pd_annotation";

    private RoomEditorKit() {}

    /**
     * Creates the kit item stack. The item is a renamed ender chest with
     * custom NBT so it can be detected on right-click and on inventory scans.
     */
    static ItemStack createKitItem() {
        ItemStack stack = new ItemStack(Items.ENDER_CHEST);
        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal("Room Editor Kit")
                        .withStyle(ChatFormatting.LIGHT_PURPLE)
                        .withStyle(s -> s.withItalic(false)));
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(KIT_KEY, true));
        return stack;
    }

    /**
     * Returns true if the given stack is a Room Editor Kit item.
     */
    static boolean isKitItem(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() != Items.ENDER_CHEST) {
            return false;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBooleanOr(KIT_KEY, false);
    }

    /**
     * Gives the player a kit item if they do not already have one.
     * Called when the player enters a build room. If the inventory is full,
     * the kit is dropped at the player's feet so it is never lost.
     */
    static void issueKit(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (isKitItem(player.getInventory().getItem(i))) {
                return;
            }
        }
        ItemStack kit = createKitItem();
        if (!player.getInventory().add(kit)) {
            // Inventory full: drop the remainder at the player's feet.
            player.drop(kit, false);
        }
    }

    /**
     * Removes all kit items from the player's inventory.
     * Called when the player exits a build room.
     */
    static void removeKit(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (isKitItem(player.getInventory().getItem(i))) {
                player.getInventory().setItem(i, ItemStack.EMPTY);
            }
        }
    }

    /**
     * Opens the kit GUI for the player. The GUI is a 6-row chest (54 slots)
     * with special blocks in slots 0-18, tools in 19-20, and functions in 27-31.
     */
    static void openKitGui(ServerPlayer player) {
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x6, player, false);
        gui.setTitle(Component.literal("Room Editor Kit"));

        // ---- Special blocks (slots 0-18) ----
        gui.setSlot(0, authoredBlock(Items.TRIAL_SPAWNER, "Trial Spawner",
                "Place in the room. Reconfigured at stamp time.", 4));
        gui.setSlot(1, authoredBlock(Items.VAULT, "Vault",
                "Place in the room. Reconfigured at stamp time.", 2));
        gui.setSlot(2, authoredBlock(Items.CHEST, "Chest",
                "Supply or loot chest. Role set via inspector.", 8));
        gui.setSlot(3, authoredBlock(Items.JIGSAW, "Spawn Jigsaw",
                "pocketdungeons:spawn anchor. Legacy fallback.", 4));
        gui.setSlot(4, authoredBlock(Items.JIGSAW, "Door Jigsaw",
                "pocketdungeons:door anchor. Snaps to canonical slots.", 8));

        // Sculk omen sources (vanilla blocks, also available from creative inventory).
        gui.setSlot(16, authoredBlock(Items.SCULK_SENSOR, "Sculk Sensor",
                "Omen source. Active when pressure: omen in metadata.", 4));
        gui.setSlot(17, authoredBlock(Items.SCULK_SHRIEKER, "Sculk Shrieker",
                "Omen source. Active when pressure: omen in metadata.", 2));
        gui.setSlot(18, authoredBlock(Items.BARRIER, "Barrier",
                "Annotation marker. Not saved with the template.", 16));

        // Fill empty block slots (5-15) with gray panes.
        for (int i = 5; i < 16; i++) {
            gui.setSlot(i, filler());
        }

        // ---- Tools (slots 19-20) ----
        gui.setSlot(19, tool(Items.STICK, "Inspector",
                "Right-click a placed block to open its properties.", INSPECTOR_KEY));
        gui.setSlot(20, tool(Items.WRITABLE_BOOK, "Annotations",
                "Right-click a block to attach a floating note.", ANNOTATION_KEY));

        // Fill empty tool slots (21-26) with gray panes.
        for (int i = 21; i < 27; i++) {
            gui.setSlot(i, filler());
        }

        // ---- Functions (slots 27-31) ----
        gui.setSlot(27, function(Items.ENCHANTED_BOOK, "Room Metadata",
                "Opens the room metadata GUI.",
                (index, clickType, actionType, g) -> {
                    g.close();
                    RoomEditorMetadata.open(player);
                }));
        gui.setSlot(28, function(Items.EMERALD, "Test",
                "Opens the test-stamp dialog.", null));
        gui.setSlot(29, function(Items.SUNFLOWER, "Validate",
                "Runs validation and reports in chat.",
                (index, clickType, actionType, g) -> {
                    g.close();
                    RoomValidator.validateAndReport(player);
                }));
        gui.setSlot(30, function(Items.NETHER_STAR, "Save",
                "Saves the room. Prompts for a name.",
                (index, clickType, actionType, g) -> {
                    g.close();
                    player.sendSystemMessage(Component.literal(
                            "Type /dungeon roombuilder save <name> to save the room.")
                            .withStyle(ChatFormatting.AQUA));
                }));
        gui.setSlot(31, function(Items.BARRIER, "Close",
                "Closes this GUI.",
                (index, clickType, actionType, g) -> g.close()));

        // Fill remaining slots (32-53) with gray panes.
        for (int i = 32; i < 54; i++) {
            gui.setSlot(i, filler());
        }

        gui.open();
    }

    /**
     * Returns true if the given stack is the inspector tool.
     */
    static boolean isInspector(ItemStack stack) {
        return hasToolKey(stack, INSPECTOR_KEY);
    }

    private static boolean hasToolKey(ItemStack stack, String key) {
        if (stack.isEmpty()) {
            return false;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBooleanOr(key, false);
    }

    /**
     * Creates a special block element for the kit GUI. The item carries the
     * {@link #AUTHORED_KEY} flag so the placement listener can transfer it
     * to the placed block entity.
     */
    private static GuiElementBuilder authoredBlock(Item item, String name, String lore, int count) {
        GuiElementBuilder builder = new GuiElementBuilder(item, count);
        builder.setName(Component.literal(name).withStyle(ChatFormatting.AQUA)
                .withStyle(s -> s.withItalic(false)));
        builder.addLoreLine(Component.literal(lore).withStyle(ChatFormatting.GRAY)
                .withStyle(s -> s.withItalic(false)));
        // Attach the pd_authored flag via custom data component.
        builder.setComponent(DataComponents.CUSTOM_DATA,
                CustomData.of(taggedAuthoredData()));
        return builder;
    }

    /**
     * Creates a tool element for the kit GUI. Tools are not consumed on use.
     * Tagged with custom data so the UseBlockCallback can identify them.
     */
    private static GuiElementBuilder tool(Item item, String name, String lore, String toolKey) {
        GuiElementBuilder builder = new GuiElementBuilder(item);
        builder.setName(Component.literal(name).withStyle(ChatFormatting.YELLOW)
                .withStyle(s -> s.withItalic(false)));
        builder.addLoreLine(Component.literal(lore).withStyle(ChatFormatting.GRAY)
                .withStyle(s -> s.withItalic(false)));
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(toolKey, true);
        builder.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return builder;
    }

    /**
     * Creates a function element for the kit GUI. Clicking it runs the callback.
     */
    private static GuiElementBuilder function(Item item, String name, String lore,
                                               eu.pb4.sgui.api.elements.GuiElement.ClickCallback callback) {
        GuiElementBuilder builder = new GuiElementBuilder(item);
        builder.setName(Component.literal(name).withStyle(ChatFormatting.GREEN)
                .withStyle(s -> s.withItalic(false)));
        builder.addLoreLine(Component.literal(lore).withStyle(ChatFormatting.GRAY)
                .withStyle(s -> s.withItalic(false)));
        if (callback != null) {
            builder.setCallback(callback);
        }
        return builder;
    }

    private static GuiElementBuilder filler() {
        return new GuiElementBuilder(Items.GLASS_PANE)
                .setName(Component.literal(""));
    }

    /**
     * Builds a CompoundTag with the pd_authored flag, for attaching to
     * kit-provided block items via the CUSTOM_DATA component.
     */
    private static CompoundTag taggedAuthoredData() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(AUTHORED_KEY, true);
        return tag;
    }
}
