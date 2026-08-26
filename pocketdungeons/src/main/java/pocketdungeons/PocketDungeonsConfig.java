package pocketdungeons;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.Reader;
import java.util.function.DoublePredicate;
import java.util.function.IntPredicate;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Config at {@code config/pocketdungeons.json}. Suite's readOrCreate contract: missing
 * -&gt; write defaults; fails to parse -&gt; defaults in memory, file untouched;
 * parses -&gt; use it. See SPEC.md section 10.
 *
 * <p>Item ids ({@code keystoneItem}) are kept as strings here
 * and resolved against the registry at their use site. This class carries no
 * Minecraft imports, and config load runs early enough that resolving them here
 * would be betting on registry availability for no benefit.
 */
public final class PocketDungeonsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ---- instance grid and lifecycle ---------------------------------------
    private static int slotPitch = 2048;
    private static int slotsPerRow = 64;
    private static int watchIntervalTicks = 20;
    private static int voidGuardDepth = 10;
    private static int maxPartyMembers = 6;
    private static int inviteTtlSeconds = 120;

    // ---- layout planning ----------------------------------------------------
    private static int pathLengthMin = 5;
    private static int pathLengthMax = 8;
    private static double branchProbability = 0.35;
    private static double loopProbability = 0.15;
    private static int planAttemptBudget = 16;
    private static int maxGridSpan = 12;
    private static int clearBlocksPerTick = 8192;

    // ---- ritual -------------------------------------------------------------
    private static boolean ritualEnabled = true;

    // ---- payout -------------------------------------------------------------
    // U8 Stage 0: chests replace the item grant outright. payoutCommand survives
    // as the one remaining hook an operator has for routing a reward through an
    // economy mod without this mod taking a Java dependency on it.
    private static String payoutCommand = "";

    // ---- trials (U6) --------------------------------------------------------
    private static String vaultKeyItem = "minecraft:trial_key";
    private static String ominousVaultKeyItem = "minecraft:ominous_trial_key";
    private static int trialSpawnerCooldownTicks = 36000;

    // ---- keystones (U7/U8) ----------------------------------------------------
    // U8 Stage 4: the remote moves off minecraft:trial_key -- that item is what
    // this mod's own spawners eject for its own vaults, and a recovery compass
    // has no such collision with anything else in the suite.
    private static String keystoneItem = "minecraft:recovery_compass";
    private static int keystoneMaxLevel = 25;
    private static String callingCardItem = "minecraft:compass";
    private static int timerBaseSeconds = 180;
    private static int timerPerRoomSeconds = 60;

    // ---- U8: the run outlives the player, the reward room, the three doors ---
    private static int rewardRoomGraceSeconds = 600;
    private static int threeChestPercent = 60;
    private static int twoChestPercent = 80;
    /** The clock ran out before the run was completed. The harshest depletion. */
    private static int timedOutDepletion = 1;
    /** Completed, but after the clock. Costs less, and a door offer is still earned. */
    private static int lateCompletionDepletion = 2;

    // ---- affixes (M4) --------------------------------------------------------
    /** Overclocked scales {@link #trialSpawnerCooldownTicks} down by this. */
    private static double overclockedCooldownFactor = 0.4;
    /** Swarming scales a tier's total/simultaneous mob counts up by this. */
    private static double swarmingMobFactor = 1.5;
    /** Silenced's trial spawners detect players at this range instead of 14. */
    private static int silencedPlayerRange = 6;
    /** Molten hazard blocks placed per cell. */
    private static int moltenHazardsPerCell = 4;
    /** Feral wolves spawned per non-encounter cell. Zero disables the affix's spawns. */
    private static int feralWolvesPerCell = 2;

    private PocketDungeonsConfig() {}

    /** Loads and validates every registry setting, preserving an unreadable file for manual repair. */
    public static void load(Path configDir) {
        Path file = configDir.resolve("pocketdungeons.json");
        try {
            Files.createDirectories(configDir);
            if (!Files.exists(file)) {
                try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    GSON.toJson(defaultsJson(), w);
                }
                PocketDungeonsMod.LOG.info("Created default pocketdungeons.json");
                applyDefaults();
                return;
            }
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject parsed = GSON.fromJson(r, JsonObject.class);
                if (parsed == null) {
                    throw new IllegalStateException("pocketdungeons.json is empty");
                }
                apply(parsed);
            }
        } catch (Exception e) {
            // Defaults in memory, file untouched -- an operator's broken-but-
            // recoverable edit is worth more than a tidy file.
            PocketDungeonsMod.LOG.error("pocketdungeons.json could not be read; using defaults and "
                    + "leaving the file alone", e);
            applyDefaults();
        }
    }

    public static int slotPitch() {
        return slotPitch;
    }

    public static int slotsPerRow() {
        return slotsPerRow;
    }

    public static int watchIntervalTicks() {
        return watchIntervalTicks;
    }

    public static int voidGuardDepth() {
        return voidGuardDepth;
    }

    public static int maxPartyMembers() {
        return maxPartyMembers;
    }

    public static int inviteTtlSeconds() {
        return inviteTtlSeconds;
    }

    public static int pathLengthMin() {
        return pathLengthMin;
    }

    public static int pathLengthMax() {
        return pathLengthMax;
    }

    public static double branchProbability() {
        return branchProbability;
    }

    public static double loopProbability() {
        return loopProbability;
    }

    public static int planAttemptBudget() {
        return planAttemptBudget;
    }

    public static int maxGridSpan() {
        return maxGridSpan;
    }

    public static int clearBlocksPerTick() {
        return clearBlocksPerTick;
    }

    public static boolean ritualEnabled() {
        return ritualEnabled;
    }

    public static String payoutCommand() {
        return payoutCommand;
    }

    public static String vaultKeyItem() {
        return vaultKeyItem;
    }

    public static String ominousVaultKeyItem() {
        return ominousVaultKeyItem;
    }

    public static int trialSpawnerCooldownTicks() {
        return trialSpawnerCooldownTicks;
    }

    public static double overclockedCooldownFactor() {
        return overclockedCooldownFactor;
    }

    public static double swarmingMobFactor() {
        return swarmingMobFactor;
    }

    public static int silencedPlayerRange() {
        return silencedPlayerRange;
    }

    public static int moltenHazardsPerCell() {
        return moltenHazardsPerCell;
    }

    public static int feralWolvesPerCell() {
        return feralWolvesPerCell;
    }

    public static String keystoneItem() {
        return keystoneItem;
    }

    public static int keystoneMaxLevel() {
        return keystoneMaxLevel;
    }

    public static String callingCardItem() {
        return callingCardItem;
    }


    public static int timerBaseSeconds() {
        return timerBaseSeconds;
    }

    public static int timerPerRoomSeconds() {
        return timerPerRoomSeconds;
    }

    public static int rewardRoomGraceSeconds() {
        return rewardRoomGraceSeconds;
    }

    public static int threeChestPercent() {
        return threeChestPercent;
    }

    public static int twoChestPercent() {
        return twoChestPercent;
    }

    public static int timedOutDepletion() {
        return timedOutDepletion;
    }

    public static int lateCompletionDepletion() {
        return lateCompletionDepletion;
    }

    private static void applyDefaults() {
        slotPitch = 2048;
        slotsPerRow = 64;
        watchIntervalTicks = 20;
        voidGuardDepth = 10;
        maxPartyMembers = 6;
        inviteTtlSeconds = 120;

        pathLengthMin = 5;
        pathLengthMax = 8;
        branchProbability = 0.35;
        loopProbability = 0.15;
        planAttemptBudget = 16;
        maxGridSpan = 12;
        clearBlocksPerTick = 8192;

        ritualEnabled = true;

        payoutCommand = "";

        vaultKeyItem = "minecraft:trial_key";
        ominousVaultKeyItem = "minecraft:ominous_trial_key";
        trialSpawnerCooldownTicks = 36000;

        keystoneItem = "minecraft:recovery_compass";
        keystoneMaxLevel = 25;
        callingCardItem = "minecraft:compass";
        timerBaseSeconds = 180;
        timerPerRoomSeconds = 60;

        rewardRoomGraceSeconds = 600;
        threeChestPercent = 60;
        twoChestPercent = 80;
        timedOutDepletion = 1;
        lateCompletionDepletion = 2;

        overclockedCooldownFactor = 0.4;
        swarmingMobFactor = 1.5;
        silencedPlayerRange = 6;
        moltenHazardsPerCell = 4;
        feralWolvesPerCell = 2;
    }

    private static void apply(JsonObject root) {
        // A cell is exactly one chunk only while the slot origin is chunk-aligned,
        // and every force-load and teardown calculation downstream leans on that.
        slotPitch = readInt(root, "slotPitch", 2048,
                v -> v > 0 && v % 16 == 0, "must be > 0 and a multiple of 16");
        slotsPerRow = readInt(root, "slotsPerRow", 64, v -> v > 0, "must be > 0");
        watchIntervalTicks = readInt(root, "watchIntervalTicks", 20, v -> v > 0, "must be > 0");
        voidGuardDepth = readInt(root, "voidGuardDepth", 10, v -> v >= 0, "must be >= 0");
        maxPartyMembers = readInt(root, "maxPartyMembers", 6, v -> v >= 1, "must be >= 1");
        inviteTtlSeconds = readInt(root, "inviteTtlSeconds", 120, v -> v >= 0, "must be >= 0");

        pathLengthMin = readInt(root, "pathLengthMin", 5, v -> v >= 2, "must be >= 2");
        pathLengthMax = readInt(root, "pathLengthMax", 8, v -> v >= 2, "must be >= 2");
        if (pathLengthMax < pathLengthMin) {
            PocketDungeonsMod.LOG.error(
                    "pocketdungeons.json pathLengthMax ({}) is below pathLengthMin ({}); "
                            + "using pathLengthMax = pathLengthMin", pathLengthMax, pathLengthMin);
            pathLengthMax = pathLengthMin;
        }
        branchProbability = readDouble(root, "branchProbability", 0.35,
                v -> v >= 0.0 && v <= 1.0, "must be between 0.0 and 1.0");
        loopProbability = readDouble(root, "loopProbability", 0.15,
                v -> v >= 0.0 && v <= 1.0, "must be between 0.0 and 1.0");
        planAttemptBudget = readInt(root, "planAttemptBudget", 16, v -> v >= 1, "must be >= 1");
        maxGridSpan = readInt(root, "maxGridSpan", 12, v -> v >= 3, "must be >= 3");
        clearBlocksPerTick = readInt(root, "clearBlocksPerTick", 8192,
                v -> v >= 1024, "must be >= 1024");

        ritualEnabled = readBoolean(root, "ritualEnabled", true);

        payoutCommand = readString(root, "payoutCommand", "", true);

        vaultKeyItem = readString(root, "vaultKeyItem", "minecraft:trial_key", false);
        ominousVaultKeyItem = readString(root, "ominousVaultKeyItem",
                "minecraft:ominous_trial_key", false);
        trialSpawnerCooldownTicks = readInt(root, "trialSpawnerCooldownTicks", 36000,
                v -> v >= 0, "must be >= 0");

        keystoneItem = readString(root, "keystoneItem", "minecraft:recovery_compass", false);
        keystoneMaxLevel = readInt(root, "keystoneMaxLevel", 25, v -> v >= 1, "must be >= 1");
        callingCardItem = readString(root, "callingCardItem", "minecraft:compass", false);
        timerBaseSeconds = readInt(root, "timerBaseSeconds", 180, v -> v >= 0, "must be >= 0");
        timerPerRoomSeconds = readInt(root, "timerPerRoomSeconds", 60, v -> v >= 0, "must be >= 0");

        rewardRoomGraceSeconds = readInt(root, "rewardRoomGraceSeconds", 600,
                v -> v >= 0, "must be >= 0");
        threeChestPercent = readInt(root, "threeChestPercent", 60,
                v -> v >= 1 && v <= 100, "must be between 1 and 100");
        twoChestPercent = readInt(root, "twoChestPercent", 80,
                v -> v > threeChestPercent && v <= 100,
                "must be > threeChestPercent and <= 100");
        if (twoChestPercent <= threeChestPercent) {
            PocketDungeonsMod.LOG.error(
                    "pocketdungeons.json twoChestPercent ({}) must be greater than threeChestPercent "
                            + "({}); using default 80", twoChestPercent, threeChestPercent);
            twoChestPercent = Math.max(threeChestPercent + 1, 80);
        }
        timedOutDepletion = readInt(root, "timedOutDepletion", 1, v -> v >= 0, "must be >= 0");
        lateCompletionDepletion = readInt(root, "lateCompletionDepletion", 2,
                v -> v >= 0, "must be >= 0");

        overclockedCooldownFactor = readDouble(root, "overclockedCooldownFactor", 0.4,
                v -> v > 0 && v <= 1, "must be between 0 (exclusive) and 1");
        swarmingMobFactor = readDouble(root, "swarmingMobFactor", 1.5,
                v -> v >= 1, "must be >= 1");
        silencedPlayerRange = readInt(root, "silencedPlayerRange", 6, v -> v >= 1, "must be >= 1");
        moltenHazardsPerCell = readInt(root, "moltenHazardsPerCell", 4, v -> v >= 0, "must be >= 0");
        feralWolvesPerCell = readInt(root, "feralWolvesPerCell", 2, v -> v >= 0, "must be >= 0");
    }

    private static int readInt(JsonObject root, String key, int defaultValue,
                                IntPredicate valid, String requirement) {
        if (!root.has(key)) {
            PocketDungeonsMod.LOG.error("pocketdungeons.json field '{}' is missing; using default {}",
                    key, defaultValue);
            return defaultValue;
        }
        try {
            int value = root.get(key).getAsInt();
            if (!valid.test(value)) {
                PocketDungeonsMod.LOG.error(
                        "pocketdungeons.json field '{}' has invalid value {}; {}; using default {}",
                        key, value, requirement, defaultValue);
                return defaultValue;
            }
            return value;
        } catch (Exception e) {
            PocketDungeonsMod.LOG.error("pocketdungeons.json field '{}' is not a valid integer; using default {}",
                    key, defaultValue, e);
            return defaultValue;
        }
    }

    private static double readDouble(JsonObject root, String key, double defaultValue,
                                     DoublePredicate valid, String requirement) {
        if (!root.has(key)) {
            PocketDungeonsMod.LOG.error("pocketdungeons.json field '{}' is missing; using default {}",
                    key, defaultValue);
            return defaultValue;
        }
        try {
            double value = root.get(key).getAsDouble();
            if (!valid.test(value)) {
                PocketDungeonsMod.LOG.error(
                        "pocketdungeons.json field '{}' has invalid value {}; {}; using default {}",
                        key, value, requirement, defaultValue);
                return defaultValue;
            }
            return value;
        } catch (Exception e) {
            PocketDungeonsMod.LOG.error("pocketdungeons.json field '{}' is not a valid number; using default {}",
                    key, defaultValue, e);
            return defaultValue;
        }
    }

    private static boolean readBoolean(JsonObject root, String key, boolean defaultValue) {
        if (!root.has(key)) {
            PocketDungeonsMod.LOG.error("pocketdungeons.json field '{}' is missing; using default {}",
                    key, defaultValue);
            return defaultValue;
        }
        try {
            return root.get(key).getAsBoolean();
        } catch (Exception e) {
            PocketDungeonsMod.LOG.error("pocketdungeons.json field '{}' is not a valid boolean; using default {}",
                    key, defaultValue, e);
            return defaultValue;
        }
    }

    /**
     * @param allowEmpty whether an empty string is a meaningful value for this
     *                   field ({@code payoutCommand} uses empty to mean "off";
     *                   an empty item id is always a mistake)
     */
    private static String readString(JsonObject root, String key, String defaultValue,
                                     boolean allowEmpty) {
        if (!root.has(key)) {
            PocketDungeonsMod.LOG.error("pocketdungeons.json field '{}' is missing; using default '{}'",
                    key, defaultValue);
            return defaultValue;
        }
        try {
            String value = root.get(key).getAsString();
            if (value == null || (!allowEmpty && value.isBlank())) {
                PocketDungeonsMod.LOG.error(
                        "pocketdungeons.json field '{}' must not be empty; using default '{}'",
                        key, defaultValue);
                return defaultValue;
            }
            return value;
        } catch (Exception e) {
            PocketDungeonsMod.LOG.error("pocketdungeons.json field '{}' is not a valid string; using default '{}'",
                    key, defaultValue, e);
            return defaultValue;
        }
    }

    private static JsonObject defaultsJson() {
        JsonObject root = new JsonObject();
        root.addProperty("slotPitch", 2048);
        root.addProperty("slotsPerRow", 64);
        root.addProperty("watchIntervalTicks", 20);
        root.addProperty("voidGuardDepth", 10);
        root.addProperty("maxPartyMembers", 6);
        root.addProperty("inviteTtlSeconds", 120);

        root.addProperty("pathLengthMin", 5);
        root.addProperty("pathLengthMax", 8);
        root.addProperty("branchProbability", 0.35);
        root.addProperty("loopProbability", 0.15);
        root.addProperty("planAttemptBudget", 16);
        root.addProperty("maxGridSpan", 12);
        root.addProperty("clearBlocksPerTick", 8192);

        root.addProperty("ritualEnabled", true);

        root.addProperty("payoutCommand", "");

        root.addProperty("vaultKeyItem", "minecraft:trial_key");
        root.addProperty("ominousVaultKeyItem", "minecraft:ominous_trial_key");
        root.addProperty("trialSpawnerCooldownTicks", 36000);

        root.addProperty("keystoneItem", "minecraft:recovery_compass");
        root.addProperty("keystoneMaxLevel", 25);
        root.addProperty("callingCardItem", "minecraft:compass");
        root.addProperty("timerBaseSeconds", 180);
        root.addProperty("timerPerRoomSeconds", 60);

        root.addProperty("rewardRoomGraceSeconds", 600);
        root.addProperty("threeChestPercent", 60);
        root.addProperty("twoChestPercent", 80);
        root.addProperty("timedOutDepletion", 1);
        root.addProperty("lateCompletionDepletion", 2);

        root.addProperty("overclockedCooldownFactor", 0.4);
        root.addProperty("swarmingMobFactor", 1.5);
        root.addProperty("silencedPlayerRange", 6);
        root.addProperty("moltenHazardsPerCell", 4);
        root.addProperty("feralWolvesPerCell", 2);
        return root;
    }
}
