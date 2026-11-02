package charter.mc.moderation;

import charter.id.PlayerId;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.moderation.LordRules;
import charter.moderation.ModerationEvents;
import charter.org.PlayerRecord;
import charter.time.Durations;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One hour of read only Ledger access for a Lord, given by an operator and nobody else. It is a LuckPerms temporary
 * permission, so the permissions mod does the lapsing; Charter keeps its own expiry beside it so it can refuse a
 * second grant while one stands, say when it ends, and take it back early when the player stops being a Lord.
 *
 * <p>Ledger cannot limit a lookup to one kingdom. What this grants is the whole server's block history to read, for
 * an hour, to someone an operator trusts for that hour. The nodes are the read ones only: no rollback, restore,
 * preview, teleport or player node is ever granted.
 */
public final class LedgerGrants {

    /** How long a grant lasts. Fixed; there is no knob and no argument for it. */
    public static final long DURATION_MILLIS = Durations.HOUR;
    /** The duration as LuckPerms reads it. */
    public static final String DURATION_TEXT = "1h";

    /** The only Ledger nodes ever granted: the root, and looking up, searching and status. */
    public static final List<String> NODES = List.of(
            "ledger.commands.root", "ledger.commands.inspect", "ledger.commands.search", "ledger.commands.status");

    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private LedgerGrants() {
    }

    /**
     * The LuckPerms commands that give the grant: one hour, never {@code accumulate}. LuckPerms' {@code replace} keeps
     * the longer of two durations, so {@link #grant} first runs {@link #revokeCommands} to clear any node set by hand.
     */
    public static List<String> grantCommands(UUID player) {
        List<String> out = new ArrayList<>();
        for (String node : NODES) out.add("lp user " + player + " permission settemp " + node + " true " + DURATION_TEXT + " replace");
        return out;
    }

    /** The LuckPerms commands that take the grant back at once. */
    public static List<String> revokeCommands(UUID player) {
        List<String> out = new ArrayList<>();
        for (String node : NODES) out.add("lp user " + player + " permission unsettemp " + node);
        return out;
    }

    /** "14:05 UTC". */
    public static String clock(long wall) {
        return AT.format(Instant.ofEpochMilli(wall));
    }

    public static boolean luckPermsPresent(MinecraftServer server) {
        return server.getCommands().getDispatcher().getRoot().getChild("lp") != null;
    }

    /** True while a grant stands for this player. */
    public static boolean active(CharterRuntime rt, PlayerId player) {
        return rt.state.knownPlayer(player).map(r -> r.ledgerUntil > rt.clock.now()).orElse(false);
    }

    private static void run(MinecraftServer server, List<String> commands) {
        for (String command : commands) server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }

    /** The result of asking for a grant, as the key of the line to show and its values. */
    public record Result(boolean ok, String text) {
    }

    /** Gives the grant. Checks, in order: a current Lord, none standing, LuckPerms present. Nothing changes on a refusal. */
    public static Result grant(MinecraftServer server, CharterRuntime rt, PlayerId player, String name, String admin) {
        if (!LordRules.isLord(rt.state, player)) return new Result(false, Text.get("admin.ledger.not_lord", "name", name));
        long now = rt.clock.now();
        PlayerRecord record = rt.state.player(player);
        if (record.ledgerUntil > now) {
            return new Result(false, Text.get("admin.ledger.active", "name", name, "until", clock(record.ledgerUntil)));
        }
        if (!luckPermsPresent(server)) return new Result(false, Text.get("admin.ledger.no_lp"));
        // LuckPerms runs its commands in order on one thread: clear any leftover node, then set exactly one hour.
        run(server, revokeCommands(player.value()));
        run(server, grantCommands(player.value()));
        record.ledgerUntil = now + DURATION_MILLIS;
        rt.state.history.record(new ModerationEvents.LedgerGranted(now, player, DURATION_MILLIS), "admin:" + admin, true);
        String until = clock(record.ledgerUntil);
        String note = Text.get("admin.ledger.told", "admin", admin, "until", until);
        ServerPlayer online = server.getPlayerList().getPlayer(player.value());
        if (online != null) online.sendSystemMessage(Component.literal(note).withStyle(net.minecraft.ChatFormatting.GOLD));
        else rt.mail.post(player, now, note);
        return new Result(true, Text.get("admin.ledger.granted", "name", name, "until", until, "minutes", DURATION_MILLIS / Durations.MINUTE));
    }

    /** Takes the grant back now. */
    public static Result revoke(MinecraftServer server, CharterRuntime rt, PlayerId player, String name, String admin) {
        PlayerRecord record = rt.state.knownPlayer(player).orElse(null);
        if (record == null || record.ledgerUntil <= rt.clock.now()) return new Result(false, Text.get("admin.ledger.none", "name", name));
        end(server, rt, player, record, "taken back by " + admin, "admin:" + admin);
        return new Result(true, Text.get("admin.ledger.revoked", "name", name));
    }

    /** Every Ledger event is recorded as an admin one, so the public Chronicle never tells who could read the block history. */
    private static void end(MinecraftServer server, CharterRuntime rt, PlayerId player, PlayerRecord record, String why, String actor) {
        record.ledgerUntil = 0;
        if (luckPermsPresent(server)) run(server, revokeCommands(player.value()));
        rt.state.history.record(new ModerationEvents.LedgerRevoked(rt.clock.now(), player, why), actor, true);
        ServerPlayer online = server.getPlayerList().getPlayer(player.value());
        if (online != null) online.sendSystemMessage(Component.literal(Text.get("admin.ledger.ended")).withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    /**
     * Once in a while: a grant whose time has run out is cleared (LuckPerms has already dropped the node; the unset is a
     * safeguard), and a grant held by someone who is no longer a Lord is taken back at once.
     */
    public static void sweep(MinecraftServer server) {
        if (!CharterServer.running()) return;
        CharterRuntime rt = CharterServer.runtime();
        long now = rt.clock.now();
        for (PlayerRecord record : new ArrayList<>(rt.state.allPlayers())) {
            if (record.ledgerUntil <= 0) continue;
            if (now >= record.ledgerUntil) {
                end(server, rt, record.id, record, "it lapsed", "system");
            } else if (!LordRules.isLord(rt.state, record.id)) {
                end(server, rt, record.id, record, "they stopped being a Lord", "system");
            }
        }
    }
}
