package pocketdungeons.mixin;

import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerStateData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;
import java.util.UUID;

/**
 * PD-134 (playtest 2026-10-03-2): read access to the mobs a trial spawner is
 * still waiting on, so a mob a player tamed can be let go. Accessor only;
 * nothing is injected.
 */
@Mixin(TrialSpawnerStateData.class)
public interface TrialSpawnerStateDataAccessor {

    @Accessor("currentMobs")
    Set<UUID> pocketdungeons$currentMobs();
}
