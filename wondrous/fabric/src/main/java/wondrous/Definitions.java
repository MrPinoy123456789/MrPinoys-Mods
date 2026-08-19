package wondrous;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.MenuConstructor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
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
 * Several differ only in which vanilla menu they open.
 *
 * <p>A pocket station only earns its slot if carrying it saves a trip you would
 * actually make. The grindstone, stonecutter and loom failed that test and were
 * cut -- you visit those blocks rarely and never far from home, so the pocket
 * versions were shop clutter competing with the items players want.
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
                    // No ContainerLevelAccess: the vanilla menu's stillValid checks for
                    // an actual crafting table block at that position, which doesn't
                    // exist here and would close the menu the instant it opens.
                    (id, inv, player) -> new CraftingMenu(id, inv)),

            new Def("pocket_anvil",
                    Items.ANVIL,
                    name("Fixing Sweetie", ChatFormatting.GOLD),
                    lore(voice("lord have mercy, again??"),
                         plain("Repairs and renames."),
                         plain("Never breaks.")),
                    PLAIN,
                    // No ContainerLevelAccess: the vanilla menu's stillValid checks for
                    // an actual anvil block at that position, which doesn't exist here
                    // and would close the menu the instant it opens.
                    (id, inv, player) -> new AnvilMenu(id, inv)),

            new Def(BoomerangBall.ID,
                    Items.SLIME_BALL,
                    name("Boomerang Pet Ball", ChatFormatting.GREEN),
                    lore(voice("go fetch! ...both of you"),
                         plain("Throw it. Your pet comes running,"),
                         plain("then it flies back home to you.")),
                    PLAIN,
                    null),

            new Def("pocket_disenchanter",
                    Items.GRINDSTONE,
                    name("Soul Grinder", ChatFormatting.DARK_PURPLE),
                    lore(voice("give it here, ill save the good part"),
                         plain("Destroys an enchanted item, saves its"),
                         plain("enchantments onto a book.")),
                    PLAIN,
                    (id, inv, menuPlayer) ->
                            new DisenchantMenu(id, inv, (ServerPlayer) menuPlayer)),

            new Def("pocket_smelter",
                    Items.BLAST_FURNACE,
                    name("Melty Pocket", ChatFormatting.RED),
                    lore(voice("ugh fine ill recycle your junk"),
                         plain("Melts iron or golden gear into nuggets."),
                         plain("Worn-down gear yields less.")),
                    PLAIN,
                    (id, inv, menuPlayer) ->
                            new SmelterMenu(id, inv)),

            new Def("long_arm_gloves",
                    Items.LEATHER,
                    name("Long Arm Gloves", ChatFormatting.GOLD),
                    lore(voice("reach further"),
                         plain("Three extra blocks of reach while held.")),
                    attributeItem("long_arm_gloves", Attributes.BLOCK_INTERACTION_RANGE,
                            3.0, AttributeModifier.Operation.ADD_VALUE, EquipmentSlotGroup.MAINHAND),
                    null),

            new Def("sticky_grip_boots",
                    Items.LEATHER_BOOTS,
                    name("Sticky Grip Boots", ChatFormatting.DARK_RED),
                    lore(voice("stuck to the floor"),
                         plain("Slower, but heavy hits barely move you.")),
                    stack -> {
                        stack.set(DataComponents.DYED_COLOR, new DyedItemColor(0x8B4513));
                        stack = attributeItem("sticky_grip_boots_speed", Attributes.MOVEMENT_SPEED,
                                -0.3, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, EquipmentSlotGroup.FEET).apply(stack);
                        return attributeItem("sticky_grip_boots_knockback", Attributes.KNOCKBACK_RESISTANCE,
                                0.6, AttributeModifier.Operation.ADD_VALUE, EquipmentSlotGroup.FEET).apply(stack);
                    },
                    null),

            new Def("frog_boots",
                    Items.LEATHER_BOOTS,
                    name("Frog Boots", ChatFormatting.GREEN),
                    lore(voice("boing"),
                         plain("Jump higher.")),
                    stack -> {
                        stack.set(DataComponents.DYED_COLOR, new DyedItemColor(0x00FF00));
                        return attributeItem("frog_boots", Attributes.JUMP_STRENGTH,
                                0.4, AttributeModifier.Operation.ADD_VALUE, EquipmentSlotGroup.FEET).apply(stack);
                    },
                    null),

            new Def(PeekBox.ID,
                    Items.SHULKER_BOX,
                    name("Peek Box", ChatFormatting.LIGHT_PURPLE),
                    lore(voice("just checking what i packed"),
                         plain("Opens right where you are."),
                         plain("Never needs putting down.")),
                    PLAIN,
                    null),

            new Def(VoidBin.ID,
                    Items.COMPOSTER,
                    name("Bye Forever Bin", ChatFormatting.DARK_GREEN),
                    lore(voice("i dont want it. its gone. dont ask"),
                         plain("Throw items in. Confirm to destroy.")),
                    PLAIN,
                    null),

            new Def("big_hole_pick",
                    Items.DIAMOND_PICKAXE,
                    name("Big Hole Pick", ChatFormatting.GOLD),
                    lore(voice("oops thats a lot of stone"),
                         plain("Breaks a 3x3 facing you."),
                         plain("Sneak to mine one block.")),
                    areaTool(),
                    null,
                    new AreaBreak.AreaTool(1)),

            new Def(ChuckIt.ID,
                    Items.BLAZE_ROD,
                    name("Chuck It Wand", ChatFormatting.GOLD),
                    lore(voice("put yourself away"),
                         plain("Right-click: everything with a home nearby goes home.")),
                    PLAIN,
                    null),

            new Def(TidyUp.ID,
                    Items.BRUSH,
                    name("Tidy Up Stick", ChatFormatting.AQUA),
                    lore(voice("make it neat"),
                         plain("Sorts nearby chests without moving the contents.")),
                    PLAIN,
                    null),

            new Def(BigLazyHoe.ID,
                    Items.DIAMOND_HOE,
                    name("Big Lazy Hoe", ChatFormatting.GREEN),
                    lore(voice("grow faster"),
                         plain("Right-click a crop to bonemeal the 3x3 around it.")),
                    PLAIN,
                    null),

            new Def(Growth.GROWY_CAN_ID,
                    Items.BUCKET,
                    name("Growy Can", ChatFormatting.GREEN),
                    lore(voice("drink up babes"),
                         plain("One bone meal grows a 5x5x3 patch on right-click.")),
                    PLAIN,
                    null),

            new Def(LazySprinkler.ID,
                    Items.COPPER_GRATE.weathering().oxidized(),
                    name("Lazy Sprinkler", ChatFormatting.AQUA),
                    lore(voice("i got it, go do something else"),
                         plain("Right-click to feed it bone meal."),
                         plain("Fertilizes 3 random blocks in an 11x11 around itself, same level, every 3 seconds.")),
                    PLAIN,
                    null),

            new Def(Mortar.ID,
                    Items.BOWL,
                    name("Smashy Mortar", ChatFormatting.DARK_GRAY),
                    lore(voice("ugh fine, ill break it smaller"),
                         plain("Hold a crushable in your off-hand and right-click.")),
                    PLAIN,
                    null),

            new Def(CraftStation.ID,
                    Items.CRAFTING_TABLE,
                    name("Left It Out Crafter", ChatFormatting.GOLD),
                    lore(voice("leave it there"),
                         plain("A crafting table that keeps its grid when you leave.")),
                    PLAIN,
                    null),

            new Def(LinkWand.ID,
                    Items.BREEZE_ROD,
                    name("Put It There Wand", ChatFormatting.AQUA),
                    lore(voice("toss"),
                         plain("Right-click a machine, then a container to link. Right-click again to unlink.")),
                    PLAIN,
                    null),

            new Def(CarryGlove.ID,
                    Items.LEATHER,
                    name("Piggyback Glove", ChatFormatting.YELLOW),
                    lore(voice("come here you"),
                         plain("Sneak-right-click a container to lift it. Right-click to put it back.")),
                    // Leather stacks to 64 and custom_data is per-stack, so a stack
                    // of gloves would share one stored container and place it once
                    // per glove. One at a time.
                    stack -> {
                        stack.set(DataComponents.MAX_STACK_SIZE, 1);
                        return stack;
                    },
                    null),

            new Def(Restock.ID,
                    Items.NAUTILUS_SHELL,
                    name("Never Empty Charm", ChatFormatting.AQUA),
                    lore(voice("i packed spares"),
                         plain("Refills an empty main hand from matching inventory stacks.")),
                    PLAIN,
                    null),

            new Def("owl_eye_goggles",
                    Items.LEATHER_HELMET,
                    name("Owl Eye Goggles", ChatFormatting.YELLOW),
                    lore(voice("whos out there"),
                         plain("Night vision while worn.")),
                    PLAIN,
                    null),

            new Def("fishy_necklace",
                    Items.TROPICAL_FISH,
                    name("Fishy Necklace", ChatFormatting.AQUA),
                    lore(voice("glub glub"),
                         plain("Water breathing and dolphins grace while held.")),
                    PLAIN,
                    null),

            new Def("toasty_scarf",
                    Items.LEATHER_CHESTPLATE,
                    name("Toasty Scarf", ChatFormatting.RED),
                    lore(voice("toasty"),
                         plain("Fire resistance while worn.")),
                    PLAIN,
                    null),

            new Def("floaty_feet",
                    Items.LEATHER_LEGGINGS,
                    name("Floaty Feet", ChatFormatting.LIGHT_PURPLE),
                    lore(voice("float on"),
                         plain("Slow falling while worn.")),
                    PLAIN,
                    null),

            new Def("zoomies_boots",
                    Items.LEATHER_BOOTS,
                    name("Zoomies Boots", ChatFormatting.WHITE),
                    lore(voice("zoom zoom"),
                         plain("Speed while sprinting.")),
                    PLAIN,
                    null),

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

    /** Sets one attribute modifier. The whole trick behind the Phase -1 batch. */
    private static UnaryOperator<ItemStack> attributeItem(
            String modifierName, Holder<Attribute> attribute,
            double amount, AttributeModifier.Operation op, EquipmentSlotGroup slot) {
        return stack -> {
            ItemAttributeModifiers modifiers = stack.getOrDefault(
                    DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
            modifiers = modifiers.withModifierAdded(
                    attribute,
                    new AttributeModifier(
                            Identifier.fromNamespaceAndPath("wondrous", modifierName),
                            amount, op),
                    slot);
            stack.set(DataComponents.ATTRIBUTE_MODIFIERS, modifiers);
            return stack;
        };
    }

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
        List<Component> lore = new ArrayList<>(def.lore());
        if (RecipeGuard.isFuel(def.base())) {
            lore.add(plain("dont put me in a furnace"));
        }
        stack.set(DataComponents.LORE, new ItemLore(lore));
        wondrous.api.WondrousTag.stamp(stack, def.id());
        return def.decorate().apply(stack);
    }
}
