package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The big text when a floor commits (playtest 2026-10-02-1): the floor's name
 * on screen, then each affix stacking under it one at a time, with a low thud
 * for every line. The beat machinery is {@link StaggeredTitle}, shared with
 * every other big title; this class only decides what the floor start says.
 */
final class FloorStartTitle {

    private FloorStartTitle() {}

    /** Starts the sequence for every online member of {@code record}'s party. */
    static void show(MinecraftServer server, InstanceRecord record) {
        if (server == null || record == null) {
            return;
        }
        DungeonDef def = isHome(record.floor.theme) || record.interval.dungeonId == null
                ? null : DungeonDefs.current().byId(record.interval.dungeonId);
        DungeonDef.Node node = def == null ? null : def.node(record.interval.nodeId);
        String themeName = titleFor(record.floor.theme);
        String floorName = node == null ? null : node.name();
        Component title = Component.literal(titleFor(themeName, floorName)).withStyle(ChatFormatting.GOLD);
        List<String> affixes = new ArrayList<>();
        // PD-173: the title is the floor's own name now, so the first floor of a trip names the
        // dungeon under it; later floors already know where they are.
        String dungeonLine = dungeonLine(themeName, floorName, record.interval.path.size());
        if (dungeonLine != null) {
            affixes.add(dungeonLine);
        }
        if (!isHome(record.floor.theme)) {
            affixes.addAll(affixLabels(record.floor.affixes));
        }
        for (UUID member : record.members.keySet()) {
            StaggeredTitle.show(server, member, title, affixes, ChatFormatting.LIGHT_PURPLE);
        }
    }

    /**
     * PD-129 (playtest 2026-10-03-2): the big title. Entering through the
     * lodestone lands in the home room, which has no floor theme yet, and
     * {@link DungeonScreen#themeName}'s fallback greeted the player with
     * "Uncharted", read as both a place and as something missing. Home says
     * HOME, the same word the homecoming title uses.
     */
    static String titleFor(String theme) {
        return isHome(theme) ? "HOME" : DungeonScreen.themeName(theme).toUpperCase();
    }

    /**
     * PD-173: the big title of a floor. The floor's own node name when it has one (the player
     * asked twice what a floor was called; the dungeon name alone repeated on every floor), else
     * the dungeon's name as before.
     */
    static String titleFor(String themeTitle, String floorName) {
        return floorName == null || floorName.isBlank() || "HOME".equals(themeTitle)
                ? themeTitle : floorName.toUpperCase();
    }

    /**
     * The line under the floor name that says which dungeon this is, on the first floor of a trip
     * only; {@code null} when there is nothing to add.
     */
    static String dungeonLine(String themeTitle, String floorName, int floorsOnPath) {
        if (floorName == null || floorName.isBlank() || "HOME".equals(themeTitle) || floorsOnPath > 1
                || floorName.equalsIgnoreCase(themeTitle)) {
            return null;
        }
        return themeTitle;
    }

    private static boolean isHome(String theme) {
        return theme == null || theme.isEmpty();
    }

    private static List<String> affixLabels(java.util.Collection<String> ids) {
        List<String> labels = new ArrayList<>();
        if (ids == null) {
            return labels;
        }
        for (String id : ids) {
            String label = FloorHistory.words(id);
            for (AffixDefinition def : AffixManifest.current().definitions()) {
                if (def.id.equals(id)) {
                    label = def.label.toUpperCase();
                    break;
                }
            }
            labels.add(label);
        }
        return labels;
    }
}
