package spiritwolves;

import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import eu.pb4.sgui.api.gui.SlotBasedGui;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side chest GUI for the wolf's abilities, in its two rows: Fangs above,
 * Tricks below.
 *
 * <p>Left-click an ability to equip or set it aside; right-click to attune the
 * next tier. The two categories draw on separate slot pools ({@link Souls}), and
 * the row header for each shows that pool's usage, so the tradeoff a player is
 * actually making is visible where they make it. All validation still happens
 * server-side in {@link Abilities}, exactly as it does for the chat commands.
 */
public final class AbilityGui {

    /** Row 1 (of a 6-row chest): up to six fangs, centred. */
    private static final int[] FANG_SLOTS = { 11, 12, 13, 14, 15, 16 };

    /** Row 3: the tricks. */
    private static final int[] TRICK_SLOTS = { 29, 30, 31, 32 };

    private static final int HEADER_SLOT = 4;
    private static final int FANG_LABEL_SLOT = 9;
    private static final int TRICK_LABEL_SLOT = 27;
    private static final int CLOSE_SLOT = 53;

    private AbilityGui() {}

    public static void open(ServerPlayer player) {
        WolfRecord record = PlayerWolfRegistry.get(player.getUUID());
        if (record == null) {
            player.sendSystemMessage(Component.literal("You have no spirit wolf.")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }

        int level = Souls.levelFor(record.souls);
        String name = record.wolfName != null ? record.wolfName : "Your wolf";

        int fangsUsed = Abilities.equippedCount(record, Abilities.Category.FANG);
        int fangSlots = Souls.fangSlotsFor(level);
        int tricksUsed = Abilities.equippedCount(record, Abilities.Category.TRICK);
        int trickSlots = Souls.trickSlotsFor(level);

        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x6, player, false);
        gui.setTitle(Component.literal(name + " — Level " + level + " · "
                        + record.souls + " souls")
                .withStyle(ChatFormatting.GOLD));

        gui.setSlot(HEADER_SLOT, header(record, level, fangsUsed, fangSlots, tricksUsed, trickSlots));
        gui.setSlot(FANG_LABEL_SLOT, label(Items.BONE, "Fangs", ChatFormatting.RED,
                "What the wolf does in a fight.", fangsUsed, fangSlots));
        gui.setSlot(TRICK_LABEL_SLOT, label(Items.LEAD, "Tricks", ChatFormatting.GREEN,
                "What the wolf does for you.", tricksUsed, trickSlots));

        fill(gui, player, record, Abilities.FANGS, FANG_SLOTS);
        fill(gui, player, record, Abilities.TRICKS, TRICK_SLOTS);

        gui.setSlot(CLOSE_SLOT, new GuiElementBuilder(Items.BARRIER)
                .setName(Component.literal("Close").withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                .setCallback((i, t, a, g) -> g.close())
                .build());

        gui.open();
    }

    private static GuiElement header(WolfRecord record, int level,
                                     int fangsUsed, int fangSlots, int tricksUsed, int trickSlots) {
        return new GuiElementBuilder(Items.WOLF_SPAWN_EGG)
                .setName(Component.literal(record.wolfName != null ? record.wolfName : "Spirit Wolf")
                        .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                .addLoreLine(Component.literal("Level " + level).withStyle(ChatFormatting.YELLOW))
                .addLoreLine(Component.literal(record.souls + " souls").withStyle(ChatFormatting.LIGHT_PURPLE))
                .addLoreLine(Component.literal("Fangs " + fangsUsed + "/" + fangSlots
                        + "   Tricks " + tricksUsed + "/" + trickSlots).withStyle(ChatFormatting.GRAY))
                .build();
    }

    private static GuiElement label(Item icon, String title, ChatFormatting color,
                                    String blurb, int used, int slots) {
        return new GuiElementBuilder(icon)
                .setName(Component.literal(title).withStyle(color, ChatFormatting.BOLD))
                .addLoreLine(Component.literal(blurb).withStyle(ChatFormatting.GRAY))
                .addLoreLine(Component.literal(used + "/" + slots + " carried")
                        .withStyle(used >= slots ? ChatFormatting.RED : ChatFormatting.GREEN))
                .build();
    }

    private static void fill(SimpleGui gui, ServerPlayer player, WolfRecord record,
                             List<Abilities.Ability> abilities, int[] slots) {
        int diamonds = AbilityCommands.countDiamonds(player);
        for (int i = 0; i < abilities.size() && i < slots.length; i++) {
            gui.setSlot(slots[i], element(player, record, abilities.get(i), diamonds));
        }
    }

    private static GuiElement element(ServerPlayer player, WolfRecord record,
                                      Abilities.Ability ability, int diamonds) {
        Abilities.State state = Abilities.stateOf(record, ability);
        int progress = record.familyKills.getOrDefault(ability.id(), 0);

        if (state == Abilities.State.HIDDEN) {
            return new GuiElementBuilder(Items.BARRIER)
                    .setName(Component.literal("???")
                            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.BOLD))
                    .addLoreLine(Component.literal("Something stirs...")
                            .withStyle(ChatFormatting.DARK_GRAY))
                    .addLoreLine(Component.literal(progress + "/" + ability.unlockGoal
                            + " " + ability.progressLabel).withStyle(ChatFormatting.BLACK))
                    .build();
        }

        WolfRecord.AbilityRecord ar = record.abilities.get(ability.id());
        if (ar == null) {
            // SCENTED: progress has started but tier I is not reached yet, so no
            // record exists. Show the trail rather than an error.
            return new GuiElementBuilder(iconFor(ability))
                    .setName(Component.literal(ability.displayName())
                            .withStyle(ChatFormatting.DARK_GRAY))
                    .addLoreLine(Component.literal("Not yet learned.")
                            .withStyle(ChatFormatting.DARK_GRAY))
                    .addLoreLine(Component.literal(progress + "/" + ability.unlockGoal
                            + " " + ability.progressLabel).withStyle(ChatFormatting.DARK_GREEN))
                    .build();
        }

        boolean equipped = ar.equipped;
        GuiElementBuilder builder = new GuiElementBuilder(iconFor(ability))
                .setName(Component.literal((equipped ? "◆ " : "◇ ") + ability.displayName()
                                + " " + Abilities.roman(ar.tier))
                        .withStyle(equipped ? ChatFormatting.AQUA : ChatFormatting.WHITE,
                                equipped ? ChatFormatting.BOLD : ChatFormatting.RESET));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.literal(ability.tierEffect[Math.max(1, Math.min(3, ar.tier))])
                .withStyle(ChatFormatting.GRAY));
        lore.add(Component.literal(ability.category.singular + " · " + progress + " "
                + ability.progressLabel).withStyle(ChatFormatting.DARK_GREEN));

        if (state == Abilities.State.ATTUNED) {
            int requirement = Abilities.FILL_REQUIREMENT[ar.attunedTier];
            lore.add(Component.literal("Filling Tier " + Abilities.roman(ar.attunedTier) + ": "
                            + ar.fillKills + "/" + requirement + " " + ability.progressLabel)
                    .withStyle(ChatFormatting.GOLD));
        }

        if (ar.tier < 3) {
            int nextTier = ar.tier + 1;
            int cost = Abilities.ATTUNE_COST[nextTier];
            String rejection = Abilities.canAttune(player, record, ability, diamonds);
            if (rejection == null) {
                lore.add(Component.literal("Right-click to attune Tier " + Abilities.roman(nextTier)
                                + " for " + cost + " diamond" + (cost == 1 ? "" : "s"))
                        .withStyle(ChatFormatting.AQUA));
            } else {
                lore.add(Component.literal("Tier " + Abilities.roman(nextTier) + ": " + rejection)
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        }

        if (equipped) {
            builder.glow();
            lore.add(Component.literal("Left-click to set aside").withStyle(ChatFormatting.YELLOW));
        } else {
            lore.add(Component.literal("Left-click to carry").withStyle(ChatFormatting.GREEN));
        }

        builder.setLore(lore);

        return builder
                .setCallback((i, clickType, input, gui) -> {
                    if (clickType.isLeft) {
                        handleEquip(player, record, ability, ar, gui);
                    } else if (clickType.isRight) {
                        handleAttune(player, record, ability, ar, gui);
                    }
                })
                .build();
    }

    private static void handleEquip(ServerPlayer player, WolfRecord record, Abilities.Ability ability,
                                    WolfRecord.AbilityRecord ar, SlotBasedGui gui) {
        String rejection;
        String message;
        ChatFormatting color;
        if (ar.equipped) {
            rejection = Abilities.canUnequip(record, ability);
            if (rejection == null) {
                ar.equipped = false;
                PlayerWolfRegistry.markDirty(player.getUUID());
                message = ability.displayName() + " set aside.";
                color = ChatFormatting.GRAY;
            } else {
                message = rejection;
                color = ChatFormatting.RED;
            }
        } else {
            rejection = Abilities.canEquip(record, ability);
            if (rejection == null) {
                ar.equipped = true;
                PlayerWolfRegistry.markDirty(player.getUUID());
                message = ability.displayName() + " equipped.";
                color = ChatFormatting.GREEN;
            } else {
                message = rejection;
                color = ChatFormatting.RED;
            }
        }
        player.sendSystemMessage(Component.literal(message).withStyle(color));
        gui.close();
        open(player);
    }

    private static void handleAttune(ServerPlayer player, WolfRecord record, Abilities.Ability ability,
                                     WolfRecord.AbilityRecord ar, SlotBasedGui gui) {
        int diamonds = AbilityCommands.countDiamonds(player);
        String rejection = Abilities.canAttune(player, record, ability, diamonds);
        if (rejection != null) {
            player.sendSystemMessage(Component.literal(rejection).withStyle(ChatFormatting.RED));
            gui.close();
            open(player);
            return;
        }

        int nextTier = ar.tier + 1;
        AbilityCommands.removeDiamonds(player, Abilities.ATTUNE_COST[nextTier]);
        ar.attunedTier = nextTier;
        ar.fillKills = 0;
        PlayerWolfRegistry.markDirty(player.getUUID());

        player.sendSystemMessage(AbilityCommands.attuneMessage(ability, nextTier));
        Chime.abilityAttuned(player);

        gui.close();
        open(player);
    }

    private static Item iconFor(Abilities.Ability ability) {
        if (ability == Abilities.EMBERFANG) return Items.BLAZE_POWDER;
        if (ability == Abilities.VENOMFANG) return Items.SPIDER_EYE;
        if (ability == Abilities.RAVENOUS) return Items.ROTTEN_FLESH;
        if (ability == Abilities.BONECHILL) return Items.BONE;
        if (ability == Abilities.WITHERFANG) return Items.WITHER_SKELETON_SKULL;
        if (ability == Abilities.VOIDFANG) return Items.ENDER_PEARL;
        if (ability == Abilities.FETCH) return Items.CHEST;
        if (ability == Abilities.SHINE) return Items.TORCH;
        if (ability == Abilities.DIG) return Items.IRON_PICKAXE;
        if (ability == Abilities.SPEAK) return Items.GOAT_HORN;
        return Items.PAPER;
    }
}
