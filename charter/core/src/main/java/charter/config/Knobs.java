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

    // ---- time ----
    /** Compresses every siege, spoilage, raising, grace and carry-in timer. */
    public double timeScale = 1.0;
    public List<String> claimDimensions = new ArrayList<>(List.of("minecraft:overworld"));

    // ---- land and prices ----
    public int keepPriceDiamonds = 64;
    public int warBannerPriceCobblestone = 256;
    public int keepCapacityClaims = 40;
    public int keepRangeHops = 8;
    public int campCapacityClaims = 4;
    public int campRangeHops = 2;
    public int claimsPerActiveMember = 6;
    public int keepGapChunks = 4;

    // ---- food (S) ----
    public double drainPerMouthPerHour = 10;
    public int minMouthsPerKeep = 3;
    public double storageLimitHours = 84;
    public double spoilagePerDayPreserved = 0.02;
    public double spoilagePerDayCooked = 0.10;
    public double spoilagePerDayFresh = 0.30;
    public double spoilagePerDayPastLimit = 0.50;
    public int granaryMaxChests = 8;

    // ---- camps and holds (S) ----
    public double campBurnPerHour = 30;
    public double campBurnCutOffPerHour = 60;
    public double outlyingHoldHours = 6;
    public double outlyingRefillHours = 24;
    public double campSallyProtectionHours = 1;

    // ---- presence and assault ----
    public double presencePerPlayer = 0.25;
    public double multiplierMin = 0.5;
    public double multiplierMax = 2.0;
    public double notorietyMaxMultiplier = 1.5;
    public int presenceAfkMinutes = 5;
    public int presenceMinMemberDays = 3;
    public double starvationThreshold = 0.25;
    public double assaultMultiplier = 4.0;
    public int teleportDetectBlocks = 16;

    // ---- siege timers (S) ----
    public double campRaisingHours = 2;
    public int carryInWindowMinutes = 10;
    public int truceDays = 3;
    public int newFactionProtectionDays = 3;
    public int bannerMoveCooldownMinutes = 30;
    public int orphanGraceMinutes = 30;
    public double plunderShare = 0.25;
    public int occupationCapDays = 7;
    /** How long the besieger has to choose raze or occupy before the keep is razed. */
    public int fallChoiceMinutes = 10;
    /** Notoriety gained per camp raised against someone, and per claim or keep taken. */
    public double notorietyPerCamp = 1;
    public double notorietyPerClaimTaken = 1;
    public double notorietyDecayPerDay = 1;
    /** Notoriety at which a faction's keeps reach notorietyMaxMultiplier. */
    public double notorietyForMax = 10;

    // ---- governance (wall time) ----
    public int settleDays = 3;
    public int absentLordDays = 7;
    public int voteEligibilityDays = 3;
    public int voteDurationHours = 48;
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
    public double imposedNotorietyPerSeverity = 1.0;
    public boolean scaleSentencesWithTimeScale = false;
    public boolean labourTerms = false;
    public int indentureCapDays = 7;
    public int warGoalsPerWar = 2;

    // ---- signals, logging, ticking ----
    public int smokeColumnRangeBlocks = 256;
    public int offlineMessagesKept = 20;
    public boolean siegeLog = true;
    public int siegeLogRetentionDays = 30;
    public int simStepTicks = 100;
    public int cartScanTicks = 5;

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
        if (multiplierMin <= 0 || multiplierMax < multiplierMin) out.add("multiplierMin must be above 0 and no more than multiplierMax.");
        if (starvationThreshold <= 0 || starvationThreshold >= 1) out.add("starvationThreshold must be between 0 and 1.");
        if (granaryMaxChests < 1) out.add("granaryMaxChests must be at least 1.");
        if (confinementMinMinutes > confinementMaxHours * 60) out.add("confinementMinMinutes is longer than confinementMaxHours.");
        if (plunderShare < 0 || plunderShare > 1) out.add("plunderShare must be between 0 and 1.");
        if (simStepTicks < 1 || cartScanTicks < 1) out.add("simStepTicks and cartScanTicks must be at least 1.");
        if (voteDurationHours < 1) out.add("voteDurationHours must be at least 1.");
        return out;
    }
}
