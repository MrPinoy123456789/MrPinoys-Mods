package foundry.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The race traits Foundry gives on top of the looks: how each race moves and what it can sense. Read from
 * {@code config/foundry/race_traits.json}; the built in values below are used when the file is absent and match the
 * shipped example. Pure data and rules, so it can be tested. The double jump and the swim dash themselves sit with the
 * looks (worn_displays.json); the effect passives (Haste, Luck, Night Vision and the rest) are Charter's racePassives.
 *
 * <p>Every number is mild by default: a speed bonus is a share of the player's base speed (0.15 is three quarters of Speed
 * I, which is 0.2), the same kind of number the Speed effect itself adds.
 */
public record RaceTraits(Map<String, Trait> races) {

    /** Where a race's stride bonus applies. */
    public enum Ground {
        /** Under a roof of blocks (no open sky straight above): Dwarves in their halls and mines. */
        UNDERGROUND,
        /** Standing on living ground: grass, moss, podzol, mycelium, rooted or coarse dirt, mud and leaves. */
        WILD,
        /** Everywhere. */
        ANYWHERE
    }

    /** Extra movement speed (a share of base speed) where {@code ground} says. */
    public record Stride(double bonus, Ground ground) {}

    /** A share of extra speed and extra jump strength for the mount this player rides (horses, donkeys, mules, camels). */
    public record Mount(double speed, double jump) {}

    /**
     * A sense: every {@code every} ticks the blocks within {@code radius} of the player are searched for the listed ones,
     * and the nearest is pointed at, to that player only (a short trail of particles toward it and an action bar line).
     * A block is an id, an id with block state values ({@code minecraft:cave_vines[berries=true]}),
     * {@code #loot_chest} (a chest, barrel or other container that still has its loot to roll) or {@code #loot:<prefix>}
     * (such a container whose loot table id starts with the prefix, as in {@code #loot:minecraft:chests/village/}).
     */
    public record Sense(String name, List<String> blocks, int radius, int every, int colour) {}

    /**
     * One race's traits. {@code fallDamage} is the share of fall damage taken off (0.5 halves it). {@code jumpDrift} is
     * the forward push of a double jump (blocks per tick along the way the player faces), for a race that has one.
     * {@code tradeDiscount} is the share taken off villager and wandering trader prices (see {@link #discount}).
     */
    public record Trait(Stride stride, Mount mount, double fallDamage, double jumpDrift, Sense sense, double tradeDiscount,
                        Flight flight, Dash dash, Slide slide) {
        public Trait(Stride stride, Mount mount, double fallDamage, double jumpDrift, Sense sense) {
            this(stride, mount, fallDamage, jumpDrift, sense, 0, null, null, null);
        }

        public Trait(Stride stride, Mount mount, double fallDamage, double jumpDrift, Sense sense, double tradeDiscount) {
            this(stride, mount, fallDamage, jumpDrift, sense, tradeDiscount, null, null, null);
        }

        public Trait(Stride stride, Mount mount, double fallDamage, double jumpDrift, Sense sense, double tradeDiscount,
                     Flight flight) {
            this(stride, mount, fallDamage, jumpDrift, sense, tradeDiscount, flight, null, null);
        }

        public Trait(Stride stride, Mount mount, double fallDamage, double jumpDrift, Sense sense, double tradeDiscount,
                     Flight flight, Dash dash) {
            this(stride, mount, fallDamage, jumpDrift, sense, tradeDiscount, flight, dash, null);
        }

        /** The wing dash for a race that double jumps: this race's own, or {@link Dash#DEFAULT} when it names none. */
        public Dash dashOrDefault() {
            return dash != null ? dash : Dash.DEFAULT;
        }
    }

    /**
     * The Fae wing dash (see {@link WingDash}), for a race that double jumps: {@code charges} bursts (0 keeps the plain
     * once per air double jump), all back on landing and one back every {@code refill} ticks in the air, at least
     * {@code gap} ticks apart. A burst pushes {@code forward} blocks per tick the way the player looks and lifts up to
     * {@code up} (looking straight up), and never leaves them moving faster than {@code maxSpeed} across the ground. Above
     * {@code ceiling} blocks over the ground below a burst no longer climbs. After a burst they fall slowly until they
     * land or press sneak; a hit from a mob, a player or a projectile takes a charge and drops them for {@code hitDrop}
     * ticks.
     */
    /**
     * The Human slide (see {@link CrouchSlide}): sprinting on the ground, a fresh press of sneak throws them forward the way they
     * run at {@code speed} blocks per tick (the ground's friction stops it within about 4 blocks at 1.7), then not again for
     * {@code cooldown} ticks.
     */
    public record Slide(double speed, int cooldown) {
        public static final Slide DEFAULT = new Slide(1.7, 60);
    }

    public record Dash(int charges, int refill, int gap, double up, double forward, double maxSpeed, int ceiling, int hitDrop) {
        public static final Dash DEFAULT = new Dash(3, 30, 8, 0.8, 1.2, 1.3, 12, 20);
    }

    /**
     * Powered flight for a race that glides (the Fairies; see {@link WingFlight}): the takeoff leap's upward speed in
     * blocks per tick ({@code leap}, 0 for none) and its cooldown in ticks; how many wingbeats ({@code beats}), each one's
     * lift and forward push in blocks per tick, the ticks for one to come back in the air ({@code refill}), and the
     * horizontal speed a beat never pushes past ({@code maxSpeed}, blocks per tick; a rocket goes about 1.7).
     */
    public record Flight(double leap, int leapCooldown, int beats, double beatLift, double beatPush, int refill, double maxSpeed) {}

    /** The result of reading a file: the traits, and what was wrong with it (the traits are the defaults if unreadable). */
    public record Parsed(RaceTraits traits, List<String> problems) {}

    public static final String LOOT_CHEST = "#loot_chest";
    /** A container whose loot table starts with what follows, as in {@code #loot:minecraft:chests/village/}. */
    public static final String LOOT_PREFIX = "#loot:";

    /** The Dwarves' ores: no diamond and no ancient debris (those are nobody's to smell). */
    public static final List<String> ORES = List.of("minecraft:gold_ore", "minecraft:deepslate_gold_ore",
            "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore", "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore",
            "minecraft:iron_ore", "minecraft:deepslate_iron_ore", "minecraft:copper_ore", "minecraft:deepslate_copper_ore",
            "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore");
    /** The Humans' treasure: surface structure loot only (villages, shipwrecks, buried treasure, mineshafts, ruined portals). */
    public static final List<String> TREASURE = List.of("#loot:minecraft:chests/village/", "#loot:minecraft:chests/shipwreck_",
            "#loot:minecraft:chests/buried_treasure", "#loot:minecraft:chests/abandoned_mineshaft",
            "#loot:minecraft:chests/ruined_portal");
    public static final List<String> HERBS = List.of("minecraft:nether_wart[age=3]", "minecraft:torchflower",
            "minecraft:pitcher_plant", "minecraft:wither_rose", "minecraft:chorus_flower",
            "minecraft:cave_vines[berries=true]", "minecraft:cave_vines_plant[berries=true]", "minecraft:cocoa[age=2]");
    public static final List<String> TIDE = List.of("minecraft:sponge", "minecraft:wet_sponge", "minecraft:suspicious_sand",
            "minecraft:suspicious_gravel");
    /** The Fairies' gems. */
    public static final List<String> GLIMMER = List.of("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
            "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore", "minecraft:amethyst_cluster",
            "minecraft:budding_amethyst");
    public static final List<String> NESTS = List.of("minecraft:bee_nest", "minecraft:beehive", "minecraft:turtle_egg",
            "minecraft:sniffer_egg", "minecraft:frogspawn");

    private static final Trait NONE = new Trait(null, null, 0, 0, null);

    /** The traits for these race nodes; NONE (nothing) for a player of no listed race. */
    public Trait of(String node) {
        return races.getOrDefault(node, NONE);
    }

    /** Built in values, used when the file is absent; they match the shipped example file. */
    public static RaceTraits defaults() {
        Map<String, Trait> r = new LinkedHashMap<>();
        r.put("race:azrak", new Trait(new Stride(0.15, Ground.UNDERGROUND), null, 0, 0,
                new Sense("Ore sense", ORES, 8, 40, 0xE0B040)));
        r.put("race:aurelia", new Trait(null, new Mount(0.2, 0.1), 0, 0,
                new Sense("Treasure sense", TREASURE, 8, 40, 0xC9CED8), 0.1, null, null, Slide.DEFAULT));
        r.put("race:faeloria", new Trait(null, null, 0.5, 0.15, new Sense("Herb sense", HERBS, 12, 40, 0x7CD35A)));
        r.put("race:azurius", new Trait(null, null, 0, 0, new Sense("Tide sense", TIDE, 10, 40, 0x3FC2CF)));
        r.put("race:everdelle", new Trait(null, null, 0, 0, new Sense("Glimmer sense", GLIMMER, 7, 80, 0xF59AC4), 0,
                new Flight(1.05, 100, 3, 0.55, 0.3, 200, 1.2)));
        r.put("race:mossbeard_s_muddle", new Trait(new Stride(0.15, Ground.WILD), null, 0, 0,
                new Sense("Kindred sense", NESTS, 12, 40, 0x6FAF3A)));
        return new RaceTraits(Map.copyOf(r));
    }

    /**
     * Parses the file: {@code {"race:azrak": {"stride": {"bonus": 0.15, "ground": "underground"}, "mount": {"speed": 0.2,
     * "jump": 0.1}, "fall_damage": 0.5, "jump_drift": 0.15, "sense": {"name": "Ore sense", "blocks": [...], "radius": 8,
     * "every": 40, "colour": "#E0B040"}, "trade_discount": 0.1, "flight": {"leap": 1.05, "leap_cooldown": 100, "beats": 3,
     * "beat_lift": 0.55, "beat_push": 0.3, "refill": 200, "max_speed": 1.2}}}. A race listed in the file replaces the built in one; a race left out keeps it.
     * A bad file gives the defaults and a problem line.
     */
    public static Parsed parse(String json) {
        List<String> problems = new ArrayList<>();
        RaceTraits defaults = defaults();
        JsonObject root;
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject()) return new Parsed(defaults, List.of("race_traits.json: the top level must be an object"));
            root = e.getAsJsonObject();
        } catch (RuntimeException ex) {
            return new Parsed(defaults, List.of("race_traits.json: not valid JSON (" + ex.getMessage() + ")"));
        }
        Map<String, Trait> races = new LinkedHashMap<>(defaults.races());
        for (var en : root.entrySet()) {
            if (en.getKey().startsWith("_")) continue;  // a comment
            String where = "race_traits.json: " + en.getKey();
            if (!en.getValue().isJsonObject()) {
                problems.add(where + " must be an object");
                continue;
            }
            try {
                races.put(en.getKey(), trait(en.getValue().getAsJsonObject(), where, problems));
            } catch (RuntimeException ex) {
                problems.add(where + " has a value of the wrong type (" + ex.getMessage() + ")");
            }
        }
        return new Parsed(new RaceTraits(Map.copyOf(races)), List.copyOf(problems));
    }

    private static Trait trait(JsonObject o, String where, List<String> problems) {
        Stride stride = null;
        if (o.has("stride")) {
            JsonObject s = o.getAsJsonObject("stride");
            Ground ground;
            try {
                ground = Ground.valueOf(s.has("ground") ? s.get("ground").getAsString().toUpperCase(java.util.Locale.ROOT) : "ANYWHERE");
            } catch (IllegalArgumentException ex) {
                problems.add(where + " stride ground must be underground, wild or anywhere");
                ground = Ground.ANYWHERE;
            }
            stride = new Stride(share(s, "bonus", where, problems), ground);
        }
        Mount mount = null;
        if (o.has("mount")) {
            JsonObject m = o.getAsJsonObject("mount");
            mount = new Mount(share(m, "speed", where, problems), share(m, "jump", where, problems));
        }
        double fall = o.has("fall_damage") ? share(o, "fall_damage", where, problems) : 0;
        double drift = o.has("jump_drift") ? Math.max(0, Math.min(1, o.get("jump_drift").getAsDouble())) : 0;
        Sense sense = null;
        if (o.has("sense")) {
            JsonObject s = o.getAsJsonObject("sense");
            List<String> blocks = new ArrayList<>();
            if (s.has("blocks")) s.getAsJsonArray("blocks").forEach(b -> blocks.add(b.getAsString()));
            for (String b : blocks) {
                boolean loot = b.equals(LOOT_CHEST) || (b.startsWith(LOOT_PREFIX) && b.length() > LOOT_PREFIX.length());
                if (!loot && BlockMatch.parse(b) == null) problems.add(where + " sense block " + b + " is not a block id");
            }
            int radius = s.has("radius") ? s.get("radius").getAsInt() : 8;
            if (radius < 1 || radius > 16) {
                problems.add(where + " sense radius must be 1 to 16 blocks");
                radius = Math.max(1, Math.min(16, radius));
            }
            int every = s.has("every") ? Math.max(10, s.get("every").getAsInt()) : 40;
            int colour = 0xFFFFFF;
            if (s.has("colour")) {
                try {
                    colour = Integer.parseInt(s.get("colour").getAsString().replace("#", ""), 16);
                } catch (NumberFormatException ex) {
                    problems.add(where + " sense colour must be a hex colour like #E0B040");
                }
            }
            sense = new Sense(s.has("name") ? s.get("name").getAsString() : "Sense", List.copyOf(blocks), radius, every, colour);
        }
        double trade = o.has("trade_discount") ? share(o, "trade_discount", where, problems) : 0;
        if (trade > 0.5) {
            problems.add(where + " trade_discount must be at most 0.5");
            trade = 0.5;
        }
        Flight flight = null;
        if (o.has("flight")) {
            JsonObject f = o.getAsJsonObject("flight");
            double leap = f.has("leap") ? f.get("leap").getAsDouble() : 0;
            double lift = f.has("beat_lift") ? f.get("beat_lift").getAsDouble() : 0;
            double push = f.has("beat_push") ? f.get("beat_push").getAsDouble() : 0;
            double max = f.has("max_speed") ? f.get("max_speed").getAsDouble() : 1.2;
            if (leap < 0 || leap > 2 || lift < 0 || lift > 1.5 || push < 0 || push > 1 || max <= 0 || max > 3) {
                problems.add(where + " flight: leap 0 to 2, beat_lift 0 to 1.5, beat_push 0 to 1, max_speed above 0 up to 3");
            }
            int beats = f.has("beats") ? f.get("beats").getAsInt() : 0;
            if (beats < 0 || beats > 10) problems.add(where + " flight beats must be 0 to 10");
            flight = new Flight(Math.max(0, Math.min(2, leap)), Math.max(0, f.has("leap_cooldown") ? f.get("leap_cooldown").getAsInt() : 100),
                    Math.max(0, Math.min(10, beats)), Math.max(0, Math.min(1.5, lift)), Math.max(0, Math.min(1, push)),
                    Math.max(1, f.has("refill") ? f.get("refill").getAsInt() : 200), Math.max(0.1, Math.min(3, max)));
        }
        Dash dash = null;
        if (o.has("wing_dash")) {
            JsonObject d = o.getAsJsonObject("wing_dash");
            Dash def = Dash.DEFAULT;
            int charges = d.has("charges") ? d.get("charges").getAsInt() : def.charges();
            int refill = d.has("refill") ? d.get("refill").getAsInt() : def.refill();
            int gap = d.has("gap") ? d.get("gap").getAsInt() : def.gap();
            double up = d.has("up") ? d.get("up").getAsDouble() : def.up();
            double forward = d.has("forward") ? d.get("forward").getAsDouble() : def.forward();
            double max = d.has("max_speed") ? d.get("max_speed").getAsDouble() : def.maxSpeed();
            int ceiling = d.has("ceiling") ? d.get("ceiling").getAsInt() : def.ceiling();
            int hitDrop = d.has("hit_drop") ? d.get("hit_drop").getAsInt() : def.hitDrop();
            if (charges < 0 || charges > 10 || refill < 1 || gap < 0 || up < 0 || up > 1.5 || forward < 0 || forward > 1.5
                    || max <= 0 || max > 3 || ceiling < 1 || ceiling > 64 || hitDrop < 0) {
                problems.add(where + " wing_dash: charges 0 to 10, refill 1 or more, gap 0 or more, up and forward 0 to 1.5,"
                        + " max_speed above 0 up to 3, ceiling 1 to 64, hit_drop 0 or more");
            }
            dash = new Dash(Math.max(0, Math.min(10, charges)), Math.max(1, refill), Math.max(0, gap),
                    Math.max(0, Math.min(1.5, up)), Math.max(0, Math.min(1.5, forward)), Math.max(0.1, Math.min(3, max)),
                    Math.max(1, Math.min(64, ceiling)), Math.max(0, hitDrop));
        }
        Slide slide = null;
        if (o.has("slide")) {
            JsonObject sl = o.getAsJsonObject("slide");
            double speed = sl.has("speed") ? sl.get("speed").getAsDouble() : Slide.DEFAULT.speed();
            int cooldown = sl.has("cooldown") ? sl.get("cooldown").getAsInt() : Slide.DEFAULT.cooldown();
            if (speed <= 0 || speed > 2.5 || cooldown < 10) {
                problems.add(where + " slide: speed above 0 up to 2.5, cooldown 10 ticks or more");
            }
            slide = new Slide(Math.max(0.1, Math.min(2.5, speed)), Math.max(10, cooldown));
        }
        return new Trait(stride, mount, fall, drift, sense, trade, flight, dash, slide);
    }

    /**
     * How many of an offer's first cost a trade discount takes off: the share of the base count, rounded, and never so
     * much that the price would go below one (vanilla also keeps every price at one or more). A 10 emerald offer with a
     * 0.1 discount costs 9; a 3 emerald one stays 3.
     */
    public static int discount(int baseCount, double share) {
        if (share <= 0 || baseCount <= 1) return 0;
        return Math.max(0, Math.min(baseCount - 1, (int) Math.round(baseCount * share)));
    }

    /** A share from 0 to 1; anything outside is clamped and reported. */
    private static double share(JsonObject o, String key, String where, List<String> problems) {
        double v = o.has(key) ? o.get(key).getAsDouble() : 0;
        if (v < 0 || v > 1) {
            problems.add(where + " " + key + " must be from 0 to 1");
            v = Math.max(0, Math.min(1, v));
        }
        return v;
    }

    /** A block id with optional block state values, as in {@code minecraft:cocoa[age=2]}. */
    public record BlockMatch(String id, Map<String, String> state) {
        public static BlockMatch parse(String text) {
            String id = text;
            Map<String, String> state = new LinkedHashMap<>();
            int open = text.indexOf('[');
            if (open >= 0) {
                if (!text.endsWith("]")) return null;
                id = text.substring(0, open);
                for (String pair : text.substring(open + 1, text.length() - 1).split(",")) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length != 2 || kv[0].isBlank()) return null;
                    state.put(kv[0].trim(), kv[1].trim());
                }
            }
            if (!id.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")) return null;
            return new BlockMatch(id, Map.copyOf(state));
        }
    }

    /** The index of the nearest of these squared distances, or -1 for none; ties go to the first. Pure helper for senses. */
    public static int nearest(double[] distSq) {
        int best = -1;
        for (int i = 0; i < distSq.length; i++) if (best < 0 || distSq[i] < distSq[best]) best = i;
        return best;
    }
}
