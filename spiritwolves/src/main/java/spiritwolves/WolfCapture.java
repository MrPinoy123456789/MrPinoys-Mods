package spiritwolves;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.core.component.DataComponents;

/**
 * Full-entity NBT round-trip for a wolf. See SPEC.md section 6 for why this
 * beats a curated record: {@code Wolf} no longer exposes setCollarColor() or
 * getVariant() -- collar, variant, and sound variant are entity data
 * components now, and hand-rolling every field is more code and less
 * faithful than just capturing the whole entity.
 */
public final class WolfCapture {

    /** +25% base land movement speed for all summoned spirit wolves. */
    private static final Identifier LAND_SPEED_MODIFIER_ID =
            Identifier.fromNamespaceAndPath(SpiritWolvesMod.MOD_ID, "land_speed");
    private static final double LAND_SPEED_BONUS = 0.25;

    /** +50% water movement efficiency for all summoned spirit wolves. */
    private static final Identifier SWIM_SPEED_MODIFIER_ID =
            Identifier.fromNamespaceAndPath(SpiritWolvesMod.MOD_ID, "swim_speed");
    private static final double SWIM_SPEED_BONUS = 0.5;

    private WolfCapture() {}

    /**
     * Captures a wolf's full NBT, including its UUID.
     * <p>{@code saveWithoutId} intentionally omits the UUID, but the registry
     * keys the live wolf by UUID, so we preserve it explicitly.
     */
    public static CompoundTag capture(Wolf wolf, ServerLevel level) {
        ProblemReporter reporter = ProblemReporter.DISCARDING;
        TagValueOutput out = TagValueOutput.createWithContext(reporter, level.registryAccess());
        wolf.saveWithoutId(out);
        out.putIntArray("UUID", UUIDUtil.uuidToIntArray(wolf.getUUID()));

        ItemStack bodyArmor = wolf.getBodyArmorItem();
        if (!bodyArmor.isEmpty()) {
            out.store("SpiritWolvesBodyArmor", ItemStack.OPTIONAL_CODEC, bodyArmor);
        }

        return out.buildResult();
    }

    /**
     * Restores a wolf from captured NBT at the given position, and adds it to
     * the level. Does not check for an existing entity with the same UUID --
     * that duplicate check belongs to the caller (see SPEC.md section 8).
     */
    public static Wolf restore(CompoundTag wolfTag, ServerLevel level,
                                double x, double y, double z, float yaw, float pitch) {
        ProblemReporter reporter = ProblemReporter.DISCARDING;
        ValueInput in = TagValueInput.create(reporter, level.registryAccess(), wolfTag);
        Wolf wolf = EntityTypes.WOLF.create(level, EntitySpawnReason.COMMAND);
        if (wolf == null) {
            return null;
        }
        wolf.load(in);

        Optional<ItemStack> bodyArmor = in.read("SpiritWolvesBodyArmor", ItemStack.OPTIONAL_CODEC);
        bodyArmor.ifPresent(armor -> wolf.setItemSlot(EquipmentSlot.BODY, armor));

        // Old broken captures sometimes stored a zero attack-damage base. Once
        // that is in the wolfTag, every restore re-applies it. Clamp it back to
        // the vanilla wolf default so the wolf is not permanently toothless.
        AttributeInstance attack = wolf.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attack != null && attack.getBaseValue() <= 0.0) {
            attack.setBaseValue(4.0);
        }

        // Spirit wolves are faster on land and better swimmers than wild wolves.
        applyModifier(wolf.getAttribute(Attributes.MOVEMENT_SPEED), LAND_SPEED_MODIFIER_ID,
                LAND_SPEED_BONUS, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        applyModifier(wolf.getAttribute(Attributes.WATER_MOVEMENT_EFFICIENCY), SWIM_SPEED_MODIFIER_ID,
                SWIM_SPEED_BONUS, AttributeModifier.Operation.ADD_VALUE);

        // A wolf captured mid-fall (or mid-death-save) carries its accumulated
        // fall distance and any residual velocity/fire in its NBT. Left alone,
        // the restored wolf reapplies fall damage -- sometimes lethal -- the
        // instant it touches ground, before the player sees it take a step.
        wolf.resetFallDistance();
        wolf.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        wolf.clearFire();
        // Potion effects active at the moment of capture (a Weakness debuff mid-
        // fight, say) are frozen into the NBT and would otherwise resume exactly
        // where they left off on the next summon -- effects don't tick while the
        // wolf is inside the stone, so a mid-duration debuff would look "stuck".
        // Every summon starts from a clean slate instead.
        wolf.removeAllEffects();
        wolf.snapTo(x, y, z, yaw, pitch);
        wolf.setPersistenceRequired();
        level.addFreshEntity(wolf);
        return wolf;
    }

    /** The wolf's display name, or null if unnamed. */
    public static String nameOf(Wolf wolf) {
        return wolf.hasCustomName() && wolf.getCustomName() != null
                ? wolf.getCustomName().getString()
                : null;
    }

    private static void applyModifier(AttributeInstance attribute, Identifier id, double amount,
                                      AttributeModifier.Operation operation) {
        if (attribute == null) {
            return;
        }
        attribute.removeModifier(id);
        if (amount != 0.0) {
            attribute.addOrUpdateTransientModifier(new AttributeModifier(id, amount, operation));
        }
    }

    /** The wolf's collar colour name, or null if uncollared. */
    public static String collarOf(Wolf wolf) {
        DyeColor collar = wolf.get(DataComponents.WOLF_COLLAR);
        if (collar == null) {
            return null;
        }
        return collar.getName();
    }
}
