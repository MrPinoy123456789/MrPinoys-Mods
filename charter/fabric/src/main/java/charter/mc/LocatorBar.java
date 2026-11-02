package charter.mc;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * The locator bar (the player markers on the experience bar) for admins only. The {@code locator_bar} game rule is world
 * wide, so with {@code locatorBarAdminsOnly} on, every player without {@code charter.locatorbar} gets a modifier that
 * takes their waypoint receive range to 0: they see nobody on the bar. Their transmit range is left alone, so admins
 * still see them. The modifier is transient (never saved with the player), refreshed on join, respawn, dimension change
 * and every {@link #EVERY_TICKS} ticks so a LuckPerms change shows without a relog, and removed when the knob is off.
 */
public final class LocatorBar {

    public static final int EVERY_TICKS = 60;
    private static final Identifier HIDDEN = Identifier.fromNamespaceAndPath("charter", "locator_bar_hidden");

    private LocatorBar() {
    }

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> applyTo(handler.player));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, fresh, alive) -> applyTo(fresh));
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) -> applyTo(player));
    }

    public static void apply(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) applyTo(player);
    }

    /** Hides the bar from this player, or gives it back, by the knob and their node. */
    public static void applyTo(ServerPlayer player) {
        if (!CharterServer.running()) return;
        AttributeInstance range = player.getAttribute(Attributes.WAYPOINT_RECEIVE_RANGE);
        if (range == null) return;
        boolean hide = CharterServer.runtime().knobs.locatorBarAdminsOnly && !CharterPerms.locatorBar(player);
        boolean hidden = range.getModifier(HIDDEN) != null;
        if (hide && !hidden) {
            // Times (1 - 1): whatever the base and other modifiers are, the range ends at 0.
            range.addTransientModifier(new AttributeModifier(HIDDEN, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        } else if (!hide && hidden) {
            range.removeModifier(HIDDEN);
        }
    }
}
