package fortune.core;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code config/fortune/fortune.json}. Plain public fields with their defaults set here, so a file that leaves a
 * key out keeps the default (the fabric module reads it with Gson, which runs the no argument constructor first).
 *
 * <p>Knobs for the parts not built yet (First Week, Second Wind, kingdom muster, Discord) exist and default off;
 * turning them on does nothing until those parts are built. See the TODO notes on each.
 */
public final class Settings {

    // ---- the day and the streak ----

    /** A day runs from this hour (UTC) to the same hour the next day. */
    public int rolloverHourUtc = 8;
    /** The streak survives a gap of up to this many days between claims (a day difference of 1 to this). */
    public int streakGraceDays = 7;
    /** A claim this many days or more after the last one is a Welcome Back spin. */
    public int comebackDays = 14;
    /** A player's first ever spin waits until they have played this many minutes. Stops join and quit farming. */
    public int firstSpinMinutes = 5;

    // ---- payout ----

    /**
     * Run as the server console on every spin when Charter Currency is loaded. {@code %player%} is the player's name,
     * {@code %uuid%} their id, {@code %level%} the spin's level, {@code %date%} the claim day (yyyy-MM-dd).
     */
    public String payoutCommand = "chartercurrency earn %player% fortune %level% fortune-%date%-%player%";
    /** Mirrors the {@code fortune} source in Charter Currency's earn.json; used only to size the stake bonus and the Odds screen. */
    public int valorBase = 4;
    public int valorPerLevel = 2;

    // ---- the coin stake ----

    public boolean stakeEnabled = true;
    public List<StakeTier> stakeTiers = new ArrayList<>(List.of(
            new StakeTier("copper", "Copper stake", "minecraft:copper_nugget", 8, 25),
            new StakeTier("iron", "Iron stake", "minecraft:iron_nugget", 24, 50),
            new StakeTier("gold", "Gold stake", "minecraft:gold_nugget", 64, 100)));

    // ---- the machine ----

    /** Ticks between animation frames for Java players at full speed. */
    public int frameTicks = 2;
    /** Ticks between frames for Bedrock players, so Geyser is not flooded with inventory updates. */
    public int frameTicksBedrock = 3;
    /** {@code "chest"} (the machine) or {@code "form"} (Bedrock players get a form with the result in words). */
    public String bedrockMode = "chest";
    /** The chat or form prompt on join. */
    public boolean joinPrompt = true;
    /** Ticks after joining before the prompt, so it lands below the MOTD and pack prompt. */
    public int joinDelayTicks = 60;
    /** Crown symbol fallback when the Foundry content item is not registered. */
    public String crownFallbackItem = "minecraft:nether_star";

    // ---- symbols and paytables ----

    public List<SymbolDef> symbols = new ArrayList<>(List.of(
            new SymbolDef("bread", "Bread", "minecraft:bread", 30, 0),
            new SymbolDef("iron", "Iron", "minecraft:iron_ingot", 25, 0),
            new SymbolDef("gold", "Gold", "minecraft:gold_ingot", 18, 1),
            new SymbolDef("emerald", "Emerald", "minecraft:emerald", 14, 1),
            new SymbolDef("diamond", "Diamond", "minecraft:diamond", 8, 2),
            new SymbolDef("crown", "Crown", "kingdoms:gold_coin", 5, 3)));

    /** Weights are basis points: each table sums to 10000 (100 percent). */
    public List<PayRow> normalTable = new ArrayList<>(List.of(
            new PayRow("NO_MATCH", 4600, 1, List.of(), false, false),
            new PayRow("PAIR", 3600, 2, List.of(), false, false),
            new PayRow("LOW_TRIPLE", 1100, 4, List.of(new Extra("minecraft:iron_ingot", 4)), false, false),
            new PayRow("MID_TRIPLE", 550, 6, List.of(new Extra("minecraft:emerald", 2)), false, false),
            new PayRow("DIAMONDS", 130, 10, List.of(new Extra("minecraft:diamond", 1)), true, false),
            new PayRow("CROWNS", 20, 16, List.of(new Extra("minecraft:diamond", 2)), true, true)));

    /** Day 7 of the week: three of a kind guaranteed, Crowns at 2 percent. */
    public List<PayRow> goldenTable = new ArrayList<>(List.of(
            new PayRow("LOW_TRIPLE", 6000, 4, List.of(new Extra("minecraft:iron_ingot", 4)), false, false),
            new PayRow("MID_TRIPLE", 3000, 6, List.of(new Extra("minecraft:emerald", 2)), false, false),
            new PayRow("DIAMONDS", 800, 10, List.of(new Extra("minecraft:diamond", 1)), true, false),
            new PayRow("CROWNS", 200, 16, List.of(new Extra("minecraft:diamond", 2)), true, true)));

    /** The first claim after {@link #comebackDays} away: at least three of a kind. */
    public List<PayRow> welcomeBackTable = new ArrayList<>(List.of(
            new PayRow("LOW_TRIPLE", 6500, 4, List.of(new Extra("minecraft:iron_ingot", 4)), false, false),
            new PayRow("MID_TRIPLE", 2800, 6, List.of(new Extra("minecraft:emerald", 2)), false, false),
            new PayRow("DIAMONDS", 600, 10, List.of(new Extra("minecraft:diamond", 1)), true, false),
            new PayRow("CROWNS", 100, 16, List.of(new Extra("minecraft:diamond", 2)), true, true)));

    /** The jackpot keepsake: a named, enchanted vanilla item. */
    public Keepsake jackpotKeepsake = new Keepsake("minecraft:clock", "Fortune's Favour");

    // ---- milestones ----

    public List<Milestone> milestones = new ArrayList<>(List.of(
            new Milestone(7, "the Steadfast", "minecraft:amethyst_shard", "Charm of the Seventh Day"),
            new Milestone(30, "the Faithful", "minecraft:prismarine_crystals", "Charm of the Thirtieth Day"),
            new Milestone(60, "the Devoted", "minecraft:echo_shard", "Charm of the Sixtieth Day"),
            new Milestone(100, "the Centurion", "minecraft:heart_of_the_sea", "Charm of the Hundredth Day"),
            new Milestone(200, "the Unbroken", "minecraft:nautilus_shell", "Charm of the Two Hundredth Day"),
            new Milestone(365, "the Eternal", "minecraft:nether_star", "Charm of the Year")));
    /**
     * Run as the console when a milestone is reached; {@code %player%} and {@code %title%}. Empty by default (owner,
     * 2026-11-04): a LuckPerms suffix such as {@code lp user %player% meta setsuffix 50 " %title%"} would replace a
     * suffix the player already has, and nobody has checked that chat shows suffixes. The keepsake carries the title
     * in its name either way.
     */
    public List<String> milestoneCommands = new ArrayList<>();

    // ---- not built yet: knobs exist, default off ----

    /** TODO First Week (spec 5.3): fixed gifts for a player's first seven claims, no Valor. Not built. */
    public boolean firstWeekEnabled = false;
    public List<Extra> firstWeek = new ArrayList<>(List.of(
            new Extra("minecraft:emerald", 8), new Extra("minecraft:diamond", 2),
            new Extra("minecraft:ender_pearl", 4), new Extra("minecraft:blaze_rod", 4),
            new Extra("minecraft:gold_ingot", 8)));
    /** TODO Second Wind (spec 5.4): a half value spin after this many minutes online in a day. Not built. */
    public boolean secondWindEnabled = false;
    public int secondWindMinutes = 20;
    /** TODO Kingdom muster (spec 7), read from race:&lt;kingdom&gt; permission nodes. Not built. */
    public boolean musterEnabled = false;
    public int musterSize = 3;
    /** TODO Discord posts (spec 8). Not built: nothing is ever sent. */
    public String discordWebhook = "";
    public int discordMaxPerHour = 6;

    // ---- nested shapes ----

    public static final class SymbolDef {
        public String id;
        public String name;
        public String item;
        public int weight;
        /** 0 pays the low triple, 1 the middle triple, 2 Diamonds, 3 Crowns. */
        public int group;

        public SymbolDef() {
        }

        public SymbolDef(String id, String name, String item, int weight, int group) {
            this.id = id;
            this.name = name;
            this.item = item;
            this.weight = weight;
            this.group = group;
        }
    }

    public static final class PayRow {
        public String line;
        public int weight;
        public int level;
        public List<Extra> extras = new ArrayList<>();
        public boolean broadcast;
        public boolean jackpot;

        public PayRow() {
        }

        public PayRow(String line, int weight, int level, List<Extra> extras, boolean broadcast, boolean jackpot) {
            this.line = line;
            this.weight = weight;
            this.level = level;
            this.extras = new ArrayList<>(extras);
            this.broadcast = broadcast;
            this.jackpot = jackpot;
        }
    }

    public static final class Extra {
        public String item;
        public int count;

        public Extra() {
        }

        public Extra(String item, int count) {
            this.item = item;
            this.count = count;
        }
    }

    public static final class StakeTier {
        public String id;
        public String name;
        public String item;
        /** In Marks, Charter Currency's Coin unit. */
        public long cost;
        public int bonusPercent;

        public StakeTier() {
        }

        public StakeTier(String id, String name, String item, long cost, int bonusPercent) {
            this.id = id;
            this.name = name;
            this.item = item;
            this.cost = cost;
            this.bonusPercent = bonusPercent;
        }
    }

    public static final class Milestone {
        public int claims;
        public String title;
        public String keepsakeItem;
        public String keepsakeName;

        public Milestone() {
        }

        public Milestone(int claims, String title, String keepsakeItem, String keepsakeName) {
            this.claims = claims;
            this.title = title;
            this.keepsakeItem = keepsakeItem;
            this.keepsakeName = keepsakeName;
        }
    }

    public static final class Keepsake {
        public String item;
        public String name;

        public Keepsake() {
        }

        public Keepsake(String item, String name) {
            this.item = item;
            this.name = name;
        }
    }
}
