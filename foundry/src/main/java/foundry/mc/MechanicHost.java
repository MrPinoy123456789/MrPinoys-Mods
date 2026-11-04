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
import net.minecraft.world.phys.Vec3;
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
     * Decides whether an entity holds a permission node (an Identifier such as {@code race:fairy}). It asks the Fabric
     * permission API, which a permissions mod or Charter feeds, and falls back to vanilla's permission set. A test may
     * replace it; Foundry never names a node itself.
     */
    public static java.util.function.BiPredicate<LivingEntity, Identifier> permissionCheck = (entity, node) -> {
        if (!(entity instanceof ServerPlayer p)) return false;
        if (((net.fabricmc.fabric.api.permission.v1.PermissionContextOwner) p).checkPermission(node, false)) return true;
        return p.permissions().hasPermission(net.minecraft.server.permissions.Permission.Atom.create(node));
    };

    private final ServerPlayer player;
    private final LivingEntity hit;
    /** Where an on_bolt_hit bolt landed (the entity struck, or the block or the end of its flight), or null. */
    private Vec3 impact;
    private final String contentId;
    /** The mechanic being run and its index in the item's list (a parry remembers which recharge to give back). */
    private MechanicDef current;
    private int index = -1;
    /** How charged a released hold was, 0 to 1; 1 for everything that is not a part charge. */
    private double charge = 1;

    private MechanicHost(ServerPlayer player, LivingEntity hit, String contentId) {
        this.player = player;
        this.hit = hit;
        this.contentId = contentId;
    }

    static boolean fire(Trigger trigger, String contentId, List<MechanicDef> mechanics, ServerPlayer player,
            LivingEntity hit) {
        return fire(trigger, "", contentId, mechanics, player, hit);
    }

    /**
     * A right click: as {@link #fire(Trigger, String, List, ServerPlayer, LivingEntity)}, but a power with a parry runs
     * only on a fresh press, never from a held click repeating (see Charging.press).
     */
    static boolean fire(Trigger trigger, String contentId, List<MechanicDef> mechanics, ServerPlayer player,
            LivingEntity hit, boolean fresh) {
        if (fresh) return fire(trigger, "", contentId, mechanics, player, hit);
        List<MechanicDef> kept = new ArrayList<>();
        for (MechanicDef m : mechanics) {
            boolean parries = m.actions().stream().anyMatch(a -> a.type().equals("parry"));
            // keep the indexes (cooldowns are keyed by them): a power left out becomes one this trigger never runs
            kept.add(parries ? new MechanicDef(Trigger.WHILE_WORN, m.conditions(), m.actions(), m.cooldownTicks(),
                    m.probability(), m.name(), m.tag(), m.chargeTicks(), m.minChargeTicks()) : m);
        }
        return fire(trigger, "", contentId, kept, player, hit);
    }

    /** Fires every mechanic of {@code trigger} (for on_bolt_hit, only those with {@code tag}). True when one ran. */
    static boolean fire(Trigger trigger, String tag, String contentId, List<MechanicDef> mechanics, ServerPlayer player,
            LivingEntity hit) {
        return fire(trigger, tag, contentId, mechanics, player, hit, null);
    }

    /**
     * A bolt landing: as above, with the spot it landed for target "impact". {@code hit} is null when it burst on a
     * block or at the end of its flight; steps aimed at the hit entity are then skipped.
     */
    static boolean fire(Trigger trigger, String tag, String contentId, List<MechanicDef> mechanics, ServerPlayer player,
            LivingEntity hit, Vec3 impact) {
        boolean any = false;
        MechanicHost host = new MechanicHost(player, hit, contentId);
        host.impact = impact;
        for (int i = 0; i < mechanics.size(); i++) {
            MechanicDef m = mechanics.get(i);
            if (m.trigger() != trigger) continue;
            if (m.charged()) continue; // fires on release, through fireCharged
            if (trigger == Trigger.ON_BOLT_HIT && !m.tag().equals(tag)) continue;
            host.current = m;
            host.index = i;
            var outcome = RUNNER.fire(player.getStringUUID(), contentId, i, m, host);
            if (outcome == MechanicRunner.Outcome.RAN) {
                any = true;
                AbilityBar.started(player, contentId, i, m);
            }
            // A press on recharge answers with the ability line (what is ready, and when the rest is back).
            if (outcome == MechanicRunner.Outcome.ON_COOLDOWN && !m.name().isEmpty()) AbilityBar.poke(player);
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

    /** Runs the charged mechanic that was armed when the hold began, at full charge. True when it ran. */
    static boolean fireCharged(String contentId, List<MechanicDef> mechanics, int index, ServerPlayer player) {
        return fireCharged(contentId, mechanics, index, player, 1);
    }

    /** As above, at {@code charge} (0 to 1, see MechanicDef.chargeOf). */
    static boolean fireCharged(String contentId, List<MechanicDef> mechanics, int index, ServerPlayer player, double charge) {
        if (index < 0 || index >= mechanics.size()) return false;
        MechanicDef m = mechanics.get(index);
        MechanicHost host = new MechanicHost(player, null, contentId);
        host.current = m;
        host.index = index;
        host.charge = Math.max(0, Math.min(1, charge));
        var outcome = RUNNER.fire(player.getStringUUID(), contentId, index, m, host, false);
        if (outcome == MechanicRunner.Outcome.RAN) AbilityBar.started(player, contentId, index, m);
        return outcome == MechanicRunner.Outcome.RAN;
    }

    /**
     * A parry opened by mechanic {@code index} landed: gives back {@code ticks} of its recharge and runs the item's
     * on_parry mechanics against {@code attacker} (null when there is nobody to answer). True when one ran.
     */
    static boolean parried(String contentId, List<MechanicDef> mechanics, int index, long ticks, ServerPlayer player,
            LivingEntity attacker) {
        if (ticks > 0) {
            long now = player.level().getServer().getTickCount();
            long readyAt = RUNNER.refund(player.getStringUUID(), contentId, index, ticks, now);
            AbilityBar.refunded(player, contentId, index, readyAt);
        }
        return attacker != null && fire(Trigger.ON_PARRY, contentId, mechanics, player, attacker);
    }

    /** Ticks until this player may use mechanic {@code index} of {@code contentId} again (0 when ready). */
    static long cooldownLeft(String contentId, int index, ServerPlayer player) {
        return RUNNER.cooldownLeft(player.getStringUUID(), contentId, index, player.level().getServer().getTickCount());
    }

    /** True while one of the item's charged right click abilities is recharging for this player. */
    static boolean chargeRecharging(String contentId, List<MechanicDef> mechanics, ServerPlayer player) {
        for (int i = 0; i < mechanics.size(); i++) {
            MechanicDef m = mechanics.get(i);
            if (m.trigger() == Trigger.ON_USE && m.charged() && cooldownLeft(contentId, i, player) > 0) return true;
        }
        return false;
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
            // in water or out in the rain: the Merfolk's weapon is stronger wet
            case "in_water" -> player.isInWaterOrRain() == Boolean.parseBoolean(condition.param("value", "true"));
            case "on_foot" -> Quickdraw.onFoot(player) == Boolean.parseBoolean(condition.param("value", "true"));
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
        if (!exclude.isEmpty() && permissionCheck.test(entity, Identifier.parse(exclude))) return false;
        for (String kind : foundry.core.mechanics.MechanicTypes.excluded(step)) {
            if (spared(kind, entity)) return false;
        }
        return true;
    }

    /** True when the entity is one of the kind an {@code exclude} entry names (see MechanicTypes.EXCLUDES). */
    static boolean spared(String kind, LivingEntity entity) {
        return switch (kind) {
            case "villagers" -> entity instanceof net.minecraft.world.entity.npc.villager.AbstractVillager;
            case "tamed" -> entity instanceof net.minecraft.world.entity.OwnableEntity pet && pet.getOwnerReference() != null;
            case "passive" -> !(entity instanceof net.minecraft.world.entity.monster.Enemy) && !(entity instanceof Player);
            case "players" -> entity instanceof Player;
            default -> false;
        };
    }

    @Override
    public boolean roll(double probability) {
        return player.getRandom().nextDouble() < probability;
    }

    /**
     * For tests: runs the on_bolt_hit mechanics tagged {@code tag} of the Foundry item in the player's main hand, as if
     * its bolt had struck {@code hit}. False when the hand holds no Foundry item.
     */
    public static boolean strike(String tag, ServerPlayer player, LivingEntity hit) {
        if (!(player.getMainHandItem().getItem() instanceof FoundryItem f) || f.def() == null) return false;
        return fire(Trigger.ON_BOLT_HIT, tag, f.contentId(), f.def().mechanics(), player, hit, hit.position());
    }

    /** Magic weapon enchantments (data/foundry/enchantment): bolt damage, left click cooldown, effect length. */
    static final net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> SPELLPOWER =
            net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT, Identifier.parse("foundry:spellpower"));
    static final net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> QUICKCAST =
            net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT, Identifier.parse("foundry:quickcast"));
    static final net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> POTENCY =
            net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT, Identifier.parse("foundry:potency"));

    /** The level of an enchantment on the item this mechanic belongs to, if the player holds it in the main hand. */
    int level(net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> key) {
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof FoundryItem f) || !contentId.equals(f.contentId())) return 0;
        var lookup = player.level().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        return lookup.get(key).map(h -> net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(h, held)).orElse(0);
    }

    /** Quickcast: one tick off a left click mechanic's cooldown for each level. */
    @Override
    public int cooldownCut(MechanicDef m) {
        return m.trigger() == Trigger.ON_SWING ? level(QUICKCAST) : 0;
    }

    /** The entity a step acts on: the hit entity for {@code target "hit"}, the acting player otherwise. */
    private LivingEntity targetOf(Step step) {
        return "hit".equals(step.param("target", "self")) && hit != null ? hit : player;
    }

    @Override
    public void perform(Step action) {
        ServerLevel level = player.level();
        String aim = action.param("target", "self");
        // a bolt that burst on a block struck nobody: what was meant for the struck entity is skipped, not turned on the caster
        if ("hit".equals(aim) && hit == null && impact != null) return;
        if ("impact".equals(aim)) {
            if (impact != null) atImpact(level, action);
            return;
        }
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
                        int ticks = action.intParam("duration", 100);
                        // Potency: what the weapon does to what it strikes lasts a quarter longer for each level
                        if ("hit".equals(action.param("target", "self")) && hit != null) ticks = ticks * (4 + level(POTENCY)) / 4;
                        var instance = new MobEffectInstance(effect, ticks, action.intParam("amplifier", 0));
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
                // Spellpower: one more damage for each level on what the weapon strikes (magic weapons only take it)
                float amount = (float) action.decimalParam("amount", 1) + (hit != null ? level(SPELLPOWER) : 0);
                if ("area".equals(action.param("target", "self"))) {
                    for (LivingEntity e : around(action)) e.hurtServer(level, level.damageSources().indirectMagic(player, player), amount);
                    return;
                }
                // A hit that follows a melee swing lands inside the target's hit window, where vanilla only applies
                // the amount above the last hit. A bonus that must land in full asks to clear the window first.
                if (Boolean.parseBoolean(action.param("ignore_hit_delay", "false"))) target.damageCooldownTime = 0;
                target.hurtServer(level, level.damageSources().indirectMagic(player, player), amount);
            }
            case "knockback" -> {
                double strength = action.decimalParam("strength", 0.5), lift = action.decimalParam("lift", 0.2);
                List<LivingEntity> who = "area".equals(action.param("target", "self")) ? around(action)
                        : target == player ? List.of() : List.of(target);
                for (LivingEntity e : who) Effects.knock(player, e, strength, lift);
            }
            case "particle_shape" -> Effects.shape(level, player, "hit".equals(action.param("target", "self")) ? hit : null, action);
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
            case "dash_strike" -> Quickdraw.strike(player, action, charge);
            case "parry" -> {
                if (current != null) Parry.open(player, contentId, index, current.cooldownTicks(), action);
            }
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

    /**
     * Target "impact": the step acts around where the bolt landed. Effects, damage and knockback reach every living thing
     * within the radius of it (never the caster, narrowed by the filters); a sound or a shape plays at the spot.
     */
    private void atImpact(ServerLevel level, Step action) {
        switch (action.type()) {
            case "play_sound" -> {
                var sound = BuiltInRegistries.SOUND_EVENT.getValue(Identifier.parse(action.param("sound", "")));
                if (sound != null) {
                    level.playSound(null, impact.x, impact.y, impact.z, sound, SoundSource.PLAYERS,
                            (float) action.decimalParam("volume", 1.0), (float) action.decimalParam("pitch", 1.0));
                }
            }
            case "particle_shape" -> Effects.shapeAt(level, player, impact, 1, action);
            case "apply_effect" -> BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(action.param("effect", "")))
                    .ifPresent(effect -> {
                        // Potency stretches it, as on a struck target
                        int ticks = action.intParam("duration", 100) * (4 + level(POTENCY)) / 4;
                        for (LivingEntity e : nearImpact(action)) {
                            e.addEffect(new MobEffectInstance(effect, ticks, action.intParam("amplifier", 0)));
                        }
                    });
            case "damage" -> {
                float amount = (float) action.decimalParam("amount", 1) + level(SPELLPOWER);
                for (LivingEntity e : nearImpact(action)) e.hurtServer(level, level.damageSources().indirectMagic(player, player), amount);
            }
            case "knockback" -> {
                for (LivingEntity e : nearImpact(action)) {
                    Effects.knockFrom(impact, player, e, action.decimalParam("strength", 0.5), action.decimalParam("lift", 0.2));
                }
            }
            default -> { }
        }
    }

    /** Every living thing within the step's radius of the impact, measured to the nearest point of its body. */
    private List<LivingEntity> nearImpact(Step action) {
        double r = action.decimalParam("radius", 3);
        return player.level().getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(impact, impact).inflate(r),
                e -> e != player && e.isAlive() && !e.isSpectator()
                        && e.getBoundingBox().distanceToSqr(impact) <= r * r && passesFilters(action, e));
    }

    /**
     * The living things an area action reaches: within {@code radius} of the entity struck (or of the caster when
     * nothing was struck), never the caster, alive and not spectating, and passing the permission filters.
     */
    private List<LivingEntity> around(Step action) {
        double r = action.decimalParam("radius", 3);
        LivingEntity centre = hit != null ? hit : player;
        return player.level().getEntitiesOfClass(LivingEntity.class, centre.getBoundingBox().inflate(r),
                e -> e != player && e.isAlive() && !e.isSpectator() && e.distanceToSqr(centre) <= r * r && passesFilters(action, e));
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
                        case "particle_shape" -> {
                            if (a.params().containsKey("particle")) particle(problems, def.id(), a.param("particle", ""));
                        }
                        case "launch_bolt" -> {
                            if (a.params().containsKey("particle")) particle(problems, def.id(), a.param("particle", ""));
                            if (a.params().containsKey("model")) {
                                missing(problems, def.id(), "item", a.param("model", ""),
                                        BuiltInRegistries.ITEM.containsKey(Identifier.parse(a.param("model", "")))
                                                || set.items().containsKey(foundry.core.Ident.parse(a.param("model", ""))));
                            }
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
