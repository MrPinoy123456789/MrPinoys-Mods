package pocketdungeons;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Affix set arithmetic: parsing, the level thresholds, the seeded pick, the
 * depletion multiplier and the keystone's name. No Minecraft imports -- same
 * discipline as {@link KeystoneMath}, {@link DoorMask} and
 * {@link DifficultyProfile}, and for the same reason: this is the part that is
 * easy to get subtly wrong and trivial to test with plain {@code javac}.
 *
 * <h2>Two questions, one mechanism</h2>
 *
 * <p><em>How many</em> affixes a key carries is one every 20 levels past a
 * level-5 start ({@link #seededCount}). <em>Which</em> ones fill those slots is seeded from
 * the key itself ({@link #seededFor}). M10 removes the third piece this section
 * used to describe: a door choice no longer adds an elective affix on top.
 * {@code Affix.Kind.ELECTIVE} is gone, and every affix left is seeded.
 *
 * <p>There is deliberately <strong>no weekly rotation</strong>
 * ({@code docs/MYTHIC_PLUS_RECONCILIATION.md} section 4). A wall-clock seed would make
 * the keystone's name drift under the instance watcher, which rewrites stale
 * remotes in place; seeding from the key keeps the name a pure function of
 * {@code (level, affixSet)}.
 *
 * <h2>Why the seeded affixes are not persisted</h2>
 *
 * <p>{@link DungeonLog} stores the <em>elective</em> affixes only. The seeded ones
 * are re-derived from {@code (owner, level)} on every read, which costs nothing
 * and buys two things: no codec field to migrate, and a depleted key that
 * correctly stops carrying affixes its new level no longer earns.
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
     * written before affixes stacked holds {@code "ominous"}, which reads back as
     * a one-element set, and {@code ""} reads back as an empty one. An unknown
     * word -- a renamed affix, a hand-edited save -- is dropped rather than
     * thrown on, because the alternative is a player who cannot log in.
     */
    static EnumSet<Affix> parse(String joined) {
        EnumSet<Affix> set = EnumSet.noneOf(Affix.class);
        if (joined == null || joined.isBlank()) {
            return set;
        }
        for (String part : joined.split(",")) {
            String name = part.trim();
            if (name.isEmpty()) {
                continue;
            }
            for (Affix affix : Affix.values()) {
                if (affix.name().equalsIgnoreCase(name)) {
                    set.add(affix);
                    break;
                }
            }
        }
        return set;
    }

    /** The inverse of {@link #parse}, in enum order so the string is stable. */
    static String join(Set<Affix> affixes) {
        StringBuilder out = new StringBuilder();
        for (Affix affix : ordered(affixes)) {
            if (!out.isEmpty()) {
                out.append(",");
            }
            out.append(affix.name().toLowerCase());
        }
        return out.toString();
    }

    /** The set in enum declaration order -- the only order anything renders in. */
    static List<Affix> ordered(Set<Affix> affixes) {
        List<Affix> out = new ArrayList<>();
        if (affixes == null || affixes.isEmpty()) {
            return out;
        }
        for (Affix affix : Affix.values()) {
            if (affixes.contains(affix)) {
                out.add(affix);
            }
        }
        return out;
    }

    // ---- thresholds ---------------------------------------------------------

    /**
     * How many seeded affixes a key of this level carries: {@code 0} below 5,
     * {@code 1} at 5-24, {@code 2} at 25-44, and one more every 20 levels after
     * that, reaching {@code 5} out of the {@code Kind.SEEDED} pool at level 85.
     *
     * <p>Monotonic and uncapped here: {@link #seededFor} already clamps to the
     * pool's own size, so a cap raised past what the pool can fill just means
     * every seeded affix is in play, never an exception thrown.
     */
    static int seededCount(int level) {
        return (Math.max(0, level) + (LEVELS_PER_AFFIX - FIRST_AFFIX_LEVEL)) / LEVELS_PER_AFFIX;
    }

    /**
     * The seeded affixes for one player's key at one level.
     *
     * <p>Takes {@code owner} and {@code level} rather than a seed so that no caller
     * can feed it run randomness by accident: the answer has to be the same every
     * time the watcher asks, and the layout's seed is freshly rolled per run.
     */
    static EnumSet<Affix> seededFor(UUID owner, int level) {
        EnumSet<Affix> picked = EnumSet.noneOf(Affix.class);
        int count = seededCount(level);
        if (count <= 0) {
            return picked;
        }
        List<Affix> pool = new ArrayList<>();
        for (Affix affix : Affix.values()) {
            if (affix.kind == Affix.Kind.SEEDED && level >= affix.minLevel) {
                pool.add(affix);
            }
        }
        Collections.shuffle(pool, new Random(seed(owner, level)));
        for (int i = 0; i < Math.min(count, pool.size()); i++) {
            picked.add(pool.get(i));
        }
        return picked;
    }

    /**
     * Everything riding on a key: what the player opted into, plus what the level
     * hands them. This is what every consumer actually wants.
     */
    static EnumSet<Affix> effective(UUID owner, int level, Set<Affix> elective) {
        EnumSet<Affix> set = seededFor(owner, level);
        if (elective != null) {
            set.addAll(elective);
        }
        return set;
    }

    /**
     * Only the affixes a player chose, which is all {@link DungeonLog} stores.
     *
     * @deprecated M10 removes {@code Affix.Kind.ELECTIVE}: nothing is chosen at a
     * door any more, so this always returns the empty set. Kept, rather than
     * deleted or inlined, because every call site ({@link DungeonLog#setKeystone},
     * {@code Keystones.grantOffer}, {@code Keystones.returnTo}) still reads as "the
     * part of the set that gets persisted", and a future elective affix (if one
     * ever ships again) has exactly one method to change back.
     */
    @Deprecated
    static EnumSet<Affix> elective(Set<Affix> affixes) {
        return EnumSet.noneOf(Affix.class);
    }

    /**
     * A stable, well-spread seed for {@code (owner, level)}.
     *
     * <p>{@code java.util.Random} correlates badly on low-entropy seeds and the
     * pick here is a shuffle of five elements, so the UUID's two halves and the
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
     * A level-20 key must not shed most of a ladder on one bad night: loss aversion
     * already runs at roughly twice the felt weight of an equivalent gain, so a
     * doubled depletion is felt as roughly quadrupled, and a product of two
     * doubling affixes would be unrecoverable in an evening.
     */
    static int depletionMultiplier(Set<Affix> affixes) {
        int max = 1;
        if (affixes != null) {
            for (Affix affix : affixes) {
                max = Math.max(max, affix.depletionMultiplier);
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
     * <p>Kamu Totems' <em>convention</em>, with entirely separate words -- same
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
     * {@code <intensifier> <affix> Keystone [<level>]}, with any remaining affixes
     * in a bracketed subtitle -- the {@code <title> [<subtitle>]} shape
     * {@code BossNames.build} uses next door.
     *
     * <p>A pure function of its arguments, with no randomness anywhere in it. The
     * watcher rewrites remotes in place, so a label that rolled anything would
     * churn on every reconciliation.
     */
    static String name(int level, Set<Affix> affixes) {
        String head = intensifier(level);
        List<Affix> ordered = ordered(affixes);
        if (ordered.isEmpty()) {
            return head + " Keystone [" + level + "]";
        }
        String title = head + " " + ordered.get(0).label + " Keystone [" + level + "]";
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
