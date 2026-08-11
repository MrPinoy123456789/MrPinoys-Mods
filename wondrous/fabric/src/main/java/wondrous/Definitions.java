package wondrous;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MenuConstructor;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The item catalogue, as data.
 *
 * <p>Written this way so that adding an item is a list entry rather than a class.
 * Six of the seven differ only in which vanilla menu they open.
 *
 * <p>Names are noun phrases in Ellie's voice that say what the item is -- a name
 * that is only an in-joke leaves a player holding something they can't identify,
 * and a verb phrase reads like an instruction rather than a thing in your hand.
 * The flavour line underneath carries the joke instead.
 *
 * <p>The {@code id} strings are not in-jokes either: they are the API contract that
 * quest JSON and {@code /wondrous give} reference, so they stay descriptive and
 * they stay put. Rename a display name freely; renaming an id breaks every reward
 * pointing at it.
 */
public final class Definitions {

    private Definitions() {}

    /**
     * @param id        stable id, stamped into custom_data -- do not change
     * @param base      what a vanilla client renders
     * @param name      player-facing name
     * @param lore      one or more lines under the name
     * @param decorate  extra components (dye, durability); identity for most
     * @param menu      what right-clicking opens, or null for a passive item
     */
    public record Def(String id,
                      Item base,
                      Component name,
                      List<Component> lore,
                      UnaryOperator<ItemStack> decorate,
                      MenuConstructor menu,
                      AreaBreak.AreaTool area) {

        /** For items that aren't area tools -- which is all of them but two. */
        public Def(String id,
                   Item base,
                   Component name,
                   List<Component> lore,
                   UnaryOperator<ItemStack> decorate,
                   MenuConstructor menu) {
            this(id, base, name, lore, decorate, menu, null);
        }

        /** No menu to open on right-click. Area tools are passive in this sense. */
        public boolean isPassive() {
            return menu == null;
        }
    }

    public static final String FLYING_BOOTS_ID = "flying_boots";

    private static final UnaryOperator<ItemStack> PLAIN = UnaryOperator.identity();

    public static final List<Def> ALL = List.of(

            new Def(FLYING_BOOTS_ID,
                    Items.LEATHER_BOOTS,
                    name("Up Up And Bye Boots", ChatFormatting.LIGHT_PURPLE),
                    lore(voice("byeeeee"),
                         plain("Flight, while worn."),
                         plain("Take them off gently.")),
                    stack -> {
                        stack.set(DataComponents.DYED_COLOR, new DyedItemColor(0xB3312C));
                        // Removing max_damage makes the item non-damageable. Boots that
                        // wear out mid-flight are a bad time.
                        stack.remove(DataComponents.MAX_DAMAGE);
                        return stack;
                    },
                    null),

            new Def("pocket_enderchest",
                    Items.ENDER_CHEST,
                    name("Messy Chest", ChatFormatting.AQUA),
                    lore(voice("dont mind the mess"),
                         plain("Your ender chest, wherever you are.")),
                    PLAIN,
                    (id, inv, player) ->
                            ChestMenu.threeRows(id, inv, player.getEnderChestInventory())),

            new Def("pocket_workbench",
                    Items.CRAFTING_TABLE,
                    name("Pocket Crafter", ChatFormatting.GOLD),
                    lore(voice("whos on the crafts"),
                         plain("A workbench that never needs setting down.")),
                    PLAIN,
                    (id, inv, player) ->
                            new CraftingMenu(id, inv, ContainerLevelAccess.NULL)),

            new Def("pocket_anvil",
                    Items.ANVIL,
                    name("Fixing Sweetie", ChatFormatting.GOLD),
                    lore(voice("lord have mercy, again??"),
                         plain("Repairs and renames."),
                         plain("Never breaks.")),
                    PLAIN,
                    (id, inv, player) ->
                            new AnvilMenu(id, inv, ContainerLevelAccess.NULL)),

            new Def("pocket_grindstone",
                    Items.GRINDSTONE,
                    name("Take-Backsies Stone", ChatFormatting.GOLD),
                    lore(voice("nvm i changed my mind"),
                         plain("Strips enchantments, returns some experience.")),
                    PLAIN,
                    (id, inv, player) ->
                            new GrindstoneMenu(id, inv, ContainerLevelAccess.NULL)),

            new Def("pocket_stonecutter",
                    Items.STONECUTTER,
                    name("Chop Chop Cutter", ChatFormatting.GOLD),
                    lore(voice("chop chop perioood"),
                         plain("Cuts stone without the setup.")),
                    PLAIN,
                    (id, inv, player) ->
                            new StonecutterMenu(id, inv, ContainerLevelAccess.NULL)),

            new Def("pocket_loom",
                    Items.LOOM,
                    name("Cutie Loom", ChatFormatting.GOLD),
                    lore(voice("im so sick of rainbow everything"),
                         plain("Banner patterns on the move.")),
                    PLAIN,
                    (id, inv, player) ->
                            new LoomMenu(id, inv, ContainerLevelAccess.NULL)),

            new Def("big_hole_pick",
                    Items.DIAMOND_PICKAXE,
                    name("Big Hole Pick", ChatFormatting.GOLD),
                    lore(voice("oops thats a lot of stone"),
                         plain("Breaks a 3x3 facing you."),
                         plain("Sneak to mine one block.")),
                    areaTool(),
                    null,
                    new AreaBreak.AreaTool(1)),

            new Def("big_hole_shovel",
                    Items.DIAMOND_SHOVEL,
                    name("Big Hole Shovel", ChatFormatting.GOLD),
                    lore(voice("just a lil dig"),
                         plain("Digs a 3x3 facing you."),
                         plain("Sneak to dig one block.")),
                    areaTool(),
                    null,
                    new AreaBreak.AreaTool(1))
    );

    /** Slows area tools so the 3x3 is a trade-off, not a strict upgrade. */
    private static UnaryOperator<ItemStack> areaTool() {
        return stack -> {
            ItemAttributeModifiers modifiers = stack.getOrDefault(
                    DataComponents.ATTRIBUTE_MODIFIERS,
                    ItemAttributeModifiers.EMPTY);
            modifiers = modifiers.withModifierAdded(
                    Attributes.BLOCK_BREAK_SPEED,
                    new AttributeModifier(
                            Identifier.fromNamespaceAndPath("wondrous", "area_tool_speed_penalty"),
                            -0.3,
                            AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL),
                    EquipmentSlotGroup.MAINHAND);
            stack.set(DataComponents.ATTRIBUTE_MODIFIERS, modifiers);
            return stack;
        };
    }

    /**
     * ITEM_NAME rather than CUSTOM_NAME: item_name is the item's own name, so it
     * renders upright rather than italic and an anvil rename still reads as a rename
     * layered on top of it.
     */
    private static Component name(String text, ChatFormatting colour) {
        return Component.literal(text).withStyle(style ->
                style.withColor(colour).withItalic(false));
    }

    /** The flavour line. Italic, so it reads as someone saying it. */
    private static Component voice(String text) {
        return Component.literal(text).withStyle(style ->
                style.withColor(ChatFormatting.DARK_PURPLE).withItalic(true));
    }

    /** An ordinary description line. */
    private static Component plain(String text) {
        return Component.literal(text).withStyle(style ->
                style.withColor(ChatFormatting.DARK_GRAY).withItalic(false));
    }

    private static List<Component> lore(Component... lines) {
        return List.of(lines);
    }

    /** Builds a finished stack: base item, count, name, lore, tag, decoration. */
    public static ItemStack createStack(Def def, int count) {
        ItemStack stack = new ItemStack(def.base(), count);
        stack.set(DataComponents.ITEM_NAME, def.name());
        stack.set(DataComponents.LORE, new ItemLore(new ArrayList<>(def.lore())));
        wondrous.api.WondrousTag.stamp(stack, def.id());
        return def.decorate().apply(stack);
    }
}
