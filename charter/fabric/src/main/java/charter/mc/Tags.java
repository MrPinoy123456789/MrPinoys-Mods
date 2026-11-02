package charter.mc;

import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.Ranks;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.Locale;
import java.util.Optional;

/**
 * A coloured [tag] in front of each player's name in chat, the tab list and over their head: their kingdom's name, or
 * Mercenary for someone with a race and no kingdom. It is a scoreboard team per tag whose prefix carries the colour, so
 * it follows the player on its own. Colours come from the {@code tagColors} knob (kingdom name lower case to a hex
 * colour, and {@code mercenary}); a kingdom with no entry gets no tag. Only teams this class made are touched, so a
 * player on someone else's team keeps it.
 *
 * <p>Ranks: once a kingdom's Lord has edited its ranks, and with {@code showRankTags} on, a member's rank follows the
 * tag, "[Faeloria] Queen Heather": the bracket in the kingdom's colour, the rank in grey. That needs one team per
 * kingdom and rank (a kingdom has at most {@value Ranks#MAX_RANKS} ranks), since a team's prefix is the only thing
 * both vanilla Java clients and Geyser draw before a name. A kingdom whose ranks were never edited keeps its one team
 * and the tag exactly as before. With {@code rankInChat} off, chat shows the kingdom tag alone (see
 * {@link #chatBound}); the name over the head and in the tab list keep the rank.
 */
public final class Tags {

    public static final int EVERY_TICKS = 40;
    static final String PREFIX = "ct_";
    static final String MERCENARY = "mercenary";
    /** The colour of the rank after the kingdom tag: a neutral light grey, which Bedrock has as its own colour code. */
    static final ChatFormatting RANK_COLOUR = ChatFormatting.GRAY;

    private Tags() {
    }

    public static void apply(MinecraftServer server, CharterRuntime rt) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) applyTo(player, rt);
        sweep(server.getScoreboard());
    }

    /**
     * Drops our teams nobody is on: a removed rank's, or a kingdom's plain team once its ranks were edited. That keeps
     * the count of teams to the tags in use (at most {@value Ranks#MAX_RANKS} a kingdom). Other teams are not touched.
     */
    static void sweep(Scoreboard board) {
        for (PlayerTeam team : java.util.List.copyOf(board.getPlayerTeams())) {
            if (team.getName().startsWith(PREFIX) && team.getPlayers().isEmpty()) board.removePlayerTeam(team);
        }
    }

    /** Puts the player on the team for their tag, or off ours if they have none. */
    public static void applyTo(ServerPlayer player, CharterRuntime rt) {
        Scoreboard board = player.level().getServer().getScoreboard();
        PlayerId id = new PlayerId(player.getUUID());
        String key = keyOf(rt, id);
        String colour = key == null ? null : rt.knobs.tagColors.get(key);
        PlayerTeam current = board.getPlayersTeam(player.getScoreboardName());
        if (colour == null) {
            if (current != null && current.getName().startsWith(PREFIX)) board.removePlayerFromTeam(player.getScoreboardName(), current);
            return;
        }
        // Someone else's team: leave the player there, and make no team of ours for the sweep to drop again.
        if (current != null && !current.getName().startsWith(PREFIX)) return;
        Optional<Ranks.Rank> rank = MERCENARY.equals(key) ? Optional.empty() : shownRank(rt, id);
        String teamName = teamName(key, rank.map(Ranks.Rank::id).orElse(null));
        PlayerTeam team = board.getPlayerTeam(teamName);
        if (team == null) team = board.addPlayerTeam(teamName);
        Component prefix = prefix(rt, key, colour, rank.map(Ranks.Rank::name).orElse(null));
        if (!prefix.equals(team.getPlayerPrefix())) team.setPlayerPrefix(prefix);
        if (current == team) return;
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

    /** The rank to show after the tag: only with the knob on and in a kingdom whose ranks were edited. */
    public static Optional<Ranks.Rank> shownRank(CharterRuntime rt, PlayerId player) {
        if (!rt.knobs.showRankTags) return Optional.empty();
        Optional<OrgId> kingdom = Ranks.kingdomOf(rt.state, player);
        if (kingdom.isEmpty() || !rt.state.ranks.edited(kingdom.get())) return Optional.empty();
        return rt.state.ranks.rankOf(rt.state, player);
    }

    /**
     * The team for a tag: {@code ct_<kingdom>} without a rank, as before ranks; with one, a short kingdom part with a
     * hash (so two kingdoms that share a beginning never share a team) and the rank: at most 16 characters.
     */
    static String teamName(String key, String rankId) {
        if (rankId == null) return PREFIX + key.substring(0, Math.min(key.length(), 12));
        String hash = Integer.toString(Math.floorMod(key.hashCode(), 36 * 36), 36);
        if (hash.length() < 2) hash = "0" + hash;
        String code = rankId.equals(Ranks.TOP) ? "t" : rankId.equals(Ranks.DEFAULT) ? "d" : rankId;
        return PREFIX + key.substring(0, Math.min(key.length(), 6)) + hash + "." + code;
    }

    /** "[Everdelle] " in its colour, then the rank in grey when one is shown: "[Everdelle] Princess ". */
    static Component prefix(CharterRuntime rt, String key, String colour, String rank) {
        MutableComponent tag = kingdomPart(rt, key, colour);
        if (rank != null && !rank.isBlank()) tag.append(Component.literal(rank + " ").withStyle(RANK_COLOUR));
        return tag;
    }

    private static MutableComponent kingdomPart(CharterRuntime rt, String key, String colour) {
        String label = MERCENARY.equals(key) ? Text.get("tag.mercenary")
                : rt.state.orgNamed(key).map(charter.org.Organization::name).orElse(key);
        TextColor parsed = parse(colour);
        return Component.literal("[" + label + "] ").withStyle(Style.EMPTY.withColor(parsed == null ? TextColor.fromRgb(0xFFFFFF) : parsed));
    }

    /** The plain text a player's name shows with, "[Faeloria] Queen Heather": what tests and the docs mean by the tag. */
    public static String tagText(CharterRuntime rt, PlayerId player, String name) {
        String key = keyOf(rt, player);
        String colour = key == null ? null : rt.knobs.tagColors.get(key);
        if (colour == null) return name;
        Optional<Ranks.Rank> rank = MERCENARY.equals(key) ? Optional.empty() : shownRank(rt, player);
        return prefix(rt, key, colour, rank.map(Ranks.Rank::name).orElse(null)).getString() + name;
    }

    /**
     * With {@code rankInChat} off, the sender's name in a chat message without the rank: the kingdom tag and the name,
     * keeping the click and hover vanilla puts on a name. Null when nothing changes (the knob is on, no rank is shown,
     * or the name is already the plain one), so the message goes out exactly as vanilla built it.
     */
    public static ChatType.Bound chatBound(ServerPlayer sender, ChatType.Bound bound) {
        if (sender == null || bound == null || !CharterServer.running()) return null;
        CharterRuntime rt = CharterServer.runtime();
        if (rt.knobs.rankInChat) return null;
        PlayerId id = new PlayerId(sender.getUUID());
        String key = keyOf(rt, id);
        String colour = key == null ? null : rt.knobs.tagColors.get(key);
        if (colour == null || MERCENARY.equals(key) || shownRank(rt, id).isEmpty()) return null;
        Component display = sender.getDisplayName();
        MutableComponent name = Component.empty().withStyle(display.getStyle());
        name.append(kingdomPart(rt, key, colour)).append(sender.getName());
        if (name.equals(bound.name())) return null;
        return new ChatType.Bound(bound.chatType(), name, bound.targetName());
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
