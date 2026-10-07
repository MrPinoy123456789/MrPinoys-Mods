package pocketdungeons.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pocketdungeons.PocketDungeonsMod;

/**
 * Dungeon structure W7b: the Act 4 capstone's Wither never breaks a block in the dungeon dimension.
 *
 * <p>{@code ServerExplosionMixin} already stops every blast block in a dungeon cell, which covers
 * the Wither's spawn blast and its skulls. The Wither also has a second, non explosive way of
 * wrecking a room: after it is hurt it breaks every block in a box around its body
 * ({@code destroyBlocksTick}, through {@code destroyBlock}), which would carve the boss room's
 * decor and shell. This clears that countdown at the head of every AI step, so the count never
 * reaches the break.
 */
@Mixin(WitherBoss.class)
public abstract class WitherBossMixin {

    @Shadow
    private int destroyBlocksTick;

    @Inject(method = "customServerAiStep", at = @At("HEAD"))
    private void pocketdungeons$noBlockBreaking(ServerLevel level, CallbackInfo ci) {
        if (level.dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
            this.destroyBlocksTick = 0;
        }
    }
}
