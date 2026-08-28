package thingy.api;

import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.Optional;

/**
 * The lookup surface other mods compile against.
 *
 * <p>Consume it with {@code modCompileOnly}, not {@code modImplementation}. The
 * real jar supplies the classes at runtime; when it is absent, {@link #get()}
 * returns empty and the calling mod carries on without it.
 *
 * <p>Guard the first API class reference with
 * {@code FabricLoader.isModLoaded("thingy")}: a bare {@code catch (Exception)}
 * around a call site does not save you from {@code NoClassDefFoundError}, which
 * is thrown at class-verification time, not at the call (PLAN.md Phase 1, hard
 * constraint 4; the trap is documented against {@code WondrousShop}).
 *
 * <pre>{@code
 * VirtualItems.get()
 *     .flatMap(v -> v.byId("wondrous:flying_boots"))
 *     .ifPresentOrElse(
 *         item -> Give.giveOrDrop(player, item.createStack()),
 *         ()   -> LOG.warn("Thingy not installed"));
 * }</pre>
 */
public interface VirtualItems {

    /**
     * The registry, if Thingy is installed and has finished initialising.
     *
     * <p>Returns empty rather than throwing, including when called from a mod
     * that loaded before this one: a caller gets nothing rather than a
     * half-built registry.
     */
    static Optional<VirtualItems> get() {
        return Optional.ofNullable(Holder.INSTANCE);
    }

    /** Look up by namespaced id. Empty for an unknown id: typos in config are not fatal. */
    Optional<VirtualItem> byId(String id);

    /** Every item, in declaration order. */
    Collection<VirtualItem> all();

    /** True if this stack is any virtual item, in any registered namespace. */
    boolean isVirtual(ItemStack stack);

    /** The namespaced id of this stack, if it is a virtual item. */
    Optional<String> idOf(ItemStack stack);

    /**
     * Set once during the mod's own initialisation. Not public API; it is only
     * public because Java has no other way for the mod module to reach it.
     */
    final class Holder {
        private static volatile VirtualItems INSTANCE;

        private Holder() {}

        public static void set(VirtualItems instance) {
            INSTANCE = instance;
        }
    }
}
