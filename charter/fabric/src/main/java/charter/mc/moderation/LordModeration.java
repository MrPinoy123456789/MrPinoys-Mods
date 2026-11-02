package charter.mc.moderation;

import charter.id.PlayerId;
import charter.mc.CharterPerms;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.cmd.Chat;
import charter.org.PlayerRecord;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Where a Lord's moderation bites. A muted citizen's chat, private messages and {@code /me} are stopped; the
 * suspension itself is the banishment push ({@code Banishments}); the Ledger grants lapse here. Operators are never
 * held by any of it: the permission is asked again on every message, so someone made an operator after a mute was
 * given is let go at once and the mute is dropped.
 */
public final class LordModeration {

    private static final int SWEEP_TICKS = 100;
    private static int ticks;

    private LordModeration() {
    }

    public static void register() {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, bound) -> mayTalk(sender));
        ServerMessageEvents.ALLOW_COMMAND_MESSAGE.register((message, source, bound) -> {
            ServerPlayer player = source.getPlayer();
            return player == null || mayTalk(player);
        });
        ServerTickEvents.END_SERVER_TICK.register(LordModeration::tick);
    }

    private static void tick(MinecraftServer server) {
        if (++ticks % SWEEP_TICKS != 0) return;
        LedgerGrants.sweep(server);
    }

    /**
     * True for the people a Lord can never moderate: anyone holding {@code charter.admin} or {@code charter.bypass} by
     * permission. The admin kingdom pass does not count, so an admin kingdom's own Lord still moderates its members.
     */
    public static boolean staff(ServerPlayer player) {
        return CharterPerms.admin(player) || CharterPerms.bypassByPermission(player);
    }

    /** False, with the sender told why, when a Lord's mute holds this player now. */
    public static boolean mayTalk(ServerPlayer sender) {
        if (!CharterServer.running()) return true;
        CharterRuntime rt = CharterServer.runtime();
        PlayerRecord record = rt.state.knownPlayer(new PlayerId(sender.getUUID())).orElse(null);
        if (record == null || record.mutedUntil <= rt.clock.now()) return true;
        if (staff(sender)) {
            record.mutedUntil = 0;
            return true;
        }
        sender.sendSystemMessage(Component.literal(Text.get("lord.muted_now", "time", Chat.duration(record.mutedUntil - rt.clock.now())))
                .withStyle(ChatFormatting.RED));
        return false;
    }

    /** Lifts a suspension held by someone who is, or became, an operator. Called as the push looks at a player. */
    public static boolean suspensionDropped(ServerPlayer player, PlayerRecord record, long now) {
        if (record.suspendedUntil <= now || !staff(player)) return false;
        record.suspendedUntil = 0;
        record.suspendedFrom = null;
        return true;
    }

    /** Tells every operator who is online what a Lord just did. */
    public static void tellOperators(MinecraftServer server, String text) {
        Component line = Component.literal(text).withStyle(ChatFormatting.DARK_AQUA);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (CharterPerms.admin(p)) p.sendSystemMessage(line);
        }
        server.sendSystemMessage(line);
    }
}
