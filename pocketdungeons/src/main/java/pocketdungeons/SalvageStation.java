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
 * other stations take. Tagged gear pays emeralds by tier, vault keys pay
 * emeralds (or fuel, when {@code salvageKeysPerFuel} is on), and untagged mob
 * gear pays XP only. The rates and the reasoning behind them are in
 * {@code docs/reference/SALVAGE_PROPOSAL.md}; the arithmetic is
 * {@link SalvageMath}.
 *
 * <p><b>Every use opens it (PD-96).</b> The configured block (a grindstone by
 * default) opens the bench on any use that is not a sneak, whatever the hand
 * holds; a bench that only answered to the right item was one nobody found.
 * Sneaking still opens the vanilla grindstone, and the bench offers a
 * Disenchant button that hands a single enchanted item over to it.
 *
 * <p><b>A drop-in screen, not a one-item picker.</b> Clearing four bows one
 * click at a time is the chore the player complained about, so the bench is
 * an 18-slot SGUI chest with a summary button underneath. The item the
 * player clicked with goes straight in. Things the bench refuses (imbued or
 * trimmed gear, anything that goes home with the player, anything that is
 * not gear or a key) may be dropped in but stay put and are named on the
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

    enum Kind { GEAR, KEY, OMINOUS_KEY, MOB_GEAR, REFUSED }

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
        if (InventorySwap.isOurs(stack)) {
            return refuse("goes home with you");
        }
        if (stack.is(TrialContent.keyStack(true).getItem())) {
            return take(Kind.OMINOUS_KEY);
        }
        if (stack.is(TrialContent.keyStack(false).getItem())) {
            return take(Kind.KEY);
        }
        if (!CubeStation.powerOf(stack).isBlank()) {
            return refuse("imbued with a power");
        }
        if (!CubeStation.rewardOf(stack).isBlank()) {
            return refuse("a rare reward for the Cube");
        }
        if (stack.has(DataComponents.TRIM)) {
            return refuse("trimmed");
        }
        if (RerollStation.tierOf(stack) > 0) {
            return take(Kind.GEAR);
        }
        if (stack.isDamageableItem()) {
            return take(Kind.MOB_GEAR);
        }
        return refuse("not gear or a vault key");
    }

    /** Half of what the stack's enchantments are worth, in grindstone terms; see {@link SalvageMath#mobGearXp}. */
    private static int enchantCostSum(ItemStack stack) {
        int sum = 0;
        for (var entry : stack.getEnchantments().entrySet()) {
            Holder<Enchantment> enchantment = entry.getKey();
            sum += enchantment.value().getMinCost(entry.getIntValue());
        }
        return sum;
    }

    // ---- the block -----------------------------------------------------------------

    /**
     * Called from {@link RitualListener#onUseBlock} with the other stations.
     * Returns whether this click was handled; {@code false} means not our
     * block or a sneak, and the vanilla grindstone runs as usual. Below the
     * unlock level the vanilla grindstone runs too, unless the hand holds
     * something the bench takes, which earns the "needs level N" line.
     */
    static boolean onUse(ServerPlayer player, BlockState state, InteractionHand hand,
                         ContainerLevelAccess access) {
        if (!matchesStation(state) || player.isShiftKeyDown()) {
            return false;
        }
        ItemStack held = player.getItemInHand(hand);
        boolean takes = classify(held).takes();
        if (keystoneLevel(player) < PocketDungeonsConfig.salvageUnlockLevel()) {
            return takes && levelTooLow(player);
        }
        Input input = new Input();
        if (takes) {
            input.setItem(0, held.copy());
            player.setItemInHand(hand, ItemStack.EMPTY);
        }
        open(player, input, access);
        return true;
    }

    private static int keystoneLevel(ServerPlayer player) {
        return DungeonLog.forServer(player.level().getServer()).get(player.getUUID()).keystoneLevel();
    }

    private static boolean levelTooLow(ServerPlayer player) {
        return StationSupport.levelTooLow(player, keystoneLevel(player),
                PocketDungeonsConfig.salvageUnlockLevel(), "salvage bench");
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
            // Never opened, so onRemoved will not run: the item taken from
            // the hand goes straight back.
            handBack(player, input);
        }
    }

    /** What the screen's contents would pay right now, and what stays. */
    record Quote(int gear, int gearEmeralds, int keys, int ominousKeys, int mobGear, int xp,
                         int refused, String firstRefusal) {
        int keyFuel() {
            return SalvageMath.keyFuel(keys, PocketDungeonsConfig.salvageKeysPerFuel());
        }

        int keysTaken() {
            int perFuel = PocketDungeonsConfig.salvageKeysPerFuel();
            return perFuel > 0 ? SalvageMath.keysForFuel(keys, perFuel) : keys;
        }

        int emeralds() {
            int keyEmeralds = PocketDungeonsConfig.salvageKeysPerFuel() > 0 ? 0
                    : SalvageMath.keyEmeralds(keys, PocketDungeonsConfig.salvageKeyEmeralds());
            return gearEmeralds + keyEmeralds
                    + SalvageMath.keyEmeralds(ominousKeys, PocketDungeonsConfig.salvageOminousKeyEmeralds());
        }

        boolean anything() {
            return gear + ominousKeys + mobGear + keysTaken() > 0;
        }
    }

    static Quote quote(SimpleContainer input) {
        int gear = 0, gearEmeralds = 0, keys = 0, ominousKeys = 0, mobGear = 0, xp = 0, refused = 0;
        String firstRefusal = "";
        for (int i = 0; i < INPUT_SLOTS; i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            Verdict verdict = classify(stack);
            int count = stack.getCount();
            switch (verdict.kind()) {
                case GEAR -> {
                    gear += count;
                    gearEmeralds += count * SalvageMath.gearEmeralds(RerollStation.tierOf(stack),
                            PocketDungeonsConfig.salvageEmeraldsPerTier());
                }
                case KEY -> keys += count;
                case OMINOUS_KEY -> ominousKeys += count;
                case MOB_GEAR -> {
                    mobGear += count;
                    xp += count * SalvageMath.mobGearXp(enchantCostSum(stack));
                }
                case REFUSED -> {
                    refused += count;
                    if (firstRefusal.isEmpty()) {
                        firstRefusal = stack.getHoverName().getString() + ": " + verdict.reason();
                    }
                }
            }
        }
        return new Quote(gear, gearEmeralds, keys, ominousKeys, mobGear, xp, refused, firstRefusal);
    }

    private static void refresh(SimpleGui gui, ServerPlayer player, SimpleContainer input,
                                ContainerLevelAccess access) {
        Quote q = quote(input);
        List<Component> lore = new ArrayList<>();
        if (q.gear() > 0) {
            lore.add(line("Gear: " + q.gear() + " for " + q.gearEmeralds() + " emerald" + plural(q.gearEmeralds())));
        }
        if (q.keys() > 0) {
            int perFuel = PocketDungeonsConfig.salvageKeysPerFuel();
            if (perFuel > 0) {
                int left = q.keys() - q.keysTaken();
                lore.add(line("Vault keys: " + q.keysTaken() + " for " + q.keyFuel() + " fuel"
                        + (left > 0 ? " (" + left + " short of the next, they stay)" : "")));
            } else {
                int paid = SalvageMath.keyEmeralds(q.keys(), PocketDungeonsConfig.salvageKeyEmeralds());
                lore.add(line("Vault keys: " + q.keys() + " for " + paid + " emerald" + plural(paid)));
            }
        }
        if (q.ominousKeys() > 0) {
            int paid = SalvageMath.keyEmeralds(q.ominousKeys(), PocketDungeonsConfig.salvageOminousKeyEmeralds());
            lore.add(line("Ominous keys: " + q.ominousKeys() + " for " + paid + " emerald" + plural(paid)));
        }
        if (q.mobGear() > 0) {
            lore.add(line("Mob gear: " + q.mobGear() + " for " + q.xp() + " XP"));
        }
        if (q.refused() > 0) {
            lore.add(Component.literal("Stays: " + q.refused() + " (" + q.firstRefusal() + ")")
                    .withStyle(ChatFormatting.YELLOW).withStyle(s -> s.withItalic(false)));
        }
        GuiElementBuilder button = new GuiElementBuilder(Items.GRINDSTONE);
        if (q.anything()) {
            button.setName(Component.literal("Salvage").withStyle(ChatFormatting.GREEN)
                    .withStyle(s -> s.withItalic(false)));
            lore.add(Component.literal("Click to salvage.").withStyle(ChatFormatting.GRAY)
                    .withStyle(s -> s.withItalic(false)));
            button.setCallback((index, clickType, action, g) -> salvage(gui, player, input, access));
        } else {
            button.setName(Component.literal("Nothing to salvage yet").withStyle(ChatFormatting.GRAY)
                    .withStyle(s -> s.withItalic(false)));
            lore.add(line("Drop in gear or vault keys."));
            lore.add(line("They pay emeralds, fuel or XP."));
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
        if (slot < 0) {
            return new GuiElementBuilder(Items.BOOK)
                    .setName(Component.literal("Disenchant").withStyle(ChatFormatting.GRAY)
                            .withStyle(s -> s.withItalic(false)))
                    .setLore(List.of(line("Put one enchanted item in alone"),
                            line("to strip it on the plain grindstone.")));
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
        if (slot < 0) {
            return;
        }
        ItemStack item = input.removeItemNoUpdate(slot);
        gui.close();
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new GrindstoneMenu(id, inventory, access),
                Component.translatable("container.grindstone_title")));
        if (player.containerMenu instanceof GrindstoneMenu menu) {
            menu.getSlot(0).set(item);
            menu.broadcastChanges();
        } else {
            player.getInventory().placeItemBackInInventory(item);
        }
    }

    private static Component line(String text) {
        return Component.literal(text).withStyle(ChatFormatting.WHITE).withStyle(s -> s.withItalic(false));
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
     * Re-sorts the live contents rather than trusting the last summary, and
     * re-checks the unlock level, the same staleness discipline the reroll
     * station follows. Refused items and keys short of a whole fuel unit stay.
     * Package private so {@code SalvageGameTest} can drive it without a screen.
     *
     * @return what was paid for, or {@code null} if nothing was
     */
    static Quote salvageContents(ServerPlayer player, SimpleContainer input) {
        if (levelTooLow(player)) {
            return null;
        }
        Quote q = quote(input);
        if (!q.anything()) {
            return null;
        }
        int keysToTake = q.keysTaken();
        for (int i = 0; i < INPUT_SLOTS; i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            Kind kind = classify(stack).kind();
            if (kind == Kind.KEY) {
                int taken = Math.min(keysToTake, stack.getCount());
                keysToTake -= taken;
                stack.shrink(taken);
                input.setItem(i, stack.isEmpty() ? ItemStack.EMPTY : stack);
            } else if (kind != Kind.REFUSED) {
                input.setItem(i, ItemStack.EMPTY);
            }
        }

        int emeralds = q.emeralds();
        int fuel = q.keyFuel();
        deliverEmeralds(player, emeralds);
        if (fuel > 0) {
            Fuel.grant(player, fuel);
        }
        if (q.xp() > 0) {
            player.giveExperiencePoints(q.xp());
        }
        player.level().playSound(null, player.blockPosition(), SoundEvents.GRINDSTONE_USE,
                SoundSource.BLOCKS, 1.0f, 1.0f);

        List<String> parts = new ArrayList<>();
        if (emeralds > 0) {
            parts.add(emeralds + " emerald" + plural(emeralds));
        }
        if (fuel > 0) {
            parts.add(fuel + " fuel");
        }
        if (q.xp() > 0) {
            parts.add(q.xp() + " XP");
        }
        player.sendSystemMessage(Component.literal("Salvaged for " + (parts.isEmpty() ? "nothing"
                : String.join(", ", parts)) + ".").withStyle(ChatFormatting.AQUA));

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("gear", q.gear());
        extras.put("keys", q.keysTaken());
        extras.put("ominous_keys", q.ominousKeys());
        extras.put("mob_gear", q.mobGear());
        extras.put("emeralds", emeralds);
        extras.put("fuel", fuel);
        extras.put("xp", q.xp());
        PlaytestJournal.salvage(player, extras);
        return q;
    }

    /** Emeralds in whole stacks, through {@link Payout#deliver} so a full inventory drops the rest. */
    private static void deliverEmeralds(ServerPlayer player, int emeralds) {
        Item emerald = Items.EMERALD;
        int max = new ItemStack(emerald).getMaxStackSize();
        while (emeralds > 0) {
            int n = Math.min(max, emeralds);
            Payout.deliver(player, new ItemStack(emerald, n));
            emeralds -= n;
        }
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
