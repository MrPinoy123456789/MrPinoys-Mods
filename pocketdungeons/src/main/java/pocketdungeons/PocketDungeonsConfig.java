package pocketdungeons;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
    /**
     * How long a run waits for an owner who lost their connection while the
     * rest of the party is still inside, before it ends for everyone. 0 ends
     * it at once, as it did before the grace existed.
     */
    private static int ownerReconnectGraceSeconds = 120;
    // M76: operating envelope. These bound how much concurrent world work the
    // mod can hold at once, so an overload refuses a new request before it
    // charges fuel or a catalyst rather than letting the server thrash. They
    // are measurement points for a declared cap, not promised capacity: a
    // mixed-mod server's real ceiling depends on its own hardware and
    // population, which is exactly why the load test (dungeonLoadTest) is the
    // thing an operator runs to set them.
    private static int maxConcurrentInstances = 32;
    private static int maxConcurrentVisits = 16;
    private static int maxConcurrentPreviews = 16;

    // ---- playtest journal and Lemon -------------------------------------------
    /** Record the playtest journal ({@code docs/PLAYTEST_EVENTS.md}). */
    private static boolean playtestJournal = true;
    /** Seconds Lemon lingers after its last line before it vanishes. */
    private static int lemonIdleSeconds = 20;
    /** Seconds Lemon waits for the answer to a question before it vanishes. */
    private static int lemonAskIdleSeconds = 120;
    /** Seconds an agent's LLM mode lasts without a refresh before Lemon falls back to guide mode. */
    private static int lemonLlmLapseSeconds = 300;
    /** Seconds a player's question waits for the agent before guide mode answers it. */
    private static int lemonFallbackSeconds = 45;
    /** Seconds Lemon stays after its last line before vanishing. */
    private static int lemonLingerSeconds = 4;
    /** Seconds a {@code dungeon lemon think} holds the guide's fallback answer. */
    private static int lemonThinkSeconds = 90;

    /** Mob health/damage/speed bonus per point of omen on the current floor. */
    private static double omenDangerScalePerOmen = 0.15;

    // ---- layout planning ----------------------------------------------------
    // M54 (spec 6.5): the critical path goes up to 8-12 cells at tier 1, with
    // branch and loop rates high enough that a floor reads as a small maze
    // rather than a spine with stubs. The terminal is placed partway along
    // the path (LayoutGraphGenerator), so the staging room is never the
    // obvious far end.
    private static int pathLengthMin = 8;
    private static int pathLengthMax = 12;
    /**
     * Playtest 2026-10-03-2: the most encounter cells the first floor of an
     * interval may hold, so floor 1 runs about two spawners rather than four
     * ("floor 1 should be about 2 spawners"; 298 s at 05:04). 0 means no cap.
     */
    private static int firstFloorMaxEncounters = 2;
    private static double branchProbability = 0.55;
    private static double loopProbability = 0.30;
    private static int planAttemptBudget = 32;
    private static int maxGridSpan = 12;
    private static int clearBlocksPerTick = 8192;
    // The interval's usual length: after this many floors the HOME screen
    // lights up, and it is how many door steps bank one keystone level
    // (IntervalBanking). Nothing forces the party home.
    private static int floorsPerSafeVisit = 3;
    // The compass level the Endless Mine's door 3 needs (D29): the Mine is in
    // act 1 now, but a new player's first doors stay dungeons until this.
    private static int endlessMineUnlockLevel = 3;
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

    // ---- U8: the run outlives the player, the reward room, the three doors ---
    private static int rewardRoomGraceSeconds = 600;
    /**
     * How long a player in a finished dungeon may do nothing at all before the
     * reward-room grace window stops treating them as a reason to keep the slot
     * open. Measured against vanilla's own idle clock
     * ({@code ServerPlayer.getLastActionTime}), which any movement, click,
     * container interaction or chat line resets, so it is the same notion of
     * "away" the {@code player-idle-timeout} server property already uses.
     * {@code 0} disables the check: presence alone holds the slot then, however
     * long the player stands still.
     */
    private static int afkSeconds = 300;
    /**
     * What {@code /dungeon quit} on a floor in progress costs the owner's
     * keystone. Named for the clock that used to charge it; the name is kept
     * so existing config files still apply.
     */
    private static int timedOutDepletion = 1;

    // ---- affixes (M4) --------------------------------------------------------
    /** Overclocked scales {@link #trialSpawnerCooldownTicks} down by this. */
    private static double overclockedCooldownFactor = 0.4;
    /** Swarming scales a tier's total/simultaneous mob counts up by this. */
    private static double swarmingMobFactor = 1.5;
    /** Share of generic (themeless) spawners that keep the broad mixed mob pool (PD-107). */
    private static double chaoticSpawnerChance = 0.05;
    /** Silenced: this many consumables used raise the omen by one. */
    private static int silencedConsumablesPerOmen = 3;
    /** Chance a dead-end cell holds a fountain boon. */
    private static double fountainChance = 0.15;
    /** Omen a cleansing fountain takes off the floor. */
    /** Emeralds each member is paid when a dungeon is finished (J1: was the echo shard). */
    private static int finishEmeralds = 8;
    /** Extra completion chests, at the dungeon's top loot tier, a finished dungeon's themed vault adds (design D11). */
    private static int finishVaultChests = 2;
    /** The share of a haul a failed dungeon still banks, in percent (Haul and Blood Doors). */
    private static int failHaulKeepPercent = ScrapMath.FAIL_KEEP_PERCENT;
    /** Omen a capstone dungeon's final floor starts with (design D16a), 0 to 4; 0 turns the head start off. */
    /** Chance, per player in the room, of 2 emeralds when an Ordeal resolves (J1). */
    private static double ordealEmeraldChance = 0.25;
    /** Silenced's trial spawners detect players at this range instead of 14. */
    private static int silencedPlayerRange = 6;
    /** Molten hazard blocks placed per cell. */
    private static int moltenHazardsPerCell = 4;
    /** Feral wolves spawned per non-encounter cell. Zero disables the affix's spawns. */
    private static int feralWolvesPerCell = 2;
    /** Explosive fake TNT mine blocks placed per cell. */
    private static int explosiveHazardsPerCell = 4;
    /** Fraction of eligible cells that get voided floor when VOIDED affix active. */
    private static double voidedCellChance = 0.3;

    // ---- ladder reframe (M10) -------------------------------------------------
    /** +0.8% mob strength (max health, attack damage, movement speed) per keystone level. */
    private static double mobScalePerLevel = 0.008;
    /** Base mob strength multiplier at level 0. Below 1.0 makes low-level mobs weaker than vanilla. */
    private static double mobScaleBase = 0.65;
    /**
     * Additional HP-only multiplier applied to Breezes after the level scaling,
     * because their high mobility makes them disproportionately tedious to kill
     * even at low levels. 0.5 halves their post-scaling HP; 1.0 disables the
     * override entirely.
     */
    private static double breezeHpMultiplier = 0.5;
    /**
     * Fraction of a run's trial spawners that must reach {@code COOLDOWN} before
     * a pad contact completes the run. An untouched spawner sits at
     * {@code INACTIVE}, not {@code COOLDOWN}, and counts against this same
     * denominator: sprinting past every spawner is exactly what this gate
     * exists to refuse.
     */
    private static double spawnerClearThreshold = 0.75;

    // ---- two-tier doors and fuel (M12) ----------------------------------------
    // J1 retires the whole section: Fuel.java is gone, side branches cost scrap,
    // and the finish and Ordeal pays are emeralds. The old keys load quietly
    // through RETIRED_KEYS so an existing pocketdungeons.json still works.

    // ---- Pocket2 sub-dungeon (M25) -------------------------------------------
    /** How long a Pocket2 child stays open once entered, in seconds. */
    private static int pocket2TimerSeconds = 60;
    /** Chance per run that a rare door to a Pocket2 appears in a cleared encounter room. */
    private static double pocket2DoorChance = 0.2;

    // ---- anomaly rooms (M35) --------------------------------------------------
    /** Chance per run that one critical-path cell is swapped for an anomaly room. */
    private static double anomalyRoomChance = 0.08;

    // ---- gear reroll station (M14, moved to the enchanting table by J5) ---------
    /** The block a reroll station is; right-clicking it with tiered gear opens the picker. */
    private static String rerollBlock = "minecraft:enchanting_table";
    /** Lapis cost per tier step; a tier-N reroll costs this times N. */
    private static int rerollLapisPerTier = 4;

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

    // ---- run storage ------------------------------------------------------------
    /**
     * The block that opens run storage (see {@link RunStorage}). It used to open
     * the gamble station, a duplicate of the blacksmith, which was removed
     * (playtest 2026-10-02-1); the gamble itself went with the home vendor
     * rework (J5a).
     */
    private static String storageBlock = "minecraft:ender_chest";

    // ---- salvage bench (playtest 2026-09-29, docs/reference/SALVAGE_PROPOSAL.md) --
    /**
     * The block the salvage bench is. Claimed only while the player holds
     * something salvageable, so an empty hand still opens the vanilla
     * grindstone.
     */
    private static String salvageBlock = "minecraft:grindstone";
    /**
     * Superseded 2026-10-03 (owner request): scrapped gear pays the
     * grindstone's XP and its materials, never emeralds, so this is no longer
     * read. Kept so existing pocketdungeons.json files still load. It was the
     * emeralds per tier for one piece of tagged gear.
     */
    private static int salvageEmeraldsPerTier = 1;
    /** Emeralds per vault key: below the 1.4 a spawner's emerald eject is worth. */
    private static int salvageKeyEmeralds = 1;
    /** Emeralds per ominous vault key. */
    private static int salvageOminousKeyEmeralds = 3;
    /**
     * Tuning knob (2026-10-08): extra pieces of material every salvaged item pays in every
     * wear band, so what paid 0 pays 1, 1 pays 2, 2 pays 3. 0 restores the old table.
     */
    private static int salvageMaterialBonus = 1;

    // ---- durability of dungeon gear (tuning knobs, 2026-10-08) ----------------------------
    /**
     * Percent of the dungeon durability cap ({@link DungeonTools#durabilityCap}) that gear from
     * the mod's chests, vaults and gear tables gets: 110 is 10 percent more. 100 is the cap table as
     * written.
     */
    private static int lootDurabilityPercent = 110;
    /**
     * Percent of the same cap table for crafted gear and everything else (owner, 2026-10-08: all gear gets
     * 10 percent more); 100 is the table as written.
     */
    private static int craftedDurabilityPercent = 110;

    /**
     * The longest any poison lasts on a player inside a dungeon, in seconds (PD-178, owner ruling
     * 2026-10-08: "10 seconds at the most"). 0 turns the cap off.
     */
    private static int poisonMaxSeconds = 10;
    /**
     * Longest wither, slowness and mining fatigue last on a player inside a dungeon, in seconds (design pass
     * 2026-10-09, Q10e: the same "hide for 30 seconds" complaint as poison). 0 turns a cap off.
     */
    private static int witherMaxSeconds = 8;
    /** The trip sidebar (Q7): shown at all, and the ticks between repaints. */
    private static boolean sidebarEnabled = true;
    /**
     * The Astrolabe Room (design pass 2026-10-09, Q1): the first staging room lets the owner turn an astrolabe
     * to pick an act and open any of its dungeons. Off restores the old three random doors.
     */
    private static boolean hallEnabled = true;
    /** What a repeat finish of a dungeon pays of the finish emeralds, as a percent (the first finish pays all). */
    private static int repeatFinishEmeraldPercent = 50;
    private static int sidebarRepaintTicks = 20;
    private static int slownessMaxSeconds = 6;
    private static int miningFatigueMaxSeconds = 30;

    // ---- Powers (M17) --------------------------------------------------------
    // J8/14d unregistered the Herobrine Cube station itself, so the cubeBlock,
    // imbueMaterial, imbueCost and extractionReversible knobs went with it; the
    // custom_data.pocketdungeons.power marker on already-imbued gear still feeds
    // PowerListener, which is what these two remaining settings serve.
    /**
     * One extracted power's equip-time attribute bonus, read by
     * {@code PowerListener} the same way {@link TrimBonusEntry} feeds
     * {@code TrimListener}: {@code id} is the power id an extract ritual wrote
     * ({@link AdventureGraph.Node#reward}), matched against
     * {@code custom_data.pocketdungeons.power} on an imbued item.
     */
    private static List<PowerBonusEntry> powerBonuses = defaultPowerBonuses();
    /**
     * How many extracted powers can be active on a player at once, across every
     * worn/held slot the Cube reads. A pool, not a per-piece allowance: three
     * powers spread across five imbuable slots is still "at most three," not
     * "at most three per slot." D3's own count.
     */
    private static int equipCap = 3;

    /** The file {@link #load} last read, so {@link #setModuleOverride} can write it back. */
    private static volatile Path configFile;

    private PocketDungeonsConfig() {}

    /** Loads and validates every registry setting, preserving an unreadable file for manual repair. */
    public static void load(Path configDir) {
        Path file = configDir.resolve("pocketdungeons.json");
        configFile = file;
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
                // Keys added since the file was written are filled in so the
                // file stays a complete reference, but what the operator set
                // is written back as they set it: rewriting with the defaults
                // would undo every edit on the next boot.
                JsonObject rewrite = effectiveForSave(parsed);
                if (rewrite != null) {
                    try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                        GSON.toJson(rewrite, w);
                    }
                    PocketDungeonsMod.LOG.info("pocketdungeons.json updated: missing keys added at "
                            + "their defaults, retired keys dropped, every other value kept");
                }
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

    public static int ownerReconnectGraceSeconds() {
        return ownerReconnectGraceSeconds;
    }

    public static int inviteTtlSeconds() {
        return inviteTtlSeconds;
    }

    /** M76: cap on live (non-admin-build) instances. 0 disables the cap. */
    public static int maxConcurrentInstances() {
        return maxConcurrentInstances;
    }

    /** M76: cap on concurrent read-only visit instances. 0 disables the cap. */
    public static int maxConcurrentVisits() {
        return maxConcurrentVisits;
    }

    /** M76: cap on preview cells standing at once. 0 disables the cap. */
    public static int maxConcurrentPreviews() {
        return maxConcurrentPreviews;
    }

    public static boolean playtestJournal() {
        return playtestJournal;
    }

    public static int lemonIdleSeconds() {
        return lemonIdleSeconds;
    }

    public static int lemonAskIdleSeconds() {
        return lemonAskIdleSeconds;
    }

    public static int lemonLlmLapseSeconds() {
        return lemonLlmLapseSeconds;
    }

    public static int lemonFallbackSeconds() {
        return lemonFallbackSeconds;
    }

    public static int lemonLingerSeconds() {
        return lemonLingerSeconds;
    }

    public static int lemonThinkSeconds() {
        return lemonThinkSeconds;
    }

    public static double omenDangerScalePerOmen() {
        return omenDangerScalePerOmen;
    }

    public static int pathLengthMin() {
        return pathLengthMin;
    }

    public static int pathLengthMax() {
        return pathLengthMax;
    }

    public static int firstFloorMaxEncounters() {
        return firstFloorMaxEncounters;
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

    /** The interval's usual length, and the door steps a keystone level costs. See the field. */
    public static int floorsPerSafeVisit() {
        return floorsPerSafeVisit;
    }

    /** The compass level the Endless Mine's door needs. See the field. */
    public static int endlessMineUnlockLevel() {
        return endlessMineUnlockLevel;
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

    public static int finishEmeralds() {
        return finishEmeralds;
    }

    public static int finishVaultChests() {
        return finishVaultChests;
    }

    public static int failHaulKeepPercent() {
        return failHaulKeepPercent;
    }


    public static double ordealEmeraldChance() {
        return ordealEmeraldChance;
    }

    public static double fountainChance() {
        return fountainChance;
    }


    public static int silencedConsumablesPerOmen() {
        return silencedConsumablesPerOmen;
    }

    public static double chaoticSpawnerChance() {
        return chaoticSpawnerChance;
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

    public static int explosiveHazardsPerCell() {
        return explosiveHazardsPerCell;
    }

    public static double voidedCellChance() {
        return voidedCellChance;
    }

    public static double mobScalePerLevel() {
        return mobScalePerLevel;
    }

    public static double mobScaleBase() {
        return mobScaleBase;
    }

    public static double breezeHpMultiplier() {
        return breezeHpMultiplier;
    }

    public static double spawnerClearThreshold() {
        return spawnerClearThreshold;
    }

    public static int pocket2TimerSeconds() {
        return pocket2TimerSeconds;
    }

    public static double pocket2DoorChance() {
        return pocket2DoorChance;
    }

    public static double anomalyRoomChance() {
        return anomalyRoomChance;
    }

    public static String rerollBlock() {
        return rerollBlock;
    }

    public static int rerollLapisPerTier() {
        return rerollLapisPerTier;
    }

    public static String storageBlock() {
        return storageBlock;
    }

    public static String salvageBlock() {
        return salvageBlock;
    }

    public static int salvageEmeraldsPerTier() {
        return salvageEmeraldsPerTier;
    }

    public static int salvageKeyEmeralds() {
        return salvageKeyEmeralds;
    }

    public static int salvageMaterialBonus() {
        return salvageMaterialBonus;
    }

    public static int lootDurabilityPercent() {
        return lootDurabilityPercent;
    }

    public static int craftedDurabilityPercent() {
        return craftedDurabilityPercent;
    }

    public static int poisonMaxSeconds() {
        return poisonMaxSeconds;
    }

    public static int witherMaxSeconds() {
        return witherMaxSeconds;
    }

    public static boolean hallEnabled() {
        return hallEnabled;
    }

    public static int repeatFinishEmeraldPercent() {
        return repeatFinishEmeraldPercent;
    }

    public static boolean sidebarEnabled() {
        return sidebarEnabled;
    }

    public static int sidebarRepaintTicks() {
        return sidebarRepaintTicks;
    }

    public static int slownessMaxSeconds() {
        return slownessMaxSeconds;
    }

    public static int miningFatigueMaxSeconds() {
        return miningFatigueMaxSeconds;
    }

    public static int salvageOminousKeyEmeralds() {
        return salvageOminousKeyEmeralds;
    }

    public static List<PowerBonusEntry> powerBonuses() {
        return powerBonuses;
    }

    public static int equipCap() {
        return equipCap;
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


    public static int rewardRoomGraceSeconds() {
        return rewardRoomGraceSeconds;
    }

    public static int afkSeconds() {
        return afkSeconds;
    }

    public static int timedOutDepletion() {
        return timedOutDepletion;
    }

    private static void applyDefaults() {
        slotPitch = 2048;
        slotsPerRow = 64;
        watchIntervalTicks = 20;
        voidGuardDepth = 10;
        maxPartyMembers = 6;
        inviteTtlSeconds = 120;
        ownerReconnectGraceSeconds = 120;
        maxConcurrentInstances = 32;
        maxConcurrentVisits = 16;
        maxConcurrentPreviews = 16;
        playtestJournal = true;
        lemonIdleSeconds = 20;
        lemonAskIdleSeconds = 120;
        lemonLlmLapseSeconds = 300;
        lemonFallbackSeconds = 45;
        lemonLingerSeconds = 4;
        lemonThinkSeconds = 90;
        omenDangerScalePerOmen = 0.15;

        pathLengthMin = 8;
        pathLengthMax = 12;
        firstFloorMaxEncounters = 2;
        branchProbability = 0.55;
        loopProbability = 0.30;
        planAttemptBudget = 32;
        maxGridSpan = 12;
        clearBlocksPerTick = 8192;
        floorsPerSafeVisit = 3;
        endlessMineUnlockLevel = 3;
        ritualEnabled = true;

        payoutCommand = "";

        vaultKeyItem = "minecraft:trial_key";
        ominousVaultKeyItem = "minecraft:ominous_trial_key";
        trialSpawnerCooldownTicks = 36000;

        keystoneItem = "minecraft:recovery_compass";
        keystoneMaxLevel = 100;

        rewardRoomGraceSeconds = 600;
        afkSeconds = 300;
        timedOutDepletion = 1;

        overclockedCooldownFactor = 0.4;
        swarmingMobFactor = 1.5;
        chaoticSpawnerChance = 0.05;
        silencedConsumablesPerOmen = 3;
        fountainChance = 0.15;
        finishEmeralds = 8;
        finishVaultChests = 2;
        failHaulKeepPercent = ScrapMath.FAIL_KEEP_PERCENT;
        ordealEmeraldChance = 0.25;
        silencedPlayerRange = 6;
        moltenHazardsPerCell = 4;
        feralWolvesPerCell = 2;
        explosiveHazardsPerCell = 4;
        voidedCellChance = 0.3;

        mobScalePerLevel = 0.008;
        mobScaleBase = 0.65;
        breezeHpMultiplier = 0.5;
        spawnerClearThreshold = 0.75;

        pocket2TimerSeconds = 60;
        pocket2DoorChance = 0.2;

        anomalyRoomChance = 0.08;

        rerollBlock = "minecraft:enchanting_table";
        rerollLapisPerTier = 4;

        trimBonuses = defaultTrimBonuses();
        trimBonusDungeonOnly = false;

        storageBlock = "minecraft:ender_chest";

        salvageBlock = "minecraft:grindstone";
        salvageEmeraldsPerTier = 1;
        salvageKeyEmeralds = 1;
        salvageOminousKeyEmeralds = 3;
        salvageMaterialBonus = 1;
        lootDurabilityPercent = 110;
        craftedDurabilityPercent = 110;
        poisonMaxSeconds = 10;
        witherMaxSeconds = 8;
        sidebarEnabled = true;
        hallEnabled = true;
        repeatFinishEmeraldPercent = 50;
        sidebarRepaintTicks = 20;
        slownessMaxSeconds = 6;
        miningFatigueMaxSeconds = 30;

        powerBonuses = defaultPowerBonuses();
        equipCap = 3;

        // L2 (D41): no content module overrides; every module sits at its manifest default.
        ContentModules.setOverrides(Map.of());
    }

    /**
     * Keys the mod once read and no longer does: the run clock and its chest
     * scoring, and the Fuel currency keys J1 retired. A file that still carries
     * them loads normally; they are named once in the log and left out of
     * the next save.
     */
    private static final List<String> RETIRED_KEYS = List.of(
            "timerBaseSeconds", "timerPerRoomSeconds", "door1TimerSeconds",
            "threeChestPercent", "twoChestPercent", "lateCompletionDepletion",
            // J1: the Fuel (echo shard) currency is gone; scrap and emeralds took its places.
            "fuelItem", "fuelCostPerGreaterDoor", "fuelPerFreeRun",
            "greaterDoorMinLevel", "door2MinLevel", "echoShardsPerInterval",
            "echoShardsPerFinish", "echoShardFloorChance", "echoShardOrdealChance",
            "salvageKeysPerFuel",
            // Steps 14 and 18: the kit top-up, lock in, gamble, per-station unlock
            // levels and the Herobrine Cube knobs are gone.
            "kitTopUpBandLow", "kitTopUpBandMid", "kitTopUpBandHigh",
            "lockInEmeralds", "lockInUnlockLevel", "gambleEmeraldsPerTier",
            "gambleSlotMultiplier", "gambleWeightedSlot", "gambleUnlockLevel",
            "rerollUnlockLevel", "salvageUnlockLevel", "cubeBlock", "cubeUnlockLevel",
            "imbueMaterial", "imbueCost", "extractionReversible",
            // Haul and Blood Doors: side doors cost a life, and floors below your level pay scrap.
            "overlevelEmeraldsPerScrap");

    private static void apply(JsonObject root) {
        List<String> retired = RETIRED_KEYS.stream().filter(root::has).toList();
        if (!retired.isEmpty()) {
            PocketDungeonsMod.LOG.info("pocketdungeons.json still sets {}, which no longer do anything "
                    + "(retired keys); ignoring them", String.join(", ", retired));
        }
        // L2 (D41): "modules": {"alchemy": true} overrides a content module's
        // manifest default. A missing entry means the manifest default; a
        // non-boolean value is ignored with a warning; an id no module
        // declares is kept but reported at content reload time
        // (ContentModuleLoader.parse).
        Map<String, Boolean> moduleOverrides = new java.util.LinkedHashMap<>();
        JsonElement modulesElement = root.get("modules");
        if (modulesElement != null && modulesElement.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : modulesElement.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
                    moduleOverrides.put(JsonPackSupport.qualify(entry.getKey()), value.getAsBoolean());
                } else {
                    PocketDungeonsMod.LOG.warn("pocketdungeons.json modules.{} must be true or false; "
                            + "ignoring", entry.getKey());
                }
            }
        }
        ContentModules.setOverrides(moduleOverrides);
        // A cell is exactly one chunk only while the slot origin is chunk-aligned,
        // and every force-load and teardown calculation downstream leans on that.
        slotPitch = readInt(root, "slotPitch", 2048,
                v -> v > 0 && v % 16 == 0, "must be > 0 and a multiple of 16");
        slotsPerRow = readInt(root, "slotsPerRow", 64, v -> v > 0, "must be > 0");
        watchIntervalTicks = readInt(root, "watchIntervalTicks", 20, v -> v > 0, "must be > 0");
        voidGuardDepth = readInt(root, "voidGuardDepth", 10, v -> v >= 0, "must be >= 0");
        maxPartyMembers = readInt(root, "maxPartyMembers", 6, v -> v >= 1, "must be >= 1");
        // PD-45: 0 made the window sub-millisecond in practice (both
        // consumers compare an expiry computed from currentTimeMillis() +
        // ttl * 1000L against "now"), which silently disabled party kick
        // confirmations and invites rather than the operator getting a
        // diagnostic.
        inviteTtlSeconds = readInt(root, "inviteTtlSeconds", 120, v -> v >= 1, "must be >= 1");
        ownerReconnectGraceSeconds = readInt(root, "ownerReconnectGraceSeconds", 120,
                v -> v >= 0 && v <= 3600, "must be between 0 and 3600");
        maxConcurrentInstances = readInt(root, "maxConcurrentInstances", 32,
                v -> v >= 0, "must be >= 0");
        maxConcurrentVisits = readInt(root, "maxConcurrentVisits", 16,
                v -> v >= 0, "must be >= 0");
        maxConcurrentPreviews = readInt(root, "maxConcurrentPreviews", 16,
                v -> v >= 0, "must be >= 0");
        playtestJournal = readBoolean(root, "playtestJournal", true);
        lemonIdleSeconds = readInt(root, "lemonIdleSeconds", 20,
                v -> v >= 3 && v <= 600, "must be between 3 and 600");
        lemonAskIdleSeconds = readInt(root, "lemonAskIdleSeconds", 120,
                v -> v >= 10 && v <= 1800, "must be between 10 and 1800");
        lemonLlmLapseSeconds = readInt(root, "lemonLlmLapseSeconds", 300,
                v -> v >= 30 && v <= 3600, "must be between 30 and 3600");
        lemonFallbackSeconds = readInt(root, "lemonFallbackSeconds", 45,
                v -> v >= 5 && v <= 600, "must be between 5 and 600");
        lemonLingerSeconds = readInt(root, "lemonLingerSeconds", 4,
                v -> v >= 1 && v <= 60, "must be between 1 and 60");
        lemonThinkSeconds = readInt(root, "lemonThinkSeconds", 90,
                v -> v >= 10 && v <= 600, "must be between 10 and 600");
        omenDangerScalePerOmen = readDouble(root, "omenDangerScalePerOmen", 0.15,
                v -> v >= 0.0 && v <= 1.0, "must be between 0.0 and 1.0");

        pathLengthMin = readInt(root, "pathLengthMin", 8, v -> v >= 2, "must be >= 2");
        pathLengthMax = readInt(root, "pathLengthMax", 12, v -> v >= 2, "must be >= 2");
        firstFloorMaxEncounters = readInt(root, "firstFloorMaxEncounters", 2, v -> v >= 0,
                "must be >= 0 (0 means no cap)");
        if (pathLengthMax < pathLengthMin) {
            PocketDungeonsMod.LOG.error(
                    "pocketdungeons.json pathLengthMax ({}) is below pathLengthMin ({}); "
                            + "using pathLengthMax = pathLengthMin", pathLengthMax, pathLengthMin);
            pathLengthMax = pathLengthMin;
        }
        branchProbability = readDouble(root, "branchProbability", 0.55,
                v -> v >= 0.0 && v <= 1.0, "must be between 0.0 and 1.0");
        loopProbability = readDouble(root, "loopProbability", 0.30,
                v -> v >= 0.0 && v <= 1.0, "must be between 0.0 and 1.0");
        planAttemptBudget = readInt(root, "planAttemptBudget", 32, v -> v >= 1, "must be >= 1");
        maxGridSpan = readInt(root, "maxGridSpan", 12, v -> v >= 3, "must be >= 3");
        floorsPerSafeVisit = readInt(root, "floorsPerSafeVisit", 3, v -> v >= 1, "must be >= 1");
        endlessMineUnlockLevel = readInt(root, "endlessMineUnlockLevel", 3, v -> v >= 1, "must be >= 1");
        // PD-46: a path longer than the grid can possibly hold (its cell
        // count can never exceed maxGridSpan squared, whatever shape the
        // generator folds it into) fails RoomSelector.validate for every
        // seed with no diagnostic, and the mod silently falls back to
        // StaticLayout forever. This is the worst-case bound the grid
        // literally cannot exceed, not a tight one: a real generated path
        // can still fail well below it depending on branching.
        int maxPossiblePathLength = maxGridSpan * maxGridSpan;
        if (pathLengthMax > maxPossiblePathLength) {
            PocketDungeonsMod.LOG.error(
                    "pocketdungeons.json pathLengthMax ({}) cannot fit inside a maxGridSpan of {} "
                            + "({} cells at most); clamping pathLengthMax to {}",
                    pathLengthMax, maxGridSpan, maxPossiblePathLength, maxPossiblePathLength);
            pathLengthMax = maxPossiblePathLength;
            if (pathLengthMin > pathLengthMax) {
                pathLengthMin = pathLengthMax;
            }
        }
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

        rewardRoomGraceSeconds = readInt(root, "rewardRoomGraceSeconds", 600,
                v -> v >= 0, "must be >= 0");
        afkSeconds = readInt(root, "afkSeconds", 300, v -> v >= 0, "must be >= 0");
        timedOutDepletion = readInt(root, "timedOutDepletion", 1, v -> v >= 0, "must be >= 0");

        overclockedCooldownFactor = readDouble(root, "overclockedCooldownFactor", 0.4,
                v -> v > 0 && v <= 1, "must be between 0 (exclusive) and 1");
        swarmingMobFactor = readDouble(root, "swarmingMobFactor", 1.5,
                v -> v >= 1, "must be >= 1");
        chaoticSpawnerChance = readDouble(root, "chaoticSpawnerChance", 0.05,
                v -> v >= 0 && v <= 1, "must be between 0 and 1");
        silencedPlayerRange = readInt(root, "silencedPlayerRange", 6, v -> v >= 1, "must be >= 1");
        silencedConsumablesPerOmen = readInt(root, "silencedConsumablesPerOmen", 3, v -> v >= 1, "must be >= 1");
        fountainChance = readDouble(root, "fountainChance", 0.15, v -> v >= 0 && v <= 1,
                "must be between 0 and 1");
        finishEmeralds = readInt(root, "finishEmeralds", 8, v -> v >= 0, "must be >= 0");
        finishVaultChests = readInt(root, "finishVaultChests", 2, v -> v >= 0 && v <= 6, "must be between 0 and 6");
        failHaulKeepPercent = readInt(root, "failHaulKeepPercent", ScrapMath.FAIL_KEEP_PERCENT,
                v -> v >= 0 && v <= 100, "must be between 0 and 100");
        ordealEmeraldChance = readDouble(root, "ordealEmeraldChance", 0.25, v -> v >= 0 && v <= 1,
                "must be between 0 and 1");
        moltenHazardsPerCell = readInt(root, "moltenHazardsPerCell", 4, v -> v >= 0, "must be >= 0");
        feralWolvesPerCell = readInt(root, "feralWolvesPerCell", 2, v -> v >= 0, "must be >= 0");
        explosiveHazardsPerCell = readInt(root, "explosiveHazardsPerCell", 4, v -> v >= 0, "must be >= 0");
        voidedCellChance = readDouble(root, "voidedCellChance", 0.3,
                v -> v >= 0.0 && v <= 1.0, "must be between 0.0 and 1.0");

        mobScalePerLevel = readDouble(root, "mobScalePerLevel", 0.008, v -> v >= 0, "must be >= 0");
        mobScaleBase = readDouble(root, "mobScaleBase", 0.65, v -> v > 0.0 && v <= 2.0, "must be between 0.0 and 2.0");
        breezeHpMultiplier = readDouble(root, "breezeHpMultiplier", 0.5, v -> v > 0.0 && v <= 2.0, "must be between 0.0 and 2.0");
        spawnerClearThreshold = readDouble(root, "spawnerClearThreshold", 0.75,
                v -> v > 0.0 && v <= 1.0, "must be between 0.0 (exclusive) and 1.0");

        pocket2TimerSeconds = readInt(root, "pocket2TimerSeconds", 60, v -> v >= 1, "must be >= 1");
        pocket2DoorChance = readDouble(root, "pocket2DoorChance", 0.2,
                v -> v >= 0.0 && v <= 1.0, "must be between 0.0 and 1.0");

        anomalyRoomChance = readDouble(root, "anomalyRoomChance", 0.08,
                v -> v >= 0.0 && v <= 1.0, "must be between 0.0 and 1.0");

        rerollBlock = readString(root, "rerollBlock", "minecraft:enchanting_table", false);
        // J5 moved the reroll to the enchanting table and retired the smithing
        // table as a station; a saved config that still names it is remapped
        // rather than resurrecting the old station.
        if ("minecraft:smithing_table".equals(rerollBlock)) {
            PocketDungeonsMod.LOG.warn("rerollBlock was the smithing table, which is not a station "
                    + "any more; using minecraft:enchanting_table for rerolls instead.");
            rerollBlock = "minecraft:enchanting_table";
        }
        rerollLapisPerTier = readInt(root, "rerollLapisPerTier", 4, v -> v >= 0, "must be >= 0");

        trimBonuses = readTrimBonuses(root);
        trimBonusDungeonOnly = readBoolean(root, "trimBonusDungeonOnly", false);

        storageBlock = readString(root, "storageBlock", "minecraft:ender_chest", false);
        // A build between the gamble block's removal and the 2026-10-02 swap wrote
        // the copper chest here. That block is the bag chest now, so the value would
        // make every staging room's storage a second bag chest.
        if ("minecraft:waxed_oxidized_copper_chest".equals(storageBlock)) {
            PocketDungeonsMod.LOG.warn("storageBlock was the waxed oxidized copper chest, which is the bag "
                    + "chest now; using minecraft:ender_chest for run storage instead.");
            storageBlock = "minecraft:ender_chest";
        }
        salvageBlock = readString(root, "salvageBlock", "minecraft:grindstone", false);
        salvageEmeraldsPerTier = readInt(root, "salvageEmeraldsPerTier", 1, v -> v >= 0, "must be >= 0");
        salvageKeyEmeralds = readInt(root, "salvageKeyEmeralds", 1, v -> v >= 0, "must be >= 0");
        salvageOminousKeyEmeralds = readInt(root, "salvageOminousKeyEmeralds", 3, v -> v >= 0, "must be >= 0");
        salvageMaterialBonus = readInt(root, "salvageMaterialBonus", 1, v -> v >= 0 && v <= 20, "must be 0 to 20");
        lootDurabilityPercent = readInt(root, "lootDurabilityPercent", 110, v -> v >= 10 && v <= 1000,
                "must be 10 to 1000");
        craftedDurabilityPercent = readInt(root, "craftedDurabilityPercent", 110, v -> v >= 10 && v <= 1000,
                "must be 10 to 1000");
        poisonMaxSeconds = readInt(root, "poisonMaxSeconds", 10, v -> v >= 0 && v <= 600,
                "must be 0 (off) to 600");
        witherMaxSeconds = readInt(root, "witherMaxSeconds", 8, v -> v >= 0 && v <= 600,
                "must be 0 (off) to 600");
        sidebarEnabled = readBoolean(root, "sidebarEnabled", true);
        hallEnabled = readBoolean(root, "hallEnabled", true);
        repeatFinishEmeraldPercent = readInt(root, "repeatFinishEmeraldPercent", 50, v -> v >= 0 && v <= 100,
                "must be 0 to 100");
        sidebarRepaintTicks = readInt(root, "sidebarRepaintTicks", 20, v -> v >= 5 && v <= 100,
                "must be 5 to 100");
        slownessMaxSeconds = readInt(root, "slownessMaxSeconds", 6, v -> v >= 0 && v <= 600,
                "must be 0 (off) to 600");
        miningFatigueMaxSeconds = readInt(root, "miningFatigueMaxSeconds", 30, v -> v >= 0 && v <= 600,
                "must be 0 (off) to 600");

        powerBonuses = readPowerBonuses(root);
        equipCap = readInt(root, "equipCap", 3, v -> v >= 0, "must be >= 0");
    }

    private static List<PowerBonusEntry> readPowerBonuses(JsonObject root) {
        if (!root.has("powerBonuses") || !root.get("powerBonuses").isJsonArray()) {
            PocketDungeonsMod.LOG.info(
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
            PocketDungeonsMod.LOG.info(
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
            PocketDungeonsMod.LOG.info("pocketdungeons.json field '{}' is missing; using default {}",
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
            PocketDungeonsMod.LOG.info("pocketdungeons.json field '{}' is missing; using default {}",
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
            PocketDungeonsMod.LOG.info("pocketdungeons.json field '{}' is missing; using default {}",
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
            PocketDungeonsMod.LOG.info("pocketdungeons.json field '{}' is missing; using default '{}'",
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

    /**
     * The file {@link #load} writes back after reading {@code parsed}: every key
     * the operator set, kept exactly as written (an out-of-range value included,
     * so the next boot names it again rather than the file quietly forgetting
     * it), every known key the file lacks at its default, and no
     * {@link #RETIRED_KEYS}. A key this version does not know, such as a typo,
     * is kept too: it is the operator's to remove. Returns {@code null} when
     * that is exactly what {@code parsed} already holds, so an up-to-date file
     * is left untouched.
     */
    static JsonObject effectiveForSave(JsonObject parsed) {
        JsonObject merged = new JsonObject();
        for (Map.Entry<String, JsonElement> known : defaultsJson().entrySet()) {
            JsonElement set = parsed.get(known.getKey());
            merged.add(known.getKey(), set != null ? set : known.getValue());
        }
        for (Map.Entry<String, JsonElement> own : parsed.entrySet()) {
            if (!merged.has(own.getKey()) && !RETIRED_KEYS.contains(own.getKey())) {
                merged.add(own.getKey(), own.getValue());
            }
        }
        return merged.equals(parsed) ? null : merged;
    }

    /**
     * L2 (D41): sets or clears one content module override ({@code on == null}
     * restores the manifest default) and persists the {@code "modules"} object
     * to {@code pocketdungeons.json}. Other keys are preserved: the file is
     * read back, the modules object rewritten, and the result saved. With no
     * config file on disk (unit tests) only the in-memory override moves.
     */
    public static void setModuleOverride(String id, Boolean on) {
        ContentModules.setOverride(id, on);
        Path file = configFile;
        if (file == null) {
            return;
        }
        try {
            JsonObject parsed = null;
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    parsed = GSON.fromJson(r, JsonObject.class);
                }
            }
            if (parsed == null) {
                parsed = new JsonObject();
            }
            JsonObject modules = new JsonObject();
            for (Map.Entry<String, Boolean> entry : ContentModules.overrides().entrySet()) {
                modules.addProperty(entry.getKey(), entry.getValue());
            }
            parsed.add("modules", modules);
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(parsed, w);
            }
        } catch (Exception e) {
            PocketDungeonsMod.LOG.error("pocketdungeons.json could not record module override {}", id, e);
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
        root.addProperty("ownerReconnectGraceSeconds", 120);
        root.addProperty("maxConcurrentInstances", 32);
        root.addProperty("maxConcurrentVisits", 16);
        root.addProperty("maxConcurrentPreviews", 16);
        root.addProperty("playtestJournal", true);
        root.addProperty("lemonIdleSeconds", 20);
        root.addProperty("lemonAskIdleSeconds", 120);
        root.addProperty("lemonLlmLapseSeconds", 300);
        root.addProperty("lemonFallbackSeconds", 45);
        root.addProperty("lemonLingerSeconds", 4);
        root.addProperty("lemonThinkSeconds", 90);
        root.addProperty("omenDangerScalePerOmen", 0.15);

        root.addProperty("pathLengthMin", 8);
        root.addProperty("pathLengthMax", 12);
        root.addProperty("firstFloorMaxEncounters", 2);
        root.addProperty("branchProbability", 0.55);
        root.addProperty("loopProbability", 0.30);
        root.addProperty("planAttemptBudget", 32);
        root.addProperty("maxGridSpan", 12);
        root.addProperty("clearBlocksPerTick", 8192);
        root.addProperty("floorsPerSafeVisit", 3);
        root.addProperty("endlessMineUnlockLevel", 3);

        root.addProperty("ritualEnabled", true);

        root.addProperty("payoutCommand", "");

        root.addProperty("vaultKeyItem", "minecraft:trial_key");
        root.addProperty("ominousVaultKeyItem", "minecraft:ominous_trial_key");
        root.addProperty("trialSpawnerCooldownTicks", 36000);

        root.addProperty("keystoneItem", "minecraft:recovery_compass");
        root.addProperty("keystoneMaxLevel", 100);

        root.addProperty("rewardRoomGraceSeconds", 600);
        root.addProperty("afkSeconds", 300);
        root.addProperty("timedOutDepletion", 1);

        root.addProperty("overclockedCooldownFactor", 0.4);
        root.addProperty("swarmingMobFactor", 1.5);
        root.addProperty("chaoticSpawnerChance", 0.05);
        root.addProperty("silencedPlayerRange", 6);
        root.addProperty("silencedConsumablesPerOmen", 3);
        root.addProperty("fountainChance", 0.15);
        root.addProperty("finishEmeralds", 8);
        root.addProperty("finishVaultChests", 2);
        root.addProperty("failHaulKeepPercent", ScrapMath.FAIL_KEEP_PERCENT);
        root.addProperty("ordealEmeraldChance", 0.25);
        root.addProperty("moltenHazardsPerCell", 4);
        root.addProperty("feralWolvesPerCell", 2);
        root.addProperty("explosiveHazardsPerCell", 4);
        root.addProperty("voidedCellChance", 0.3);

        root.addProperty("mobScalePerLevel", 0.008);
        root.addProperty("mobScaleBase", 0.65);
        root.addProperty("breezeHpMultiplier", 0.5);
        root.addProperty("spawnerClearThreshold", 0.75);

        root.addProperty("pocket2TimerSeconds", 60);
        root.addProperty("pocket2DoorChance", 0.2);

        root.addProperty("anomalyRoomChance", 0.08);

        root.addProperty("rerollBlock", "minecraft:enchanting_table");
        root.addProperty("rerollLapisPerTier", 4);

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

        root.addProperty("storageBlock", "minecraft:ender_chest");

        root.addProperty("salvageBlock", "minecraft:grindstone");
        root.addProperty("salvageEmeraldsPerTier", 1);
        root.addProperty("salvageKeyEmeralds", 1);
        root.addProperty("salvageOminousKeyEmeralds", 3);
        root.addProperty("salvageMaterialBonus", 1);
        root.addProperty("lootDurabilityPercent", 110);
        root.addProperty("craftedDurabilityPercent", 110);
        root.addProperty("poisonMaxSeconds", 10);
        root.addProperty("witherMaxSeconds", 8);
        root.addProperty("sidebarEnabled", true);
        root.addProperty("hallEnabled", true);
        root.addProperty("repeatFinishEmeraldPercent", 50);
        root.addProperty("sidebarRepaintTicks", 20);
        root.addProperty("slownessMaxSeconds", 6);
        root.addProperty("miningFatigueMaxSeconds", 30);

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
        root.add("modules", new JsonObject());
        root.addProperty("equipCap", 3);
        return root;
    }
}
