package pocketdungeons;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoublePredicate;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.UnaryOperator;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Config at {@code config/pocketdungeons.json}. Suite's readOrCreate contract: missing
 * -&gt; write defaults; fails to parse -&gt; defaults in memory, file untouched;
 * parses -&gt; use it. See SPEC.md section 10.
 *
 * <p>Every tunable is one {@link Knob} declaration below: its key, default, validator and
 * message are stated there once, and loading, the reset to defaults, the file written on a
 * first boot (in declaration order) and the stored value all walk {@link #KNOBS}. To add a
 * knob, declare it and give it a one line accessor named after its key; {@code ConfigKnobsTest}
 * fails if a knob has no matching accessor.
 *
 * <p>Item ids ({@code keystoneItem}) are kept as strings here
 * and resolved against the registry at their use site. This class carries no
 * Minecraft imports, and config load runs early enough that resolving them here
 * would be betting on registry availability for no benefit.
 */
public final class PocketDungeonsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Every knob, in declaration order. That order is the order the keys are written to a new
     * config file and the order they are read, so a new knob goes where its group sits. Filled
     * by the {@link Knob} constructor, and declared before the knobs so it exists when they are.
     */
    private static final List<Knob<?>> KNOBS = new ArrayList<>();

    // A cell is exactly one chunk only while the slot origin is chunk-aligned,
    // and every force-load and teardown calculation downstream leans on that.
    private static final Knob<Integer> SLOT_PITCH = integer("slotPitch", 2048,
            v -> v > 0 && v % 16 == 0, "must be > 0 and a multiple of 16");
    private static final Knob<Integer> SLOTS_PER_ROW = integer("slotsPerRow", 64, v -> v > 0, "must be > 0");
    private static final Knob<Integer> WATCH_INTERVAL_TICKS = integer("watchIntervalTicks", 20,
            v -> v > 0, "must be > 0");
    private static final Knob<Integer> VOID_GUARD_DEPTH = integer("voidGuardDepth", 10, v -> v >= 0, "must be >= 0");
    private static final Knob<Integer> MAX_PARTY_MEMBERS = integer("maxPartyMembers", 6, v -> v >= 1, "must be >= 1");
    // PD-45: 0 made the window sub-millisecond in practice (both
    // consumers compare an expiry computed from currentTimeMillis() +
    // ttl * 1000L against "now"), which silently disabled party kick
    // confirmations and invites rather than the operator getting a
    // diagnostic.
    private static final Knob<Integer> INVITE_TTL_SECONDS = integer("inviteTtlSeconds", 120,
            v -> v >= 1, "must be >= 1");
    /**
     * How long a run waits for an owner who lost their connection while the
     * rest of the party is still inside, before it ends for everyone. 0 ends
     * it at once, as it did before the grace existed.
     */
    private static final Knob<Integer> OWNER_RECONNECT_GRACE_SECONDS = integer(
            "ownerReconnectGraceSeconds", 120, v -> v >= 0 && v <= 3600, "must be between 0 and 3600");
    // M76: operating envelope. These bound how much concurrent world work the
    // mod can hold at once, so an overload refuses a new request before it
    // charges fuel or a catalyst rather than letting the server thrash. They
    // are measurement points for a declared cap, not promised capacity: a
    // mixed-mod server's real ceiling depends on its own hardware and
    // population, which is exactly why the load test (dungeonLoadTest) is the
    // thing an operator runs to set them.
    private static final Knob<Integer> MAX_CONCURRENT_INSTANCES = integer("maxConcurrentInstances", 32,
            v -> v >= 0, "must be >= 0");
    private static final Knob<Integer> MAX_CONCURRENT_VISITS = integer("maxConcurrentVisits", 16,
            v -> v >= 0, "must be >= 0");
    private static final Knob<Integer> MAX_CONCURRENT_PREVIEWS = integer("maxConcurrentPreviews", 16,
            v -> v >= 0, "must be >= 0");
    /** Record the playtest journal ({@code docs/PLAYTEST_EVENTS.md}). */
    private static final Knob<Boolean> PLAYTEST_JOURNAL = flag("playtestJournal", true);
    /** Seconds Lemon lingers after its last line before it vanishes. */
    private static final Knob<Integer> LEMON_IDLE_SECONDS = integer("lemonIdleSeconds", 20,
            v -> v >= 3 && v <= 600, "must be between 3 and 600");
    /** Seconds Lemon waits for the answer to a question before it vanishes. */
    private static final Knob<Integer> LEMON_ASK_IDLE_SECONDS = integer("lemonAskIdleSeconds", 120,
            v -> v >= 10 && v <= 1800, "must be between 10 and 1800");
    /** Seconds an agent's LLM mode lasts without a refresh before Lemon falls back to guide mode. */
    private static final Knob<Integer> LEMON_LLM_LAPSE_SECONDS = integer("lemonLlmLapseSeconds", 300,
            v -> v >= 30 && v <= 3600, "must be between 30 and 3600");
    /** Seconds a player's question waits for the agent before guide mode answers it. */
    private static final Knob<Integer> LEMON_FALLBACK_SECONDS = integer("lemonFallbackSeconds", 45,
            v -> v >= 5 && v <= 600, "must be between 5 and 600");
    /** Seconds Lemon stays after its last line before vanishing. */
    private static final Knob<Integer> LEMON_LINGER_SECONDS = integer("lemonLingerSeconds", 4,
            v -> v >= 1 && v <= 60, "must be between 1 and 60");
    /** Seconds a {@code dungeon lemon think} holds the guide's fallback answer. */
    private static final Knob<Integer> LEMON_THINK_SECONDS = integer("lemonThinkSeconds", 90,
            v -> v >= 10 && v <= 600, "must be between 10 and 600");
    /** Mob health/damage/speed bonus per point of omen on the current floor, from Act 2 (Act 0 and 1 use omenDangerScaleActOne). */
    private static final Knob<Double> OMEN_DANGER_SCALE_PER_OMEN = decimal("omenDangerScalePerOmen", 0.1,
            v -> v >= 0.0 && v <= 1.0, "must be between 0.0 and 1.0");
    /**
     * Mob health, damage and speed bonus per point of omen on a trip in Act 0 or Act 1 (deaths harden
     * the floor from Act 2; see omenDangerScalePerOmen).
     */
    private static final Knob<Double> OMEN_DANGER_SCALE_ACT_ONE = decimal("omenDangerScaleActOne", 0.0,
            v -> v >= 0.0 && v <= 1.0, "must be 0.0 to 1.0");
    // M54 (spec 6.5): the critical path goes up to 8-12 cells at tier 1, with
    // branch and loop rates high enough that a floor reads as a small maze
    // rather than a spine with stubs. The terminal is placed partway along
    // the path (LayoutGraphGenerator), so the staging room is never the
    // obvious far end.
    private static final Knob<Integer> PATH_LENGTH_MIN = integer("pathLengthMin", 8, v -> v >= 2, "must be >= 2");
    private static final Knob<Integer> PATH_LENGTH_MAX = integer("pathLengthMax", 12, v -> v >= 2, "must be >= 2")
            .after(PocketDungeonsConfig::raisePathLengthMax);
    /**
     * Playtest 2026-10-03-2: the most encounter cells the first floor of an
     * interval may hold, so floor 1 runs about two spawners rather than four
     * ("floor 1 should be about 2 spawners"; 298 s at 05:04). 0 means no cap.
     */
    private static final Knob<Integer> FIRST_FLOOR_MAX_ENCOUNTERS = integer("firstFloorMaxEncounters", 2,
            v -> v >= 0, "must be >= 0 (0 means no cap)");
    private static final Knob<Double> BRANCH_PROBABILITY = decimal("branchProbability", 0.55,
            v -> v >= 0.0 && v <= 1.0, "must be between 0.0 and 1.0");
    private static final Knob<Double> LOOP_PROBABILITY = decimal("loopProbability", 0.30,
            v -> v >= 0.0 && v <= 1.0, "must be between 0.0 and 1.0");
    private static final Knob<Integer> PLAN_ATTEMPT_BUDGET = integer("planAttemptBudget", 32,
            v -> v >= 1, "must be >= 1");
    private static final Knob<Integer> MAX_GRID_SPAN = integer("maxGridSpan", 12, v -> v >= 3, "must be >= 3")
            .after(PocketDungeonsConfig::fitPathLengthsToGrid);
    private static final Knob<Integer> CLEAR_BLOCKS_PER_TICK = integer("clearBlocksPerTick", 8192,
            v -> v >= 1024, "must be >= 1024");
    // The interval's usual length: after this many floors the HOME screen
    // lights up, and it is how many door steps bank one keystone level
    // (IntervalBanking). Nothing forces the party home.
    private static final Knob<Integer> FLOORS_PER_SAFE_VISIT = integer("floorsPerSafeVisit", 3,
            v -> v >= 1, "must be >= 1");
    /**
     * The last act on this server. Acts above it never open, never show on the astrolabe and never
     * deal a door; a save that already has them keeps them stored but unreachable. Finishing the
     * capstone of this act is the end of the campaign (Campaign.done), which opens the Chart Room.
     */
    private static final Knob<Integer> RELEASE_MAX_ACT = integer("releaseMaxAct", 2,
            v -> v >= 1 && v <= 5, "must be 1 to 5");
    /**
     * How many of an act's ordinary dungeons (not the capstone, not an Endless Mine) a player must
     * finish before the act's capstone opens, capped at the number the act has. The act's Endless Mine
     * is an optional dive and never gates.
     */
    private static final Knob<Integer> CAPSTONE_DUNGEONS_NEEDED = integer("capstoneDungeonsNeeded", 3,
            v -> v >= 1 && v <= 6, "must be 1 to 6");
    private static final Knob<Boolean> RITUAL_ENABLED = flag("ritualEnabled", true);
    // U8 Stage 0: chests replace the item grant outright. payoutCommand survives
    // as the one remaining hook an operator has for routing a reward through an
    // economy mod without this mod taking a Java dependency on it.
    private static final Knob<String> PAYOUT_COMMAND = text("payoutCommand", "", true);
    private static final Knob<String> VAULT_KEY_ITEM = text("vaultKeyItem", "minecraft:trial_key", false);
    private static final Knob<String> OMINOUS_VAULT_KEY_ITEM = text("ominousVaultKeyItem",
            "minecraft:ominous_trial_key", false);
    private static final Knob<Integer> TRIAL_SPAWNER_COOLDOWN_TICKS = integer("trialSpawnerCooldownTicks", 36000,
            v -> v >= 0, "must be >= 0");
    // U8 Stage 4: the remote moves off minecraft:trial_key -- that item is what
    // this mod's own spawners eject for its own vaults, and a recovery compass
    // has no such collision with anything else in the suite.
    private static final Knob<String> KEYSTONE_ITEM = text("keystoneItem", "minecraft:recovery_compass", false);
    private static final Knob<Integer> KEYSTONE_MAX_LEVEL = integer("keystoneMaxLevel", 100,
            v -> v >= 1, "must be >= 1");
    private static final Knob<Integer> REWARD_ROOM_GRACE_SECONDS = integer("rewardRoomGraceSeconds", 600,
            v -> v >= 0, "must be >= 0");
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
    private static final Knob<Integer> AFK_SECONDS = integer("afkSeconds", 300, v -> v >= 0, "must be >= 0");
    /**
     * What {@code /dungeon quit} on a floor in progress costs the owner's
     * keystone. Named for the clock that used to charge it; the name is kept
     * so existing config files still apply.
     */
    private static final Knob<Integer> TIMED_OUT_DEPLETION = integer("timedOutDepletion", 1,
            v -> v >= 0, "must be >= 0");
    /** Overclocked scales {@link #trialSpawnerCooldownTicks()} down by this. */
    private static final Knob<Double> OVERCLOCKED_COOLDOWN_FACTOR = decimal("overclockedCooldownFactor", 0.4,
            v -> v > 0 && v <= 1, "must be between 0 (exclusive) and 1");
    /** Overclocked: a floor cleared within this many seconds of its commit pays {@link #overclockedBonus()} more. */
    private static final Knob<Integer> OVERCLOCKED_PAR_SECONDS = integer("overclockedParSeconds", 360,
            v -> v >= 1, "must be >= 1");
    /** Overclocked: the scrap added to a floor's pay when it is cleared under the par time. */
    private static final Knob<Integer> OVERCLOCKED_BONUS = integer("overclockedBonus", 1,
            v -> v >= 0, "must be >= 0");
    /** Swarming scales a tier's total/simultaneous mob counts up by this. */
    private static final Knob<Double> SWARMING_MOB_FACTOR = decimal("swarmingMobFactor", 1.5,
            v -> v >= 1, "must be >= 1");
    /** Share of generic (themeless) spawners that keep the broad mixed mob pool (PD-107). */
    private static final Knob<Double> CHAOTIC_SPAWNER_CHANCE = decimal("chaoticSpawnerChance", 0.05,
            v -> v >= 0 && v <= 1, "must be between 0 and 1");
    /** Silenced's trial spawners detect players at this range instead of 14. */
    private static final Knob<Integer> SILENCED_PLAYER_RANGE = integer("silencedPlayerRange", 6,
            v -> v >= 1, "must be >= 1");
    /** Silenced: this many consumables used raise the omen by one. */
    private static final Knob<Integer> SILENCED_CONSUMABLES_PER_OMEN = integer("silencedConsumablesPerOmen", 3,
            v -> v >= 1, "must be >= 1");
    /** Chance a dead-end cell holds a fountain boon. */
    private static final Knob<Double> FOUNTAIN_CHANCE = decimal("fountainChance", 0.15,
            v -> v >= 0 && v <= 1, "must be between 0 and 1");
    /** Omen a cleansing fountain takes off the floor. */
    /** Emeralds each member is paid when a dungeon is finished (J1: was the echo shard). */
    private static final Knob<Integer> FINISH_EMERALDS = integer("finishEmeralds", 8, v -> v >= 0, "must be >= 0");
    /** Extra completion chests, at the dungeon's top loot tier, a finished dungeon's themed vault adds (design D11). */
    private static final Knob<Integer> FINISH_VAULT_CHESTS = integer("finishVaultChests", 2,
            v -> v >= 0 && v <= 6, "must be between 0 and 6");
    /** The share of a haul a failed dungeon still banks, in percent (Haul and Blood Doors). */
    private static final Knob<Integer> FAIL_HAUL_KEEP_PERCENT = integer("failHaulKeepPercent",
            ScrapMath.FAIL_KEEP_PERCENT, v -> v >= 0 && v <= 100, "must be between 0 and 100");
    /** Omen a capstone dungeon's final floor starts with (design D16a), 0 to 4; 0 turns the head start off. */
    /** Chance, per player in the room, of 2 emeralds when an Ordeal resolves (J1). */
    private static final Knob<Double> ORDEAL_EMERALD_CHANCE = decimal("ordealEmeraldChance", 0.25,
            v -> v >= 0 && v <= 1, "must be between 0 and 1");
    /** the scale the woken Specimen 0 grows to, as a multiplier of a cow */
    private static final Knob<Double> COW_SPECIMEN_ZERO_SCALE = decimal("cowSpecimenZeroScale", 3.0,
            v -> v >= 0.5 && v <= 8.0, "must be 0.5 to 8.0");
    /** the health the woken Specimen 0 is given, before the party bonus */
    private static final Knob<Integer> COW_SPECIMEN_ZERO_HEALTH = integer("cowSpecimenZeroHealth", 60,
            v -> v >= 1, "must be >= 1");
    /** Molten hazard blocks placed per cell. */
    private static final Knob<Integer> MOLTEN_HAZARDS_PER_CELL = integer("moltenHazardsPerCell", 4,
            v -> v >= 0, "must be >= 0");
    /** Feral wolves spawned per non-encounter cell. Zero disables the affix's spawns. */
    private static final Knob<Integer> FERAL_WOLVES_PER_CELL = integer("feralWolvesPerCell", 2,
            v -> v >= 0, "must be >= 0");
    /** Explosive fake TNT mine blocks placed per cell. */
    private static final Knob<Integer> EXPLOSIVE_HAZARDS_PER_CELL = integer("explosiveHazardsPerCell", 4,
            v -> v >= 0, "must be >= 0");
    /** Fraction of eligible cells that get voided floor when VOIDED affix active. */
    private static final Knob<Double> VOIDED_CELL_CHANCE = decimal("voidedCellChance", 0.3,
            v -> v >= 0.0 && v <= 1.0, "must be between 0.0 and 1.0");
    /** +2% mob strength (max health, attack damage, movement speed) per keystone level. */
    private static final Knob<Double> MOB_SCALE_PER_LEVEL = decimal("mobScalePerLevel", 0.02,
            v -> v >= 0, "must be >= 0");
    /** Base mob strength multiplier at level 0. Below 1.0 makes low-level mobs weaker than vanilla. */
    private static final Knob<Double> MOB_SCALE_BASE = decimal("mobScaleBase", 0.85,
            v -> v > 0.0 && v <= 2.0, "must be between 0.0 and 2.0");
    /**
     * Additional HP-only multiplier applied to Breezes after the level scaling,
     * because their high mobility makes them disproportionately tedious to kill
     * even at low levels. 0.5 halves their post-scaling HP; 1.0 disables the
     * override entirely.
     */
    private static final Knob<Double> BREEZE_HP_MULTIPLIER = decimal("breezeHpMultiplier", 0.5,
            v -> v > 0.0 && v <= 2.0, "must be between 0.0 and 2.0");
    /**
     * Fraction of a run's trial spawners that must reach {@code COOLDOWN} before
     * a pad contact completes the run. An untouched spawner sits at
     * {@code INACTIVE}, not {@code COOLDOWN}, and counts against this same
     * denominator: sprinting past every spawner is exactly what this gate
     * exists to refuse.
     */
    private static final Knob<Double> SPAWNER_CLEAR_THRESHOLD = decimal("spawnerClearThreshold", 0.75,
            v -> v > 0.0 && v <= 1.0, "must be between 0.0 (exclusive) and 1.0");
    /** How long a Pocket2 child stays open once entered, in seconds. */
    private static final Knob<Integer> POCKET2_TIMER_SECONDS = integer("pocket2TimerSeconds", 60,
            v -> v >= 1, "must be >= 1");
    /** Chance per run that a rare door to a Pocket2 appears in a cleared encounter room. */
    private static final Knob<Double> POCKET2_DOOR_CHANCE = decimal("pocket2DoorChance", 0.2,
            v -> v >= 0.0 && v <= 1.0, "must be between 0.0 and 1.0");
    /** Chance per run that one critical-path cell is swapped for an anomaly room. */
    private static final Knob<Double> ANOMALY_ROOM_CHANCE = decimal("anomalyRoomChance", 0.08,
            v -> v >= 0.0 && v <= 1.0, "must be between 0.0 and 1.0");
    /** The block a reroll station is; right-clicking it with tiered gear opens the picker. */
    private static final Knob<String> REROLL_BLOCK = text("rerollBlock", "minecraft:enchanting_table", false)
            // J5 moved the reroll to the enchanting table and retired the smithing
            // table as a station; a saved config that still names it is remapped
            // rather than resurrecting the old station.
            .normalising(v -> {
                if ("minecraft:smithing_table".equals(v)) {
                    PocketDungeonsMod.LOG.warn("rerollBlock was the smithing table, which is not a station "
                            + "any more; using minecraft:enchanting_table for rerolls instead.");
                    return "minecraft:enchanting_table";
                }
                return v;
            });
    /** Lapis cost per tier step; a tier-N reroll costs this times N. */
    private static final Knob<Integer> REROLL_LAPIS_PER_TIER = integer("rerollLapisPerTier", 4,
            v -> v >= 0, "must be >= 0");
    /**
     * Material decides the bonus; pattern stays cosmetic. Ten materials, tuned
     * once here, rather than authoring a cell per material/pattern combination.
     * {@code attribute} is a registry id resolved against {@code BuiltInRegistries
     * .ATTRIBUTE} at the use site; {@code operation} is an
     * {@code AttributeModifier.Operation}'s serialized name
     * ({@code add_value}, {@code add_multiplied_base}, {@code add_multiplied_total}).
     */
    private static final Knob<List<TrimBonusEntry>> TRIM_BONUSES = new Knob<>("trimBonuses", defaultTrimBonuses(),
            PocketDungeonsConfig::readTrimBonuses, PocketDungeonsConfig::trimBonusesJson);
    /**
     * If true, a worn trim's bonus applies only while the wearer is in the
     * dungeon dimension; if false, it applies everywhere the armour is worn.
     * {@code VISION.md} section 3.7 permits the global reading by its letter
     * (the template and material are both dungeon-loot-gated), so the
     * milestone default is global; an operator who wants the narrower
     * reading can flip this.
     */
    private static final Knob<Boolean> TRIM_BONUS_DUNGEON_ONLY = flag("trimBonusDungeonOnly", false);
    /**
     * The block that opens run storage (see {@link RunStorage}). It used to open
     * the gamble station, a duplicate of the blacksmith, which was removed
     * (playtest 2026-10-02-1); the gamble itself went with the home vendor
     * rework (J5a).
     */
    private static final Knob<String> STORAGE_BLOCK = text("storageBlock", "minecraft:ender_chest", false)
            // A build between the gamble block's removal and the 2026-10-02 swap wrote
            // the copper chest here. That block is the bag chest now, so the value would
            // make every staging room's storage a second bag chest.
            .normalising(v -> {
                if ("minecraft:waxed_oxidized_copper_chest".equals(v)) {
                    PocketDungeonsMod.LOG.warn("storageBlock was the waxed oxidized copper chest, which is the bag "
                            + "chest now; using minecraft:ender_chest for run storage instead.");
                    return "minecraft:ender_chest";
                }
                return v;
            });
    /**
     * The block the salvage bench is. Claimed only while the player holds
     * something salvageable, so an empty hand still opens the vanilla
     * grindstone.
     */
    private static final Knob<String> SALVAGE_BLOCK = text("salvageBlock", "minecraft:grindstone", false);
    /** Emeralds per vault key: below the 1.4 a spawner's emerald eject is worth. */
    private static final Knob<Integer> SALVAGE_KEY_EMERALDS = integer("salvageKeyEmeralds", 1,
            v -> v >= 0, "must be >= 0");
    /** Emeralds per ominous vault key. */
    private static final Knob<Integer> SALVAGE_OMINOUS_KEY_EMERALDS = integer("salvageOminousKeyEmeralds", 3,
            v -> v >= 0, "must be >= 0");
    /** Emeralds a duplicate shell token pays on right-click, once the palette is already unlocked. */
    private static final Knob<Integer> SHELL_DUPE_EMERALDS = integer("shellDupeEmeralds", 2,
            v -> v >= 0 && v <= 64, "must be 0 to 64");
    /**
     * Tuning knob (2026-10-08): extra pieces of material every salvaged item pays in every
     * wear band, so what paid 0 pays 1, 1 pays 2, 2 pays 3. 0 restores the old table.
     */
    private static final Knob<Integer> SALVAGE_MATERIAL_BONUS = integer("salvageMaterialBonus", 1,
            v -> v >= 0 && v <= 20, "must be 0 to 20");
    /**
     * Percent of the dungeon durability cap ({@link DungeonTools#durabilityCap}) that gear from
     * the mod's chests, vaults and gear tables gets: 110 is 10 percent more. 100 is the cap table as
     * written.
     */
    private static final Knob<Integer> LOOT_DURABILITY_PERCENT = integer("lootDurabilityPercent", 110,
            v -> v >= 10 && v <= 1000, "must be 10 to 1000");
    /**
     * Percent of the same cap table for crafted gear and everything else (owner, 2026-10-08: all gear gets
     * 10 percent more); 100 is the table as written.
     */
    private static final Knob<Integer> CRAFTED_DURABILITY_PERCENT = integer("craftedDurabilityPercent", 110,
            v -> v >= 10 && v <= 1000, "must be 10 to 1000");
    /**
     * The longest any poison lasts on a player inside a dungeon, in seconds (PD-178, owner ruling
     * 2026-10-08: "10 seconds at the most"). 0 turns the cap off.
     */
    private static final Knob<Integer> POISON_MAX_SECONDS = integer("poisonMaxSeconds", 10,
            v -> v >= 0 && v <= 600, "must be 0 (off) to 600");
    /**
     * Longest wither, slowness and mining fatigue last on a player inside a dungeon, in seconds (design pass
     * 2026-10-09, Q10e: the same "hide for 30 seconds" complaint as poison). 0 turns a cap off.
     */
    private static final Knob<Integer> WITHER_MAX_SECONDS = integer("witherMaxSeconds", 8,
            v -> v >= 0 && v <= 600, "must be 0 (off) to 600");
    /** The trip sidebar (Q7): shown at all, and the ticks between repaints. */
    private static final Knob<Boolean> SIDEBAR_ENABLED = flag("sidebarEnabled", true);
    /**
     * The scrap curve (design pass 2026-10-09, Q2). Going from compass {@code c} to {@code c + 1} costs
     * {@code scrapCostBase + floor(c / scrapCostEvery)} scrap. A cleared floor pays its dealt step plus
     * {@code scrapPerAct} for every act above the first plus {@code scrapFinalBonus} on a dungeon's final
     * floor; below the player's compass it pays {@code belowCompassPercent} of that, at least 1. An Endless
     * Mine floor adds 1 for every {@code endlessDepthEvery} floors down, up to {@code endlessDepthMax}, in
     * place of the act bonus.
     */
    private static final Knob<Integer> SCRAP_COST_BASE = integer("scrapCostBase", 4,
            v -> v >= 2 && v <= 10, "must be 2 to 10");
    private static final Knob<Integer> SCRAP_COST_EVERY = integer("scrapCostEvery", 3,
            v -> v >= 1 && v <= 10, "must be 1 to 10");
    private static final Knob<Integer> SCRAP_PER_ACT = integer("scrapPerAct", 1,
            v -> v >= 0 && v <= 3, "must be 0 to 3");
    private static final Knob<Integer> SCRAP_FINAL_BONUS = integer("scrapFinalBonus", 1,
            v -> v >= 0 && v <= 5, "must be 0 to 5");
    private static final Knob<Integer> BELOW_COMPASS_PERCENT = integer("belowCompassPercent", 50,
            v -> v >= 0 && v <= 100, "must be 0 to 100");
    private static final Knob<Integer> ENDLESS_DEPTH_EVERY = integer("endlessDepthEvery", 4,
            v -> v >= 1 && v <= 10, "must be 1 to 10");
    private static final Knob<Integer> ENDLESS_DEPTH_MAX = integer("endlessDepthMax", 4,
            v -> v >= 0 && v <= 10, "must be 0 to 10");
    /**
     * The Endless Mine's layer guardians (decision 2026-10-11, task T40). A guardian waits on every
     * {@code endlessGuardianEvery}-th floor of a mine (3, 6, 9 and so on); that floor is the checkpoint that pays,
     * and reaching the first one counts toward opening the next act. Guardian number n runs at hardness
     * {@code 1 + endlessGuardianHardnessStep * (n - 1) + endlessGuardianLapStep * lap}, where a lap is a full run
     * of three guardians (so lap 1 starts at guardian 4). Hardness has no cap.
     */
    private static final Knob<Integer> ENDLESS_GUARDIAN_EVERY = integer("endlessGuardianEvery", 3,
            v -> v >= 1 && v <= 10, "must be 1 to 10");
    private static final Knob<Double> ENDLESS_GUARDIAN_HARDNESS_STEP = decimal("endlessGuardianHardnessStep", 0.25,
            v -> v >= 0.0 && v <= 5.0, "must be 0 to 5");
    private static final Knob<Double> ENDLESS_GUARDIAN_LAP_STEP = decimal("endlessGuardianLapStep", 0.5,
            v -> v >= 0.0 && v <= 5.0, "must be 0 to 5");
    /**
     * The most mobs one Endless Mine guardian may have standing or waiting at once: the queen plus her
     * bricks, the packmaster plus his wolves, or the coffins' sleepers. Counts that hardness would
     * push past it are cut back.
     */
    private static final Knob<Integer> MINE_GUARDIAN_MOB_CAP = integer("mineGuardianMobCap", 16,
            v -> v >= 1, "must be >= 1");
    /**
     * The Proving's wave ladder (decision 2026-10-16): floor n sends
     * {@code provingWaveBaseSize + provingWaveSizePerFloor * (n - 1) + provingWaveSizePerMember * (members - 1)}
     * mobs, capped at {@code provingWaveMaxSize}, and every {@code provingChampionEvery}-th
     * floor sends a champion scaled half again above the wave.
     */
    private static final Knob<Integer> PROVING_WAVE_BASE_SIZE = integer("provingWaveBaseSize", 5,
            v -> v >= 1 && v <= 32, "must be 1 to 32");
    private static final Knob<Integer> PROVING_WAVE_SIZE_PER_FLOOR = integer("provingWaveSizePerFloor", 2,
            v -> v >= 0 && v <= 8, "must be 0 to 8");
    private static final Knob<Integer> PROVING_WAVE_SIZE_PER_MEMBER = integer("provingWaveSizePerMember", 2,
            v -> v >= 0 && v <= 8, "must be 0 to 8");
    private static final Knob<Integer> PROVING_WAVE_MAX_SIZE = integer("provingWaveMaxSize", 20,
            v -> v >= 1 && v <= 40, "must be 1 to 40");
    private static final Knob<Integer> PROVING_CHAMPION_EVERY = integer("provingChampionEvery", 3,
            v -> v >= 1 && v <= 10, "must be 1 to 10");
    /**
     * At most this many Brood Chamber mobs stand at once: the wave, the mother's retinue, coffin
     * sleepers, silverfish and dark spiders all count. New arrivals wait for room.
     */
    private static final Knob<Integer> BROOD_MOB_CAP = integer("broodMobCap", 24, v -> v >= 1, "must be >= 1");
    /**
     * Sculk hears you (design pass 2026-10-09, Q3). Each sensor pulse in a room fills its Heard meter; at
     * sculkHeardMax (the Ancient City: sculkHeardMaxAncient) the room answers with darkness and a wave and the
     * meter resets. A room with a spawner that is cleared without it ever answering pays sculkUnheardScrap into
     * the haul. The ancientWardenAnswers-th answer on the Ancient City's final floor wakes the Warden.
     */
    private static final Knob<Integer> SCULK_HEARD_MAX = integer("sculkHeardMax", 4,
            v -> v >= 1 && v <= 10, "must be 1 to 10");
    private static final Knob<Integer> SCULK_HEARD_MAX_ANCIENT = integer("sculkHeardMaxAncient", 2,
            v -> v >= 1 && v <= 10, "must be 1 to 10");
    private static final Knob<Integer> SCULK_UNHEARD_SCRAP = integer("sculkUnheardScrap", 1,
            v -> v >= 0 && v <= 3, "must be 0 to 3");
    /**
     * The Silent Deep's Hush rule (roadmap T31): the Heard meter's ceiling for the finale. A full
     * meter answers with darkness and a Warden whelp, never the Warden. Meter units: a sensor pulse is
     * hushPulseRise.
     */
    private static final Knob<Integer> HUSH_CEILING = integer("hushCeiling", 16,
            v -> v >= 4 && v <= 60, "must be 4 to 60");
    /**
     * Hush rule: meter units one sculk sensor pulse adds during the Silent Deep finale (4 pulses fill
     * the default ceiling).
     */
    private static final Knob<Integer> HUSH_PULSE_RISE = integer("hushPulseRise", 4,
            v -> v >= 0 && v <= 20, "must be 0 to 20");
    /** Hush rule: meter units one bow or crossbow shot by a member adds during the Silent Deep finale. */
    private static final Knob<Integer> HUSH_BOW_RISE = integer("hushBowRise", 2,
            v -> v >= 0 && v <= 20, "must be 0 to 20");
    /** Hush rule: meter units one hit a member takes adds during the Silent Deep finale. */
    private static final Knob<Integer> HUSH_HIT_RISE = integer("hushHitRise", 2,
            v -> v >= 0 && v <= 20, "must be 0 to 20");
    /** Hush rule: meter units each death of a finale mob adds during the Silent Deep finale. */
    private static final Knob<Integer> HUSH_DEATH_RISE = integer("hushDeathRise", 1,
            v -> v >= 0 && v <= 20, "must be 0 to 20");
    /** Hush rule: meter units added for each second a member sprints in the Silent Deep during the finale. */
    private static final Knob<Integer> HUSH_SPRINT_RISE = integer("hushSprintRise", 1,
            v -> v >= 0 && v <= 20, "must be 0 to 20");
    /**
     * Hush rule: seconds of quiet fighting that add one meter unit during the Silent Deep finale (0
     * turns the slow rise off).
     */
    private static final Knob<Integer> HUSH_QUIET_SECONDS = integer("hushQuietSeconds", 15,
            v -> v >= 0 && v <= 120, "must be 0 to 120");
    /** Hush rule: Warden whelps a full meter sends in the Silent Deep finale. Never the real Warden. */
    private static final Knob<Integer> HUSH_WHELPS = integer("hushWhelps", 1, v -> v >= 1 && v <= 4, "must be 1 to 4");
    /**
     * The Warden whelp a sculk answer sends (design pass 2026-10-09): a small melee-only Warden that always
     * knows where the party is, one at a time, gone after whelpSeconds. Health, melee damage and speed are
     * its own; the speed is a movement attribute (a player walks at 0.1, a zombie 0.23, a Warden 0.3).
     */
    private static final Knob<Double> WHELP_HEALTH = decimal("whelpHealth", 20.0,
            v -> v >= 1.0 && v <= 200.0, "must be 1 to 200");
    private static final Knob<Double> WHELP_DAMAGE = decimal("whelpDamage", 4.0,
            v -> v >= 0.0 && v <= 30.0, "must be 0 to 30");
    private static final Knob<Double> WHELP_SPEED = decimal("whelpSpeed", 0.3,
            v -> v >= 0.1 && v <= 0.6, "must be 0.1 to 0.6");
    private static final Knob<Integer> WHELP_SECONDS = integer("whelpSeconds", 15,
            v -> v >= 3 && v <= 120, "must be 3 to 120");
    private static final Knob<Integer> ANCIENT_WARDEN_ANSWERS = integer("ancientWardenAnswers", 2,
            v -> v >= 1 && v <= 5, "must be 1 to 5");
    /** The most tamed wolves one player keeps standing with them in a dungeon; the extras sit (design pass 2026-10-09, Q8). */
    private static final Knob<Integer> PET_CAP = integer("petCap", 3, v -> v >= 0 && v <= 8, "must be 0 to 8");
    /**
     * The finale (design pass 2026-10-09, Q4): whether a dungeon\u0027s final floor ends in a wave, the extra
     * reward chests it pays, the seconds between the title and the wave, and the percent an elite\u0027s health
     * grows for each member past the first. {@code mobUniformEnabled} turns the per-dungeon mob uniform on.
     */
    private static final Knob<Boolean> FINALE_ENABLED = flag("finaleEnabled", true);
    private static final Knob<Integer> FINALE_REWARD_CHESTS = integer("finaleRewardChests", 1,
            v -> v >= 0 && v <= 3, "must be 0 to 3");
    /**
     * A floor whose affixes wager something pays one extra piece of gear into its copper chest
     * (WagerGear); false pays none.
     */
    private static final Knob<Boolean> WAGER_GEAR_ENABLED = flag("wagerGearEnabled", true);
    /**
     * Percent chance, per wager point, that the extra copper chest piece is one tier higher than the
     * floor base tier (never above the dungeon loot band).
     */
    private static final Knob<Integer> WAGER_TIER_CHANCE_PER_POINT = integer("wagerTierChancePerPoint", 20,
            v -> v >= 0 && v <= 100, "must be 0 to 100");
    /** Most the wager tier chance reaches, in percent. */
    private static final Knob<Integer> WAGER_TIER_CHANCE_CAP = integer("wagerTierChanceCap", 90,
            v -> v >= 0 && v <= 100, "must be 0 to 100");
    /** The Loot percent the door board shows and the barrel pays: 100 plus this per wager point. */
    private static final Knob<Integer> LOOT_PERCENT_PER_WAGER = integer("lootPercentPerWager", 50,
            v -> v >= 0 && v <= 200, "must be 0 to 200");
    private static final Knob<Integer> FINALE_COUNTDOWN_SECONDS = integer("finaleCountdownSeconds", 3,
            v -> v >= 0 && v <= 10, "must be 0 to 10");
    /** Minutes the Copper Works furnace counts down from when the trip enters; at zero the furnace blows */
    private static final Knob<Integer> COPPER_CLOCK_MINUTES = integer("copperClockMinutes", 20,
            v -> v >= 1 && v <= 120, "must be 1 to 120");
    /** Seconds each cleared trial spawner buys back on the Copper Works furnace clock */
    private static final Knob<Integer> COPPER_CLOCK_BONUS_SECONDS = integer("copperClockBonusSeconds", 60,
            v -> v >= 0 && v <= 600, "must be 0 to 600");
    /** Seconds between vent pulses in the Master Furnace finale */
    private static final Knob<Integer> COPPER_VENT_PULSE_SECONDS = integer("copperVentPulseSeconds", 8,
            v -> v >= 3 && v <= 30, "must be 3 to 30");
    /** Percent of the normal vent cycle once the furnace clock blew; lower means faster pulses */
    private static final Knob<Integer> COPPER_VENT_BLOWN_PERCENT = integer("copperVentBlownPercent", 50,
            v -> v >= 10 && v <= 100, "must be 10 to 100");
    /** Seconds a member caught on a vent tile burns for */
    private static final Knob<Integer> COPPER_VENT_BURN_SECONDS = integer("copperVentBurnSeconds", 3,
            v -> v >= 0 && v <= 10, "must be 0 to 10");
    /** Damage a second the Foreman takes while he stands in a water vat */
    private static final Knob<Double> COPPER_FOREMAN_VAT_DAMAGE = decimal("copperForemanVatDamage", 1.0,
            v -> v >= 0 && v <= 20, "must be 0 to 20");
    /** Multiplier on the vat damage once the Foreman is cornered in his last phase */
    private static final Knob<Double> COPPER_FOREMAN_VAT_CORNERED = decimal("copperForemanVatCorneredMultiplier",
            2.0, v -> v >= 1 && v <= 10, "must be 1 to 10");
    /** Percent of the normal vent cycle once the Foreman is cornered; lower means faster pulses */
    private static final Knob<Integer> COPPER_VENT_CORNERED_PERCENT = integer("copperVentCorneredPercent", 60,
            v -> v >= 10 && v <= 100, "must be 10 to 100");
    /** Seconds a phased boss staggers (invulnerable, mindless) while a phase changes over */
    private static final Knob<Integer> BOSS_PHASE_STAGGER_SECONDS = integer("bossPhaseStaggerSeconds", 3,
            v -> v >= 1 && v <= 10, "must be 1 to 10");
    /** Seconds of Weakness and Slowness the Foreman carries while in a water vat */
    private static final Knob<Integer> COPPER_FOREMAN_VAT_WEAK_SECONDS = integer("copperForemanVatWeakSeconds", 4,
            v -> v >= 0 && v <= 30, "must be 0 to 30");
    /**
     * The Big Freeze (Frostworks finale): freeze ticks a member gains each second while in the cold
     * room and away from a lit brazier. A full freeze is 140 ticks, so the default takes about 23
     * seconds. 0 turns the cold off.
     */
    private static final Knob<Integer> FROST_COLD_TICKS_PER_SECOND = integer("frostColdTicksPerSecond", 6,
            v -> v >= 0 && v <= 40, "must be 0 to 40");
    /**
     * The Big Freeze (Frostworks finale): blocks from a lit brazier within which a member thaws.
     * Measured from the brazier block to the member's feet.
     */
    private static final Knob<Integer> FROST_BRAZIER_RADIUS = integer("frostBrazierRadius", 4,
            v -> v >= 2 && v <= 10, "must be 2 to 10");
    /**
     * Creeping bricks (Infestation): seconds a brick stays mossy stone brick before it matures to
     * stone brick.
     */
    private static final Knob<Integer> CREEPING_MOSSY_SECONDS = integer("creepingMossySeconds", 5,
            v -> v >= 1 && v <= 60, "must be 1 to 60");
    /** Creeping bricks: seconds a brick stays stone brick before it turns infested. */
    private static final Knob<Integer> CREEPING_STONE_SECONDS = integer("creepingStoneSeconds", 5,
            v -> v >= 1 && v <= 60, "must be 1 to 60");
    /**
     * Creeping bricks: seconds a brick stays infested stone brick (shaking, loud) before it bursts
     * into a silverfish.
     */
    private static final Knob<Integer> CREEPING_INFESTED_SECONDS = integer("creepingInfestedSeconds", 3,
            v -> v >= 1 && v <= 60, "must be 1 to 60");
    /** Creeping bricks: the most bricks one room may have growing at once. */
    private static final Knob<Integer> CREEPING_ROOM_CAP = integer("creepingRoomCap", 4,
            v -> v >= 1 && v <= 32, "must be 1 to 32");
    /**
     * Creeping bricks: the most bricks growing at once on one floor, all rooms and the boss fight
     * together.
     */
    private static final Knob<Integer> CREEPING_FLOOR_CAP = integer("creepingFloorCap", 10,
            v -> v >= 1 && v <= 64, "must be 1 to 64");
    /**
     * Extra lives a trip gains for each party member beyond the first, counted at the first door
     * commit and capped at four members (solo 5, two 6, four 8). Unit: lives.
     */
    private static final Knob<Integer> LIVES_PER_EXTRA_MEMBER = integer("livesPerExtraMember", 1,
            v -> v >= 0 && v <= 5, "must be 0 to 5");
    /**
     * Creeping bricks: how many seed spots a Chewed Corridors or Silverfish Nests room marks at stamp
     * time; each starts a brick when a member enters.
     */
    private static final Knob<Integer> CREEPING_ROOM_SEEDS = integer("creepingRoomSeeds", 3,
            v -> v >= 0 && v <= 8, "must be 0 to 8");
    /** Infestation finale: the Attributes.SCALE of the giant silverfish boss (1.0 is a normal silverfish). */
    private static final Knob<Double> INFESTATION_BOSS_SCALE = decimal("infestationBossScale", 6.0,
            v -> v >= 1.0 && v <= 12.0, "must be 1.0 to 12.0");
    /** Infestation finale: seconds the boss fights on the surface before it begins to burrow. */
    private static final Knob<Integer> INFESTATION_SURFACE_SECONDS = integer("infestationSurfaceSeconds", 25,
            v -> v >= 5 && v <= 120, "must be 5 to 120");
    /** Infestation finale: seconds of warning (rumble and dust) before the boss burrows; never under 2. */
    private static final Knob<Integer> INFESTATION_BURROW_TELL_SECONDS = integer("infestationBurrowTellSeconds", 3,
            v -> v >= 2 && v <= 10, "must be 2 to 10");
    /**
     * Infestation finale: seconds of warning (the floor cracks) between the burrow and the first
     * bricks; never under 2.
     */
    private static final Knob<Integer> INFESTATION_ADDS_TELL_SECONDS = integer("infestationAddsTellSeconds", 2,
            v -> v >= 2 && v <= 10, "must be 2 to 10");
    /**
     * Infestation finale: the longest the adds phase lasts before the boss emerges whatever bricks
     * remain.
     */
    private static final Knob<Integer> INFESTATION_ADDS_MAX_SECONDS = integer("infestationAddsMaxSeconds", 40,
            v -> v >= 10 && v <= 180, "must be 10 to 180");
    /** Infestation finale: seconds of warning (the ground heaves) before the boss emerges; never under 2. */
    private static final Knob<Integer> INFESTATION_EMERGE_TELL_SECONDS = integer("infestationEmergeTellSeconds", 2,
            v -> v >= 2 && v <= 10, "must be 2 to 10");
    /** Infestation finale: bricks one adds phase plants for a party of one. */
    private static final Knob<Integer> INFESTATION_BRICKS_PER_PHASE = integer("infestationBricksPerPhase", 6,
            v -> v >= 1 && v <= 30, "must be 1 to 30");
    /** Infestation finale: extra bricks one adds phase plants for each member past the first. */
    private static final Knob<Integer> INFESTATION_BRICKS_PER_EXTRA_MEMBER = integer("infestationBricksPerExtraMember", 2,
            v -> v >= 0 && v <= 10, "must be 0 to 10");
    /** Infestation finale: the most bricks growing at once in the arena. */
    private static final Knob<Integer> INFESTATION_ARENA_BRICK_CAP = integer("infestationArenaBrickCap", 6,
            v -> v >= 1 && v <= 32, "must be 1 to 32");
    /**
     * Infestation finale: the most silverfish (not the boss) alive at once; a ripe brick waits while
     * the cap is full.
     */
    private static final Knob<Integer> INFESTATION_SILVERFISH_CAP = integer("infestationSilverfishCap", 10,
            v -> v >= 1 && v <= 40, "must be 1 to 40");
    /** Infestation finale: seconds between one brick planted and the next during the adds phase. */
    private static final Knob<Integer> INFESTATION_PLANT_SECONDS = integer("infestationPlantSeconds", 2,
            v -> v >= 1 && v <= 20, "must be 1 to 20");
    /**
     * Infestation finale: the giant silverfish boss's health for a party of one (more per extra member
     * by finaleEliteHealthPercentPerMember).
     */
    private static final Knob<Integer> INFESTATION_BOSS_HEALTH = integer("infestationBossHealth", 100,
            v -> v >= 10 && v <= 1000, "must be 10 to 1000");
    /** Infestation finale: the giant silverfish boss's attack damage (a plain silverfish hits for 1). */
    private static final Knob<Double> INFESTATION_BOSS_DAMAGE = decimal("infestationBossDamage", 4.0,
            v -> v >= 0.0 && v <= 30.0, "must be 0.0 to 30.0");
    private static final Knob<Integer> FINALE_ELITE_HEALTH_PERCENT_PER_MEMBER = integer(
            "finaleEliteHealthPercentPerMember", 50, v -> v >= 0 && v <= 100, "must be 0 to 100");
    /**
     * The Mineshaft finale dark phase (roadmap T20): the cave spiders that join for a party of one (grown per
     * member like the data wave, bounded by {@link FinaleRules#MAX_DARK_SPIDERS}), and the seconds between
     * the warning and the lights going out (never under {@link FinaleRules#MIN_DARK_TELL_SECONDS}).
     */
    private static final Knob<Integer> MINESHAFT_DARK_SPIDERS = integer("mineshaftDarkSpiders", 3,
            v -> v >= 0 && v <= 10, "must be 0 to 10");
    private static final Knob<Integer> MINESHAFT_DARK_TELL_SECONDS = integer("mineshaftDarkTellSeconds", 3,
            v -> v >= 2 && v <= 10, "must be 2 to 10");
    private static final Knob<Boolean> MOB_UNIFORM_ENABLED = flag("mobUniformEnabled", true);
    /**
     * The Lush Caves potion drip (roadmap T23): splash potions fall on party members from the start floor on.
     * Base seconds between two brews for one player, the percent each brew varies either way, the first
     * floor of the run that drips, the percent of the base rate added per later floor, the percent the rate is
     * multiplied by during the boss fight, the percent of brews that are harmful, the floor level from which a
     * harming brew may fall (0 never), the seconds a brew's effect lasts (before its own scaling), the ticks a
     * brew takes to fall, and the least seconds between two brews however fast the rate.
     */
    private static final Knob<Boolean> POTION_DRIP_ENABLED = flag("potionDripEnabled", true);
    private static final Knob<Integer> POTION_DRIP_INTERVAL_SECONDS = integer("potionDripIntervalSeconds", 45,
            v -> v >= 5 && v <= 600, "must be 5 to 600");
    private static final Knob<Integer> POTION_DRIP_JITTER_PERCENT = integer("potionDripJitterPercent", 30,
            v -> v >= 0 && v <= 90, "must be 0 to 90");
    private static final Knob<Integer> POTION_DRIP_START_FLOOR = integer("potionDripStartFloor", 3,
            v -> v >= 1 && v <= 12, "must be 1 to 12");
    private static final Knob<Integer> POTION_DRIP_RAMP_PERCENT = integer("potionDripRampPercent", 50,
            v -> v >= 0 && v <= 500, "must be 0 to 500");
    private static final Knob<Integer> POTION_DRIP_BOSS_PERCENT = integer("potionDripBossPercent", 200,
            v -> v >= 100 && v <= 1000, "must be 100 to 1000");
    private static final Knob<Integer> POTION_DRIP_HARMFUL_PERCENT = integer("potionDripHarmfulPercent", 70,
            v -> v >= 0 && v <= 100, "must be 0 to 100");
    private static final Knob<Integer> POTION_DRIP_HARMING_MIN_LEVEL = integer("potionDripHarmingMinLevel", 5,
            v -> v >= 0 && v <= 100, "must be 0 to 100 (0 means never)");
    private static final Knob<Integer> POTION_DRIP_EFFECT_SECONDS = integer("potionDripEffectSeconds", 8,
            v -> v >= 1 && v <= 30, "must be 1 to 30");
    private static final Knob<Integer> POTION_DRIP_FALL_TICKS = integer("potionDripFallTicks", 40,
            v -> v >= 20 && v <= 100, "must be 20 to 100");
    private static final Knob<Integer> POTION_DRIP_MIN_SECONDS = integer("potionDripMinSeconds", 8,
            v -> v >= 1 && v <= 120, "must be 1 to 120");
    /**
     * The Lush Caves boss (roadmap T23): the giant slime's size (vanilla slimes are 1 to 4), its health at a
     * party of one, and the most slimes alive at once, so the doubled splits can never lag a server.
     */
    private static final Knob<Integer> LUSH_SLIME_SIZE = integer("lushSlimeSize", 8,
            v -> v >= 2 && v <= 16, "must be 2 to 16");
    private static final Knob<Integer> LUSH_SLIME_HEALTH = integer("lushSlimeHealth", 120,
            v -> v >= 10 && v <= 2000, "must be 10 to 2000");
    private static final Knob<Integer> LUSH_SLIME_MAX_ALIVE = integer("lushSlimeMaxAlive", 40,
            v -> v >= 4 && v <= 200, "must be 4 to 200");
    /**
     * Haunts (docs/DECISIONS.md): a floor wide roaming creature with a learnable rule. The pressure
     * rises by {@code hauntPressurePerSecond} each second the party spends on a haunted floor and the haunt wakes
     * when it reaches {@code hauntWakePressure}. The floor number in the dungeon sets its stage: a sighting before
     * {@code hauntNearMissFloor}, a near miss before {@code hauntStrikeFloor}, a strike from there on (the strike
     * floor is at least 3, so a two floor dungeon never strikes). A strike hurts for {@code hauntStrikeDamage}
     * health points (never more than leaves one) and slows for {@code hauntSlownessSeconds}; the haunt then stays
     * away {@code hauntRetreatSeconds}. {@code hauntTellSeconds} is the least warning before it can act and
     * {@code hauntLookConeDegrees} the half angle of the view that freezes the Counted. A sighting vanishes when
     * a member comes within {@code hauntSightingApproachBlocks} or after {@code hauntLingerSeconds}, and the
     * haunt gives up for the floor after {@code hauntSightingVanishes} of them.
     */
    private static final Knob<Boolean> HAUNTS_ENABLED = flag("hauntsEnabled", true);
    private static final Knob<Double> HAUNT_PRESSURE_PER_SECOND = decimal("hauntPressurePerSecond", 1.0,
            v -> v >= 0.0 && v <= 20.0, "must be 0.0 to 20.0");
    private static final Knob<Integer> HAUNT_WAKE_PRESSURE = integer("hauntWakePressure", 90,
            v -> v >= 10 && v <= 3600, "must be 10 to 3600");
    private static final Knob<Integer> HAUNT_LOOK_CONE_DEGREES = integer("hauntLookConeDegrees", 60,
            v -> v >= 10 && v <= 90, "must be 10 to 90");
    private static final Knob<Integer> HAUNT_NEAR_MISS_FLOOR = integer("hauntNearMissFloor", 2,
            v -> v >= 2 && v <= 5, "must be 2 to 5");
    private static final Knob<Integer> HAUNT_STRIKE_FLOOR = integer("hauntStrikeFloor", 3,
            v -> v >= 3 && v <= 10, "must be 3 to 10");
    private static final Knob<Double> HAUNT_STRIKE_DAMAGE = decimal("hauntStrikeDamage", 3.0,
            v -> v >= 0.0 && v <= 8.0, "must be 0.0 to 8.0");
    private static final Knob<Integer> HAUNT_SLOWNESS_SECONDS = integer("hauntSlownessSeconds", 3,
            v -> v >= 0 && v <= 10, "must be 0 to 10");
    private static final Knob<Integer> HAUNT_RETREAT_SECONDS = integer("hauntRetreatSeconds", 40,
            v -> v >= 5 && v <= 600, "must be 5 to 600");
    private static final Knob<Integer> HAUNT_TELL_SECONDS = integer("hauntTellSeconds", 2,
            v -> v >= 2 && v <= 10, "must be 2 to 10");
    /** milk buckets each Milking Parlour churn is left holding */
    private static final Knob<Integer> COW_PARLOUR_MILK_BUCKETS = integer("cowParlourMilkBuckets", 2,
            v -> v >= 0 && v <= 9, "must be 0 to 9");
    private static final Knob<Integer> HAUNT_LINGER_SECONDS = integer("hauntLingerSeconds", 45,
            v -> v >= 10 && v <= 300, "must be 10 to 300");
    private static final Knob<Integer> HAUNT_SIGHTING_VANISHES = integer("hauntSightingVanishes", 3,
            v -> v >= 1 && v <= 8, "must be 1 to 8");
    private static final Knob<Integer> HAUNT_SIGHTING_APPROACH_BLOCKS = integer("hauntSightingApproachBlocks", 7,
            v -> v >= 3 && v <= 16, "must be 3 to 16");
    /** Seconds after a hunt floor opens before its keeper wakes */
    private static final Knob<Integer> HAUNT_HUNT_GRACE_SECONDS = integer("hauntHuntGraceSeconds", 20,
            v -> v >= 5 && v <= 60, "must be 5 to 60");
    /** Seconds a hit staggers a hunting keeper for; it is immune for five more */
    private static final Knob<Integer> HAUNT_STUN_SECONDS = integer("hauntStunSeconds", 3,
            v -> v >= 1 && v <= 10, "must be 1 to 10");
    /** Path speed multiplier a hunting keeper walks at; a drowned body crawls on land at 1.0 */
    private static final Knob<Double> HAUNT_HUNT_SPEED = decimal("hauntHuntSpeed", 2.5,
            v -> v >= 0.5 && v <= 6.0, "must be 0.5 to 6.0");
    /** Seconds a member stands on a haunt floor beacon plate to light it */
    private static final Knob<Integer> BEACON_CHARGE_SECONDS = integer("beaconChargeSeconds", 3,
            v -> v >= 1 && v <= 10, "must be 1 to 10");
    /** Seconds a lit beacon drives a hunting keeper off the floor */
    private static final Knob<Integer> BEACON_BANISH_SECONDS = integer("beaconBanishSeconds", 15,
            v -> v >= 5 && v <= 60, "must be 5 to 60");
    /** Seconds a member channels on the vault core before it cracks */
    private static final Knob<Integer> HEIST_CORE_CHANNEL_SECONDS = integer("heistCoreChannelSeconds", 5,
            v -> v >= 1 && v <= 30, "must be 1 to 30");
    /** Drowned a cracked vault raises before one more per member */
    private static final Knob<Integer> HEIST_CREW_BASE = integer("heistCrewBase", 2,
            v -> v >= 0 && v <= 8, "must be 0 to 8");
    /** Strongboxes a heist floor places before party size adds more */
    private static final Knob<Integer> HEIST_STRONGBOX_BASE = integer("heistStrongboxBase", 2,
            v -> v >= 1 && v <= 8, "must be 1 to 8");
    /** Gilded veins a claim floor asks the party to claim before the pad opens */
    private static final Knob<Integer> CLAIM_QUOTA = integer("claimQuota", 3, v -> v >= 1 && v <= 6, "must be 1 to 6");
    /** Extra gilded veins a claim floor sprinkles in beyond the quota, for digging past it */
    private static final Knob<Integer> CLAIM_EXTRA_VEINS = integer("claimExtraVeins", 3,
            v -> v >= 0 && v <= 8, "must be 0 to 8");
    /** Tremor a claim floor's claims must reach before each further claim raises a hoglin pack */
    private static final Knob<Integer> CLAIM_WAVE_TREMOR = integer("claimWaveTremor", 4,
            v -> v >= 1 && v <= 20, "must be 1 to 20");
    /** Tremor that wakes The Assayer on a claim floor, once a floor; expected above claimWaveTremor */
    private static final Knob<Integer> CLAIM_HUNTER_TREMOR = integer("claimHunterTremor", 6,
            v -> v >= 1 && v <= 20, "must be 1 to 20");
    /**
     * The Astrolabe Room (design pass 2026-10-09, Q1): the first staging room lets the owner turn an astrolabe
     * to pick an act and open any of its dungeons. Off restores the old three random doors.
     */
    private static final Knob<Boolean> HALL_ENABLED = flag("hallEnabled", true);
    /** What a repeat finish of a dungeon pays of the finish emeralds, as a percent (the first finish pays all). */
    private static final Knob<Integer> REPEAT_FINISH_EMERALD_PERCENT = integer("repeatFinishEmeraldPercent", 50,
            v -> v >= 0 && v <= 100, "must be 0 to 100");
    private static final Knob<Integer> SIDEBAR_REPAINT_TICKS = integer("sidebarRepaintTicks", 20,
            v -> v >= 5 && v <= 100, "must be 5 to 100");
    private static final Knob<Integer> SLOWNESS_MAX_SECONDS = integer("slownessMaxSeconds", 6,
            v -> v >= 0 && v <= 600, "must be 0 (off) to 600");
    private static final Knob<Integer> MINING_FATIGUE_MAX_SECONDS = integer("miningFatigueMaxSeconds", 30,
            v -> v >= 0 && v <= 600, "must be 0 (off) to 600");
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
    private static final Knob<List<PowerBonusEntry>> POWER_BONUSES = new Knob<>("powerBonuses", defaultPowerBonuses(),
            PocketDungeonsConfig::readPowerBonuses, PocketDungeonsConfig::powerBonusesJson);
    /**
     * L2 (D41): content module overrides, {@code "modules": {"alchemy": true}}. Not a number to read back: the
     * map is handed to {@link ContentModules} as the file is read, and it is read first, before any other
     * knob. It has no accessor here; {@link ContentModules#overrides()} is the way to read it.
     */
    private static final Knob<Map<String, Boolean>> MODULES = new Knob<Map<String, Boolean>>("modules", Map.of(),
            PocketDungeonsConfig::readModules, ignored -> new JsonObject())
            .early().withoutAccessor().publishing(ContentModules::setOverrides);
    /**
     * How many extracted powers can be active on a player at once, across every
     * worn/held slot the Cube reads. A pool, not a per-piece allowance: three
     * powers spread across five imbuable slots is still "at most three," not
     * "at most three per slot." D3's own count.
     */
    private static final Knob<Integer> EQUIP_CAP = integer("equipCap", 3, v -> v >= 0, "must be >= 0");

    /** One material's equip-time attribute bonus. See {@link #trimBonuses()}. */
    public record TrimBonusEntry(String material, String attribute, double amount, String operation) {}

    /** One extracted power's equip-time attribute bonus. See {@link #powerBonuses()}. */
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

    // ---- the Knob descriptor and its factories ------------------------------------------

    /**
     * One tunable. The descriptor is the whole definition: the JSON {@code key}, the default,
     * how the file's value is read and checked, and the value in force. {@code key} is also the
     * name of the public accessor, which {@code ConfigKnobsTest} enforces.
     *
     * <p>Built with {@link #integer}, {@link #decimal}, {@link #flag} or {@link #text}; the
     * structured lists and the module map use the constructor with their own reader. A knob
     * whose value must be checked against another knob, or remapped, adds {@link #after} or
     * {@link #normalising}.
     */
    static final class Knob<T> {
        private final String key;
        private final T defaultValue;
        private final Function<JsonObject, T> reader;
        private final Function<T, JsonElement> writer;
        private T value;
        private boolean early;
        private boolean accessor = true;
        private UnaryOperator<T> normaliser;
        private Runnable afterRead;
        private Consumer<T> publisher;

        Knob(String key, T defaultValue, Function<JsonObject, T> reader, Function<T, JsonElement> writer) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.reader = reader;
            this.writer = writer;
            this.value = defaultValue;
            KNOBS.add(this);
        }

        String key() {
            return key;
        }

        T get() {
            return value;
        }

        T defaultValue() {
            return defaultValue;
        }

        /** Whether a public accessor named {@link #key()} is required. */
        boolean hasAccessor() {
            return accessor;
        }

        /** The default as it is written to a new config file. */
        JsonElement defaultJson() {
            return writer.apply(defaultValue);
        }

        private void set(T newValue) {
            value = newValue;
        }

        private void reset() {
            value = defaultValue;
            if (publisher != null) {
                publisher.accept(value);
            }
        }

        private void read(JsonObject root) {
            T read = reader.apply(root);
            value = normaliser != null ? normaliser.apply(read) : read;
            if (publisher != null) {
                publisher.accept(value);
            }
            if (afterRead != null) {
                afterRead.run();
            }
        }

        /** Rewrites the value just read (a saved value that names something retired). */
        Knob<T> normalising(UnaryOperator<T> normalise) {
            this.normaliser = normalise;
            return this;
        }

        /** Runs after this knob is read, for a check that needs other knobs read before it. */
        Knob<T> after(Runnable check) {
            this.afterRead = check;
            return this;
        }

        /** Reads before every other knob. */
        Knob<T> early() {
            this.early = true;
            return this;
        }

        /** For a knob read through some other class, which therefore has no accessor here. */
        Knob<T> withoutAccessor() {
            this.accessor = false;
            return this;
        }

        /** Hands every value read, and the default on a reset, to {@code consumer}. */
        Knob<T> publishing(Consumer<T> consumer) {
            this.publisher = consumer;
            return this;
        }
    }

    private static Knob<Integer> integer(String key, int defaultValue, IntPredicate valid, String requirement) {
        return new Knob<>(key, defaultValue, root -> readInt(root, key, defaultValue, valid, requirement),
                JsonPrimitive::new);
    }

    private static Knob<Double> decimal(String key, double defaultValue, DoublePredicate valid, String requirement) {
        return new Knob<>(key, defaultValue, root -> readDouble(root, key, defaultValue, valid, requirement),
                JsonPrimitive::new);
    }

    private static Knob<Boolean> flag(String key, boolean defaultValue) {
        return new Knob<>(key, defaultValue, root -> readBoolean(root, key, defaultValue), JsonPrimitive::new);
    }

    /** {@code allowEmpty}: whether an empty string is a meaningful value (see {@link #readString}). */
    private static Knob<String> text(String key, String defaultValue, boolean allowEmpty) {
        return new Knob<>(key, defaultValue, root -> readString(root, key, defaultValue, allowEmpty),
                JsonPrimitive::new);
    }

    /** The knobs in declaration order, for {@code ConfigKnobsTest}. */
    static List<Knob<?>> knobs() {
        return Collections.unmodifiableList(KNOBS);
    }

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
                writeAtomically(file, defaultsJson());
                PocketDungeonsMod.LOG.info("Created default pocketdungeons.json");
                applyDefaults();
                return;
            }
            JsonObject parsed;
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                parsed = GSON.fromJson(r, JsonObject.class);
            }
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
                try {
                    writeAtomically(file, rewrite);
                    PocketDungeonsMod.LOG.info("pocketdungeons.json updated: missing keys added at "
                            + "their defaults, retired keys dropped, every other value kept");
                } catch (IOException e) {
                    // The values just applied are the operator's; a file another program holds open
                    // (the move cannot replace it) must not send the server back to defaults.
                    PocketDungeonsMod.LOG.warn("pocketdungeons.json could not be updated with the missing "
                            + "keys; running on the values it holds", e);
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

    /**
     * PD-200: writes {@code json} to {@code file} through a sibling temp file and an atomic move, so a kill
     * part way through the write leaves the old file whole instead of a truncated one.
     */
    private static void writeAtomically(Path file, JsonObject json) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(json, w);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static int slotPitch() {
        return SLOT_PITCH.get();
    }

    public static int slotsPerRow() {
        return SLOTS_PER_ROW.get();
    }

    public static int watchIntervalTicks() {
        return WATCH_INTERVAL_TICKS.get();
    }

    public static int voidGuardDepth() {
        return VOID_GUARD_DEPTH.get();
    }

    public static int maxPartyMembers() {
        return MAX_PARTY_MEMBERS.get();
    }

    public static int ownerReconnectGraceSeconds() {
        return OWNER_RECONNECT_GRACE_SECONDS.get();
    }

    public static int inviteTtlSeconds() {
        return INVITE_TTL_SECONDS.get();
    }

    /** M76: cap on live (non-admin-build) instances. 0 disables the cap. */
    public static int maxConcurrentInstances() {
        return MAX_CONCURRENT_INSTANCES.get();
    }

    /** M76: cap on concurrent read-only visit instances. 0 disables the cap. */
    public static int maxConcurrentVisits() {
        return MAX_CONCURRENT_VISITS.get();
    }

    /** M76: cap on preview cells standing at once. 0 disables the cap. */
    public static int maxConcurrentPreviews() {
        return MAX_CONCURRENT_PREVIEWS.get();
    }

    public static boolean playtestJournal() {
        return PLAYTEST_JOURNAL.get();
    }

    public static int lemonIdleSeconds() {
        return LEMON_IDLE_SECONDS.get();
    }

    public static int lemonAskIdleSeconds() {
        return LEMON_ASK_IDLE_SECONDS.get();
    }

    public static int lemonLlmLapseSeconds() {
        return LEMON_LLM_LAPSE_SECONDS.get();
    }

    public static int lemonFallbackSeconds() {
        return LEMON_FALLBACK_SECONDS.get();
    }

    public static int lemonLingerSeconds() {
        return LEMON_LINGER_SECONDS.get();
    }

    public static int lemonThinkSeconds() {
        return LEMON_THINK_SECONDS.get();
    }

    public static double omenDangerScalePerOmen() {
        return OMEN_DANGER_SCALE_PER_OMEN.get();
    }

    public static double omenDangerScaleActOne() {
        return OMEN_DANGER_SCALE_ACT_ONE.get();
    }

    public static int pathLengthMin() {
        return PATH_LENGTH_MIN.get();
    }

    public static int pathLengthMax() {
        return PATH_LENGTH_MAX.get();
    }

    public static int firstFloorMaxEncounters() {
        return FIRST_FLOOR_MAX_ENCOUNTERS.get();
    }

    public static double branchProbability() {
        return BRANCH_PROBABILITY.get();
    }

    public static double loopProbability() {
        return LOOP_PROBABILITY.get();
    }

    public static int planAttemptBudget() {
        return PLAN_ATTEMPT_BUDGET.get();
    }

    /** The interval's usual length, and the door steps a keystone level costs. See the field. */
    public static int floorsPerSafeVisit() {
        return FLOORS_PER_SAFE_VISIT.get();
    }

    public static int releaseMaxAct() {
        return RELEASE_MAX_ACT.get();
    }

    public static int capstoneDungeonsNeeded() {
        return CAPSTONE_DUNGEONS_NEEDED.get();
    }

    public static int maxGridSpan() {
        return MAX_GRID_SPAN.get();
    }

    public static int clearBlocksPerTick() {
        return CLEAR_BLOCKS_PER_TICK.get();
    }

    public static boolean ritualEnabled() {
        return RITUAL_ENABLED.get();
    }

    public static String payoutCommand() {
        return PAYOUT_COMMAND.get();
    }

    public static String vaultKeyItem() {
        return VAULT_KEY_ITEM.get();
    }

    public static String ominousVaultKeyItem() {
        return OMINOUS_VAULT_KEY_ITEM.get();
    }

    public static int trialSpawnerCooldownTicks() {
        return TRIAL_SPAWNER_COOLDOWN_TICKS.get();
    }

    public static double overclockedCooldownFactor() {
        return OVERCLOCKED_COOLDOWN_FACTOR.get();
    }

    public static int overclockedParSeconds() {
        return OVERCLOCKED_PAR_SECONDS.get();
    }

    public static int overclockedBonus() {
        return OVERCLOCKED_BONUS.get();
    }

    public static double swarmingMobFactor() {
        return SWARMING_MOB_FACTOR.get();
    }

    public static int finishEmeralds() {
        return FINISH_EMERALDS.get();
    }

    public static int finishVaultChests() {
        return FINISH_VAULT_CHESTS.get();
    }

    public static int failHaulKeepPercent() {
        return FAIL_HAUL_KEEP_PERCENT.get();
    }


    public static double ordealEmeraldChance() {
        return ORDEAL_EMERALD_CHANCE.get();
    }

    public static double cowSpecimenZeroScale() {
        return COW_SPECIMEN_ZERO_SCALE.get();
    }

    public static int cowSpecimenZeroHealth() {
        return COW_SPECIMEN_ZERO_HEALTH.get();
    }

    public static double fountainChance() {
        return FOUNTAIN_CHANCE.get();
    }


    public static int silencedConsumablesPerOmen() {
        return SILENCED_CONSUMABLES_PER_OMEN.get();
    }

    public static double chaoticSpawnerChance() {
        return CHAOTIC_SPAWNER_CHANCE.get();
    }

    public static int silencedPlayerRange() {
        return SILENCED_PLAYER_RANGE.get();
    }

    public static int moltenHazardsPerCell() {
        return MOLTEN_HAZARDS_PER_CELL.get();
    }

    public static int feralWolvesPerCell() {
        return FERAL_WOLVES_PER_CELL.get();
    }

    public static int explosiveHazardsPerCell() {
        return EXPLOSIVE_HAZARDS_PER_CELL.get();
    }

    public static double voidedCellChance() {
        return VOIDED_CELL_CHANCE.get();
    }

    public static double mobScalePerLevel() {
        return MOB_SCALE_PER_LEVEL.get();
    }

    public static double mobScaleBase() {
        return MOB_SCALE_BASE.get();
    }

    public static double breezeHpMultiplier() {
        return BREEZE_HP_MULTIPLIER.get();
    }

    public static double spawnerClearThreshold() {
        return SPAWNER_CLEAR_THRESHOLD.get();
    }

    public static int pocket2TimerSeconds() {
        return POCKET2_TIMER_SECONDS.get();
    }

    public static double pocket2DoorChance() {
        return POCKET2_DOOR_CHANCE.get();
    }

    public static double anomalyRoomChance() {
        return ANOMALY_ROOM_CHANCE.get();
    }

    public static String rerollBlock() {
        return REROLL_BLOCK.get();
    }

    public static int rerollLapisPerTier() {
        return REROLL_LAPIS_PER_TIER.get();
    }

    public static String storageBlock() {
        return STORAGE_BLOCK.get();
    }

    public static String salvageBlock() {
        return SALVAGE_BLOCK.get();
    }

    public static int salvageKeyEmeralds() {
        return SALVAGE_KEY_EMERALDS.get();
    }

    public static int salvageMaterialBonus() {
        return SALVAGE_MATERIAL_BONUS.get();
    }

    public static int lootDurabilityPercent() {
        return LOOT_DURABILITY_PERCENT.get();
    }

    public static int craftedDurabilityPercent() {
        return CRAFTED_DURABILITY_PERCENT.get();
    }

    public static int poisonMaxSeconds() {
        return POISON_MAX_SECONDS.get();
    }

    public static int witherMaxSeconds() {
        return WITHER_MAX_SECONDS.get();
    }

    public static int scrapCostBase() {
        return SCRAP_COST_BASE.get();
    }

    public static int scrapCostEvery() {
        return SCRAP_COST_EVERY.get();
    }

    public static int scrapPerAct() {
        return SCRAP_PER_ACT.get();
    }

    public static int scrapFinalBonus() {
        return SCRAP_FINAL_BONUS.get();
    }

    public static int belowCompassPercent() {
        return BELOW_COMPASS_PERCENT.get();
    }

    public static int endlessDepthEvery() {
        return ENDLESS_DEPTH_EVERY.get();
    }

    public static int endlessDepthMax() {
        return ENDLESS_DEPTH_MAX.get();
    }

    public static int endlessGuardianEvery() {
        return ENDLESS_GUARDIAN_EVERY.get();
    }

    public static double endlessGuardianHardnessStep() {
        return ENDLESS_GUARDIAN_HARDNESS_STEP.get();
    }

    public static double endlessGuardianLapStep() {
        return ENDLESS_GUARDIAN_LAP_STEP.get();
    }

    public static int mineGuardianMobCap() {
        return MINE_GUARDIAN_MOB_CAP.get();
    }

    public static int provingWaveBaseSize() {
        return PROVING_WAVE_BASE_SIZE.get();
    }

    public static int provingWaveSizePerFloor() {
        return PROVING_WAVE_SIZE_PER_FLOOR.get();
    }

    public static int provingWaveSizePerMember() {
        return PROVING_WAVE_SIZE_PER_MEMBER.get();
    }

    public static int provingWaveMaxSize() {
        return PROVING_WAVE_MAX_SIZE.get();
    }

    public static int provingChampionEvery() {
        return PROVING_CHAMPION_EVERY.get();
    }

    public static int broodMobCap() {
        return BROOD_MOB_CAP.get();
    }

    public static int sculkHeardMax() {
        return SCULK_HEARD_MAX.get();
    }

    public static int sculkHeardMaxAncient() {
        return SCULK_HEARD_MAX_ANCIENT.get();
    }

    public static double whelpHealth() {
        return WHELP_HEALTH.get();
    }

    public static double whelpDamage() {
        return WHELP_DAMAGE.get();
    }

    public static double whelpSpeed() {
        return WHELP_SPEED.get();
    }

    public static int whelpSeconds() {
        return WHELP_SECONDS.get();
    }

    public static int sculkUnheardScrap() {
        return SCULK_UNHEARD_SCRAP.get();
    }

    public static int hushCeiling() {
        return HUSH_CEILING.get();
    }

    public static int hushPulseRise() {
        return HUSH_PULSE_RISE.get();
    }

    public static int hushBowRise() {
        return HUSH_BOW_RISE.get();
    }

    public static int hushHitRise() {
        return HUSH_HIT_RISE.get();
    }

    public static int hushDeathRise() {
        return HUSH_DEATH_RISE.get();
    }

    public static int hushSprintRise() {
        return HUSH_SPRINT_RISE.get();
    }

    public static int hushQuietSeconds() {
        return HUSH_QUIET_SECONDS.get();
    }

    public static int hushWhelps() {
        return HUSH_WHELPS.get();
    }

    public static int ancientWardenAnswers() {
        return ANCIENT_WARDEN_ANSWERS.get();
    }

    public static int petCap() {
        return PET_CAP.get();
    }

    public static boolean finaleEnabled() {
        return FINALE_ENABLED.get();
    }

    public static int finaleRewardChests() {
        return FINALE_REWARD_CHESTS.get();
    }

    public static boolean wagerGearEnabled() {
        return WAGER_GEAR_ENABLED.get();
    }

    public static int wagerTierChancePerPoint() {
        return WAGER_TIER_CHANCE_PER_POINT.get();
    }

    public static int wagerTierChanceCap() {
        return WAGER_TIER_CHANCE_CAP.get();
    }

    public static int lootPercentPerWager() {
        return LOOT_PERCENT_PER_WAGER.get();
    }

    public static int finaleCountdownSeconds() {
        return FINALE_COUNTDOWN_SECONDS.get();
    }

    public static int copperClockMinutes() {
        return COPPER_CLOCK_MINUTES.get();
    }

    public static int copperClockBonusSeconds() {
        return COPPER_CLOCK_BONUS_SECONDS.get();
    }

    public static int copperVentPulseSeconds() {
        return COPPER_VENT_PULSE_SECONDS.get();
    }

    public static int copperVentBlownPercent() {
        return COPPER_VENT_BLOWN_PERCENT.get();
    }

    public static int copperVentBurnSeconds() {
        return COPPER_VENT_BURN_SECONDS.get();
    }

    public static double copperForemanVatDamage() {
        return COPPER_FOREMAN_VAT_DAMAGE.get();
    }

    public static int copperForemanVatWeakSeconds() {
        return COPPER_FOREMAN_VAT_WEAK_SECONDS.get();
    }

    public static double copperForemanVatCorneredMultiplier() {
        return COPPER_FOREMAN_VAT_CORNERED.get();
    }

    public static int copperVentCorneredPercent() {
        return COPPER_VENT_CORNERED_PERCENT.get();
    }

    public static int bossPhaseStaggerSeconds() {
        return BOSS_PHASE_STAGGER_SECONDS.get();
    }

    public static int frostColdTicksPerSecond() {
        return FROST_COLD_TICKS_PER_SECOND.get();
    }

    public static int frostBrazierRadius() {
        return FROST_BRAZIER_RADIUS.get();
    }

    public static int creepingMossySeconds() {
        return CREEPING_MOSSY_SECONDS.get();
    }

    public static int creepingStoneSeconds() {
        return CREEPING_STONE_SECONDS.get();
    }

    public static int creepingInfestedSeconds() {
        return CREEPING_INFESTED_SECONDS.get();
    }

    public static int creepingRoomCap() {
        return CREEPING_ROOM_CAP.get();
    }

    public static int creepingFloorCap() {
        return CREEPING_FLOOR_CAP.get();
    }

    public static int livesPerExtraMember() {
        return LIVES_PER_EXTRA_MEMBER.get();
    }

    public static int creepingRoomSeeds() {
        return CREEPING_ROOM_SEEDS.get();
    }

    public static double infestationBossScale() {
        return INFESTATION_BOSS_SCALE.get();
    }

    public static int infestationSurfaceSeconds() {
        return INFESTATION_SURFACE_SECONDS.get();
    }

    public static int infestationBurrowTellSeconds() {
        return INFESTATION_BURROW_TELL_SECONDS.get();
    }

    public static int infestationAddsTellSeconds() {
        return INFESTATION_ADDS_TELL_SECONDS.get();
    }

    public static int infestationAddsMaxSeconds() {
        return INFESTATION_ADDS_MAX_SECONDS.get();
    }

    public static int infestationEmergeTellSeconds() {
        return INFESTATION_EMERGE_TELL_SECONDS.get();
    }

    public static int infestationBricksPerPhase() {
        return INFESTATION_BRICKS_PER_PHASE.get();
    }

    public static int infestationBricksPerExtraMember() {
        return INFESTATION_BRICKS_PER_EXTRA_MEMBER.get();
    }

    public static int infestationArenaBrickCap() {
        return INFESTATION_ARENA_BRICK_CAP.get();
    }

    public static int infestationSilverfishCap() {
        return INFESTATION_SILVERFISH_CAP.get();
    }

    public static int infestationPlantSeconds() {
        return INFESTATION_PLANT_SECONDS.get();
    }

    public static int infestationBossHealth() {
        return INFESTATION_BOSS_HEALTH.get();
    }

    public static double infestationBossDamage() {
        return INFESTATION_BOSS_DAMAGE.get();
    }

    public static int finaleEliteHealthPercentPerMember() {
        return FINALE_ELITE_HEALTH_PERCENT_PER_MEMBER.get();
    }

    public static boolean potionDripEnabled() {
        return POTION_DRIP_ENABLED.get();
    }

    public static int potionDripIntervalSeconds() {
        return POTION_DRIP_INTERVAL_SECONDS.get();
    }

    public static int potionDripJitterPercent() {
        return POTION_DRIP_JITTER_PERCENT.get();
    }

    public static int potionDripStartFloor() {
        return POTION_DRIP_START_FLOOR.get();
    }

    public static int potionDripRampPercent() {
        return POTION_DRIP_RAMP_PERCENT.get();
    }

    public static int potionDripBossPercent() {
        return POTION_DRIP_BOSS_PERCENT.get();
    }

    public static int potionDripHarmfulPercent() {
        return POTION_DRIP_HARMFUL_PERCENT.get();
    }

    public static int potionDripHarmingMinLevel() {
        return POTION_DRIP_HARMING_MIN_LEVEL.get();
    }

    public static int potionDripEffectSeconds() {
        return POTION_DRIP_EFFECT_SECONDS.get();
    }

    public static int potionDripFallTicks() {
        return POTION_DRIP_FALL_TICKS.get();
    }

    public static int potionDripMinSeconds() {
        return POTION_DRIP_MIN_SECONDS.get();
    }

    public static int lushSlimeSize() {
        return LUSH_SLIME_SIZE.get();
    }

    public static int lushSlimeHealth() {
        return LUSH_SLIME_HEALTH.get();
    }

    public static int lushSlimeMaxAlive() {
        return LUSH_SLIME_MAX_ALIVE.get();
    }

    public static int mineshaftDarkSpiders() {
        return MINESHAFT_DARK_SPIDERS.get();
    }

    public static int mineshaftDarkTellSeconds() {
        return MINESHAFT_DARK_TELL_SECONDS.get();
    }

    public static boolean hauntsEnabled() {
        return HAUNTS_ENABLED.get();
    }

    public static double hauntPressurePerSecond() {
        return HAUNT_PRESSURE_PER_SECOND.get();
    }

    public static int hauntWakePressure() {
        return HAUNT_WAKE_PRESSURE.get();
    }

    public static int hauntLookConeDegrees() {
        return HAUNT_LOOK_CONE_DEGREES.get();
    }

    public static int hauntNearMissFloor() {
        return HAUNT_NEAR_MISS_FLOOR.get();
    }

    public static int hauntStrikeFloor() {
        return HAUNT_STRIKE_FLOOR.get();
    }

    public static double hauntStrikeDamage() {
        return HAUNT_STRIKE_DAMAGE.get();
    }

    public static int hauntSlownessSeconds() {
        return HAUNT_SLOWNESS_SECONDS.get();
    }

    public static int hauntRetreatSeconds() {
        return HAUNT_RETREAT_SECONDS.get();
    }

    public static int hauntTellSeconds() {
        return HAUNT_TELL_SECONDS.get();
    }

    public static int cowParlourMilkBuckets() {
        return COW_PARLOUR_MILK_BUCKETS.get();
    }

    public static int hauntLingerSeconds() {
        return HAUNT_LINGER_SECONDS.get();
    }

    public static int hauntSightingVanishes() {
        return HAUNT_SIGHTING_VANISHES.get();
    }

    public static int hauntSightingApproachBlocks() {
        return HAUNT_SIGHTING_APPROACH_BLOCKS.get();
    }

    public static int hauntHuntGraceSeconds() {
        return HAUNT_HUNT_GRACE_SECONDS.get();
    }

    public static int hauntStunSeconds() {
        return HAUNT_STUN_SECONDS.get();
    }

    public static double hauntHuntSpeed() {
        return HAUNT_HUNT_SPEED.get();
    }

    public static int beaconChargeSeconds() {
        return BEACON_CHARGE_SECONDS.get();
    }

    public static int beaconBanishSeconds() {
        return BEACON_BANISH_SECONDS.get();
    }

    public static int heistCoreChannelSeconds() {
        return HEIST_CORE_CHANNEL_SECONDS.get();
    }

    public static int heistCrewBase() {
        return HEIST_CREW_BASE.get();
    }

    public static int heistStrongboxBase() {
        return HEIST_STRONGBOX_BASE.get();
    }

    public static int claimQuota() {
        return CLAIM_QUOTA.get();
    }

    public static int claimExtraVeins() {
        return CLAIM_EXTRA_VEINS.get();
    }

    public static int claimWaveTremor() {
        return CLAIM_WAVE_TREMOR.get();
    }

    public static int claimHunterTremor() {
        return CLAIM_HUNTER_TREMOR.get();
    }

    public static boolean mobUniformEnabled() {
        return MOB_UNIFORM_ENABLED.get();
    }

    public static boolean hallEnabled() {
        return HALL_ENABLED.get();
    }

    public static int repeatFinishEmeraldPercent() {
        return REPEAT_FINISH_EMERALD_PERCENT.get();
    }

    public static boolean sidebarEnabled() {
        return SIDEBAR_ENABLED.get();
    }

    public static int sidebarRepaintTicks() {
        return SIDEBAR_REPAINT_TICKS.get();
    }

    public static int slownessMaxSeconds() {
        return SLOWNESS_MAX_SECONDS.get();
    }

    public static int miningFatigueMaxSeconds() {
        return MINING_FATIGUE_MAX_SECONDS.get();
    }

    public static int salvageOminousKeyEmeralds() {
        return SALVAGE_OMINOUS_KEY_EMERALDS.get();
    }

    public static int shellDupeEmeralds() {
        return SHELL_DUPE_EMERALDS.get();
    }

    public static List<PowerBonusEntry> powerBonuses() {
        return POWER_BONUSES.get();
    }

    public static int equipCap() {
        return EQUIP_CAP.get();
    }

    public static List<TrimBonusEntry> trimBonuses() {
        return TRIM_BONUSES.get();
    }

    public static boolean trimBonusDungeonOnly() {
        return TRIM_BONUS_DUNGEON_ONLY.get();
    }

    public static String keystoneItem() {
        return KEYSTONE_ITEM.get();
    }

    public static int keystoneMaxLevel() {
        return KEYSTONE_MAX_LEVEL.get();
    }


    public static int rewardRoomGraceSeconds() {
        return REWARD_ROOM_GRACE_SECONDS.get();
    }

    public static int afkSeconds() {
        return AFK_SECONDS.get();
    }

    public static int timedOutDepletion() {
        return TIMED_OUT_DEPLETION.get();
    }

    private static void applyDefaults() {
        for (Knob<?> knob : KNOBS) {
            knob.reset();
        }
    }

    /**
     * Keys the mod once read and no longer does: the run clock and its chest
     * scoring, and the Fuel currency keys J1 retired. A file that still carries
     * them loads normally; they are named once in the log and left out of
     * the next save.
     */
    private static final List<String> RETIRED_KEYS = List.of(
            "timerBaseSeconds", "timerPerRoomSeconds", "door1TimerSeconds", "salvageEmeraldsPerTier",
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
            "overlevelEmeraldsPerScrap",
            // Campaign and endgame (2026-10-18): the campaign pays no scrap and Compass 1 costs the curve's price.
            // The first-level cost is also known by its decision-record name, compassCostFirstLevel.
            "actZeroScrap", "firstLevelScrapCost", "compassCostFirstLevel",
            // The Act 1 mine opens with Act 1; the compass no longer gates any campaign door.
            "endlessMineUnlockLevel");

    private static void apply(JsonObject root) {
        List<String> retired = RETIRED_KEYS.stream().filter(root::has).toList();
        if (!retired.isEmpty()) {
            PocketDungeonsMod.LOG.info("pocketdungeons.json still sets {}, which no longer do anything "
                    + "(retired keys); ignoring them", String.join(", ", retired));
        }
        for (Knob<?> knob : KNOBS) {
            if (knob.early) {
                knob.read(root);
            }
        }
        for (Knob<?> knob : KNOBS) {
            if (!knob.early) {
                knob.read(root);
            }
        }
    }

    /**
     * L2 (D41): {@code "modules": {"alchemy": true}} overrides a content module's
     * manifest default. A missing entry means the manifest default; a
     * non-boolean value is ignored with a warning; an id no module
     * declares is kept but reported at content reload time
     * (ContentModuleLoader.parse).
     */
    private static Map<String, Boolean> readModules(JsonObject root) {
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
        return moduleOverrides;
    }

    /** A path cannot be shorter at its longest than at its shortest; the file is wrong, so the max is raised. */
    private static void raisePathLengthMax() {
        int min = PATH_LENGTH_MIN.get();
        int max = PATH_LENGTH_MAX.get();
        if (max < min) {
            PocketDungeonsMod.LOG.error(
                    "pocketdungeons.json pathLengthMax ({}) is below pathLengthMin ({}); "
                            + "using pathLengthMax = pathLengthMin", max, min);
            PATH_LENGTH_MAX.set(min);
        }
    }

    private static void fitPathLengthsToGrid() {
        // PD-46: a path longer than the grid can possibly hold (its cell
        // count can never exceed maxGridSpan squared, whatever shape the
        // generator folds it into) fails RoomSelector.validate for every
        // seed with no diagnostic, and the mod silently falls back to
        // StaticLayout forever. This is the worst-case bound the grid
        // literally cannot exceed, not a tight one: a real generated path
        // can still fail well below it depending on branching.
        int maxGridSpan = MAX_GRID_SPAN.get();
        int maxPossiblePathLength = maxGridSpan * maxGridSpan;
        if (PATH_LENGTH_MAX.get() > maxPossiblePathLength) {
            PocketDungeonsMod.LOG.error(
                    "pocketdungeons.json pathLengthMax ({}) cannot fit inside a maxGridSpan of {} "
                            + "({} cells at most); clamping pathLengthMax to {}",
                    PATH_LENGTH_MAX.get(), maxGridSpan, maxPossiblePathLength, maxPossiblePathLength);
            PATH_LENGTH_MAX.set(maxPossiblePathLength);
            if (PATH_LENGTH_MIN.get() > PATH_LENGTH_MAX.get()) {
                PATH_LENGTH_MIN.set(PATH_LENGTH_MAX.get());
            }
        }
    }

    private static JsonElement trimBonusesJson(List<TrimBonusEntry> entries) {
        JsonArray array = new JsonArray();
        for (TrimBonusEntry entry : entries) {
            JsonObject entryJson = new JsonObject();
            entryJson.addProperty("material", entry.material());
            entryJson.addProperty("attribute", entry.attribute());
            entryJson.addProperty("amount", entry.amount());
            entryJson.addProperty("operation", entry.operation());
            array.add(entryJson);
        }
        return array;
    }

    private static JsonElement powerBonusesJson(List<PowerBonusEntry> entries) {
        JsonArray array = new JsonArray();
        for (PowerBonusEntry entry : entries) {
            JsonObject entryJson = new JsonObject();
            entryJson.addProperty("id", entry.id());
            entryJson.addProperty("attribute", entry.attribute());
            entryJson.addProperty("amount", entry.amount());
            entryJson.addProperty("operation", entry.operation());
            array.add(entryJson);
        }
        return array;
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
            writeAtomically(file, parsed);
        } catch (Exception e) {
            PocketDungeonsMod.LOG.error("pocketdungeons.json could not record module override {}", id, e);
        }
    }

    private static JsonObject defaultsJson() {
        JsonObject root = new JsonObject();
        for (Knob<?> knob : KNOBS) {
            root.add(knob.key(), knob.defaultJson());
        }
        return root;
    }
}
