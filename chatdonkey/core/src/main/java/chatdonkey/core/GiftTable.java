package chatdonkey.core;

import java.util.Random;

/**
 * Turns an {@link EndReason} plus a hit count into a {@link Gift}
 * (SPEC.md section 5).
 *
 * <p>M1 exercises exactly one path -- {@link EndReason#WAITED} with zero hits,
 * giving 2-8 cobblestone. The hit downgrade and the 2% golden roll are written
 * here because they are pure arithmetic and cheap to test now, but nothing in
 * M1 can produce a hit count above zero: hit reactions are M2.
 */
public final class GiftTable {

    /** Three hits or more drops the gift to the grudge tier (SPEC.md section 4). */
    public static final int HITS_FOR_GRUDGE = 3;

    /** The rare upgrade roll on any tier above grudge (SPEC.md section 5). */
    public static final double GOLDEN_CHANCE = 0.02;

    private GiftTable() {}

    /**
     * Worst to best. Being rude moves the player one step down this ladder.
     *
     * <p>SPEC.md is not quite self-consistent here: section 4 says three hits
     * "downgrades the gift one tier", while section 5's table says the grudge
     * tier is simply what you get for hitting it three times. The ladder
     * satisfies both -- a downgraded {@code STANDARD} <em>is</em> {@code GRUDGE},
     * which is section 5's row exactly, and it also gives the kinder, more
     * sensible answer for a player who paid a diamond and then got shirty
     * ({@code GRACIOUS} to {@code STANDARD}, rather than losing everything).
     */
    private static final GiftTier[] LADDER = {
            GiftTier.GRUDGE, GiftTier.STANDARD, GiftTier.GRACIOUS,
            GiftTier.SATISFIED, GiftTier.GOLDEN
    };

    public static Gift select(EndReason reason, int hitCount, Random random) {
        return select(reason, hitCount, random, GiftSettings.defaults());
    }

    public static Gift select(EndReason reason, int hitCount, Random random, GiftSettings gifts) {
        if (!reason.givesGift()) {
            return new Gift(GiftTier.GRUDGE, 0);
        }

        GiftTier tier = switch (reason) {
            case WAITED -> GiftTier.STANDARD;
            case BRIBED -> GiftTier.GRACIOUS;
            case SATISFIED -> GiftTier.SATISFIED;
            case ABORTED -> GiftTier.GRUDGE;
        };

        if (hitCount >= HITS_FOR_GRUDGE) {
            tier = downgrade(tier);
        }

        // The rare upgrade applies to any tier above grudge -- so a player who
        // earned the grudge cannot luck their way out of it.
        if (tier != GiftTier.GRUDGE && random.nextDouble() < gifts.goldenChance()) {
            tier = GiftTier.GOLDEN;
        }

        return new Gift(tier, countFor(tier, random, gifts));
    }

    /**
     * The gift for a Food Critic that was actually fed.
     *
     * <p>The treat decides the tier outright rather than rolling for it -- a
     * golden carrot is the stated way to earn the golden tier (SPEC.md section
     * 5), so it must not be left to a 2% chance. Being rude still costs a tier.
     */
    public static Gift selectFed(Treat treat, int hitCount, Random random) {
        return selectFed(treat, hitCount, random, GiftSettings.defaults());
    }

    public static Gift selectFed(Treat treat, int hitCount, Random random, GiftSettings gifts) {
        GiftTier tier = treat.tier();
        if (hitCount >= HITS_FOR_GRUDGE) {
            tier = downgrade(tier);
        }
        return new Gift(tier, countFor(tier, random, gifts));
    }

    /** One step down {@link #LADDER}; {@code GRUDGE} is the floor. */
    public static GiftTier downgrade(GiftTier tier) {
        for (int i = 0; i < LADDER.length; i++) {
            if (LADDER[i] == tier) {
                return LADDER[Math.max(0, i - 1)];
            }
        }
        return GiftTier.GRUDGE;
    }

    /** Inclusive-range roll for a tier's stack size. */
    public static int countFor(GiftTier tier, Random random) {
        return countFor(tier, random, GiftSettings.defaults());
    }

    public static int countFor(GiftTier tier, Random random, GiftSettings gifts) {
        return between(random, gifts.minFor(tier), gifts.maxFor(tier));
    }

    private static int between(Random random, int minInclusive, int maxInclusive) {
        if (maxInclusive <= minInclusive) {
            return minInclusive;
        }
        return minInclusive + random.nextInt(maxInclusive - minInclusive + 1);
    }
}
