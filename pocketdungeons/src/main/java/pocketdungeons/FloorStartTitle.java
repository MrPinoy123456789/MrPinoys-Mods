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
        Component title = Component.literal(titleFor(record.floor.theme)).withStyle(ChatFormatting.GOLD);
        List<String> affixes = isHome(record.floor.theme) ? List.of() : affixLabels(record.floor.affixes);
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
