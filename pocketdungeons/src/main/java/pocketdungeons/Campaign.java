package pocketdungeons;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * The seam between the campaign (Acts 0 to {@code releaseMaxAct}) and the endgame that follows it
 * (decision 2026-10-18, "Campaign and endgame"). Everything that differs between the two branches on
 * {@link #done}; everything that must not apply to the endgame's own trips asks {@link #isCampaignTrip}.
 *
 * <p>The helpers take their numbers as arguments so they test with plain {@code javac}; the no argument
 * forms read the two knobs ({@code releaseMaxAct}, {@code capstoneDungeonsNeeded}). Acts are never taken
 * from a save: a stored act above {@code releaseMaxAct} stays stored and is simply unreachable.
 */
final class Campaign {

    private Campaign() {}

    // ---- the knobs ---------------------------------------------------------------------

    /** The last act on this server ({@code releaseMaxAct}, 2). */
    static int releaseMaxAct() {
        return PocketDungeonsConfig.releaseMaxAct();
    }

    /** How many ordinary dungeons of an act open its capstone ({@code capstoneDungeonsNeeded}, 3). */
    static int capstoneDungeonsNeeded() {
        return PocketDungeonsConfig.capstoneDungeonsNeeded();
    }

    // ---- acts --------------------------------------------------------------------------

    /** Whether {@code act} can open on a server whose last act is {@code maxAct}. Act 0 (the prologue) always can. */
    static boolean released(int act, int maxAct) {
        return act <= maxAct;
    }

    /** {@code acts} without any act above {@code maxAct}; the prologue (act 0) and the released acts stay. */
    static Set<Integer> cap(Set<Integer> acts, int maxAct) {
        Set<Integer> out = new TreeSet<>();
        for (int act : acts) {
            if (released(act, maxAct)) {
                out.add(act);
            }
        }
        return out;
    }

    /**
     * How many of an act's ordinary dungeons must be finished before its capstone opens: the knob
     * {@code configured}, but never more than the {@code available} dungeons the act has (an act with
     * two ordinary dungeons asks for two), and 0 for an act that has none.
     */
    static int needed(int available, int configured) {
        return Math.max(0, Math.min(available, Math.max(1, configured)));
    }

    // ---- the end of the campaign ----------------------------------------------------------

    /**
     * Whether the campaign is done for a player who has finished {@code finished} (bare or namespaced ids):
     * the capstone of act {@code maxAct} is among them ({@link ActProgress#capstoneFinished}). An act with
     * nothing declared is never done, so a typo in {@code releaseMaxAct} cannot hand out the endgame.
     */
    static boolean done(Set<String> finished, Collection<DungeonDef> all, int maxAct) {
        return ActProgress.capstoneFinished(maxAct, finished, all);
    }

    /** {@link #done(Set, Collection, int)} for the live data and the knob. */
    static boolean done(Set<String> finished, Collection<DungeonDef> all) {
        return done(finished, all, releaseMaxAct());
    }

    /** Whether the campaign is done for the player behind {@code entry}. */
    static boolean done(DungeonLog.Entry entry) {
        return done(entry.dungeonsFinished(), DungeonDefs.current().all());
    }

    // ---- which trips the campaign rules apply to -------------------------------------------

    /**
     * Whether a trip in {@code def} (null before the first door) is a campaign trip: not an uncharted
     * door and not a descent (the endgame dive, an Endless Mine or the Proving).
     */
    static boolean isCampaignTrip(DungeonDef def) {
        return def == null || (!def.kind().descends() && !isUncharted(def));
    }

    /**
     * Whether {@code record}'s trip is a campaign trip: not an uncharted door and not the endgame dive.
     * A trip that has not taken its first door counts as a campaign trip; no record is no trip.
     */
    static boolean isCampaignTrip(InstanceRecord record) {
        if (record == null || EndlessMineRules.isMine(record)
                || EndlessMineRules.isMineId(record.interval.dungeonId)) {
            return false;
        }
        return isCampaignTrip(TripView.def(record));
    }

    /**
     * Whether {@code def} is a generated uncharted door. There are none yet; the Chart Room task replaces
     * this with the generated dungeon's marker, and every caller of {@link #isCampaignTrip} follows.
     */
    static boolean isUncharted(DungeonDef def) {
        return false;
    }
}
