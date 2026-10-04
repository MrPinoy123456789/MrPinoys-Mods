package pocketdungeons;

import java.util.Set;

/**
 * M69: one data-driven affix definition. Replaces the {@code Affix} enum
 * constant: a definition carries its namespaced id, its label and blurb, its
 * stable render/seed order, its level eligibility and weight, its
 * incompatibilities, and its bounded {@link AffixEffects}.
 *
 * <p>Pure Java (no Minecraft imports), so {@link AffixMath} and its
 * plain-{@code javac} test can build fixtures without the server classpath.
 * The {@link AffixManifest} loader constructs these from JSON; the maths
 * receive a stable-ordered list of them and never touch the enum.
 *
 * <h2>Order and seeding</h2>
 *
 * <p>{@link #order} is the stable render and seed order the enum's declaration
 * order used to provide. The manifest sorts definitions by order then id, so
 * the keystone name stays a pure function of {@code (level, affixSet)} the
 * way it was before, and the watcher's in-place rewrite never churns a
 * label. A third-party definition declares its own order; ties break by id
 * so two packs cannot disagree on the order of equal-order affixes.
 *
 * <h2>Incompatibilities</h2>
 *
 * <p>{@link #incompatible} is the set of ids this affix cannot appear
 * alongside. The manifest validates the set is symmetric (if A lists B, B
 * lists A) at load, so a one-sided declaration is caught rather than
 * silently allowing the pair. The seeded pick skips an affix incompatible
 * with one already chosen, the way the enum-era code had no incompatibilities
 * but the data model now allows.
 */
final class AffixDefinition {

    final String id;
    final String label;
    final String blurb;
    /** A few letters for tight spaces such as the floor history board; the label when a file gives none. */
    final String shortName;
    final int order;
    final int minLevel;
    final int weight;
    final int depletionMultiplier;
    final Set<String> incompatible;
    final AffixEffects effects;

    AffixDefinition(String id, String label, String blurb, int order, int minLevel,
                   int weight, int depletionMultiplier, Set<String> incompatible,
                   AffixEffects effects) {
        this(id, label, label, blurb, order, minLevel, weight, depletionMultiplier, incompatible, effects);
    }

    AffixDefinition(String id, String label, String shortName, String blurb, int order, int minLevel,
                   int weight, int depletionMultiplier, Set<String> incompatible,
                   AffixEffects effects) {
        this.shortName = shortName == null || shortName.isBlank() ? label : shortName;
        this.id = id;
        this.label = label;
        this.blurb = blurb;
        this.order = order;
        this.minLevel = minLevel;
        this.weight = weight;
        this.depletionMultiplier = depletionMultiplier;
        this.incompatible = incompatible == null ? Set.of() : Set.copyOf(incompatible);
        this.effects = effects == null ? AffixEffects.none() : effects;
    }
}
