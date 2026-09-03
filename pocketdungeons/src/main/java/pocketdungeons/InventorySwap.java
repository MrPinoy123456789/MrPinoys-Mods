package pocketdungeons;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * M45 seam: the stash and swap pass (SITUATIONS_SPEC 11).
 *
 * <p>Both methods are deliberately empty. M46 fills them: a player entering a
 * dungeon has their own inventory stashed and is handed the run's bag, and a
 * player leaving gets it back. This milestone only lands the class and its
 * three call sites, so M46 never has to open {@link Instances}.
 *
 * <p>The three hooks, all registered in {@code Instances.register}:
 *
 * <ul>
 *   <li>{@link #reconcileAll(MinecraftServer)} from the end-of-tick lambda,
 *       after the join-recovery drain, which is the sweep that catches a player
 *       whose state drifted for any reason the edges below missed;</li>
 *   <li>{@link #reconcile(ServerPlayer)} from the join handler, for a player
 *       who logged out inside a run and logged back in;</li>
 *   <li>{@link #reconcile(ServerPlayer)} from
 *       {@code ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL}, which
 *       is the edge that matters: crossing into or out of the dungeon
 *       dimension is exactly when a swap is owed.</li>
 * </ul>
 */
final class InventorySwap {

    private InventorySwap() {}

    /**
     * The per-tick sweep. Empty until M46.
     *
     * <p>Runs every tick, so whatever M46 puts here has to gate itself on an
     * interval or on there being a live instance at all; the surrounding
     * end-of-tick lambda does not gate it.
     */
    static void reconcileAll(MinecraftServer server) {
        // M46 (spec 11): stash and swap.
    }

    /**
     * One player's stash state brought back in line with where they are. Empty
     * until M46.
     *
     * <p>Reached from a join and from a dimension change, so it has to be safe
     * to call for a player who owes nothing, and safe to call twice.
     */
    static void reconcile(ServerPlayer player) {
        // M46 (spec 11): stash and swap.
    }
}
