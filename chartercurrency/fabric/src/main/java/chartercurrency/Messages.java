package chartercurrency;

import chartercurrency.core.Currency;
import chartercurrency.core.Wallet;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Native text components, plain wording, no emoji in the body copy.
 *
 * <p>Centralised so the mod has one voice. The target is a feature that could
 * plausibly have shipped in vanilla -- a player should not be able to tell which of
 * the server's messages come from a mod.
 *
 * <p>Currencies are colour-coded, and that is the only decoration: coin gold, Valor
 * purple. It reads at a glance in a busy chat and costs no extra words.
 */
public final class Messages {

    public static final ChatFormatting BODY = ChatFormatting.GRAY;
    public static final ChatFormatting HEADING = ChatFormatting.YELLOW;
    public static final ChatFormatting GOOD = ChatFormatting.GREEN;
    public static final ChatFormatting BAD = ChatFormatting.RED;
    public static final ChatFormatting RULE = ChatFormatting.DARK_GRAY;
    public static final ChatFormatting YOU = ChatFormatting.YELLOW;

    private static final String LINE = "\u2501".repeat(24);

    private Messages() {}

    /** Coin reads as gold, Valor as purple. Anything new gets white. */
    public static ChatFormatting colourOf(Currency currency) {
        return switch (currency.id()) {
            case "coin" -> ChatFormatting.GOLD;
            case "valor" -> ChatFormatting.LIGHT_PURPLE;
            default -> ChatFormatting.WHITE;
        };
    }

    /**
     * A formatted amount in its currency's colour: {@code 12,481} Valor, and coin in its
     * words, {@code 2,261.5 Sovereigns}, so a bare coin number never reads as Marks.
     */
    public static MutableComponent amount(Currency currency, long value) {
        String text = currency.hasDenominations() ? currency.describe(value) : Wallet.format(value);
        return Component.literal(text).withStyle(colourOf(currency));
    }

    /**
     * The number alone, for columns: {@code 12,481} Valor, and coin as Sovereigns,
     * {@code 2,261.5} (owner, 2026-11-04). The column's heading names the unit.
     */
    public static String number(Currency currency, long value) {
        if (!currency.hasDenominations()) return Wallet.format(value);
        return Wallet.inUnits(value, currency.largestFirst().get(0).value());
    }

    /** {@code Coin (Sovereigns)}: a heading over a column of {@link #number}s. */
    public static String heading(Currency currency) {
        if (!currency.hasDenominations()) return currency.displayName();
        return currency.displayName() + " (" + currency.largestFirst().get(0).shortPlural() + ")";
    }

    /** {@code 640 cobblestone}, coloured. */
    public static MutableComponent priced(Currency currency, long value) {
        return Component.literal(currency.describe(value)).withStyle(colourOf(currency));
    }

    public static MutableComponent body(String text) {
        return Component.literal(text).withStyle(BODY);
    }

    public static MutableComponent good(String text) {
        return Component.literal(text).withStyle(GOOD);
    }

    public static MutableComponent bad(String text) {
        return Component.literal(text).withStyle(BAD);
    }

    public static MutableComponent rule() {
        return Component.literal(LINE).withStyle(RULE);
    }

    public static MutableComponent title(String text) {
        return Component.literal(text).withStyle(HEADING, ChatFormatting.BOLD);
    }

    public static MutableComponent blank() {
        return Component.empty();
    }

    /** {@code Cobblestone: 12,481} */
    public static MutableComponent balanceLine(Currency currency, long balance) {
        return body(currency.displayName() + ": ").append(priced(currency, balance));
    }

    /** A command and what it does, for help panels. */
    public static MutableComponent helpLine(String command, String description) {
        return Component.literal("  " + command).withStyle(ChatFormatting.WHITE)
                .append(Component.literal("  " + description).withStyle(RULE));
    }

    /**
     * One leaderboard row: {@code  1. Steve          2,261.5}, coin in Sovereigns under a
     * {@link #heading} that says so.
     *
     * <p>The viewer's own row is marked with a trailing arrow rather than a different
     * colour, because a coloured row in a list of coloured amounts is noise. Section
     * 10 of the leaderboard spec asks for subtle, and an arrow is subtle.
     */
    public static MutableComponent rankLine(int rank, String name, Currency currency,
                                            long balance, boolean isViewer) {
        MutableComponent line = Component.literal(String.format("%2d. ", rank)).withStyle(RULE)
                .append(Component.literal(padRight(name, 17))
                        .withStyle(isViewer ? YOU : ChatFormatting.WHITE))
                .append(Component.literal(padLeft(number(currency, balance), 10))
                        .withStyle(colourOf(currency)));
        if (isViewer) {
            line.append(Component.literal("  \u2190 You").withStyle(YOU));
        }
        return line;
    }

    static String padRight(String text, int width) {
        if (text.length() >= width) return text.substring(0, width - 1) + " ";
        return text + " ".repeat(width - text.length());
    }

    static String padLeft(String text, int width) {
        if (text.length() >= width) return text;
        return " ".repeat(width - text.length()) + text;
    }
}
