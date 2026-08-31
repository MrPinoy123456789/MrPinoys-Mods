package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared lore-line formatting for {@link TrimListener}'s material bonuses and
 * {@link PowerListener}'s imbued-power bonuses: the same "show the player what
 * this item does" need, the same idempotent set-onto-LORE shape. A utility
 * rather than per-listener duplication, since the formatting (amount, operation,
 * attribute name) is identical and would drift if copied.
 *
 * <h2>Why LORE and not a tooltip event</h2>
 *
 * <p>This mod is server-side only (see {@link PocketDungeonsMod}'s class note),
 * so there is no client-side {@code ItemTooltipCallback} to register. LORE is
 * the one component vanilla syncs to the client and renders in the hover
 * tooltip, so baking the bonus line into {@link DataComponents#LORE} is how a
 * player sees it without any client-side code. The same shape {@link Keystone}
 * already uses for affix blurbs.
 *
 * <h2>Idempotent, not one-shot</h2>
 *
 * <p>Called from the same per-tick reconciliation pass that applies the
 * attribute modifier, so the line self-heals if an item loses it (a trim
 * re-applied with a different material, a power re-imbued). The check is a
 * plain-string prefix match: if the exact line is already present the stack
 * is not touched, avoiding unnecessary sync traffic on every scan.
 */
final class BonusLore {

    private BonusLore() {}

    /**
     * Idempotently ensures {@code stack}'s LORE carries exactly one line
     * starting with {@code prefix}, replacing any prior line with the same
     * prefix and preserving every other lore line. If the exact line is
     * already present, the stack is not modified.
     */
    static void ensure(ItemStack stack, String prefix, Component line) {
        ItemLore lore = stack.get(DataComponents.LORE);
        List<Component> lines = lore == null ? new ArrayList<>() : new ArrayList<>(lore.lines());
        String lineString = line.getString();
        for (Component existing : lines) {
            if (existing.getString().equals(lineString)) {
                return;
            }
        }
        lines.removeIf(c -> c.getString().startsWith(prefix));
        lines.add(line);
        stack.set(DataComponents.LORE, new ItemLore(lines));
    }

    /**
     * Formats one bonus as a lore line: "+1 Armor Toughness",
     * "-0.1 Fall Damage Multiplier", "+10% Movement Speed (base)".
     * Grey and italic, matching {@link Keystone}'s affix-blurb style.
     *
     * @param attributeKey the attribute's registry id, humanised from its path
     *                     (e.g. {@code minecraft:armor_toughness} to "Armor Toughness")
     * @param dungeonOnly if true, appends "(dungeon only)" so a player knows
     *                    the bonus does not apply outside the dungeon dimension
     */
    static Component line(String prefix, double amount,
                          AttributeModifier.Operation operation, Identifier attributeKey,
                          boolean dungeonOnly) {
        String formatted = formatBonus(amount, operation);
        String attrName = humanize(attributeKey.getPath());
        String text = prefix + " " + formatted + " " + attrName;
        if (dungeonOnly) {
            text += " (dungeon only)";
        }
        return Component.literal(text)
                .withStyle(ChatFormatting.GRAY)
                .withStyle(s -> s.withItalic(true));
    }

    private static String formatBonus(double amount, AttributeModifier.Operation operation) {
        String sign = amount >= 0 ? "+" : "";
        if (operation == AttributeModifier.Operation.ADD_VALUE) {
            return sign + formatAmount(amount);
        }
        int percent = (int) Math.round(amount * 100);
        String suffix = operation == AttributeModifier.Operation.ADD_MULTIPLIED_BASE
                ? " (base)" : " (total)";
        return sign + percent + "%" + suffix;
    }

    private static String formatAmount(double amount) {
        if (amount == Math.floor(amount)) {
            return String.valueOf((long) amount);
        }
        return String.valueOf(amount);
    }

    private static String humanize(String path) {
        String[] words = path.split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }
}
