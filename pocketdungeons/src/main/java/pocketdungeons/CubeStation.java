package pocketdungeons;

import net.minecraft.world.item.ItemStack;

/**
 * M17 markers, minus the station. J5/14d unregistered the Herobrine Cube for
 * the next test (its block is a plain beacon again) ahead of the J8 door
 * crafting redesign: no block dispatch, no picker, no extract or imbue
 * ritual. What remains is the pair of {@code custom_data.pocketdungeons}
 * markers the rest of the mod still reads: {@link SalvageStation} refuses
 * gear carrying either one, and {@link PowerListener} powers whatever an
 * already-imbued item says it has.
 *
 * <p>The {@code cube_recipe} files and the {@link CubeRecipe} keystone-tag
 * plumbing stay on disk and in the log for J8; only the interactive station
 * is gone.
 */
final class CubeStation {

    /** Where the extract reward id and the imbued power id both live, nested like every other marker in this mod. */
    static final String KEY_CUBE_REWARD = "cubeReward";
    static final String KEY_POWER = "power";

    private CubeStation() {}

    /** The reward id a rare item claims via {@code custom_data.pocketdungeons.cubeReward}, or {@code ""} if none. */
    static String rewardOf(ItemStack stack) {
        return StationSupport.readStringMarker(stack, KEY_CUBE_REWARD);
    }

    /** The power id an already-imbued item carries via {@code custom_data.pocketdungeons.power}, or {@code ""}. */
    static String powerOf(ItemStack stack) {
        return StationSupport.readStringMarker(stack, KEY_POWER);
    }
}
