package charter.mc;

import charter.id.PlayerId;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * Godmode for the staff: with {@code adminKingdomGodmode} on, a member of an admin kingdom (any rank, by real
 * membership, never by standing on its land) takes no damage at all, from players, mobs, falls, fire, lava, drowning or
 * anything else. Only damage that ignores invulnerability in vanilla gets through ({@code /kill} and falling out of the
 * world), so a stuck staffer can still respawn. Leaving or being taken out ends it at once.
 */
public final class AdminGodmode {

    private AdminGodmode() {
    }

    public static void register() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !shields(entity, source));
    }

    /** Whether this damage is refused because the victim is an admin kingdom member with godmode on. */
    public static boolean shields(LivingEntity victim, DamageSource source) {
        if (!(victim instanceof ServerPlayer player) || !CharterServer.running()) return false;
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
        return protectedPlayer(player);
    }

    /** Whether godmode is on for this player now: the knob is on and they belong to an admin kingdom. */
    public static boolean protectedPlayer(ServerPlayer player) {
        CharterRuntime rt = CharterServer.runtime();
        if (!rt.knobs.adminKingdomGodmode) return false;
        return rt.state.membership(new PlayerId(player.getUUID()))
                .flatMap(m -> rt.state.org(rt.state.root(m.org())))
                .map(org -> org.adminKingdom && org.exists())
                .orElse(false);
    }
}
