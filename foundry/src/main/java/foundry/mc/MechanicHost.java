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
import net.minecraft.world.item.ItemStack;

/**
 * Carries out validated mechanic steps for one player. Effects and sounds apply to the acting player; there is no
 * target selector yet (see docs/devin/01-TASKS.md, F3 follow-ups). Server thread only.
 */
final class MechanicHost implements MechanicRunner.Host {
    private static final MechanicRunner RUNNER = new MechanicRunner();

    private final ServerPlayer player;

    private MechanicHost(ServerPlayer player) {
        this.player = player;
    }

    /** Fires every mechanic of {@code trigger}. Returns true when at least one ran. */
    static boolean fire(Trigger trigger, String contentId, List<MechanicDef> mechanics, ServerPlayer player) {
        boolean any = false;
        MechanicHost host = new MechanicHost(player);
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
    public boolean hasPermission(int level) {
        return level <= 0 || player.permissions().hasPermission(new net.minecraft.server.permissions.Permission.HasCommandLevel(
                net.minecraft.server.permissions.PermissionLevel.byId(level)));
    }

    @Override
    public boolean roll(double probability) {
        return player.getRandom().nextDouble() < probability;
    }

    @Override
    public void perform(Step action) {
        ServerLevel level = player.level();
        switch (action.type()) {
            case "play_sound" -> {
                var sound = BuiltInRegistries.SOUND_EVENT.getValue(Identifier.parse(action.param("sound", "")));
                if (sound != null) {
                    level.playSound(null, player.getX(), player.getY(), player.getZ(), sound, SoundSource.PLAYERS,
                            (float) action.decimalParam("volume", 1.0), (float) action.decimalParam("pitch", 1.0));
                }
            }
            case "apply_effect" -> BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(action.param("effect", "")))
                    .ifPresent(effect -> player.addEffect(new MobEffectInstance(effect, action.intParam("duration", 100),
                            action.intParam("amplifier", 0))));
            case "give_item" -> {
                var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(action.param("item", "")));
                player.getInventory().placeItemBackInInventory(new ItemStack(item, action.intParam("count", 1)));
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
        return new ContentSet(java.util.Map.copyOf(kept), List.copyOf(problems));
    }

    private static void missing(List<Problem> problems, Ident id, String what, String value, boolean exists) {
        if (!exists) {
            problems.add(Problem.error(id.toString(), "mechanics", "There is no " + what + " called " + value + ".",
                    "Check the spelling; use /give or the wiki for real ids."));
        }
    }
}
