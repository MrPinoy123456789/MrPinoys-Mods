package pocketdungeons;

import java.util.HashMap;
import java.util.Map;

/**
 * T50: the maths of the extra copper chest piece a wagered floor pays. Slot even, tier chance per wager point capped,
 * never above the loot band, wager 0 pays nothing, same seed gives the same pick.
 */
public class WagerGearTest {

    public static void main(String[] args) {
        chance();
        slots();
        tiers();
        picks();
        System.out.println("WagerGearTest passed");
    }

    private static void chance() {
        check(WagerGear.tierChancePercent(0, 20, 90) == 0, "no wager, no chance");
        check(WagerGear.tierChancePercent(1, 20, 90) == 20, "20 percent for a point");
        check(WagerGear.tierChancePercent(3, 20, 90) == 60, "60 percent for three");
        check(WagerGear.tierChancePercent(4, 20, 90) == 80, "80 percent for four");
        check(WagerGear.tierChancePercent(5, 20, 90) == 90, "capped at 90");
        check(WagerGear.tierChancePercent(10, 20, 90) == 90, "still capped at ten");
        check(WagerGear.tierChancePercent(5, 20, 100) == 100, "the cap can be 100");
        check(WagerGear.tierChancePercent(5, 20, 500) == 100, "never above 100");
        check(WagerGear.tierChancePercent(5, 0, 90) == 0, "a zero per point knob is no chance");
        check(WagerGear.tierChancePercent(5, 20, 0) == 0, "a zero cap is no chance");
        check(WagerGear.tierChancePercent(-2, 20, 90) == 0, "a negative wager is no chance");
    }

    private static void slots() {
        Map<String, Integer> counts = new HashMap<>();
        for (long seed = 0; seed < 8000; seed++) {
            counts.merge(WagerGear.slot(seed * 7919L + 13), 1, Integer::sum);
        }
        check(counts.keySet().equals(new java.util.HashSet<>(WagerGear.SLOTS)), "every slot comes up");
        for (String slot : WagerGear.SLOTS) {
            int n = counts.get(slot);
            check(n > 1700 && n < 2300, slot + " is picked evenly, got " + n + " of 8000");
        }
        check(WagerGear.slot(42).equals(WagerGear.slot(42)), "the same seed picks the same slot");
        check(WagerGear.SLOTS.contains("material") && WagerGear.SLOTS.contains("weapon")
                && WagerGear.SLOTS.contains("tool") && WagerGear.SLOTS.contains("armour"), "the four slots");
    }

    private static int ups(int wager, int base, int min, int max, int perPoint, int cap) {
        int up = 0;
        for (long seed = 0; seed < 5000; seed++) {
            int tier = WagerGear.tier(seed * 104729L + 1, base, min, max, wager, perPoint, cap);
            check(tier >= min && tier <= max, "tier " + tier + " outside the band " + min + " to " + max);
            check(tier == base || tier == base + 1 || tier == max || tier == min, "at most one tier up");
            if (tier > Math.max(min, Math.min(max, base))) {
                up++;
            }
        }
        return up;
    }

    private static void tiers() {
        check(ups(1, 1, 1, 2, 20, 90) > 800 && ups(1, 1, 1, 2, 20, 90) < 1200, "wager 1 goes up about a fifth of the time");
        int w3 = ups(3, 1, 1, 2, 20, 90);
        check(w3 > 2800 && w3 < 3200, "wager 3 goes up about 60 percent, got " + w3);
        int w6 = ups(6, 1, 1, 2, 20, 90);
        check(w6 > 4300 && w6 < 4700, "wager 6 is capped near 90 percent, got " + w6);
        check(ups(0, 1, 1, 2, 20, 90) == 0, "wager 0 never goes up");
        check(ups(8, 2, 2, 2, 20, 90) == 0, "a band of one tier never goes up");
        check(ups(8, 3, 2, 3, 20, 90) == 0, "never above the band top");
        check(WagerGear.tier(5, 4, 1, 2, 0, 20, 90) == 2, "a base above the band is clamped down");
        check(WagerGear.tier(5, 1, 3, 4, 0, 20, 90) == 3, "a base below the band is clamped up");
        check(WagerGear.tier(77, 2, 1, 4, 5, 20, 90) == WagerGear.tier(77, 2, 1, 4, 5, 20, 90), "deterministic");
        check(ups(5, 1, 1, 4, 20, 100) == 5000, "a hundred percent always goes up");
    }

    private static void picks() {
        check(WagerGear.pick(9, 1, 1, 2, 0, 20, 90) == null, "wager 0 pays nothing extra");
        WagerGear.Pick a = WagerGear.pick(9, 1, 1, 2, 4, 20, 90);
        WagerGear.Pick b = WagerGear.pick(9, 1, 1, 2, 4, 20, 90);
        check(a != null && a.equals(b), "the same seed, the same piece: the board and the chest agree");
        check(WagerGear.SLOTS.contains(a.slot()) && a.tier() >= 1 && a.tier() <= 2, "a real slot and a tier in the band");
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
