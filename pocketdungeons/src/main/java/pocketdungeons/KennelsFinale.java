package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.TeamColor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Kennels finale (roadmap T22, boss workshop 2026-10-11 row 4, decision 4b), on the phased spine (roadmap
 * T03). The Alpha is the boss of a {@link BossFight}; the wave's pillager is the handler. Phase two (the Alpha
 * at two thirds): the handler raises its arm and a horn sounds. Phase three (one third): once the windup has
 * run its five seconds the wolves of the pack grow and turn angry, glowing red, if the handler lived. Killing
 * the handler before that cancels it; killing it afterwards shrinks the pack back. If the handler is already
 * dead when phase two begins, nothing happens.
 * The decisions are in {@link KennelsFinaleRules}; this class only does them to the world.
 *
 * <p>What the world holds, and where it is undone: the wolves' scale (and, if the knob is on, maximum health)
 * and glow tag, and one scoreboard team per run slot. The team goes when the handler dies after the pack grew,
 * when the floor ends or is torn down ({@link #onFloorEnded}), when the finale is won, and for any that a
 * stopping server leaves behind (a stop hook, registered the first time a team is made).
 *
 * <p>The handler gets its name here: the data wave ({@code DungeonDef.FinaleMob}) has no name field. It is
 * tagged as a finale mob by {@link FinaleWave}, which keeps it out of Restless.
 */
final class KennelsFinale implements FinaleScript {

    /** The handler's name. */
    static final String HANDLER_NAME = "The Handler";
    /** Louder than a mob, quieter than a raid: the party hears it from across the den. */
    private static final float HORN_VOLUME = 8.0f;

    /** The Alpha's phases. One: the baseline fight. Two: the handler's windup. Three: the pack answers. */
    private static final List<BossFight.Step> PHASES = List.of(
            new BossFight.Step(1.0, List.of(), (ctx, boss) -> {
            }),
            new BossFight.Step(2.0 / 3.0, List.of("The Alpha staggers.", "The handler raises his arm."),
                    (ctx, boss) -> ctx.scriptState(Run.class, Run::new).windupRequested = true),
            new BossFight.Step(1.0 / 3.0, List.of("The Alpha is cornered.", "The pack answers."),
                    (ctx, boss) -> ctx.scriptState(Run.class, Run::new).empowerRequested = true));

    /** Set once, when the first team is made, so a pure test that builds the script never loads Fabric events. */
    private static boolean stopHooked;

    /** One run's state (package-visible so the gametest can move the windup clock). */
    static final class Run {
        KennelsFinaleRules.Phase phase = KennelsFinaleRules.Phase.WAITING;
        long windupEnd;
        long nextAnger;
        UUID handler;
        /** Per wolf: scale and maximum health before the pack grew. */
        final Map<UUID, double[]> originals = new HashMap<>();
        boolean teamMade;
        /** Phase two began: the handler may start his windup. */
        boolean windupRequested;
        /** Phase three began: the pack may grow once the windup has run. */
        boolean empowerRequested;
    }

    @Override
    public void onWaveSpawned(FinaleContext ctx) {
        Run run = ctx.scriptState(Run.class, Run::new);
        for (Mob mob : ctx.liveMobs()) {
            if (mob instanceof Pillager pillager) {
                run.handler = pillager.getUUID();
                pillager.setCustomName(Component.literal(HANDLER_NAME).withStyle(ChatFormatting.RED));
                pillager.setCustomNameVisible(true);
                break;
            }
        }
    }

    @Override
    public void onTick(FinaleContext ctx) {
        Run run = ctx.scriptState(Run.class, Run::new);
        ctx.scriptState(BossFight.class, () -> new BossFight(PHASES)).tick(ctx);
        Pillager handler = handlerOf(ctx, run);
        KennelsFinaleRules.Step step = KennelsFinaleRules.phasedStep(run.phase, ctx.tickCount(), run.windupEnd,
                handler != null, run.windupRequested, run.empowerRequested);
        run.phase = step.phase();
        switch (step.action()) {
            case START_WINDUP -> startWindup(ctx, run, handler);
            case EMPOWER -> empower(ctx, run, handler);
            case CANCEL -> overlay(ctx, "The handler falls. The pack stays small.", ChatFormatting.GREEN);
            case SHRINK -> {
                shrink(ctx, run);
                removeTeam(ctx.server(), ctx.record().slot);
                overlay(ctx, "The handler falls. The pack shrinks.", ChatFormatting.GREEN);
            }
            default -> { }
        }
        if (run.phase == KennelsFinaleRules.Phase.WINDUP && handler != null) {
            handler.setCelebrating(true);
        } else if (run.phase == KennelsFinaleRules.Phase.EMPOWERED && ctx.tickCount() >= run.nextAnger) {
            run.nextAnger = ctx.tickCount() + KennelsFinaleRules.ANGER_REFRESH_TICKS;
            for (Mob mob : ctx.liveMobs()) {
                if (mob instanceof Wolf wolf && run.originals.containsKey(wolf.getUUID())) {
                    anger(ctx, wolf);
                }
            }
        }
    }

    @Override
    public void onWon(FinaleContext ctx) {
        removeTeam(ctx.server(), ctx.record().slot);
    }

    @Override
    public void onFloorEnded(FinaleContext ctx) {
        removeTeam(ctx.server(), ctx.record().slot);
    }

    // ---- the beats ---------------------------------------------------------------------------------

    private static void startWindup(FinaleContext ctx, Run run, Pillager handler) {
        run.windupEnd = ctx.tickCount() + KennelsFinaleRules.WINDUP_TICKS;
        handler.setCelebrating(true);
        ctx.level().playSound(null, handler.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE,
                HORN_VOLUME, 1.0f);
        overlay(ctx, "Kill the handler!", ChatFormatting.RED);
    }

    private static void empower(FinaleContext ctx, Run run, Pillager handler) {
        handler.setCelebrating(false);
        PlayerTeam team = packTeam(ctx.server(), ctx.record().slot);
        run.teamMade = true;
        for (Mob mob : ctx.liveMobs()) {
            if (!(mob instanceof Wolf wolf)) {
                continue;
            }
            AttributeInstance scale = wolf.getAttribute(Attributes.SCALE);
            AttributeInstance health = wolf.getAttribute(Attributes.MAX_HEALTH);
            double oldScale = scale == null ? 1.0 : scale.getBaseValue();
            double oldMax = health == null ? wolf.getMaxHealth() : health.getBaseValue();
            run.originals.put(wolf.getUUID(), new double[] {oldScale, oldMax});
            if (scale != null) {
                scale.setBaseValue(KennelsFinaleRules.empoweredScale(oldScale));
            }
            if (health != null && KennelsFinaleRules.PACK_HEALTH_BONUS_PERCENT > 0) {
                double newMax = KennelsFinaleRules.empoweredMaxHealth(oldMax,
                        KennelsFinaleRules.PACK_HEALTH_BONUS_PERCENT);
                health.setBaseValue(newMax);
                wolf.setHealth((float) KennelsFinaleRules.healthAfterGrow(wolf.getHealth(), oldMax, newMax));
            }
            ctx.server().getScoreboard().addPlayerToTeam(wolf.getScoreboardName(), team);
            wolf.setGlowingTag(true);
            anger(ctx, wolf);
        }
        run.nextAnger = ctx.tickCount() + KennelsFinaleRules.ANGER_REFRESH_TICKS;
        overlay(ctx, "The pack grows!", ChatFormatting.RED);
    }

    private static void shrink(FinaleContext ctx, Run run) {
        ServerScoreboard scoreboard = ctx.server().getScoreboard();
        for (Map.Entry<UUID, double[]> entry : new ArrayList<>(run.originals.entrySet())) {
            Entity entity = ctx.level().getEntity(entry.getKey());
            if (!(entity instanceof Wolf wolf) || !wolf.isAlive()) {
                continue;
            }
            AttributeInstance scale = wolf.getAttribute(Attributes.SCALE);
            AttributeInstance health = wolf.getAttribute(Attributes.MAX_HEALTH);
            if (scale != null) {
                scale.setBaseValue(entry.getValue()[0]);
            }
            if (health != null && KennelsFinaleRules.PACK_HEALTH_BONUS_PERCENT > 0) {
                health.setBaseValue(entry.getValue()[1]);
                wolf.setHealth((float) KennelsFinaleRules.healthAfterShrink(wolf.getHealth(), entry.getValue()[1]));
            }
            wolf.setGlowingTag(false);
            PlayerTeam team = scoreboard.getPlayersTeam(wolf.getScoreboardName());
            if (team != null && KennelsFinaleRules.isPackTeam(team.getName())) {
                scoreboard.removePlayerFromTeam(wolf.getScoreboardName(), team);
            }
        }
        run.originals.clear();
    }

    /** Angers the wolf at the player it already hunts, else at the nearest member. */
    private static void anger(FinaleContext ctx, Wolf wolf) {
        ServerPlayer target = wolf.getTarget() instanceof ServerPlayer player && player.isAlive() ? player : null;
        if (target == null) {
            double best = Double.MAX_VALUE;
            for (ServerPlayer member : ctx.members()) {
                double d = member.distanceToSqr(wolf);
                if (member.isAlive() && d < best) {
                    best = d;
                    target = member;
                }
            }
        }
        if (target == null) {
            return;
        }
        wolf.setPersistentAngerTarget(EntityReference.of(target));
        wolf.startPersistentAngerTimer();
        wolf.setTarget(target);
    }

    private static Pillager handlerOf(FinaleContext ctx, Run run) {
        Entity entity = run.handler == null ? null : ctx.level().getEntity(run.handler);
        return entity instanceof Pillager pillager && pillager.isAlive() ? pillager : null;
    }

    private static void overlay(FinaleContext ctx, String text, ChatFormatting colour) {
        for (ServerPlayer member : ctx.members()) {
            member.sendOverlayMessage(Component.literal(text).withStyle(colour));
        }
    }

    // ---- the red glow team -------------------------------------------------------------------------

    /** This slot's red team, made if missing; the first call also hooks the server stop. */
    private static PlayerTeam packTeam(MinecraftServer server, int slot) {
        if (!stopHooked) {
            stopHooked = true;
            ServerLifecycleEvents.SERVER_STOPPING.register(KennelsFinale::removeAllTeams);
        }
        ServerScoreboard scoreboard = server.getScoreboard();
        String name = KennelsFinaleRules.teamName(slot);
        PlayerTeam team = scoreboard.getPlayerTeam(name);
        if (team == null) {
            team = scoreboard.addPlayerTeam(name);
            team.setColor(Optional.of(TeamColor.RED));
        }
        return team;
    }

    /** Removes this slot's team, if it exists. */
    static void removeTeam(MinecraftServer server, int slot) {
        ServerScoreboard scoreboard = server.getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(KennelsFinaleRules.teamName(slot));
        if (team != null) {
            scoreboard.removePlayerTeam(team);
        }
    }

    /** Server stop: no pack team survives into the saved scoreboard. */
    private static void removeAllTeams(MinecraftServer server) {
        ServerScoreboard scoreboard = server.getScoreboard();
        List<PlayerTeam> ours = new ArrayList<>();
        for (PlayerTeam team : scoreboard.getPlayerTeams()) {
            if (KennelsFinaleRules.isPackTeam(team.getName())) {
                ours.add(team);
            }
        }
        for (PlayerTeam team : ours) {
            scoreboard.removePlayerTeam(team);
        }
    }
}
