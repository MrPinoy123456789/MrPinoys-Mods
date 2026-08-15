package kamutotems;

import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import eu.pb4.sgui.api.gui.SlotBasedGui;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import kamutotems.core.AuraKind;
import kamutotems.core.AuraSpec;
import kamutotems.core.Construct;
import kamutotems.core.HostType;
import kamutotems.core.ImbueCost;
import kamutotems.core.Kamuy;
import kamutotems.core.Slot;
import kamutotems.core.SlotRole;

/**
 * The totem panel: place bound kamu into three typed slots, take them back out,
 * and open a second tab to carve the aura face.
 *
 * <p>A kamu exists in one of three states, with one explicit action between
 * each:
 *
 * <pre>
 *   item  --right-click-->  bound (pool)  --click-->  slotted
 *         &lt;--shift-click--               &lt;--click--
 * </pre>
 *
 * <p>The construct is now three slots: one delivery and two modifiers. The
 * aura is a separate tab, not a fourth slot.
 */
public final class SlotMenu {

    /** Delivery, Modifier, Modifier. */
    private static final int[] SLOT_POSITIONS = { 11, 13, 15 };
    private static final int POOL_LABEL_SLOT = 36;
    private static final int[] POOL_POSITIONS = { 37, 38, 39, 40, 41, 42, 43, 44 };

    private static final Map<UUID, Runnable> PENDING_BACK = new HashMap<>();

    private SlotMenu() {}

    public static void open(ServerPlayer player) {
        PENDING_BACK.remove(player.getUUID());
        redraw(player);
    }

    public static void openFrom(ServerPlayer player, Runnable back) {
        PENDING_BACK.put(player.getUUID(), back);
        redraw(player);
    }

    private static void redraw(ServerPlayer player) {
        Kamuy kamuy = TotemHost.KamuyStore.getOrCreate(player);
        ItemStack totem = Totem.find(player);
        int charges = totem != null ? Totem.chargesRemaining(totem) : 0;

        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x6, player, false);
        gui.setTitle(Component.literal("Kamu Totem").withStyle(ChatFormatting.AQUA));
        build(gui, player, kamuy, charges);
        gui.open();
    }

    private static void build(SimpleGui gui, ServerPlayer player, Kamuy kamuy, int charges) {
        String name = kamuy.name() != null ? kamuy.name() : "an unnamed spirit";

        gui.setSlot(4, new GuiElementBuilder(Items.TOTEM_OF_UNDYING)
                .setName(Component.literal(name).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .addLoreLine(Component.literal("Charges: " + charges + "/" + Totem.maxCharges())
                        .withStyle(charges > 0 ? ChatFormatting.GREEN : ChatFormatting.RED))
                .addLoreLine(sentence(kamuy.construct().slots()))
                .build());

        gui.setSlot(8, new GuiElementBuilder(Items.AMETHYST_SHARD)
                .setName(Component.literal("Aura").withStyle(ChatFormatting.LIGHT_PURPLE))
                .addLoreLine(Component.literal("Click to shape the totem's aura face.")
                        .withStyle(ChatFormatting.GRAY))
                .setCallback((i, type, input, g) -> openAura(player, (SlotBasedGui) g))
                .build());

        List<Slot> slots = kamuy.construct().slots();
        for (int i = 0; i < SLOT_POSITIONS.length && i < slots.size(); i++) {
            gui.setSlot(SLOT_POSITIONS[i], slotted(i, slots.get(i), player));
        }

        List<Slot> pool = kamuy.pool();
        gui.setSlot(POOL_LABEL_SLOT, new GuiElementBuilder(Items.BUNDLE)
                .setName(Component.literal("Bound kamu").withStyle(ChatFormatting.AQUA))
                .addLoreLine(Component.literal(pool.isEmpty()
                                ? "Right-click holding a kamu to bind it here."
                                : pool.size() + " waiting to be placed.")
                        .withStyle(ChatFormatting.GRAY))
                .build());

        for (int i = 0; i < POOL_POSITIONS.length && i < pool.size(); i++) {
            gui.setSlot(POOL_POSITIONS[i], pooled(i, pool.get(i), player));
        }

        Runnable back = PENDING_BACK.get(player.getUUID());
        if (back != null) {
            gui.setSlot(StationRouting.BACK_SLOT, StationRouting.backButton(back));
        }
    }

    /** A construct slot. Click a real kamu to pop it back to the pool. */
    private static GuiElement slotted(int index, Slot slot, ServerPlayer player) {
        SlotRole role = Construct.roleOf(index);

        if (slot == null) {
            return new GuiElementBuilder(emptyIconFor(role))
                    .setName(Component.literal(role.label()).withStyle(ChatFormatting.DARK_GRAY))
                    .addLoreLine(Component.literal(hintFor(role)).withStyle(ChatFormatting.DARK_GRAY))
                    .build();
        }

        kamutotems.core.Kamu def = KamuData.catalog().get(slot.kamuId());
        String display = def != null ? def.displayName() : slot.kamuId();
        List<Component> lore = new ArrayList<>();
        lore.add(Component.literal(role.label()).withStyle(ChatFormatting.DARK_GRAY));

        if (Construct.isDefault(index, slot)) {
            lore.add(Component.literal(hintFor(role)).withStyle(ChatFormatting.DARK_GRAY));
            return new GuiElementBuilder(iconFor(slot.kamuId()))
                    .setName(Component.literal(display).withStyle(ChatFormatting.GRAY))
                    .setLore(lore)
                    .build();
        }

        int cost = ImbueCost.remove(slot.tier(), removeCosts());
        lore.add(Component.literal("Click to remove: " + cost + " cobblestone")
                .withStyle(ChatFormatting.YELLOW));
        return new GuiElementBuilder(iconFor(slot.kamuId()))
                .setName(Component.literal(display + " " + roman(slot.tier()))
                        .withStyle(ChatFormatting.AQUA))
                .setLore(lore)
                .setCallback((i, type, input, gui) -> handleRemove(index, player, (SlotBasedGui) gui))
                .build();
    }

    /** A bound kamu: click to place, shift-click to take back as an item. */
    private static GuiElement pooled(int poolIndex, Slot slot, ServerPlayer player) {
        kamutotems.core.Kamu def = KamuData.catalog().get(slot.kamuId());
        String display = def != null ? def.displayName() : slot.kamuId();
        SlotRole role = def != null ? roleFor(def.category()) : SlotRole.MODIFIER;

        List<Component> lore = new ArrayList<>();
        lore.add(Component.literal("Goes in the " + role.label() + " slot")
                .withStyle(ChatFormatting.DARK_GRAY));
        lore.add(Component.literal("Click to place: " + ImbueCost.imbue(slot.tier(), imbueCosts())
                        + " cobblestone")
                .withStyle(ChatFormatting.GREEN));
        lore.add(Component.literal("Shift-click to take back as an item")
                .withStyle(ChatFormatting.GRAY));

        return new GuiElementBuilder(iconFor(slot.kamuId()))
                .setName(Component.literal(display + " " + roman(slot.tier()))
                        .withStyle(ChatFormatting.WHITE))
                .setLore(lore)
                .setCallback((i, type, input, gui) -> {
                    if (type.shift) {
                        handleEject(poolIndex, player, (SlotBasedGui) gui);
                    } else {
                        handlePlace(poolIndex, slot, player, (SlotBasedGui) gui);
                    }
                })
                .build();
    }

    /** Pool to slot. Charges cobblestone; the kamu leaves the pool. */
    private static void handlePlace(int poolIndex, Slot slot, ServerPlayer player, SlotBasedGui gui) {
        Kamuy kamuy = TotemHost.KamuyStore.getOrCreate(player);
        if (poolIndex >= kamuy.pool().size()) {
            gui.close();
            redraw(player);
            return;
        }

        kamutotems.core.Kamu def = KamuData.catalog().get(slot.kamuId());
        if (def == null) {
            player.sendSystemMessage(Component.literal("This totem doesn't recognise that spirit.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        List<Slot> slots = new ArrayList<>(kamuy.construct().slots());
        int target = -1;
        for (int i = 0; i < slots.size(); i++) {
            if (!Construct.roleOf(i).accepts(def.category())) {
                continue;
            }
            if (slots.get(i) == null || Construct.isDefault(i, slots.get(i))) {
                target = i;
                break;
            }
        }
        if (target < 0) {
            player.sendSystemMessage(Component.literal(
                            "No free " + roleFor(def.category()).label()
                                    + " slot. Remove one first.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        int cost = ImbueCost.imbue(slot.tier(), imbueCosts());
        if (Cobble.count(player) < cost) {
            Cobble.notifyShortfall(player, cost);
            return;
        }
        if (!Cobble.consume(player, cost)) {
            player.sendSystemMessage(Component.literal("Could not remove cobblestone.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        slots.set(target, slot);
        Kamuy next = kamuy.removeFromPool(poolIndex)
                .withConstruct(new Construct(HostType.TOTEM, slots));
        TotemHost.KamuyStore.put(player.getUUID(), next);
        syncTotem(player, next);

        if (next.name() == null) {
            player.sendSystemMessage(Component.literal(
                            "This spirit is awake. Name it: /totem name <text>")
                    .withStyle(ChatFormatting.YELLOW));
        }
        Chime.play(player, SoundEvents.NOTE_BLOCK_CHIME, 0.2f, 1.0f);
        gui.close();
        redraw(player);
    }

    /** Slot to pool. Charges cobblestone; the default returns to the slot. */
    private static void handleRemove(int index, ServerPlayer player, SlotBasedGui gui) {
        Kamuy kamuy = TotemHost.KamuyStore.getOrCreate(player);
        List<Slot> slots = new ArrayList<>(kamuy.construct().slots());
        Slot slot = slots.get(index);
        if (slot == null || Construct.isDefault(index, slot)) {
            return;
        }

        int cost = ImbueCost.remove(slot.tier(), removeCosts());
        if (Cobble.count(player) < cost) {
            Cobble.notifyShortfall(player, cost);
            return;
        }
        if (!Cobble.consume(player, cost)) {
            player.sendSystemMessage(Component.literal("Could not remove cobblestone.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        slots.set(index, Construct.defaultFor(index));
        List<Slot> pool = new ArrayList<>(kamuy.pool());
        pool.add(slot);
        Kamuy next = kamuy.withConstruct(new Construct(HostType.TOTEM, slots))
                .withPool(pool);
        TotemHost.KamuyStore.put(player.getUUID(), next);
        syncTotem(player, next);

        player.sendSystemMessage(Component.literal(defName(slot) + " returned to your bound kamu.")
                .withStyle(ChatFormatting.GRAY));
        gui.close();
        redraw(player);
    }

    /** Pool to item. Free -- you are only changing what form it is in. */
    private static void handleEject(int poolIndex, ServerPlayer player, SlotBasedGui gui) {
        Kamuy kamuy = TotemHost.KamuyStore.getOrCreate(player);
        if (poolIndex >= kamuy.pool().size()) {
            return;
        }
        Slot slot = kamuy.pool().get(poolIndex);
        Totem.giveOrDrop(player, Totem.createKamu(slot.kamuId(), slot.tier()));
        TotemHost.KamuyStore.put(player.getUUID(), kamuy.removeFromPool(poolIndex));

        player.sendSystemMessage(Component.literal(defName(slot) + " taken back as an item.")
                .withStyle(ChatFormatting.GRAY));
        gui.close();
        redraw(player);
    }

    /**
     * Aura tab. The player chooses an aura kind, then picks a bound modifier.
     * Shaping the aura is free -- it is not consumed, it just sets the face.
     */
    private static void openAura(ServerPlayer player, SlotBasedGui back) {
        Kamuy kamuy = TotemHost.KamuyStore.getOrCreate(player);
        ItemStack totem = Totem.find(player);
        AuraSpec current = totem != null ? Totem.auraSpec(totem) : AuraSpec.none();

        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x6, player, false);
        gui.setTitle(Component.literal("Kamu Aura").withStyle(ChatFormatting.LIGHT_PURPLE));

        gui.setSlot(0, new GuiElementBuilder(Items.ARROW)
                .setName(Component.literal("Back").withStyle(ChatFormatting.WHITE))
                .setCallback((i, type, input, g) -> {
                    g.close();
                    redraw(player);
                })
                .build());

        gui.setSlot(13, new GuiElementBuilder(Items.AMETHYST_SHARD)
                .setName(current.isPresent()
                        ? Component.literal("Aura: " + current.kind().label() + " of "
                                + defName(new Slot(current.modifierKamuId(), current.tier())))
                                .withStyle(ChatFormatting.LIGHT_PURPLE)
                        : Component.literal("No aura bound").withStyle(ChatFormatting.GRAY))
                .addLoreLine(Component.literal("The aura runs while the totem has charges.")
                        .withStyle(ChatFormatting.DARK_GRAY))
                .build());

        if (current.isPresent()) {
            gui.setSlot(22, new GuiElementBuilder(Items.BARRIER)
                    .setName(Component.literal("Clear aura").withStyle(ChatFormatting.RED))
                    .setCallback((i, type, input, g) -> {
                        ItemStack t = Totem.find(player);
                        if (t != null) {
                            Totem.writeAura(t, AuraSpec.none());
                            Totem.refreshLore(t, kamuy);
                        }
                        g.close();
                        openAura(player, back);
                    })
                    .build());
        }

        int[] kindSlots = { 29, 30, 31, 32 };
        AuraKind[] kinds = AuraKind.values();
        for (int i = 0; i < kindSlots.length && i < kinds.length; i++) {
            AuraKind kind = kinds[i];
            gui.setSlot(kindSlots[i], new GuiElementBuilder(iconFor(kind.name().toLowerCase()))
                    .setName(Component.literal(kind.label()).withStyle(ChatFormatting.AQUA))
                    .setCallback((idx, t, input, g) -> openAuraModifier(player, kind, g))
                    .build());
        }

        gui.setSlot(40, new GuiElementBuilder(Items.PAPER)
                .setName(Component.literal("Choose a shape, then pick a bound spirit.")
                        .withStyle(ChatFormatting.GRAY))
                .build());

        back.close();
        gui.open();
    }

    private static void openAuraModifier(ServerPlayer player, AuraKind kind, SlotBasedGui back) {
        Kamuy kamuy = TotemHost.KamuyStore.getOrCreate(player);
        List<Slot> pool = kamuy.pool();

        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x6, player, false);
        gui.setTitle(Component.literal(kind.label() + " modifier").withStyle(ChatFormatting.AQUA));

        gui.setSlot(0, new GuiElementBuilder(Items.ARROW)
                .setName(Component.literal("Back").withStyle(ChatFormatting.WHITE))
                .setCallback((i, type, input, g) -> {
                    g.close();
                    openAura(player, back);
                })
                .build());

        for (int i = 0; i < POOL_POSITIONS.length && i < pool.size(); i++) {
            Slot slot = pool.get(i);
            gui.setSlot(POOL_POSITIONS[i], new GuiElementBuilder(iconFor(slot.kamuId()))
                    .setName(Component.literal(defName(slot)).withStyle(ChatFormatting.WHITE))
                    .addLoreLine(Component.literal("Click to shape as " + kind.label())
                            .withStyle(ChatFormatting.GREEN))
                    .setCallback((idx, type, input, g) -> handleAuraBind(player, kind, slot, g))
                    .build());
        }

        back.close();
        gui.open();
    }

    private static void handleAuraBind(ServerPlayer player, AuraKind kind, Slot slot,
                                       SlotBasedGui gui) {
        ItemStack totem = Totem.find(player);
        if (totem == null) {
            player.sendSystemMessage(Component.literal("You are not holding a totem.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        kamutotems.core.Kamu def = KamuData.catalog().get(slot.kamuId());
        if (def == null) {
            player.sendSystemMessage(Component.literal("Unknown spirit.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (!SlotRole.MODIFIER.accepts(def.category())) {
            player.sendSystemMessage(Component.literal("Only modifiers can be shaped into an aura.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        AuraSpec next = new AuraSpec(kind, slot.kamuId(), slot.tier());
        Totem.writeAura(totem, next);
        Totem.refreshLore(totem, TotemHost.KamuyStore.getOrCreate(player));

        player.sendSystemMessage(Component.literal("Aura set: " + kind.label() + " of "
                        + defName(slot) + ".")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        Chime.play(player, SoundEvents.NOTE_BLOCK_CHIME, 0.3f, 1.2f);
        gui.close();
        redraw(player);
    }

    private static void syncTotem(ServerPlayer player, Kamuy kamuy) {
        ItemStack totem = Totem.find(player);
        if (totem != null) {
            Totem.writeSlots(totem, new ArrayList<>(kamuy.construct().slots()));
            Totem.refreshLore(totem, kamuy);
        }
    }

    /** Which role a category belongs to. Inverse of {@link SlotRole#accepts}. */
    private static SlotRole roleFor(kamutotems.core.Category category) {
        return switch (category) {
            case DELIVERY -> SlotRole.DELIVERY;
            default -> SlotRole.MODIFIER;
        };
    }

    private static Item emptyIconFor(SlotRole role) {
        return switch (role) {
            case DELIVERY -> Items.IRON_SWORD;
            case MODIFIER -> Items.GLASS_PANE;
        };
    }

    private static String hintFor(SlotRole role) {
        return switch (role) {
            case DELIVERY -> "How it arrives. Hit = your own attack.";
            case MODIFIER -> "What it does. Plain = no change.";
        };
    }

    /**
     * The construct as a sentence -- the source spec's whole player-facing
     * mental model: the delivery plus the two modifiers.
     */
    private static Component sentence(List<Slot> slots) {
        Slot delivery = slots.isEmpty() ? null : slots.get(0);
        List<String> mods = new ArrayList<>();
        for (int i = 1; i < slots.size(); i++) {
            Slot s = slots.get(i);
            if (s != null && !Construct.isDefault(i, s)) {
                mods.add(defName(s));
            }
        }

        String doText = (delivery == null || Construct.isDefault(0, delivery))
                ? "My attack hits" : defName(delivery);
        String withText = mods.isEmpty() ? "" : " with " + String.join(", then ", mods);

        return Component.literal(doText + withText)
                .withStyle(ChatFormatting.WHITE);
    }

    private static String defName(Slot slot) {
        kamutotems.core.Kamu def = KamuData.catalog().get(slot.kamuId());
        String base = def != null ? def.displayName() : slot.kamuId();
        boolean isBaseline = Construct.DEFAULT_DELIVERY.equals(slot.kamuId())
                || Construct.DEFAULT_MODIFIER.equals(slot.kamuId());
        return isBaseline ? base : base + " " + roman(slot.tier());
    }

    private static int[] imbueCosts() {
        return new int[] {
                KamuTotemsConfig.i("totem", "imbue_t1", 32),
                KamuTotemsConfig.i("totem", "imbue_t2", 96),
                KamuTotemsConfig.i("totem", "imbue_t3", 256)
        };
    }

    private static int[] removeCosts() {
        return new int[] {
                KamuTotemsConfig.i("totem", "remove_t1", 16),
                KamuTotemsConfig.i("totem", "remove_t2", 48),
                KamuTotemsConfig.i("totem", "remove_t3", 128)
        };
    }

    static final class Cobble {
        static int count(ServerPlayer player) {
            int total = 0;
            Inventory inv = player.getInventory();
            for (int i = 0; i < 36 && i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (s.is(Items.COBBLESTONE)) {
                    total += s.getCount();
                }
            }
            return total;
        }

        static boolean consume(ServerPlayer player, int amount) {
            if (count(player) < amount) {
                return false;
            }
            Inventory inv = player.getInventory();
            int remaining = amount;
            for (int i = 0; i < 36 && i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (!s.is(Items.COBBLESTONE)) {
                    continue;
                }
                int take = Math.min(s.getCount(), remaining);
                s.shrink(take);
                if (s.isEmpty()) {
                    inv.setItem(i, ItemStack.EMPTY);
                }
                remaining -= take;
                if (remaining == 0) {
                    return true;
                }
            }
            return false;
        }

        static void notifyShortfall(ServerPlayer player, int need) {
            int have = count(player);
            player.sendSystemMessage(Component.literal(
                            "Not enough cobblestone (need " + need + ", have " + have + ")")
                    .withStyle(ChatFormatting.RED));
        }
    }

    private static String roman(int tier) {
        return switch (tier) {
            case 2 -> "II";
            case 3 -> "III";
            default -> "I";
        };
    }

    static Item iconFor(String kamuId) {
        return switch (kamuId) {
            // Deliveries
            case "hit" -> Items.IRON_SWORD;
            case "echo" -> Items.CLOCK;
            case "splash" -> Items.SPLASH_POTION;
            case "plain" -> Items.GLASS_PANE;
            // Elements and behaviours
            case "fire" -> Items.BLAZE_POWDER;
            case "ice" -> Items.SNOWBALL;
            case "poison" -> Items.SPIDER_EYE;
            case "wither" -> Items.WITHER_ROSE;
            case "lightning" -> Items.COPPER_INGOT;
            case "heal" -> Items.GOLDEN_APPLE;
            case "absorption" -> Items.GOLDEN_CARROT;
            // Auras, keyed by AuraKind.name().toLowerCase() (see the aura tab)
            case "bloom" -> Items.LILY_PAD;
            case "focus" -> Items.CLOCK;
            case "momentum" -> Items.FEATHER;
            case "rebuke" -> Items.SHIELD;
            default -> Items.PAPER;
        };
    }
}
