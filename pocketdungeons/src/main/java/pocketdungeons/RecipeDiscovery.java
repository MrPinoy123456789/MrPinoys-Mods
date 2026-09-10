package pocketdungeons;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * M71: one player's personal Cube recipe discovery state. A recipe is
 * discovered the first time a player successfully applies it at the Cube;
 * the discovery is personal, never broadcast, and never browsed. A recipe
 * the player has not discovered is never listed, never auto-completed, and
 * never shown in any catalogue, because no catalogue exists
 * (VISION 5.4: "you can see the items, not the recipe").
 *
 * <p>Two sets and one flag, all never-shrinking, the same shape as
 * {@link DungeonLog.Entry#extractedPowers()} and
 * {@link DungeonLog.Entry#unlockedShells()}:
 * <ul>
 *   <li><strong>{@link #discoveredRecipes}</strong>: every recipe id the
 *       player has successfully applied at the Cube. A recipe is recorded
 *       only on success, never on a refused or cancelled attempt, so a
 *       failed experiment does not hand the player a discovery they never
 *       earned.</li>
 *   <li><strong>{@link #ingredientsEncountered}</strong>: every catalyst
 *       item id the player has held at the Cube. This is the discovery
 *       floor's ingredient surface: the dungeon log already records
 *       completed themes, and this set records which catalysts a player
 *       has touched, so the floor can guarantee a catalyst and a terse
 *       "try this at the Cube" opportunity without handing out a recipe
 *       list.</li>
 *   <li><strong>{@link #floorDelivered}</strong>: whether the discovery
 *       floor has already delivered a catalyst to this player. The floor
 *       fires once, on the first eligible safe visit; after that the flag
 *       stays set and the floor never fires again. A player who drops the
 *       catalyst or loses it gets no second floor; the floor is a
 *       dependable first experiment, not a supply line.</li>
 * </ul>
 *
 * <p>Held as a sidecar on {@link DungeonLog} (like task progress and
 * bounties) rather than on {@link DungeonLog.Entry}, because the codec
 * for {@code Entry} is already split across two 16-field groups and the
 * discovery state is read and written on the Cube interaction path, not
 * on every run completion.
 */
record RecipeDiscovery(Set<String> discoveredRecipes, Set<String> ingredientsEncountered,
                       boolean floorDelivered) {

    static final RecipeDiscovery NONE = new RecipeDiscovery(Set.of(), Set.of(), false);

    static final Codec<RecipeDiscovery> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.listOf().xmap(list -> (Set<String>) new HashSet<>(list), List::copyOf)
                    .optionalFieldOf("discovered_recipes", Set.of())
                    .forGetter(RecipeDiscovery::discoveredRecipes),
            Codec.STRING.listOf().xmap(list -> (Set<String>) new HashSet<>(list), List::copyOf)
                    .optionalFieldOf("ingredients_encountered", Set.of())
                    .forGetter(RecipeDiscovery::ingredientsEncountered),
            Codec.BOOL.optionalFieldOf("floor_delivered", false)
                    .forGetter(RecipeDiscovery::floorDelivered)
    ).apply(instance, RecipeDiscovery::new));

    RecipeDiscovery {
        discoveredRecipes = discoveredRecipes == null ? Set.of() : Set.copyOf(discoveredRecipes);
        ingredientsEncountered = ingredientsEncountered == null ? Set.of()
                : Set.copyOf(ingredientsEncountered);
    }

    /** Whether the player has discovered the given recipe id. */
    boolean hasDiscovered(String recipeId) {
        return recipeId != null && discoveredRecipes.contains(recipeId);
    }

    /** Returns a copy with {@code recipeId} added to the discovered set. */
    RecipeDiscovery withDiscovered(String recipeId) {
        if (recipeId == null || discoveredRecipes.contains(recipeId)) {
            return this;
        }
        Set<String> next = new HashSet<>(discoveredRecipes);
        next.add(recipeId);
        return new RecipeDiscovery(Set.copyOf(next), ingredientsEncountered, floorDelivered);
    }

    /** Returns a copy with {@code catalystId} added to the encountered set. */
    RecipeDiscovery withIngredient(String catalystId) {
        if (catalystId == null || ingredientsEncountered.contains(catalystId)) {
            return this;
        }
        Set<String> next = new HashSet<>(ingredientsEncountered);
        next.add(catalystId);
        return new RecipeDiscovery(discoveredRecipes, Set.copyOf(next), floorDelivered);
    }

    /** Returns a copy with the floor-delivered flag set. */
    RecipeDiscovery withFloorDelivered() {
        if (floorDelivered) {
            return this;
        }
        return new RecipeDiscovery(discoveredRecipes, ingredientsEncountered, true);
    }
}
