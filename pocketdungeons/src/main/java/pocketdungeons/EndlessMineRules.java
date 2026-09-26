package pocketdungeons;

/**
 * M78: what is left of the Endless Mine's own ruleset once the zone rules hook
 * ({@link ZoneRules}) carries the rest. The Mine is a Cube recipe that turns an
 * interval into a descent: every floor is stamped in the Mine theme, previous
 * floors close behind the party, and the haul gets richer with depth.
 *
 * <p>What the hook expresses, in the Mine theme's {@code rules} block
 * ({@code data/pocketdungeons/dungeon_theme/endless_mine.json}): the loot tier
 * escalating one step every three floors since the last bank, capped at the
 * top tier, and its role as the materials faucet. What it cannot, and so stays
 * here: the recipe forcing the Mine theme onto every floor of the interval
 * ({@link #effectiveTheme}), which is how a Mine interval comes to run under
 * the Mine zone at all, the depth recorded on a run record, and the Mine's
 * own words at its commitment surfaces.
 *
 * <p>There is no Mine exit of its own any more: every checkpoint in every zone
 * offers the HOME lever, and {@code /dungeon cashout} is that lever typed.
 * Banking uses the same average-of-doors rule everywhere
 * ({@link IntervalBanking}), so the Mine cannot out-level the ordinary loop;
 * its reward is the escalating haul and a displayable depth, never a higher
 * power ceiling.
 *
 * <p>Pure policy: no Minecraft state. The Mine flag lives on the interval
 * ({@link IntervalState#endlessMine}), in memory like the rest of the record.
 */
final class EndlessMineRules {

    private EndlessMineRules() {}

    /** The theme id the Mine recipe forces onto every Mine floor. */
    static final String MINE_THEME_ID = "pocketdungeons:endless_mine";

    /** Whether the record is an active Mine run. */
    static boolean isMine(InstanceRecord record) {
        return record != null && record.interval.endlessMine;
    }

    /** Whether the resolved recipe plan opens a Mine. */
    static boolean isMine(RunRecipePlan plan) {
        return plan != null && plan.endlessMine;
    }

    /**
     * The theme id a floor should be stamped at. A Mine run (already flagged
     * on the record, or flagged by the recipe plan being previewed) forces the
     * Mine theme on every floor, so the recipe being cleared after the first
     * commit does not drop the Mine look, or the Mine's zone rules, on later
     * floors.
     */
    static String effectiveTheme(String offerTheme, InstanceRecord record,
                                 RunRecipePlan previewPlan) {
        if (isMine(record) || isMine(previewPlan)) {
            return MINE_THEME_ID;
        }
        return offerTheme;
    }

    /**
     * The depth to record on the run record when a Mine interval banks, or 0
     * for an ordinary run. The depth is the floor index reached, the
     * displayable Mine record a memento can carry without granting power.
     */
    static int cashOutDepth(InstanceRecord record) {
        return isMine(record) ? Math.max(0, record.interval.floorIndex) : 0;
    }

    // ---- commitment surface text (pure strings; callers wrap in Component) ----

    /**
     * Published when a Mine run first commits, so the owner knows the rules
     * before descending. Names the risk (no final floor, previous floors
     * close) and the reward (escalating haul) without explaining the spatial
     * room movement, the same silence the ordinary loop keeps.
     */
    static String mineStartMessage() {
        return "Endless Mine. There is no final floor, and the haul grows richer the deeper you go. "
                + "Every checkpoint offers a door deeper, or the HOME lever to leave with what you carry. "
                + "Previous floors close behind you.";
    }

    /**
     * Published at each Mine checkpoint, naming the depth reached and the
     * loot tier. This is the commitment surface: the player sees the risk
     * (deeper is further from the exit) and the reward (the tier) before
     * choosing a door or going home.
     */
    static String mineCheckpointMessage(int floorIndex, int tier) {
        return "Mine floor " + floorIndex + " cleared. Loot tier " + tier
                + ". Walk a door to descend, or pull the HOME lever to leave with your haul.";
    }

    /** Said when a Mine interval banks, naming the depth reached. */
    static String cashOutMessage(int depth) {
        return "You leave the Mine with " + depth + " floor" + (depth == 1 ? "" : "s") + " banked.";
    }
}
