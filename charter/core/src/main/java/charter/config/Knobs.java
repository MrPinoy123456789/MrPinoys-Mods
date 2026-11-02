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
    /** What a keep costs, in Marks (about 2048: five days of a ten member kingdom's Mint tax). Paid by the treasury through the payment port. */
    public int keepPriceMarks = 2048;
    /** Diamonds a founder carries and gives up to found a kingdom with the Charter book's Nexus (items, not a treasury). */
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
    /** Marks the first outpost of a nation holds as its deposit; each outpost held already adds the step. */
    public int outpostDepositMarks = 256;
    public int outpostDepositStepMarks = 128;
    /** Whether teleport pads show a ring of sparks to players nearby. */
    public boolean padParticles = true;
    /** Whether a pad chimes when a player steps on it and a pad trip makes a sound on arrival. */
    public boolean padSounds = true;
    /** Whether Charter terminals (blocks that open the Charter menu when right-clicked) show a few quiet sparks. */
    public boolean terminalCues = true;
    /** Whether founding or moving a keep puts a lectern terminal beside its Nexus. */
    public boolean keepTerminals = true;
    /**
     * Whether players who run the Charter Maps client mod are sent the kingdom map (held land, keeps, outposts) for
     * Xaero's World Map and Minimap. Players without the mod, and Bedrock players, are never sent anything either way.
     */
    public boolean mapSyncEnabled = true;
    /** Whether map markers (supply drops, {@code /charter admin mapmarker}) go out with the kingdom map. */
    public boolean mapSyncMarkers = true;
    /** Who is handed the Charter book automatically: "everyone", "newcomers" (no kingdom and no race) or "none". */
    public String bookGivenTo = "everyone";
    /** Minutes a lifted Nexus lodestone may be carried before it goes back to where it stood. */
    public int nexusLiftMinutes = 10;
    /** Seconds a lifted Nexus waits for its carrier to come back after logging out before it goes back (0: at once). */
    public int nexusLiftLogoutSeconds = 0;

    // ---- keep upkeep (wall days; the granary is the upkeep) ----
    /** Days a full granary lasts. It drains by an even share each hour and nothing else changes the drain. */
    public int keepUpkeepDays = 14;
    /**
     * Effects a race gives the people who have it, by race node path (the kingdom's name lower case, as in race:azrak) to
     * effect ids. These are the race traits that used to come from the race trinkets: Merfolk (Azurius) breathe water and
     * swim like dolphins, Dwarves (Azrak) mine faster, the Fae (Faeloria) see in the dark. The Humans (Aurelia) have no
     * effect: their passive is better trade deals, which Foundry gives them (race_traits.json trade_discount), and the
     * Fairies (Everdelle) glide, which Foundry gives them too.
     */
    public java.util.Map<String, List<String>> racePassives = new java.util.LinkedHashMap<>(java.util.Map.of(
            "azurius", List.of("minecraft:water_breathing", "minecraft:dolphins_grace"),
            "azrak", List.of("minecraft:haste"),
            "faeloria", List.of("minecraft:night_vision")));
    /**
     * Races whose people keep everything when they die: nothing drops and the inventory and experience come back on
     * respawn. Race node paths (the kingdom's name lower case, anything but letters and digits an underscore, as in
     * race:mossbeard_s_muddle). Empty: no race has it.
     */
    public java.util.List<String> racesKeepInventory = new java.util.ArrayList<>(java.util.List.of("mossbeard_s_muddle"));
    // ---- race combat passives (always on, kept mild; see docs/RACES.md) ----
    /** Master switch for the race combat passives below. Off: no race gets any of them. */
    public boolean raceCombat = true;
    /** Dwarf (Azrak) Bedrock Stance: knockback resistance, 0 to 1, while the player sneaks. */
    public double dwarfSneakKnockbackResist = 0.4;
    /** Merfolk (Azurius) Tidewrath: extra melee attack damage while the player is in water. */
    public double merfolkWaterDamageBonus = 1.5;
    /** Human (Aurelia) Rally: extra melee attack damage for each nearby member of the same kingdom. */
    public double humanRallyPerAlly = 0.5;
    /** Rally counts at most this many allies. */
    public int humanRallyMaxAllies = 3;
    /** Rally counts allies within this many blocks. */
    public double humanRallyRadius = 8;
    /** Fae (Faeloria) Glamour: hostile mobs farther than this many blocks lose their target. */
    public double faeGlamourRange = 6;
    /** Glamour stops this many ticks after the Fae last attacked, so a Fae who fights is seen. */
    public int faeGlamourGraceTicks = 200;
    /** Fairy (Everdelle) Featherlight: the chance, 0 to 1, to dodge one melee hit. Fairies also take no fall damage. */
    public double fairyDodgeChance = 0.08;
    /**
     * Whether members of the same kingdom can hurt each other: by hand, with arrows, tridents and other projectiles,
     * with lit TNT and through tamed pets. Off: those hits do nothing. A player can still hurt themselves.
     */
    public boolean kingdomFriendlyFire = false;
    /**
     * Whether a player riding something that is being carried (by a carry mod, which switches the carried entity's
     * physics off) is set down at once, and a carried player sheds anyone riding on them. Stops the endless climb from
     * mounting a carried horse. Off: Charter does not interfere.
     */
    public boolean carryGuard = true;
    /** Whether players are told on the action bar when they cross into a kingdom, an outpost, held land or the wild. */
    public boolean entryMessages = true;
    /**
     * The colour of the [tag] before a player's name, by the kingdom's name in lower case, and "mercenary" for players
     * with a race and no kingdom. A kingdom with no entry gets no tag. Hex colours.
     */
    public java.util.Map<String, String> tagColors = new java.util.LinkedHashMap<>(java.util.Map.of(
            "everdelle", "#FF69B4", "azurius", "#89CFF0", "faeloria", "#A020F0",
            "azrak", "#FFD700", "aurelia", "#FF6A1A", "mercenary", "#8A0303",
            "mossbeard's muddle", "#388E3C"));
    /**
     * A shorter word for the [tag] than the kingdom's name, by the same lower case key as {@code tagColors}. A kingdom with
     * no entry shows its own name. The kingdom keeps its name everywhere else.
     */
    public java.util.Map<String, String> tagLabels = new java.util.LinkedHashMap<>(java.util.Map.of("mossbeard's muddle", "Moss"));
    /**
     * Whether a member's rank shows after their kingdom's tag, "[Faeloria] Queen Heather", over the head, in the tab
     * list and in chat. Only a kingdom whose Lord has edited its ranks shows them; until then the tag is as before.
     */
    public boolean showRankTags = true;
    /** Whether the rank also shows before a name in chat. Off: chat shows the kingdom tag only, the name tag keeps the rank. */
    public boolean rankInChat = true;
    /** Whether a keep turns away players who are not its members, not at war with it and not operators. */
    public boolean closedKeeps = false;
    /**
     * A player with no kingdom and no race (neither a member nor a mercenary) may only look around: they cannot break,
     * place, use or attack anything, in the wild as well. Operators are exempt. They open the Charter book to choose.
     */
    public boolean requireAffiliation = true;
    /**
     * Bedrock players (through Floodgate) get the book as native forms with button icons. Off sends them the same
     * dialogs a Java player sees, translated by Geyser. A switch in case a Floodgate update breaks the forms.
     */
    public boolean bedrockNativeForms = true;
    /** Operators are given an Admin Book (the operators' menu as an item) when they join or respawn without one. */
    public boolean opsGetAdminBook = true;
    /** Whether every player sees a glowing line along nearby keep borders without holding anything. */
    public boolean keepBorderGlow = false;
    /** Whether a player who has lost their Charter book is given a new one. */
    public boolean replaceLostBook = true;
    /**
     * Extra effects only for Bedrock players, by race node path. Bedrock has no Dolphin's Grace, so the Merfolk get
     * Speed II while they are in water instead, with no icon and no particles. An entry is an effect id, optionally
     * after "water:" (only while the player is in water) and before "#" and an amplifier: {@code water:minecraft:speed#1}.
     */
    public java.util.Map<String, List<String>> racePassivesBedrock = new java.util.LinkedHashMap<>(java.util.Map.of(
            "azurius", List.of("water:minecraft:speed#1")));
    /** Whether a kingdom's deciders may change its form of government (Lordship, council, Commune). Off: only operators can. */
    public boolean leadersChangeGovernance = true;
    /** Whether anyone may be put into an office, or a new office made, by the people who hold power. Off: only operators, and the members' elections. */
    public boolean leadersAppointOffices = true;
    /** Whether a keep eats from its granary and lapses when it runs dry. Off: keeps need no food, and only outposts keep a store. */
    public boolean keepGranary = true;
    /** Days of food left at which every member is warned, largest first. */
    public int[] keepWarnDays = {7, 3, 1};
    /**
     * Whether explosions (TNT, creepers, fireballs, withers, end crystals, beds and respawn anchors, wind charges, any
     * modded source) may break or ignite blocks on claimed land: keeps, outposts and every other claim, whether or not the
     * claim is protected at the time. Off: no explosion changes a claimed block, the owner's own included. The wild is
     * unchanged, and so is the damage an explosion does to entities.
     */
    public boolean explosionsInKingdoms = false;

    // ---- the spawn: an admin kingdom's land ----
    /** Master switch for {@link #spawnRules}. Off: an admin kingdom's land is protected like any other kingdom's. */
    public boolean spawnRulesEnabled = true;
    /** What is switched off on an admin kingdom's land for everyone but operators. */
    public SpawnRuleSet spawnRules = new SpawnRuleSet();

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
    /** Real seconds after a wave is wiped out before the next spawns, instead of waiting out the interval. */
    public int npcWaveAfterClearSeconds = 30;
    /** A site nobody fought for this many real days is cleaned up and replaced. */
    public int npcExpireDays = 10;
    /** Server ticks between the layers of a falling site's crumble, from the top down. */
    public int npcCrumbleTicksPerLayer = 4;
    /** A new site stands at least this many chunks from any player claim and any online player. */
    public int npcPlayerClearChunks = 8;
    /** Tiers by distance from the world spawn, in chunks: near is below the first, middle below the second, else far. */
    public int npcNearChunks = 40;
    public int npcMiddleChunks = 120;
    /** Granary share at spawn, near, middle, far. */
    public double[] npcGranaryShare = {0.30, 0.50, 0.70};
    /** Raiders a stronghold may have alive at once, near, middle, far, and an outpost. */
    public int[] npcPoolByTier = {3, 5, 8};
    public int[] npcOutpostPool = {1, 2, 3};
    /** Raiders a site can still spawn, over its whole life, near, middle, far. Each raider costs one. */
    public int[] npcSupplyStronghold = {24, 48, 96};
    public int[] npcSupplyOutpost = {6, 9, 12};
    /** Marks a stronghold's treasury starts with, near, middle, far. The share set by npcPlunderShare is plundered when it falls. */
    public int[] npcTreasuryMarks = {528, 1056, 2112};
    /** The share of a fallen stronghold's treasury the winner takes, 0 to 1. The rest stays with the dissolved site. */
    public double npcPlunderShare = 1.0;
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
    /** How long a mercenary waits before taking a different race. */
    public int mercChangeDays = 7;
    public int dormancyDays = 30;
    /**
     * What a Lordship's Lord may spend in one action without a vote, in Marks. Negative means no limit, which is the
     * default for now; the dial stays here for when a limit is wanted. Read when a Lordship is founded, so a kingdom keeps the
     * limit it was founded with.
     */
    public int lordSpendLimitMarks = -1;
    public int orgNameMaxLength = 32;

    // ---- justice and contracts ----
    public int confinementMinMinutes = 60;
    public int confinementMaxHours = 24;
    public int executionRespawnDelayMaxMinutes = 60;
    public int banishmentMaxHours = 24;
    /** The longest a Lord may mute one of their own citizens. */
    public int lordMuteMaxMinutes = 60;
    /** The longest a Lord may suspend one of their own citizens from the kingdom's land. */
    public int lordSuspendMaxHours = 12;
    public int weeklyPunishmentCapHours = 24;
    public int sameFactionSamePlayerCooldownDays = 3;
    public int custodyFineCapMarks = 512;
    /** Marks a kingdom pays, for each hour of a sentence still to run, to buy one of its own members out of it (at most custodyFineCapMarks). */
    public int bailMarksPerHour = 32;
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
        if (spawnRules == null) out.add("spawnRules must be a section of rules, not null.");
        if (bookGivenTo == null || !List.of("everyone", "newcomers", "none").contains(bookGivenTo.trim().toLowerCase(java.util.Locale.ROOT))) {
            out.add("bookGivenTo must be everyone, newcomers or none.");
        }
        if (nexusLiftMinutes < 1) out.add("nexusLiftMinutes must be at least 1.");
        if (nexusLiftLogoutSeconds < 0) out.add("nexusLiftLogoutSeconds must not be negative.");
        return out;
    }
}
