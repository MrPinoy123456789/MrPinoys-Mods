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
 * {@code /spiritwolves fangs} and {@code /spiritwolves tricks}, plus the hidden
 * equip/unequip/attune subcommands (SPEC.md section 18.3).
 *
 * <p>Both literals open the same panel -- it shows Fangs and Tricks together,
 * because the interesting question is how the two rows trade against each other.
 * Two names for one screen simply means whichever word a player reaches for
 * works. The panel is a server-side chest GUI ({@link AbilityGui}); the chat
 * subcommands still work and refresh it.
 */
final class AbilityCommands {

    private AbilityCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> buildFangs() {
        return build("fangs");
    }

    static LiteralArgumentBuilder<CommandSourceStack> buildTricks() {
        return build("tricks");
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(String literal) {
        return Commands.literal(literal)
                .executes(ctx -> panel(ctx.getSource().getPlayerOrException()))
                .then(Commands.literal("equip")
                        .then(Commands.argument("ability", StringArgumentType.word())
                                .executes(ctx -> equip(ctx.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(ctx, "ability")))))
                .then(Commands.literal("unequip")
                        .then(Commands.argument("ability", StringArgumentType.word())
                                .executes(ctx -> unequip(ctx.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(ctx, "ability")))))
                .then(Commands.literal("attune")
                        .then(Commands.argument("ability", StringArgumentType.word())
                                .executes(ctx -> attune(ctx.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(ctx, "ability")))));
    }

    // ---- subcommands ----------------------------------------------------------

    private static int equip(ServerPlayer player, String abilityId) {
        Abilities.Ability ability = Abilities.byId(abilityId);
        WolfRecord record = PlayerWolfRegistry.get(player.getUUID());
        if (ability == null || record == null) {
            player.sendSystemMessage(Component.literal("Unknown ability.").withStyle(ChatFormatting.RED));
            return 0;
        }
        String rejection = Abilities.canEquip(record, ability);
        if (rejection != null) {
            player.sendSystemMessage(Component.literal(rejection).withStyle(ChatFormatting.RED));
            panel(player);
            return 0;
        }
        record.ability(ability.id).equipped = true;
        PlayerWolfRegistry.markDirty(player.getUUID());
        player.sendSystemMessage(Component.literal(ability.displayName + " equipped.")
                .withStyle(ChatFormatting.GREEN));
        panel(player);
        return 1;
    }

    private static int unequip(ServerPlayer player, String abilityId) {
        Abilities.Ability ability = Abilities.byId(abilityId);
        WolfRecord record = PlayerWolfRegistry.get(player.getUUID());
        if (ability == null || record == null) {
            player.sendSystemMessage(Component.literal("Unknown ability.").withStyle(ChatFormatting.RED));
            return 0;
        }
        String rejection = Abilities.canUnequip(record, ability);
        if (rejection != null) {
            player.sendSystemMessage(Component.literal(rejection).withStyle(ChatFormatting.RED));
            panel(player);
            return 0;
        }
        record.ability(ability.id).equipped = false;
        PlayerWolfRegistry.markDirty(player.getUUID());
        player.sendSystemMessage(Component.literal(ability.displayName + " set aside.")
                .withStyle(ChatFormatting.GRAY));
        panel(player);
        return 1;
    }

    private static int attune(ServerPlayer player, String abilityId) {
        Abilities.Ability ability = Abilities.byId(abilityId);
        WolfRecord record = PlayerWolfRegistry.get(player.getUUID());
        if (ability == null || record == null) {
            player.sendSystemMessage(Component.literal("Unknown ability.").withStyle(ChatFormatting.RED));
            return 0;
        }
        int diamonds = countDiamonds(player);
        String rejection = Abilities.canAttune(player, record, ability, diamonds);
        if (rejection != null) {
            player.sendSystemMessage(Component.literal(rejection).withStyle(ChatFormatting.RED));
            panel(player);
            return 0;
        }

        WolfRecord.AbilityRecord ar = record.ability(ability.id);
        int nextTier = ar.tier + 1;
        removeDiamonds(player, Abilities.ATTUNE_COST[nextTier]);
        ar.attunedTier = nextTier;
        ar.fillKills = 0;
        PlayerWolfRegistry.markDirty(player.getUUID());

        player.sendSystemMessage(attuneMessage(ability, nextTier));
        Chime.abilityAttuned(player);
        panel(player);
        return 1;
    }

    /**
     * A fang is fed a mob family; a trick is trained. Same mechanic, but telling
     * a player to "feed it ores mined nearby" reads like nonsense, so the two
     * categories get their own sentence.
     */
    static Component attuneMessage(Abilities.Ability ability, int nextTier) {
        String tail = ability.category == Abilities.Category.FANG
                ? " -- feed it " + ability.progressLabel + "."
                : " -- keep working at it: " + ability.progressLabel + ".";
        return Component.literal("The diamonds crumble. " + ability.displayName
                        + " reaches for Tier " + Abilities.roman(nextTier) + tail)
                .withStyle(ChatFormatting.AQUA);
    }

    // ---- panel ------------------------------------------------------------

    private static int panel(ServerPlayer player) {
        AbilityGui.open(player);
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
