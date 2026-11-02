package foundry.mc;

import foundry.core.mechanics.Trigger;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

/**
 * The on_swing trigger (left click). Minecraft has no item hook for swinging at air and Foundry uses no Mixins, so the
 * server watches each player's swing state: a swing that starts from rest, with a Foundry item in the main hand, is
 * a left click. A swing that came from another action is ignored: right click and block placing (the server swings
 * the arm for them), hitting an entity (that is on_hit_entity) and starting to mine a block.
 */
public final class Swings {
    /** A swing within this many ticks of a suppressing action is part of that action. */
    private static final long WINDOW = 2;

    private static final Map<UUID, Boolean> WAS_SWINGING = new HashMap<>();
    private static final Map<UUID, Long> SUPPRESSED_AT = new HashMap<>();

    private Swings() {}

    static void register() {
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (player instanceof ServerPlayer sp) attacked(sp);
            return InteractionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (player instanceof ServerPlayer sp) attacked(sp);
            return InteractionResult.PASS;
        });
    }

    /**
     * An attack that landed on a mob or a block. With a caster in hand (an item whose left click casts and that has no
     * hit of its own: the wands and staffs) the attack is the cast, so the bolt flies whatever the crosshair was on. A
     * controller or touch player's crosshair rests on the ground or locks onto a mob most of the time, so before this a
     * Bedrock Fae's left click almost never cast. Either way the swing that comes with it is not a second left click.
     */
    static void attacked(ServerPlayer player) {
        long now = player.level().getServer().getTickCount();
        boolean fresh = now - SUPPRESSED_AT.getOrDefault(player.getUUID(), Long.MIN_VALUE / 2) > WINDOW;
        suppress(player);
        if (fresh && player.getMainHandItem().getItem() instanceof FoundryItem item && item.castsOnAttack()) {
            item.fire(Trigger.ON_SWING, player, null);
        }
    }

    /** Marks the player's current swing as belonging to another action. */
    public static void suppress(ServerPlayer player) {
        SUPPRESSED_AT.put(player.getUUID(), (long) player.level().getServer().getTickCount());
    }

    public static void clear() {
        WAS_SWINGING.clear();
        SUPPRESSED_AT.clear();
    }

    static void tick(MinecraftServer server) {
        server.getPlayerList().getPlayers().forEach(p -> observe(p, server.getTickCount()));
        WAS_SWINGING.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        SUPPRESSED_AT.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    }

    /** Looks at one player's swing state this tick; fires on_swing when a left click just began. */
    public static void observe(ServerPlayer player, long now) {
        boolean swinging = player.isSwinging() && player.getCurrentSwing().hand() == InteractionHand.MAIN_HAND;
        boolean before = WAS_SWINGING.getOrDefault(player.getUUID(), false);
        WAS_SWINGING.put(player.getUUID(), swinging);
        if (!swinging || before) return;
        if (now - SUPPRESSED_AT.getOrDefault(player.getUUID(), Long.MIN_VALUE / 2) <= WINDOW) return;
        if (player.getMainHandItem().getItem() instanceof FoundryItem item) item.fire(Trigger.ON_SWING, player, null);
    }
}
