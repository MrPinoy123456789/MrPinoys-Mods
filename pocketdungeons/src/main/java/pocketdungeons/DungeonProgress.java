package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Which acts a player may be offered dungeons from (design D8), and what a
 * finish opens (D16, reworked by D29). The live half of {@link ActProgress}:
 * the rules are there, the per player record is {@link DungeonLog.Campaign}.
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
     * Progress was just made by {@code member} (a dungeon finished, or an
     * Endless Mine floor cleared): re-evaluates every act against their
     * finished dungeons and deepest Mine floor (D29) and opens each newly
     * complete act's successor, or sets the campaign complete flag when act 5
     * is complete. Tells them in chat and on screen and writes the journal
     * event. When nothing opened, names what the lowest incomplete act still
     * asks for. Never revokes.
     *
     * @param announceRemaining when nothing opened, name what the lowest
     *                          incomplete act still asks for (a finish's
     *                          "what is left" line)
     * @return the act newly opened, or 0 when none was
     */
    static int onProgress(MinecraftServer server, InstanceRecord record, ServerPlayer member,
                          boolean announceRemaining) {
        return onProgress(server, record, member, announceRemaining, false);
    }

    /**
     * As {@link #onProgress(MinecraftServer, InstanceRecord, ServerPlayer, boolean)};
     * {@code newFinish} marks the first time this member finished the
     * record's dungeon, which earns a milestone title when no act opened.
     */
    static int onProgress(MinecraftServer server, InstanceRecord record, ServerPlayer member,
                          boolean announceRemaining, boolean newFinish) {
        DungeonLog log = DungeonLog.forServer(server);
        UUID id = member.getUUID();
        DungeonLog.Entry entry = log.get(id);
        Set<Integer> open = entry.campaign().actsUnlocked();
        Set<String> finished = entry.dungeonsFinished();
        int depth = entry.campaign().deepestMineFloor();
        Collection<DungeonDef> all = DungeonDefs.current().all();
        String dungeonId = record == null ? "" : record.interval.dungeonId;

        int opened = 0;
        for (int act = DungeonDef.MIN_ACT; act < DungeonDef.MAX_ACT; act++) {
            int next = act + 1;
            if (open.contains(next) || !ActProgress.complete(act, finished, depth, all)) {
                continue;
            }
            if (!log.unlockAct(id, next)) {
                continue;
            }
            String label = ActProgress.label(next) + " is open";
            member.sendSystemMessage(Component.literal(label + ". Its dungeons now appear at the first door.")
                    .withStyle(ChatFormatting.GOLD));
            StaggeredTitle.show(server, id, Component.literal(label).withStyle(ChatFormatting.GOLD),
                    java.util.List.of(), ChatFormatting.GRAY);
            PlaytestJournal.actUnlocked(member, record, dungeonId, next, false);
            opened = next;
        }

        if (ActProgress.complete(DungeonDef.MAX_ACT, finished, depth, all)
                && log.markCampaignComplete(id)) {
            member.sendSystemMessage(Component.literal(
                    "The campaign is complete. He got away, and Alex stayed behind to look for him. Nothing in the dark"
                    + " is whole yet. The search continues.")
                    .withStyle(ChatFormatting.GOLD));
            PlaytestJournal.actUnlocked(member, record, dungeonId, 0, true);
        }

        if (opened == 0) {
            milestoneTitle(server, id, record, finished, depth, all, newFinish, announceRemaining);
        }

        if (opened == 0 && announceRemaining) {
            // Name what is left on the lowest incomplete act, so a finish that
            // opens nothing still says why.
            for (int act = DungeonDef.MIN_ACT; act <= DungeonDef.MAX_ACT; act++) {
                String left = ActProgress.remainingLine(act, finished, depth, all);
                if (!left.isEmpty()) {
                    member.sendSystemMessage(Component.literal(left).withStyle(ChatFormatting.GRAY));
                    break;
                }
            }
        }
        return opened;
    }

    /**
     * Big on-screen text for a milestone that did not open an act: the first
     * finish of a dungeon (its name, then where the act stands) and the Mine
     * reaching an act's depth target. An act opening has its own title.
     */
    private static void milestoneTitle(MinecraftServer server, UUID id, InstanceRecord record,
                                       Set<String> finished, int depth, Collection<DungeonDef> all,
                                       boolean newFinish, boolean finishCall) {
        if (finishCall) {
            DungeonDef def = record == null ? null : DungeonDefs.current().byId(record.interval.dungeonId);
            if (newFinish && def != null) {
                StaggeredTitle.show(server, id,
                        Component.literal(def.name() + " cleared").withStyle(ChatFormatting.GOLD),
                        java.util.List.of("Act " + def.act() + ": "
                                + ActProgress.progressLine(def.act(), finished, depth, all)),
                        ChatFormatting.GRAY);
            }
            return;
        }
        for (int act = DungeonDef.MIN_ACT; act < DungeonDef.MAX_ACT; act++) {
            if (ActProgress.mineTarget(act) > 0 && depth == ActProgress.mineTarget(act)) {
                StaggeredTitle.show(server, id,
                        Component.literal("Mine floor " + depth + " reached").withStyle(ChatFormatting.GOLD),
                        java.util.List.of("Act " + act + ": "
                                + ActProgress.progressLine(act, finished, depth, all)),
                        ChatFormatting.GRAY);
                return;
            }
        }
    }
}
