package spiritwolves;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.wolf.Wolf;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Killstreak: the wolf grows and hits harder the longer it goes without being
 * returned to the stone.
 *
 * <p>This is the one place in the mod that touches combat math, and it is
 * deliberately fenced in. A streak is <em>per-outing</em>: it is never written
 * to the stone, it resets the moment the wolf goes back inside by any route,
 * and {@link RecallLock} stops the player pulling the wolf out of a fight it is
 * losing. Those three rules together are what stop a killstreak becoming a
 * reason to <em>not</em> use the wolf -- there is nothing to bank and nothing
 * to protect, so the only way to hold a streak is to keep fighting.
 *
 * <p>Only the high-water mark survives, as a journal line.
 */
public final class Streak {

    /** Streak thresholds, highest first. Discrete tiers read better than a smooth ramp. */
    private static final int[] TIER_THRESHOLDS = { 15, 10, 6, 3 };
    private static final double[] TIER_SCALE_BONUS = { 0.15, 0.13, 0.09, 0.05 };
    private static final double[] TIER_DAMAGE_BONUS = { 2.0, 1.5, 1.0, 0.5 };
    /** Fractional bonus to movement speed, same tier spread as scale. */
    private static final double[] TIER_SPEED_BONUS = { 0.15, 0.13, 0.09, 0.05 };

    private static final Identifier SCALE_MODIFIER_ID =
            Identifier.fromNamespaceAndPath(SpiritWolvesMod.MOD_ID, "streak_scale");
    private static final Identifier DAMAGE_MODIFIER_ID =
            Identifier.fromNamespaceAndPath(SpiritWolvesMod.MOD_ID, "streak_damage");
    private static final Identifier SPEED_MODIFIER_ID =
            Identifier.fromNamespaceAndPath(SpiritWolvesMod.MOD_ID, "streak_speed");

    /** Wolf UUID -> current streak. Transient by design; see the class doc. */
    private static final Map<UUID, Integer> streaks = new HashMap<>();

    private Streak() {}

    /** The wolf's current streak. Zero for a wolf that has just been summoned. */
    public static int current(Wolf wolf) {
        return streaks.getOrDefault(wolf.getUUID(), 0);
    }

    /**
     * The wolf killed something. Bumps the streak, re-applies the buffs live so
     * the wolf visibly swells mid-fight, and chimes when it crosses a tier.
     */
    public static void onKill(Wolf wolf, ServerPlayer owner) {
        int before = current(wolf);
        int after = before + 1;
        streaks.put(wolf.getUUID(), after);

        applyBuffs(wolf, after);

        if (tierOf(after) > tierOf(before)) {
            Chime.streakUp(owner);
        }
    }

    /**
     * The wolf went back into the stone -- recalled, death-saved, logged out, or
     * reconciled away. Records the high-water mark on the record and drops the
     * streak. Call this before the wolf is discarded.
     */
    public static void onReturn(Wolf wolf, WolfRecord record) {
        Integer ended = streaks.remove(wolf.getUUID());
        if (record == null || ended == null || ended <= record.bestStreak) {
            return;
        }
        record.bestStreak = ended;
        Journal.bestStreak(record, ended);
    }

    /** Drops a wolf's streak with no stone to record it against. */
    public static void forget(UUID wolfUuid) {
        streaks.remove(wolfUuid);
    }

    /**
     * Applies the streak's scale and attack-damage bonuses.
     *
     * <p>Transient modifiers, not {@code setBaseValue}: {@link WolfCapture}
     * round-trips the whole entity through NBT, and a clobbered base value would
     * persist into the stone and compound on every summon. Transient modifiers
     * are not serialised, so a captured wolf always goes into the stone clean and
     * the buffs are rebuilt from the live streak instead.
     */
    public static void applyBuffs(Wolf wolf, int streak) {
        int tier = tierOf(streak);
        double scaleBonus = tier == 0 ? 0.0 : TIER_SCALE_BONUS[TIER_THRESHOLDS.length - tier];
        double damageBonus = tier == 0 ? 0.0 : TIER_DAMAGE_BONUS[TIER_THRESHOLDS.length - tier];
        double speedBonus = tier == 0 ? 0.0 : TIER_SPEED_BONUS[TIER_THRESHOLDS.length - tier];

        applyModifier(wolf.getAttribute(Attributes.SCALE), SCALE_MODIFIER_ID, scaleBonus,
                AttributeModifier.Operation.ADD_VALUE);
        applyModifier(wolf.getAttribute(Attributes.ATTACK_DAMAGE), DAMAGE_MODIFIER_ID, damageBonus,
                AttributeModifier.Operation.ADD_VALUE);
        // Percentage bonus, not flat -- the wolf's base speed is a fraction of a
        // block per tick, so a flat ADD_VALUE would be wildly oversized.
        applyModifier(wolf.getAttribute(Attributes.MOVEMENT_SPEED), SPEED_MODIFIER_ID, speedBonus,
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
    }

    private static void applyModifier(AttributeInstance attribute, Identifier id, double amount,
                                       AttributeModifier.Operation operation) {
        if (attribute == null) {
            return;
        }
        attribute.removeModifier(id);
        if (amount > 0.0) {
            attribute.addOrUpdateTransientModifier(new AttributeModifier(id, amount, operation));
        }
    }

    /** Tier number, 0 (no bonus) through {@code TIER_THRESHOLDS.length}. */
    private static int tierOf(int streak) {
        for (int i = 0; i < TIER_THRESHOLDS.length; i++) {
            if (streak >= TIER_THRESHOLDS[i]) {
                return TIER_THRESHOLDS.length - i;
            }
        }
        return 0;
    }

    /** Current size multiplier for a streak, for display. */
    public static double scaleFor(int streak) {
        int tier = tierOf(streak);
        return 1.0 + (tier == 0 ? 0.0 : TIER_SCALE_BONUS[TIER_THRESHOLDS.length - tier]);
    }

    /** Current bonus attack damage for a streak, for display. */
    public static double damageFor(int streak) {
        int tier = tierOf(streak);
        return tier == 0 ? 0.0 : TIER_DAMAGE_BONUS[TIER_THRESHOLDS.length - tier];
    }

    /** Current movement speed multiplier for a streak, for display. */
    public static double speedFor(int streak) {
        int tier = tierOf(streak);
        return 1.0 + (tier == 0 ? 0.0 : TIER_SPEED_BONUS[TIER_THRESHOLDS.length - tier]);
    }
}
