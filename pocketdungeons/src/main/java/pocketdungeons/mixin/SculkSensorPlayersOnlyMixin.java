package pocketdungeons.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gameevent.GameEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pocketdungeons.PocketDungeonsMod;

/**
 * Dungeon sculk sensors only hear players. A mob standing or walking in a room
 * (including the mobs an omen wave spawns) must not set a sensor off, or a
 * wave feeds the next one, and a crouching player in a room with a mob would
 * be blamed for the mob's footsteps. A vibration counts when its source is a
 * player, or a projectile a player shot. Everywhere outside the dungeon
 * dimension vanilla behaviour is untouched. The pulse count the Ancient City
 * and the Warden read comes from the sensors' own activations, so this one
 * filter covers both.
 */
@Mixin(targets = "net.minecraft.world.level.block.entity.SculkSensorBlockEntity$VibrationUser")
public abstract class SculkSensorPlayersOnlyMixin {

    @Inject(method = "canReceiveVibration", at = @At("HEAD"), cancellable = true)
    private void pocketdungeons$onlyPlayersAreHeard(ServerLevel level, BlockPos pos,
                                                    Holder<GameEvent> event, GameEvent.Context context,
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (!level.dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
            return;
        }
        Entity source = context.sourceEntity();
        if (source instanceof ServerPlayer) {
            return;
        }
        if (source instanceof Projectile projectile && projectile.getOwner() instanceof ServerPlayer) {
            return;
        }
        cir.setReturnValue(false);
    }
}
