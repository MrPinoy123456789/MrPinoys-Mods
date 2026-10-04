package pocketdungeons.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pocketdungeons.DungeonTools;
import pocketdungeons.PocketDungeonsMod;
import pocketdungeons.RubbleOrdeal;


import java.util.ArrayList;
import java.util.List;

/**
 * (M48) Protects dungeon cells from explosion block damage.
 *
 * <p>Player-initiated breaks are already protected by {@code RoomProtection}'s
 * {@code PlayerBlockBreakEvents.BEFORE} handler, but explosions do not go
 * through that event. Without this mixin, a TNT blast inside a dungeon cell
 * would blow the walls, floor and room mechanisms open, bypassing the shell
 * protection the dungeon is built on.
 *
 * <p>Injects at the return of {@link ServerExplosion#calculateExplodedPositions}
 * and filters positions based on the explosion source:
 * <ul>
 * <li><strong>Inside a dungeon cell</strong>: no block is destroyed by any
 * explosion. Entity damage still applies, but pressure plates, chests,
 * doors, spawners and other mechanisms stay intact. The only exception is
 * a blast that reaches rubble, which is handled first by
 * {@link RubbleOrdeal#blast} and does no other damage.</li>
 * <li><strong>Outside cells</strong>: shell and furniture blocks are always
 * protected, so explosions cannot breach the safe room, staging room or
 * overworld builds.</li>
 * </ul>
 *
 * <p>Sapper TNT and other player TNT therefore cannot break room mechanisms;
 * it can only trigger rubble clears. The explosive affix uses a separate
 * fake TNT path that also breaks no blocks.
 *
 * <p>After filtering, any destroyed position is removed from the
 * {@code playerPlaced} tracking via {@link DungeonTools#forgetPlayerPlacement},
 * so a natural block that later appears at the same position is not wrongly
 * exempt from the tool rule.
 */
@Mixin(ServerExplosion.class)
public class ServerExplosionMixin {

    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Entity source;
    @Shadow @Final private Vec3 center;
    @Shadow @Final private float radius;

    /** Set when this explosion reached rubble: it then breaks nothing and hurts nobody. */
    @Unique private boolean pocketdungeons$rubbleBlast;

    @Inject(method = "calculateExplodedPositions", at = @At("RETURN"), cancellable = true)
    private void pocketdungeons$filterExplosion(CallbackInfoReturnable<List<BlockPos>> cir) {
        if (!level.dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
            return;
        }
        // A blast that reaches a rubble doorway clears it (RubbleOrdeal) and
        // does nothing else: no blocks, and hurtEntities below is skipped.
        // Checked before the empty-list return, since a creeper in open air
        // may break nothing at all and must still count.
        if (RubbleOrdeal.blast(level, center, radius)) {
            pocketdungeons$rubbleBlast = true;
            cir.setReturnValue(new ArrayList<>());
            return;
        }
        List<BlockPos> original = cir.getReturnValue();
        if (original.isEmpty()) {
            return;
        }
        List<BlockPos> filtered = new ArrayList<>(original.size());
        for (BlockPos pos : original) {
            BlockPos immutable = pos.immutable();
            if (DungeonTools.isInsideDungeonCell(immutable)) {
                // Inside a dungeon cell: no block is destroyed by any explosion.
                // Rubble clears are handled above by RubbleOrdeal.blast.
                continue;
            }
            if (DungeonTools.isShellProtected(level, immutable)) {
                // Room shell and furniture: always protected, even from TNT.
                continue;
            }
            filtered.add(pos);
            // Clean up placement tracking for any block that will be destroyed.
            DungeonTools.forgetPlayerPlacement(immutable);
        }
        cir.setReturnValue(filtered);
    }

    /** No damage and no knockback from a blast that cleared rubble. */
    @Inject(method = "hurtEntities", at = @At("HEAD"), cancellable = true)
    private void pocketdungeons$spareRubbleBlast(CallbackInfo ci) {
        if (pocketdungeons$rubbleBlast) {
            ci.cancel();
        }
    }
}
