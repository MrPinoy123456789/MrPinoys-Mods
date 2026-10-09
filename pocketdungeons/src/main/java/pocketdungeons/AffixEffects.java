package pocketdungeons;

/**
 * M69: the bounded operation model an {@link AffixDefinition} carries.
 *
 * <p>Every affix bends a rule and pays for it with a gift
 * ({@code docs/reference/MYTHIC_PLUS_RECONCILIATION.md} 5.0). The curse and the
 * gift are both expressed here as bounded parameters, so a third-party
 * definition declares what its affix does in data rather than in Java. An
 * operation a JSON file declares that is not one of these fields is a load
 * rejection: the supported set is closed on purpose, the same way the
 * situation tag vocabulary is closed
 * ({@code docs/INTEGRATION.md} 2.2). Genuinely new operations still require
 * reviewed engine work.
 *
 * <p>Pure Java (no Minecraft imports), so {@link AffixMath} and its
 * plain-{@code javac} test can build fixtures without the server classpath.
 *
 * <h2>Operation order</h2>
 *
 * <p>When a cell is stamped, the active affix set's effects apply in one
 * fixed, documented order. The order is frozen so a given seed always stamps
 * the same room, the same way enum declaration order was frozen before it:
 * <ol>
 *   <li>ominous blockstate (every trial spawner and vault stamped ominous)</li>
 *   <li>trial count (mob count multiplier, Swarming)</li>
 *   <li>cooldown (spawner cooldown factor, Overclocked)</li>
 *   <li>player range (detection range, Silenced)</li>
 *   <li>consumable rule (allow, block, or raise the omen on use; Silenced uses OMEN, playtest 2026-10-02-1)</li>
 *   <li>neutral wolf spawn (Feral)</li>
 *   <li>interior hazard placement (Molten lava, Explosive TNT)</li>
 *   <li>voided floor (Voided)</li>
 *   <li>extra trial bodies (Loaded)</li>
 *   <li>bonus tool pool (Loaded)</li>
 *   <li>decor pool (Loaded, optional)</li>
 *   <li>undead rise chance (Restless; a runtime effect, read when an undead mob dies)</li>
 * </ol>
 *
 * <p>A field left at its default (no-op) means the affix does not touch that
 * operation. An affix that touches nothing is rejected at load: the rule
 * every member owes is that it bends a rule, and a definition that bends
 * nothing has no business shipping.
 */
final class AffixEffects {

    /** How the consumable rule operation reads. {@code ALLOW} is the no-op default. */
    enum ConsumableRule { ALLOW, BLOCK, OMEN }

    /** The kind of interior hazard placed underfoot. {@code NONE} is the no-op default. */
    enum HazardKind { NONE, LAVA, TNT }

    /** Whether the whole run stamps ominous. */
    final boolean ominous;

    /**
     * Multiplier applied to a trial spawner's {@code totalMobs} and
     * {@code simultaneousMobs}. {@code 1.0} (the default) leaves the counts
     * alone. Bounded to {@code [1.0, 4.0]} at load: more than quadruple is a
     * different dungeon, not a harder one.
     */
    final double trialCountMultiplier;

    /**
     * Multiplier applied to the spawner cooldown. {@code 1.0} (the default)
     * leaves it alone. Bounded to {@code [0.25, 1.0]} at load: a cooldown
     * shorter than a quarter is a spawner that never rests.
     */
    final double cooldownFactor;

    /**
     * The trial spawner's {@code required_player_range}. {@code 14} (the
     * default) is vanilla's value. Bounded to {@code [4, 14]} at load.
     */
    final int playerRange;

    /** Whether consumables are blocked for this run. */
    final ConsumableRule consumableRule;

    /** Whether neutral wolves spawn in non-encounter cells. */
    final boolean neutralWolfSpawn;

    /** The interior hazard placed underfoot, or {@code NONE}. */
    final HazardKind hazardKind;

    /**
     * How many hazard blocks per cell. Bounded to {@code [0, 16]} at load
     * (a 16-wide cell). {@code 0} with a non-{@code NONE} kind is rejected.
     */
    final int hazardsPerCell;

    /** Whether the floor is ripped open in scattered cells. */
    final boolean voidedFloor;

    /** Whether extra trial bodies are placed (Loaded). */
    final boolean extraTrialBodies;

    /**
     * A namespaced loot table id for a guaranteed bonus tool pool, or
     * {@code null} when the affix carries none. Validated against the loot
     * table registry at load.
     */
    final String bonusToolPool;

    /** A namespaced loot table id for a decor pool, or {@code null}. */
    final String decorPool;

    /**
     * Restless (design pass 2026-10-09, Q8): the chance a slain undead mob rises once more where it fell, and
     * the same chance on an ominous floor. Fire keeps them down. {@code 0} (the default) is no rise. Bounded
     * to {@code [0, 1]}.
     */
    final double undeadRiseChance;
    final double undeadRiseChanceOminous;

    private AffixEffects(boolean ominous, double trialCountMultiplier, double cooldownFactor,
                         int playerRange, ConsumableRule consumableRule, boolean neutralWolfSpawn,
                         HazardKind hazardKind, int hazardsPerCell, boolean voidedFloor,
                         boolean extraTrialBodies, String bonusToolPool, String decorPool,
                         double undeadRiseChance, double undeadRiseChanceOminous) {
        this.undeadRiseChance = undeadRiseChance;
        this.undeadRiseChanceOminous = undeadRiseChanceOminous;
        this.ominous = ominous;
        this.trialCountMultiplier = trialCountMultiplier;
        this.cooldownFactor = cooldownFactor;
        this.playerRange = playerRange;
        this.consumableRule = consumableRule;
        this.neutralWolfSpawn = neutralWolfSpawn;
        this.hazardKind = hazardKind;
        this.hazardsPerCell = hazardsPerCell;
        this.voidedFloor = voidedFloor;
        this.extraTrialBodies = extraTrialBodies;
        this.bonusToolPool = bonusToolPool;
        this.decorPool = decorPool;
    }

    /** The no-op effects: an affix that does nothing. Used only as the build baseline. */
    static AffixEffects none() {
        return new AffixEffects(false, 1.0, 1.0, 14, ConsumableRule.ALLOW, false,
                HazardKind.NONE, 0, false, false, null, null, 0.0, 0.0);
    }

    /**
     * Builds effects from validated parameters. Bounds are enforced here and
     * a violation throws, naming the field, so the manifest loader can catch
     * it and report the file. This is the single chokepoint for the
     * "supported operations and extension limits" gate.
     */
    static AffixEffects build(boolean ominous, double trialCountMultiplier, double cooldownFactor,
                              int playerRange, ConsumableRule consumableRule, boolean neutralWolfSpawn,
                              HazardKind hazardKind, int hazardsPerCell, boolean voidedFloor,
                              boolean extraTrialBodies, String bonusToolPool, String decorPool) {
        if (trialCountMultiplier < 1.0 || trialCountMultiplier > 4.0) {
            throw new IllegalArgumentException("trial_count_multiplier must be in [1.0, 4.0]");
        }
        if (cooldownFactor < 0.25 || cooldownFactor > 1.0) {
            throw new IllegalArgumentException("cooldown_factor must be in [0.25, 1.0]");
        }
        if (playerRange < 4 || playerRange > 14) {
            throw new IllegalArgumentException("player_range must be in [4, 14]");
        }
        if (hazardsPerCell < 0 || hazardsPerCell > 16) {
            throw new IllegalArgumentException("hazards_per_cell must be in [0, 16]");
        }
        if (hazardKind != HazardKind.NONE && hazardsPerCell == 0) {
            throw new IllegalArgumentException("hazards_per_cell must be > 0 when hazard_kind is set");
        }
        return new AffixEffects(ominous, trialCountMultiplier, cooldownFactor, playerRange,
                consumableRule == null ? ConsumableRule.ALLOW : consumableRule, neutralWolfSpawn,
                hazardKind == null ? HazardKind.NONE : hazardKind, hazardsPerCell, voidedFloor,
                extraTrialBodies, bonusToolPool, decorPool, 0.0, 0.0);
    }

    /** These effects with an undead rise chance (Restless), each in {@code [0, 1]}. */
    AffixEffects withUndeadRise(double chance, double ominousChance) {
        if (chance < 0.0 || chance > 1.0 || ominousChance < 0.0 || ominousChance > 1.0) {
            throw new IllegalArgumentException("undead_rise_chance must be in [0.0, 1.0]");
        }
        return new AffixEffects(ominous, trialCountMultiplier, cooldownFactor, playerRange, consumableRule,
                neutralWolfSpawn, hazardKind, hazardsPerCell, voidedFloor, extraTrialBodies, bonusToolPool,
                decorPool, chance, ominousChance);
    }

    /**
     * Whether this definition touches any operation at all. A definition that
     * bends nothing is rejected at load: the rule every member owes is that it
     * bends a rule.
     */
    boolean hasAnyOperation() {
        return ominous || trialCountMultiplier != 1.0 || cooldownFactor != 1.0
                || playerRange != 14 || consumableRule != ConsumableRule.ALLOW
                || neutralWolfSpawn || hazardKind != HazardKind.NONE || voidedFloor
                || extraTrialBodies || bonusToolPool != null || decorPool != null || undeadRiseChance > 0.0
                || undeadRiseChanceOminous > 0.0;
    }
}
