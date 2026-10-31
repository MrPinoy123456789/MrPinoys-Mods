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

    /** How often the water-only effects are refreshed, in ticks, and how long each lasts: it fades fast on leaving the water. */
    public static final int WATER_EVERY_TICKS = 10;
    static final int WATER_LASTS_TICKS = 30;

    /** A Floodgate (Bedrock) player has a UUID whose high half is zero. */
    public static boolean isBedrock(java.util.UUID id) {
        return id.getMostSignificantBits() == 0L;
    }

    /** One entry of the Bedrock list: an effect id, whether it is for water only, and its amplifier. */
    record Entry(String id, boolean waterOnly, int amplifier) {
    }

    static Entry parse(String text) {
        boolean water = text.startsWith("water:");
        String rest = water ? text.substring("water:".length()) : text;
        int amp = 0;
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            try {
                amp = Integer.parseInt(rest.substring(hash + 1));
            } catch (NumberFormatException bad) {
                amp = 0;
            }
            rest = rest.substring(0, hash);
        }
        return new Entry(rest, water, Math.max(0, amp));
    }

    /** The Bedrock-only effects of this player's race; none for a Java player. */
    static List<Entry> bedrockEffectsOf(CharterRuntime rt, ServerPlayer player) {
        if (!isBedrock(player.getUUID())) return List.of();
        return rt.state.raceOf(new PlayerId(player.getUUID())).flatMap(rt.state::org)
                .map(org -> rt.knobs.racePassivesBedrock.getOrDefault(RaceNodes.slug(org.name()), List.<String>of()))
                .orElse(List.of()).stream().map(RacePassives::parse).toList();
    }

    /** The quick pass: Bedrock players in water get their water-only effects, invisibly. */
    public static void applyWater(MinecraftServer server, CharterRuntime rt) {
        if (rt.knobs.racePassivesBedrock.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) applyWaterTo(player, rt);
    }

    public static void applyWaterTo(ServerPlayer player, CharterRuntime rt) {
        if (!player.isAlive()) return;
        for (Entry entry : bedrockEffectsOf(rt, player)) {
            if (entry.waterOnly() && !player.isInWater()) continue;
            int lasts = entry.waterOnly() ? WATER_LASTS_TICKS : LASTS_TICKS;
            BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(entry.id()))
                    .ifPresent(effect -> player.addEffect(new MobEffectInstance(effect, lasts, entry.amplifier(), true, false, false)));
        }
    }

    /** The effect ids of this player's race; none when they have no race or it gives none. */
    static List<String> effectsOf(CharterRuntime rt, PlayerId player) {
        return rt.state.raceOf(player).flatMap(rt.state::org)
                .map(org -> rt.knobs.racePassives.getOrDefault(RaceNodes.slug(org.name()), List.<String>of()))
                .orElse(List.of());
    }
}
