package pocketdungeons;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which kingdom cleared a wild dungeon (decision 2026-11-04), read without a dependency on Charter: Charter puts every
 * member on a scoreboard team named {@code ct_<kingdom>} whose prefix is the coloured tag ("[Aurelia] ", then the rank
 * in grey), so this reads the team's prefix through the vanilla scoreboard. A player on no such team counts by name.
 * The pure parts ({@link #labelOf}, {@link #majority}) are tested by {@code WildKingdomsTest}.
 */
final class WildKingdoms {

    /** Charter's team name prefix ({@code charter.mc.Tags.PREFIX}). */
    static final String CHARTER_TEAM_PREFIX = "ct_";

    /** One player's side: the kingdom's tag label and colour (0xRRGGBB, -1 for none), or a player's own name. */
    record Side(String name, int colour) {}

    private WildKingdoms() {}

    /** The side a player is on: their Charter tag, else their own name with no colour. */
    static Side of(ServerPlayer player) {
        PlayerTeam team = player.level().getServer().getScoreboard().getPlayersTeam(player.getScoreboardName());
        if (team != null && team.getName().startsWith(CHARTER_TEAM_PREFIX)) {
            Component prefix = team.getPlayerPrefix();
            String label = labelOf(prefix.getString());
            if (label != null) {
                TextColor colour = prefix.getStyle().getColor();
                return new Side(plural(label), colour == null ? -1 : colour.getValue() & 0xFFFFFF);
            }
        }
        return new Side(player.getName().getString(), -1);
    }

    /** The label inside a tag's brackets: "[Aurelia] Princess " gives "Aurelia"; no brackets gives {@code null}. */
    static String labelOf(String prefixText) {
        if (prefixText == null) {
            return null;
        }
        String text = prefixText.trim();
        int close = text.indexOf(']');
        if (!text.startsWith("[") || close < 2) {
            return null;
        }
        return text.substring(1, close).trim();
    }

    /** Reads better on the menu: "Last cleared by Mercenaries", not "by Mercenary". */
    static String plural(String label) {
        return "Mercenary".equalsIgnoreCase(label) ? "Mercenaries" : label;
    }

    /**
     * The side with the most players inside; a tie goes to the side listed first. A kingdom always beats a lone
     * player's name when they tie. {@code null} for nobody.
     */
    static Side majority(List<Side> sides) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Side> first = new LinkedHashMap<>();
        for (Side side : sides) {
            counts.merge(side.name(), 1, Integer::sum);
            first.putIfAbsent(side.name(), side);
        }
        Side best = null;
        int bestCount = 0;
        for (Map.Entry<String, Side> entry : first.entrySet()) {
            int count = counts.get(entry.getKey());
            Side side = entry.getValue();
            boolean better = count > bestCount
                    || (count == bestCount && best != null && best.colour() < 0 && side.colour() >= 0);
            if (better) {
                best = side;
                bestCount = count;
            }
        }
        return best;
    }
}
