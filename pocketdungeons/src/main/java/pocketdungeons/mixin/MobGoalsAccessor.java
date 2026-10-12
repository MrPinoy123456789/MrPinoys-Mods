package pocketdungeons.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes a mob's goal selectors so content can turn a placid creature hostile
 * (the Cow Pits' herd when Specimen 0 wakes). Accessors only; nothing is injected.
 */
@Mixin(Mob.class)
public interface MobGoalsAccessor {

    @Accessor("goalSelector")
    GoalSelector pocketdungeons$goalSelector();

    @Accessor("targetSelector")
    GoalSelector pocketdungeons$targetSelector();
}
