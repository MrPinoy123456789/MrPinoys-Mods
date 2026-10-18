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
     * Acts above {@code releaseMaxAct} are left out even when the save stores them (decision
     * 2026-10-18): they stay stored and come back if the knob is raised.
     */
    static Set<Integer> unlockedActs(MinecraftServer server, UUID leader) {
        DungeonLog log = DungeonLog.forServer(server);
        if (!prologueDone(log, leader)) {
            return new TreeSet<>(Set.of(DungeonDef.PROLOGUE_ACT));
        }
        return Campaign.cap(log.get(leader).campaign().actsUnlocked(), Campaign.releaseMaxAct());
    }

    /** The {@link DungeonLog#taskProgress} key that holds a player's Act 0 state: 1 done, 2 reset to pending, 0 unset. */
    static final String PROLOGUE_KEY = "act0";

    /**
     * Whether {@code player} has finished Act 0 (The First Night). A stored 1 means done and 2 means a key reset
     * reopened it. With nothing stored, a player who already has progress (a compass above 1, a finished dungeon
     * or a later act open) counts as done, so players from before the prologue are never sent back to it.
     */
    static boolean prologueDone(DungeonLog log, UUID player) {
        int stored = log.taskProgress(player, PROLOGUE_KEY);
        if (stored == 1) {
            return true;
        }
        if (stored == 2) {
            return false;
        }
        DungeonLog.Entry entry = log.get(player);
        return entry.keystoneLevel() > 1 || !entry.dungeonsFinished().isEmpty()
                || entry.campaign().actsUnlocked().size() > 1 || entry.campaign().campaignComplete();
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
        int opened = evaluate(server, record, member, announceRemaining, newFinish);
        // Decision 2026-10-18: the compass in the campaign is a quest line, so any progress rewrites it
        // (the Act 0 finish brings the first line, a finished dungeon crosses one off).
        UUID id = member.getUUID();
        Keystone.showCompass(member, DungeonLog.forServer(server).get(id), InstanceRegistry.byMember.containsKey(id));
        return opened;
    }

    private static int evaluate(MinecraftServer server, InstanceRecord record, ServerPlayer member,
                                boolean announceRemaining, boolean newFinish) {
        DungeonLog log = DungeonLog.forServer(server);
        UUID id = member.getUUID();
        if (record != null && RunLifecycle.isPrologue(record)) {
            // Finishing The First Night completes Act 0: it is never offered again (until a key reset)
            // and Act 1 opens at the first door.
            if (log.taskProgress(id, PROLOGUE_KEY) != 1) {
                log.setTaskProgress(id, PROLOGUE_KEY, 1);
                member.sendSystemMessage(Component.literal("The First Night is behind you. Act 1 is open.")
                        .withStyle(ChatFormatting.GOLD));
            }
            return DungeonDef.MIN_ACT;
        }
        DungeonLog.Entry entry = log.get(id);
        Set<Integer> open = entry.campaign().actsUnlocked();
        Set<String> finished = entry.dungeonsFinished();
        ActProgress.MineDepth depth = entry.campaign().mineDepth();
        Collection<DungeonDef> all = DungeonDefs.current().all();
        String dungeonId = record == null ? "" : record.interval.dungeonId;

        int opened = 0;
        for (int act = DungeonDef.MIN_ACT; act < Campaign.releaseMaxAct(); act++) {
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
            StaggeredTitle.showMilestone(server, id, Component.literal(label).withStyle(ChatFormatting.GOLD),
                    java.util.List.of("Act " + act + " complete"), ChatFormatting.GRAY,
                    DungeonProgress::celebrate);
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

        if (newFinish && record != null && compassWoke(finished, record, all)) {
            // The campaign is done: the last capstone on the server was just finished for the first time.
            // The stored compass is left exactly as it is; from here it counts scrap (decision 2026-10-18).
            StaggeredTitle.showMilestone(server, id, Component.literal(WAKE_TITLE).withStyle(ChatFormatting.GOLD),
                    java.util.List.of(WAKE_SUBTITLE), ChatFormatting.GRAY, DungeonProgress::celebrate);
            return opened;
        }
        if (opened == 0 && capstoneJustUnlocked(server, member, record, open, finished, depth, all,
                announceRemaining, newFinish)) {
            return opened;
        }
        if (opened == 0) {
            milestoneTitle(server, id, record, finished, depth, all, newFinish, announceRemaining);
        }

        if (opened == 0 && announceRemaining) {
            // Name what is left on the lowest incomplete act, so a finish that
            // opens nothing still says why.
            for (int act = DungeonDef.MIN_ACT; act <= Campaign.releaseMaxAct(); act++) {
                String left = ActProgress.remainingLine(act, finished, depth, all);
                if (!left.isEmpty()) {
                    member.sendSystemMessage(Component.literal(left).withStyle(ChatFormatting.GRAY));
                    break;
                }
            }
        }
        return opened;
    }

    /** The title when the last capstone on the server is finished (decision 2026-10-18). */
    static final String WAKE_TITLE = "Your compass wakes up.";

    /** Its subtitle. */
    static final String WAKE_SUBTITLE = "The Chart Room is open.";

    /**
     * Whether the dungeon in {@code record}, just finished for the first time, is what ends the campaign:
     * the campaign is done with it in {@code finished} and was not without it. True once per player.
     */
    static boolean compassWoke(Set<String> finished, InstanceRecord record, Collection<DungeonDef> all) {
        if (!Campaign.done(finished, all)) {
            return false;
        }
        Set<String> before = new java.util.LinkedHashSet<>(finished);
        before.remove(record.interval.dungeonId);
        before.remove(DungeonDef.qualify(record.interval.dungeonId));
        before.remove(record.interval.dungeonId.replaceFirst("^[^:]*:", ""));
        return !Campaign.done(before, all);
    }

    /**
     * Big on-screen text for a milestone that did not open an act: the first
     * finish of a dungeon (its name, then where the act stands) and the Mine
     * reaching an act's depth target. An act opening has its own title.
     */
    private static void milestoneTitle(MinecraftServer server, UUID id, InstanceRecord record,
                                       Set<String> finished, ActProgress.MineDepth depth, Collection<DungeonDef> all,
                                       boolean newFinish, boolean finishCall) {
        if (finishCall) {
            DungeonDef def = record == null ? null : DungeonDefs.current().byId(record.interval.dungeonId);
            if (def != null) {
                // Playtest 2026-10-08-1: the Infestation finish title went unnoticed while the capstone's
                // (which carried the fanfare) landed. Every finish is its own beat: the banked scrap, the
                // act's progress on a first finish, and the fanfare either way.
                int banked = record.interval.finishBanked.getOrDefault(id, 0);
                java.util.List<String> lines = new java.util.ArrayList<>();
                if (!Campaign.isCampaignTrip(record)) {
                    // The campaign pays items, not scrap (decision 2026-10-18): no "Banked 0 scrap" line.
                    lines.add(IntervalBanking.finishLine(banked));
                }
                if (newFinish) {
                    lines.add("Act " + def.act() + ": "
                            + ActProgress.progressLine(def.act(), finished, depth, all));
                }
                StaggeredTitle.showMilestone(server, id,
                        Component.literal(def.name() + " finished").withStyle(ChatFormatting.GOLD),
                        lines, ChatFormatting.GRAY, DungeonProgress::celebrate);
            }
            return;
        }
        for (int act = DungeonDef.MIN_ACT; act < DungeonDef.MAX_ACT; act++) {
            int target = ActProgress.mineTarget(act, all);
            if (target > 0 && depth.floor(act) == target) {
                StaggeredTitle.showMilestone(server, id,
                        Component.literal("Mine floor " + target + " reached").withStyle(ChatFormatting.GOLD),
                        java.util.List.of("Act " + act + ": "
                                + ActProgress.progressLine(act, finished, depth, all)),
                        ChatFormatting.GRAY, Chime::keystoneLevelUp);
                return;
            }
        }
    }

    /** Fanfare for an act milestone: the toast and chime, and totem sparkles around the player. */
    private static void celebrate(ServerPlayer member) {
        Chime.fanfare(member);
        if (member.level() instanceof net.minecraft.server.level.ServerLevel level) {
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.TOTEM_OF_UNDYING,
                    member.getX(), member.getY() + 1.0, member.getZ(), 60, 0.6, 0.8, 0.6, 0.4);
        }
    }

    /**
     * The rest of an open act was just finished, so its capstone is now
     * unlocked: a title and the fanfare, once. "Just" means the state before
     * this progress (without the dungeon just finished, one Mine floor
     * shallower) had the capstone locked. Returns whether it fired.
     */
    private static boolean capstoneJustUnlocked(MinecraftServer server, ServerPlayer member,
                                                InstanceRecord record, Set<Integer> open,
                                                Set<String> finished, ActProgress.MineDepth depth,
                                                Collection<DungeonDef> all,
                                                boolean finishCall, boolean newFinish) {
        Set<String> before = new java.util.LinkedHashSet<>(finished);
        if (finishCall && newFinish && record != null) {
            before.remove(record.interval.dungeonId);
            before.remove(DungeonDef.qualify(record.interval.dungeonId));
            before.remove(record.interval.dungeonId.replaceFirst("^[^:]*:", ""));
        }
        ActProgress.MineDepth depthBefore = finishCall ? depth : depth.oneShallower();
        for (int act = DungeonDef.MIN_ACT; act <= Campaign.releaseMaxAct(); act++) {
            DungeonDef capstone = null;
            for (DungeonDef def : ActProgress.actDungeons(act, all)) {
                if (def.kind() == DungeonDef.Kind.CAPSTONE) {
                    capstone = def;
                }
            }
            if (capstone == null || !open.contains(act)
                    || finished.contains(DungeonDef.qualify(capstone.id()))
                    || finished.contains(capstone.id())) {
                continue;
            }
            if (ActProgress.capstoneReady(act, finished, depth, all)
                    && !ActProgress.capstoneReady(act, before, depthBefore, all)) {
                StaggeredTitle.showMilestone(server, member.getUUID(),
                        Component.literal("Act " + act + " trials complete").withStyle(ChatFormatting.GOLD),
                        java.util.List.of(capstone.name() + " is unlocked"), ChatFormatting.GRAY,
                        DungeonProgress::celebrate);
                member.sendSystemMessage(Component.literal("The trials of Act " + act + " are done. "
                        + capstone.name() + " now appears at the first door"
                        + (act < Campaign.releaseMaxAct() ? "; clear it to open the next act." : "."))
                        .withStyle(ChatFormatting.GOLD));
                return true;
            }
        }
        return false;
    }
}
