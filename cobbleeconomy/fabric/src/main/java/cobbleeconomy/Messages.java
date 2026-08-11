package cobbleeconomy;

import cobbleeconomy.core.Currency;
import cobbleeconomy.core.Wallet;
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
 * <p>Currencies are colour-coded, and that is the only decoration: cobblestone grey,
 * diamonds aqua. It reads at a glance in a busy chat and costs no extra words.
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

    /** Cobblestone reads as stone, diamonds as diamond. Anything new gets gold. */
    public static ChatFormatting colourOf(Currency currency) {
        return switch (currency.id()) {
            case "cobblestone" -> ChatFormatting.WHITE;
            case "diamond" -> ChatFormatting.AQUA;
            default -> ChatFormatting.GOLD;
        };
    }

    /** A formatted amount in its currency's colour, e.g. {@code 12,481}. */
    public static MutableComponent amount(Currency currency, long value) {
        return Component.literal(Wallet.format(value)).withStyle(colourOf(currency));
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
        return body(currency.displayName() + ": ").append(amount(currency, balance));
    }

    /** A command and what it does, for help panels. */
    public static MutableComponent helpLine(String command, String description) {
        return Component.literal("  " + command).withStyle(ChatFormatting.WHITE)
                .append(Component.literal("  " + description).withStyle(RULE));
    }

    /**
     * One leaderboard row: {@code  1. Steve          82,410}.
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
                .append(Component.literal(padLeft(Wallet.format(balance), 10))
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
