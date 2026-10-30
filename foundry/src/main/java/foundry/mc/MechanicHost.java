package foundry.mc;

import foundry.core.Ident;
import foundry.core.Problem;
import foundry.core.ContentSet;
import foundry.core.mechanics.MechanicDef;
import foundry.core.mechanics.MechanicRunner;
import foundry.core.mechanics.Step;
import foundry.core.mechanics.Trigger;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Carries out validated mechanic steps. {@code target "self"} (the default) is the acting player;
 * {@code target "hit"} is the entity an on_hit_entity mechanic struck, and is null for every other trigger.
 * Server thread only.
 */
public final class MechanicHost implements MechanicRunner.Host {
    private static final MechanicRunner RUNNER = new MechanicRunner();

    /**
     * Decides whether an entity holds a permission node (an Identifier such as {@code race:fairy}) through vanilla's
     * permission set. A test or another mod's permission source may replace it; Foundry never names a node itself.
     */
    public static java.util.function.BiPredicate<LivingEntity, Identifier> permissionCheck = (entity, node) ->
            entity instanceof ServerPlayer p && p.permissions().hasPermission(
                    net.minecraft.server.permissions.Permission.Atom.create(node));

    private final ServerPlayer player;
    private final LivingEntity hit;
    private final String contentId;

    private MechanicHost(ServerPlayer player, LivingEntity hit, String contentId) {
        this.player = player;
        this.hit = hit;
        this.contentId = contentId;
    }

    static boolean fire(Trigger trigger, String contentId, List<MechanicDef> mechanics, ServerPlayer player,
            LivingEntity hit) {
        return fire(trigger, "", contentId, mechanics, player, hit);
    }

    /** Fires every mechanic of {@code trigger} (for on_bolt_hit, only those with {@code tag}). True when one ran. */
    static boolean fire(Trigger trigger, String tag, String contentId, List<MechanicDef> mechanics, ServerPlayer player,
            LivingEntity hit) {
        boolean any = false;
        MechanicHost host = new MechanicHost(player, hit, contentId);
        for (int i = 0; i < mechanics.size(); i++) {
            MechanicDef m = mechanics.get(i);
            if (m.trigger() != trigger) continue;
            if (m.charged()) continue; // fires on release, through fireCharged
            if (trigger == Trigger.ON_BOLT_HIT && !m.tag().equals(tag)) continue;
            var outcome = RUNNER.fire(player.getStringUUID(), contentId, i, m, host);
            if (outcome == MechanicRunner.Outcome.RAN) any = true;
            if (outcome == MechanicRunner.Outcome.ON_COOLDOWN && !m.name().isEmpty()) {
                long left = RUNNER.cooldownLeft(player.getStringUUID(), contentId, i, host.nowTick());
                player.sendSystemMessage(Component.literal(m.name() + " is recharging: " + ((left + 19) / 20) + "s"), true);
            }
            if (outcome == MechanicRunner.Outcome.RATE_LIMITED || outcome == MechanicRunner.Outcome.REENTERED) {
                FoundryMod.LOG.warn("Foundry: mechanic {} #{} refused ({}) for {}.", contentId, i, outcome,
                        player.getName().getString());
            }
        }
        return any;
    }

    /**
     * The index of the first charged on_use mechanic that may start now (conditions hold, off cooldown), or -1. The
     * hold-to-charge start calls this; nothing runs yet.
     */
    static int armedCharge(String contentId, List<MechanicDef> mechanics, ServerPlayer player) {
        MechanicHost host = new MechanicHost(player, null, contentId);
        for (int i = 0; i < mechanics.size(); i++) {
            MechanicDef m = mechanics.get(i);
            if (m.trigger() == Trigger.ON_USE && m.charged()
                    && RUNNER.ready(player.getStringUUID(), contentId, i, m, host)) {
                return i;
            }
        }
        return -1;
    }

    /** Runs the charged mechanic that was armed when the hold began. True when it ran. */
    static boolean fireCharged(String contentId, List<MechanicDef> mechanics, int index, ServerPlayer player) {
        if (index < 0 || index >= mechanics.size()) return false;
        MechanicDef m = mechanics.get(index);
        MechanicHost host = new MechanicHost(player, null, contentId);
        var outcome = RUNNER.fire(player.getStringUUID(), contentId, index, m, host, false);
        return outcome == MechanicRunner.Outcome.RAN;
    }

    @Override
    public long nowTick() {
        return player.level().getServer().getTickCount();
    }

    @Override
    public boolean holds(Step condition) {
        return switch (condition.type()) {
            case "has_permission" -> hasPermission(condition.intParam("level", 4),
                    "hit".equals(condition.param("target", "self")));
            case "is_sneaking" -> player.isShiftKeyDown() == Boolean.parseBoolean(condition.param("value", "true"));
            default -> false;
        };
    }

    private boolean hasPermission(int level, boolean onHit) {
        if (level <= 0) return true;
        var who = onHit ? hit : player;
        return who instanceof ServerPlayer p && p.permissions().hasPermission(
                new net.minecraft.server.permissions.Permission.HasCommandLevel(
                        net.minecraft.server.permissions.PermissionLevel.byId(level)));
    }

    /** True when {@code entity} passes the step's require_permission and exclude_permission filters. */
    public static boolean passesFilters(Step step, LivingEntity entity) {
        String require = step.param("require_permission", "");
        String exclude = step.param("exclude_permission", "");
        if (!require.isEmpty() && !permissionCheck.test(entity, Identifier.parse(require))) return false;
        return exclude.isEmpty() || !permissionCheck.test(entity, Identifier.parse(exclude));
    }

    @Override
    public boolean roll(double probability) {
        return player.getRandom().nextDouble() < probability;
    }

    /** The entity a step acts on: the hit entity for {@code target "hit"}, the acting player otherwise. */
    private LivingEntity targetOf(Step step) {
        return "hit".equals(step.param("target", "self")) && hit != null ? hit : player;
    }

    @Override
    public void perform(Step action) {
        ServerLevel level = player.level();
        LivingEntity target = targetOf(action);
        switch (action.type()) {
            case "play_sound" -> {
                var sound = BuiltInRegistries.SOUND_EVENT.getValue(Identifier.parse(action.param("sound", "")));
                if (sound != null) {
                    level.playSound(null, target.getX(), target.getY(), target.getZ(), sound, SoundSource.PLAYERS,
                            (float) action.decimalParam("volume", 1.0), (float) action.decimalParam("pitch", 1.0));
                }
            }
            case "apply_effect" -> BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(action.param("effect", "")))
                    .ifPresent(effect -> {
                        var instance = new MobEffectInstance(effect, action.intParam("duration", 100),
                                action.intParam("amplifier", 0));
                        if ("area".equals(action.param("target", "self"))) {
                            double r = action.decimalParam("radius", 1);
                            // Players only, the caster included when the filters allow it.
                            for (ServerPlayer p : level.getEntitiesOfClass(ServerPlayer.class,
                                    player.getBoundingBox().inflate(r), p -> p.isAlive() && p.distanceToSqr(player) <= r * r)) {
                                if (passesFilters(action, p)) p.addEffect(new MobEffectInstance(instance));
                            }
                        } else {
                            target.addEffect(instance);
                        }
                    });
            case "damage" -> {
                // A hit that follows a melee swing lands inside the target's hit window, where vanilla only applies
                // the amount above the last hit. A bonus that must land in full asks to clear the window first.
                if (Boolean.parseBoolean(action.param("ignore_hit_delay", "false"))) target.damageCooldownTime = 0;
                target.hurtServer(level, level.damageSources().indirectMagic(player, player), (float) action.decimalParam("amount", 1));
            }
            case "spawn_particles" -> {
                var type = BuiltInRegistries.PARTICLE_TYPE.getValue(Identifier.parse(action.param("particle", "")));
                if (type instanceof net.minecraft.core.particles.SimpleParticleType simple) {
                    double spread = action.decimalParam("spread", 0.3);
                    level.sendParticles(simple, target.getX(), target.getY() + target.getBbHeight() / 2, target.getZ(),
                            action.intParam("count", 8), spread, spread, spread, action.decimalParam("speed", 0.02));
                }
            }
            case "send_message" -> {
                if (target instanceof ServerPlayer p) p.sendSystemMessage(Component.literal(action.param("message", "")), true);
            }
            case "launch_bolt" -> Bolts.launch(player, contentId, action);
            case "give_item" -> {
                if (target instanceof Player p) {
                    var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(action.param("item", "")));
                    p.getInventory().placeItemBackInInventory(new ItemStack(item, action.intParam("count", 1)), net.minecraft.util.Prediction.SERVER_ONLY);
                } else {
                    FoundryMod.LOG.warn("Foundry: give_item skipped; the target is not a player.");
                }
            }
            default -> FoundryMod.LOG.warn("Foundry: no handler for action type {}; the registry and host are out of step.",
                    action.type());
        }
    }

    /** Adds an error for every sound, effect or item id a mechanic names that Minecraft does not have. */
    static ContentSet checkReferences(ContentSet set) {
        List<Problem> problems = new ArrayList<>(set.problems());
        set.items().values().forEach(def -> {
            for (MechanicDef m : def.mechanics()) {
                for (Step a : m.actions()) {
                    switch (a.type()) {
                        case "play_sound" -> missing(problems, def.id(), "sound", a.param("sound", ""),
                                BuiltInRegistries.SOUND_EVENT.containsKey(Identifier.parse(a.param("sound", ""))));
                        case "apply_effect" -> missing(problems, def.id(), "effect", a.param("effect", ""),
                                BuiltInRegistries.MOB_EFFECT.containsKey(Identifier.parse(a.param("effect", ""))));
                        case "give_item" -> missing(problems, def.id(), "item", a.param("item", ""),
                                BuiltInRegistries.ITEM.containsKey(Identifier.parse(a.param("item", ""))));
                        case "spawn_particles" -> particle(problems, def.id(), a.param("particle", ""));
                        case "launch_bolt" -> {
                            if (a.params().containsKey("particle")) particle(problems, def.id(), a.param("particle", ""));
                        }
                        default -> { }
                    }
                }
            }
        });
        java.util.Set<String> bad = new java.util.HashSet<>();
        problems.stream().skip(set.problems().size()).filter(Problem::blocking).forEach(p -> bad.add(p.content()));
        var kept = new java.util.LinkedHashMap<>(set.items());
        kept.keySet().removeIf(id -> bad.contains(id.toString()));
        var sources = new java.util.LinkedHashMap<>(set.sources());
        sources.keySet().removeIf(id -> bad.contains(id.toString()));
        var furniture = new java.util.LinkedHashMap<>(set.furniture());
        furniture.values().removeIf(f -> !kept.containsKey(f.placedBy()));
        return new ContentSet(java.util.Map.copyOf(kept), set.recipes(), List.copyOf(problems),
                java.util.Map.copyOf(sources), java.util.Map.copyOf(furniture));
    }

    private static void particle(List<Problem> problems, Ident id, String value) {
        var type = BuiltInRegistries.PARTICLE_TYPE.getValue(Identifier.parse(value));
        if (!(type instanceof net.minecraft.core.particles.SimpleParticleType)) {
            problems.add(Problem.error(id.toString(), "mechanics", "There is no plain particle called " + value + ".",
                    "Use a particle that needs no options, such as minecraft:end_rod, minecraft:happy_villager or minecraft:heart."));
        }
    }

    private static void missing(List<Problem> problems, Ident id, String what, String value, boolean exists) {
        if (!exists) {
            problems.add(Problem.error(id.toString(), "mechanics", "There is no " + what + " called " + value + ".",
                    "Check the spelling; use /give or the wiki for real ids."));
        }
    }
}
