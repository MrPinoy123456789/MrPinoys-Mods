package charter.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Every tuning value in DESIGN.md §24 and PLAN.md §9, with its default.
 *
 * <p>Plain public fields so the fabric side can fill it from
 * {@code config/charter/charter.json} without the rules model knowing about
 * JSON: a key missing from the file keeps the default written here.
 *
 * <p>Siege-time values (marked S) are scaled by {@link #timeScale}; wall-time
 * values (governance) are not. Knobs for features outside the first build
 * (settling, dormancy, imposed vassalage, indenture, war goals) are present so
 * the file does not change shape later; they do nothing yet.
 */
public final class Knobs {

    /** The two sets of values: {@link #shipped()} is for balance, {@link #test()} makes every timer short to test faster. */
    public static final String SHIPPED = "shipped";
    public static final String TEST = "test";

    /**
     * Which set the values started from. The config file names it; the loader starts from that set and then applies
     * whatever else the file says, so a key in the file always wins.
     */
    public String profile = SHIPPED;

    /** The balance values: what a real server runs on. */
    public static Knobs shipped() {
        return new Knobs();
    }

    /**
     * The shipped values, with the raiders' expiry and a few slow governance clocks (settling, join requests, breaking
     * away, the founding cooldown) short, so a feature can be tried in minutes. Siege time, a trip's wait, votes,
     * presence, the raiders' waves and crumble and the absent Lord's clock stay as shipped, since they are the part of the
     * balance a test must not change. Not for a real server.
     */
    public static Knobs test() {
        Knobs k = new Knobs();
        k.profile = TEST;
        k.npcExpireDays = 1;
        k.settleDays = 0;
        k.joinRequestDays = 1;
        k.breakawayHours = 1;
        k.foundSecedeCooldownDays = 0;
        return k;
    }

    /** The values of the named set; an unknown name is the shipped set. */
    public static Knobs forProfile(String name) {
        return TEST.equals(name) ? test() : shipped();
    }

    // ---- time ----
    /** Compresses every siege, spoilage, raising, grace and carry-in timer. */
    public double timeScale = 1.0;
    public List<String> claimDimensions = new ArrayList<>(List.of("minecraft:overworld"));

    // ---- land and prices ----
    public int keepPriceDiamonds = 64;
    public int keepCapacityClaims = 40;
    public int keepRangeHops = 8;
    public int campCapacityClaims = 4;
    public int campRangeHops = 2;
    public int claimsPerActiveMember = 6;
    public int keepGapChunks = 4;
    /** Chunks a keep's plot reaches from its core chunk each way: 1 is 3 by 3, 4 is 9 by 9. */
    public int keepRadiusChunks = 1;
    /** Whether players may found kingdoms and keeps themselves. Off: only operators set kingdoms, through the admin commands. */
    public boolean playerFounding = true;
    /**
     * Whether the playtest and debugging commands exist: fast-forwarding time, backdating members, raising a camp at
     * once, hand-made kingdoms, a Nexus to hand out, notes, the supply and granary tools, and /charter page.
     */
    public boolean devTools = true;
    /** Whether the operator playtest scenarios (the tutorial, the jail and the rest) can be run with /charter admin scenario. */
    public boolean scenarios = true;
    /** Whether land can be claimed beside a keep. Off: a keep is its plot and extra land comes only from outposts. */
    public boolean keepClaims = true;
    /** Whether the game may generate structures (dungeons, villages) that overlap land a keep or outpost holds. Off keeps them out. */
    public boolean structuresInKingdoms = false;

    // ---- food (S) ----
    public double drainPerMouthPerHour = 10;
    public int minMouthsPerKeep = 3;
    public double storageLimitHours = 84;
    public double spoilagePerDayPreserved = 0.02;
    public double spoilagePerDayCooked = 0.10;
    public double spoilagePerDayFresh = 0.30;
    public double spoilagePerDayPastLimit = 0.50;
    public int granaryMaxChests = 8;

    // ---- one of each, and teleports (protected keeps, K3) ----
    /** Seconds a teleport waits, cancelled by moving or taking damage. */
    public int teleportWarmupSeconds = 5;
    /** Seconds before the same player may teleport again. */
    public int teleportCooldownSeconds = 30;
    /** Food taken from an outpost's supply by each /outpost. */
    public int outpostTeleportFood = 1;
    /** Members a sub-faction needs before it may place an outpost. */
    public int outpostMinMembers = 2;
    /** Diamonds the first outpost of a nation holds as its deposit; each outpost held already adds the step. */
    public int outpostDeposit = 8;
    public int outpostDepositStep = 4;

    // ---- keep upkeep (wall days; the granary is the upkeep) ----
    /** Days a full granary lasts. It drains by an even share each hour and nothing else changes the drain. */
    public int keepUpkeepDays = 14;
    /** Whether a keep eats from its granary and lapses when it runs dry. Off: keeps need no food, and only outposts keep a store. */
    public boolean keepGranary = true;
    /** Days of food left at which every member is warned, largest first. */
    public int[] keepWarnDays = {7, 3, 1};

    // ---- camps (S) ----
    public double campBurnPerHour = 30;
    public double campBurnCutOffPerHour = 60;

    // ---- presence ----
    public int presenceAfkMinutes = 5;
    /** How long a member must have belonged before they count as present in a war: three days when shipped. */
    public int presenceMinMemberSeconds = 259_200;
    public int teleportDetectBlocks = 16;

    // ---- siege timers (S) ----
    public double campRaisingHours = 2;
    /** Days after a war ends, by capture or lapse, before the same two nations may fight again. */
    public int truceDays = 3;
    public int bannerMoveCooldownMinutes = 30;
    public int orphanGraceMinutes = 30;
    /** The longest a treaty may grant access to a keep (BasisCaps). */
    public int occupationCapDays = 7;

    // ---- governance (wall time) ----
    public int settleDays = 3;
    public int absentLordDays = 7;
    public int voteEligibilityDays = 3;
    public int voteDurationHours = 48;
    // ---- NPC strongholds (wall time) ----
    /** Most strongholds (NPC keeps) standing at once. */
    public int npcMaxStrongholds = 4;
    /** Most outposts (NPC camps) standing at once. */
    public int npcMaxOutposts = 4;
    /** Real minutes between waves, and before a fallen or expired site is replaced elsewhere. */
    public int npcWaveMinutes = 5;
    /** A site nobody fought for this many real days is cleaned up and replaced. */
    public int npcExpireDays = 10;
    /** Server ticks between the layers of a falling site's crumble, from the top down. */
    public int npcCrumbleTicksPerLayer = 4;
    /** Stacks of the treasury's diamonds, then cobblestone, spilled where a site falls. */
    public int npcFallLoot = 4;
    /** A new site stands at least this many chunks from any player claim and any online player. */
    public int npcPlayerClearChunks = 8;
    /** Tiers by distance from the world spawn, in chunks: near is below the first, middle below the second, else far. */
    public int npcNearChunks = 40;
    public int npcMiddleChunks = 120;
    /** Granary share at spawn, defenders, treasury diamonds and cobblestone, near, middle, far. */
    public double[] npcGranaryShare = {0.30, 0.50, 0.70};
    /** Raiders a stronghold may have alive at once, near, middle, far, and an outpost. */
    public int[] npcPoolByTier = {3, 5, 8};
    public int[] npcOutpostPool = {1, 2, 3};
    /** Raiders a site can still spawn, over its whole life, near, middle, far. Each raider costs one. */
    public int[] npcSupplyStronghold = {24, 48, 96};
    public int[] npcSupplyOutpost = {6, 9, 12};
    public int[] npcDiamonds = {16, 32, 64};
    public int[] npcCobblestone = {128, 256, 512};
    /** How long a faction that voted to break away has to place its Nexus, and a group has to gather, before it lapses. */
    public int breakawayHours = 24;
    /** How long a request to join a faction waits for an answer before it lapses. */
    public int joinRequestDays = 7;
    /** Most seats an office may have. */
    public int maxSeats = 7;
    /** How long a vote among the seats of a council stays open. */
    public int seatVoteHours = 24;
    public int activeWindowDays = 14;
    public int foundSecedeCooldownDays = 7;
    public int dormancyDays = 30;
    /** What a Lordship's Lord may spend in one action without a vote. */
    public int lordSpendLimitDiamonds = 32;
    public int lordSpendLimitCobblestone = 2048;
    public int orgNameMaxLength = 32;

    // ---- justice and contracts ----
    public int confinementMinMinutes = 60;
    public int confinementMaxHours = 24;
    public int executionRespawnDelayMaxMinutes = 60;
    public int banishmentMaxHours = 24;
    public int weeklyPunishmentCapHours = 24;
    public int sameFactionSamePlayerCooldownDays = 3;
    public int custodyFineCapDiamonds = 16;
    public int custodyLapseMinutes = 5;
    public int imposedVassalageCapDays = 14;
    public boolean scaleSentencesWithTimeScale = false;
    public boolean labourTerms = false;
    public int indentureCapDays = 7;
    /** Wars a nation may hold at once. */
    public int warGoalsPerWar = 2;
    /** Hours of siege time between a declaration and the first moment capture may begin. */
    public int warGoalNoticeHours = 12;
    /** Hours of siege time a goal lasts from its declaration before it lapses. */
    public int warGoalHours = 48;
    /** Minutes the attackers must hold an outpost's chunk, with no defender in it, to capture it. */
    public int outpostCaptureMinutes = 10;
    /** Minutes after a capture in which only the captors may plant an outpost in that chunk. */
    public int outpostWinnerWindowMinutes = 30;
    /** Days a new keep's empty granary cannot lapse it. */
    public int keepNewGraceDays = 14;

    // ---- signals, logging, ticking ----
    public int smokeColumnRangeBlocks = 256;
    public int offlineMessagesKept = 20;
    public boolean siegeLog = true;
    public int siegeLogRetentionDays = 30;
    public int simStepTicks = 100;

    // ---- the Chronicle in the world (M13) ----
    /** Whether books also tell admin events, with the clerk's voice (PLAN.md section 3). Off keeps test scenarios out of the story. */
    public boolean chronicleShowAdmin = false;

    /**
     * Problems that would make the rules misbehave, one plain sentence each. An
     * empty list means the knobs are usable. The fabric side logs these and falls
     * back to defaults rather than running with a broken value.
     */
    public List<String> problems() {
        List<String> out = new ArrayList<>();
        if (!(timeScale > 0)) out.add("timeScale must be above 0.");
        if (claimDimensions == null || claimDimensions.isEmpty()) out.add("claimDimensions must name at least one dimension.");
        if (minMouthsPerKeep < 1) out.add("minMouthsPerKeep must be at least 1.");
        if (!(storageLimitHours > 0)) out.add("storageLimitHours must be above 0.");
        if (!(drainPerMouthPerHour > 0)) out.add("drainPerMouthPerHour must be above 0.");
        if (granaryMaxChests < 1) out.add("granaryMaxChests must be at least 1.");
        if (confinementMinMinutes > confinementMaxHours * 60) out.add("confinementMinMinutes is longer than confinementMaxHours.");
        if (simStepTicks < 1) out.add("simStepTicks must be at least 1.");
        if (voteDurationHours < 1) out.add("voteDurationHours must be at least 1.");
        if (keepRadiusChunks < 1) out.add("keepRadiusChunks must be at least 1.");
        return out;
    }
}
