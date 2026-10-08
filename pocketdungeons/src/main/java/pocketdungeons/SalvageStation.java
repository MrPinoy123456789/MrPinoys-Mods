package pocketdungeons;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The salvage bench (playtest 2026-09-29, A3: "I'm accumulating too much gear
 * and vault keys"): a room station that turns the surplus into something the
 * other stations take. Gear, tagged or a mob drop, pays the grindstone's XP
 * and its materials. Vault keys stopped paying at the bench with J7: they
 * settle for emeralds at the floor clear instead
 * ({@link RunLifecycle#payFloorMembers}), so the bench refuses them.
 * The rates and the reasoning behind them are in
 * {@code docs/reference/SALVAGE_PROPOSAL.md}; the arithmetic is
 * {@link SalvageMath}.
 *
 * <p><b>Every use in the dungeon opens it (PD-96).</b> The configured block (a
 * grindstone by default) opens the bench on any use that is not a sneak,
 * whatever the hand holds; a bench that only answered to the right item was
 * one nobody found. Sneaking still opens the vanilla grindstone, and the bench
 * offers a Disenchant button that hands a single enchanted item over to it.
 * Outside the dungeon dimension every grindstone is vanilla.
 *
 * <p><b>A drop-in screen, not a one-item picker.</b> Clearing four bows one
 * click at a time is the chore the player complained about, so the bench is
 * an 18-slot SGUI chest with a summary button underneath. A plain use only
 * opens it and leaves the held stack alone (PD-101); the player drops items
 * in from the inventory. Things the bench refuses (imbued or
 * trimmed gear, anything that goes home with the player, anything that is
 * not gear) may be dropped in but stay put and are named on the
 * summary. Closing the screen by any path, disconnect included, hands back
 * whatever is still in it.
 */
final class SalvageStation {

    private static final ConfiguredItem SALVAGE_BLOCK_ITEM = new ConfiguredItem("salvageBlock",
            PocketDungeonsConfig::salvageBlock,
            "the salvage bench will never open for anybody.");

    private static final int INPUT_SLOTS = 18;
    private static final int SUMMARY_SLOT = 22;
    private static final int DISENCHANT_SLOT = 26;

    private SalvageStation() {}

    /** Resolves the configured block once, so a typo is a boot-time log line. */
    static void warmUp() {
        SALVAGE_BLOCK_ITEM.get();
    }

    static boolean matchesStation(BlockState state) {
        return StationSupport.matchesBlock(SALVAGE_BLOCK_ITEM, state);
    }

    // ---- sorting -----------------------------------------------------------------

    enum Kind { GEAR, MOB_GEAR, REFUSED }

    /** What the bench makes of one stack, and for a refusal, why. */
    record Verdict(Kind kind, String reason) {
        boolean takes() {
            return kind != Kind.REFUSED;
        }
    }

    private static Verdict take(Kind kind) {
        return new Verdict(kind, "");
    }

    private static Verdict refuse(String reason) {
        return new Verdict(Kind.REFUSED, reason);
    }

    /**
     * Sorts one stack. Order matters: the keep-safe refusals come before any
     * payout, so an imbued or trimmed piece of tagged gear is refused rather
     * than scrapped for its tier.
     */
    static Verdict classify(ItemStack stack) {
        if (stack.isEmpty()) {
            return refuse("empty");
        }
        // J7: keys never leave their floor; the floor clear buys them back
        // at the same rates, so the bench refuses them.
        if (stack.is(TrialContent.keyStack(true).getItem())
                || stack.is(TrialContent.keyStack(false).getItem())) {
            return refuse("vault keys settle when the floor clears");
        }
        // Owner request (2026-10-03): kit can be scrapped like any other gear;
        // the safe room tops the kit back up. Only the keystone is kept.
        if (Keystone.isKeystone(stack)) {
            return refuse("your compass, it goes home with you");
        }
        if (!CubeStation.powerOf(stack).isBlank()) {
            return refuse("imbued with a power, kept safe");
        }
        if (!CubeStation.rewardOf(stack).isBlank()) {
            return refuse("a rare Cube reward, kept safe");
        }
        if (stack.has(DataComponents.TRIM)) {
            return refuse("trimmed armour is kept safe, never scrapped");
        }
        if (RerollStation.tierOf(stack) > 0) {
            return take(Kind.GEAR);
        }
        if (stack.isDamageableItem()) {
            return take(Kind.MOB_GEAR);
        }
        return refuse("not gear");
    }

    /**
     * What one piece of gear gives back when salvaged (owner request,
     * 2026-10-03), by its material and how worn it is
     * ({@link SalvageMath#band}):
     * <ul>
     *   <li>leather, iron, gold, diamond and netherite (as scrap) armour,
     *       weapons and tools: {@link SalvageMath#materials}. Chainmail and
     *       copper gear left the loot tables with K2, and their rules left
     *       with them;</li>
     *   <li>wooden tools: 1 plank in the high band, 2 sticks in the middle;</li>
     *   <li>stone tools: 1 cobblestone in the high or middle band;</li>
     *   <li>a shield: 1 plank in the high or middle band.</li>
     * </ul>
     * Under 25 percent nothing, before the {@code salvageMaterialBonus} knob (default 1)
     * adds its count to every band; anything else (bows, flint and steel) gives
     * {@link ItemStack#EMPTY}. Never nuggets.
     */
    static ItemStack materialsBack(ItemStack stack) {
        return materialsBack(stack, PocketDungeonsConfig.salvageMaterialBonus());
    }

    /**
     * As {@link #materialsBack(ItemStack)}, with the bonus given: every count is raised by
     * {@code bonus} in every band (the {@code salvageMaterialBonus} knob), so a piece that paid
     * nothing under 25 percent pays {@code bonus}, one that paid 1 pays {@code 1 + bonus}, and so on.
     * Pieces that give nothing at any wear (bows, flint and steel) still give nothing.
     */
    static ItemStack materialsBack(ItemStack stack, int bonus) {
        SalvageMath.Band band = SalvageMath.band(stack.getMaxDamage() - stack.getDamageValue(),
                stack.getMaxDamage());
        boolean low = band == SalvageMath.Band.LOW;
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (path.equals("shield")) {
            return counted(Items.OAK_PLANKS, low ? 0 : 1, bonus);
        }
        if (!isArmourOrTool(path)) {
            return ItemStack.EMPTY;
        }
        if (path.startsWith("wooden_")) {
            return switch (band) {
                case HIGH -> counted(Items.OAK_PLANKS, 1, bonus);
                case MID -> counted(Items.STICK, 2, bonus);
                case LOW -> counted(Items.STICK, 0, bonus);
            };
        }
        if (path.startsWith("stone_")) {
            return counted(Items.COBBLESTONE, low ? 0 : 1, bonus);
        }
        Item material = metalOf(path);
        if (material == null) {
            return ItemStack.EMPTY;
        }
        return counted(material, SalvageMath.materials(isLarge(stack), band), bonus);
    }

    /** {@code base} plus {@code bonus} of {@code item}, or nothing when that is not positive. */
    private static ItemStack counted(Item item, int base, int bonus) {
        int count = SalvageMath.withBonus(base, bonus);
        return count <= 0 ? ItemStack.EMPTY : new ItemStack(item, count);
    }

    /** The material of leather, metal and gem gear, read from its id; {@code null} for anything else. */
    private static Item metalOf(String path) {
        if (path.startsWith("leather_")) {
            return Items.LEATHER;
        }
        if (path.startsWith("iron_")) {
            return Items.IRON_INGOT;
        }
        if (path.startsWith("golden_")) {
            return Items.GOLD_INGOT;
        }
        if (path.startsWith("diamond_")) {
            return Items.DIAMOND;
        }
        if (path.startsWith("netherite_")) {
            return Items.NETHERITE_SCRAP;
        }
        return null;
    }

    private static final String[] GEAR_SUFFIXES = {"_helmet", "_chestplate", "_leggings", "_boots",
            "_sword", "_axe", "_pickaxe", "_shovel", "_hoe", "_spear"};

    private static boolean isArmourOrTool(String path) {
        for (String suffix : GEAR_SUFFIXES) {
            if (path.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** A chestplate or leggings: the pieces that give up to two (see {@link SalvageMath#materials}). */
    static boolean isLarge(ItemStack stack) {
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return path.endsWith("_chestplate") || path.endsWith("_leggings");
    }

    /** What the stack's enchantments are worth to a grindstone; see {@link SalvageMath#grindstoneHalf}. */
    private static int enchantCostSum(ItemStack stack) {
        int sum = 0;
        ItemEnchantments enchantments = net.minecraft.world.item.enchantment.EnchantmentHelper
                .getEnchantmentsForCrafting(stack);
        for (var entry : enchantments.entrySet()) {
            Holder<Enchantment> enchantment = entry.getKey();
            // As the grindstone: curses are not stripped, so they pay nothing.
            if (!enchantment.is(net.minecraft.tags.EnchantmentTags.CURSE)) {
                sum += enchantment.value().getMinCost(entry.getIntValue());
            }
        }
        return sum;
    }

    // ---- the block -----------------------------------------------------------------

    /**
     * Called from {@link RitualListener#onUseBlock} with the other stations.
     * Returns whether this click was handled; {@code false} means not our
     * block, not the dungeon, or a sneak, and the vanilla grindstone runs as
     * usual. There is no level gate (J5): a placed grindstone is the bench.
     */
    static boolean onUse(ServerPlayer player, BlockState state, InteractionHand hand,
                         ContainerLevelAccess access) {
        return onUse(player, state, hand, access,
                player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL));
    }

    /** As above, with the dimension test supplied so a gametest can stand in the dungeon. */
    static boolean onUse(ServerPlayer player, BlockState state, InteractionHand hand,
                         ContainerLevelAccess access, boolean inDungeon) {
        if (!inDungeon || !matchesStation(state) || player.isShiftKeyDown()) {
            return false;
        }
        // PD-101: opening never moves the held stack; depositing is a
        // deliberate click inside the screen.
        StationTutorial.used(player, StationTutorial.Step.SALVAGE);
        open(player, new Input(), access);
        return true;
    }

    // ---- the screen ----------------------------------------------------------------

    /**
     * The screen's 18 input slots. 26.2's {@code SimpleContainer} has no
     * listener list, so the summary is redrawn from {@code setChanged}, which
     * every slot write and every {@code setItem} goes through.
     */
    private static final class Input extends SimpleContainer {
        private Runnable onChange = () -> {};

        Input() {
            super(INPUT_SLOTS);
        }

        @Override
        public void setChanged() {
            super.setChanged();
            onChange.run();
        }
    }

    private static void open(ServerPlayer player, Input input, ContainerLevelAccess access) {
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x3, player, false) {
            @Override
            public void onRemoved() {
                handBack(player, input);
            }
        };
        gui.setTitle(Component.literal("Salvage Bench"));
        for (int i = 0; i < INPUT_SLOTS; i++) {
            gui.setSlot(i, new Slot(input, i, 0, 0));
        }
        GuiElementBuilder filler = new GuiElementBuilder(Items.STAINED_GLASS_PANE.gray()).hideTooltip();
        for (int i = INPUT_SLOTS; i < INPUT_SLOTS + 9; i++) {
            if (i != SUMMARY_SLOT && i != DISENCHANT_SLOT) {
                gui.setSlot(i, filler);
            }
        }
        refresh(gui, player, input, access);
        input.onChange = () -> refresh(gui, player, input, access);
        if (!gui.open()) {
            // Never opened, so onRemoved will not run: anything already in
            // the screen goes straight back.
            handBack(player, input);
        }
    }

    /** What the screen's contents would pay right now, and what stays. */
    /**
     * {@code grindHalves} holds {@link SalvageMath#grindstoneHalf} for each
     * enchanted piece, one entry per item; the XP is rolled from them at
     * payout, the way the grindstone rolls it, and {@link #xpMin} and
     * {@link #xpMax} are what the screen quotes.
     */
    record Quote(int gear, int mobGear, List<Integer> grindHalves,
                         int refused, String firstRefusal, Map<Item, Integer> materials) {
        int xpMin() {
            return grindHalves.stream().mapToInt(Integer::intValue).sum();
        }

        int xpMax() {
            return grindHalves.stream().mapToInt(c -> 2 * c - 1).sum();
        }

        boolean anything() {
            return gear + mobGear > 0;
        }
    }

    static Quote quote(SimpleContainer input) {
        int gear = 0, mobGear = 0, refused = 0;
        List<Integer> grindHalves = new ArrayList<>();
        String firstRefusal = "";
        Map<Item, Integer> materials = new LinkedHashMap<>();
        for (int i = 0; i < INPUT_SLOTS; i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            Verdict verdict = classify(stack);
            int count = stack.getCount();
            switch (verdict.kind()) {
                // Owner request (2026-10-03): gear, tagged or a mob drop, pays
                // the grindstone's XP and its materials, never emeralds.
                case GEAR, MOB_GEAR -> {
                    if (verdict.kind() == Kind.GEAR) {
                        gear += count;
                    } else {
                        mobGear += count;
                    }
                    int half = SalvageMath.grindstoneHalf(enchantCostSum(stack));
                    for (int n = 0; n < count && half > 0; n++) {
                        grindHalves.add(half);
                    }
                }
                case REFUSED -> {
                    refused += count;
                    if (firstRefusal.isEmpty()) {
                        firstRefusal = "Kept, not salvaged: " + verdict.reason()
                                + " (" + stack.getHoverName().getString() + ")";
                    }
                }
            }
        }
        for (int i = 0; i < INPUT_SLOTS; i++) {
            ItemStack stack = input.getItem(i);
            Kind kind = stack.isEmpty() ? Kind.REFUSED : classify(stack).kind();
            if (kind == Kind.GEAR || kind == Kind.MOB_GEAR) {
                ItemStack back = materialsBack(stack);
                if (!back.isEmpty()) {
                    materials.merge(back.getItem(), back.getCount() * stack.getCount(), Integer::sum);
                }
            }
        }
        return new Quote(gear, mobGear, List.copyOf(grindHalves), refused, firstRefusal, materials);
    }

    private static void refresh(SimpleGui gui, ServerPlayer player, SimpleContainer input,
                                ContainerLevelAccess access) {
        Quote q = quote(input);
        List<Component> lore = new ArrayList<>();
        if (q.gear() + q.mobGear() > 0) {
            int pieces = q.gear() + q.mobGear();
            lore.add(line("Gear: " + pieces + " piece" + plural(pieces) + " for " + xpText(q)));
        }
        if (!q.materials().isEmpty()) {
            lore.add(line("Materials back: " + materialsText(q.materials())));
        }
        if (q.refused() > 0) {
            String text = q.firstRefusal();
            if (q.refused() > 1) {
                text = text + " and " + (q.refused() - 1) + " more";
            }
            lore.add(Component.literal(text)
                    .withStyle(ChatFormatting.YELLOW).withStyle(s -> s.withItalic(false)));
        }
        GuiElementBuilder button = new GuiElementBuilder(Items.GRINDSTONE);
        boolean inputEmpty = inputEmpty(input);
        if (q.anything()) {
            button.setName(Component.literal("Salvage").withStyle(ChatFormatting.GREEN)
                    .withStyle(s -> s.withItalic(false)));
            lore.add(Component.literal("Click to salvage.").withStyle(ChatFormatting.GRAY)
                    .withStyle(s -> s.withItalic(false)));
            button.setCallback((index, clickType, action, g) -> salvage(gui, player, input, access));
        } else if (inputEmpty) {
            button.setName(Component.literal("Put gear here to salvage").withStyle(ChatFormatting.GRAY)
                    .withStyle(s -> s.withItalic(false)));
            lore.add(line("Click gear in your pack to move it in."));
            lore.add(line("Gear pays XP and materials."));
        } else {
            button.setName(Component.literal("Nothing here can be salvaged").withStyle(ChatFormatting.GRAY)
                    .withStyle(s -> s.withItalic(false)));
            lore.add(line("Drop in gear."));
            lore.add(line("Gear pays XP and materials."));
        }
        lore.add(Component.literal("Sneak and use the grindstone for the plain one.")
                .withStyle(ChatFormatting.DARK_GRAY).withStyle(s -> s.withItalic(false)));
        button.setLore(lore);
        gui.setSlot(SUMMARY_SLOT, button);
        gui.setSlot(DISENCHANT_SLOT, disenchantButton(gui, player, input, access));
    }

    /**
     * The slot of the one disenchantable item in {@code input}, or -1 unless
     * it holds exactly one item and that item carries enchantments the
     * vanilla grindstone would strip.
     */
    static int loneDisenchantable(SimpleContainer input) {
        int found = -1;
        for (int i = 0; i < INPUT_SLOTS; i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (found >= 0 || stack.getCount() != 1) {
                return -1;
            }
            found = i;
        }
        if (found < 0) {
            return -1;
        }
        ItemStack stack = input.getItem(found);
        boolean enchanted = stack.isEnchanted()
                || !stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY).isEmpty();
        return enchanted ? found : -1;
    }

    private static GuiElementBuilder disenchantButton(SimpleGui gui, ServerPlayer player,
                                                      SimpleContainer input, ContainerLevelAccess access) {
        int slot = loneDisenchantable(input);
        if (slot < 0 && inputEmpty(input)) {
            // PD-135 (playtest 2026-10-03-2): disenchanting no longer needs
            // an item in the bench first. An empty bench opens the plain
            // grindstone, and the player puts the item in there.
            return new GuiElementBuilder(Items.ENCHANTED_BOOK)
                    .setName(Component.literal("Disenchant").withStyle(ChatFormatting.LIGHT_PURPLE)
                            .withStyle(s -> s.withItalic(false)))
                    .setLore(List.of(line("Opens the plain grindstone"),
                            line("to strip enchantments.")))
                    .setCallback((index, clickType, action, g) -> toVanilla(gui, player, input, access));
        }
        if (slot < 0) {
            return new GuiElementBuilder(Items.BOOK)
                    .setName(Component.literal("Disenchant").withStyle(ChatFormatting.GRAY)
                            .withStyle(s -> s.withItalic(false)))
                    .setLore(List.of(line("Take the other items out, or leave"),
                            line("one enchanted item in alone.")));
        }
        return new GuiElementBuilder(Items.ENCHANTED_BOOK)
                .setName(Component.literal("Disenchant").withStyle(ChatFormatting.LIGHT_PURPLE)
                        .withStyle(s -> s.withItalic(false)))
                .setLore(List.of(line("Moves " + input.getItem(slot).getHoverName().getString()),
                        line("to the plain grindstone.")))
                .setCallback((index, clickType, action, g) -> toVanilla(gui, player, input, access));
    }

    /**
     * The Disenchant button: takes the lone enchanted item out of the bench,
     * closes it (nothing else is in it to hand back) and opens the vanilla
     * grindstone with the item already in its top slot. The grindstone's own
     * close returns the item if the player walks away.
     */
    private static void toVanilla(SimpleGui gui, ServerPlayer player, SimpleContainer input,
                                  ContainerLevelAccess access) {
        int slot = loneDisenchantable(input);
        if (slot < 0 && !inputEmpty(input)) {
            return;
        }
        ItemStack item = slot < 0 ? ItemStack.EMPTY : input.removeItemNoUpdate(slot);
        gui.close();
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new GrindstoneMenu(id, inventory, access),
                Component.translatable("container.grindstone_title")));
        if (item.isEmpty()) {
            return;
        }
        if (player.containerMenu instanceof GrindstoneMenu menu) {
            menu.getSlot(0).set(item);
            menu.broadcastChanges();
        } else {
            player.getInventory().placeItemBackInInventory(item);
        }
    }

    private static boolean inputEmpty(SimpleContainer input) {
        for (int i = 0; i < INPUT_SLOTS; i++) {
            if (!input.getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static Component line(String text) {
        return Component.literal(text).withStyle(ChatFormatting.WHITE).withStyle(s -> s.withItalic(false));
    }

    /** The quoted XP: "no XP", "6 XP" or "6 to 11 XP", the grindstone's range. */
    private static String xpText(Quote q) {
        if (q.xpMax() <= 0) {
            return "no XP";
        }
        return q.xpMin() == q.xpMax() ? q.xpMin() + " XP" : q.xpMin() + " to " + q.xpMax() + " XP";
    }

    /** For example "2 Iron Ingot, 1 Leather". */
    private static String materialsText(Map<Item, Integer> materials) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Item, Integer> m : materials.entrySet()) {
            parts.add(m.getValue() + " " + new ItemStack(m.getKey()).getHoverName().getString());
        }
        return String.join(", ", parts);
    }

    private static String plural(int n) {
        return n == 1 ? "" : "s";
    }

    // ---- paying out ----------------------------------------------------------------

    /** The Salvage button: pays for the screen's contents and redraws what is left. */
    private static void salvage(SimpleGui gui, ServerPlayer player, SimpleContainer input,
                                ContainerLevelAccess access) {
        if (salvageContents(player, input) != null) {
            refresh(gui, player, input, access);
        }
    }

    /**
     * Takes everything the bench accepts out of {@code input} and pays for it.
     * Re-sorts the live contents rather than trusting the last summary.
     * Refused items stay.
     * Package private so {@code SalvageGameTest} can drive it without a screen.
     *
     * @return what was paid for, or {@code null} if nothing was
     */
    static Quote salvageContents(ServerPlayer player, SimpleContainer input) {
        Quote q = quote(input);
        if (!q.anything()) {
            return null;
        }
        for (int i = 0; i < INPUT_SLOTS; i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (classify(stack).kind() != Kind.REFUSED) {
                input.setItem(i, ItemStack.EMPTY);
            }
        }

        int xp = 0;
        for (int half : q.grindHalves()) {
            xp += half + player.getRandom().nextInt(half);
        }
        if (xp > 0) {
            player.giveExperiencePoints(xp);
        }
        for (Map.Entry<Item, Integer> m : q.materials().entrySet()) {
            Payout.deliver(player, new ItemStack(m.getKey(), m.getValue()));
        }
        player.level().playSound(null, player.blockPosition(), SoundEvents.GRINDSTONE_USE,
                SoundSource.BLOCKS, 1.0f, 1.0f);

        List<String> parts = new ArrayList<>();
        if (xp > 0) {
            parts.add(xp + " XP");
        }
        if (!q.materials().isEmpty()) {
            parts.add(materialsText(q.materials()));
        }
        player.sendSystemMessage(Component.literal("Salvaged for " + (parts.isEmpty() ? "nothing"
                : String.join(", ", parts)) + ".").withStyle(ChatFormatting.AQUA));

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("gear", q.gear());
        extras.put("mob_gear", q.mobGear());
        extras.put("xp", xp);
        Map<String, Integer> materialIds = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> m : q.materials().entrySet()) {
            materialIds.put(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(m.getKey()).toString(),
                    m.getValue());
        }
        extras.put("materials", materialIds);
        PlaytestJournal.salvage(player, extras);
        return q;
    }

    /**
     * Returns whatever is still in the screen when it closes, by any path.
     * A player who is gone gets it dropped where they stood, the way a vanilla
     * crafting grid clears on close.
     */
    private static void handBack(ServerPlayer player, SimpleContainer input) {
        for (int i = 0; i < INPUT_SLOTS; i++) {
            ItemStack stack = input.removeItemNoUpdate(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (player.isRemoved() || player.hasDisconnected()) {
                player.drop(stack, false);
            } else {
                player.getInventory().placeItemBackInInventory(stack);
            }
        }
    }
}
