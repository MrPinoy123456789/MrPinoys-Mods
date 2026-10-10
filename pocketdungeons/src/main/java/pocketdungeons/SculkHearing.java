package pocketdungeons;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.gameevent.GameEvent;

/**
 * The one rule behind both sculk sensor mixins ({@code SculkSensorPlayersOnlyMixin} and
 * {@code CalibratedSculkSensorPlayersOnlyMixin}): in the dungeon dimension a sensor hears only players, and the
 * projectiles a player shot. Everywhere else vanilla behaviour is untouched.
 */
public final class SculkHearing {

    private SculkHearing() {}

    /** True when a sensor in this level must ignore a vibration with this context. */
    public static boolean ignores(ServerLevel level, GameEvent.Context context) {
        if (!DungeonWorldRules.inDungeon(level)) {
            return false;
        }
        Entity source = context.sourceEntity();
        if (source instanceof ServerPlayer) {
            return false;
        }
        return !(source instanceof Projectile projectile && projectile.getOwner() instanceof ServerPlayer);
    }
}
