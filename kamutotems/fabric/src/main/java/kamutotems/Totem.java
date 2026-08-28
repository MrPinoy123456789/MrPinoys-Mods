package kamutotems;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import kamutotems.core.AuraKind;
import kamutotems.core.AuraSpec;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Repairable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import thingy.api.VirtualTag;

import kamutotems.core.Construct;
import kamutotems.core.HostType;
import kamutotems.core.KamuCatalog;
import kamutotems.core.Kamuy;
import kamutotems.core.Slot;

/**
 * Stack construction and custom_data read/write for the Kamu Totem.
 *
 * <p>The totem is a vanilla {@code minecraft:totem_of_undying} carrying
 * {@code minecraft:custom_data}. It is bound to the player through the
 * {@link Kamuy}, not the item -- losing the totem and running {@code /totem}
 * returns it with its name, journal and slots intact.
 */
public final class Totem {

    public static final String KEY = "kamutotems";
    public static final String TOTEM_KEY = "totem";
    public static final String KAMU_KEY = "kamu";
    public static final String AURA_KEY = "aura";

    private static final int MAX_CHARGES = 16;
    private static final int MAX_LORE_LINES = 5;
    private static final String UNNAMED = "an unnamed spirit";

    public static int maxCharges() {
        return MAX_CHARGES;
    }

    private Totem() {}

    public static boolean is(ItemStack stack) {
        return VirtualTag.is(stack, KEY, TOTEM_KEY);
    }

    public static boolean isKamu(ItemStack stack) {
        CompoundTag root = KamuTag.root(stack);
        if (root == null) {
            return false;
        }
        CompoundTag inner = root.getCompoundOrEmpty(KEY);
        return inner.contains(KAMU_KEY) && !inner.getStringOr(KAMU_KEY, "").isBlank();
    }

    public static String kamuId(ItemStack stack) {
        CompoundTag root = KamuTag.root(stack);
        if (root == null) {
            return null;
        }
        return root.getCompoundOrEmpty(KEY).getStringOr(KAMU_KEY, null);
    }

    public static int kamuTier(ItemStack stack) {
        CompoundTag root = KamuTag.root(stack);
        if (root == null) {
            return 1;
        }
        int tier = root.getCompoundOrEmpty(KEY).getIntOr("tier", 1);
        return persistedTier(tier, kamuId(stack));
    }

    public static ItemStack create(Kamuy kamuy, int charges) {
        Construct construct = kamuy != null ? kamuy.construct()
                : Construct.empty(HostType.TOTEM);

        // minecraft:totem_of_undying verified as the host item (SPEC 16.1).
        ItemStack stack = new ItemStack(Items.TOTEM_OF_UNDYING, 1);

        // ⚠ SPEC §16.1: strip vanilla's DEATH_PROTECTION or the stack is consumed on death.
        // PLAY-VERIFY (SPEC 16.1): DEATH_PROTECTION and ItemStack.remove both verified present, so the strip compiles and is well-formed. That vanilla then declines to consume the stack on death can only be proven on a live server -- it is the single highest-risk item in this mod.
        stack.remove(DataComponents.DEATH_PROTECTION);

        stack.set(DataComponents.MAX_DAMAGE, MAX_CHARGES);
        stack.setDamageValue(MAX_CHARGES - charges);
        stack.set(DataComponents.MAX_STACK_SIZE, 1);
        stack.set(DataComponents.REPAIRABLE,
                new Repairable(HolderSet.direct(Items.DIAMOND.builtInRegistryHolder())));

        writeSlots(stack, new ArrayList<>(construct.slots()));
        refreshLore(stack, kamuy);
        return stack;
    }

    private static final String[] TIER_NUMERAL = { "I", "II", "III" };

    /**
     * The single source of truth for what a loose Kamu item looks like.
     *
     * <p>Both producers go through here -- the totem panel when a kamu is
     * removed (SPEC section 5.8) and {@code BossDrops} when one is dropped
     * (SPEC section 7.2). They previously built the stack independently and
     * disagreed on the host item, so a removed kamu and a dropped kamu were
     * different items that would not stack or look alike. Do not re-fork this.
     */
    public static ItemStack createKamu(String id, int tier) {
        kamutotems.core.Kamu definition = KamuData.catalog().get(id);
        String display = definition != null ? definition.displayName() : id;
        int t = Math.max(1, Math.min(3, tier));

        String itemId = KamuTotemsConfig.s("totem", "kamu_item", "minecraft:amethyst_shard");
        Item host = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
        if (host == null || host == Items.AIR) {
            host = Items.AMETHYST_SHARD;
        }

        ItemStack stack = new ItemStack(host, 1);
        stack.set(DataComponents.ITEM_NAME,
                Component.literal(display + " " + TIER_NUMERAL[t - 1])
                        .withStyle(tierColour(t)));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Kamu").withStyle(ChatFormatting.DARK_GRAY))));
        // Tiers must not stack together, and fusion consumes exactly two.
        stack.set(DataComponents.MAX_STACK_SIZE, 1);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag inner = new CompoundTag();
            inner.putString(KAMU_KEY, id);
            inner.putInt("tier", t);
            tag.put(KEY, inner);
        });
        return stack;
    }

    private static ChatFormatting tierColour(int tier) {
        return switch (tier) {
            case 3 -> ChatFormatting.LIGHT_PURPLE;
            case 2 -> ChatFormatting.AQUA;
            default -> ChatFormatting.GRAY;
        };
    }

    public static int chargesRemaining(ItemStack stack) {
        Integer max = stack.get(DataComponents.MAX_DAMAGE);
        if (max == null) {
            return 0;
        }
        return Math.max(0, max - stack.getDamageValue());
    }

    public static void consumeCharge(ItemStack stack) {
        int max = stack.getMaxDamage();
        stack.setDamageValue(Math.min(max, stack.getDamageValue() + 1));
    }

    public static void setCharges(ItemStack stack, int charges) {
        stack.setDamageValue(Math.max(0, Math.min(MAX_CHARGES, MAX_CHARGES - charges)));
    }

    /** Read the aura face from the totem. Fails open to no aura. */
    public static AuraSpec auraSpec(ItemStack stack) {
        CompoundTag root = KamuTag.root(stack);
        if (root == null) {
            return AuraSpec.none();
        }
        CompoundTag inner = root.getCompoundOrEmpty(KEY);
        CompoundTag aura = inner.getCompoundOrEmpty(AURA_KEY);
        if (aura.isEmpty()) {
            return AuraSpec.none();
        }
        String kind = aura.getStringOr("kind", "");
        String modifier = aura.getStringOr("modifier", "");
        int tier = aura.getIntOr("tier", 1);
        if (kind.isBlank() || modifier.isBlank()) {
            return AuraSpec.none();
        }
        try {
            return new AuraSpec(AuraKind.valueOf(kind.toUpperCase()), modifier, tier);
        } catch (IllegalArgumentException ex) {
            return AuraSpec.none();
        }
    }

    /** Write the aura face to the totem. {@code AuraSpec.none()} clears it. */
    public static void writeAura(ItemStack stack, AuraSpec spec) {
        if (spec == null || !spec.isPresent()) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
                CompoundTag inner = tag.getCompoundOrEmpty(KEY);
                inner.remove(AURA_KEY);
                tag.put(KEY, inner);
            });
            return;
        }
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag inner = tag.getCompoundOrEmpty(KEY);
            CompoundTag aura = new CompoundTag();
            aura.putString("kind", spec.kind().name());
            aura.putString("modifier", spec.modifierKamuId());
            aura.putInt("tier", spec.tier());
            inner.put(AURA_KEY, aura);
            tag.put(KEY, inner);
        });
    }

    public static List<Slot> readSlots(ItemStack stack) {
        List<Slot> out = new ArrayList<>();
        CompoundTag root = KamuTag.root(stack);
        if (root == null) {
            for (int i = 0; i < Construct.SLOT_COUNT; i++) {
                out.add(null);
            }
            return out;
        }
        CompoundTag inner = root.getCompoundOrEmpty(KEY);
        ListTag list = inner.getListOrEmpty("slots");
        for (int i = 0; i < Construct.SLOT_COUNT; i++) {
            if (i < list.size()) {
                CompoundTag entry = list.getCompoundOrEmpty(i);
                String id = entry.getStringOr("id", "");
                if (id.isBlank()) {
                    out.add(null);
                } else {
                    out.add(new Slot(id, persistedTier(entry.getIntOr("tier", 1), id)));
                }
            } else {
                out.add(null);
            }
        }
        while (out.size() < Construct.SLOT_COUNT) {
            out.add(null);
        }
        return out;
    }

    private static int persistedTier(int tier, String kamuId) {
        if (tier >= 1 && tier <= Slot.MAX_TIER) {
            return tier;
        }
        int repaired = Math.max(1, Math.min(Slot.MAX_TIER, tier));
        KamuTotemsMod.LOG.warn("Repairing invalid persisted tier {} for kamu {} to {}",
                tier, kamuId, repaired);
        return repaired;
    }

    public static void writeSlots(ItemStack stack, List<Slot> slots) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag inner = tag.getCompoundOrEmpty(KEY);
            inner.putBoolean(TOTEM_KEY, true);
            ListTag list = new ListTag();
            for (Slot slot : slots) {
                CompoundTag entry = new CompoundTag();
                if (slot == null) {
                    entry.putString("id", "");
                    entry.putInt("tier", 0);
                } else {
                    entry.putString("id", slot.kamuId());
                    entry.putInt("tier", slot.tier());
                }
                list.add(entry);
            }
            inner.put("slots", list);
            tag.put(KEY, inner);
        });
    }

    public static void refreshLore(ItemStack stack, Kamuy kamuy) {
        if (!is(stack)) {
            return;
        }
        int charges = chargesRemaining(stack);

        if (charges <= 0) {
            stack.set(DataComponents.ITEM_NAME,
                    Component.literal("Dormant Totem").withStyle(ChatFormatting.GRAY));
        } else {
            stack.set(DataComponents.ITEM_NAME,
                    Component.literal("Kamu Totem").withStyle(ChatFormatting.AQUA));
        }

        List<Component> lore = new ArrayList<>();
        String name = (kamuy != null && kamuy.name() != null && !kamuy.name().isBlank())
                ? kamuy.name() : UNNAMED;
        lore.add(Component.literal(name).withStyle(ChatFormatting.GOLD));
        lore.add(auraLine(stack));
        lore.add(Component.literal("Take it to a fletching table to shape it.")
                .withStyle(ChatFormatting.DARK_GRAY));

        if (kamuy != null) {
            for (String line : kamuy.journal()) {
                if (lore.size() >= MAX_LORE_LINES) {
                    break;
                }
                lore.add(Component.literal(line)
                        .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
            }
        }

        if (charges <= 0) {
            lore.add(Component.literal("It sleeps. Repair it at an anvil with diamonds.")
                    .withStyle(ChatFormatting.RED, ChatFormatting.ITALIC));
        } else {
            lore.add(Component.literal("Charges: " + charges + "/" + MAX_CHARGES)
                    .withStyle(charges > 3 ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        }

        stack.set(DataComponents.LORE, new ItemLore(lore));
    }

    /** One-line description of the current aura for item lore. */
    private static Component auraLine(ItemStack stack) {
        AuraSpec spec = auraSpec(stack);
        if (spec == null || !spec.isPresent()) {
            return Component.literal("Aura: none")
                    .withStyle(ChatFormatting.DARK_GRAY);
        }
        kamutotems.core.Kamu def = KamuData.catalog().get(spec.modifierKamuId());
        String modifier = def != null ? def.displayName() : spec.modifierKamuId();
        String text = "Aura: " + spec.kind().label() + " of " + modifier + " "
                + TIER_NUMERAL[Math.max(1, Math.min(3, spec.tier())) - 1];
        return Component.literal(text).withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    public static ItemStack find(ServerPlayer player) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (is(s)) {
                return s;
            }
        }
        return null;
    }

    public static void giveOrDrop(ServerPlayer player, ItemStack stack) {
        player.getInventory().add(stack);
        if (!stack.isEmpty()) {
            player.drop(stack, false);
        }
    }

    public static void updatePlayerTotem(ServerPlayer player, Kamuy kamuy, int charges) {
        ItemStack found = find(player);
        if (found == null) {
            giveOrDrop(player, create(kamuy, charges));
            return;
        }
        writeSlots(found, new ArrayList<>(kamuy.construct().slots()));
        setCharges(found, charges);
        refreshLore(found, kamuy);
    }

}
