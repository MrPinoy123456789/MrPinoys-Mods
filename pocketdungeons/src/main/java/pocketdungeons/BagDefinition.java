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
 */
final class BagDefinition {

    final String id;
    final String label;
    final String blurb;
    final int order;
    final List<String> headline;
    final Set<String> tags;
    final String lootTable;

    BagDefinition(String id, String label, String blurb, int order,
                  List<String> headline, Set<String> tags, String lootTable) {
        this.id = id;
        this.label = label;
        this.blurb = blurb;
        this.order = order;
        this.headline = headline == null ? List.of() : List.copyOf(headline);
        this.tags = tags == null ? Set.of() : Set.copyOf(tags);
        this.lootTable = lootTable;
    }
}
