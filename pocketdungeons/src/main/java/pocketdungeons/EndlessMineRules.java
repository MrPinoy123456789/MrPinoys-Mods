package pocketdungeons;

/**
 * M78: the Endless Mine ruleset. The Mine is the one rule-breaking dungeon the
 * roadmap conditionally unholds: an unbounded sequence of floors with no
 * compulsory final floor, where each checkpoint offers continue (a dungeon
 * door) or a voluntary cash-out, previous floors are released and can never be
 * revisited, and only the current floor plus its bounded transition work stay
 * loaded. The Mine's reward is an escalating loot tier and a displayable depth
 * record, never a higher permanent power ceiling.
 *
 * <p>This class is the pure policy half. It owns no Minecraft state: the
 * lifecycle hooks in {@link RunLifecycle} and {@link Instances} read these
 * helpers so the Mine's transition policy stays separate from the ordinary
 * three-floor settlement. The Mine reuses the M65 silent physical exit
 * ({@link RunLifecycle#returnToSafe}) for its cash-out and the M63 journal
 * recovery for custody, so a reconnecting party finds the same floor and the
 * same cash-out option. The record stays in memory for the lifetime of the
 * server process, the same as every other {@link InstanceRecord}, so the Mine
 * flag needs no NBT sidecar of its own.
 *
 * <p>Why the Mine does not raise the power ceiling: the cash-out settles
 * through the same {@link RunLifecycle#settleSafeVisit} path as an ordinary
 * safe visit, so the keystone level change keys off the same omen finish
 * table. A deep cash-out accumulates a large omen sum (high band, no level
 * change); a shallow one behaves like a normal safe visit. The Mine's
 * differential is the escalating loot tier on its completion chests and the
 * depth recorded on the run record, not more keystone levels. The ordinary
 * loop remains the keystone progression route, so the Mine cannot become the
 * only sensible supply route.
 *
 * <p>Why previous floors cannot be revisited: {@link Instances#commitDoor}
 * already clears the previous floor's cells (via
 * {@link RunLifecycle#resetForNextDungeon}) and releases its force-load
 * tickets before the next floor is stamped, so chaining Mine floors reuses the
 * ordinary loop's per-floor release. The Mine only removes the forced safe
 * visit that would otherwise interrupt the chain.
 */
final class EndlessMineRules {

    private EndlessMineRules() {}

    /** The theme id the Mine recipe forces onto every Mine floor. */
    static final String MINE_THEME_ID = "pocketdungeons:endless_mine";

    /** Whether the record is an active Mine run. */
    static boolean isMine(InstanceRecord record) {
        return record != null && record.endlessMine;
    }

    /** Whether the resolved recipe plan opens a Mine. */
    static boolean isMine(RunRecipePlan plan) {
        return plan != null && plan.endlessMine;
    }

    /**
     * The theme id a floor should be stamped at. A Mine run (already flagged
     * on the record, or flagged by the recipe plan being previewed) forces the
     * Mine theme on every floor, so the recipe being cleared after the first
     * commit does not drop the Mine look on later floors.
     */
    static String effectiveTheme(String offerTheme, InstanceRecord record,
                                 RunRecipePlan previewPlan) {
        if (isMine(record) || isMine(previewPlan)) {
            return MINE_THEME_ID;
        }
        return offerTheme;
    }

    /**
     * Whether {@link RunLifecycle#advanceFloor} should stamp a safe staging
     * room at this floor. The ordinary loop forces one every
     * {@code floorsPerSafeVisit} floors; the Mine never forces one, so every
     * checkpoint offers continue doors and the cash-out stays voluntary.
     */
    static boolean shouldForceSafeStaging(InstanceRecord record, int floorsPerSafeVisit) {
        if (isMine(record)) {
            return false;
        }
        int cycle = Math.max(1, floorsPerSafeVisit);
        return record.floorIndex % cycle == 0;
    }

    /**
     * The loot tier for a Mine floor's completion chests. The Mine escalates
     * the tier with depth, one step per {@code floorsPerSafeVisit} floors,
     * capped at the top tier (3) so the materials get better but never leave
     * the table the ordinary loop draws from. A non-Mine floor returns the
     * base tier unchanged.
     */
    static int completionLootTier(InstanceRecord record, int baseTier, int floorsPerSafeVisit) {
        if (!isMine(record)) {
            return baseTier;
        }
        int cycle = Math.max(1, floorsPerSafeVisit);
        int escalated = baseTier + record.floorIndex / cycle;
        return Math.min(3, escalated);
    }

    /**
     * The depth to record on a cash-out run record, or 0 for an ordinary run.
     * The depth is the floor index reached, the displayable Mine record a
     * memento can carry without granting power.
     */
    static int cashOutDepth(InstanceRecord record) {
        return isMine(record) ? Math.max(0, record.floorIndex) : 0;
    }

    // ---- commitment surface text (pure strings; callers wrap in Component) ----

    /**
     * Published when a Mine run first commits, so the owner knows the rules
     * before descending. Names the risk (no final floor, previous floors
     * close) and the reward (escalating haul) without explaining the spatial
     * room movement, the same silence the ordinary loop keeps.
     */
    static String mineStartMessage() {
        return "Endless Mine. There is no final floor. Each checkpoint offers a door deeper, "
                + "or /dungeon cashout to leave with what you have banked. "
                + "Previous floors close behind you.";
    }

    /**
     * Published at each Mine checkpoint, naming the depth reached and the
     * cash-out. This is the commitment surface: the player sees the risk
     * (deeper is further from the exit) and the reward (the tier) before
     * choosing a door or cashing out.
     */
    static String mineCheckpointMessage(int floorIndex, int tier) {
        return "Mine floor " + floorIndex + " cleared. Loot tier " + tier
                + ". Walk a door to descend, or /dungeon cashout to leave with your haul.";
    }

    /** The refusal when /dungeon cashout is used outside a Mine. */
    static String notInMineMessage() {
        return "You are not in the Endless Mine.";
    }

    /** The refusal when /dungeon cashout is used outside a staging checkpoint. */
    static String cashOutBetweenFloorsMessage() {
        return "You can only cash out between Mine floors, standing in the staging room.";
    }

    /** The cash-out confirmation, naming the depth banked. */
    static String cashOutMessage(int depth) {
        return "You leave the Mine with " + depth + " floor" + (depth == 1 ? "" : "s") + " banked.";
    }
}
