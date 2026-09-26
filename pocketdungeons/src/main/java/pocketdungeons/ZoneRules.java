package pocketdungeons;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The zone rules hook ({@code docs/ZONES_SPEC.md} section 2): the small rules
 * object that says how a place plays, as opposed to how it looks. Every floor
 * runs the one loop (staging room, door, floor, checkpoint, bank); a zone only
 * changes the numbers the loop reads.
 *
 * <p>A zone is a {@code dungeon_theme}: its rules are the theme file's
 * optional {@code rules} block, and a theme without one is the default zone,
 * which reproduces the ordinary dungeon. The rules in force are the ones of
 * the floor in progress (or just cleared): {@link #forTheme} of
 * {@code record.floor.theme}. The Endless Mine recipe forces the Mine theme
 * on every floor of its interval, so a Mine interval runs under the Mine
 * theme's rules throughout.
 *
 * <p>No Minecraft imports on the parsing and arithmetic half, so it is
 * testable with plain {@code javac}; {@link #forTheme} is the one lookup into
 * the live manifests.
 *
 * @param floorSequence   the pattern of floor kinds within an interval,
 *                        repeating. Only {@code standard} is built, so every
 *                        entry is {@code standard} today; the field exists so
 *                        the Mine's shaft and drift floors plug in later.
 * @param capstone        what the zone's floors end in: {@link Capstone#BOSS}
 *                        is the existing boss behaviour (the Drowned Warden at
 *                        the terminal, which gates the pad), {@link Capstone#NONE}
 *                        is an ordinary floor. {@code null} when the file does
 *                        not say, in which case {@link #capstoneFor} derives
 *                        it from the adventure node's kind, as before.
 * @param depthBonus      bonus completion chests per floor since the last
 *                        bank, counted from the second floor: the floor's
 *                        chests are the band's plus
 *                        {@code floor(depthBonus * (floor - 1))}, capped at
 *                        {@link #MAX_BONUS_CHESTS}. The default third of a
 *                        chest per floor adds nothing on floors 1 to 3, one
 *                        chest from floor 4, two from 7, three from 10.
 * @param omenBaseAfter   the floor count after which every new floor of the
 *                        interval starts with {@link #omenBaseAmount} omen
 *                        already on it, or {@link #INTERVAL_LENGTH} for
 *                        {@code floorsPerSafeVisit}
 * @param omenBaseAmount  that head start, 0 to 4
 * @param omenScale       multiplier on every omen rise (dwell, sensors,
 *                        shrieks). Relief and the Ominous Bargain's fixed
 *                        value are not scaled.
 * @param lootRole        which faucet the zone is (ZONES_SPEC section 5):
 *                        {@code gear}, {@code materials} or {@code trophy}.
 *                        Declared and validated; nothing pays differently by
 *                        role yet.
 * @param lootTierEvery   floors since the last bank per extra completion loot
 *                        tier, capped at the top tier; {@code 0} for none.
 *                        The Endless Mine's escalating haul.
 * @param unlockLevel     the keystone level at which a door into this zone
 *                        can be offered
 * @param kitTopUpScale   multiplier on the safe-visit kit top-up
 *                        ({@link KitTopUp}); the result is still capped at
 *                        the kit baseline.
 */
record ZoneRules(List<String> floorSequence, Capstone capstone, double depthBonus,
                 int omenBaseAfter, int omenBaseAmount, double omenScale, String lootRole,
                 int lootTierEvery, int unlockLevel, double kitTopUpScale) {

    /** What a zone's floors end in. */
    enum Capstone { NONE, BOSS }

    /** The one floor kind the planner builds. */
    static final String STANDARD = "standard";

    /** Every floor kind the planner builds. Anything else is refused at load. */
    static final Set<String> FLOOR_KINDS = Set.of(STANDARD);

    /** The faucets a zone can declare. */
    static final Set<String> LOOT_ROLES = Set.of("gear", "materials", "trophy");

    /** {@link #omenBaseAfter} meaning "the interval length", {@code floorsPerSafeVisit}. */
    static final int INTERVAL_LENGTH = -1;

    /** The most bonus chests a floor can carry: one on top of each completion chest spot. */
    static final int MAX_BONUS_CHESTS = 3;

    /** The top completion loot tier ({@link KeystoneMath#lootTier}'s highest). */
    static final int MAX_LOOT_TIER = 3;

    /** The ordinary dungeon, and every theme whose file has no {@code rules} block. */
    static final ZoneRules DEFAULT = new ZoneRules(List.of(STANDARD), null, 1.0 / 3.0,
            INTERVAL_LENGTH, 1, 1.0, "gear", 0, 1, 1.0);

    ZoneRules {
        floorSequence = List.copyOf(floorSequence);
    }

    // ---- lookup ---------------------------------------------------------------

    /** The rules of the zone {@code themeId} names, or {@link #DEFAULT} for none or an unknown theme. */
    static ZoneRules forTheme(String themeId) {
        if (themeId == null || themeId.isEmpty()) {
            return DEFAULT;
        }
        ThemeManifest.Entry entry = ThemeManifest.current().byId(themeId);
        return entry == null || entry.meta().rules == null ? DEFAULT : entry.meta().rules;
    }

    /** The rules of the floor in progress, or of the floor just cleared between floors. */
    static ZoneRules of(InstanceRecord record) {
        return forTheme(record == null ? null : record.floor.theme);
    }

    /**
     * Whether a floor of {@code themeId} ends in the boss. An explicit
     * {@code capstone} wins; without one, a {@code boss} adventure node is a
     * boss floor, which is how the Drowned Vault has always worked.
     */
    static boolean bossCapstone(String themeId) {
        return capstoneFor(forTheme(themeId), adventureBoss(themeId)) == Capstone.BOSS;
    }

    private static boolean adventureBoss(String themeId) {
        AdventureGraph.Node node = AdventureGraphs.current().graph().node(themeId);
        return node != null && node.kind() == AdventureGraph.Kind.BOSS;
    }

    // ---- arithmetic (pure) ------------------------------------------------------

    /** {@code rules}' capstone, or the adventure node's when the file leaves it out. */
    static Capstone capstoneFor(ZoneRules rules, boolean adventureNodeIsBoss) {
        if (rules.capstone() != null) {
            return rules.capstone();
        }
        return adventureNodeIsBoss ? Capstone.BOSS : Capstone.NONE;
    }

    /** The floor kind of floor {@code floorNumber} (1 based) of an interval. */
    String floorKindAt(int floorNumber) {
        return floorSequence.get(Math.floorMod(Math.max(1, floorNumber) - 1, floorSequence.size()));
    }

    /** {@link #omenBaseAfter}, with {@link #INTERVAL_LENGTH} resolved against the config. */
    int omenBaseAfter(int floorsPerSafeVisit) {
        return omenBaseAfter == INTERVAL_LENGTH ? Math.max(1, floorsPerSafeVisit) : omenBaseAfter;
    }

    /**
     * The omen floor {@code floorNumber} (1 based) of an interval starts with:
     * {@link #omenBaseAmount} once the interval is past
     * {@link #omenBaseAfter}, nothing before. Not cumulative: floor 5 starts
     * with the same head start as floor 4.
     */
    int baseOmen(int floorNumber, int floorsPerSafeVisit) {
        return floorNumber > omenBaseAfter(floorsPerSafeVisit) ? omenBaseAmount : 0;
    }

    /**
     * Bonus completion chests for the floor that makes {@code floorsCleared}
     * since the last bank. See {@link #depthBonus}. The small epsilon keeps a
     * third written as {@code 0.3333} in a pack file from missing floor 4.
     */
    int bonusChests(int floorsCleared) {
        if (floorsCleared <= 1 || depthBonus <= 0) {
            return 0;
        }
        int bonus = (int) Math.floor(depthBonus * (floorsCleared - 1) + 1e-3);
        return Math.min(MAX_BONUS_CHESTS, bonus);
    }

    /**
     * The completion loot tier for the floor that makes {@code floorsCleared}
     * since the last bank, from the tier the keystone level earns: one step up
     * every {@link #lootTierEvery} floors, never past the top tier.
     */
    int lootTier(int baseTier, int floorsCleared) {
        if (lootTierEvery <= 0) {
            return baseTier;
        }
        return Math.min(MAX_LOOT_TIER, baseTier + Math.max(0, floorsCleared) / lootTierEvery);
    }

    /**
     * An omen rise scaled by {@link #omenScale}, rounded to the nearest whole
     * point. Relief (a negative or zero contribution) passes through
     * unscaled: the scale is about pressure, and a harsher zone should not
     * also make its reliefs bigger.
     */
    int scaleOmen(int contribution) {
        if (contribution <= 0 || omenScale == 1.0) {
            return contribution;
        }
        return (int) Math.max(0, Math.round(contribution * omenScale));
    }

    // ---- parsing (pure) -------------------------------------------------------

    /**
     * Parses a theme file's {@code rules} block. Every field is optional and
     * defaults to {@link #DEFAULT}'s value; an unknown field, a floor kind the
     * planner does not build, or a value out of range throws, which rejects
     * the theme at load with the reason (the same way a missing processor
     * list does), so a typo never quietly becomes the default.
     */
    static ZoneRules fromJson(JsonObject obj) {
        for (String key : obj.keySet()) {
            if (!KNOWN_KEYS.contains(key)) {
                throw new IllegalArgumentException("rules: unknown field '" + key + "'");
            }
        }
        List<String> sequence;
        if (obj.has("floor_sequence")) {
            if (!obj.get("floor_sequence").isJsonArray()) {
                throw new IllegalArgumentException("rules.floor_sequence must be an array of floor kinds");
            }
            JsonArray array = obj.getAsJsonArray("floor_sequence");
            sequence = new ArrayList<>();
            for (JsonElement kind : array) {
                sequence.add(floorKind(kind.getAsString(), "rules.floor_sequence"));
            }
            if (sequence.isEmpty()) {
                throw new IllegalArgumentException("rules.floor_sequence must not be empty");
            }
        } else {
            String kind = obj.has("floor_kind")
                    ? floorKind(obj.get("floor_kind").getAsString(), "rules.floor_kind")
                    : STANDARD;
            sequence = List.of(kind);
        }

        Capstone capstone = null;
        if (obj.has("capstone")) {
            String raw = obj.get("capstone").getAsString().trim().toLowerCase(Locale.ROOT);
            capstone = switch (raw) {
                case "none" -> Capstone.NONE;
                case "boss" -> Capstone.BOSS;
                default -> throw new IllegalArgumentException(
                        "rules.capstone must be 'none' or 'boss', not '" + raw + "'");
            };
        }

        double depthBonus = number(obj, "depth_bonus", DEFAULT.depthBonus, 0.0, MAX_BONUS_CHESTS);
        int omenBaseAfter = DEFAULT.omenBaseAfter;
        int omenBaseAmount = DEFAULT.omenBaseAmount;
        if (obj.has("omen_base")) {
            if (!obj.get("omen_base").isJsonObject()) {
                throw new IllegalArgumentException("rules.omen_base must be an object with 'after' and 'amount'");
            }
            JsonObject base = obj.getAsJsonObject("omen_base");
            for (String key : base.keySet()) {
                if (!key.equals("after") && !key.equals("amount")) {
                    throw new IllegalArgumentException("rules.omen_base: unknown field '" + key + "'");
                }
            }
            omenBaseAfter = base.has("after") ? integer(base, "after", 0, 0, 1000) : INTERVAL_LENGTH;
            omenBaseAmount = integer(base, "amount", DEFAULT.omenBaseAmount, 0, Omen.MAX_OMEN);
        }
        double omenScale = number(obj, "omen_scale", DEFAULT.omenScale, 0.0, 4.0);
        String lootRole = DEFAULT.lootRole;
        if (obj.has("loot_role")) {
            lootRole = obj.get("loot_role").getAsString().trim().toLowerCase(Locale.ROOT);
            if (!LOOT_ROLES.contains(lootRole)) {
                throw new IllegalArgumentException("rules.loot_role must be one of " + LOOT_ROLES
                        + ", not '" + lootRole + "'");
            }
        }
        int lootTierEvery = integer(obj, "loot_tier_every", DEFAULT.lootTierEvery, 0, 1000);
        int unlockLevel = integer(obj, "unlock_level", DEFAULT.unlockLevel, 1, 100000);
        double kitTopUpScale = number(obj, "kit_top_up_scale", DEFAULT.kitTopUpScale, 0.0, 10.0);
        return new ZoneRules(sequence, capstone, depthBonus, omenBaseAfter, omenBaseAmount,
                omenScale, lootRole, lootTierEvery, unlockLevel, kitTopUpScale);
    }

    private static final Set<String> KNOWN_KEYS = Set.of("floor_kind", "floor_sequence", "capstone",
            "depth_bonus", "omen_base", "omen_scale", "loot_role", "loot_tier_every", "unlock_level",
            "kit_top_up_scale");

    private static String floorKind(String raw, String field) {
        String kind = raw.trim().toLowerCase(Locale.ROOT);
        if (!FLOOR_KINDS.contains(kind)) {
            throw new IllegalArgumentException(field + ": floor kind '" + kind
                    + "' is not built yet; only " + FLOOR_KINDS + " is");
        }
        return kind;
    }

    private static double number(JsonObject obj, String key, double fallback, double min, double max) {
        if (!obj.has(key)) {
            return fallback;
        }
        double value;
        try {
            value = obj.get(key).getAsDouble();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("rules." + key + " must be a number");
        }
        if (Double.isNaN(value) || value < min || value > max) {
            throw new IllegalArgumentException("rules." + key + " must be between " + min + " and " + max
                    + ", not " + value);
        }
        return value;
    }

    private static int integer(JsonObject obj, String key, int fallback, int min, int max) {
        if (!obj.has(key)) {
            return fallback;
        }
        int value;
        try {
            value = obj.get(key).getAsInt();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("rules." + key + " must be a whole number");
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException("rules." + key + " must be between " + min + " and " + max
                    + ", not " + value);
        }
        return value;
    }
}
