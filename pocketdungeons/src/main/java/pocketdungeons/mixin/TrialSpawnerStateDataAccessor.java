package pocketdungeons.mixin;

import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerStateData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;
import java.util.UUID;

/**
 * PD-134 (playtest 2026-10-03-2): read access to the mobs a trial spawner is
 * still waiting on, so a mob a player tamed can be let go, and to the players
 * it rewards. Accessors only; nothing is injected.
 */
@Mixin(TrialSpawnerStateData.class)
public interface TrialSpawnerStateDataAccessor {

    @Accessor("currentMobs")
    Set<UUID> pocketdungeons$currentMobs();

    /**
     * Playtest 2026-10-03-2: the players the spawner ejects a reward for, one
     * each, so a party's spawner can be held to one key.
     */
    @Accessor("detectedPlayers")
    Set<UUID> pocketdungeons$detectedPlayers();
}
