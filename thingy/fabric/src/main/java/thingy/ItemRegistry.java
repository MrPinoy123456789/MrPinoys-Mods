package thingy;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import thingy.api.VirtualItem;
import thingy.api.VirtualItems;
import thingy.api.VirtualTag;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns {@link Definitions#ALL} into the public API surface.
 *
 * <p>Two lookups, by two id shapes. {@link #byId} is the public
 * {@link VirtualItems} surface and takes the namespaced id
 * ({@code "wondrous:flying_boots"}), because a consumer may have several
 * namespaces to choose between once Phase 5 lands. {@link #defOf} is the
 * internal dispatch path (right-click handlers, area tools) and takes the bare
 * id, because that is what {@link VirtualTag#read} hands back for the
 * {@code wondrous} namespace: the whole point of shape preservation is that
 * the stamped value never changed.
 */
public final class ItemRegistry implements VirtualItems {

    private static final String NAMESPACE = "wondrous";

    private final Map<String, Impl> byNamespacedId = new LinkedHashMap<>();
    private final Map<String, Definitions.Def> byBareId = new LinkedHashMap<>();

    public ItemRegistry() {
        for (Definitions.Def def : Definitions.ALL) {
            byBareId.put(def.id(), def);
            byNamespacedId.put(namespaced(def.id()), new Impl(def));
        }
    }

    /** {@code "wondrous:" + bareId}, the id shape every consumer sees. */
    public static String namespaced(String bareId) {
        return NAMESPACE + ":" + bareId;
    }

    @Override
    public Optional<VirtualItem> byId(String id) {
        return Optional.ofNullable(id == null ? null : byNamespacedId.get(id))
                .map(impl -> impl);
    }

    @Override
    public Collection<VirtualItem> all() {
        return List.copyOf(byNamespacedId.values());
    }

    @Override
    public boolean isVirtual(ItemStack stack) {
        return VirtualTag.readAny(stack).isPresent();
    }

    @Override
    public Optional<String> idOf(ItemStack stack) {
        return VirtualTag.readAny(stack);
    }

    /** Bare ids in declaration order, for command suggestions. */
    public Collection<String> bareIds() {
        return List.copyOf(byBareId.keySet());
    }

    /** The definition behind a bare id, for the internal dispatch paths. */
    public Optional<Definitions.Def> defOf(String bareId) {
        return Optional.ofNullable(bareId == null ? null : byBareId.get(bareId));
    }

    /** The item behind a bare id, for command handling that already has one. */
    public Optional<VirtualItem> byBareId(String bareId) {
        return bareId == null ? Optional.empty() : byId(namespaced(bareId));
    }

    private record Impl(Definitions.Def def) implements VirtualItem {

        @Override
        public String id() {
            return namespaced(def.id());
        }

        @Override
        public Component displayName() {
            return def.name();
        }

        @Override
        public ItemStack createStack() {
            return createStack(1);
        }

        @Override
        public ItemStack createStack(int count) {
            return Definitions.createStack(def, count);
        }
    }
}
