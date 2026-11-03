package charter.mc;

import charter.id.PlayerId;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
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
    /** Night vision blinks, as if about to run out, once it has this many ticks or fewer left. */
    static final int FLICKER_TICKS = 200;
    /**
     * How long each refresh lasts, in ticks: the wait, plus the night vision flicker, plus a margin, so a passive never gets
     * near the end between refreshes. It fades within about 20 seconds of losing the race.
     */
    static final int LASTS_TICKS = EVERY_TICKS + FLICKER_TICKS + 100;

    private RacePassives() {
    }

    public static void apply(MinecraftServer server, CharterRuntime rt) {
        if (rt.knobs.racePassives.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) applyTo(player, rt);
    }

    /** Refreshes the always-on effects of this one player's race; water and swim entries are the quick pass's. */
    public static void applyTo(ServerPlayer player, CharterRuntime rt) {
        if (!player.isAlive()) return;
        for (String text : effectsOf(rt, new PlayerId(player.getUUID()))) {
            Entry entry = parse(text);
            if (entry.conditional()) continue;
            BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(entry.id())).ifPresent(effect ->
                    player.addEffect(new MobEffectInstance(effect, LASTS_TICKS, entry.amplifier(), true, false, true)));
        }
    }

    /**
     * How often the water and swim effects are refreshed, in ticks, and how long each lasts: it fades fast on leaving the
     * water. A swim effect is taken off at once when the player stops swimming.
     */
    public static final int WATER_EVERY_TICKS = 5;
    static final int WATER_LASTS_TICKS = 15;

    /** A Floodgate (Bedrock) player has a UUID whose high half is zero. */
    public static boolean isBedrock(java.util.UUID id) {
        return id.getMostSignificantBits() == 0L;
    }

    /**
     * One entry of a race's list: an effect id, when it applies ("" always, "water" while in water, "swim" while
     * swimming with arms paddling, "under" while the head is under water) and its amplifier ("#1").
     */
    record Entry(String id, String when, int amplifier) {
        boolean waterOnly() {
            return when.equals("water");
        }

        boolean swimOnly() {
            return when.equals("swim");
        }

        boolean underOnly() {
            return when.equals("under");
        }

        boolean conditional() {
            return !when.isEmpty();
        }

        boolean appliesTo(ServerPlayer player) {
            return switch (when) {
                case "water" -> player.isInWater();
                case "swim" -> player.isSwimming() && player.isInWater();
                case "under" -> player.isEyeInFluid(FluidTags.WATER);
                default -> true;
            };
        }
    }

    private static final List<String> CONDITIONS = List.of("water", "swim", "under");
    private static final Identifier NIGHT_VISION = Identifier.withDefaultNamespace("night_vision");

    static Entry parse(String text) {
        String when = "";
        String rest = text;
        for (String condition : CONDITIONS) {
            if (text.startsWith(condition + ":")) {
                when = condition;
                rest = text.substring(condition.length() + 1);
            }
        }
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
        return new Entry(rest, when, Math.max(0, amp));
    }

    /** The Bedrock-only effects of this player's race; none for a Java player. */
    static List<Entry> bedrockEffectsOf(CharterRuntime rt, ServerPlayer player) {
        if (!isBedrock(player.getUUID())) return List.of();
        return rt.state.raceOf(new PlayerId(player.getUUID())).flatMap(rt.state::org)
                .map(org -> rt.knobs.racePassivesBedrock.getOrDefault(RaceNodes.slug(org.name()), List.<String>of()))
                .orElse(List.of()).stream().map(RacePassives::parse).toList();
    }

    /**
     * The quick pass: water and swim effects from the race's list (with their icon), and every Bedrock extra
     * (invisibly).
     */
    public static void applyWater(MinecraftServer server, CharterRuntime rt) {
        if (rt.knobs.racePassivesBedrock.isEmpty() && rt.knobs.racePassives.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) applyWaterTo(player, rt);
    }

    public static void applyWaterTo(ServerPlayer player, CharterRuntime rt) {
        if (!player.isAlive()) return;
        for (String text : effectsOf(rt, new PlayerId(player.getUUID()))) {
            Entry entry = parse(text);
            if (entry.conditional()) quick(player, entry, true);
        }
        for (Entry entry : bedrockEffectsOf(rt, player)) quick(player, entry, false);
    }

    /**
     * Refreshes one entry while it applies, and takes a water, swim or under entry off at once when it stops. Night
     * vision is held as long as the always-on effects, since it blinks when nearly out; the rest last a moment.
     */
    private static void quick(ServerPlayer player, Entry entry, boolean icon) {
        BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(entry.id())).ifPresent(effect -> {
            boolean nightVision = Identifier.parse(entry.id()).equals(NIGHT_VISION);
            int lasts = !entry.conditional() || nightVision ? LASTS_TICKS : WATER_LASTS_TICKS;
            if (entry.appliesTo(player)) {
                player.addEffect(new MobEffectInstance(effect, lasts, entry.amplifier(), true, false, icon));
                return;
            }
            MobEffectInstance held = player.getEffect(effect);
            // only ours: ambient, the same strength and no longer than we give, so a potion of the same effect stays
            if (entry.conditional() && held != null && held.isAmbient() && held.getAmplifier() == entry.amplifier()
                    && held.getDuration() <= lasts) {
                player.removeEffect(effect);
            }
        });
    }

    /** The effect ids of this player's race; none when they have no race or it gives none. */
    /** True for a player whose race is in {@code racesKeepInventory}: they drop nothing on death and get it all back. */
    public static boolean keepsInventory(net.minecraft.world.entity.Entity entity) {
        if (!(entity instanceof ServerPlayer player) || !CharterServer.running()) return false;
        CharterRuntime rt = CharterServer.runtime();
        if (rt.knobs.racesKeepInventory == null || rt.knobs.racesKeepInventory.isEmpty()) return false;
        return rt.state.raceOf(new PlayerId(player.getUUID())).flatMap(rt.state::org)
                .map(org -> rt.knobs.racesKeepInventory.contains(RaceNodes.slug(org.name()))).orElse(false);
    }

    static List<String> effectsOf(CharterRuntime rt, PlayerId player) {
        return rt.state.raceOf(player).flatMap(rt.state::org)
                .map(org -> rt.knobs.racePassives.getOrDefault(RaceNodes.slug(org.name()), List.<String>of()))
                .orElse(List.of());
    }
}
