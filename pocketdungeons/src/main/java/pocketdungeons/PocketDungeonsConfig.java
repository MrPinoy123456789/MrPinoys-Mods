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
 * <p>Item ids ({@code ritualKeyItem}, {@code payoutItem}) are kept as strings here
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

    // ---- difficulty ---------------------------------------------------------
    private static int baseMobsPerEncounter = 2;
    private static int maxMobsPerRoom = 8;
    private static boolean spawnerDensEnabled = true;

    // ---- ritual -------------------------------------------------------------
    private static boolean ritualEnabled = true;
    private static String ritualKeyItem = "minecraft:echo_shard";
    private static int ritualKeyCount = 1;

    // ---- payout -------------------------------------------------------------
    private static String payoutItem = "minecraft:emerald";
    private static int payoutBaseCount = 6;
    private static int payoutPerTier = 4;
    private static int streakBonusPercent = 10;
    private static int streakBonusCapPercent = 100;
    private static String payoutCommand = "";

    // ---- trials (U6) --------------------------------------------------------
    private static boolean trialsEnabled = true;
    private static String vaultKeyItem = "minecraft:trial_key";
    private static String ominousVaultKeyItem = "minecraft:ominous_trial_key";
    private static boolean ominousRequiresBottle = true;
    private static int ominousPayoutPercent = 150;
    private static int trialSpawnerCooldownTicks = 36000;

    // ---- keystones (U7) -----------------------------------------------------
    private static String keystoneItem = "minecraft:trial_key";
    private static String keystoneTokenItem = "minecraft:trial_key";
    private static int keystoneMaxLevel = 25;
    private static int depletionOnDeath = 2;
    private static int depletionOnExit = 1;
    private static int depletionOnDisconnect = 3;
    private static int overtimeDepletion = 0;
    private static boolean timerEnabled = true;
    private static int timerBaseSeconds = 180;
    private static int timerPerRoomSeconds = 60;
    private static int payoutPerLevelPercent = 5;
    private static int ominousFromLevel = 10;

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

    public static int baseMobsPerEncounter() {
        return baseMobsPerEncounter;
    }

    public static int maxMobsPerRoom() {
        return maxMobsPerRoom;
    }

    public static boolean spawnerDensEnabled() {
        return spawnerDensEnabled;
    }

    public static boolean ritualEnabled() {
        return ritualEnabled;
    }

    public static String ritualKeyItem() {
        return ritualKeyItem;
    }

    public static int ritualKeyCount() {
        return ritualKeyCount;
    }

    public static String payoutItem() {
        return payoutItem;
    }

    public static int payoutBaseCount() {
        return payoutBaseCount;
    }

    public static int payoutPerTier() {
        return payoutPerTier;
    }

    public static int streakBonusPercent() {
        return streakBonusPercent;
    }

    public static int streakBonusCapPercent() {
        return streakBonusCapPercent;
    }

    public static String payoutCommand() {
        return payoutCommand;
    }

    public static boolean trialsEnabled() {
        return trialsEnabled;
    }

    public static String vaultKeyItem() {
        return vaultKeyItem;
    }

    public static String ominousVaultKeyItem() {
        return ominousVaultKeyItem;
    }

    public static boolean ominousRequiresBottle() {
        return ominousRequiresBottle;
    }

    public static int ominousPayoutPercent() {
        return ominousPayoutPercent;
    }

    public static int trialSpawnerCooldownTicks() {
        return trialSpawnerCooldownTicks;
    }

    public static String keystoneItem() {
        return keystoneItem;
    }

    public static String keystoneTokenItem() {
        return keystoneTokenItem;
    }

    public static int keystoneMaxLevel() {
        return keystoneMaxLevel;
    }

    public static int depletionOnDeath() {
        return depletionOnDeath;
    }

    public static int depletionOnExit() {
        return depletionOnExit;
    }

    public static int depletionOnDisconnect() {
        return depletionOnDisconnect;
    }

    public static int overtimeDepletion() {
        return overtimeDepletion;
    }

    public static boolean timerEnabled() {
        return timerEnabled;
    }

    public static int timerBaseSeconds() {
        return timerBaseSeconds;
    }

    public static int timerPerRoomSeconds() {
        return timerPerRoomSeconds;
    }

    public static int payoutPerLevelPercent() {
        return payoutPerLevelPercent;
    }

    public static int ominousFromLevel() {
        return ominousFromLevel;
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

        baseMobsPerEncounter = 2;
        maxMobsPerRoom = 8;
        spawnerDensEnabled = true;

        ritualEnabled = true;
        ritualKeyItem = "minecraft:echo_shard";
        ritualKeyCount = 1;

        payoutItem = "minecraft:emerald";
        payoutBaseCount = 6;
        payoutPerTier = 4;
        streakBonusPercent = 10;
        streakBonusCapPercent = 100;
        payoutCommand = "";

        trialsEnabled = true;
        vaultKeyItem = "minecraft:trial_key";
        ominousVaultKeyItem = "minecraft:ominous_trial_key";
        ominousRequiresBottle = true;
        ominousPayoutPercent = 150;
        trialSpawnerCooldownTicks = 36000;

        keystoneItem = "minecraft:trial_key";
        keystoneTokenItem = "minecraft:trial_key";
        keystoneMaxLevel = 25;
        depletionOnDeath = 2;
        depletionOnExit = 1;
        depletionOnDisconnect = 3;
        overtimeDepletion = 0;
        timerEnabled = true;
        timerBaseSeconds = 180;
        timerPerRoomSeconds = 60;
        payoutPerLevelPercent = 5;
        ominousFromLevel = 10;
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

        baseMobsPerEncounter = readInt(root, "baseMobsPerEncounter", 2, v -> v >= 0, "must be >= 0");
        maxMobsPerRoom = readInt(root, "maxMobsPerRoom", 8, v -> v >= 1, "must be >= 1");
        spawnerDensEnabled = readBoolean(root, "spawnerDensEnabled", true);

        ritualEnabled = readBoolean(root, "ritualEnabled", true);
        ritualKeyItem = readString(root, "ritualKeyItem", "minecraft:echo_shard", false);
        ritualKeyCount = readInt(root, "ritualKeyCount", 1, v -> v >= 1, "must be >= 1");

        payoutItem = readString(root, "payoutItem", "minecraft:emerald", false);
        payoutBaseCount = readInt(root, "payoutBaseCount", 6, v -> v >= 0, "must be >= 0");
        payoutPerTier = readInt(root, "payoutPerTier", 4, v -> v >= 0, "must be >= 0");
        streakBonusPercent = readInt(root, "streakBonusPercent", 10, v -> v >= 0, "must be >= 0");
        streakBonusCapPercent = readInt(root, "streakBonusCapPercent", 100,
                v -> v >= 0, "must be >= 0");
        payoutCommand = readString(root, "payoutCommand", "", true);

        trialsEnabled = readBoolean(root, "trialsEnabled", true);
        vaultKeyItem = readString(root, "vaultKeyItem", "minecraft:trial_key", false);
        ominousVaultKeyItem = readString(root, "ominousVaultKeyItem",
                "minecraft:ominous_trial_key", false);
        ominousRequiresBottle = readBoolean(root, "ominousRequiresBottle", true);
        ominousPayoutPercent = readInt(root, "ominousPayoutPercent", 150,
                v -> v >= 100, "must be >= 100");
        trialSpawnerCooldownTicks = readInt(root, "trialSpawnerCooldownTicks", 36000,
                v -> v >= 0, "must be >= 0");

        keystoneItem = readString(root, "keystoneItem", "minecraft:trial_key", false);
        keystoneTokenItem = readString(root, "keystoneTokenItem", "minecraft:trial_key", false);
        keystoneMaxLevel = readInt(root, "keystoneMaxLevel", 25, v -> v >= 1, "must be >= 1");
        depletionOnDeath = readInt(root, "depletionOnDeath", 2, v -> v >= 0, "must be >= 0");
        depletionOnExit = readInt(root, "depletionOnExit", 1, v -> v >= 0, "must be >= 0");
        depletionOnDisconnect = readInt(root, "depletionOnDisconnect", 3,
                v -> v >= 0, "must be >= 0");
        overtimeDepletion = readInt(root, "overtimeDepletion", 0, v -> v >= 0, "must be >= 0");
        timerEnabled = readBoolean(root, "timerEnabled", true);
        timerBaseSeconds = readInt(root, "timerBaseSeconds", 180, v -> v >= 0, "must be >= 0");
        timerPerRoomSeconds = readInt(root, "timerPerRoomSeconds", 60, v -> v >= 0, "must be >= 0");
        payoutPerLevelPercent = readInt(root, "payoutPerLevelPercent", 5,
                v -> v >= 0, "must be >= 0");
        ominousFromLevel = readInt(root, "ominousFromLevel", 10, v -> v >= 1, "must be >= 1");
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

        root.addProperty("baseMobsPerEncounter", 2);
        root.addProperty("maxMobsPerRoom", 8);
        root.addProperty("spawnerDensEnabled", true);

        root.addProperty("ritualEnabled", true);
        root.addProperty("ritualKeyItem", "minecraft:echo_shard");
        root.addProperty("ritualKeyCount", 1);

        root.addProperty("payoutItem", "minecraft:emerald");
        root.addProperty("payoutBaseCount", 6);
        root.addProperty("payoutPerTier", 4);
        root.addProperty("streakBonusPercent", 10);
        root.addProperty("streakBonusCapPercent", 100);
        root.addProperty("payoutCommand", "");

        root.addProperty("trialsEnabled", true);
        root.addProperty("vaultKeyItem", "minecraft:trial_key");
        root.addProperty("ominousVaultKeyItem", "minecraft:ominous_trial_key");
        root.addProperty("ominousRequiresBottle", true);
        root.addProperty("ominousPayoutPercent", 150);
        root.addProperty("trialSpawnerCooldownTicks", 36000);

        root.addProperty("keystoneItem", "minecraft:trial_key");
        root.addProperty("keystoneTokenItem", "minecraft:trial_key");
        root.addProperty("keystoneMaxLevel", 25);
        root.addProperty("depletionOnDeath", 2);
        root.addProperty("depletionOnExit", 1);
        root.addProperty("depletionOnDisconnect", 3);
        root.addProperty("overtimeDepletion", 0);
        root.addProperty("timerEnabled", true);
        root.addProperty("timerBaseSeconds", 180);
        root.addProperty("timerPerRoomSeconds", 60);
        root.addProperty("payoutPerLevelPercent", 5);
        root.addProperty("ominousFromLevel", 10);
        return root;
    }
}
