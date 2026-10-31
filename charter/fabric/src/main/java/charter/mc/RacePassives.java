package charter.mc;

import charter.id.PlayerId;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.List;

/**
 * Standing effects a race gives its people, read from the {@code racePassives} knob: the race node path (the kingdom's
 * name, as in {@code race:azrak}) to a list of effect ids. Each pass refreshes the effects for longer than it waits to
 * run again, so they hold while the race does and fade within seconds of losing it. A player has the effects of the race
 * they have, belonging to a kingdom or not.
 */
public final class RacePassives {

    /** How often the effects are refreshed, in ticks. */
    public static final int EVERY_TICKS = 100;
    /** How long each refresh lasts, in ticks: a little longer than the wait. */
    static final int LASTS_TICKS = EVERY_TICKS + 120;

    private RacePassives() {
    }

    public static void apply(MinecraftServer server, CharterRuntime rt) {
        if (rt.knobs.racePassives.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) applyTo(player, rt);
    }

    /** Refreshes the effects of this one player's race. */
    public static void applyTo(ServerPlayer player, CharterRuntime rt) {
        if (!player.isAlive()) return;
        for (String id : effectsOf(rt, new PlayerId(player.getUUID()))) {
            BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(id))
                    .ifPresent(effect -> player.addEffect(new MobEffectInstance(effect, LASTS_TICKS, 0, true, false, true)));
        }
    }

    /** The effect ids of this player's race; none when they have no race or it gives none. */
    static List<String> effectsOf(CharterRuntime rt, PlayerId player) {
        return rt.state.raceOf(player).flatMap(rt.state::org)
                .map(org -> rt.knobs.racePassives.getOrDefault(RaceNodes.slug(org.name()), List.<String>of()))
                .orElse(List.of());
    }
}
