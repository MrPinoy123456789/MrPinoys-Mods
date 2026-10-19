package pocketdungeons;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

/**
 * The phased-boss spine (boss workshop, 2026-10-16). A finale elite's health is cut into
 * phases by fraction bounds; crossing a bound staggers the boss (a few seconds invulnerable
 * and mindless, with a title and a sound) and arms the next phase when the stagger ends.
 * Phases advance one at a time: a burst that carries the boss under two bounds still runs
 * both mechanics back to back rather than skipping one. A script keeps a BossFight in its
 * {@code scriptState} and drives it from {@code onTick}; the bound check is a poll between
 * i-frames, so no single hit can kill through a bound the poll has not seen.
 */
final class BossFight {
    /** One phase of a boss fight. */
    interface Phase {
        /** The elite's health fraction at or below which this phase begins; the opening phase uses 1.0. */
        double bound();
        /** Title lines shown through the stagger into this phase; empty keeps the transition quiet. */
        List<String> lines();
        /** Runs once when the stagger ends and the phase begins. */
        void enter(FinaleContext ctx, Mob boss);
    }

    /** A phase as data: bound, tell lines, and the one-shot arm. */
    record Step(double bound, List<String> lines, BiConsumer<FinaleContext, Mob> arm) implements Phase {
        @Override
        public void enter(FinaleContext ctx, Mob boss) {
            arm.accept(ctx, boss);
        }
    }

    private final List<Phase> phases;
    private int phase;
    private boolean started;
    /** The server tick the current stagger ends, or -1 while the boss fights. Tests may pin it to zero. */
    long staggerEnd = -1;
    /** The boss's health at the last poll; a clamped death never heals it above this. */
    private float lastHealth = Float.MAX_VALUE;

    /** Live fights by boss entity, for the death clamp. Weak, so a discarded boss frees its fight. */
    private static final Map<Entity, BossFight> FIGHTS = Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean hooked;

    /**
     * Lazily hooks the death clamp, once. A killing blow on a boss with a phase still to come does not kill: the
     * death is cancelled and the boss is left at the next bound (never above the health it had at the last
     * poll), so the poll sees the bound and runs the phase. Burst damage can fast-forward a phase, never skip one.
     */
    private static void hook() {
        if (hooked) {
            return;
        }
        hooked = true;
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            BossFight fight = FIGHTS.get(entity);
            return fight == null || !fight.clamp(entity);
        });
    }

    /** Cancels the death by pinning health at the next bound; true when it did (a phase is still to come). */
    private boolean clamp(LivingEntity boss) {
        if (phase + 1 >= phases.size()) {
            return false;
        }
        float bound = boss.getMaxHealth() * (float) phases.get(phase + 1).bound();
        boss.setHealth(Math.max(1.0f, Math.min(bound, lastHealth)));
        return true;
    }

    /** Whether a stagger is running (the boss is invulnerable and mindless). */
    boolean staggering() {
        return staggerEnd >= 0;
    }

    BossFight(List<? extends Phase> phases) {
        if (phases.isEmpty()) {
            throw new IllegalArgumentException("a boss fight needs at least one phase");
        }
        for (int i = 1; i < phases.size(); i++) {
            if (phases.get(i).bound() <= 0.0 || phases.get(i).bound() >= phases.get(i - 1).bound()) {
                throw new IllegalArgumentException("bounds must fall inside (0, 1) and descend");
            }
        }
        this.phases = List.copyOf(phases);
    }

    /** The phase the fight is in; a bound crossed mid-tick counts even while the stagger runs. */
    int phase() {
        return phase;
    }

    /** One watch tick: arms the opening phase, runs a pending stagger, checks the next bound. */
    void tick(FinaleContext ctx) {
        if (!(ctx.elite() instanceof Mob boss) || !boss.isAlive()) {
            return;
        }
        hook();
        FIGHTS.put(boss, this);
        lastHealth = boss.getHealth();
        if (!started) {
            started = true;
            phases.get(0).enter(ctx, boss);
        }
        if (staggerEnd >= 0) {
            if (ctx.tickCount() < staggerEnd) {
                return;
            }
            staggerEnd = -1;
            boss.setInvulnerable(false);
            boss.setNoAi(false);
            phases.get(phase).enter(ctx, boss);
            return;
        }
        if (phase + 1 >= phases.size()
                || boss.getHealth() > boss.getMaxHealth() * (float) phases.get(phase + 1).bound()) {
            return;
        }
        phase++;
        staggerEnd = ctx.tickCount() + PocketDungeonsConfig.bossPhaseStaggerSeconds() * 20L;
        boss.setInvulnerable(true);
        boss.setNoAi(true);
        List<String> lines = phases.get(phase).lines();
        if (!lines.isEmpty()) {
            for (ServerPlayer member : ctx.members()) {
                StaggeredTitle.show(ctx.server(), member.getUUID(),
                        Component.literal(lines.get(0)).withStyle(ChatFormatting.GOLD),
                        lines.subList(1, lines.size()), ChatFormatting.GRAY);
            }
        }
        ctx.level().sendParticles(ParticleTypes.CRIT, boss.getX(), boss.getY() + boss.getBbHeight() * 0.5,
                boss.getZ(), 40, 0.6, 0.6, 0.6, 0.3);
        ctx.level().playSound(null, boss.blockPosition(), SoundEvents.RAVAGER_STUNNED,
                SoundSource.HOSTILE, 1.0f, 1.0f);
    }
}
