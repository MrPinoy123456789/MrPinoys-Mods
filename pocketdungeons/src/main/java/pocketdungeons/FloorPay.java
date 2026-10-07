package pocketdungeons;

/**
 * What one cleared floor pays one member (design B, J1). Scrap is experience:
 * a member at or below the floor's level is paid its dealt step in scrap.
 * A member whose highest chart level already stands above the floor is paid
 * emeralds instead, {@code step * overlevelEmeraldsPerScrap}, so replaying an
 * easy dungeon buys things but never inflates the compass.
 *
 * <p>The read is {@code memberHighestCharts}, the permanent high
 * ({@link ScrapMath}), not the current chart level: spending scrap on a
 * side branch must never re-open cheap scrap on low floors.
 *
 * <p>No Minecraft imports, same discipline as {@link KeystoneMath}.
 */
final class FloorPay {

    private FloorPay() {}

    /** One member's take from a cleared floor. Exactly one side is nonzero. */
    record Payout(int scrap, int emeralds) {

        /** {@code "+2 scrap"} or {@code "+4 emeralds, you are above this floor"}. */
        String line() {
            if (scrap > 0) {
                return "+" + scrap + " scrap";
            }
            return "+" + emeralds + " emeralds, you are above this floor";
        }
    }

    /**
     * The pay for a floor dealt {@code step} at {@code floorLevel} for a member
     * whose permanent chart level is {@code memberHighestCharts}.
     */
    static Payout of(int step, int floorLevel, int memberHighestCharts, int overlevelEmeraldsPerScrap) {
        int dealt = Math.max(0, step);
        if (memberHighestCharts <= floorLevel) {
            return new Payout(dealt, 0);
        }
        return new Payout(0, dealt * Math.max(0, overlevelEmeraldsPerScrap));
    }
}
