package spiritwolves;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The {@code /spiritwolves verbs} panel and its hidden equip/unequip/attune
 * subcommands (SPEC.md section 18.3). All UX is chat {@code Component}s with
 * click/hover events -- no container screen. Every mutating subcommand
 * re-prints the panel so it always reflects the true, re-validated state.
 */
final class VerbCommands {

    private VerbCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("verbs")
                .executes(ctx -> panel(ctx.getSource().getPlayerOrException()))
                .then(Commands.literal("equip")
                        .then(Commands.argument("verb", StringArgumentType.word())
                                .executes(ctx -> equip(ctx.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(ctx, "verb")))))
                .then(Commands.literal("unequip")
                        .then(Commands.argument("verb", StringArgumentType.word())
                                .executes(ctx -> unequip(ctx.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(ctx, "verb")))))
                .then(Commands.literal("attune")
                        .then(Commands.argument("verb", StringArgumentType.word())
                                .executes(ctx -> attune(ctx.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(ctx, "verb")))));
    }

    // ---- subcommands ----------------------------------------------------------

    private static int equip(ServerPlayer player, String verbId) {
        Verbs.Verb verb = Verbs.byId(verbId);
        WolfRecord record = PlayerWolfRegistry.get(player.getUUID());
        if (verb == null || record == null) {
            player.sendSystemMessage(Component.literal("Unknown verb.").withStyle(ChatFormatting.RED));
            return 0;
        }
        String rejection = Verbs.canEquip(record, verb);
        if (rejection != null) {
            player.sendSystemMessage(Component.literal(rejection).withStyle(ChatFormatting.RED));
            panel(player);
            return 0;
        }
        record.verb(verb.id).equipped = true;
        PlayerWolfRegistry.markDirty(player.getUUID());
        player.sendSystemMessage(Component.literal(verb.displayName + " equipped.")
                .withStyle(ChatFormatting.GREEN));
        panel(player);
        return 1;
    }

    private static int unequip(ServerPlayer player, String verbId) {
        Verbs.Verb verb = Verbs.byId(verbId);
        WolfRecord record = PlayerWolfRegistry.get(player.getUUID());
        if (verb == null || record == null) {
            player.sendSystemMessage(Component.literal("Unknown verb.").withStyle(ChatFormatting.RED));
            return 0;
        }
        String rejection = Verbs.canUnequip(record, verb);
        if (rejection != null) {
            player.sendSystemMessage(Component.literal(rejection).withStyle(ChatFormatting.RED));
            panel(player);
            return 0;
        }
        record.verb(verb.id).equipped = false;
        PlayerWolfRegistry.markDirty(player.getUUID());
        player.sendSystemMessage(Component.literal(verb.displayName + " set aside.")
                .withStyle(ChatFormatting.GRAY));
        panel(player);
        return 1;
    }

    private static int attune(ServerPlayer player, String verbId) {
        Verbs.Verb verb = Verbs.byId(verbId);
        WolfRecord record = PlayerWolfRegistry.get(player.getUUID());
        if (verb == null || record == null) {
            player.sendSystemMessage(Component.literal("Unknown verb.").withStyle(ChatFormatting.RED));
            return 0;
        }
        int diamonds = countDiamonds(player);
        String rejection = Verbs.canAttune(player, record, verb, diamonds);
        if (rejection != null) {
            player.sendSystemMessage(Component.literal(rejection).withStyle(ChatFormatting.RED));
            panel(player);
            return 0;
        }

        WolfRecord.VerbRecord vr = record.verb(verb.id);
        int nextTier = vr.tier + 1;
        int cost = Verbs.ATTUNE_COST[nextTier];
        removeDiamonds(player, cost);
        vr.attunedTier = nextTier;
        vr.fillKills = 0;
        PlayerWolfRegistry.markDirty(player.getUUID());

        player.sendSystemMessage(Component.literal(
                        "The diamonds crumble. " + verb.displayName + " reaches for Tier "
                                + Verbs.roman(nextTier) + " -- feed it " + verb.familyLabel + ".")
                .withStyle(ChatFormatting.AQUA));
        Chime.verbAttuned(player);
        panel(player);
        return 1;
    }

    // ---- panel ------------------------------------------------------------

    private static int panel(ServerPlayer player) {
        WolfRecord record = PlayerWolfRegistry.get(player.getUUID());
        if (record == null) {
            player.sendSystemMessage(Component.literal("You have no spirit wolf.")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }

        String name = record.wolfName != null ? record.wolfName : "Your wolf";
        int level = Souls.levelFor(record.souls);
        int slots = Souls.slotsFor(level);
        int equippedCount = 0;
        for (WolfRecord.VerbRecord vr : record.verbs.values()) {
            if (vr.equipped) {
                equippedCount++;
            }
        }

        player.sendSystemMessage(Component.literal(
                        "─── " + name + " — Level " + level + " · "
                                + record.souls + " souls · " + slots + " slots ───")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        int diamonds = countDiamonds(player);
        for (Verbs.Verb verb : Verbs.ALL) {
            renderRow(player, record, verb, diamonds);
        }

        if (equippedCount >= slots) {
            player.sendSystemMessage(Component.literal("Slots " + equippedCount + "/" + slots
                            + " — unequip a verb to swap.")
                    .withStyle(ChatFormatting.GRAY));
        }
        return 1;
    }

    private static void renderRow(ServerPlayer player, WolfRecord record, Verbs.Verb verb, int diamonds) {
        Verbs.State state = Verbs.stateOf(record, verb);
        if (state == Verbs.State.HIDDEN) {
            return;
        }

        WolfRecord.VerbRecord vr = record.verbs.get(verb.id);
        int kills = record.familyKills.getOrDefault(verb.id, 0);

        if (state == Verbs.State.SCENTED) {
            player.sendSystemMessage(Component.literal(
                            "?  ???  — something stirs (" + kills + "/" + Verbs.UNLOCK_KILLS + ")")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }

        boolean equipped = vr.equipped;
        MutableComponent row = Component.literal(equipped ? "◆ " : "◇ ")
                .withStyle(equipped ? ChatFormatting.AQUA : ChatFormatting.WHITE);
        row.append(Component.literal(verb.displayName + " " + Verbs.roman(vr.tier) + "   ")
                .withStyle(style -> style
                        .withHoverEvent(new HoverEvent.ShowText(tierHover(verb, vr.tier)))));

        if (record.summoned) {
            row.append(Component.literal("[recall to change]").withStyle(style -> style
                    .withColor(ChatFormatting.DARK_GRAY)
                    .withHoverEvent(new HoverEvent.ShowText(
                            Component.literal("Recall your wolf to change its verbs.")))));
        } else if (equipped) {
            row.append(Component.literal("[unequip]").withStyle(style -> style
                    .withColor(ChatFormatting.YELLOW)
                    .withClickEvent(new ClickEvent.RunCommand("/spiritwolves verbs unequip " + verb.id))
                    .withHoverEvent(new HoverEvent.ShowText(
                            Component.literal("Unequip this verb.")))));
        } else {
            row.append(Component.literal("[equip]").withStyle(style -> style
                    .withColor(ChatFormatting.GREEN)
                    .withClickEvent(new ClickEvent.RunCommand("/spiritwolves verbs equip " + verb.id))
                    .withHoverEvent(new HoverEvent.ShowText(
                            Component.literal(verb.tierEffect[Math.max(1, vr.tier)])))));
        }

        if (vr.tier < 3) {
            int nextTier = vr.tier + 1;
            row.append(Component.literal("   ▸ " + Verbs.roman(nextTier) + ": "));
            if (state == Verbs.State.ATTUNED) {
                int requirement = Verbs.FILL_REQUIREMENT[vr.attunedTier];
                row.append(Component.literal(vr.fillKills + "/" + requirement + " " + verb.familyLabel + " kills")
                        .withStyle(ChatFormatting.GRAY));
            } else {
                String rejection = Verbs.canAttune(player, record, verb, diamonds);
                int cost = Verbs.ATTUNE_COST[nextTier];
                if (rejection == null) {
                    row.append(Component.literal("attune " + cost + "◆").withStyle(style -> style
                            .withColor(ChatFormatting.AQUA)
                            .withClickEvent(new ClickEvent.RunCommand("/spiritwolves verbs attune " + verb.id))));
                } else {
                    row.append(Component.literal("attune " + cost + "◆").withStyle(style -> style
                            .withColor(ChatFormatting.DARK_GRAY)
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal(rejection)))));
                }
            }
        }

        player.sendSystemMessage(row);

        if (state == Verbs.State.ATTUNED) {
            int requirement = Verbs.FILL_REQUIREMENT[vr.attunedTier];
            player.sendSystemMessage(Component.literal(
                            "     " + vr.fillKills + "/" + requirement + " " + verb.familyLabel
                                    + " kills toward Tier " + Verbs.roman(vr.attunedTier))
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    private static Component tierHover(Verbs.Verb verb, int currentTier) {
        MutableComponent hover = Component.literal("");
        for (int tier = 1; tier <= 3; tier++) {
            ChatFormatting color = tier == currentTier ? ChatFormatting.AQUA
                    : (tier <= currentTier ? ChatFormatting.GRAY : ChatFormatting.DARK_GRAY);
            if (tier > 1) {
                hover.append(Component.literal("\n"));
            }
            hover.append(Component.literal(Verbs.roman(tier) + ": " + verb.tierEffect[tier]).withStyle(color));
        }
        return hover;
    }

    // ---- diamond inventory helpers ------------------------------------------

    /** Counts diamonds across the player's entire inventory, including the offhand. */
    static int countDiamonds(ServerPlayer player) {
        Inventory inv = player.getInventory();
        int count = 0;
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.is(Items.DIAMOND)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** Shrinks diamond stacks in place across the whole inventory until {@code amount} is removed. */
    static void removeDiamonds(ServerPlayer player, int amount) {
        Inventory inv = player.getInventory();
        int remaining = amount;
        for (int slot = 0; slot < inv.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (!stack.is(Items.DIAMOND)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
        }
    }
}
