package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * The live dungeon of one lodestone (decision 2026-10-30). Phase 1 only answers the menu: entering and leaving say a line
 * in chat so the screens can be played before the run exists. Phase 2 builds the real thing.
 */
final class WildRun {

    private WildRun() {}

    /** Players inside the live dungeon of this lodestone. Phase 1 has no dungeons, so none. */
    static int insideCount(String lodestoneId) {
        return 0;
    }

    static void enter(ServerPlayer player, String lodestoneId) {
        player.sendSystemMessage(Component.literal("Entering is not built yet.").withStyle(ChatFormatting.GRAY));
    }

    static void leave(ServerPlayer player) {
        player.sendSystemMessage(Component.literal("Leaving is not built yet.").withStyle(ChatFormatting.GRAY));
    }
}
