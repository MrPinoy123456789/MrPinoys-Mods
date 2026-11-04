package charter.api;

/**
 * How Charter tells a coin amount to a player: always in Gold Sovereigns (owner, 2026-11-04).
 * Marks stay the unit in code, knobs and files; a Sovereign is 64 of them. The same rule as
 * Charter Currency's {@code Wallet.sovereigns}, kept here because Charter runs without it.
 *
 * <p>Whole Sovereigns carry group separators, {@code 2,261}; a remainder is a decimal of at
 * most two places, {@code 2,261.5}, cut and never rounded, so an amount never reads as more
 * than it is (63 Marks read {@code 0.98}, 1 Mark {@code 0.01}).
 */
public final class Coin {

    /** Marks in one Gold Sovereign. */
    public static final long MARKS_PER_SOVEREIGN = 64;

    private Coin() {
    }

    /** The number alone: {@code 2,261.5}. */
    public static String sovereigns(long marks) {
        long abs = marks == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(marks);
        long whole = abs / MARKS_PER_SOVEREIGN;
        long hundredths = (abs % MARKS_PER_SOVEREIGN) * 100 / MARKS_PER_SOVEREIGN;
        String out = group(whole);
        if (hundredths > 0) {
            String frac = hundredths < 10 ? "0" + hundredths : Long.toString(hundredths);
            if (frac.endsWith("0")) frac = frac.substring(0, 1);
            out += "." + frac;
        }
        return marks < 0 ? "-" + out : out;
    }

    /** With the word: {@code 1 Sovereign}, {@code 0.5 Sovereigns}, {@code 2,261 Sovereigns}. */
    public static String words(long marks) {
        return sovereigns(marks) + (marks == MARKS_PER_SOVEREIGN ? " Sovereign" : " Sovereigns");
    }

    /** Whole Sovereigns typed by a player, as Marks; saturates rather than wrapping. */
    public static long marks(long sovereigns) {
        if (sovereigns > Long.MAX_VALUE / MARKS_PER_SOVEREIGN) return Long.MAX_VALUE;
        if (sovereigns < Long.MIN_VALUE / MARKS_PER_SOVEREIGN) return Long.MIN_VALUE;
        return sovereigns * MARKS_PER_SOVEREIGN;
    }

    private static String group(long n) {
        String digits = Long.toString(n);
        StringBuilder out = new StringBuilder();
        int lead = digits.length() % 3;
        if (lead == 0) lead = 3;
        out.append(digits, 0, lead);
        for (int i = lead; i < digits.length(); i += 3) out.append(',').append(digits, i, i + 3);
        return out.toString();
    }
}
