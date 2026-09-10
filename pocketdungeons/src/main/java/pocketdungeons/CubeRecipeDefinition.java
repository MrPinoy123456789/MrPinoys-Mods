package pocketdungeons;

import java.util.List;

/**
 * M71: one data-driven Cube recipe definition. Replaces the {@link CubeRecipe}
 * enum constant: a definition carries its namespaced id, its confirmation
 * message, its catalyst predicate (an item id or an item tag id), its cost in
 * catalysts, its match priority, its keystone-level eligibility, and its
 * bounded {@link RecipeEffects}.
 *
 * <p>Pure Java (no Minecraft imports), so the headless tests can build
 * fixtures without the server classpath. The {@link CubeRecipeManifest}
 * loader constructs these from JSON; the Cube station's match path
 * iterates them in priority order and the resolver accumulates their
 * effects into one {@link RunRecipePlan}.
 *
 * <h2>Catalyst predicate</h2>
 *
 * <p>Exactly one of {@link #catalystItem} and {@link #catalystTag} is non-null.
 * A recipe that names an item id matches that item; a recipe that names an
 * item tag id matches any item in that tag. The match path rejects an
 * ambiguous match (two definitions matching the same off-hand stack) rather
 * than silently picking one, the deterministic rejection the schema set
 * promises in place of last-file-wins.
 *
 * <h2>Priority and match order</h2>
 *
 * <p>{@link #priority} is the order the match path checks definitions in:
 * lower numbers first. Two definitions with the same priority that both
 * match the same stack is an ambiguous match and is rejected. A
 * third-party definition declares its own priority; the built-ins spread
 * theirs so a foreign catalyst never collides with a shipped one.
 *
 * <h2>Eligibility</h2>
 *
 * <p>{@link #minLevel} is the keystone level a player must hold before the
 * recipe matches. The Cube station's own unlock level still gates the
 * station as a whole; this is the per-recipe gate, so a recipe that would
 * waste a new player's first catalyst refuses before consuming it.
 *
 * <h2>Discovery</h2>
 *
 * <p>A recipe is discovered the first time a player successfully applies it
 * at the Cube. The discovery is personal: it is recorded in the player's
 * {@link RecipeDiscovery} and never broadcast. A recipe the player has not
 * discovered is never listed, never browsed, never auto-completed; the
 * discovery floor (M71 step 3) guarantees a catalyst and a terse
 * opportunity, not a catalogue.
 */
final class CubeRecipeDefinition {

    final String id;
    final String confirmation;
    final String catalystItem;
    final String catalystTag;
    final int cost;
    final int priority;
    final int minLevel;
    final RecipeEffects effects;

    CubeRecipeDefinition(String id, String confirmation,
                         String catalystItem, String catalystTag, int cost,
                         int priority, int minLevel, RecipeEffects effects) {
        this.id = id;
        this.confirmation = confirmation == null ? "" : confirmation;
        this.catalystItem = catalystItem;
        this.catalystTag = catalystTag;
        this.cost = Math.max(1, cost);
        this.priority = priority;
        this.minLevel = Math.max(0, minLevel);
        this.effects = effects == null ? RecipeEffects.none() : effects;
    }
}
