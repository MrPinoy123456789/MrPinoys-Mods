package pocketdungeons;

/**
 * One affix. A run carries a <em>set</em> of these, never a single value -- see
 * {@link AffixMath} for the arithmetic that decides which.
 *
 * <h2>Why this is not nested in {@link Keystone} any more</h2>
 *
 * <p>It was, until M4. The threshold, seeding, naming and depletion maths belong
 * in a plain-{@code javac} class in the manner of {@link KeystoneMath},
 * {@link DifficultyProfile} and {@link DoorMask} -- and they cannot be, if merely
 * naming the enum drags in {@code Keystone}'s {@code ConfiguredItem} statics and
 * with them the whole of Minecraft. So the enum moved out and carries no
 * Minecraft imports; {@code ChatFormatting} lives at the one place that renders
 * a label, {@link Keystone#colourOf}.
 *
 * <h2>The rule every member here owes</h2>
 *
 * <p><strong>Every affix bends a rule and pays for it with a gift</strong>
 * ({@code docs/MYTHIC_PLUS_RECONCILIATION.md} 5.0). {@link #blurb} is where that debt
 * is settled: it is the line a player reads on the item, and it has to say both
 * halves in one sentence. An affix whose blurb cannot be written is an affix that
 * should not ship.
 *
 * <p><strong>Declaration order is render order, always.</strong> The keystone name
 * must stay a pure function of {@code (level, affixSet)}: the instance watcher
 * rewrites stale remotes in place on its interval, so any ordering that varied
 * would churn every label on every reconciliation.
 */
enum Affix {

    /** The whole run stamps ominous, and the payout is worth more. */
    OMINOUS("Cooked", Kind.ELECTIVE, 1,
            "Cooked: the whole run runs ominous, and pays out ominous."),

    /** Depletes double on a failure. The whole cost of having taken the +3. */
    FRAGILE("Big L", Kind.ELECTIVE, 2,
            "Big L: every failure costs double. The extra levels were the gift."),

    /**
     * Wolves in the halls. The curse is that they are there at all and they are
     * not yours yet; the kiss is that they can be, and a wolf caught on a deep
     * key wears a coat a shallow one never hands out.
     *
     * <p>The wolves spawn <strong>neutral</strong>. That is not a softening --
     * {@code Wolf.mobInteract} refuses a bone outright while {@code isAngry()},
     * so an angered wolf is an untameable wolf and the kiss would be worth
     * nothing. The rule a player actually reads is "don't hit it, feed it", and
     * vanilla's anger-on-hit enforces it for free.
     */
    FERAL("Feral", Kind.SEEDED, 1,
            "Feral: wolves in the halls. Swing and they are lost, feed them and they are yours."),

    /** More bodies per trial spawner. */
    SWARMING("Swarming", Kind.SEEDED, 1,
            "Swarming: more of them, and more of them is more drops."),

    /** Trial spawners come back off cooldown far sooner. */
    OVERCLOCKED("Overclocked", Kind.SEEDED, 1,
            "Overclocked: the waves come back fast, so a fast clear is faster."),

    /**
     * Lava and magma underfoot -- and the only lava in the game. A sealed dungeon
     * has none, and lava gates furnace fuel and, with water, obsidian. The hazard
     * blocks <em>are</em> the reward.
     */
    MOLTEN("Molten", Kind.SEEDED, 1,
            "Molten: lava underfoot, and the only lava you will ever find."),

    /** No consumables. The mobs cannot hear you either. */
    SILENCED("Silenced", Kind.SEEDED, 1,
            "Silenced: no consumables, and they cannot hear you coming.");

    /** How an affix gets onto a key. */
    enum Kind {
        /** Taken deliberately at a door, in exchange for extra levels. */
        ELECTIVE,
        /** Handed out by the level thresholds, picked by the key itself. */
        SEEDED
    }

    /** The word that appears in the item name. */
    final String label;

    /** Whether this one is chosen at a door or dealt by the thresholds. */
    final Kind kind;

    /**
     * What this affix multiplies a depletion by. Across a set the multipliers are
     * combined with {@code max}, never a product -- see
     * {@link AffixMath#depletionMultiplier}.
     */
    final int depletionMultiplier;

    /** Curse and kiss, one sentence, rendered as a lore line. */
    final String blurb;

    Affix(String label, Kind kind, int depletionMultiplier, String blurb) {
        this.label = label;
        this.kind = kind;
        this.depletionMultiplier = depletionMultiplier;
        this.blurb = blurb;
    }
}
