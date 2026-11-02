package charter.mc;

import charter.id.OrgId;
import charter.id.PlayerId;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Optional;

/**
 * No friendly fire inside a kingdom: with {@code kingdomFriendlyFire} off, a hit from a member of the victim's own
 * kingdom (by hand, by projectile, by lit TNT or by a tamed pet) does no damage. Kingdoms are compared at the top, so
 * members of a kingdom's outposts and offices count as one people. Players with no kingdom fight as before.
 */
public final class FriendlyFire {

    private FriendlyFire() {
    }

    public static void register() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !refuses(entity, source));
    }

    /** Whether this hit is between two members of the same kingdom and the knob forbids it. */
    public static boolean refuses(LivingEntity victim, DamageSource source) {
        if (!(victim instanceof Player target) || !CharterServer.running()) return false;
        CharterRuntime rt = CharterServer.runtime();
        if (rt.knobs.kingdomFriendlyFire) return false;
        Player attacker = playerBehind(source.getEntity());
        if (attacker == null || attacker == target) return false;
        Optional<OrgId> own = kingdomOf(rt, target);
        return own.isPresent() && own.equals(kingdomOf(rt, attacker));
    }

    /** The player behind an entity: itself, the owner of an arrow or lit TNT, or the owner of a tamed wolf. */
    private static Player playerBehind(Entity entity) {
        if (entity instanceof Player p) return p;
        if (entity instanceof TraceableEntity t && t.getOwner() instanceof Player p) return p;
        if (entity instanceof OwnableEntity o && o.getOwner() instanceof Player p) return p;
        return null;
    }

    private static Optional<OrgId> kingdomOf(CharterRuntime rt, Player player) {
        return rt.state.membership(new PlayerId(player.getUUID())).map(m -> rt.state.root(m.org()));
    }
}
