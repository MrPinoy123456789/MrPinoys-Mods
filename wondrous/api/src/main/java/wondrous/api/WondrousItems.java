package wondrous.api;

import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.Optional;

/**
 * The lookup surface other mods compile against.
 *
 * <p>Consume it with {@code modCompileOnly}, not {@code modImplementation}. The
 * real jar supplies the classes at runtime; when it is absent, {@link #get()}
 * returns empty and the calling mod carries on without it. That is the whole
 * reason these are four separate jars.
 *
 * <pre>{@code
 * WondrousItems.get()
 *     .flatMap(w -> w.byId("flying_boots"))
 *     .ifPresentOrElse(
 *         item -> WondrousGive.giveOrDrop(player, item.createStack()),
 *         ()   -> LOG.warn("Wondrous Items not installed"));
 * }</pre>
 */
public interface WondrousItems {

    /**
     * The registry, if Wondrous Items is installed and has finished initialising.
     *
     * <p>Returns empty rather than throwing, including when called from a mod that
     * loaded before this one -- a caller gets nothing rather than a half-built
     * registry.
     */
    static Optional<WondrousItems> get() {
        return Optional.ofNullable(Holder.INSTANCE);
    }

    /** Look up by id. Empty for an unknown id -- typos in config are not fatal. */
    Optional<WondrousItem> byId(String id);

    /** Every item, in declaration order. */
    Collection<WondrousItem> all();

    /** True if this stack is any wondrous item. */
    default boolean isWondrous(ItemStack stack) {
        return WondrousTag.read(stack).isPresent();
    }

    /** The id of this stack, if it is a wondrous item. */
    default Optional<String> idOf(ItemStack stack) {
        return WondrousTag.read(stack);
    }

    /**
     * Set once during the mod's own initialisation. Not public API -- it is only
     * public because Java has no other way for the mod module to reach it.
     */
    final class Holder {
        private static volatile WondrousItems INSTANCE;

        private Holder() {}

        public static void set(WondrousItems instance) {
            INSTANCE = instance;
        }
    }
}
