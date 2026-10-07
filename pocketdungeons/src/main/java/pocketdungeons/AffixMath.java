package pocketdungeons;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Affix set arithmetic: parsing, the level thresholds, the seeded pick, the
 * depletion multiplier and the keystone's name. No Minecraft imports, same
 * discipline as {@link KeystoneMath}, {@link DoorMask} and
 * {@link DifficultyProfile}, and for the same reason: this is the part that is
 * easy to get subtly wrong and trivial to test with plain {@code javac}.
 *
 * <h2>Two questions, one mechanism</h2>
 *
 * <p><em>How many</em> affixes a key carries is one every 20 levels past a
 * level-5 start ({@link #seededCount}). <em>Which</em> ones fill those slots is
 * seeded from the key itself ({@link #seededFor}). M10 removed the third piece
 * this section used to describe: a door choice no longer adds an elective
 * affix on top. Every affix is seeded.
 *
 * <p>There is deliberately <strong>no weekly rotation</strong>
 * ({@code docs/MYTHIC_PLUS_RECONCILIATION.md} section 4). A wall-clock seed
 * would make the keystone's name drift under the instance watcher, which
 * rewrites stale remotes in place; seeding from the key keeps the name a pure
 * function of {@code (level, affixSet)}.
 *
 * <h2>M69: data-driven definitions</h2>
 *
 * <p>Before M69 the affixes were a Java enum, and these methods iterated
 * {@code Affix.values()}. Now the affixes are data-driven
 * {@link AffixDefinition}s loaded by {@link AffixManifest}, and the maths
 * receive the stable-ordered definition list as a parameter. This keeps the
 * class import-free (the manifest needs the server; the maths do not) and
 * lets the plain-{@code javac} test build fixtures without a classpath.
 *
 * <p>Identity is a namespaced id string ({@code "pocketdungeons:ominous"}). A
 * legacy bare name ({@code "ominous"}, a pre-M69 save) is resolved to its id
 * by {@link AffixIds#resolve} on parse, so a save written before M69 loads
 * without a codec migration.
 *
 * <h2>Why the seeded affixes are not persisted</h2>
 *
 * <p>{@link DungeonLog} stores the <em>elective</em> affixes only. The seeded
 * ones are re-derived from {@code (owner, level)} on every read, which costs
 * nothing and buys two things: no codec field to migrate, and a depleted key
 * that correctly stops carrying affixes its new level no longer earns.
 */
final class AffixMath {

    /**
     * M10: one more seeded affix every 20 levels, replacing the fixed
     * {@code 5 / 11 / 17} thresholds. Those three left 83 of the cap-100 ladder
     * flat past level 17; a percentage curve keeps the count meaningful across
     * whatever the cap grows to next, and stays a pure, watcher-stable function
     * of level either way.
     *
     * <p>The offset keeps the old system's first threshold: a bare
     * {@code level / 20} would leave levels 1-19 with zero seeded affixes,
     * turning the entire early ladder featureless. {@code (level + 15) / 20}
     * lands the first affix at level 5, same as the old {@code FIRST}
     * threshold, then one more every 20 levels after that (25, 45, 65, 85).
     */
    private static final int LEVELS_PER_AFFIX = 20;
    private static final int FIRST_AFFIX_LEVEL = 5;

    private AffixMath() {}

    // ---- storage ------------------------------------------------------------

    /**
     * Parses the comma-joined form {@link DungeonLog} and the item tag store.
     *
     * <p>Lenient by design, and that is what makes M4 migration-free: a save
     * written before affixes stacked holds {@code "ominous"}, which reads back
     * as a one-element set, and {@code ""} reads back as an empty one. An
     * unknown word, a renamed affix, or a hand-edited save is dropped rather
     * than thrown on, because the alternative is a player who cannot log in.
     *
     * <p>M69: each token is resolved through {@link AffixIds#resolve}, so a
     * legacy bare name ({@code "ominous"}) and a namespaced id
     * ({@code "pocketdungeons:ominous"}) both parse to the same id. A
     * third-party id ({@code "theirpack:their_affix"}) parses as is; if its
     * definition is not loaded the id is still carried, the way an unknown
     * enum name was dropped before.
     */
    static Set<String> parse(String joined) {
        Set<String> set = new LinkedHashSet<>();
        if (joined == null || joined.isBlank()) {
            return set;
        }
        for (String part : joined.split(",")) {
            String resolved = AffixIds.resolve(part);
            if (resolved != null) {
                set.add(resolved);
            }
        }
        return set;
    }

    /**
     * The inverse of {@link #parse}, in stable order so the string is stable.
     * Emits the namespaced id for each affix; a pre-M69 save that held
     * {@code "ominous"} now round-trips through {@code "pocketdungeons:ominous"},
     * which {@link #parse} accepts back.
     */
    static String join(Set<String> affixes, List<AffixDefinition> all) {
        StringBuilder out = new StringBuilder();
        for (AffixDefinition def : ordered(affixes, all)) {
            if (!out.isEmpty()) {
                out.append(",");
            }
            out.append(def.id);
        }
        return out.toString();
    }

    /**
     * The definitions for the given ids, in stable render/seed order. The
     * only order anything renders in. An id with no loaded definition is
     * skipped, the way an unknown enum name was dropped on parse.
     */
    static List<AffixDefinition> ordered(Set<String> affixes, List<AffixDefinition> all) {
        List<AffixDefinition> out = new ArrayList<>();
        if (affixes == null || affixes.isEmpty() || all == null) {
            return out;
        }
        for (AffixDefinition def : all) {
            if (affixes.contains(def.id)) {
                out.add(def);
            }
        }
        return out;
    }

    // ---- thresholds ---------------------------------------------------------

    /**
     * How many seeded affixes a key of this level carries: {@code 0} below 5,
     * {@code 1} at 5-24, {@code 2} at 25-44, and one more every 20 levels after
     * that, reaching {@code 5} out of the seeded pool at level 85.
     *
     * <p>Monotonic and uncapped here: {@link #seededFor} already clamps to the
     * pool's own size, so a cap raised past what the pool can fill just means
     * every seeded affix is in play, never an exception thrown.
     */
    static int seededCount(int level) {
        return (Math.max(0, level) + (LEVELS_PER_AFFIX - FIRST_AFFIX_LEVEL)) / LEVELS_PER_AFFIX;
    }

    /**
     * The seeded affix ids for one player's key at one level.
     *
     * <p>Takes {@code owner} and {@code level} rather than a seed so that no
     * caller can feed it run randomness by accident: the answer has to be the
     * same every time the watcher asks, and the layout's seed is freshly
     * rolled per run.
     *
     * @param definitions the stable-ordered definition list (from
     *                    {@link AffixManifest#definitions()}); only definitions
     *                    with {@code minLevel <= level} enter the seeded pool
     */
    static Set<String> seededFor(UUID owner, int level, List<AffixDefinition> definitions) {
        return pick(owner, level, definitions, seed(owner, level));
    }

    /**
     * The same draw as {@link #seededFor(UUID, int, List)} from a different seed:
     * {@code salt} is mixed into {@link #seed}, so a door that repeats a floor can
     * deal its own set of the same size. Stable for one {@code (owner, level, salt)}.
     */
    static Set<String> seededFor(UUID owner, int level, List<AffixDefinition> definitions, long salt) {
        long mixed = seed(owner, level) + salt * 0x9E3779B97F4A7C15L;
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        return pick(owner, level, definitions, mixed ^ (mixed >>> 31));
    }

    private static Set<String> pick(UUID owner, int level, List<AffixDefinition> definitions, long seed) {
        Set<String> picked = new LinkedHashSet<>();
        int count = seededCount(level);
        if (count <= 0) {
            return picked;
        }
        List<AffixDefinition> pool = new ArrayList<>();
        if (definitions != null) {
            for (AffixDefinition def : definitions) {
                if (level >= def.minLevel) {
                    pool.add(def);
                }
            }
        }
        Collections.shuffle(pool, new Random(seed));
        for (int i = 0; i < Math.min(count, pool.size()); i++) {
            picked.add(pool.get(i).id);
        }
        return picked;
    }

    /**
     * Everything riding on a key: what the player opted into, plus what the
     * level hands them. This is what every consumer actually wants.
     */
    static Set<String> effective(UUID owner, int level, Set<String> elective,
                                 List<AffixDefinition> definitions) {
        Set<String> set = seededFor(owner, level, definitions);
        if (elective != null) {
            set.addAll(elective);
        }
        return set;
    }

    /**
     * Only the affixes a player chose, which is all {@link DungeonLog} stores.
     *
     * @deprecated M10 removes elective affixes: nothing is chosen at a door
     * any more, so this always returns the empty set. Kept, rather than
     * deleted or inlined, because every call site still reads as "the part
     * of the set that gets persisted", and a future elective affix (if one
     * ever ships again) has exactly one method to change back.
     */
    @Deprecated
    static Set<String> elective(Set<String> affixes) {
        return new LinkedHashSet<>();
    }

    /**
     * A stable, well-spread seed for {@code (owner, level)}.
     *
     * <p>{@code java.util.Random} correlates badly on low-entropy seeds and the
     * pick here is a shuffle of the pool, so the UUID's two halves and the
     * level go through a SplitMix64-style finaliser before they reach the
     * constructor rather than straight into it.
     */
    static long seed(UUID owner, int level) {
        long mixed = owner == null ? 0L
                : owner.getMostSignificantBits() * 31L + owner.getLeastSignificantBits();
        mixed = mixed * 0x9E3779B97F4A7C15L + level;
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        return mixed ^ (mixed >>> 31);
    }

    // ---- depletion ----------------------------------------------------------

    /**
     * What a failure costs, as a multiplier over the whole set.
     *
     * <p><strong>The max, capped at two. Never the sum, never the product.</strong>
     * A level-20 key must not shed most of a ladder on one bad night: loss
     * aversion already runs at roughly twice the felt weight of an equivalent
     * gain, so a doubled depletion is felt as roughly quadrupled, and a product
     * of two doubling affixes would be unrecoverable in an evening.
     */
    static int depletionMultiplier(Set<String> affixes, List<AffixDefinition> all) {
        int max = 1;
        if (affixes != null && all != null) {
            for (AffixDefinition def : all) {
                if (affixes.contains(def.id)) {
                    max = Math.max(max, def.depletionMultiplier);
                }
            }
        }
        return Math.min(2, max);
    }

    // ---- naming -------------------------------------------------------------

    /**
     * The intensifier band for a level: Baby 1-5, Lowkey 6-10, Highkey 11-15,
     * Menace 16-20, then eight ten-level bands from Unhinged (21-30) up to
     * Transcendent (91-100), M10's extension of the ladder past the old cap of
     * 25.
     *
     * <p>Kamu Totems' <em>convention</em>, with entirely separate words, same
     * machinery, no shared code and no shared vocabulary, per
     * ({@code kamutotems/INTEGRATION.md}'s stranger rule).
     */
    static String intensifier(int level) {
        if (level <= 5) {
            return "Baby";
        }
        if (level <= 10) {
            return "Lowkey";
        }
        if (level <= 15) {
            return "Highkey";
        }
        if (level <= 20) {
            return "Menace";
        }
        if (level <= 30) {
            return "Unhinged";
        }
        if (level <= 40) {
            return "Deranged";
        }
        if (level <= 50) {
            return "Unholy";
        }
        if (level <= 60) {
            return "Cursed";
        }
        if (level <= 70) {
            return "Forsaken";
        }
        if (level <= 80) {
            return "Abyssal";
        }
        if (level <= 90) {
            return "Apocalyptic";
        }
        return "Transcendent";
    }

    /**
     * The 1-12 ordinal of {@link #intensifier}'s band for a level, Baby=1
     * through Transcendent=12. Exists so callers that need to compare bands
     * (M26's diary drop) do not have to parse the display word back apart;
     * the thresholds are identical, just returned as a number instead of a
     * name.
     */
    static int intensifierBandIndex(int level) {
        if (level <= 5) {
            return 1;
        }
        if (level <= 10) {
            return 2;
        }
        if (level <= 15) {
            return 3;
        }
        if (level <= 20) {
            return 4;
        }
        if (level <= 30) {
            return 5;
        }
        if (level <= 40) {
            return 6;
        }
        if (level <= 50) {
            return 7;
        }
        if (level <= 60) {
            return 8;
        }
        if (level <= 70) {
            return 9;
        }
        if (level <= 80) {
            return 10;
        }
        if (level <= 90) {
            return 11;
        }
        return 12;
    }

    /**
     * {@code <intensifier> <affix> Keystone [<level>]}, with any remaining
     * affixes in a bracketed subtitle, the {@code <title> [<subtitle>]} shape
     * {@code BossNames.build} uses next door.
     *
     * <p>A pure function of its arguments, with no randomness anywhere in it.
     * The watcher rewrites remotes in place, so a label that rolled anything
     * would churn on every reconciliation.
     */
    static String name(int level, Set<String> affixes, List<AffixDefinition> all) {
        String head = intensifier(level);
        List<AffixDefinition> ordered = ordered(affixes, all);
        if (ordered.isEmpty()) {
            return head + " Compass [" + level + "]";
        }
        String title = head + " " + ordered.get(0).label + " Compass [" + level + "]";
        if (ordered.size() == 1) {
            return title;
        }
        StringBuilder subtitle = new StringBuilder();
        for (int i = 1; i < ordered.size(); i++) {
            if (i > 1) {
                subtitle.append(", ");
            }
            subtitle.append(ordered.get(i).label);
        }
        return title + " [" + subtitle + "]";
    }
}
