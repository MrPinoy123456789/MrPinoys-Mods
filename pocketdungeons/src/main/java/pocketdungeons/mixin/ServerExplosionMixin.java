package pocketdungeons.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pocketdungeons.DungeonTools;
import pocketdungeons.PocketDungeonsMod;

import java.util.ArrayList;
import java.util.List;

/**
 * (M48) Protects the dungeon's shell blocks from TNT and other explosions,
 * and restricts which explosions can destroy interior blocks at all.
 *
 * <p>Player-initiated breaks are already protected by {@code RoomProtection}'s
 * {@code PlayerBlockBreakEvents.BEFORE} handler, but explosions do not go
 * through that event. Without this mixin, a TNT blast inside a dungeon cell
 * would blow the walls and floor open, bypassing the shell protection the
 * dungeon is built on.
 *
 * <p>Injects at the return of {@link ServerExplosion#calculateExplodedPositions}
 * and filters positions based on the explosion source:
 * <ul>
 * <li><strong>TNT</strong> (primed TNT and TNT minecarts): destroys interior
 * blocks, but shell and furniture blocks are filtered out. This is the "TNT
 * bypasses tool, not shell" rule: a TNT blast clears a path through the
 * interior but cannot breach the cell envelope.</li>
 * <li><strong>All other explosions</strong> (creeper, wither, end crystal,
 * bed, respawn anchor, ghast fireball): no block destruction inside dungeon
 * cells at all. Entity damage is unaffected; only block breaking is blocked.
 * This prevents creepers from becoming a tool-free mining shortcut.</li>
 * </ul>
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

    @Inject(method = "calculateExplodedPositions", at = @At("RETURN"), cancellable = true)
    private void pocketdungeons$filterExplosion(CallbackInfoReturnable<List<BlockPos>> cir) {
        if (!level.dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
            return;
        }
        List<BlockPos> original = cir.getReturnValue();
        if (original.isEmpty()) {
            return;
        }
        boolean isTnt = DungeonTools.isTntExplosion(source);
        List<BlockPos> filtered = new ArrayList<>(original.size());
        for (BlockPos pos : original) {
            BlockPos immutable = pos.immutable();
            if (DungeonTools.isInsideDungeonCell(immutable)) {
                // Inside a dungeon cell: TNT destroys interior (non-shell)
                // blocks; non-TNT explosions destroy nothing.
                if (!isTnt) {
                    continue;
                }
                if (DungeonTools.isShellProtected(level, immutable)) {
                    continue;
                }
            } else if (DungeonTools.isShellProtected(level, immutable)) {
                // Room shell and furniture: always protected, even from TNT.
                continue;
            }
            filtered.add(pos);
            // Clean up placement tracking for any block that will be destroyed.
            DungeonTools.forgetPlayerPlacement(immutable);
        }
        cir.setReturnValue(filtered);
    }
}
