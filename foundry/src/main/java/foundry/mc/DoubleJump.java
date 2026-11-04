package foundry.mc;

import foundry.core.AirJump;
import foundry.core.RaceTraits;
import foundry.core.WingDash;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;

/**
 * The double jump as a race trait: a race whose look has {@code double_jump} (the Fae of Faeloria, by their race:faeloria
 * node) can press jump again in the air for a second hop, once per time off the ground, on a short cooldown. It replaces
 * gliding for the Fae, so they and the Fairies are told apart by how they move as well as how they look.
 *
 * <p>The client moves the player, so the hop is the server telling the player's own client its new motion (the same
 * packet a knockback sends); it works on vanilla and Bedrock clients alike. The fall distance is reset, so the hop never
 * adds fall damage. A burst of falling leaves and a rustle mark it for everyone nearby.
 *
 * <p>The Fae fly with it: unless their race_traits.json {@code wing_dash} sets no charges, the double jump is a wing dash
 * ({@link WingDash}): a few bursts the way they look, recharging in the air, with a slow fall between them, so a Fae can
 * stay up for good at a fraction of a Fairy's speed. The {@code double_jump} cooldown in worn_displays.json then only
 * marks the race as one that double jumps.
 *
 * <p>A Fae with a mace in the main hand folds their wings for a dive ({@link WingDash#dive}): no slow fall, and a
 * burst only steers, never climbing and never breaking the fall, so the mace's smash counts the whole drop.
 *
 * <p>Until a Fae has done one double jump they are taught it: a chat line the first time they are seen each login, and
 * an action bar hint near the top of a plain jump, at most every 30 seconds (JumpTip). Who has learned it is kept in
 * config/foundry/wing_dash_learned.json, so the hints stop for good.
 */
public final class DoubleJump {
    /** Each Fae's cooldown in ticks, refreshed every {@value WornDisplays#SCAN_EVERY} ticks; absent for other races. */
    private static final Map<UUID, Integer> COOLDOWN = new HashMap<>();
    private static final Map<UUID, AirJump> STATE = new HashMap<>();
    private static final Map<UUID, WingDash> DASH = new HashMap<>();
    /** Players wearing the Slow Falling this put on (so a potion's is never taken off). */
    private static final java.util.Set<UUID> FLOATING = new java.util.HashSet<>();
    /** How long each slow fall lasts, and how low it may run before it is renewed. */
    static final int SLOW_FALL_TICKS = 12;
    static final int SLOW_FALL_RENEW = 5;
    private static final Map<UUID, Long> TIP_AT = new HashMap<>();
    private static final java.util.Set<UUID> LEARNED = new java.util.HashSet<>();
    private static boolean learnedLoaded;

    private DoubleJump() {}

    /** The double jump cooldown for this player's race in ticks, or 0 when the race cannot double jump. */
    public static int cooldownOf(UUID player) {
        return COOLDOWN.getOrDefault(player, 0);
    }

    static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        if (now % WornDisplays.SCAN_EVERY == 0) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                int cooldown = 0;
                if (player.isAlive()) {
                    for (var race : WornDisplays.table().races().entrySet()) {
                        for (var look : race.getValue()) {
                            if (look.doubleJump() > 0 && Gate.hasNode(player, race.getKey())) cooldown = look.doubleJump();
                        }
                    }
                }
                if (cooldown > 0) {
                    if (COOLDOWN.put(player.getUUID(), cooldown) == null && !learned(player.getUUID())) {
                        boolean dash = RaceTraitsRuntime.of(player.getUUID()).dashOrDefault().charges() > 0;
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                        dash ? foundry.core.JumpTip.DASH_WELCOME : foundry.core.JumpTip.WELCOME)
                                .withStyle(net.minecraft.ChatFormatting.GREEN));
                    }
                } else {
                    forget(player.getUUID());
                }
            }
        }
        if (COOLDOWN.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Integer cooldown = COOLDOWN.get(player.getUUID());
            if (cooldown == null) continue;
            var input = player.getLastClientInput();
            boolean grounded = player.onGround() || player.isInWater() || player.isInLava() || player.isPassenger()
                    || player.getAbilities().flying || player.isFallFlying() || player.onClimbable() || !player.isAlive()
                    || player.isSpectator();
            RaceTraits.Dash dash = RaceTraitsRuntime.of(player.getUUID()).dashOrDefault();
            if (dash.charges() > 0) {
                dashTick(player, dash, input.jump(), input.shift(), grounded, now);
                continue;
            }
            AirJump state = STATE.computeIfAbsent(player.getUUID(), id -> new AirJump());
            if (state.tick(input.jump(), grounded, now, cooldown)) {
                hop(player, AirJump.push(player.getYRot(), input.forward(), input.backward(), input.left(), input.right(),
                        RaceTraitsRuntime.of(player.getUUID()).jumpDrift()));
                learn(player.getUUID());
            } else if (state.refused()) {
                AbilityBar.notYet(player, "Leaf wings", state.waiting(now),
                        net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.AZALEA_LEAVES_BREAK));
            } else if (foundry.core.JumpTip.show(learned(player.getUUID()), state.airTicks(), state.used(), now,
                    TIP_AT.getOrDefault(player.getUUID(), Long.MIN_VALUE))) {
                TIP_AT.put(player.getUUID(), now);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(foundry.core.JumpTip.TEXT), true);
            }
        }
    }

    /** One tick of a Fae's wing dash: bursts, the charge line, the slow fall and the hint. */
    private static void dashTick(ServerPlayer player, RaceTraits.Dash dash, boolean jump, boolean sneak, boolean grounded, long now) {
        UUID id = player.getUUID();
        WingDash state = DASH.computeIfAbsent(id, k -> new WingDash());
        boolean diving = diving(player);
        if (state.tick(jump, sneak, grounded, now, dash)) {
            burst(player, dash);
            learn(id);
            if (diving) state.dive();
            charges(player, state, dash, diving);
        } else if (diving && state.dive()) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Leaf wings folded: diving"), true);
        } else if (state.refused() && state.charges() == 0) {
            AbilityBar.notYet(player, "Leaf wings", state.waiting(now, dash),
                    net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.AZALEA_LEAVES_BREAK));
        } else if (state.refilled()) {
            charges(player, state, dash, diving);
        } else if (foundry.core.JumpTip.show(learned(id), state.airTicks(), state.burstThisAir(), now,
                TIP_AT.getOrDefault(id, Long.MIN_VALUE))) {
            TIP_AT.put(id, now);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(foundry.core.JumpTip.DASH_TEXT), true);
        }
        slowFall(player, state.slowFall(now) && player.isAlive());
        // the server runs with allow-flight off, and Bedrock moves the player under its own Slow Falling, so a Fae who has
        // beaten their wings this time in the air is never counted as floating (vanilla kicks after 4 seconds of it)
        if (state.burstThisAir() && player.connection != null) player.connection.resetFlyingTicks();
    }

    /** "Leaf wings ●●○" on the action bar: the charges left, and whether the wings are folded for a dive. */
    private static void charges(ServerPlayer player, WingDash state, RaceTraits.Dash dash, boolean diving) {
        int left = state.charges();
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Leaf wings " + "●".repeat(left)
                + "○".repeat(Math.max(0, dash.charges() - left)) + (diving ? " (folded: diving)" : "")), true);
    }

    /** True while this Fae holds a mace in the main hand, so the wings fold for a dive. Public so a test can drive it. */
    public static boolean diving(ServerPlayer player) {
        return player.getMainHandItem().is(net.minecraft.world.item.Items.MACE);
    }

    /**
     * Beats a Fae's wings: a burst the way they look (see {@link WingDash#burst}). Diving with a mace the burst only
     * steers ({@link WingDash#dive}): it never climbs or slows the drop, and keeps the fall so far, so the smash still counts the whole drop. Public so a test can
     * drive it.
     */
    public static void burst(ServerPlayer player, RaceTraits.Dash dash) {
        boolean diving = diving(player);
        Vec3 v = player.getDeltaMovement();
        double[] after = diving ? WingDash.dive(v.x, v.y, v.z, player.getYRot(), player.getXRot(), dash)
                : WingDash.burst(v.x, v.y, v.z, player.getYRot(), player.getXRot(), dash, overCeiling(player, dash.ceiling()));
        player.setDeltaMovement(after[0], after[1], after[2]);
        player.syncVelocity = true;
        if (!diving) player.resetFallDistance();
        flourish(player);
    }

    /**
     * True when nothing solid (or water) is within {@code ceiling} blocks below the player, so a burst should not climb.
     * It looks straight down from the player rather than at the map's top block, so under trees or in a cave it measures
     * the floor they are over.
     */
    public static boolean overCeiling(ServerPlayer player, int ceiling) {
        var level = player.level();
        var pos = player.blockPosition().mutable();
        for (int i = 0; i <= ceiling; i++) {
            var state = level.getBlockState(pos);
            if (!state.getCollisionShape(level, pos).isEmpty() || !state.getFluidState().isEmpty()) return false;
            pos.move(0, -1, 0);
        }
        return true;
    }

    /**
     * Holds the slow fall while {@code want}: a short Slow Falling with no particles and no icon, renewed before it runs
     * out, so it ends within a moment of landing, a sneak press or a hit. The player's client runs Slow Falling itself
     * (Java and Bedrock), so the fall is smooth whatever the lag. Only the effect this put on is ever taken off: a Fae who
     * drank a Slow Falling potion keeps it. Public so a test can drive it.
     */
    public static void slowFall(ServerPlayer player, boolean want) {
        UUID id = player.getUUID();
        var current = player.getEffect(MobEffects.SLOW_FALLING);
        if (want) {
            if (current == null || (FLOATING.contains(id) && current.getDuration() <= SLOW_FALL_RENEW)) {
                player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, SLOW_FALL_TICKS, 0, true, false, false));
                FLOATING.add(id);
            }
        } else if (FLOATING.remove(id) && current != null && current.getDuration() <= SLOW_FALL_TICKS) {
            player.removeEffect(MobEffects.SLOW_FALLING);
        }
    }

    /** A hit from a mob, a player or a projectile: a Fae in the air loses a charge and drops for a moment. */
    public static void hurt(ServerPlayer player, net.minecraft.world.damagesource.DamageSource source) {
        if (source.getEntity() == null && source.getDirectEntity() == null) return;
        WingDash state = DASH.get(player.getUUID());
        if (state == null) return;
        RaceTraits.Dash dash = RaceTraitsRuntime.of(player.getUUID()).dashOrDefault();
        state.hit(player.level().getServer().getTickCount(), dash);
        slowFall(player, false);
    }

    /** This player's wing dash, or null before their first tick as a Fae (for tests). */
    public static WingDash dashOf(UUID player) {
        return DASH.get(player);
    }

    /** Lifts the player and tells their client, with leaves and a rustle; public so a test can drive it. */
    public static void hop(ServerPlayer player, double[] push) {
        Vec3 now = player.getDeltaMovement();
        // keep a little of the motion they already had, so a hop while running carries on
        player.setDeltaMovement(now.x * 0.4 + push[0], push[1], now.z * 0.4 + push[2]);
        player.syncVelocity = true;
        player.resetFallDistance();
        flourish(player);
    }

    /** Falling leaves and a rustle where the wings beat. */
    private static void flourish(ServerPlayer player) {
        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.CHERRY_LEAVES, player.getX(), player.getY() + 0.2, player.getZ(), 10, 0.4, 0.1,
                    0.4, 0.02);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, player.getX(), player.getY() + 0.1, player.getZ(), 4, 0.3, 0.05,
                    0.3, 0.0);
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.AZALEA_LEAVES_BREAK,
                    SoundSource.PLAYERS, 0.8f, 1.4f);
        }
    }

    static void forget(UUID player) {
        COOLDOWN.remove(player);
        STATE.remove(player);
        DASH.remove(player);
        FLOATING.remove(player);
        TIP_AT.remove(player);
    }

    static void clear() {
        COOLDOWN.clear();
        STATE.clear();
        DASH.clear();
        FLOATING.clear();
        TIP_AT.clear();
        LEARNED.clear();
        learnedLoaded = false;
    }

    private static java.nio.file.Path learnedFile() {
        return FoundryMod.contentRoot().resolveSibling("wing_dash_learned.json");
    }

    /** True once this player has done a double jump (ever), so they are not taught it again. */
    public static boolean learned(UUID player) {
        if (!learnedLoaded) {
            learnedLoaded = true;
            try {
                var f = learnedFile();
                if (java.nio.file.Files.isRegularFile(f)) {
                    for (var e : com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(f)).getAsJsonObject()
                            .getAsJsonArray("players")) {
                        LEARNED.add(UUID.fromString(e.getAsString()));
                    }
                }
            } catch (Exception e) {
                FoundryMod.LOG.warn("Foundry: could not read wing_dash_learned.json: {}", e.toString());
            }
        }
        return LEARNED.contains(player);
    }

    /** Remembers that this player knows the double jump; public so a test can drive it. */
    public static void learn(UUID player) {
        if (learned(player)) return;
        LEARNED.add(player);
        var players = new com.google.gson.JsonArray();
        LEARNED.stream().map(UUID::toString).sorted().forEach(players::add);
        var root = new com.google.gson.JsonObject();
        root.add("players", players);
        try {
            java.nio.file.Files.createDirectories(learnedFile().getParent());
            java.nio.file.Files.writeString(learnedFile(), root + "\n");
        } catch (java.io.IOException e) {
            FoundryMod.LOG.warn("Foundry: could not save wing_dash_learned.json: {}", e.toString());
        }
    }
}
