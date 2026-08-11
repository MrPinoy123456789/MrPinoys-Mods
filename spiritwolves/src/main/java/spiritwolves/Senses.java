package spiritwolves;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.animal.wolf.WolfSoundVariant;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Senses, not stats (SPEC.md section 15.2): the wolf notices things the player
 * might not. No combat power -- the wolf growls at danger, and can be asked to
 * point out the nearest monster. Both are information, not damage.
 */
public final class Senses {

    /** How far the wolf notices monsters, for both growling and marking prey. */
    private static final double SENSE_RADIUS = 16.0;

    /** Minimum ticks between growls for a given wolf, so a mob camp isn't a siren. */
    private static final int GROWL_COOLDOWN_TICKS = 200;

    /** Cooldown entries idle longer than this are dropped, so the map can't creep. */
    private static final int COOLDOWN_EXPIRY_TICKS = 1200;

    /** How long marked prey glows. */
    private static final int GLOW_DURATION_TICKS = 200;

    private static final float GROWL_VOLUME = 0.7f;
    private static final float GROWL_PITCH = 0.9f;

    /** Wolf UUID -> tick of its last growl. Server-lifetime only; nothing to persist. */
    private static final Map<UUID, Integer> lastGrowlTick = new HashMap<>();

    private Senses() {}

    public static void register() {
        UseEntityCallback.EVENT.register(Senses::onUseEntity);
    }

    // ---- growl -------------------------------------------------------------

    /**
     * Growls if a live monster is within {@link #SENSE_RADIUS} and this wolf
     * isn't on cooldown. Called once per {@code Tracker} poll per summoned wolf.
     *
     * @param now the tracker's tick counter, used purely as a monotonic clock
     */
    public static void growlIfThreatened(Wolf wolf, int now) {
        expireCooldowns(now);

        Integer last = lastGrowlTick.get(wolf.getUUID());
        if (last != null && now - last < GROWL_COOLDOWN_TICKS) {
            return;
        }
        if (wolf.level().getEntitiesOfClass(Monster.class,
                wolf.getBoundingBox().inflate(SENSE_RADIUS), Monster::isAlive).isEmpty()) {
            return;
        }

        lastGrowlTick.put(wolf.getUUID(), now);
        growl(wolf);
    }

    /** Plays the wolf's own growl -- its sound variant, not a generic sound event. */
    private static void growl(Wolf wolf) {
        Holder<WolfSoundVariant> variant = wolf.get(DataComponents.WOLF_SOUND_VARIANT);
        if (variant == null) {
            return;
        }
        WolfSoundVariant.WolfSoundSet sounds = wolf.isBaby()
                ? variant.value().babySounds()
                : variant.value().adultSounds();
        wolf.playSound(sounds.growlSound().value(), GROWL_VOLUME, GROWL_PITCH);
    }

    /** Drops the cooldown entry for a wolf that is no longer out. */
    public static void forget(UUID wolfUuid) {
        lastGrowlTick.remove(wolfUuid);
    }

    // ---- outline current target ---------------------------------------------

    /**
     * Keeps the wolf's current combat target glowing, refreshed every
     * {@code Tracker} poll while it stays the target. Whatever set that target
     * -- a normal fight, {@code OwnerHurtByTargetGoal}, or a wondrous item like
     * the boomerang pet ball feeding {@code owner.setLastHurtMob} -- this is the
     * one place that turns "the wolf is fighting something" into a glow the
     * player can actually see, so nothing upstream of this needs to know about
     * glowing at all.
     */
    public static void outlineCurrentTarget(Wolf wolf) {
        LivingEntity target = wolf.getTarget();
        if (target != null && target.isAlive()) {
            target.addEffect(new MobEffectInstance(MobEffects.GLOWING, GLOW_DURATION_TICKS, 0, false, false));
        }
    }

    private static void expireCooldowns(int now) {
        lastGrowlTick.values().removeIf(tick -> now - tick > COOLDOWN_EXPIRY_TICKS);
    }

    // ---- mark prey ---------------------------------------------------------

    /**
     * Sneak + empty-hand right-click on your own summoned wolf marks the nearest
     * monster with glowing.
     *
     * <p>Verified against the 26.2 jar: vanilla {@code Wolf.mobInteract} toggles
     * sit/stand on an empty-hand click from the owner, and does not check
     * sneaking. Fabric's {@code UseEntityCallback} runs first and cancels the
     * packet handler outright on any non-PASS result, so this consumes the sneak
     * variant of the click and leaves the plain click's sit toggle intact.
     */
    private static InteractionResult onUseEntity(Player player, net.minecraft.world.level.Level level,
                                                  InteractionHand hand, Entity target,
                                                  EntityHitResult hitResult) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }
        if (!player.isShiftKeyDown() || !player.getItemInHand(hand).isEmpty()) {
            return InteractionResult.PASS;
        }
        if (!(target instanceof Wolf wolf)) {
            return InteractionResult.PASS;
        }
        // Only this player's own summoned wolf -- otherwise leave the interaction
        // alone entirely.
        WolfRecord record = PlayerWolfRegistry.get(serverPlayer.getUUID());
        if (record == null || !record.summoned || !wolf.getUUID().equals(record.wolfUuid)) {
            return InteractionResult.PASS;
        }

        Monster prey = nearestMonster(serverLevel, wolf);
        if (prey == null) {
            serverPlayer.sendSystemMessage(Component.literal("Your wolf sniffs the air. Nothing stirs nearby.")
                    .withStyle(ChatFormatting.GRAY));
            return InteractionResult.SUCCESS;
        }

        prey.addEffect(new MobEffectInstance(MobEffects.GLOWING, GLOW_DURATION_TICKS, 0, false, false));
        growl(wolf);
        serverPlayer.sendSystemMessage(Component.literal(
                        "Your wolf marks " + prey.getName().getString() + ".")
                .withStyle(ChatFormatting.AQUA));
        return InteractionResult.SUCCESS;
    }

    private static Monster nearestMonster(ServerLevel level, Wolf wolf) {
        Monster nearest = null;
        double nearestDistSqr = Double.MAX_VALUE;
        for (Monster candidate : level.getEntitiesOfClass(Monster.class,
                wolf.getBoundingBox().inflate(SENSE_RADIUS), Monster::isAlive)) {
            double distSqr = candidate.distanceToSqr(wolf);
            if (distSqr < nearestDistSqr) {
                nearestDistSqr = distSqr;
                nearest = candidate;
            }
        }
        return nearest;
    }
}
