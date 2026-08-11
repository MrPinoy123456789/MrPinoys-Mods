package chatdonkey.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The weighted event pool from {@code events.json} (SPEC.md section 4).
 *
 * <p>Entries naming a behavior this build does not have are dropped with a
 * warning rather than treated as fatal: an operator who downgrades the mod, or
 * who mistypes {@code roadblok}, should lose that one event and keep the rest.
 * A pool that ends up completely empty falls back to the built-in defaults,
 * because a chat donkey mod with no events is just a config file.
 */
public final class EventPool {

    private final List<EventDefinition> entries;
    private final int totalWeight;

    private EventPool(List<EventDefinition> entries) {
        this.entries = List.copyOf(entries);
        int sum = 0;
        for (EventDefinition entry : entries) {
            sum += entry.weight();
        }
        this.totalWeight = sum;
    }

    /**
     * Builds a pool, dropping unknown or disabled entries.
     *
     * @param known    behavior ids this build actually implements
     * @param onWarning called once per dropped entry
     */
    public static EventPool of(List<EventDefinition> definitions, List<String> known,
                               java.util.function.Consumer<String> onWarning) {
        List<EventDefinition> usable = new ArrayList<>();
        if (definitions != null) {
            for (EventDefinition raw : definitions) {
                if (raw == null || raw.behavior() == null) {
                    continue;
                }
                EventDefinition entry = raw.sanitised();
                // A demand entry carries its own definition, so it does not need
                // to be a known built-in -- that is the whole point of demands
                // being data. Anything else must name a behavior this build has.
                if (!entry.isDemand() && !known.contains(entry.behavior())) {
                    onWarning.accept("Unknown event behavior '" + entry.behavior()
                            + "' in events.json -- skipping it. Known: " + known
                            + ", or give it a 'wants' item to make it a demand event.");
                    continue;
                }
                if (!entry.isEnabled()) {
                    continue;
                }
                usable.add(entry);
            }
        }
        return new EventPool(usable);
    }

    /**
     * The stock pool: every built-in movement event, plus the shipped demand
     * events.
     *
     * <p>Demand events are the Food Critic shape — follow the player asking for
     * an item, take it and leave happy, pay the golden tier for the fancy
     * version. They are pure data, so these four are examples as much as
     * content: copy an entry, change the items and the lines, and you have a
     * fifth.
     */
    public static EventPool defaults() {
        List<EventDefinition> list = new ArrayList<>();
        for (String id : Behaviors.ids()) {
            DonkeyBehavior behavior = Behaviors.byId(id);
            list.add(new EventDefinition(id, defaultWeightFor(id),
                    behavior.minDurationSeconds(), behavior.maxDurationSeconds()));
        }
        list.addAll(defaultDemands());
        return new EventPool(list);
    }

    /**
     * The shipped demand events. Each pairs an ordinary item with its fancy
     * counterpart, the way carrot pairs with golden carrot.
     */
    public static List<EventDefinition> defaultDemands() {
        return List.of(
                demand("foodcritic", 20, 30, 45, "minecraft:carrot", "minecraft:golden_carrot"),
                demand("sweettooth", 14, 30, 45, "minecraft:apple", "minecraft:golden_apple"),
                demand("bookworm", 14, 30, 50, "minecraft:book", "minecraft:enchanted_book"),
                demand("magpie", 12, 25, 45, "minecraft:iron_ingot", "minecraft:gold_ingot"),
                new EventDefinition("magician", 10, 30, 50,
                        new Demand(null, null, DUPLICATABLE, 2)));
    }

    /**
     * What the duplicating donkey will copy: <b>raw resources only</b>.
     *
     * <p>The exclusion is <b>structural, not economic</b>. Per DESIGN.md §5, play
     * showed sinks comfortably out-running faucets, so a generous payout is the
     * safer error and diamonds are on this list deliberately.
     *
     * <p>What stays off it is anything a <em>crafting recipe</em> can be
     * laundered through. Nine ingots make a block; doubling the block and
     * unpacking it returns eighteen, which turns every recipe in the game into a
     * multiplier and the trick into a machine. DESIGN.md §5's line is that a
     * payout should scale with playing, not with a workbench -- so this list is
     * raw materials only, and the 25-minute event cooldown is what bounds it.
     */
    public static final List<String> DUPLICATABLE = List.of(
            "minecraft:stone",
            "minecraft:cobblestone",
            "minecraft:deepslate",
            "minecraft:cobbled_deepslate",
            "minecraft:andesite",
            "minecraft:diorite",
            "minecraft:granite",
            "minecraft:tuff",
            "minecraft:calcite",
            "minecraft:netherrack",
            "minecraft:sand",
            "minecraft:gravel",
            "minecraft:clay_ball",
            "minecraft:flint",
            "minecraft:coal",
            "minecraft:charcoal",
            "minecraft:raw_iron",
            "minecraft:raw_copper",
            "minecraft:raw_gold",
            "minecraft:iron_ingot",
            "minecraft:copper_ingot",
            "minecraft:gold_ingot",
            "minecraft:iron_nugget",
            "minecraft:gold_nugget",
            "minecraft:redstone",
            "minecraft:lapis_lazuli",
            "minecraft:quartz",
            "minecraft:amethyst_shard",
            "minecraft:glowstone_dust",
            "minecraft:obsidian",
            // Mined, not crafted, so they double like any other rock. The suite
            // wants taps (DESIGN.md §5) and a doubled diamond once every 25
            // minutes -- only if the player happens to be carrying one -- is a
            // good moment rather than a problem.
            "minecraft:diamond",
            "minecraft:emerald",
            "minecraft:ancient_debris",
            "minecraft:netherite_scrap");

    private static EventDefinition demand(String id, int weight, int min, int max,
                                          String wants, String premium) {
        return new EventDefinition(id, weight, min, max, new Demand(wants, premium));
    }

    /**
     * Starting weights.
     *
     * <p>Not uniform, and deliberately so. Lecture is the plainest and wears
     * best, so it is the most common. False Alarm is the loudest joke with the
     * least variation, so it is the rarest -- a gag that fires as often as the
     * others stops landing fastest.
     */
    private static int defaultWeightFor(String id) {
        return switch (id) {
            case "lecture" -> 25;
            case "roadblock" -> 20;
            case "foodcritic" -> 20;
            case "serenade" -> 12;
            case "falsealarm" -> 8;
            // The loudest interruption in the mod, and the only one that can
            // hold a player's screen. Rare on purpose.
            case "burrs" -> 8;
            default -> 10;
        };
    }

    public boolean isEmpty() {
        return entries.isEmpty() || totalWeight <= 0;
    }

    public int size() {
        return entries.size();
    }

    public List<EventDefinition> entries() {
        return entries;
    }

    public int totalWeight() {
        return totalWeight;
    }

    /** @return a weighted random entry, or {@code null} if the pool is empty */
    public EventDefinition pick(Random random) {
        if (isEmpty()) {
            return null;
        }
        int roll = random.nextInt(totalWeight);
        for (EventDefinition entry : entries) {
            roll -= entry.weight();
            if (roll < 0) {
                return entry;
            }
        }
        // Unreachable while totalWeight is the true sum; returning the last
        // entry is a safer tail than throwing on a rounding surprise.
        return entries.get(entries.size() - 1);
    }

    /** Rolls a duration in ticks for one entry. */
    public static int durationTicks(EventDefinition entry, Random random) {
        int span = entry.maxSeconds() - entry.minSeconds() + 1;
        return 20 * (entry.minSeconds() + random.nextInt(Math.max(1, span)));
    }

    /** Serialisable form for {@code events.json}: behavior id to its tuning. */
    public Map<String, EventTuning> asMap() {
        Map<String, EventTuning> map = new LinkedHashMap<>();
        for (EventDefinition entry : entries) {
            map.put(entry.behavior(), EventTuning.of(entry));
        }
        return map;
    }
}
