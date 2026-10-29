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
final class MechanicHost implements MechanicRunner.Host {
    private static final MechanicRunner RUNNER = new MechanicRunner();

    private final ServerPlayer player;
    private final LivingEntity hit;

    private MechanicHost(ServerPlayer player, LivingEntity hit) {
        this.player = player;
        this.hit = hit;
    }

    /** Fires every mechanic of {@code trigger}. Returns true when at least one ran. */
    static boolean fire(Trigger trigger, String contentId, List<MechanicDef> mechanics, ServerPlayer player,
            LivingEntity hit) {
        boolean any = false;
        MechanicHost host = new MechanicHost(player, hit);
        for (int i = 0; i < mechanics.size(); i++) {
            MechanicDef m = mechanics.get(i);
            if (m.trigger() != trigger) continue;
            var outcome = RUNNER.fire(player.getStringUUID(), contentId, i, m, host);
            if (outcome == MechanicRunner.Outcome.RAN) any = true;
            if (outcome == MechanicRunner.Outcome.RATE_LIMITED || outcome == MechanicRunner.Outcome.REENTERED) {
                FoundryMod.LOG.warn("Foundry: mechanic {} #{} refused ({}) for {}.", contentId, i, outcome,
                        player.getName().getString());
            }
        }
        return any;
    }

    @Override
    public long nowTick() {
        return player.level().getServer().getTickCount();
    }

    @Override
    public boolean hasPermission(int level, boolean onHit) {
        if (level <= 0) return true;
        if (onHit) {
            return hit instanceof ServerPlayer p && p.permissions().hasPermission(
                    new net.minecraft.server.permissions.Permission.HasCommandLevel(
                            net.minecraft.server.permissions.PermissionLevel.byId(level)));
        }
        return player.permissions().hasPermission(new net.minecraft.server.permissions.Permission.HasCommandLevel(
                net.minecraft.server.permissions.PermissionLevel.byId(level)));
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
                    .ifPresent(effect -> target.addEffect(new MobEffectInstance(effect, action.intParam("duration", 100),
                            action.intParam("amplifier", 0))));
            case "give_item" -> {
                if (target instanceof Player p) {
                    var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(action.param("item", "")));
                    p.getInventory().placeItemBackInInventory(new ItemStack(item, action.intParam("count", 1)));
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

    private static void missing(List<Problem> problems, Ident id, String what, String value, boolean exists) {
        if (!exists) {
            problems.add(Problem.error(id.toString(), "mechanics", "There is no " + what + " called " + value + ".",
                    "Check the spelling; use /give or the wiki for real ids."));
        }
    }
}
