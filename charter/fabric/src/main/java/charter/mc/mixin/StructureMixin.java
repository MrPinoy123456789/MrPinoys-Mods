package charter.mc.mixin;

import charter.api.CharterAreas;
import charter.mc.CharterMod;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/**
 * Mixin 12: structures that would generate on kingdom land. A structure start whose bounding box touches any chunk a
 * keep or outpost holds is dropped before it is placed, so dungeons, villages and the rest never appear inside a
 * kingdom. It reads the public land map ({@link CharterAreas}), which is safe on worldgen threads, and only affects
 * chunks generated after the land was held; anything already generated stays as it is. The
 * {@code structuresInKingdoms} knob turns it off.
 */
@Mixin(Structure.class)
public abstract class StructureMixin {

    @Inject(method = "generate", at = @At("RETURN"), cancellable = true)
    private void charter$keepKingdomsClear(Holder<Structure> structure, ResourceKey<Level> dimension, RegistryAccess registries,
                                           ChunkGenerator generator, BiomeSource biomes, net.minecraft.world.level.biome.Climate.Sampler sampler, RandomState random,
                                           StructureTemplateManager templates, long seed, ChunkPos pos, int references,
                                           LevelHeightAccessor height, Predicate<Holder<Biome>> validBiome,
                                           CallbackInfoReturnable<StructureStart> cir) {
        StructureStart start = cir.getReturnValue();
        if (start == null || !start.isValid()) return;
        var knobs = CharterMod.knobs();
        if (knobs == null || knobs.structuresInKingdoms) return;
        BoundingBox box = start.getBoundingBox();
        if (CharterAreas.intersects(dimension.identifier().toString(), box.minX(), box.minZ(), box.maxX(), box.maxZ())) {
            cir.setReturnValue(StructureStart.INVALID_START);
        }
    }
}
