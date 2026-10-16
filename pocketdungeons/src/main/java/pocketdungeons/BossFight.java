package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;

import java.util.List;
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
        ctx.level().playSound(null, boss.blockPosition(), SoundEvents.RAVAGER_STUNNED,
                SoundSource.HOSTILE, 1.0f, 1.0f);
    }
}
