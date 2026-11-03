package foundry.mc;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/**
 * Hold-to-charge state: which charged mechanic a player armed when they started holding right click, and when. A hold
 * is decided at release, like a bow. The mechanic is remembered from the start, so letting go of sneak halfway does not
 * change what fires. Server thread only.
 *
 * <p>Bedrock players cannot hold: their client never tells the server it let go of an item Bedrock does not see as
 * usable, so a hold would never end. For them a tap starts a cast instead, which fires by itself after the same charge
 * time while the item is still in the same hand, so the wait (and the balance) is the same for both editions.
 */
public final class Charging {
    record Hold(String contentId, int index, long startTick, int needTicks) {}

    private static final Map<UUID, Hold> HOLDS = new HashMap<>();

    private Charging() {}

    static void begin(ServerPlayer player, Hold hold) {
        HOLDS.put(player.getUUID(), hold);
    }

    /** Takes the player's hold, or null when they have none. */
    static Hold end(ServerPlayer player) {
        return HOLDS.remove(player.getUUID());
    }

    static void forget(UUID player) {
        HOLDS.remove(player);
        CASTS.remove(player);
    }

    static void clear() {
        HOLDS.clear();
        CASTS.clear();
    }

    /** A Bedrock cast: the armed mechanic fires at {@code fireTick} if the item is still in {@code hand}. */
    record Cast(String contentId, int index, long fireTick, net.minecraft.world.InteractionHand hand) {}

    private static final Map<UUID, Cast> CASTS = new HashMap<>();

    static void cast(ServerPlayer player, Cast cast) {
        CASTS.put(player.getUUID(), cast);
    }

    /** True while this player has a cast waiting to fire. */
    static boolean casting(UUID player) {
        return CASTS.containsKey(player);
    }

    /**
     * Fires every cast that is due. A cast whose item left the hand (switched, dropped) or whose player died fizzles,
     * as a drawn bow does when the player swaps away.
     */
    static void tick(net.minecraft.server.MinecraftServer server) {
        if (CASTS.isEmpty()) return;
        long now = server.getTickCount();
        var it = CASTS.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Cast cast = e.getValue();
            if (now < cast.fireTick()) continue;
            it.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            if (player == null || !player.isAlive()) continue;
            if (player.getItemInHand(cast.hand()).getItem() instanceof FoundryItem item && item.contentId().equals(cast.contentId())) {
                item.fireCast(player, cast.index());
            } else {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("The spell fizzled"), true);
            }
        }
    }

    /**
     * The charge meter for abilities that may be let go early (min_charge_ticks): "Quickdraw ▮▮▮▯▯" on the action bar
     * and a note that rises with the charge, so a player on either edition can see and hear how far it has come. A
     * Bedrock cast shows the same meter while it counts down to the full charge.
     */
    static void meters(net.minecraft.server.MinecraftServer server) {
        long now = server.getTickCount();
        if (now % 2 != 0) return;
        var holds = HOLDS.entrySet().iterator();
        while (holds.hasNext()) {
            var e = holds.next();
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            Hold h = e.getValue();
            // A hold ends without a release when the player switches slot, drops the item or dies: forget it, or its
            // meter would stay on their action bar.
            if (player == null || !player.isAlive() || !player.isUsingItem()
                    || !(player.getUseItem().getItem() instanceof FoundryItem item) || !item.contentId().equals(h.contentId())) {
                holds.remove();
                continue;
            }
            meter(player, h.contentId(), h.index(), now - h.startTick());
        }
        for (var e : CASTS.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            Cast c = e.getValue();
            var m = mechanic(c.contentId(), c.index());
            if (player != null && m != null) meter(player, c.contentId(), c.index(), m.chargeTicks() - (c.fireTick() - now));
        }
    }

    private static foundry.core.mechanics.MechanicDef mechanic(String contentId, int index) {
        var def = FoundryMod.loaded().items().get(foundry.core.Ident.parse(contentId));
        if (def == null || index < 0 || index >= def.mechanics().size()) return null;
        return def.mechanics().get(index);
    }

    private static void meter(ServerPlayer player, String contentId, int index, long held) {
        var m = mechanic(contentId, index);
        if (m == null || m.minChargeTicks() <= 0) return;
        boolean ready = held >= m.releaseTicks();
        double charge = ready ? m.chargeOf(held) : 0;
        var colour = !ready ? net.minecraft.ChatFormatting.GRAY
                : charge >= 1 ? net.minecraft.ChatFormatting.GOLD : net.minecraft.ChatFormatting.YELLOW;
        String name = m.name().isBlank() ? "Charge" : m.name();
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(name + " " + foundry.core.Iai.meter(charge, 10))
                .withStyle(colour), true);
        // a note every quarter of the charge, rising, and a bright one when it is full
        long span = Math.max(1, m.chargeTicks() - m.releaseTicks());
        long since = held - m.releaseTicks();
        long quarter = since >= 0 ? Math.min(4, since * 4 / span) * span / 4 : -10;
        if (since >= 0 && since <= span + 1 && since - quarter < 2) {
            float pitch = 0.8f + (float) (1.0 * Math.min(1, since / (double) span));
            var sound = since >= span - 1 ? net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL : net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING;
            if (player.connection != null) {
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(sound,
                        net.minecraft.sounds.SoundSource.PLAYERS, player.getX(), player.getY(), player.getZ(), 0.5f, pitch,
                        player.getRandom().nextLong()));
            }
        }
    }

    /** True while this player has a hold under way (for tests). */
    public static boolean holding(UUID player) {
        return HOLDS.containsKey(player);
    }

    static int live() {
        return HOLDS.size() + CASTS.size();
    }
}
