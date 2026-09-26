package pocketdungeons;

import java.util.List;
import java.util.Set;

/**
 * M70: one data-driven bag definition. Replaces the {@code Bags} enum
 * constant: a definition carries its namespaced id, its label and blurb, its
 * stable picker order, the three headline items the door's frames show, the
 * situation tags the generator seeds its availability pass from, and the
 * namespaced loot table id the bag rolls from.
 *
 * <p>Pure Java (no Minecraft imports), so {@link BagSelectionTest} and its
 * plain-{@code javac} test can build fixtures without the server classpath.
 * The {@link BagManifest} loader constructs these from JSON; the picker and
 * the solvability seed receive a stable-ordered list of them and never touch
 * the enum.
 *
 * <h2>Order and the picker</h2>
 *
 * <p>{@link #order} is the stable picker order the enum's declaration order
 * used to provide. The manifest sorts definitions by order then id, so the
 * bag chest's button list reads the same on every run and a watcher's
 * in-place rewrite never churns a label. A third-party definition declares
 * its own order; ties break by id so two packs cannot disagree on the order
 * of equal-order bags.
 *
 * <h2>Loot table identity</h2>
 *
 * <p>{@link #lootTable} is the namespaced loot table id this bag rolls from.
 * The built-in bags carry {@code pocketdungeons:bags/<name>}, matching the
 * pre-M70 path the enum derived; a third-party bag names its own table in its
 * own namespace. The id is validated against the server's reloadable
 * registries at load, the same way an affix's {@code bonus_tool_pool} is.
 *
 * <h2>Capability tags</h2>
 *
 * <p>{@link #tags} is the situation-tag set this bag seeds the solvability
 * pass with. The vocabulary is closed ({@link SituationTags}); a tag outside
 * it is rejected at load with the bag and the offending tag named, the same
 * gate a room's {@code provides} and {@code requires} pass through.
 *
 * <h2>Kit baseline</h2>
 *
 * <p>{@link #kitBaseline} is the deterministic kit a safe visit tops up
 * toward ({@link KitTopUp}): an item, a count, whether it is a durability
 * item (replaced when missing, never repaired), and optionally the item a
 * used one turns into (a water bucket empties into a bucket, which the top-up
 * refills rather than minting a second bucket). It is declared in the bag's
 * JSON rather than read off the loot table, because a table is allowed to be
 * random and a top-up target is not; the built-in baselines are exactly their
 * tables' guaranteed entries, and {@code PackValidator} checks that a
 * baseline never asks for more than one roll of the table gives. An empty
 * baseline means the bag is never topped up.
 */
final class BagDefinition {

    /**
     * One line of a kit baseline.
     *
     * @param item        namespaced item id
     * @param count       how many the kit holds, at least 1
     * @param durability  a tool or other item whose durability is the economy:
     *                    only a missing one is replaced, at the calmest band,
     *                    and a damaged one is never repaired
     * @param emptiesInto the item a used one leaves behind, or {@code null}.
     *                    The top-up turns a held empty back into this item
     *                    instead of minting a new one alongside it.
     */
    record KitItem(String item, int count, boolean durability, String emptiesInto) {}

    final String id;
    final String label;
    final String blurb;
    final int order;
    final List<String> headline;
    final Set<String> tags;
    final String lootTable;
    final List<KitItem> kitBaseline;

    BagDefinition(String id, String label, String blurb, int order,
                  List<String> headline, Set<String> tags, String lootTable) {
        this(id, label, blurb, order, headline, tags, lootTable, List.of());
    }

    BagDefinition(String id, String label, String blurb, int order,
                  List<String> headline, Set<String> tags, String lootTable,
                  List<KitItem> kitBaseline) {
        this.id = id;
        this.label = label;
        this.blurb = blurb;
        this.order = order;
        this.headline = headline == null ? List.of() : List.copyOf(headline);
        this.tags = tags == null ? Set.of() : Set.copyOf(tags);
        this.lootTable = lootTable;
        this.kitBaseline = kitBaseline == null ? List.of() : List.copyOf(kitBaseline);
    }
}
