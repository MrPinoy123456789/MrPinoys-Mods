package kamutotems;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import kamutotems.core.AuraKind;
import kamutotems.core.AuraOutcome;
import kamutotems.core.AuraResolver;
import kamutotems.core.AuraSpec;
import kamutotems.core.Effect;
import kamutotems.core.Kamu;
import kamutotems.core.Polarity;

/**
 * The ticked runtime for auras. Tracks Focus and Momentum meters, schedules
 * Bloom pulses and telegraphs, and triggers Rebuke on the bearer being hurt.
 *
 * <p>One pipeline for every bearer (PLAN_COMBAT §2.1, the admission law): a
 * player's aura spec is read from their held totem, a boss's from
 * {@link Boss#aura()} via {@link BossHost#byEntity}, and from there every
 * tick, telegraph, pulse and Rebuke trigger runs through the same code,
 * keyed by {@link LivingEntity#getUUID()} regardless of which kind of bearer
 * it is. {@link #applyHeld} / {@link #applyPulse} stay public per the
 * cross-agent interface, but nothing here still requires an outside caller
 * to drive a boss's aura -- {@link #tick} finds every active boss itself.
 */
public final class AuraHost {

    /** charge, pulse timers and rebuke cooldown per bearer. */
    private static final Map<UUID, AuraState> states = new HashMap<>();

    private AuraHost() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(AuraHost::tick);
        ServerLivingEntityEvents.AFTER_DAMAGE.register(AuraHost::onHurt);
    }

    /**
     * A continuous, self-targeted, unscaled-by-radius application. Nothing
     * in this design currently uses it -- Focus and Momentum release a
     * {@link #applyPulse} on full charge rather than dripping per tick
     * (PLAN_COMBAT L26), and Rebuke is reactive, not ticked. Kept public per
     * the cross-agent interface (§4) for a future aura kind that genuinely
     * wants a steady self-only effect rather than a pulse.
     */
    public static void applyHeld(ServerLevel level, LivingEntity bearer,
                                 AuraOutcome outcome) {
        if (level == null || bearer == null || outcome == null || !outcome.valid()) {
            return;
        }
        double charge = chargeFor(bearer);
        applyTo(level, bearer, bearer, outcome, charge, false);
    }

    /**
     * One Bloom pulse payoff. The wind-up has already fired.
     */
    public static void applyPulse(ServerLevel level, LivingEntity bearer,
                                  AuraOutcome outcome, double radius) {
        if (level == null || bearer == null || outcome == null || !outcome.valid()) {
            return;
        }
        Kamu kamu = KamuData.catalog().get(outcome.effectId());
        if (kamu == null) {
            KamuTotemsMod.LOG.warn("Unknown aura modifier effect id '{}'", outcome.effectId());
            return;
        }

        for (LivingEntity near : level.getEntitiesOfClass(LivingEntity.class,
                boxAround(bearer, radius))) {
            if (!near.isAlive()) {
                continue;
            }

            boolean boon = false;
            boolean apply = false;

            Polarity applied = outcome.applied();
            if (applied == Polarity.BOON) {
                // Allies and self.
                if (near == bearer || isAllied(bearer, near)) {
                    apply = true;
                    boon = true;
                }
            } else if (applied == Polarity.BANE) {
                // Enemies, not self.
                if (near != bearer && !isAllied(bearer, near)) {
                    apply = true;
                    boon = false;
                }
            } else if (applied == Polarity.DUAL) {
                // Both halves: allies get the boon, enemies get the bane.
                if (near == bearer || isAllied(bearer, near)) {
                    apply = true;
                    boon = true;
                } else {
                    apply = true;
                    boon = false;
                }
            }

            if (apply) {
                applyTo(level, bearer, near, outcome, 1.0, boon, false);
            }
        }

        // Schedule the next pulse for a player.
        AuraState state = states.get(bearer.getUUID());
        if (state != null && outcome.pulses()) {
            state.nextPulseTick = tickNow(level.getServer())
                    + AuraKind.BLOOM.pulseIntervalTicks(state.spec.tier());
            state.nextTelegraphTick = state.nextPulseTick
                    - telegraphTicks();
        }
    }

    /** Advances aura timers and meters for every online player and tracked boss. */
    private static void tick(MinecraftServer server) {
        int now = server.getTickCount();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            tickBearer(player, specFor(player), now);
        }

        for (Boss boss : BossHost.activeBosses()) {
            Entity entity = boss.entity();
            if (!(entity instanceof LivingEntity bearer) || !entity.isAlive()) {
                if (entity != null) {
                    states.remove(entity.getUUID());
                }
                continue;
            }
            tickBearer(bearer, specFor(bearer), now);
        }
    }

    private static void tickBearer(LivingEntity bearer, AuraSpec spec, int now) {
        if (spec == null || !spec.isPresent()) {
            states.remove(bearer.getUUID());
            return;
        }
        if (!(bearer.level() instanceof ServerLevel level)) {
            return;
        }

        AuraOutcome outcome = AuraResolver.resolve(spec, KamuData.catalog());
        if (!outcome.valid()) {
            // A runtime refusal should not spam chat; the panel (or the
            // boss's own roll) already refused when the kamu was placed.
            // Just drop the tick.
            return;
        }

        AuraState state = states.computeIfAbsent(bearer.getUUID(),
                u -> new AuraState(spec, outcome));
        state.spec = spec;
        state.outcome = outcome;

        AuraKind kind = spec.kind();
        if (kind.pulses()) {
            tickBloom(bearer, level, outcome, state, now);
        } else if (kind.isMetered()) {
            tickMetered(bearer, level, outcome, state, now);
        }
        // REBUKE is reactive; nothing continuous here.
    }

    private static void tickBloom(LivingEntity bearer, ServerLevel level,
                                  AuraOutcome outcome, AuraState state, int now) {
        if (state.nextPulseTick == Integer.MAX_VALUE) {
            int interval = AuraKind.BLOOM.pulseIntervalTicks(state.spec.tier());
            state.nextPulseTick = now + interval;
            state.nextTelegraphTick = now + Math.max(1, interval - telegraphTicks());
        }

        if (now >= state.nextTelegraphTick && !state.telegraphFiredForThisPulse) {
            AuraTelegraph.telegraph(level, bearer, outcome, bloomRadius());
            state.telegraphFiredForThisPulse = true;
        }

        if (now >= state.nextPulseTick) {
            applyPulse(level, bearer, outcome, bloomRadius());
            state.telegraphFiredForThisPulse = false;
        }
    }

    /**
     * Focus and Momentum are the same mechanic as Bloom with an inverted
     * release clause: instead of a fixed interval, the meter charges while
     * still (Focus) or while moving (Momentum), and at full charge it
     * releases -- one pulse, radius and polarity handled by {@link
     * #applyPulse} exactly as Bloom's is, boon on you and allies, bane on
     * whatever else is in range. No special case for either reading:
     * whatever the modifier resolves to is what releases. Draining below
     * full before release forfeits the charge entirely; that is the risk
     * the meter asks the player to take (PLAN_COMBAT L11).
     */
    private static void tickMetered(LivingEntity bearer, ServerLevel level,
                                    AuraOutcome outcome, AuraState state, int now) {
        boolean moving = isMoving(bearer);
        boolean charges = false;

        AuraKind kind = state.spec.kind();
        if (kind == AuraKind.FOCUS) {
            charges = !moving;
        } else if (kind == AuraKind.MOMENTUM) {
            charges = moving;
        }

        double fill = 1.0 / KamuTotemsConfig.i("combat", "meter_fill_ticks", 100);
        double drain = 1.0 / KamuTotemsConfig.i("combat", "meter_drain_ticks", 40);

        if (charges) {
            state.charge = Math.min(1.0, state.charge + fill);
        } else {
            state.charge = Math.max(0.0, state.charge - drain);
        }

        if (state.charge >= 1.0) {
            AuraTelegraph.telegraph(level, bearer, outcome, bloomRadius());
            applyPulse(level, bearer, outcome, bloomRadius());
            state.charge = 0.0;
        }
    }

    /** Applies a ready Rebuke aura to the attacker after its bearer takes damage. */
    private static void onHurt(LivingEntity victim, DamageSource source,
                               float baseDamageTaken, float newDamageTaken,
                               boolean blocked) {
        if (newDamageTaken <= 0.0f || victim == null || source == null) {
            return;
        }
        if (!(victim.level() instanceof ServerLevel level)) {
            return;
        }

        // One aura pipeline for every bearer (PLAN_COMBAT §2.1): a player's
        // spec lives on their totem, a boss's on BossHost.byEntity(...).
        AuraSpec spec = specFor(victim);
        if (spec == null || !spec.isPresent()) {
            return;
        }

        AuraOutcome outcome = AuraResolver.resolve(spec, KamuData.catalog());
        if (!outcome.valid() || spec.kind() != AuraKind.REBUKE) {
            return;
        }

        AuraState state = states.computeIfAbsent(victim.getUUID(),
                u -> new AuraState(spec, outcome));
        int now = tickNow(level.getServer());
        int cooldown = KamuTotemsConfig.i("combat", "rebuke_cooldown_ticks", 20);
        if (now - state.lastRebukeTick < cooldown) {
            return;
        }
        state.lastRebukeTick = now;

        Entity attacker = source.getEntity();
        LivingEntity target = attacker instanceof LivingEntity l ? l : null;
        boolean boon = false;

        Polarity applied = outcome.applied();
        if (applied == Polarity.BOON) {
            // Self-benefiting: Heal / Absorption on being hit.
            target = victim;
            boon = true;
        } else if (applied == Polarity.BANE) {
            // Offensive: the attacker takes it.
            boon = false;
        } else if (applied == Polarity.DUAL) {
            // For Rebuke, only the hostile half fires.
            boon = false;
        }

        if (target == null) {
            return;
        }

        // Rebuke is a burst, not a HoT: heal should land immediately.
        applyTo(level, victim, target, outcome, 1.0, boon, "heal".equals(outcome.effectId()));
    }

    /** A bearer's current AuraSpec: a player's held totem, or a boss's roll. */
    private static AuraSpec specFor(LivingEntity bearer) {
        if (bearer instanceof ServerPlayer player) {
            ItemStack totem = Totem.find(player);
            if (totem == null || Totem.chargesRemaining(totem) <= 0) {
                return null;
            }
            return Totem.auraSpec(totem);
        }
        Boss boss = BossHost.byEntity(bearer.getUUID());
        return boss == null ? null : boss.aura();
    }

    /**
     * Shared effect builder. {@code charge} scales the numeric parameters
     * (except amplifier). {@code boon} controls which reading of dual
     * elements fires. {@code instant} only matters for {@code heal}.
     */
    private static void applyTo(ServerLevel level, LivingEntity source, LivingEntity target,
                                AuraOutcome outcome, double charge, boolean boon,
                                boolean instant) {
        Kamu kamu = KamuData.catalog().get(outcome.effectId());
        if (kamu == null) {
            KamuTotemsMod.LOG.warn("Unknown aura effect id '{}'", outcome.effectId());
            return;
        }

        Map<String, Double> p = new HashMap<>();
        if (kamu.parameters() != null) {
            for (Map.Entry<String, Double> e : kamu.parameters().entrySet()) {
                String key = e.getKey();
                double v = e.getValue();
                if ("amplifier".equals(key)) {
                    p.put(key, v);
                } else {
                    p.put(key, v * outcome.magnitude() * charge);
                }
            }
        }

        p.put("aura", 1.0);
        p.put(boon ? "boon" : "bane", 1.0);
        if (instant) {
            p.put("instant", 1.0);
        }

        Effect effect = new Effect(kamu.effectId(),
                target != null ? target.getUUID().toString() : null, p);
        EffectsMc.apply(List.of(effect), level, source, target);
    }

    private static void applyTo(ServerLevel level, LivingEntity source, LivingEntity target,
                                AuraOutcome outcome, double charge, boolean instant) {
        applyTo(level, source, target, outcome, charge, true, instant);
    }

    private static double chargeFor(LivingEntity bearer) {
        AuraState state = states.get(bearer.getUUID());
        return state == null ? 1.0 : state.charge;
    }

    private static boolean isMoving(LivingEntity bearer) {
        // ⚠ UNVERIFIED: getDeltaMovement() / horizontalDistanceSqr() in 26.2
        Vec3 d = bearer.getDeltaMovement();
        double threshold = KamuTotemsConfig.d("combat", "movement_threshold", 0.001);
        return d.horizontalDistanceSqr() > threshold;
    }

    private static boolean isAllied(LivingEntity source, Entity target) {
        // ⚠ UNVERIFIED: Entity#isAlliedTo(Entity) in 26.2
        if (target == source) {
            return true;
        }
        return source.isAlliedTo(target);
    }

    private static AABB boxAround(Entity centre, double r) {
        return centre.getBoundingBox().inflate(r);
    }

    private static double bloomRadius() {
        return KamuTotemsConfig.d("combat", "bloom_radius", 3.5);
    }

    private static int telegraphTicks() {
        return KamuTotemsConfig.i("combat", "bloom_telegraph_ticks", 30);
    }

    private static int tickNow(MinecraftServer server) {
        // ⚠ UNVERIFIED: MinecraftServer#getTickCount() in 26.2
        return server == null ? 0 : server.getTickCount();
    }

    private static final class AuraState {
        AuraSpec spec;
        AuraOutcome outcome;
        double charge;
        int nextPulseTick;
        int nextTelegraphTick;
        int lastRebukeTick;
        boolean telegraphFiredForThisPulse;

        AuraState(AuraSpec spec, AuraOutcome outcome) {
            this.spec = spec;
            this.outcome = outcome;
            this.charge = 0.0;
            this.nextPulseTick = Integer.MAX_VALUE;
            this.nextTelegraphTick = Integer.MAX_VALUE;
            this.lastRebukeTick = Integer.MIN_VALUE;
        }
    }
}
