package chatdonkey.core;

import java.util.List;
import java.util.Random;

/**
 * The pool of things the donkey hands over, as item ids (SPEC.md section 5).
 *
 * <p>Cobblestone used to be the whole gift, on the theory that the reward should
 * be an anticlimax. In play the anticlimax landed but the item was simply
 * binned — a punchline nobody keeps is a punchline nobody sees twice.
 *
 * <p>These are the replacement: cheap, slightly absurd, and <em>enchanted</em>.
 * The enchantment is rolled without regard for whether it belongs on the item,
 * so the donkey hands over a Fishing Rod of Fire Aspect or a Bowl of
 * Feather Falling. That is funnier to receive, it is a story rather than a
 * stack, and — because {@code wondrous} ships a disenchanter that lifts an
 * enchantment onto a book — the joke item is also quietly worth keeping.
 *
 * <p>Note that this is a compliment to {@code wondrous}, not a dependency on it
 * (DESIGN.md §2): nothing here knows that mod exists. A vanilla grindstone gets
 * you the same value, just less of it.
 */
public final class GiftItems {

    private GiftItems() {}

    /**
     * Junk with personality. Every one is cheap, none is a real reward on its
     * own, and all of them look ridiculous with an enchantment on.
     */
    public static final List<String> THEMATIC = List.of(
            "minecraft:fishing_rod",
            "minecraft:bowl",
            "minecraft:wooden_hoe",
            "minecraft:stick",
            "minecraft:bone",
            "minecraft:lead",
            "minecraft:saddle",
            "minecraft:name_tag",
            "minecraft:paper",
            "minecraft:feather",
            "minecraft:leather_boots",
            "minecraft:leather_helmet",
            "minecraft:carrot_on_a_stick",
            "minecraft:shears",
            "minecraft:flint_and_steel",
            "minecraft:wooden_shovel",
            "minecraft:brush",
            "minecraft:bucket",
            "minecraft:clock",
            "minecraft:compass");

    /** A random item id from the pool. */
    public static String random(Random random) {
        return THEMATIC.get(random.nextInt(THEMATIC.size()));
    }

    /**
     * How many enchantments to stack on a gift of a given tier.
     *
     * <p>The tier controls the <em>absurdity</em>, not the power: a golden gift
     * is not a better weapon, it is a more ridiculous object with more nonsense
     * written on it. That keeps the drop table a punchline while still making
     * the good endings visibly better to receive.
     */
    public static int enchantmentsFor(GiftTier tier) {
        return switch (tier) {
            case GRUDGE -> 0;
            case STANDARD -> 1;
            case GRACIOUS, SATISFIED -> 2;
            case GOLDEN -> 3;
        };
    }
}
