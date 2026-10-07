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
    // UNUSED since dungeon structure W5 (D14, no kit refill); still read and saved.
    // The share of a kit's missing stackable items a safe visit restores, by
    // the interval's omen band: calm, mid, high. Tools are replaced at the
    // calm band only, whatever these say (KitTopUp). Each in [0, 1], and no
    // band may restore more than a calmer one.
    private static double kitTopUpBandLow = 1.0;
    private static double kitTopUpBandMid = 0.5;
    private static double kitTopUpBandHigh = 0.0;

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
    /** Emeralds the librarian charges to lock in a piece of gear (adds Mending). */
    private static int lockInEmeralds = 32;
    /** Keystone level the librarian needs. */
    private static int lockInUnlockLevel = 5;
    /** Chance a dead-end cell holds a fountain boon. */
    private static double fountainChance = 0.15;
    /** Omen a cleansing fountain takes off the floor. */
    /** Emeralds each member is paid when a dungeon is finished (J1: was the echo shard). */
    private static int finishEmeralds = 8;
    /** Extra completion chests, at the dungeon's top loot tier, a finished dungeon's themed vault adds (design D11). */
    private static int finishVaultChests = 2;
    /** Emeralds per scrap a member above the floor's level is paid instead of scrap (D27). */
    private static int overlevelEmeraldsPerScrap = 2;
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
    /**
     * The block that opens run storage (see {@link RunStorage}). It used to open
     * the gamble station, a duplicate of the blacksmith, which was removed
     * (playtest 2026-10-02-1).
     */
    private static String storageBlock = "minecraft:ender_chest";
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
    /**
     * Keystone level required before the gamble station will open for a
     * player. Matches the {@code rerollUnlockLevel} shape: the sink should
     * be available before a player has fuel to spend on doors 2/3, but not
     * from run 1, since the gamble needs a gear pool (M13) and emeralds to
     * accumulate first.
     */
    private static int gambleUnlockLevel = 10;

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
    /** Keystone level the bench opens at. 1: the surplus starts in the first interval. */
    private static int salvageUnlockLevel = 1;

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
     * Keystone level required before the Cube station will open for a
     * player. The Cube's extract source is rare adventure-node rewards
     * (M11), which gate behind deeper keys; level 15 is the first station
     * that needs real progression to be useful. Low-level extract recipes
     * can be authored to give the Cube something to do before deep rare
     * nodes, the same way the gear pool gives the gamble something to
     * draw from.
     */
    private static int cubeUnlockLevel = 15;
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

    /** The kit top-up's band fractions, calm first. See the fields. */
    public static double[] kitTopUpBandFractions() {
        return new double[]{kitTopUpBandLow, kitTopUpBandMid, kitTopUpBandHigh};
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

    public static int overlevelEmeraldsPerScrap() {
        return overlevelEmeraldsPerScrap;
    }


    public static double ordealEmeraldChance() {
        return ordealEmeraldChance;
    }

    public static double fountainChance() {
        return fountainChance;
    }


    public static int lockInEmeralds() {
        return lockInEmeralds;
    }

    public static int lockInUnlockLevel() {
        return lockInUnlockLevel;
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

    public static int rerollUnlockLevel() {
        return rerollUnlockLevel;
    }

    public static String storageBlock() {
        return storageBlock;
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

    public static int gambleUnlockLevel() {
        return gambleUnlockLevel;
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

    public static int salvageOminousKeyEmeralds() {
        return salvageOminousKeyEmeralds;
    }


    public static int salvageUnlockLevel() {
        return salvageUnlockLevel;
    }

    public static String cubeBlock() {
        return cubeBlock;
    }

    public static int cubeUnlockLevel() {
        return cubeUnlockLevel;
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
        kitTopUpBandLow = 1.0;
        kitTopUpBandMid = 0.5;
        kitTopUpBandHigh = 0.0;

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
        lockInEmeralds = 32;
        lockInUnlockLevel = 5;
        fountainChance = 0.15;
        finishEmeralds = 8;
        finishVaultChests = 2;
        overlevelEmeraldsPerScrap = 2;
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

        rerollBlock = "minecraft:smithing_table";
        rerollLapisPerTier = 4;
        rerollUnlockLevel = 5;

        trimBonuses = defaultTrimBonuses();
        trimBonusDungeonOnly = false;

        storageBlock = "minecraft:ender_chest";
        gambleEmeraldsPerTier = 6;
        gambleSlotMultiplier = 1.5;
        gambleWeightedSlot = "weapon";
        gambleUnlockLevel = 10;

        salvageBlock = "minecraft:grindstone";
        salvageEmeraldsPerTier = 1;
        salvageKeyEmeralds = 1;
        salvageOminousKeyEmeralds = 3;
        salvageUnlockLevel = 1;

        cubeBlock = "minecraft:beacon";
        cubeUnlockLevel = 15;
        powerBonuses = defaultPowerBonuses();
        imbueMaterial = "minecraft:iron_ingot";
        imbueCost = 4;
        equipCap = 3;
        extractionReversible = false;
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
            "salvageKeysPerFuel");

    private static void apply(JsonObject root) {
        List<String> retired = RETIRED_KEYS.stream().filter(root::has).toList();
        if (!retired.isEmpty()) {
            PocketDungeonsMod.LOG.info("pocketdungeons.json still sets {}, which no longer do anything "
                    + "(retired keys); ignoring them", String.join(", ", retired));
        }
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
        kitTopUpBandLow = readDouble(root, "kitTopUpBandLow", 1.0,
                v -> v >= 0.0 && v <= 1.0, "must be between 0 and 1");
        kitTopUpBandMid = readDouble(root, "kitTopUpBandMid", 0.5,
                v -> v >= 0.0 && v <= 1.0, "must be between 0 and 1");
        kitTopUpBandHigh = readDouble(root, "kitTopUpBandHigh", 0.0,
                v -> v >= 0.0 && v <= 1.0, "must be between 0 and 1");
        if (kitTopUpBandMid > kitTopUpBandLow || kitTopUpBandHigh > kitTopUpBandMid) {
            // A worse band paying more would reward a riskier interval with a
            // bigger refill, the opposite of the rule. All three fall back
            // together so the set stays ordered.
            PocketDungeonsMod.LOG.error("pocketdungeons.json kitTopUpBandLow/Mid/High ({}, {}, {}) must not "
                            + "increase from calm to high; using defaults 1.0, 0.5, 0.0",
                    kitTopUpBandLow, kitTopUpBandMid, kitTopUpBandHigh);
            kitTopUpBandLow = 1.0;
            kitTopUpBandMid = 0.5;
            kitTopUpBandHigh = 0.0;
        }
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
        lockInEmeralds = readInt(root, "lockInEmeralds", 32, v -> v >= 1 && v <= 64, "must be between 1 and 64");
        lockInUnlockLevel = readInt(root, "lockInUnlockLevel", 5, v -> v >= 1, "must be >= 1");
        finishEmeralds = readInt(root, "finishEmeralds", 8, v -> v >= 0, "must be >= 0");
        finishVaultChests = readInt(root, "finishVaultChests", 2, v -> v >= 0 && v <= 6, "must be between 0 and 6");
        overlevelEmeraldsPerScrap = readInt(root, "overlevelEmeraldsPerScrap", 2, v -> v >= 0, "must be >= 0");
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

        rerollBlock = readString(root, "rerollBlock", "minecraft:smithing_table", false);
        rerollLapisPerTier = readInt(root, "rerollLapisPerTier", 4, v -> v >= 0, "must be >= 0");
        rerollUnlockLevel = readInt(root, "rerollUnlockLevel", 5, v -> v >= 1, "must be >= 1");

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
        gambleEmeraldsPerTier = readInt(root, "gambleEmeraldsPerTier", 6, v -> v >= 0, "must be >= 0");
        gambleSlotMultiplier = readDouble(root, "gambleSlotMultiplier", 1.5, v -> v >= 1.0,
                "must be >= 1.0");
        gambleWeightedSlot = readString(root, "gambleWeightedSlot", "weapon", false);
        gambleUnlockLevel = readInt(root, "gambleUnlockLevel", 10, v -> v >= 1, "must be >= 1");

        salvageBlock = readString(root, "salvageBlock", "minecraft:grindstone", false);
        salvageEmeraldsPerTier = readInt(root, "salvageEmeraldsPerTier", 1, v -> v >= 0, "must be >= 0");
        salvageKeyEmeralds = readInt(root, "salvageKeyEmeralds", 1, v -> v >= 0, "must be >= 0");
        salvageOminousKeyEmeralds = readInt(root, "salvageOminousKeyEmeralds", 3, v -> v >= 0, "must be >= 0");
        salvageUnlockLevel = readInt(root, "salvageUnlockLevel", 1, v -> v >= 1, "must be >= 1");

        cubeBlock = readString(root, "cubeBlock", "minecraft:beacon", false);
        cubeUnlockLevel = readInt(root, "cubeUnlockLevel", 15, v -> v >= 1, "must be >= 1");
        powerBonuses = readPowerBonuses(root);
        imbueMaterial = readString(root, "imbueMaterial", "minecraft:iron_ingot", false);
        imbueCost = readInt(root, "imbueCost", 4, v -> v >= 0, "must be >= 0");
        equipCap = readInt(root, "equipCap", 3, v -> v >= 0, "must be >= 0");
        extractionReversible = readBoolean(root, "extractionReversible", false);

        // PD-47: keystoneMaxLevel bounds every player's achievable level.
        // Setting it below one of these four gates makes that feature
        // permanently unreachable with no diagnostic otherwise. A low
        // level cap can be a legitimate server choice, so this warns rather
        // than refuses or clamps; the operator just needs to know what it
        // costs.
        warnIfGateUnreachable("rerollUnlockLevel", rerollUnlockLevel, "the reroll station");
        warnIfGateUnreachable("gambleUnlockLevel", gambleUnlockLevel, "the gamble station");
        warnIfGateUnreachable("salvageUnlockLevel", salvageUnlockLevel, "the salvage bench");
        warnIfGateUnreachable("cubeUnlockLevel", cubeUnlockLevel, "the Herobrine Cube");
    }

    private static void warnIfGateUnreachable(String key, int gateLevel, String feature) {
        if (gateLevel > keystoneMaxLevel) {
            PocketDungeonsMod.LOG.warn(
                    "pocketdungeons.json {} ({}) is above keystoneMaxLevel ({}); "
                            + "{} can never be reached on this server",
                    key, gateLevel, keystoneMaxLevel, feature);
        }
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
        root.addProperty("kitTopUpBandLow", 1.0);
        root.addProperty("kitTopUpBandMid", 0.5);
        root.addProperty("kitTopUpBandHigh", 0.0);

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
        root.addProperty("lockInEmeralds", 32);
        root.addProperty("lockInUnlockLevel", 5);
        root.addProperty("finishEmeralds", 8);
        root.addProperty("finishVaultChests", 2);
        root.addProperty("overlevelEmeraldsPerScrap", 2);
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

        root.addProperty("storageBlock", "minecraft:ender_chest");
        root.addProperty("gambleEmeraldsPerTier", 6);
        root.addProperty("gambleSlotMultiplier", 1.5);
        root.addProperty("gambleWeightedSlot", "weapon");
        root.addProperty("gambleUnlockLevel", 10);

        root.addProperty("salvageBlock", "minecraft:grindstone");
        root.addProperty("salvageEmeraldsPerTier", 1);
        root.addProperty("salvageKeyEmeralds", 1);
        root.addProperty("salvageOminousKeyEmeralds", 3);
        root.addProperty("salvageUnlockLevel", 1);

        root.addProperty("cubeBlock", "minecraft:beacon");
        root.addProperty("cubeUnlockLevel", 15);
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
