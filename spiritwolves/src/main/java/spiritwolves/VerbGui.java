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
 * Server-side chest GUI for managing spirit wolf verbs.
 *
 * <p>Replaces the chat-only {@code /spiritwolves verbs} panel with an organized
 * inventory view. Left-click a verb to equip/unequip it; right-click to attune
 * the next tier. All validation still happens server-side, exactly as it did
 * for the chat commands.
 */
public final class VerbGui {

    private static final int[] VERB_SLOTS = { 12, 13, 14, 21, 22, 23, 30, 31, 32 };

    private VerbGui() {}

    public static void open(ServerPlayer player) {
        WolfRecord record = PlayerWolfRegistry.get(player.getUUID());
        if (record == null) {
            player.sendSystemMessage(Component.literal("You have no spirit wolf.")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }

        int level = Souls.levelFor(record.souls);
        int slots = Souls.slotsFor(level);
        String name = record.wolfName != null ? record.wolfName : "Your wolf";

        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x4, player, false);
        gui.setTitle(Component.literal(name + " — Level " + level + " · "
                + record.souls + " souls · " + slots + " slots")
                .withStyle(ChatFormatting.GOLD));

        buildHeader(gui, record, level, slots);
        buildVerbSlots(gui, player, record);
        buildFooter(gui);

        gui.open();
    }

    private static void buildHeader(SimpleGui gui, WolfRecord record, int level, int slots) {
        GuiElementBuilder header = new GuiElementBuilder(Items.WOLF_SPAWN_EGG)
                .setName(Component.literal(record.wolfName != null ? record.wolfName : "Spirit Wolf")
                        .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                .addLoreLine(Component.literal("Level " + level)
                        .withStyle(ChatFormatting.YELLOW))
                .addLoreLine(Component.literal(record.souls + " souls")
                        .withStyle(ChatFormatting.LIGHT_PURPLE))
                .addLoreLine(Component.literal(slots + " verb slot" + (slots == 1 ? "" : "s"))
                        .withStyle(ChatFormatting.GRAY));
        gui.setSlot(4, header.build());
    }

    private static void buildVerbSlots(SimpleGui gui, ServerPlayer player, WolfRecord record) {
        int diamonds = VerbCommands.countDiamonds(player);
        boolean summoned = record.summoned;

        for (int i = 0; i < Verbs.ALL.size(); i++) {
            Verbs.Verb verb = Verbs.ALL.get(i);
            int slot = VERB_SLOTS[i];
            gui.setSlot(slot, verbElement(player, record, verb, diamonds, summoned));
        }
    }

    private static GuiElement verbElement(ServerPlayer player, WolfRecord record,
                                          Verbs.Verb verb, int diamonds, boolean summoned) {
        Verbs.State state = Verbs.stateOf(record, verb);
        int kills = record.familyKills.getOrDefault(verb.id(), 0);

        if (state == Verbs.State.HIDDEN) {
            return new GuiElementBuilder(Items.BARRIER)
                    .setName(Component.literal("???")
                            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.BOLD))
                    .addLoreLine(Component.literal("Something stirs...")
                            .withStyle(ChatFormatting.DARK_GRAY))
                    .addLoreLine(Component.literal(kills + "/" + verb.unlockKills + " " + verb.familyLabel)
                            .withStyle(ChatFormatting.BLACK))
                    .build();
        }

        WolfRecord.VerbRecord vr = record.verbs.get(verb.id());
        if (vr == null) {
            return new GuiElementBuilder(Items.BARRIER)
                    .setName(Component.literal("?").withStyle(ChatFormatting.RED))
                    .build();
        }

        Item icon = iconFor(verb);
        boolean equipped = vr.equipped;
        ChatFormatting nameColor = equipped ? ChatFormatting.AQUA : ChatFormatting.WHITE;
        GuiElementBuilder builder = new GuiElementBuilder(icon)
                .setName(Component.literal((equipped ? "◆ " : "◇ ") + verb.displayName + " " + Verbs.roman(vr.tier))
                        .withStyle(nameColor, equipped ? ChatFormatting.BOLD : ChatFormatting.RESET));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.literal(verb.tierEffect[Math.max(1, Math.min(3, vr.tier))])
                .withStyle(ChatFormatting.GRAY));
        lore.add(Component.literal("Family kills: " + kills)
                .withStyle(ChatFormatting.DARK_GREEN));

        if (state == Verbs.State.ATTUNED) {
            int requirement = Verbs.FILL_REQUIREMENT[vr.attunedTier];
            lore.add(Component.literal(
                    "Filling Tier " + Verbs.roman(vr.attunedTier) + ": "
                            + vr.fillKills + "/" + requirement + " " + verb.familyLabel + " kills")
                    .withStyle(ChatFormatting.GOLD));
        }

        if (vr.tier < 3) {
            int nextTier = vr.tier + 1;
            int cost = Verbs.ATTUNE_COST[nextTier];
            String rejection = Verbs.canAttune(player, record, verb, diamonds);
            if (rejection == null) {
                lore.add(Component.literal("Right-click to attune Tier " + Verbs.roman(nextTier)
                        + " for " + cost + " diamond" + (cost == 1 ? "" : "s"))
                        .withStyle(ChatFormatting.AQUA));
            } else {
                lore.add(Component.literal("Tier " + Verbs.roman(nextTier) + ": " + rejection)
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        }

        if (equipped) {
            builder.glow();
            lore.add(Component.literal("Left-click to unequip")
                    .withStyle(ChatFormatting.YELLOW));
        } else {
            lore.add(Component.literal("Left-click to equip")
                    .withStyle(ChatFormatting.GREEN));
        }

        builder.setLore(lore);

        return builder
                .setCallback((i, clickType, input, gui) -> {
                    if (clickType.isLeft) {
                        handleEquip(player, record, verb, vr, gui);
                    } else if (clickType.isRight) {
                        handleAttune(player, record, verb, vr, gui);
                    }
                })
                .build();
    }

    private static void handleEquip(ServerPlayer player, WolfRecord record,
                                    Verbs.Verb verb, WolfRecord.VerbRecord vr, SlotBasedGui gui) {
        String rejection;
        String message;
        ChatFormatting color;
        if (vr.equipped) {
            rejection = Verbs.canUnequip(record, verb);
            if (rejection == null) {
                vr.equipped = false;
                PlayerWolfRegistry.markDirty(player.getUUID());
                message = verb.displayName + " set aside.";
                color = ChatFormatting.GRAY;
            } else {
                message = rejection;
                color = ChatFormatting.RED;
            }
        } else {
            rejection = Verbs.canEquip(record, verb);
            if (rejection == null) {
                vr.equipped = true;
                PlayerWolfRegistry.markDirty(player.getUUID());
                message = verb.displayName + " equipped.";
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

    private static void handleAttune(ServerPlayer player, WolfRecord record,
                                     Verbs.Verb verb, WolfRecord.VerbRecord vr, SlotBasedGui gui) {
        int diamonds = VerbCommands.countDiamonds(player);
        String rejection = Verbs.canAttune(player, record, verb, diamonds);
        if (rejection != null) {
            player.sendSystemMessage(Component.literal(rejection).withStyle(ChatFormatting.RED));
            gui.close();
            open(player);
            return;
        }

        int nextTier = vr.tier + 1;
        int cost = Verbs.ATTUNE_COST[nextTier];
        VerbCommands.removeDiamonds(player, cost);
        vr.attunedTier = nextTier;
        vr.fillKills = 0;
        PlayerWolfRegistry.markDirty(player.getUUID());

        player.sendSystemMessage(Component.literal(
                        "The diamonds crumble. " + verb.displayName + " reaches for Tier "
                                + Verbs.roman(nextTier) + " -- feed it " + verb.familyLabel + ".")
                .withStyle(ChatFormatting.AQUA));
        Chime.verbAttuned(player);

        gui.close();
        open(player);
    }

    private static void buildFooter(SimpleGui gui) {
        gui.setSlot(35, new GuiElementBuilder(Items.BARRIER)
                .setName(Component.literal("Close")
                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                .setCallback((i, t, a, g) -> g.close())
                .build());
    }

    private static Item iconFor(Verbs.Verb verb) {
        if (verb == Verbs.EMBERFANG) return Items.BLAZE_POWDER;
        if (verb == Verbs.VENOMFANG) return Items.SPIDER_EYE;
        if (verb == Verbs.RAVENOUS) return Items.ROTTEN_FLESH;
        if (verb == Verbs.BONECHILL) return Items.BONE;
        if (verb == Verbs.WITHERBITE) return Items.WITHER_SKELETON_SKULL;
        if (verb == Verbs.BLINKSTRIKE) return Items.ENDER_PEARL;
        if (verb == Verbs.SCAVENGER) return Items.CHEST;
        if (verb == Verbs.LIGHT) return Items.TORCH;
        if (verb == Verbs.PREDATOR) return Items.BEEF;
        return Items.PAPER;
    }
}
