package charter.mc.justice;

import charter.contract.Justice;
import charter.id.PlayerId;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Execution is a wait and a banishment, not a death ban (DESIGN.md §15.5, D18).
 * The mod kills the player once; they keep their inventory and experience across
 * the respawn; then for the rest of the respawn delay they stand frozen and blind
 * in adventure mode with a boss bar counting down. Spectator mode is not used,
 * because it scouts. Banishment, the other half, is {@link Banishments}.
 *
 * <p>The wait is asked of core every tick ({@link Justice#respawnDelayLeft}), so it
 * also holds across a relog and ends by itself, a pardon included.
 */
public final class Executions {

    private static final class Wait {
        GameType prior;
        long total;
        final ServerBossEvent bar = new ServerBossEvent(UUID.randomUUID(), Component.empty(),
                BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
    }

    private static final Map<UUID, Wait> WAITS = new HashMap<>();
    /** Players the mod killed whose inventory must come back with them. */
    private static final Set<UUID> KEEP = new HashSet<>();

    private Executions() {
    }

    /** Kills an online player in the mod's name, dropping nothing; they come back with everything. */
    public static void carryOut(PlayerId prisoner) {
        MinecraftServer server = CharterServer.server();
        ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(prisoner.value());
        if (player == null || !player.isAlive()) return; // offline: the wait starts when they join
        ServerLevel level = player.level();
        GameRules rules = level.getGameRules();
        boolean before = rules.get(GameRules.KEEP_INVENTORY);
        KEEP.add(player.getUUID());
        rules.set(GameRules.KEEP_INVENTORY, true, server);
        try {
            player.kill(level);
            // A player whose client has not finished loading is immune to damage; the execution does not wait for it.
            if (player.isAlive()) {
                player.setHealth(0.0F);
                player.die(level.damageSources().genericKill());
            }
        } finally {
            rules.set(GameRules.KEEP_INVENTORY, before, server);
        }
    }

    /** The respawn copy: an executed player gets the inventory and experience of the one who died. */
    public static void copyFrom(ServerPlayer old, ServerPlayer fresh) {
        if (!KEEP.remove(old.getUUID())) return;
        fresh.getInventory().replaceWith(old.getInventory());
        fresh.experienceLevel = old.experienceLevel;
        fresh.experienceProgress = old.experienceProgress;
        fresh.totalExperience = old.totalExperience;
    }

    /** True if the player is standing out a respawn wait now. */
    public static boolean waiting(UUID player) {
        return WAITS.containsKey(player);
    }

    /** The boss bar of a waiting player, for tests. */
    public static ServerBossEvent bar(UUID player) {
        Wait wait = WAITS.get(player);
        return wait == null ? null : wait.bar;
    }

    /** Starts, keeps or ends the wait for one player. The server tick calls this for everyone. */
    public static void enforce(ServerPlayer player) {
        if (!CharterServer.running() || !player.isAlive() || player.isSpectator()) return;
        CharterRuntime rt = CharterServer.runtime();
        long left = Justice.respawnDelayLeft(rt.state, rt.knobs, new PlayerId(player.getUUID()), rt.clock.now());
        Wait wait = WAITS.get(player.getUUID());
        if (left <= 0) {
            if (wait != null) end(player, wait);
            return;
        }
        if (wait == null) {
            wait = new Wait();
            wait.total = left;
            GameType mode = player.gameMode();
            // A restart forgets the mode they had; adventure then means the server's own default.
            wait.prior = mode == GameType.ADVENTURE ? CharterServer.server().getDefaultGameType() : mode;
            WAITS.put(player.getUUID(), wait);
            player.setGameMode(GameType.ADVENTURE);
            player.sendSystemMessage(Component.literal(Text.get("execution.wait", "time", charter.mc.cmd.Chat.duration(left)))
                    .withStyle(ChatFormatting.RED));
        }
        wait.total = Math.max(wait.total, left);
        wait.bar.setName(Component.literal(Text.get("execution.bar", "time", charter.mc.cmd.Chat.duration(left))));
        wait.bar.setProgress((float) Math.max(0, Math.min(1, (double) left / wait.total)));
        wait.bar.addPlayer(player);
        int ticks = (int) Math.min(Integer.MAX_VALUE / 2, left / 50) + 40;
        if (!player.hasEffect(MobEffects.SLOWNESS) || player.getEffect(MobEffects.SLOWNESS).getDuration() < 40) {
            player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, Math.min(ticks, 200), 6, true, false, false));
            player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, Math.min(ticks, 200), 0, true, false, false));
        }
    }

    private static void end(ServerPlayer player, Wait wait) {
        WAITS.remove(player.getUUID());
        wait.bar.removeAllPlayers();
        player.removeEffect(MobEffects.SLOWNESS);
        player.removeEffect(MobEffects.BLINDNESS);
        if (player.gameMode() == GameType.ADVENTURE) player.setGameMode(wait.prior);
        player.sendSystemMessage(Component.literal(Text.get("execution.over")).withStyle(ChatFormatting.GREEN));
    }

    /** The dead player's object leaves the boss bar so the one who respawned can take its place. */
    public static void respawned(ServerPlayer old) {
        Wait wait = WAITS.get(old.getUUID());
        if (wait != null) wait.bar.removePlayer(old);
    }

    /** A player who left: their bar goes, their wait stays so it resumes (and keeps the mode to give back). */
    public static void left(ServerPlayer player) {
        Wait wait = WAITS.get(player.getUUID());
        if (wait != null) wait.bar.removePlayer(player);
    }

    /** Test cleanup. */
    public static void forget(UUID player) {
        Wait wait = WAITS.remove(player);
        if (wait != null) wait.bar.removeAllPlayers();
        KEEP.remove(player);
    }
}
