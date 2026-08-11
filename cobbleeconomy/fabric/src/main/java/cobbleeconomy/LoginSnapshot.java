package cobbleeconomy;

import cobbleeconomy.core.Currency;
import cobbleeconomy.core.CurrencyRegistry;
import cobbleeconomy.core.LeaderboardService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The wealth snapshot a player sees when they join.
 *
 * <p>Uses the same {@link LeaderboardService} as {@code /baltop}, which is the whole
 * requirement of section 16: two ranking implementations would eventually disagree,
 * and a leaderboard that contradicts itself is worse than no leaderboard.
 *
 * <p><b>The delay is the interesting part.</b> Sending on the join event puts the
 * message above the MOTD, the resource pack prompt, the "player joined the game"
 * lines, and whatever else the server says on connect -- so it scrolls away before
 * anyone reads it. Queuing it for a couple of seconds of ticks lands it in calm chat.
 * Pending sends are keyed by UUID so a player who disconnects in that window is simply
 * dropped rather than crashing on a stale player object.
 */
public final class LoginSnapshot {

    private final LeaderboardService leaderboard;
    private final CurrencyRegistry currencies;
    private final NameCache names;
    private final EconomySettings settings;

    /** Players waiting for their snapshot, with the tick it is due on. */
    private final List<Pending> pending = new ArrayList<>();

    private record Pending(UUID player, long dueTick) {}

    public LoginSnapshot(LeaderboardService leaderboard, CurrencyRegistry currencies,
                         NameCache names, EconomySettings settings) {
        this.leaderboard = leaderboard;
        this.currencies = currencies;
        this.names = names;
        this.settings = settings;
    }

    /** Queue a joining player. Once per login, never repeated. */
    public void onJoin(ServerPlayer player, long currentTick) {
        if (!settings.leaderboardsEnabled || !settings.showOnLogin) return;
        pending.add(new Pending(player.getUUID(), currentTick + settings.loginDelayTicks));
    }

    /** Called each server tick. Cheap: usually an empty list. */
    public void tick(MinecraftServer server, long currentTick) {
        if (pending.isEmpty()) return;

        pending.removeIf(entry -> {
            if (currentTick < entry.dueTick()) return false;
            ServerPlayer player = server.getPlayerList().getPlayer(entry.player());
            // Null means they left during the delay. Drop it silently.
            if (player != null) send(player);
            return true;
        });
    }

    private void send(ServerPlayer player) {
        int limit = Math.max(1, settings.loginTopCount);
        List<Component> lines = new ArrayList<>();

        for (Currency currency : currencies.all()) {
            List<LeaderboardService.Rank> rows = leaderboard.top(currency, limit);
            if (rows.isEmpty()) continue;

            lines.add(Component.literal(symbolOf(currency) + " " + currency.displayName())
                    .withStyle(Messages.HEADING));
            for (LeaderboardService.Rank row : rows) {
                lines.add(Messages.rankLine(row.rank(), names.nameOf(row.player()),
                        currency, row.balance(), row.player().equals(player.getUUID())));
            }
        }

        // A brand new server has nothing to show. Staying quiet beats an empty box.
        if (lines.isEmpty()) return;

        player.sendSystemMessage(Messages.rule());
        player.sendSystemMessage(Messages.title("SERVER WEALTH"));
        for (Component line : lines) player.sendSystemMessage(line);
        player.sendSystemMessage(Messages.rule());
    }

    /**
     * The one place emoji are used, because the spec asks for them here and a single
     * glyph per heading does the work of a word without adding a line.
     */
    private static String symbolOf(Currency currency) {
        if (currency.id().equals(CurrencyRegistry.COBBLESTONE.id())) return "\uD83E\uDEA8";
        if (currency.id().equals(CurrencyRegistry.DIAMOND.id())) return "\uD83D\uDC8E";
        return "\u2022";
    }
}
