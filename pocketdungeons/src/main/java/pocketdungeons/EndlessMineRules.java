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

    // ---- depth layers (design D13, ZONES_SPEC 3.5) ----------------------------------

    /** The mine dungeon's id: the door offered at the first staging room once it is open. */
    static final String MINE_DUNGEON_ID = "pocketdungeons:endless_mine";

    /**
     * The act the Endless Mine belongs to (D29): it opens in act 1 now, gated
     * on the leader's compass by {@code endlessMineUnlockLevel} so a new
     * player's first doors are dungeons.
     */
    static final int OPENING_ACT = 1;

    /** Layer names, by layer number 1 to 4. */
    private static final String[] LAYER_NAMES = {"Upper workings", "Deepslate", "Deep dark", "Magma core"};

    /** Last floor of each of the first three layers: 5, 11 and 17. Layer 4 runs on from 18. */
    private static final int[] LAYER_LAST_FLOOR = {5, 11, 17};

    /**
     * Whether the Endless Mine is open to a player with these acts unlocked
     * and this compass level: act {@link #OPENING_ACT} open, and the compass
     * at {@code endlessMineUnlockLevel} so a new player's first doors are
     * dungeons (D29).
     */
    static boolean opensFor(java.util.Set<Integer> unlockedActs, int compass) {
        return unlockedActs != null && unlockedActs.contains(OPENING_ACT)
                && compass >= PocketDungeonsConfig.endlessMineUnlockLevel();
    }

    /** Whether {@code offer} opens a Mine from the first staging room. */
    static boolean isMineOffer(Keystone.Offer offer) {
        return offer != null && MINE_DUNGEON_ID.equals(offer.dungeonId());
    }

    /** The depth layer (1 to 4) of Mine floor number {@code floor} (from 1). */
    static int layerOf(int floor) {
        for (int i = 0; i < LAYER_LAST_FLOOR.length; i++) {
            if (floor <= LAYER_LAST_FLOOR[i]) {
                return i + 1;
            }
        }
        return 4;
    }

    /** The act a player needs for layer {@code layer}: upper workings 1, deepslate 2, deep dark 3, magma core 4. */
    static int actForLayer(int layer) {
        return Math.max(1, Math.min(4, layer));
    }

    static String layerName(int layer) {
        return LAYER_NAMES[Math.max(1, Math.min(4, layer)) - 1];
    }

    /**
     * The act that must still be cleared before Mine floor {@code floor} may be entered, or 0 when
     * the player's unlocked acts allow it. A layer boundary caps the depth: the staging room then
     * offers only HOME.
     */
    static int sealedAct(int floor, java.util.Set<Integer> unlockedActs) {
        int need = actForLayer(layerOf(floor));
        return unlockedActs != null && unlockedActs.contains(need) ? 0 : need;
    }

    /** The staging room line for a sealed shaft. */
    static String sealedMessage(int act) {
        return "The shaft below is sealed until " + ActProgress.label(act) + " is cleared. Only the HOME lever is open.";
    }

    /** The loot band of a layer: the band of the act that opens it (Act 1 tiers 1 to 2, 2 to 3, 3, 3 to 4). */
    static DungeonDef.LootBand layerBand(int layer) {
        return switch (actForLayer(layer)) {
            case 1 -> new DungeonDef.LootBand(1, 2);
            case 2 -> new DungeonDef.LootBand(2, 3);
            case 3 -> new DungeonDef.LootBand(3, 3);
            default -> new DungeonDef.LootBand(3, 4);
        };
    }

    /** First floor of {@code layer}. */
    static int layerStart(int layer) {
        return layer <= 1 ? 1 : LAYER_LAST_FLOOR[Math.min(layer, 4) - 2] + 1;
    }

    /** Loot tier of Mine floor {@code floor}: the layer band's minimum, plus one every 3 floors into the layer, capped by the band. */
    static int lootTier(int floor) {
        int layer = layerOf(floor);
        DungeonDef.LootBand band = layerBand(layer);
        return band.clamp(band.min() + Math.max(0, floor - layerStart(layer)) / 3);
    }

    /** The floor level each layer's act begins at (D26): acts 1 to 4 begin at 1, 6, 13, 16. */
    private static final int[] ACT_BASE_LEVEL = {1, 6, 13, 16};

    /**
     * The level Mine floor number {@code floor} (from 1) runs at: its layer's
     * act base plus the floors into the layer, plus the dealt door step. Same
     * rule {@link FloorLevels} gives a graph dungeon, spelled out in floors
     * because the Mine has no nodes.
     */
    static int floorLevel(int floor, int step) {
        int layer = layerOf(floor);
        return ACT_BASE_LEVEL[actForLayer(layer) - 1]
                + Math.max(0, floor - layerStart(layer)) + Math.max(0, step);
    }

    /** Whether {@code floor} is the first floor of a layer past the first (a layer transition). */
    static boolean isTransition(int floor) {
        return floor > 1 && layerStart(layerOf(floor)) == floor;
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
