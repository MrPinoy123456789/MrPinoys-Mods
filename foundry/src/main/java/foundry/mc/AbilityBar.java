package foundry.mc;

import foundry.core.mechanics.MechanicDef;
import foundry.core.mechanics.Trigger;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * The ability line on the action bar, for weapons with named abilities on a recharge: "Thorn Bolt · Bramble Burst 9s ·
 * Glamour", ready ones bright, recharging ones grey with the seconds left. It shows while the weapon is in hand and
 * something on it is recharging, and for a few seconds after a press that did nothing, so a press on cooldown always
 * answers. When a longer ability comes back a soft chime plays to that player alone and the line says it is ready.
 *
 * <p>The action bar reaches Bedrock players through Geyser as it is, so the line works on Xbox and phones too. It steps
 * aside while a cast or a hold is under way, so "Casting..." stays readable. Server thread only.
 */
public final class AbilityBar {
    /** How long the line stays after a press that did nothing, or a ready ping. */
    static final int SHOW_TICKS = 50;
    /** Abilities with at least this long a recharge chime when they come back; a quick bolt does not. */
    static final int CHIME_MIN_TICKS = 100;
    private static final int EVERY = 5;

    private record Cooling(String contentId, int index, String name, long readyAt, boolean chime, boolean charged) {}

    private static final Map<UUID, Long> SHOW_UNTIL = new HashMap<>();
    private static final Map<UUID, Map<String, Cooling>> COOLING = new HashMap<>();
    /** A "ready" line to show in place of the usual one until its tick. */
    private static final Map<UUID, ReadyFlash> FLASH = new HashMap<>();

    private record ReadyFlash(String name, long until) {}

    private AbilityBar() {}

    /** A press that did nothing (on recharge): show the line at once. */
    static void poke(ServerPlayer player) {
        SHOW_UNTIL.put(player.getUUID(), now(player) + SHOW_TICKS);
        render(player);
    }

    /** A named ability with a recharge just ran: track it, so the line and the ready chime know. */
    static void started(ServerPlayer player, String contentId, int index, MechanicDef m) {
        if (m.cooldownTicks() <= 0 || m.name().isEmpty()) return;
        COOLING.computeIfAbsent(player.getUUID(), id -> new HashMap<>()).put(contentId + "#" + index,
                new Cooling(contentId, index, m.name(), now(player) + m.cooldownTicks(), m.cooldownTicks() >= CHIME_MIN_TICKS,
                        m.charged()));
        // A charged ability that is now recharging stops showing as usable, so the client draws no pose for it.
        if (m.charged()) player.containerMenu.sendAllDataToRemote();
    }

    static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Map<String, Cooling> cooling = COOLING.get(player.getUUID());
            if (cooling != null) {
                for (Iterator<Cooling> it = cooling.values().iterator(); it.hasNext(); ) {
                    Cooling c = it.next();
                    if (now < c.readyAt()) continue;
                    it.remove();
                    if (c.charged()) player.containerMenu.sendAllDataToRemote();
                    if (c.chime() && player.isAlive()) {
                        chime(player);
                        FLASH.put(player.getUUID(), new ReadyFlash(c.name(), now + SHOW_TICKS));
                        SHOW_UNTIL.put(player.getUUID(), now + SHOW_TICKS);
                        render(player);
                    }
                }
                if (cooling.isEmpty()) COOLING.remove(player.getUUID());
            }
            if (now % EVERY == 0) render(player);
        }
        SHOW_UNTIL.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        FLASH.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    }

    /** Sends the line when it should show: the weapon in hand, and something recharging or a recent press. */
    private static void render(ServerPlayer player) {
        UUID id = player.getUUID();
        long now = now(player);
        if (Charging.casting(id) || player.isUsingItem()) return;
        ReadyFlash flash = FLASH.get(id);
        if (flash != null && now >= flash.until()) {
            FLASH.remove(id);
            flash = null;
        }
        if (!(player.getMainHandItem().getItem() instanceof FoundryItem item)) {
            // Not holding a weapon: only a ready ping shows, so a long recharge still announces itself.
            if (flash != null) player.sendSystemMessage(readyLine(flash.name()), true);
            return;
        }
        var line = line(player, item);
        boolean anyCooling = line != null && line.cooling();
        boolean recent = now < SHOW_UNTIL.getOrDefault(id, Long.MIN_VALUE);
        if (line == null || (!anyCooling && !recent && flash == null)) return;
        player.sendSystemMessage(flash != null ? readyLine(flash.name()).append(Component.literal("  ")).append(line.text())
                : line.text(), true);
    }

    private record Line(MutableComponent text, boolean cooling) {}

    /** The line for the weapon in hand, or null when it has no named ability with a recharge. */
    private static Line line(ServerPlayer player, FoundryItem item) {
        var def = item.def();
        if (def == null) return null;
        MutableComponent text = Component.empty();
        boolean any = false, cooling = false;
        var list = def.mechanics();
        for (int i = 0; i < list.size(); i++) {
            MechanicDef m = list.get(i);
            if (m.cooldownTicks() <= 0 || m.name().isEmpty()) continue;
            if (m.trigger() != Trigger.ON_USE && m.trigger() != Trigger.ON_SWING) continue;
            long left = MechanicHost.cooldownLeft(item.contentId(), i, player);
            if (any) text.append(Component.literal("  ·  ").withStyle(ChatFormatting.DARK_GRAY));
            any = true;
            if (left <= 0) {
                text.append(Component.literal(m.name()).withStyle(ChatFormatting.GREEN));
            } else {
                cooling = true;
                text.append(Component.literal(m.name() + " " + seconds(left)).withStyle(ChatFormatting.GRAY));
            }
        }
        return any ? new Line(text, cooling) : null;
    }

    private static MutableComponent readyLine(String name) {
        return Component.literal(name + " ready").withStyle(ChatFormatting.GOLD);
    }

    private static String seconds(long ticks) {
        return foundry.core.JumpTip.seconds(ticks);
    }

    /** A soft chime to this player only. */
    private static void chime(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_CHIME, 0.5F, 1.6F);
    }

    /**
     * A race move pressed before it is back: "Leaf wings 1.2s" on the action bar and a soft, low sound to this player
     * only, so the press is answered instead of seeming lost.
     */
    static void notYet(ServerPlayer player, String what, long ticksLeft, Holder<SoundEvent> sound) {
        player.sendSystemMessage(Component.literal(what + " " + seconds(ticksLeft)).withStyle(ChatFormatting.GRAY), true);
        play(player, sound, 0.4F, 0.7F);
    }

    private static void play(ServerPlayer player, Holder<SoundEvent> sound, float volume, float pitch) {
        if (player.connection == null) return;
        player.connection.send(new ClientboundSoundPacket(sound, SoundSource.PLAYERS, player.getX(), player.getY(), player.getZ(),
                volume, pitch, player.getRandom().nextLong()));
    }

    /** True while this player has a tracked ability recharging (for tests). */
    public static boolean tracking(UUID player) {
        return COOLING.containsKey(player);
    }

    /** True while the weapon in this player's main hand has its charged ability recharging, so no pose is drawn (for tests). */
    public static boolean poseHidden(ServerPlayer player) {
        return player.getMainHandItem().getItem() instanceof FoundryItem item && item.def() != null
                && MechanicHost.chargeRecharging(item.contentId(), item.def().mechanics(), player);
    }

    static void forget(UUID player) {
        SHOW_UNTIL.remove(player);
        COOLING.remove(player);
        FLASH.remove(player);
    }

    static void clear() {
        SHOW_UNTIL.clear();
        COOLING.clear();
        FLASH.clear();
    }

    private static long now(ServerPlayer player) {
        return player.level().getServer().getTickCount();
    }
}
