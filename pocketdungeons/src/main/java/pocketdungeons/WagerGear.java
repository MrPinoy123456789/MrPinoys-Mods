package pocketdungeons;

import java.util.List;
import java.util.Random;

/**
 * The extra piece a wagered floor pays into its copper chest (docs/wager-draft-2026-10-12.md, owner clarification
 * of 2026-10-12). Pure maths, no Minecraft imports; {@link PromisedGear#rollExtra} turns the pick into a stack.
 *
 * <p>The slot is picked evenly from {@link #SLOTS}. The tier starts at the floor's base tier (the node's own
 * promised gear tier, else the dungeon's loot band floor) and rises one tier with a chance of
 * {@code min(cap, perPoint x wager)} percent, never above the dungeon's loot band (D10). Wager 0 pays nothing
 * extra. The same seed gives the same pick, so the door board and the chest agree.
 */
final class WagerGear {

    /** The gear slots an extra piece is drawn from; each has a table per tier 1 to 4 ({@link LootTables#gearTable}). */
    static final List<String> SLOTS = List.of("weapon", "tool", "armour", "material");

    /** One pick: a slot and a tier. */
    record Pick(String slot, int tier) {}

    private WagerGear() {}

    /** The chance in percent that the piece is one tier above the base: {@code min(cap, perPoint x wager)}, 0 to 100. */
    static int tierChancePercent(int wager, int perPoint, int cap) {
        if (wager <= 0 || perPoint <= 0) {
            return 0;
        }
        long raw = (long) wager * perPoint;
        return (int) Math.max(0, Math.min(Math.min(100, cap), raw));
    }

    /** The slot for {@code seed}: even over {@link #SLOTS}. */
    static String slot(long seed) {
        return SLOTS.get(new Random(seed ^ 0x5107L).nextInt(SLOTS.size()));
    }

    /**
     * The tier for {@code seed}: {@code baseTier} clamped into the band, plus one with the wager's chance, clamped
     * into the band again.
     */
    static int tier(long seed, int baseTier, int bandMin, int bandMax, int wager, int perPoint, int cap) {
        int base = Math.max(bandMin, Math.min(bandMax, baseTier));
        int chance = tierChancePercent(wager, perPoint, cap);
        boolean up = new Random(seed ^ 0x7131L).nextInt(100) < chance;
        return Math.min(bandMax, up ? base + 1 : base);
    }

    /** The whole pick, or {@code null} when the wager is 0 (nothing extra is paid). */
    static Pick pick(long seed, int baseTier, int bandMin, int bandMax, int wager, int perPoint, int cap) {
        if (wager <= 0) {
            return null;
        }
        return new Pick(slot(seed), tier(seed, baseTier, bandMin, bandMax, wager, perPoint, cap));
    }
}
