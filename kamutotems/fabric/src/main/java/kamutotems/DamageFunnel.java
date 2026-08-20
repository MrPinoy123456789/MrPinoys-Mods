package kamutotems;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import kamutotems.core.Context;
import kamutotems.core.Delivery;
import kamutotems.core.Effect;
import kamutotems.core.Kamuy;
import kamutotems.core.ReactionEngine;
import kamutotems.core.ResolutionResult;
import kamutotems.core.Resolver;

/**
 * Resolves the player's Kamu Totem whenever the player deals damage to a
 * living entity. A per-player re-entrancy guard stops a construct's own
 * effects from infinitely re-triggering the funnel.
 *
 * <p>The 26.2 rewrite drops the WHEN slot. The resolver now returns which
 * delivery the construct uses; this class dispatches it.
 */
public final class DamageFunnel {

    private static final Set<UUID> resolving = ConcurrentHashMap.newKeySet();
    private static final Resolver RESOLVER =
            new Resolver(KamuData.catalog(), KamuData.reactions());

    private DamageFunnel() {}

    public static void register() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(DamageFunnel::allowDamage);
    }

    /** Observes player-caused damage without cancelling it and dispatches the attacker's construct. */
    private static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
        if (entity == null || entity.level().isClientSide()) {
            return true;
        }

        Entity attacker = source.getEntity();
        if (!(attacker instanceof ServerPlayer player)) {
            return true;
        }
        if (entity == player) {
            return true; // never resolve on self-damage
        }

        resolveFor(player, entity);
        return true;
    }

    static void resolveFor(ServerPlayer player, LivingEntity entity) {
        if (player == null || player.level().isClientSide()) {
            return;
        }

        UUID id = player.getUUID();
        if (resolving.contains(id)) {
            return; // re-entrancy guard
        }

        ItemStack off = player.getOffhandItem();
        if (!Totem.is(off) || Totem.chargesRemaining(off) <= 0) {
            return;
        }

        resolving.add(id);
        try {
            ServerLevel level = (ServerLevel) player.level();
            Kamuy kamuy = TotemHost.KamuyStore.getOrCreate(player);

            Set<String> targetTags = new HashSet<>();
            if (entity.isOnFire()) targetTags.add("burning");
            if (entity.isInWater() || entity.isUnderWater()) targetTags.add("wet");
            if (entity.isFreezing()) targetTags.add("frozen");

            Set<String> envTags = new HashSet<>();
            if (level.isRainingAt(player.blockPosition())) envTags.add("raining");
            if (!level.isBrightOutside()) envTags.add("night");
            if (level.dimension().equals(Level.NETHER)) envTags.add("nether");

            long seed = player.getRandom().nextLong();
            Context context = new Context(
                    player.getUUID().toString(),
                    entity.getUUID().toString(),
                    targetTags,
                    envTags,
                    new ArrayList<>(),
                    seed);

            ResolutionResult result = RESOLVER.resolve(kamuy.construct(), context, seed);
            if (!result.valid()) {
                if (result.fault() != null && !result.fault().isBlank()) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component
                            .literal(result.fault()).withStyle(net.minecraft.ChatFormatting.RED));
                }
                return;
            }

            Delivery delivery = Delivery.fromKamuId(result.deliveryId());
            if (delivery == null) {
                delivery = Delivery.HIT;
            }

            switch (delivery) {
                case HIT -> EffectsMc.apply(result.effects(), level, player, entity);
                case ECHO -> EchoQueue.queue(level, player, entity, result.effects(),
                        delivery.delayTicks());
                case SPLASH -> applySplash(result.effects(), level, player, entity,
                        delivery.areaRadius(), delivery.powerScale(), delivery.maxTargets());
            }
        } finally {
            resolving.remove(id);
        }
    }

    private static void applySplash(List<Effect> effects, ServerLevel level,
                                    ServerPlayer source, LivingEntity primary,
                                    double radius, double scale, int maxTargets) {
        if (effects == null || effects.isEmpty()) {
            return;
        }
        List<Effect> scaled = scaleEffects(effects, scale);

        AABB box = primary.getBoundingBox().inflate(radius);
        List<LivingEntity> inRange = level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e != source && e.isAlive());

        // The primary target is included and counts as the first hit.
        List<LivingEntity> ordered = new ArrayList<>();
        ordered.add(primary);
        for (LivingEntity near : inRange) {
            if (near != primary) {
                ordered.add(near);
            }
        }

        int count = 0;
        for (LivingEntity target : ordered) {
            if (!target.isAlive()) {
                continue;
            }
            EffectsMc.apply(scaled, level, source, target);
            if (++count >= maxTargets) {
                break;
            }
        }
    }

    private static List<Effect> scaleEffects(List<Effect> effects, double scale) {
        List<Effect> out = new ArrayList<>(effects.size());
        for (Effect e : effects) {
            if (e == null) {
                out.add(null);
                continue;
            }
            Map<String, Double> scaled = new HashMap<>();
            if (e.parameters() != null) {
                for (Map.Entry<String, Double> entry : e.parameters().entrySet()) {
                    scaled.put(entry.getKey(), entry.getValue() * scale);
                }
            }
            out.add(new Effect(e.effectId(), e.targetId(), scaled));
        }
        return out;
    }
}
