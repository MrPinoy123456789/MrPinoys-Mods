package pocketdungeons;

import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Keystone level and omen scaling for dungeon mobs (M10): the transient
 * attribute modifiers that make a deeper run's mobs hit harder, move faster and
 * soak more. Extracted from {@code Instances} unchanged; callers are
 * {@code Instances}' entity load listener, {@link FeralContent}, {@link KennelSpecs}
 * and {@link BossContent}.
 */
final class MobScaling {

    private MobScaling() {}

    /** The one shown by {@code AttributeInstance}'s modifier map, stable across reapplications. */
    private static final Identifier MOB_SCALE_ID = Identifier.fromNamespaceAndPath(
            PocketDungeonsMod.MOD_ID, "difficulty_scale");

    /** Separate modifier id for the Breeze HP override, so it does not collide with the level scaler. */
    private static final Identifier BREEZE_HP_ID = Identifier.fromNamespaceAndPath(
            PocketDungeonsMod.MOD_ID, "breeze_hp_override");

    /**
     * +2% (config: {@link PocketDungeonsConfig#mobScalePerLevel()}) per keystone
     * level over a 0.85 base, on max health, attack damage and movement speed:
     * level 8 lands near vanilla, level 20 at 1.25x. {@code ADD_MULTIPLIED_TOTAL} so the
     * bonus scales whatever base value the mob already has rather than adding a
     * flat number that means nothing on a skeleton and everything on a bee.
     *
     * <p>Package-visible rather than {@code private}: {@link FeralContent}'s
     * wolves are spawned directly at stamp time, before {@code ENTITY_LOAD} would
     * ever find an {@link InstanceRecord} whose layout matches (the record still
     * carries the lobby's tiny one-cell layout at that point), so they cannot
     * rely on the listener below and call this straight from the spawn callback
     * where the run's keystone level is already known.
     *
     * <p>Idempotent under the same modifier id: a repeat call for the same
     * entity (a chunk reload's {@code ENTITY_LOAD} firing again, or the listener
     * and a direct caller both reaching the same mob) replaces the modifier
     * rather than stacking it. Health is topped up only when the mob was at full
     * health <em>before</em> this ran: a fresh spawn always is, and topping it
     * up is what keeps it from reading as already-damaged the instant it
     * appears. A mob re-entering tracking mid-fight is not, and must keep
     * whatever damage it already has.
     */
    static void applyMobScale(Mob mob, int keystoneLevel) {
        applyMobScale(mob, keystoneLevel, 0, 2);
    }

    /** The act whose omen rate applies to a trip: its dungeon's act, or 2 with no dungeon (Uncharted, the dive). */
    static int actOf(InstanceRecord record) {
        DungeonDef def = TripView.def(record);
        return def == null ? 2 : def.act();
    }

    static void applyMobScale(Mob mob, int keystoneLevel, int omen, int act) {
        double omenBonus = DifficultyProfile.omenBonus(Omen.clamp(omen), act,
                PocketDungeonsConfig.omenDangerScaleActOne(), PocketDungeonsConfig.omenDangerScalePerOmen());
        applyMobScaleBonus(mob, DifficultyProfile.mobScale(keystoneLevel,
                PocketDungeonsConfig.mobScalePerLevel(),
                PocketDungeonsConfig.mobScaleBase()) - 1.0 + omenBonus);
        // Breezes are disproportionately tedious to kill for their threat level:
        // high HP plus high mobility means a player spends most of the fight
        // chasing, not hitting. An additional HP-only cut on top of the level
        // scaling brings them in line with everything else on the spawner roster.
        if (mob.getType() == EntityTypes.BREEZE) {
            double multiplier = PocketDungeonsConfig.breezeHpMultiplier();
            if (multiplier > 0.0 && multiplier != 1.0) {
                boolean wasFullHealth = mob.getHealth() >= mob.getMaxHealth();
                AttributeInstance health = mob.getAttribute(Attributes.MAX_HEALTH);
                if (health != null) {
                    health.addOrUpdateTransientModifier(new AttributeModifier(
                            BREEZE_HP_ID, multiplier - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
                    if (wasFullHealth) {
                        mob.setHealth(mob.getMaxHealth());
                    }
                }
            }
        }
    }

    /**
     * The bonus-taking half of {@link #applyMobScale}, exposed separately for
     * {@link BossContent}: a boss run's mob wants a steeper bonus than the
     * ordinary {@code mobScalePerLevel} curve, not the same one.
     */
    static void applyMobScaleBonus(Mob mob, double bonus) {
        if (bonus == 0.0) {
            return;
        }
        boolean wasFullHealth = mob.getHealth() >= mob.getMaxHealth();
        boolean scaledHealth = scaleAttribute(mob, Attributes.MAX_HEALTH, bonus);
        scaleAttribute(mob, Attributes.ATTACK_DAMAGE, bonus);
        scaleAttribute(mob, Attributes.MOVEMENT_SPEED, bonus);
        if (scaledHealth && wasFullHealth) {
            mob.setHealth(mob.getMaxHealth());
        }
    }

    private static boolean scaleAttribute(Mob mob, Holder<Attribute> attribute, double bonus) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance == null) {
            return false;
        }
        instance.addOrUpdateTransientModifier(new AttributeModifier(
                MOB_SCALE_ID, bonus, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        return true;
    }
}
