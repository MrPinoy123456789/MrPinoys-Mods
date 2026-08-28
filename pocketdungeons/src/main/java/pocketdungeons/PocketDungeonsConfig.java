package pocketdungeons;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
    private static int keystoneMaxLevel = 100;
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

    // ---- ladder reframe (M10) -------------------------------------------------
    /** +1% mob strength (max health, attack damage, movement speed) per keystone level. */
    private static double mobScalePerLevel = 0.01;
    /**
     * Fraction of a run's trial spawners that must reach {@code COOLDOWN} before
     * a pad contact completes the run. An untouched spawner sits at
     * {@code INACTIVE}, not {@code COOLDOWN}, and counts against this same
     * denominator: sprinting past every spawner is exactly what this gate
     * exists to refuse.
     */
    private static double spawnerClearThreshold = 0.75;

    // ---- two-tier doors and fuel (M12) ----------------------------------------
    /**
     * The currency doors 2/3 cost and door 1 pays out. Echo shards, not
     * diamonds: diegetically the keystone (a recovery compass) is crafted from
     * echo shards, and nothing else in the mod's loot tables grants any, so
     * door 1's guaranteed payout is the only source. That is deliberate (see
     * "Fuel currency" in the M12 plan section): a Greater room dropping fuel
     * of its own would let the premium tier fund itself.
     */
    private static String fuelItem = "minecraft:echo_shard";
    /** Door 1's own flat clock, generous rather than tied to room count. */
    private static int door1TimerSeconds = 300;
    /** What a door 2/3 choice costs, spent atomically with the level upgrade. */
    private static int fuelCostPerGreaterDoor = 3;
    /** What a completed door-1 run pays out, guaranteed, every time. */
    private static int fuelPerFreeRun = 1;
    /**
     * The keystone level a door 2/3 offer requires before it can be taken.
     * Applies to the <em>current</em> level being upgraded, not the offer's
     * resulting level: the whole point is that a low key cannot reach the
     * premium path at all, not that it reaches a worse version of it.
     */
    private static int greaterDoorMinLevel = 15;

    // ---- gear reroll station (M14) --------------------------------------------
    /** The block a reroll station is; right-clicking it with tiered gear opens the picker. */
    private static String rerollBlock = "minecraft:smithing_table";
    /** Lapis cost per tier step; a tier-N reroll costs this times N. */
    private static int rerollLapisPerTier = 4;
    /**
     * Keystone level required before the station will open for a player. The
     * fuel-gated-door precedent (M12) argues an ungated lapis sink stops
     * sinking once lapis overflows; this is deliberately low (matches
     * {@code AffixMath}'s first seeded-affix threshold) rather than gated
     * behind Greater-door access, since the sink should be available well
     * before a player has fuel to spend on doors 2/3.
     */
    private static int rerollUnlockLevel = 5;

    // ---- armor trims (M15) -----------------------------------------------------
    /**
     * Material decides the bonus; pattern stays cosmetic. Ten materials, tuned
     * once here, rather than authoring a cell per material/pattern combination.
     * {@code attribute} is a registry id resolved against {@code BuiltInRegistries
     * .ATTRIBUTE} at the use site; {@code operation} is an
     * {@code AttributeModifier.Operation}'s serialized name
     * ({@code add_value}, {@code add_multiplied_base}, {@code add_multiplied_total}).
     */
    private static List<TrimBonusEntry> trimBonuses = defaultTrimBonuses();
    /**
     * If true, a worn trim's bonus applies only while the wearer is in the
     * dungeon dimension; if false, it applies everywhere the armour is worn.
     * {@code VISION.md} section 3.7 permits the global reading by its letter
     * (the template and material are both dungeon-loot-gated), so the
     * milestone default is global; an operator who wants the narrower
     * reading can flip this.
     */
    private static boolean trimBonusDungeonOnly = false;

    /** One material's equip-time attribute bonus. See {@link #trimBonuses}. */
    public record TrimBonusEntry(String material, String attribute, double amount, String operation) {}

    /** One extracted power's equip-time attribute bonus. See {@link #powerBonuses}. */
    public record PowerBonusEntry(String id, String attribute, double amount, String operation) {}

    private static List<PowerBonusEntry> defaultPowerBonuses() {
        // One proof pairing, matching the one proof reward this milestone wires
        // end to end (drowned_vault's boss node). More powers are content work,
        // authored the same way once M11 names more rare-node rewards.
        return List.of(new PowerBonusEntry("warden_ward", "minecraft:knockback_resistance", 0.2, "add_value"));
    }

    private static List<TrimBonusEntry> defaultTrimBonuses() {
        return List.of(
                new TrimBonusEntry("minecraft:diamond", "minecraft:armor_toughness", 1.0, "add_value"),
                new TrimBonusEntry("minecraft:netherite", "minecraft:knockback_resistance", 0.1, "add_value"),
                new TrimBonusEntry("minecraft:gold", "minecraft:movement_speed", 0.02, "add_value"),
                new TrimBonusEntry("minecraft:iron", "minecraft:armor", 1.0, "add_value"),
                new TrimBonusEntry("minecraft:copper", "minecraft:mining_efficiency", 0.5, "add_value"),
                new TrimBonusEntry("minecraft:redstone", "minecraft:attack_speed", 0.1, "add_value"),
                new TrimBonusEntry("minecraft:emerald", "minecraft:luck", 1.0, "add_value"),
                new TrimBonusEntry("minecraft:lapis", "minecraft:safe_fall_distance", 3.0, "add_value"),
                new TrimBonusEntry("minecraft:amethyst", "minecraft:entity_interaction_range", 1.0, "add_value"),
                new TrimBonusEntry("minecraft:quartz", "minecraft:max_health", 2.0, "add_value"),
                new TrimBonusEntry("minecraft:resin", "minecraft:fall_damage_multiplier", -0.1, "add_value")
        );
    }

    // ---- gamble station (M16) ---------------------------------------------------
    /** The block a gamble station is; right-clicking it opens the slot/tier picker. */
    private static String gambleBlock = "minecraft:emerald_block";
    /** Emerald cost per tier step for an unweighted slot; a tier-N gamble costs this times N. */
    private static int gambleEmeraldsPerTier = 6;
    /**
     * D3's own Kadala menu prices weapon pulls above armour pulls; this is the
     * multiplier {@link #gambleWeightedSlot()}'s slot gets on top of the plain
     * tier cost.
     */
    private static double gambleSlotMultiplier = 1.5;
    /**
     * Which of {@link LootTables#GEAR_SLOTS} is the weighted one. A slot name,
     * not an index, so a reordering of {@code GEAR_SLOTS} cannot silently
     * repoint the weighting at the wrong slot.
     */
    private static String gambleWeightedSlot = "weapon";

    // ---- Herobrine Cube (M17) -----------------------------------------------
    /**
     * The block the Cube ritual is. Right-clicking it holding a rare item
     * extracts; right-clicking it holding an ordinary weapon or armour piece
     * opens the imbue picker. A configured block, not a mixin-intercepted
     * crafting table: {@code Ingredient} (verified against the 26.2 jar) has no
     * component-value predicate, so "any item, plus my chosen one of an
     * open-ended power library" cannot be expressed as a datapack crafting
     * recipe without one recipe per (item type x power) pair. A block-use
     * ritual sidesteps that limit entirely and needs no second mixin; see
     * {@code D3_PROGRESSION_PLAN.md}'s M17 section for the full reasoning.
     */
    private static String cubeBlock = "minecraft:beacon";
    /**
     * One extracted power's equip-time attribute bonus, read by
     * {@code PowerListener} the same way {@link TrimBonusEntry} feeds
     * {@code TrimListener}: {@code id} is the power id an extract ritual wrote
     * ({@link AdventureGraph.Node#reward}), matched against
     * {@code custom_data.pocketdungeons.power} on an imbued item.
     */
    private static List<PowerBonusEntry> powerBonuses = defaultPowerBonuses();
    /** The material an imbue ritual spends alongside the extracted-power token. Cheap and repeatable by design. */
    private static String imbueMaterial = "minecraft:iron_ingot";
    /** How much {@link #imbueMaterial} an imbue ritual costs. */
    private static int imbueCost = 4;
    /**
     * How many extracted powers can be active on a player at once, across every
     * worn/held slot the Cube reads. A pool, not a per-piece allowance: three
     * powers spread across five imbuable slots is still "at most three," not
     * "at most three per slot." D3's own count.
     */
    private static int equipCap = 3;
    /**
     * Whether an extraction can be undone. D3's own answer is no: a hard sink,
     * the rare item gone for good in exchange for never needing another like
     * it. {@code false} by default; an operator running a server where a
     * mis-click destroys a genuinely rare drop forever may want {@code true}.
     * There is no undo ritual yet either way (see {@code D3_PROGRESSION_PLAN.md}'s
     * M17 section); this flag only gates whether one could exist.
     */
    private static boolean extractionReversible = false;

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

    public static double mobScalePerLevel() {
        return mobScalePerLevel;
    }

    public static double spawnerClearThreshold() {
        return spawnerClearThreshold;
    }

    public static String fuelItem() {
        return fuelItem;
    }

    public static int door1TimerSeconds() {
        return door1TimerSeconds;
    }

    public static int fuelCostPerGreaterDoor() {
        return fuelCostPerGreaterDoor;
    }

    public static int fuelPerFreeRun() {
        return fuelPerFreeRun;
    }

    public static int greaterDoorMinLevel() {
        return greaterDoorMinLevel;
    }

    public static String rerollBlock() {
        return rerollBlock;
    }

    public static int rerollLapisPerTier() {
        return rerollLapisPerTier;
    }

    public static int rerollUnlockLevel() {
        return rerollUnlockLevel;
    }

    public static String gambleBlock() {
        return gambleBlock;
    }

    public static int gambleEmeraldsPerTier() {
        return gambleEmeraldsPerTier;
    }

    public static double gambleSlotMultiplier() {
        return gambleSlotMultiplier;
    }

    public static String gambleWeightedSlot() {
        return gambleWeightedSlot;
    }

    public static String cubeBlock() {
        return cubeBlock;
    }

    public static List<PowerBonusEntry> powerBonuses() {
        return powerBonuses;
    }

    public static String imbueMaterial() {
        return imbueMaterial;
    }

    public static int imbueCost() {
        return imbueCost;
    }

    public static int equipCap() {
        return equipCap;
    }

    public static boolean extractionReversible() {
        return extractionReversible;
    }

    public static List<TrimBonusEntry> trimBonuses() {
        return trimBonuses;
    }

    public static boolean trimBonusDungeonOnly() {
        return trimBonusDungeonOnly;
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
        keystoneMaxLevel = 100;
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

        mobScalePerLevel = 0.01;
        spawnerClearThreshold = 0.75;

        fuelItem = "minecraft:echo_shard";
        door1TimerSeconds = 300;
        fuelCostPerGreaterDoor = 3;
        fuelPerFreeRun = 1;
        greaterDoorMinLevel = 15;

        rerollBlock = "minecraft:smithing_table";
        rerollLapisPerTier = 4;
        rerollUnlockLevel = 5;

        trimBonuses = defaultTrimBonuses();
        trimBonusDungeonOnly = false;

        gambleBlock = "minecraft:emerald_block";
        gambleEmeraldsPerTier = 6;
        gambleSlotMultiplier = 1.5;
        gambleWeightedSlot = "weapon";

        cubeBlock = "minecraft:beacon";
        powerBonuses = defaultPowerBonuses();
        imbueMaterial = "minecraft:iron_ingot";
        imbueCost = 4;
        equipCap = 3;
        extractionReversible = false;
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
        keystoneMaxLevel = readInt(root, "keystoneMaxLevel", 100, v -> v >= 1, "must be >= 1");
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

        mobScalePerLevel = readDouble(root, "mobScalePerLevel", 0.01, v -> v >= 0, "must be >= 0");
        spawnerClearThreshold = readDouble(root, "spawnerClearThreshold", 0.75,
                v -> v > 0.0 && v <= 1.0, "must be between 0.0 (exclusive) and 1.0");

        fuelItem = readString(root, "fuelItem", "minecraft:echo_shard", false);
        door1TimerSeconds = readInt(root, "door1TimerSeconds", 300, v -> v >= 0, "must be >= 0");
        fuelCostPerGreaterDoor = readInt(root, "fuelCostPerGreaterDoor", 3, v -> v >= 0, "must be >= 0");
        fuelPerFreeRun = readInt(root, "fuelPerFreeRun", 1, v -> v >= 0, "must be >= 0");
        greaterDoorMinLevel = readInt(root, "greaterDoorMinLevel", 15, v -> v >= 1, "must be >= 1");

        rerollBlock = readString(root, "rerollBlock", "minecraft:smithing_table", false);
        rerollLapisPerTier = readInt(root, "rerollLapisPerTier", 4, v -> v >= 0, "must be >= 0");
        rerollUnlockLevel = readInt(root, "rerollUnlockLevel", 5, v -> v >= 1, "must be >= 1");

        trimBonuses = readTrimBonuses(root);
        trimBonusDungeonOnly = readBoolean(root, "trimBonusDungeonOnly", false);

        gambleBlock = readString(root, "gambleBlock", "minecraft:emerald_block", false);
        gambleEmeraldsPerTier = readInt(root, "gambleEmeraldsPerTier", 6, v -> v >= 0, "must be >= 0");
        gambleSlotMultiplier = readDouble(root, "gambleSlotMultiplier", 1.5, v -> v >= 1.0,
                "must be >= 1.0");
        gambleWeightedSlot = readString(root, "gambleWeightedSlot", "weapon", false);

        cubeBlock = readString(root, "cubeBlock", "minecraft:beacon", false);
        powerBonuses = readPowerBonuses(root);
        imbueMaterial = readString(root, "imbueMaterial", "minecraft:iron_ingot", false);
        imbueCost = readInt(root, "imbueCost", 4, v -> v >= 0, "must be >= 0");
        equipCap = readInt(root, "equipCap", 3, v -> v >= 0, "must be >= 0");
        extractionReversible = readBoolean(root, "extractionReversible", false);
    }

    private static List<PowerBonusEntry> readPowerBonuses(JsonObject root) {
        if (!root.has("powerBonuses") || !root.get("powerBonuses").isJsonArray()) {
            PocketDungeonsMod.LOG.error(
                    "pocketdungeons.json field 'powerBonuses' is missing or not an array; using defaults");
            return defaultPowerBonuses();
        }
        JsonArray array = root.getAsJsonArray("powerBonuses");
        List<PowerBonusEntry> parsed = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            try {
                JsonObject entry = array.get(i).getAsJsonObject();
                String id = entry.get("id").getAsString();
                String attribute = entry.get("attribute").getAsString();
                double amount = entry.get("amount").getAsDouble();
                String operation = entry.get("operation").getAsString();
                if (id.isBlank() || attribute.isBlank() || operation.isBlank()) {
                    throw new IllegalArgumentException("id, attribute, and operation must not be blank");
                }
                parsed.add(new PowerBonusEntry(id, attribute, amount, operation));
            } catch (Exception e) {
                PocketDungeonsMod.LOG.error(
                        "pocketdungeons.json field 'powerBonuses[{}]' is malformed and was skipped", i, e);
            }
        }
        if (parsed.isEmpty()) {
            PocketDungeonsMod.LOG.error(
                    "pocketdungeons.json field 'powerBonuses' has no valid entries; using defaults");
            return defaultPowerBonuses();
        }
        return Collections.unmodifiableList(parsed);
    }

    private static List<TrimBonusEntry> readTrimBonuses(JsonObject root) {
        if (!root.has("trimBonuses") || !root.get("trimBonuses").isJsonArray()) {
            PocketDungeonsMod.LOG.error(
                    "pocketdungeons.json field 'trimBonuses' is missing or not an array; using defaults");
            return defaultTrimBonuses();
        }
        JsonArray array = root.getAsJsonArray("trimBonuses");
        List<TrimBonusEntry> parsed = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            try {
                JsonObject entry = array.get(i).getAsJsonObject();
                String material = entry.get("material").getAsString();
                String attribute = entry.get("attribute").getAsString();
                double amount = entry.get("amount").getAsDouble();
                String operation = entry.get("operation").getAsString();
                if (material.isBlank() || attribute.isBlank() || operation.isBlank()) {
                    throw new IllegalArgumentException("material, attribute, and operation must not be blank");
                }
                parsed.add(new TrimBonusEntry(material, attribute, amount, operation));
            } catch (Exception e) {
                PocketDungeonsMod.LOG.error(
                        "pocketdungeons.json field 'trimBonuses[{}]' is malformed and was skipped", i, e);
            }
        }
        if (parsed.isEmpty()) {
            PocketDungeonsMod.LOG.error(
                    "pocketdungeons.json field 'trimBonuses' has no valid entries; using defaults");
            return defaultTrimBonuses();
        }
        return Collections.unmodifiableList(parsed);
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
        root.addProperty("keystoneMaxLevel", 100);
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

        root.addProperty("mobScalePerLevel", 0.01);
        root.addProperty("spawnerClearThreshold", 0.75);

        root.addProperty("fuelItem", "minecraft:echo_shard");
        root.addProperty("door1TimerSeconds", 300);
        root.addProperty("fuelCostPerGreaterDoor", 3);
        root.addProperty("fuelPerFreeRun", 1);
        root.addProperty("greaterDoorMinLevel", 15);

        root.addProperty("rerollBlock", "minecraft:smithing_table");
        root.addProperty("rerollLapisPerTier", 4);
        root.addProperty("rerollUnlockLevel", 5);

        JsonArray trimBonusesJson = new JsonArray();
        for (TrimBonusEntry entry : defaultTrimBonuses()) {
            JsonObject entryJson = new JsonObject();
            entryJson.addProperty("material", entry.material());
            entryJson.addProperty("attribute", entry.attribute());
            entryJson.addProperty("amount", entry.amount());
            entryJson.addProperty("operation", entry.operation());
            trimBonusesJson.add(entryJson);
        }
        root.add("trimBonuses", trimBonusesJson);
        root.addProperty("trimBonusDungeonOnly", false);

        root.addProperty("gambleBlock", "minecraft:emerald_block");
        root.addProperty("gambleEmeraldsPerTier", 6);
        root.addProperty("gambleSlotMultiplier", 1.5);
        root.addProperty("gambleWeightedSlot", "weapon");

        root.addProperty("cubeBlock", "minecraft:beacon");
        JsonArray powerBonusesJson = new JsonArray();
        for (PowerBonusEntry entry : defaultPowerBonuses()) {
            JsonObject entryJson = new JsonObject();
            entryJson.addProperty("id", entry.id());
            entryJson.addProperty("attribute", entry.attribute());
            entryJson.addProperty("amount", entry.amount());
            entryJson.addProperty("operation", entry.operation());
            powerBonusesJson.add(entryJson);
        }
        root.add("powerBonuses", powerBonusesJson);
        root.addProperty("imbueMaterial", "minecraft:iron_ingot");
        root.addProperty("imbueCost", 4);
        root.addProperty("equipCap", 3);
        root.addProperty("extractionReversible", false);
        return root;
    }
}
