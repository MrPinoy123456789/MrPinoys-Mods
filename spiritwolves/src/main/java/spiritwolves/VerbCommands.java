package spiritwolves;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The {@code /spiritwolves verbs} panel and its hidden equip/unequip/attune
 * subcommands (SPEC.md section 18.3). The panel is now a server-side chest GUI
 * via sgui; the chat subcommands still work and refresh the GUI.
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
        VerbGui.open(player);
        return 1;
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
