package kamutotems;

import kamutotems.core.Construct;
import kamutotems.core.Context;
import kamutotems.core.Effect;
import kamutotems.core.Kamu;
import kamutotems.core.ResolutionResult;
import kamutotems.core.Resolver;
import kamutotems.core.Slot;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ticks every active boss: one kamu cast per cooldown, through the same
 * {@link EffectsMc#apply} path a player's totem uses. Every cast is telegraphed.
 *
 * <p>A boss's aura is NOT driven from here. {@link AuraHost} ticks
 * {@link BossHost#activeBosses()} directly, alongside players, in its own
 * unified loop -- one aura pipeline for both bearers, per the admission law
 * (PLAN_COMBAT §2.1). This class owns casting only.
 */
public final class BossBrain {

    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final Resolver RESOLVER = new Resolver(KamuData.catalog(), KamuData.reactions());

    private BossBrain() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(BossBrain::onServerTick);
    }

    /** Advances cast wind-ups and cooldowns for every live tracked boss. */
    private static void onServerTick(MinecraftServer server) {
        for (Boss boss : BossHost.activeBosses()) {
            Entity entity = boss.entity();
            if (entity == null || entity.isRemoved() || !entity.isAlive()) {
                STATES.remove(entity == null ? null : entity.getUUID());
                continue;
            }
            if (!(entity.level() instanceof ServerLevel level)) {
                continue;
            }
            State s = STATES.computeIfAbsent(entity.getUUID(), k -> new State(boss.tier()));
            tick(level, boss, s);
        }
    }

    private static void tick(ServerLevel level, Boss boss, State s) {
        Entity entity = boss.entity();
        if (!(entity instanceof LivingEntity source)) {
            return;
        }

        s.updateTarget(level, entity);

        if (s.winding) {
            s.progress++;
            BossCastTelegraph.tick(level, entity, s.kamu, s.progress, s.windup);
            if (s.progress >= s.windup) {
                BossCastTelegraph.castSound(level, entity, s.kamu);
                cast(level, boss, source, s.target, s.kamu);
                s.winding = false;
                s.cooldown = castCooldown(boss.tier());
                if (entity instanceof Mob mob) {
                    mob.setNoAi(false);
                }
            }
            return;
        }

        if (s.cooldown > 0) {
            s.cooldown--;
        }

        if (s.target == null || s.cooldown > 0) {
            return;
        }

        Kamu next = chooseKamu(boss, s);
        if (next == null) {
            s.cooldown = 20;
            return;
        }

        s.kamu = next;
        s.windup = KamuTotemsConfig.i("boss", "cast_windup_ticks", 30);
        s.progress = 0;
        s.winding = true;
        if (entity instanceof Mob mob) {
            // The pause is the animation for vanilla clients (PLAN_COMBAT §6).
            // setNoAi is used by the suite's wayfarers Hostile/Bodies.
            mob.setNoAi(true);
        }
    }

    private static void cast(ServerLevel level, Boss boss, LivingEntity source,
                             LivingEntity target, Kamu kamu) {
        int tier = Math.min(boss.tier(), Slot.MAX_TIER);
        List<Slot> slots = new ArrayList<>(Construct.SLOT_COUNT);
        slots.add(new Slot(Construct.DEFAULT_DELIVERY, 1));
        slots.add(new Slot(kamu.id(), tier));
        slots.add(new Slot(Construct.DEFAULT_MODIFIER, 1));
        Construct construct = new Construct(kamutotems.core.HostType.BOSS, slots);

        Set<String> targetTags = new HashSet<>();
        if (target.isOnFire()) targetTags.add("burning");
        if (target.isInWater() || target.isUnderWater()) targetTags.add("wet");
        if (target.isFreezing()) targetTags.add("frozen");

        Set<String> envTags = new HashSet<>();
        if (level.isRainingAt(target.blockPosition())) envTags.add("raining");
        if (!level.isBrightOutside()) envTags.add("night");
        if (level.dimension().equals(Level.NETHER)) envTags.add("nether");

        long seed = level.getRandom().nextLong();
        Context context = new Context(
                "boss:" + source.getUUID(),
                target.getUUID().toString(),
                targetTags,
                envTags,
                new ArrayList<>(),
                seed);

        ResolutionResult result = RESOLVER.resolve(construct, context, seed);
        if (result.valid()) {
            // Same call a player's construct uses; no boss-specific branch.
            EffectsMc.apply(result.effects(), level, source, target);
        }
    }

    private static Kamu chooseKamu(Boss boss, State s) {
        List<Kamu> carried = boss.kamu();
        if (carried == null || carried.isEmpty()) {
            return null;
        }
        Kamu k = carried.get(s.nextIndex % carried.size());
        s.nextIndex++;
        return k;
    }

    private static LivingEntity pickTarget(ServerLevel level, Entity entity) {
        if (entity instanceof Mob mob) {
            LivingEntity t = mob.getTarget();
            if (t != null && t.isAlive() && inRange(entity, t)) {
                return t;
            }
        }

        for (ServerPlayer p : level.players()) {
            if (p.isAlive() && inRange(entity, p)) {
                return p;
            }
        }
        return null;
    }

    private static boolean inRange(Entity source, LivingEntity target) {
        double range = KamuTotemsConfig.d("boss", "cast_target_range", 24.0);
        return source.distanceTo(target) <= range;
    }

    private static int castCooldown(int tier) {
        return switch (tier) {
            case 1 -> KamuTotemsConfig.i("boss", "cast_cooldown_tier1", 120);
            case 2 -> KamuTotemsConfig.i("boss", "cast_cooldown_tier2", 100);
            case 3 -> KamuTotemsConfig.i("boss", "cast_cooldown_tier3", 90);
            default -> KamuTotemsConfig.i("boss", "cast_cooldown_tier4", 80);
        };
    }

    private static final class State {
        int nextIndex;
        int cooldown;
        int windup;
        int progress;
        boolean winding;
        Kamu kamu;
        LivingEntity target;

        State(int tier) {
            this.cooldown = castCooldown(tier) / 2;
        }

        void updateTarget(ServerLevel level, Entity entity) {
            if (target != null && target.isAlive() && inRange(entity, target)) {
                return;
            }
            target = pickTarget(level, entity);
        }
    }
}
