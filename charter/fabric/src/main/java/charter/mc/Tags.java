package charter.mc;

import charter.id.PlayerId;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.Locale;

/**
 * A coloured [tag] in front of each player's name in chat, the tab list and over their head: their kingdom's name, or
 * Mercenary for someone with a race and no kingdom. It is a scoreboard team per tag whose prefix carries the colour, so
 * it follows the player on its own. Colours come from the {@code tagColors} knob (kingdom name lower case to a hex
 * colour, and {@code mercenary}); a kingdom with no entry gets no tag. Only teams this class made are touched, so a
 * player on someone else's team keeps it.
 */
public final class Tags {

    public static final int EVERY_TICKS = 40;
    static final String PREFIX = "ct_";
    static final String MERCENARY = "mercenary";

    private Tags() {
    }

    public static void apply(MinecraftServer server, CharterRuntime rt) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) applyTo(player, rt);
    }

    /** Puts the player on the team for their tag, or off ours if they have none. */
    public static void applyTo(ServerPlayer player, CharterRuntime rt) {
        Scoreboard board = player.level().getServer().getScoreboard();
        String key = keyOf(rt, new PlayerId(player.getUUID()));
        String colour = key == null ? null : rt.knobs.tagColors.get(key);
        PlayerTeam current = board.getPlayersTeam(player.getScoreboardName());
        if (colour == null) {
            if (current != null && current.getName().startsWith(PREFIX)) board.removePlayerFromTeam(player.getScoreboardName(), current);
            return;
        }
        String teamName = PREFIX + key.substring(0, Math.min(key.length(), 12));
        PlayerTeam team = board.getPlayerTeam(teamName);
        if (team == null) team = board.addPlayerTeam(teamName);
        Component prefix = prefix(rt, key, colour);
        if (!prefix.equals(team.getPlayerPrefix())) team.setPlayerPrefix(prefix);
        if (current == team) return;
        if (current != null && !current.getName().startsWith(PREFIX)) return; // someone else's team
        board.addPlayerToTeam(player.getScoreboardName(), team);
    }

    /** The tag key: the kingdom's name in lower case, mercenary, or null for no tag. */
    static String keyOf(CharterRuntime rt, PlayerId player) {
        var membership = rt.state.membership(player);
        if (membership.isPresent()) {
            return rt.state.org(rt.state.root(membership.get().org())).map(o -> o.name().toLowerCase(Locale.ROOT)).orElse(null);
        }
        return rt.state.raceOf(player).isPresent() ? MERCENARY : null;
    }

    /** "[Everdelle] " in its colour. */
    static Component prefix(CharterRuntime rt, String key, String colour) {
        String label = MERCENARY.equals(key) ? Text.get("tag.mercenary")
                : rt.state.orgNamed(key).map(charter.org.Organization::name).orElse(key);
        TextColor parsed = parse(colour);
        return Component.literal("[" + label + "] ").withStyle(Style.EMPTY.withColor(parsed == null ? TextColor.fromRgb(0xFFFFFF) : parsed));
    }

    static TextColor parse(String hex) {
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            return TextColor.fromRgb(Integer.parseInt(h, 16));
        } catch (RuntimeException bad) {
            return null;
        }
    }
}
