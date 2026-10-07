package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Which acts a player may be offered dungeons from (design D8), and what clearing
 * a capstone opens (D16). The live half of {@link ActProgress}: the rules are there,
 * the per player record is {@link DungeonLog.Campaign}.
 */
final class DungeonProgress {

    private DungeonProgress() {}

    /**
     * The acts the party leader has open. The leader's progress sets the choices
     * (D15), so offers always read this for the record owner. Act 1 is always in it.
     */
    static Set<Integer> unlockedActs(MinecraftServer server, UUID leader) {
        return new TreeSet<>(DungeonLog.forServer(server).get(leader).campaign().actsUnlocked());
    }

    /**
     * A capstone dungeon was just finished by {@code member} (online and in the
     * instance). Opens the next act for them, or sets the campaign complete flag
     * for an act 5 capstone, tells them in chat and on screen, and writes the
     * journal event.
     *
     * @return the act newly opened, or 0 when nothing was (already open, or act 5)
     */
    static int onCapstoneCleared(MinecraftServer server, InstanceRecord record, ServerPlayer member,
                                 DungeonDef def) {
        DungeonLog log = DungeonLog.forServer(server);
        UUID id = member.getUUID();
        if (ActProgress.completesCampaign(def.act())) {
            boolean newly = log.markCampaignComplete(id);
            if (newly) {
                member.sendSystemMessage(Component.literal(
                        "The campaign is complete. He got away, and Alex stayed behind to look for him. Nothing in the dark"
                        + " is whole yet. The search continues.")
                        .withStyle(ChatFormatting.GOLD));
                PlaytestJournal.actUnlocked(member, record, def.id(), 0, true);
            }
            return 0;
        }
        int next = ActProgress.unlockedByCapstone(def.act());
        if (next == 0 || !log.unlockAct(id, next)) {
            return 0;
        }
        String label = ActProgress.label(next) + " is open";
        member.sendSystemMessage(Component.literal(label + ". Its dungeons now appear at the first door.")
                .withStyle(ChatFormatting.GOLD));
        StaggeredTitle.show(server, id, Component.literal(label).withStyle(ChatFormatting.GOLD),
                java.util.List.of(), ChatFormatting.GRAY);
        PlaytestJournal.actUnlocked(member, record, def.id(), next, false);
        return next;
    }
}
